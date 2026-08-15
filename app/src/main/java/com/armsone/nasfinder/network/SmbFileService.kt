package com.armsone.nasfinder.network

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.io.InputStreamByteChunkProvider
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskEntry
import com.hierynomus.smbj.share.DiskShare
import com.armsone.nasfinder.model.RemoteConnection
import com.armsone.nasfinder.model.RemoteFileItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.EnumSet
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * SMB2/SMB3 implementation backed by hierynomus SMBJ.
 *
 * NasFinder paths use `/share/folder/file`. The first component is always the
 * SMB share and every operation is canonicalized below [RemoteConnection]'s
 * configured root before it reaches SMBJ. SMBJ core does not include the
 * SRVSVC `NetShareEnumAll` RPC, so a connection rooted at `/` can authenticate
 * but must be configured with `/share` before its contents can be enumerated.
 */
class SmbFileService(
    private val connection: RemoteConnection,
    private val password: String,
) : RemoteFileService {
    override val supportsRangeStreaming: Boolean = true
    private val configuredRoot = canonicalPath(connection.normalizedRootPath)

    override suspend fun testConnection(): Unit = withContext(Dispatchers.IO) {
        withSession { session ->
            if (configuredRoot != "/") {
                val location = split(configuredRoot)
                withShare(session, location.share) { share ->
                    if (location.relativePath.isEmpty()) {
                        share.list("")
                    } else {
                        check(share.folderExists(location.relativePath)) {
                            "설정한 SMB 시작 폴더를 찾을 수 없습니다."
                        }
                    }
                }
            }
        }
    }

    override suspend fun list(path: String): List<RemoteFileItem> = withContext(Dispatchers.IO) {
        val canonical = requireInsideRoot(path)
        if (canonical == "/") {
            throw RemoteServiceException.Unsupported(
                "SMBJ만으로 서버 공유 목록을 열거할 수 없습니다. 시작 위치를 /공유이름으로 설정하세요.",
            )
        }
        val location = split(canonical)
        withSession { session ->
            withShare(session, location.share) { share ->
                share.list(location.relativePath)
                    .asSequence()
                    .filterNot { it.fileName == "." || it.fileName == ".." }
                    .filterNot(::isReparsePoint)
                    .map { entry -> entry.toRemoteItem(canonical) }
                    .toList()
            }
        }
    }

    override suspend fun download(
        item: RemoteFileItem,
        destination: File,
        progress: (Long, Long) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        val source = requireMutableItemPath(item.path, allowMutation = false)
        require(!item.isDirectory) { "폴더는 파일로 다운로드할 수 없습니다." }
        val location = split(source)
        val operationContext = coroutineContext
        destination.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                throw IOException("다운로드 폴더를 만들 수 없습니다.")
            }
        }

        try {
            withSession { session ->
                withShare(session, location.share) { share ->
                    share.openFile(
                        location.relativePath,
                        EnumSet.of(AccessMask.FILE_READ_DATA, AccessMask.FILE_READ_ATTRIBUTES),
                        EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                        SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_OPEN,
                        EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
                    ).use { remote ->
                        destination.outputStream().buffered().use { output ->
                            val total = item.size.takeIf { it > 0 } ?: -1L
                            progress(0, total)
                            val buffer = ByteArray(DOWNLOAD_CHUNK_SIZE)
                            var offset = 0L
                            while (true) {
                                operationContext.ensureActive()
                                val count = remote.read(buffer, offset, 0, buffer.size)
                                if (count <= 0) break
                                if (total > 0 && count.toLong() > total - offset) {
                                    throw IOException("SMB 다운로드 크기가 원격 파일 크기를 초과했습니다.")
                                }
                                output.write(buffer, 0, count)
                                offset += count
                                progress(offset, total)
                            }
                            output.flush()
                            progress(offset, if (total > 0) total else offset)
                        }
                    }
                }
            }
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }

    override suspend fun readRange(item: RemoteFileItem, offset: Long, length: Int): ByteArray =
        withContext(Dispatchers.IO) {
            RemoteRangeContract.validate(item, offset, length)
            val source = requireMutableItemPath(item.path, allowMutation = false)
            val location = split(source)
            val operationContext = coroutineContext
            withSession { session ->
                withShare(session, location.share) { share ->
                    val entry = requireTransferableEntry(share, location.relativePath)
                    if (entry.isDirectory()) {
                        throw RemoteServiceException.Unsupported("폴더는 범위 읽기를 지원하지 않습니다.")
                    }
                    if (offset >= entry.endOfFile) return@withShare ByteArray(0)
                    val expected = minOf(length.toLong(), entry.endOfFile - offset).toInt()
                    share.openFile(
                        location.relativePath,
                        EnumSet.of(AccessMask.FILE_READ_DATA, AccessMask.FILE_READ_ATTRIBUTES),
                        EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                        SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_OPEN,
                        EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
                    ).use { remote ->
                        val output = ByteArrayOutputStream(minOf(expected, DOWNLOAD_CHUNK_SIZE))
                        val buffer = ByteArray(minOf(expected, DOWNLOAD_CHUNK_SIZE))
                        var currentOffset = offset
                        var remaining = expected
                        while (remaining > 0) {
                            operationContext.ensureActive()
                            val count = remote.read(buffer, currentOffset, 0, minOf(buffer.size, remaining))
                            if (count <= 0) break
                            output.write(buffer, 0, count)
                            currentOffset += count
                            remaining -= count
                        }
                        if (remaining != 0) {
                            throw RemoteServiceException.Server("SMB 범위 응답 크기가 일치하지 않습니다.")
                        }
                        output.toByteArray()
                    }
                }
            }
        }

    override suspend fun upload(parent: String, source: File): Unit = withContext(Dispatchers.IO) {
        require(source.isFile) { "일반 파일만 SMB 위치에 업로드할 수 있습니다." }
        val parentPath = requireInsideRoot(parent)
        require(parentPath != "/") { "업로드할 SMB 공유를 먼저 선택하세요." }
        val preferredName = validateName(source.name)
        val parentLocation = split(parentPath)

        withSession { session ->
            withShare(session, parentLocation.share) { share ->
                val temporaryName = ".nasfinder-upload-${UUID.randomUUID()}.part"
                val temporaryPath = joinRelative(parentLocation.relativePath, temporaryName)
                var temporaryCreated = false
                try {
                    share.openFile(
                        temporaryPath,
                        EnumSet.of(
                            AccessMask.FILE_WRITE_DATA,
                            AccessMask.FILE_WRITE_ATTRIBUTES,
                            AccessMask.DELETE,
                        ),
                        EnumSet.of(FileAttributes.FILE_ATTRIBUTE_TEMPORARY),
                        SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_CREATE,
                        EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
                    ).use { remote ->
                        temporaryCreated = true
                        source.inputStream().buffered().use { input ->
                            InputStreamByteChunkProvider(input).use { chunks -> remote.write(chunks) }
                        }
                        val finalRelative = chooseKeepBothPath(
                            share = share,
                            parentRelativePath = parentLocation.relativePath,
                            preferredName = preferredName,
                        )
                        remote.rename(finalRelative, false)
                        temporaryCreated = false
                    }
                } finally {
                    if (temporaryCreated) runCatching { share.rm(temporaryPath) }
                }
            }
        }
    }

    override suspend fun createFolder(parent: String, name: String): Unit = withContext(Dispatchers.IO) {
        val parentPath = requireInsideRoot(parent)
        require(parentPath != "/") { "폴더를 만들 SMB 공유를 먼저 선택하세요." }
        val destination = appendPath(parentPath, validateName(name))
        requireInsideRoot(destination)
        val location = split(destination)
        withSession { session ->
            withShare(session, location.share) { share ->
                if (share.fileExists(location.relativePath) || share.folderExists(location.relativePath)) {
                    throw RemoteServiceException.Server("같은 이름의 SMB 항목이 이미 있습니다.")
                }
                share.mkdir(location.relativePath)
                if (!share.folderExists(location.relativePath)) {
                    throw RemoteServiceException.Server("SMB 폴더 생성을 확인하지 못했습니다.")
                }
            }
        }
    }

    override suspend fun rename(item: RemoteFileItem, newName: String): Unit = withContext(Dispatchers.IO) {
        val source = requireMutableItemPath(item.path)
        val parent = parentPath(source)
        val destination = appendPath(parent, validateName(newName))
        requireInsideRoot(destination)
        val sourceLocation = split(source)
        val targetLocation = split(destination)
        require(sourceLocation.share.equals(targetLocation.share, ignoreCase = true)) {
            "SMB 공유 사이에서는 이름을 변경할 수 없습니다."
        }
        withSession { session ->
            withShare(session, sourceLocation.share) { share ->
                if (share.fileExists(targetLocation.relativePath) || share.folderExists(targetLocation.relativePath)) {
                    throw RemoteServiceException.Server("같은 이름의 SMB 항목이 이미 있습니다.")
                }
                openForRename(share, sourceLocation.relativePath, item.isDirectory).use { entry ->
                    entry.rename(targetLocation.relativePath, false)
                }
            }
        }
    }

    override suspend fun delete(items: List<RemoteFileItem>): Unit = withContext(Dispatchers.IO) {
        for (item in items) {
            coroutineContext.ensureActive()
            val source = requireMutableItemPath(item.path)
            val location = split(source)
            withSession { session ->
                withShare(session, location.share) { share ->
                    deleteRecursively(share, location.relativePath, item.isDirectory)
                }
            }
        }
    }

    override suspend fun copy(items: List<RemoteFileItem>, destination: String): Unit =
        transfer(items, destination, removeSource = false)

    override suspend fun move(items: List<RemoteFileItem>, destination: String): Unit =
        transfer(items, destination, removeSource = true)

    private suspend fun transfer(
        items: List<RemoteFileItem>,
        destination: String,
        removeSource: Boolean,
    ): Unit = withContext(Dispatchers.IO) {
        val safeDestination = requireInsideRoot(destination)
        require(safeDestination != "/") { "대상 SMB 공유를 먼저 선택하세요." }
        val destinationLocation = split(safeDestination)
        val sources = items.map { item ->
            val path = requireMutableItemPath(item.path)
            val location = split(path)
            require(SmbTransferPolicy.sameShare(location.share, destinationLocation.share)) {
                "SMB 복사와 이동은 같은 공유 안에서만 지원합니다."
            }
            if (SmbTransferPolicy.isSameOrDescendant(safeDestination, path)) {
                throw IllegalArgumentException("SMB 폴더를 자기 자신 안으로 복사하거나 이동할 수 없습니다.")
            }
            SmbTransferSource(path, location, relativeName(location.relativePath))
        }
        if (sources.isEmpty()) return@withContext

        withSession { session ->
            withShare(session, destinationLocation.share) { share ->
                check(share.folderExists(destinationLocation.relativePath)) { "대상 SMB 폴더를 찾을 수 없습니다." }
                if (destinationLocation.relativePath.isNotEmpty()) {
                    val destinationEntry = requireTransferableEntry(share, destinationLocation.relativePath)
                    check(destinationEntry.isDirectory()) { "대상 SMB 경로가 폴더가 아닙니다." }
                }
                for (source in sources) {
                    coroutineContext.ensureActive()
                    if (removeSource && parentPath(source.path).equals(safeDestination, ignoreCase = true)) continue
                    val metadata = requireTransferableEntry(share, source.location.relativePath)
                    val finalRelative = chooseKeepBothPath(
                        share,
                        destinationLocation.relativePath,
                        source.name,
                    )

                    if (removeSource && tryAtomicRename(
                            share,
                            source.location.relativePath,
                            finalRelative,
                            metadata.isDirectory(),
                        )
                    ) continue

                    val stagingName = ".nasfinder-copy-${UUID.randomUUID()}"
                    val stagingRelative = joinRelative(destinationLocation.relativePath, stagingName)
                    var stagingCreated = false
                    try {
                        copyRecursively(
                            share,
                            source.location.relativePath,
                            stagingRelative,
                            metadata.isDirectory(),
                        ) { stagingCreated = true }
                        openForRename(share, stagingRelative, metadata.isDirectory()).use { entry ->
                            entry.rename(finalRelative, false)
                        }
                        stagingCreated = false
                    } finally {
                        if (stagingCreated) {
                            runCatching { deleteRecursively(share, stagingRelative, metadata.isDirectory()) }
                        }
                    }
                    coroutineContext.ensureActive()
                    if (removeSource) {
                        deleteRecursively(share, source.location.relativePath, metadata.isDirectory())
                    }
                }
            }
        }
    }

    private suspend fun copyRecursively(
        share: DiskShare,
        source: String,
        target: String,
        isDirectory: Boolean,
        onCreated: () -> Unit = {},
    ) {
        coroutineContext.ensureActive()
        if (isDirectory) {
            share.mkdir(target)
            onCreated()
            for (entry in share.list(source)) {
                coroutineContext.ensureActive()
                if (entry.fileName == "." || entry.fileName == "..") continue
                if (isReparsePoint(entry)) {
                    throw RemoteServiceException.Unsupported(
                        "안전을 위해 SMB 재분석 지점은 복사하지 않습니다: ${entry.fileName}",
                    )
                }
                copyRecursively(
                    share,
                    joinRelative(source, entry.fileName),
                    joinRelative(target, entry.fileName),
                    entry.isDirectory(),
                )
            }
            return
        }

        share.openFile(
            source,
            EnumSet.of(AccessMask.FILE_READ_DATA, AccessMask.FILE_READ_ATTRIBUTES),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        ).use { input ->
            share.openFile(
                target,
                EnumSet.of(
                    AccessMask.FILE_WRITE_DATA,
                    AccessMask.FILE_WRITE_ATTRIBUTES,
                    AccessMask.FILE_READ_ATTRIBUTES,
                    AccessMask.DELETE,
                ),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_CREATE,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
            ).use { output ->
                onCreated()
                val buffer = ByteArray(DOWNLOAD_CHUNK_SIZE)
                var offset = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer, offset, 0, buffer.size)
                    if (count <= 0) break
                    var written = 0
                    while (written < count) {
                        val step = output.write(buffer, offset + written, written, count - written).toInt()
                        if (step <= 0) throw IOException("SMB 복사 중 쓰기가 중단되었습니다.")
                        written += step
                    }
                    offset += count
                }
                output.flush()
                if (output.fileInformation.standardInformation.endOfFile != offset) {
                    throw IOException("SMB 복사 결과 크기가 일치하지 않습니다.")
                }
            }
        }
    }

    private fun requireTransferableEntry(
        share: DiskShare,
        relativePath: String,
    ): FileIdBothDirectoryInformation {
        val parent = relativeParent(relativePath)
        val name = relativeName(relativePath)
        val entry = share.list(parent).firstOrNull { it.fileName.equals(name, ignoreCase = true) }
            ?: throw RemoteServiceException.Server("SMB 원본 항목을 찾을 수 없습니다.")
        if (isReparsePoint(entry)) {
            throw RemoteServiceException.Unsupported("안전을 위해 SMB 재분석 지점은 복사하거나 이동하지 않습니다.")
        }
        return entry
    }

    private fun tryAtomicRename(
        share: DiskShare,
        source: String,
        target: String,
        isDirectory: Boolean,
    ): Boolean = try {
        openForRename(share, source, isDirectory).use { entry -> entry.rename(target, false) }
        true
    } catch (_: SMBApiException) {
        false
    }

    private suspend fun deleteRecursively(
        share: DiskShare,
        relativePath: String,
        isDirectory: Boolean,
    ) {
        coroutineContext.ensureActive()
        if (!isDirectory) {
            share.rm(relativePath)
            return
        }
        for (entry in share.list(relativePath)) {
            coroutineContext.ensureActive()
            if (entry.fileName == "." || entry.fileName == "..") continue
            if (isReparsePoint(entry)) {
                throw RemoteServiceException.Unsupported(
                    "안전을 위해 SMB 재분석 지점은 재귀 삭제하지 않습니다: ${entry.fileName}",
                )
            }
            val child = joinRelative(relativePath, entry.fileName)
            deleteRecursively(share, child, entry.isDirectory())
        }
        share.rmdir(relativePath, false)
    }

    private fun chooseKeepBothPath(
        share: DiskShare,
        parentRelativePath: String,
        preferredName: String,
    ): String {
        for (index in 0..MAX_KEEP_BOTH_ATTEMPTS) {
            val name = if (index == 0) preferredName else keepBothName(preferredName, index)
            val candidate = joinRelative(parentRelativePath, name)
            if (!share.fileExists(candidate) && !share.folderExists(candidate)) return candidate
        }
        throw RemoteServiceException.Server("사용 가능한 SMB 파일 이름을 만들 수 없습니다.")
    }

    private fun keepBothName(filename: String, index: Int): String {
        val dot = filename.lastIndexOf('.')
        val hasExtension = dot > 0 && dot < filename.lastIndex
        val stem = if (hasExtension) filename.substring(0, dot) else filename
        val extension = if (hasExtension) filename.substring(dot) else ""
        return "$stem ($index)$extension"
    }

    private fun openForRename(share: DiskShare, path: String, isDirectory: Boolean): DiskEntry =
        if (isDirectory) {
            share.openDirectory(
                path,
                EnumSet.of(AccessMask.DELETE, AccessMask.FILE_READ_ATTRIBUTES),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_DIRECTORY),
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.of(SMB2CreateOptions.FILE_DIRECTORY_FILE),
            )
        } else {
            share.openFile(
                path,
                EnumSet.of(AccessMask.DELETE, AccessMask.FILE_READ_ATTRIBUTES),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
            )
        }

    private fun FileIdBothDirectoryInformation.toRemoteItem(parent: String): RemoteFileItem {
        val childPath = appendPath(parent, validateName(fileName))
        requireInsideRoot(childPath)
        val directory = isDirectory()
        return RemoteFileItem(
            id = childPath,
            name = fileName,
            path = childPath,
            isDirectory = directory,
            size = if (directory) 0 else endOfFile,
            modifiedAt = runCatching { lastWriteTime.toInstant() }.getOrNull() ?: Instant.EPOCH,
        )
    }

    private fun FileIdBothDirectoryInformation.isDirectory(): Boolean =
        fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L

    private fun isReparsePoint(entry: FileIdBothDirectoryInformation): Boolean =
        entry.fileAttributes and FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT.value != 0L

    private suspend fun <T> withSession(operation: suspend (Session) -> T): T = translateErrors {
        val config = SmbConfig.builder()
            .withDialects(
                SMB2Dialect.SMB_2_0_2,
                SMB2Dialect.SMB_2_1,
                SMB2Dialect.SMB_3_0,
                SMB2Dialect.SMB_3_0_2,
                SMB2Dialect.SMB_3_1_1,
            )
            .withTimeout(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .withSoTimeout(SOCKET_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
        val client = SMBClient(config)
        try {
            val transport = client.connect(connection.host.trim(), connection.port)
            try {
                val account = parseAccount(connection.username)
                val authentication = AuthenticationContext(
                    account.username,
                    password.toCharArray(),
                    account.domain,
                )
                val session = transport.authenticate(authentication)
                try {
                    operation(session)
                } finally {
                    session.close()
                }
            } finally {
                transport.close()
            }
        } finally {
            client.close()
        }
    }

    private suspend fun <T> withShare(
        session: Session,
        shareName: String,
        operation: suspend (DiskShare) -> T,
    ): T {
        val connected = session.connectShare(validateShareName(shareName))
        val diskShare = connected as? DiskShare
            ?: run {
                connected.close()
                throw RemoteServiceException.Unsupported("디스크 형식이 아닌 SMB 공유입니다.")
            }
        return try {
            operation(diskShare)
        } finally {
            diskShare.close()
        }
    }

    private suspend fun <T> translateErrors(operation: suspend () -> T): T = try {
        operation()
    } catch (error: CancellationException) {
        throw error
    } catch (error: RemoteServiceException) {
        throw error
    } catch (error: SMBApiException) {
        when (error.status) {
            NtStatus.STATUS_LOGON_FAILURE,
            NtStatus.STATUS_PASSWORD_EXPIRED,
            NtStatus.STATUS_ACCOUNT_DISABLED,
            NtStatus.STATUS_LOGON_TYPE_NOT_GRANTED,
            -> throw RemoteServiceException.Authentication("SMB 로그인에 실패했습니다.")

            else -> throw RemoteServiceException.Server("SMB 서버 오류: ${error.status}")
        }
    } catch (error: IOException) {
        throw RemoteServiceException.Connection("SMB 서버에 연결할 수 없습니다: ${error.message}", error)
    } catch (error: IllegalArgumentException) {
        throw error
    } catch (error: Exception) {
        throw RemoteServiceException.Server("SMB 작업을 완료하지 못했습니다: ${error.message}")
    }

    private fun requireInsideRoot(path: String): String {
        val canonical = canonicalPath(path)
        if (!canonical.isInside(configuredRoot)) {
            throw IllegalArgumentException("설정한 SMB 시작 위치 밖의 경로입니다.")
        }
        return canonical
    }

    private fun requireMutableItemPath(path: String, allowMutation: Boolean = true): String {
        val canonical = requireInsideRoot(path)
        if (canonical == "/" || (allowMutation && canonical == configuredRoot)) {
            throw IllegalArgumentException("SMB 연결 루트는 변경할 수 없습니다.")
        }
        val location = split(canonical)
        if (allowMutation && location.relativePath.isEmpty()) {
            throw IllegalArgumentException("SMB 공유 자체는 변경할 수 없습니다.")
        }
        return canonical
    }

    private fun canonicalPath(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed == "/") return "/"
        require(!trimmed.contains('\u0000') && !trimmed.contains('\\')) {
            "안전하지 않은 SMB 경로입니다."
        }
        val components = mutableListOf<String>()
        trimmed.split('/').forEach { component ->
            when (component) {
                "", "." -> Unit
                ".." -> {
                    require(components.isNotEmpty()) { "SMB 경로가 루트를 벗어납니다." }
                    components.removeAt(components.lastIndex)
                }
                else -> components += component
            }
        }
        return if (components.isEmpty()) "/" else "/${components.joinToString("/")}"
    }

    private fun String.isInside(root: String): Boolean =
        root == "/" || this == root || startsWith("$root/")

    private fun split(path: String): SmbLocation {
        val canonical = canonicalPath(path)
        val components = canonical.removePrefix("/").split('/').filter(String::isNotEmpty)
        val share = components.firstOrNull()
            ?: throw IllegalArgumentException("SMB 공유 이름이 없는 경로입니다.")
        return SmbLocation(
            share = validateShareName(share),
            relativePath = components.drop(1).joinToString("\\"),
        )
    }

    private fun validateShareName(value: String): String {
        val share = value.trim()
        require(share.isNotEmpty() && share != "." && share != "..") {
            "SMB 공유 이름이 올바르지 않습니다."
        }
        require(share.none { it == '/' || it == '\\' || it == '\u0000' || it == ':' }) {
            "안전하지 않은 SMB 공유 이름입니다."
        }
        return share
    }

    private fun validateName(value: String): String {
        val name = value.trim()
        require(name.isNotEmpty() && name != "." && name != "..") {
            "SMB 파일 이름이 올바르지 않습니다."
        }
        require(name.none { it == '/' || it == '\\' || it == '\u0000' || it == ':' }) {
            "안전하지 않은 SMB 파일 이름입니다."
        }
        return name
    }

    private fun appendPath(parent: String, name: String): String {
        val canonicalParent = canonicalPath(parent)
        val safeName = validateName(name)
        return if (canonicalParent == "/") "/$safeName" else "$canonicalParent/$safeName"
    }

    private fun parentPath(path: String): String {
        val canonical = canonicalPath(path)
        val index = canonical.lastIndexOf('/')
        return if (index <= 0) "/" else canonical.substring(0, index)
    }

    private fun joinRelative(parent: String, name: String): String {
        val safeName = validateName(name)
        return if (parent.isEmpty()) safeName else "${parent.trimEnd('\\')}\\$safeName"
    }

    private fun relativeParent(path: String): String = path.substringBeforeLast('\\', "")

    private fun relativeName(path: String): String =
        validateName(path.substringAfterLast('\\'))

    private fun parseAccount(value: String): SmbAccount {
        val trimmed = value.trim()
        val separator = trimmed.indexOf('\\')
        return if (separator in 1 until trimmed.lastIndex) {
            SmbAccount(
                domain = trimmed.substring(0, separator),
                username = trimmed.substring(separator + 1),
            )
        } else {
            SmbAccount(domain = "", username = trimmed)
        }
    }

    private data class SmbLocation(val share: String, val relativePath: String)
    private data class SmbTransferSource(val path: String, val location: SmbLocation, val name: String)
    private data class SmbAccount(val domain: String, val username: String)

    private companion object {
        const val DOWNLOAD_CHUNK_SIZE = 128 * 1024
        const val MAX_KEEP_BOTH_ATTEMPTS = 9_999
        const val OPERATION_TIMEOUT_SECONDS = 60L
        const val SOCKET_TIMEOUT_SECONDS = 90L
    }
}

internal object SmbTransferPolicy {
    fun sameShare(first: String, second: String): Boolean = first.equals(second, ignoreCase = true)

    fun isSameOrDescendant(candidate: String, ancestor: String): Boolean {
        val normalizedCandidate = candidate.trimEnd('/')
        val normalizedAncestor = ancestor.trimEnd('/')
        return normalizedCandidate.equals(normalizedAncestor, ignoreCase = true) ||
            normalizedCandidate.startsWith("$normalizedAncestor/", ignoreCase = true)
    }
}
