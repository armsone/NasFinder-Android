package com.armsone.nasfinder.platform

import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Point
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.util.Base64
import android.webkit.MimeTypeMap
import com.armsone.nasfinder.data.ConnectionRepository
import com.armsone.nasfinder.data.RemoteThumbnailRepository
import com.armsone.nasfinder.model.ConnectionKind
import com.armsone.nasfinder.model.RemoteConnection
import com.armsone.nasfinder.model.RemoteFileItem
import com.armsone.nasfinder.network.RemoteFileService
import com.armsone.nasfinder.network.RemoteFileServiceFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/**
 * SAF bridge for backends that support reliable list and download operations.
 * Mutations are narrowly exposed for verified SFTP operations; every other
 * backend remains read-only, and every path stays below the configured root.
 */
class NasFinderDocumentsProvider : DocumentsProvider() {
    private lateinit var repository: ConnectionRepository

    override fun onCreate(): Boolean {
        repository = ConnectionRepository(checkNotNull(context))
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns = projection ?: DEFAULT_ROOT_PROJECTION
        val cursor = MatrixCursor(columns)
        supportedConnections().forEach { connection ->
            cursor.newRow().apply {
                addIfPresent(cursor, DocumentsContract.Root.COLUMN_ROOT_ID, connection.id)
                addIfPresent(cursor, DocumentsContract.Root.COLUMN_DOCUMENT_ID, rootDocumentId(connection.id))
                addIfPresent(cursor, DocumentsContract.Root.COLUMN_TITLE, connection.name)
                addIfPresent(cursor, DocumentsContract.Root.COLUMN_SUMMARY, connection.endpoint)
                addIfPresent(
                    cursor,
                    DocumentsContract.Root.COLUMN_FLAGS,
                    rootFlags(connection),
                )
                addIfPresent(cursor, DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
                addIfPresent(cursor, DocumentsContract.Root.COLUMN_ICON, context?.applicationInfo?.icon ?: 0)
            }
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val columns = projection ?: DEFAULT_DOCUMENT_PROJECTION
        val reference = parseDocumentId(documentId)
        val connection = connection(reference.connectionId)
        val cursor = MatrixCursor(columns)
        if (reference.path == null) {
            addRootDocument(cursor, cursor.newRow(), connection)
        } else {
            val loaded = loadItemWithCapability(connection, reference.path, null)
            addRemoteDocument(
                cursor,
                cursor.newRow(),
                connection,
                loaded.item,
                loaded.supportsRangeStreaming,
            )
        }
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor = queryChildren(parentDocumentId, projection, null)

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        if (mode.any { it == 'w' || it == 'a' || it == '+' }) {
            throw FileNotFoundException("이 NasFinder 위치는 현재 읽기 전용입니다.")
        }
        val reference = parseDocumentId(documentId)
        val path = reference.path ?: throw FileNotFoundException("폴더는 파일로 열 수 없습니다.")
        val connection = connection(reference.connectionId)
        val item = loadItem(connection, path, signal)
        if (item.isDirectory) throw FileNotFoundException("폴더는 파일로 열 수 없습니다.")

        val cacheRoot = safeCacheDirectory("documents_provider")
        DocumentsProviderCachePolicy.prune(cacheRoot)
        val extension = item.name.substringAfterLast('.', "")
            .filter(Char::isLetterOrDigit)
            .lowercase()
            .take(12)
        val targetName = sha256(documentId) + if (extension.isEmpty()) "" else ".$extension"
        val lexicalTarget = File(cacheRoot, targetName)
        if (Files.isSymbolicLink(lexicalTarget.toPath())) {
            throw FileNotFoundException("안전하지 않은 문서 캐시 항목입니다.")
        }
        val target = lexicalTarget.canonicalFile
        if (!target.isWithin(cacheRoot)) throw FileNotFoundException("안전하지 않은 문서 경로입니다.")
        val temporary = File.createTempFile("download-", ".part", cacheRoot)

        try {
            withService(connection, signal) { service ->
                service.download(item, temporary) { _, _ -> signal?.throwIfCanceled() }
            }
            signal?.throwIfCanceled()
            moveReplacing(temporary, target)
            DocumentsProviderCachePolicy.prune(cacheRoot, preserve = target)
            return ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (error: Exception) {
            DocumentsProviderCachePolicy.cleanupFailedDownload(cacheRoot, temporary)
            if (error is android.os.OperationCanceledException) throw error
            throw FileNotFoundException("원격 파일을 열 수 없습니다.").apply { initCause(error) }
        }
    }

    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: Point,
        signal: CancellationSignal?,
    ): AssetFileDescriptor {
        val reference = parseDocumentId(documentId)
        val path = reference.path ?: throw FileNotFoundException("폴더에는 썸네일이 없습니다.")
        val connection = connection(reference.connectionId)
        val loaded = loadItemWithCapability(connection, path, signal)
        val item = loaded.item
        if (!DocumentsThumbnailPolicy.supports(item, loaded.supportsRangeStreaming)) {
            throw FileNotFoundException("지원되는 썸네일이 없습니다.")
        }
        val pixelSize = DocumentsThumbnailPolicy.requestedPixelSize(sizeHint.x, sizeHint.y)
        val cacheRoot = safeCacheDirectory("documents_provider_thumbnails")
        pruneThumbnailCache(cacheRoot)
        val key = DocumentsThumbnailPolicy.cacheKey(connection.id, item, pixelSize)
        val target = File(cacheRoot, "$key.png").canonicalFile
        if (!target.isWithin(cacheRoot)) throw FileNotFoundException("안전하지 않은 썸네일 경로입니다.")
        if (Files.isSymbolicLink(File(cacheRoot, "$key.png").toPath())) {
            throw FileNotFoundException("안전하지 않은 썸네일 캐시 항목입니다.")
        }

        if (!target.isFile) {
            val temporary = File.createTempFile("thumbnail-", ".part", cacheRoot)
            try {
                val bitmap = withService(connection, signal) { service ->
                    RemoteThumbnailRepository(checkNotNull(context), maxPixelSize = pixelSize).use { thumbnails ->
                        thumbnails.load(connection, item, service)
                    }
                } ?: throw FileNotFoundException("원격 썸네일을 만들 수 없습니다.")
                signal?.throwIfCanceled()
                FileOutputStream(temporary).use { output ->
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        throw FileNotFoundException("썸네일을 저장할 수 없습니다.")
                    }
                    output.fd.sync()
                }
                moveReplacing(temporary, target)
                pruneThumbnailCache(cacheRoot, preserve = target)
            } catch (error: Exception) {
                temporary.delete()
                if (error is android.os.OperationCanceledException) throw error
                if (error is FileNotFoundException) throw error
                throw FileNotFoundException("원격 썸네일을 열 수 없습니다.").apply { initCause(error) }
            }
        }
        signal?.throwIfCanceled()
        val descriptor = ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(descriptor, 0, target.length())
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        if (mimeType != DocumentsContract.Document.MIME_TYPE_DIR) {
            throw FileNotFoundException("NasFinder Files 위치에서는 폴더만 만들 수 있습니다.")
        }
        val parentReference = parseDocumentId(parentDocumentId)
        val connection = mutableSftpConnection(parentReference.connectionId)
        val parentPath = directoryPath(parentReference, connection)
        val name = validatedMutationName(displayName)
        val expectedPath = validatedItemPath(connection, SafSftpMutationPolicy.append(parentPath, name))
        ensureNameAvailable(connection, parentPath, name)
        performMutation(connection, "폴더 생성") { service -> service.createFolder(parentPath, name) }
        val created = verifyMutation("폴더 생성") {
            val items = listRemote(connection, parentPath)
            SafSftpMutationPolicy.verifiedItem(items, expectedPath, directory = true)
        }
        return childDocumentId(connection.id, validatedItemPath(connection, created.path))
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val reference = parseDocumentId(documentId)
        val sourcePath = reference.path ?: throw FileNotFoundException("연결 루트의 이름은 바꿀 수 없습니다.")
        val connection = mutableSftpConnection(reference.connectionId)
        val safeSource = validatedItemPath(connection, sourcePath)
        val item = loadItem(connection, safeSource, null)
        val name = validatedMutationName(displayName)
        if (name == item.name) return documentId
        val parent = parentRemotePath(safeSource)
        val expectedPath = validatedItemPath(connection, SafSftpMutationPolicy.append(parent, name))
        ensureNameAvailable(connection, parent, name)
        performMutation(connection, "이름 변경") { service -> service.rename(item, name) }
        val renamed = verifyMutation("이름 변경") {
            val items = listRemote(connection, parent)
            val result = SafSftpMutationPolicy.verifiedItem(items, expectedPath, item.isDirectory)
            val oldRemains = items.any {
                runCatching { validatedItemPath(connection, it.path) == safeSource }.getOrDefault(false)
            }
            result.takeUnless { oldRemains }
        }
        return childDocumentId(connection.id, validatedItemPath(connection, renamed.path))
    }

    override fun deleteDocument(documentId: String) {
        val reference = parseDocumentId(documentId)
        val sourcePath = reference.path ?: throw FileNotFoundException("연결 루트는 삭제할 수 없습니다.")
        val connection = mutableSftpConnection(reference.connectionId)
        val item = loadItem(connection, sourcePath, null)
        val safePath = validatedItemPath(connection, sourcePath)
        val parent = parentRemotePath(safePath)
        performMutation(connection, "삭제") { service -> service.delete(listOf(item)) }
        val remains = runCatching {
            listRemote(connection, parent).any { runCatching { validatedItemPath(connection, it.path) == safePath }.getOrDefault(false) }
        }.getOrElse { throw mutationMayHaveSucceeded("삭제", it) }
        if (remains) throw mutationMayHaveSucceeded("삭제")
    }

    override fun moveDocument(
        sourceDocumentId: String,
        sourceParentDocumentId: String,
        targetParentDocumentId: String,
    ): String {
        val source = parseDocumentId(sourceDocumentId)
        val sourcePath = source.path ?: throw FileNotFoundException("연결 루트는 이동할 수 없습니다.")
        val sourceParent = parseDocumentId(sourceParentDocumentId)
        val targetParent = parseDocumentId(targetParentDocumentId)
        if (source.connectionId != sourceParent.connectionId || source.connectionId != targetParent.connectionId) {
            throw FileNotFoundException("서로 다른 연결 사이에서는 이동할 수 없습니다.")
        }
        val connection = mutableSftpConnection(source.connectionId)
        val safeSource = validatedItemPath(connection, sourcePath)
        val sourceDirectory = directoryPath(sourceParent, connection)
        val targetDirectory = directoryPath(targetParent, connection)
        if (!SafSftpMutationPolicy.isImmediateChild(safeSource, sourceDirectory)) {
            throw FileNotFoundException("원본 상위 폴더가 일치하지 않습니다.")
        }
        if (sourceDirectory == targetDirectory) return sourceDocumentId
        val item = loadItem(connection, safeSource, null)
        val moveName = validatedMutationName(item.name)
        if (moveName != item.name) throw FileNotFoundException("이 문서 이름은 안전하게 이동할 수 없습니다.")
        if (item.isDirectory && SafSftpMutationPolicy.isSameOrDescendant(targetDirectory, safeSource)) {
            throw FileNotFoundException("폴더를 자기 자신 안으로 이동할 수 없습니다.")
        }
        ensureNameAvailable(connection, targetDirectory, moveName)
        val expectedPath = validatedItemPath(connection, SafSftpMutationPolicy.append(targetDirectory, moveName))
        performMutation(connection, "이동") { service -> service.move(listOf(item), targetDirectory) }
        val moved = verifyMutation("이동") {
            val result = SafSftpMutationPolicy.verifiedItem(
                listRemote(connection, targetDirectory),
                expectedPath,
                item.isDirectory,
            )
            val sourceRemains = listRemote(connection, sourceDirectory).any {
                runCatching { validatedItemPath(connection, it.path) == safeSource }.getOrDefault(false)
            }
            result.takeUnless { sourceRemains }
        }
        return childDocumentId(connection.id, validatedItemPath(connection, moved.path))
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = runCatching {
        val parent = parseDocumentId(parentDocumentId)
        val child = parseDocumentId(documentId)
        if (parent.connectionId != child.connectionId || child.path == null) return@runCatching false
        val connection = connection(parent.connectionId)
        val parentPath = parent.path ?: canonicalRemotePath(connection.normalizedRootPath)
        val childPath = canonicalRemotePath(child.path)
        childPath != parentPath && childPath.isWithinRemote(parentPath)
    }.getOrDefault(false)

    private fun queryChildren(
        parentDocumentId: String,
        projection: Array<out String>?,
        signal: CancellationSignal?,
    ): Cursor {
        val columns = projection ?: DEFAULT_DOCUMENT_PROJECTION
        val reference = parseDocumentId(parentDocumentId)
        val connection = connection(reference.connectionId)
        val directory = reference.path ?: canonicalRemotePath(connection.normalizedRootPath)
        val listing = withService(connection, signal) { service ->
            ServiceListing(service.list(directory), service.supportsRangeStreaming)
        }
        val cursor = MatrixCursor(columns)
        listing.items.asSequence()
            .filterNot { it.name.startsWith('.') }
            .filter { runCatching { validatedItemPath(connection, it.path) }.isSuccess }
            .sortedWith(compareByDescending<RemoteFileItem> { it.isDirectory }.thenBy { it.name.lowercase() })
            .forEach { item ->
                addRemoteDocument(
                    cursor,
                    cursor.newRow(),
                    connection,
                    item,
                    listing.supportsRangeStreaming,
                )
            }
        return cursor
    }

    private fun loadItem(
        connection: RemoteConnection,
        requestedPath: String,
        signal: CancellationSignal?,
    ): RemoteFileItem = loadItemWithCapability(connection, requestedPath, signal).item

    private fun loadItemWithCapability(
        connection: RemoteConnection,
        requestedPath: String,
        signal: CancellationSignal?,
    ): ServiceItem {
        val path = validatedItemPath(connection, requestedPath)
        val root = canonicalRemotePath(connection.normalizedRootPath)
        if (path == root) throw FileNotFoundException("연결 루트에는 별도 문서 항목이 없습니다.")
        val parent = parentRemotePath(path)
        return withService(connection, signal) { service ->
            val item = service.list(parent).firstOrNull { item ->
                !item.name.startsWith('.') && runCatching {
                    validatedItemPath(connection, item.path) == path
                }.getOrDefault(false)
            }
            ?: throw FileNotFoundException("원격 문서를 찾을 수 없습니다.")
            ServiceItem(item, service.supportsRangeStreaming)
        }
    }

    private fun addRootDocument(
        cursor: MatrixCursor,
        row: MatrixCursor.RowBuilder,
        connection: RemoteConnection,
    ) {
        row.addIfPresent(cursor, DocumentsContract.Document.COLUMN_DOCUMENT_ID, rootDocumentId(connection.id))
        row.addIfPresent(cursor, DocumentsContract.Document.COLUMN_DISPLAY_NAME, connection.name)
        row.addIfPresent(
            cursor,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.MIME_TYPE_DIR,
        )
        val capabilities = SafSftpMutationPolicy.capabilities(connection.kind, isRoot = true, isDirectory = true)
        val flags = if (capabilities.createFolder) {
            DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
        } else 0
        row.addIfPresent(cursor, DocumentsContract.Document.COLUMN_FLAGS, flags)
        row.addIfPresent(cursor, DocumentsContract.Document.COLUMN_LAST_MODIFIED, connection.createdAt)
    }

    private fun addRemoteDocument(
        cursor: MatrixCursor,
        row: MatrixCursor.RowBuilder,
        connection: RemoteConnection,
        item: RemoteFileItem,
        supportsRangeStreaming: Boolean,
    ) {
        val path = validatedItemPath(connection, item.path)
        row.addIfPresent(
            cursor,
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            childDocumentId(connection.id, path),
        )
        row.addIfPresent(cursor, DocumentsContract.Document.COLUMN_DISPLAY_NAME, item.name)
        row.addIfPresent(
            cursor,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            if (item.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else mimeType(item),
        )
        var flags = if (DocumentsThumbnailPolicy.supports(item, supportsRangeStreaming)) {
            DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL
        } else 0
        val capabilities = SafSftpMutationPolicy.capabilities(connection.kind, isRoot = false, isDirectory = item.isDirectory)
        if (capabilities.delete) flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_DELETE
        if (capabilities.rename) flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_RENAME
        if (capabilities.move) flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_MOVE
        if (capabilities.createFolder) flags = flags or DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
        row.addIfPresent(cursor, DocumentsContract.Document.COLUMN_FLAGS, flags)
        if (!item.isDirectory) {
            row.addIfPresent(cursor, DocumentsContract.Document.COLUMN_SIZE, item.size)
        }
        item.modifiedAt?.toEpochMilli()?.let {
            row.addIfPresent(cursor, DocumentsContract.Document.COLUMN_LAST_MODIFIED, it)
        }
    }

    private fun MatrixCursor.RowBuilder.addIfPresent(
        cursor: MatrixCursor,
        column: String,
        value: Any?,
    ) {
        if (cursor.getColumnIndex(column) >= 0) add(column, value)
    }

    private fun supportedConnections(): List<RemoteConnection> = repository.load()
        .filter { it.kind in READABLE_CONNECTION_KINDS }
        .filter { runCatching { UUID.fromString(it.id) }.isSuccess }
        .filter { it.host.isNotBlank() && it.port in 1..65535 }

    private fun connection(id: String): RemoteConnection = supportedConnections()
        .firstOrNull { it.id == id }
        ?: throw FileNotFoundException("지원되는 NasFinder 연결을 찾을 수 없습니다.")

    private fun rootFlags(connection: RemoteConnection): Int {
        val capabilities = SafSftpMutationPolicy.capabilities(connection.kind, isRoot = true, isDirectory = true)
        val create = if (capabilities.createFolder) DocumentsContract.Root.FLAG_SUPPORTS_CREATE else 0
        return DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD or create
    }

    private fun mutableSftpConnection(id: String): RemoteConnection = connection(id).also {
        if (!SafSftpMutationPolicy.supportsMutations(it.kind)) {
            throw FileNotFoundException("이 NasFinder 위치는 현재 읽기 전용입니다.")
        }
    }

    private fun directoryPath(reference: DocumentReference, connection: RemoteConnection): String {
        val path = reference.path ?: return canonicalRemotePath(connection.normalizedRootPath)
        val item = loadItem(connection, path, null)
        if (!item.isDirectory) throw FileNotFoundException("대상 위치가 폴더가 아닙니다.")
        return validatedItemPath(connection, item.path)
    }

    private fun validatedMutationName(value: String): String = runCatching {
        SafSftpMutationPolicy.validatedName(value)
    }.getOrElse { throw FileNotFoundException(it.message.orEmpty()) }

    private fun ensureNameAvailable(connection: RemoteConnection, parent: String, name: String) {
        if (listRemote(connection, parent).any { it.name == name }) {
            throw FileNotFoundException("같은 이름의 파일 또는 폴더가 이미 있습니다.")
        }
    }

    private fun listRemote(connection: RemoteConnection, parent: String): List<RemoteFileItem> =
        withService(connection, null) { service -> service.list(parent) }

    private fun performMutation(
        connection: RemoteConnection,
        operation: String,
        block: suspend (RemoteFileService) -> Unit,
    ) {
        try {
            withService(connection, null, block)
        } catch (error: Exception) {
            throw mutationMayHaveSucceeded(operation, error)
        }
    }

    private fun <T : Any> verifyMutation(operation: String, block: () -> T?): T = try {
        block() ?: throw mutationMayHaveSucceeded(operation)
    } catch (error: Exception) {
        if (error is MutationMayHaveSucceededException) throw error
        throw mutationMayHaveSucceeded(operation, error)
    }

    private fun mutationMayHaveSucceeded(operation: String, cause: Throwable? = null) =
        MutationMayHaveSucceededException(operation).apply { if (cause != null) initCause(cause) }

    private fun <T> withService(
        connection: RemoteConnection,
        signal: CancellationSignal?,
        operation: suspend (RemoteFileService) -> T,
    ): T {
        val cancellationJob = Job()
        signal?.setOnCancelListener { cancellationJob.cancel() }
        return try {
            signal?.throwIfCanceled()
            val password = repository.credentials.read(connection.id).orEmpty()
            val service = RemoteFileServiceFactory.create(connection, password)
            try {
                runBlocking(Dispatchers.IO + cancellationJob) {
                    signal?.throwIfCanceled()
                    operation(service)
                }
            } finally {
                service.close()
            }
        } catch (error: CancellationException) {
            throw android.os.OperationCanceledException()
        } catch (error: android.os.OperationCanceledException) {
            throw error
        } catch (error: Exception) {
            throw FileNotFoundException("원격 위치를 읽을 수 없습니다.").apply { initCause(error) }
        } finally {
            signal?.setOnCancelListener(null)
        }
    }

    private fun parseDocumentId(documentId: String): DocumentReference {
        if (documentId.startsWith(ROOT_PREFIX)) {
            val id = documentId.removePrefix(ROOT_PREFIX)
            requireValidConnectionId(id)
            return DocumentReference(id, null)
        }
        if (documentId.startsWith(DOCUMENT_PREFIX)) {
            val payload = documentId.removePrefix(DOCUMENT_PREFIX)
            val separator = payload.indexOf(':')
            if (separator <= 0 || separator == payload.lastIndex) throw invalidDocumentId()
            val id = payload.substring(0, separator)
            requireValidConnectionId(id)
            val encodedPath = payload.substring(separator + 1)
            val path = runCatching {
                String(Base64.decode(encodedPath, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), StandardCharsets.UTF_8)
            }.getOrElse { throw invalidDocumentId() }
            if (path.isBlank() || path.indexOf('\u0000') >= 0) throw invalidDocumentId()
            return DocumentReference(id, path)
        }
        throw invalidDocumentId()
    }

    private fun requireValidConnectionId(id: String) {
        if (runCatching { UUID.fromString(id) }.isFailure) throw invalidDocumentId()
    }

    private fun rootDocumentId(connectionId: String) = ROOT_PREFIX + connectionId

    private fun childDocumentId(connectionId: String, path: String): String {
        val encoded = Base64.encodeToString(
            path.toByteArray(StandardCharsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        return "$DOCUMENT_PREFIX$connectionId:$encoded"
    }

    private fun validatedItemPath(connection: RemoteConnection, candidate: String): String {
        val root = canonicalRemotePath(connection.normalizedRootPath)
        val path = canonicalRemotePath(candidate)
        if (!path.isWithinRemote(root)) throw FileNotFoundException("연결 루트 밖의 경로입니다.")
        return path
    }

    private fun canonicalRemotePath(value: String): String {
        if (value.indexOf('\u0000') >= 0 || value.contains('\\')) throw invalidDocumentId()
        val absolute = value.startsWith('/')
        val components = mutableListOf<String>()
        value.split('/').forEach { component ->
            when (component) {
                "", "." -> Unit
                ".." -> {
                    if (components.isEmpty()) throw invalidDocumentId()
                    components.removeAt(components.lastIndex)
                }
                else -> components += component
            }
        }
        return when {
            absolute && components.isEmpty() -> "/"
            absolute -> "/" + components.joinToString("/")
            components.isEmpty() -> "."
            else -> "./" + components.joinToString("/")
        }
    }

    private fun String.isWithinRemote(root: String): Boolean = when (root) {
        "/" -> startsWith('/')
        "." -> this == "." || startsWith("./")
        else -> this == root || startsWith("$root/")
    }

    private fun parentRemotePath(path: String): String {
        if (path == "/" || path == ".") return path
        val separator = path.lastIndexOf('/')
        return when {
            separator < 0 -> "."
            separator == 0 -> "/"
            path.startsWith("./") && separator == 1 -> "."
            else -> path.substring(0, separator)
        }
    }

    private fun mimeType(item: RemoteFileItem): String {
        item.mimeType?.takeIf { it.contains('/') }?.let { return it }
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(item.extension)
            ?: "application/octet-stream"
    }

    private fun moveReplacing(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun safeCacheDirectory(name: String): File {
        val appCache = checkNotNull(context).cacheDir.canonicalFile
        val lexical = File(appCache, name)
        if (Files.isSymbolicLink(lexical.toPath())) throw FileNotFoundException("안전하지 않은 캐시 경로입니다.")
        val directory = lexical.canonicalFile
        if (!directory.isWithin(appCache)) throw FileNotFoundException("안전하지 않은 캐시 경로입니다.")
        if (!directory.exists() && !directory.mkdirs()) throw FileNotFoundException("캐시를 만들 수 없습니다.")
        if (!directory.isDirectory) throw FileNotFoundException("캐시 경로가 폴더가 아닙니다.")
        return directory
    }

    private fun pruneThumbnailCache(directory: File, preserve: File? = null) {
        val now = System.currentTimeMillis()
        val files = directory.listFiles().orEmpty().filter { file ->
            !Files.isSymbolicLink(file.toPath()) && file.isFile && file.name.matches(THUMBNAIL_FILENAME)
        }
        files.filter { now - it.lastModified() > THUMBNAIL_MAX_AGE_MILLIS && it != preserve }
            .forEach { it.delete() }
        var retainedBytes = 0L
        directory.listFiles().orEmpty()
            .filter { !Files.isSymbolicLink(it.toPath()) && it.isFile && it.name.matches(THUMBNAIL_FILENAME) }
            .sortedByDescending(File::lastModified)
            .forEach { file ->
                retainedBytes += file.length().coerceAtLeast(0L)
                if (retainedBytes > THUMBNAIL_CACHE_BYTES && file != preserve) file.delete()
            }
    }

    private fun File.isWithin(root: File): Boolean {
        val rootPath = root.path.trimEnd(File.separatorChar) + File.separator
        return path == root.path || path.startsWith(rootPath)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun invalidDocumentId() = FileNotFoundException("잘못된 NasFinder 문서 식별자입니다.")

    private data class DocumentReference(val connectionId: String, val path: String?)
    private data class ServiceItem(val item: RemoteFileItem, val supportsRangeStreaming: Boolean)
    private data class ServiceListing(val items: List<RemoteFileItem>, val supportsRangeStreaming: Boolean)

    private companion object {
        const val ROOT_PREFIX = "root:"
        const val DOCUMENT_PREFIX = "document:"
        const val THUMBNAIL_CACHE_BYTES = 64L * 1024L * 1024L
        const val THUMBNAIL_MAX_AGE_MILLIS = 7L * 24L * 60L * 60L * 1_000L
        val THUMBNAIL_FILENAME = Regex("^[0-9a-f]{64}\\.png$")

        val READABLE_CONNECTION_KINDS = ConnectionKind.entries.toSet()

        val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
            DocumentsContract.Root.COLUMN_ICON,
        )

        val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}
