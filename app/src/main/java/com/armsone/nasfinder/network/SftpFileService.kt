package com.armsone.nasfinder.network

import com.armsone.nasfinder.model.RemoteConnection
import com.armsone.nasfinder.model.RemoteFileItem
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.SftpProgressMonitor
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

/** Information needed to approve a first SFTP host key or investigate a change. */
class SftpHostKeyTrustRequired(
    val serializedHostKey: String,
    val fingerprint: String,
    val isChangedKey: Boolean,
) : Exception(
    if (isChangedKey) {
        "서버의 SSH 호스트 키가 이전과 다릅니다. 서버 관리자에게 확인하기 전에는 연결하지 마세요. ($fingerprint)"
    } else {
        "처음 연결하는 서버입니다. SSH 호스트 키 지문을 확인해 주세요. ($fingerprint)"
    },
)

/** Password or unencrypted private-key SFTP backend with strict SHA-256 host-key pinning. */
class SftpFileService(
    private val connection: RemoteConnection,
    private val credentialValue: String,
) : RemoteFileService {
    override val supportsRangeStreaming: Boolean = true

    override suspend fun testConnection() = withSftp { channel ->
        requireExistingInsideRoot(channel, connection.normalizedRootPath)
        Unit
    }

    override suspend fun list(path: String): List<RemoteFileItem> = withSftp { channel ->
        currentCoroutineContext().ensureActive()
        val safePath = normalizeInsideRoot(path)
        requireExistingInsideRoot(channel, safePath)
        channel.ls(safePath).mapNotNull { rawEntry ->
            currentCoroutineContext().ensureActive()
            val entry = rawEntry as? ChannelSftp.LsEntry ?: return@mapNotNull null
            if (entry.filename == "." || entry.filename == "..") return@mapNotNull null
            val itemPath = appendName(safePath, entry.filename)
            RemoteFileItem(
                id = "${connection.id}:$itemPath",
                name = entry.filename,
                path = itemPath,
                isDirectory = entry.attrs.isDir,
                size = if (entry.attrs.isDir) 0 else entry.attrs.size,
                modifiedAt = entry.attrs.mTime.takeIf { it > 0 }?.let {
                    Instant.ofEpochSecond(it.toLong())
                },
            )
        }
    }

    override suspend fun download(
        item: RemoteFileItem,
        destination: File,
        progress: (Long, Long) -> Unit,
    ) = withSftp { channel ->
        currentCoroutineContext().ensureActive()
        val sourcePath = normalizeInsideRoot(item.path)
        requireExistingInsideRoot(channel, sourcePath)
        val expectedSize = channel.lstat(sourcePath).size
        destination.parentFile?.mkdirs()
        try {
            var completed = 0L
            channel.get(sourcePath).use { input ->
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count.toLong() > expectedSize - completed) {
                            throw RemoteServiceException.Server("SFTP 다운로드 크기가 원격 파일 크기를 초과했습니다.")
                        }
                        output.write(buffer, 0, count)
                        completed += count
                        progress(completed, expectedSize)
                    }
                }
            }
            if (completed != expectedSize) {
                throw RemoteServiceException.Server("파일을 끝까지 내려받지 못했습니다.")
            }
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
    }

    override suspend fun readRange(item: RemoteFileItem, offset: Long, length: Int): ByteArray =
        withSftp { channel ->
            currentCoroutineContext().ensureActive()
            RemoteRangeContract.validate(item, offset, length)
            val sourcePath = requireExistingInsideRoot(channel, normalizeInsideRoot(item.path))
            val attributes = channel.lstat(sourcePath)
            if (attributes.isDir) throw RemoteServiceException.Unsupported("폴더는 범위 읽기를 지원하지 않습니다.")
            if (offset >= attributes.size) return@withSftp ByteArray(0)
            val expected = minOf(length.toLong(), attributes.size - offset).toInt()
            val output = ByteArrayOutputStream(minOf(expected, 256 * 1024))
            channel.get(sourcePath, null, offset).use { input ->
                val buffer = ByteArray(minOf(expected, 64 * 1024))
                var remaining = expected
                while (remaining > 0) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    remaining -= count
                }
                if (remaining != 0) {
                    throw RemoteServiceException.Server("SFTP 범위 응답 크기가 일치하지 않습니다.")
                }
            }
            output.toByteArray()
        }

    override suspend fun upload(parent: String, source: File) = withSftp { channel ->
        currentCoroutineContext().ensureActive()
        if (!source.isFile) throw RemoteServiceException.Server("업로드할 파일을 찾을 수 없습니다.")
        val safeParent = normalizeInsideRoot(parent)
        requireDirectoryInsideRoot(channel, safeParent)
        validateName(source.name)
        val finalName = keepBothName(source.name, childNames(channel, safeParent))
        val destination = appendName(safeParent, finalName)
        val job = currentCoroutineContext()[Job]
        val monitor = CancellationProgressMonitor(job)
        source.inputStream().buffered().use { input ->
            channel.put(input, destination, monitor, ChannelSftp.OVERWRITE)
        }
        currentCoroutineContext().ensureActive()
        if (channel.lstat(destination).size != source.length()) {
            runCatching { channel.rm(destination) }
            throw RemoteServiceException.Server("업로드한 파일 크기를 확인할 수 없습니다.")
        }
    }

    override suspend fun createFolder(parent: String, name: String) = withSftp { channel ->
        currentCoroutineContext().ensureActive()
        val safeParent = normalizeInsideRoot(parent)
        requireDirectoryInsideRoot(channel, safeParent)
        validateName(name)
        val destination = appendName(safeParent, name)
        if (entryExists(channel, destination)) {
            throw RemoteServiceException.Server("같은 이름의 파일 또는 폴더가 이미 있습니다.")
        }
        channel.mkdir(destination)
    }

    override suspend fun rename(item: RemoteFileItem, newName: String) = withSftp { channel ->
        currentCoroutineContext().ensureActive()
        val source = normalizeInsideRoot(item.path)
        requireExistingInsideRoot(channel, source)
        validateName(newName)
        val destination = appendName(parentPath(source), newName)
        if (source != destination && entryExists(channel, destination)) {
            throw RemoteServiceException.Server("같은 이름의 파일 또는 폴더가 이미 있습니다.")
        }
        requireDirectoryInsideRoot(channel, parentPath(destination))
        channel.rename(source, destination)
    }

    override suspend fun delete(items: List<RemoteFileItem>) = withSftp { channel ->
        for (item in items) {
            currentCoroutineContext().ensureActive()
            val path = normalizeInsideRoot(item.path)
            requireNotConnectionRoot(path)
            requireExistingInsideRoot(channel, path)
            deleteEntry(channel, path)
        }
    }

    override suspend fun copy(items: List<RemoteFileItem>, destination: String) =
        transfer(items, destination, removeSource = false)

    override suspend fun move(items: List<RemoteFileItem>, destination: String) =
        transfer(items, destination, removeSource = true)

    private suspend fun transfer(
        items: List<RemoteFileItem>,
        destination: String,
        removeSource: Boolean,
    ) = withSftp { channel ->
        val safeDestination = normalizeInsideRoot(destination)
        requireDirectoryInsideRoot(channel, safeDestination)
        val canonicalDestination = requireExistingInsideRoot(channel, safeDestination)
        val occupiedNames = childNames(channel, safeDestination).toMutableSet()

        for (item in items) {
            currentCoroutineContext().ensureActive()
            val source = normalizeInsideRoot(item.path)
            requireNotConnectionRoot(source)
            val canonicalSource = requireExistingInsideRoot(channel, source)
            validateName(item.name)
            val finalName = keepBothName(item.name, occupiedNames)
            occupiedNames += finalName
            val target = appendName(safeDestination, finalName)
            if (item.isDirectory && isSameOrDescendant(canonicalDestination, canonicalSource)) {
                throw RemoteServiceException.Server("폴더를 자기 자신 안으로 복사하거나 이동할 수 없습니다.")
            }

            if (removeSource && tryServerSideRename(channel, source, target)) {
                continue
            }

            try {
                copyEntry(channel, source, target)
            } catch (error: Throwable) {
                runCatching { if (entryExists(channel, target)) deleteEntry(channel, target) }
                throw error
            }
            currentCoroutineContext().ensureActive()
            if (removeSource) deleteEntry(channel, source)
        }
    }

    private suspend fun copyEntry(channel: ChannelSftp, source: String, target: String) {
        currentCoroutineContext().ensureActive()
        val attrs = channel.lstat(source)
        if (attrs.isLink) {
            throw RemoteServiceException.Unsupported("루트 밖을 가리킬 수 있는 SFTP 심볼릭 링크 복사는 지원하지 않습니다.")
        }
        if (attrs.isDir) {
            channel.mkdir(target)
            for (rawEntry in channel.ls(source)) {
                currentCoroutineContext().ensureActive()
                val entry = rawEntry as? ChannelSftp.LsEntry ?: continue
                if (entry.filename == "." || entry.filename == "..") continue
                copyEntry(
                    channel,
                    appendName(source, entry.filename),
                    appendName(target, entry.filename),
                )
            }
            return
        }

        channel.get(source).use { input ->
            channel.put(target).use { output ->
                val buffer = ByteArray(256 * 1024)
                var copied = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    copied += count
                }
                output.flush()
                if (copied != attrs.size) {
                    throw RemoteServiceException.Server("SFTP 복사 중 파일 크기가 달라졌습니다.")
                }
            }
        }
        if (channel.lstat(target).size != attrs.size) {
            throw RemoteServiceException.Server("SFTP 복사 결과를 검증하지 못했습니다.")
        }
    }

    private suspend fun deleteEntry(channel: ChannelSftp, path: String) {
        currentCoroutineContext().ensureActive()
        val attrs = channel.lstat(path)
        if (attrs.isLink || !attrs.isDir) {
            channel.rm(path)
            return
        }
        for (rawEntry in channel.ls(path)) {
            currentCoroutineContext().ensureActive()
            val entry = rawEntry as? ChannelSftp.LsEntry ?: continue
            if (entry.filename == "." || entry.filename == "..") continue
            val child = appendName(path, entry.filename)
            // lstat prevents a directory-looking symlink from being traversed.
            deleteEntry(channel, child)
        }
        channel.rmdir(path)
    }

    private fun tryServerSideRename(channel: ChannelSftp, source: String, target: String): Boolean =
        try {
            channel.rename(source, target)
            true
        } catch (_: SftpException) {
            false
        }

    private fun childNames(channel: ChannelSftp, directory: String): Set<String> =
        channel.ls(directory).mapNotNull { rawEntry ->
            val entry = rawEntry as? ChannelSftp.LsEntry ?: return@mapNotNull null
            entry.filename.takeUnless { it == "." || it == ".." }
        }.toSet()

    private fun entryExists(channel: ChannelSftp, path: String): Boolean =
        try {
            channel.lstat(path)
            true
        } catch (error: SftpException) {
            if (error.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) false else throw error
        }

    private fun requireExistingInsideRoot(channel: ChannelSftp, path: String): String {
        val safePath = normalizeInsideRoot(path)
        val canonicalRoot = channel.realpath(connection.normalizedRootPath)
        val canonicalPath = channel.realpath(safePath)
        if (!isSameOrDescendant(canonicalPath, canonicalRoot)) {
            throw RemoteServiceException.Server("SFTP 시작 위치 밖의 경로에는 접근할 수 없습니다.")
        }
        return canonicalPath
    }

    private fun requireDirectoryInsideRoot(channel: ChannelSftp, path: String) {
        requireExistingInsideRoot(channel, path)
        if (!channel.lstat(path).isDir) {
            throw RemoteServiceException.Server("대상 경로가 폴더가 아닙니다.")
        }
    }

    private fun normalizeInsideRoot(path: String): String =
        SftpPathPolicy.normalize(path, connection.normalizedRootPath)

    private fun requireNotConnectionRoot(path: String) {
        if (SftpPathPolicy.normalize(path, connection.normalizedRootPath) ==
            SftpPathPolicy.normalize(connection.normalizedRootPath, connection.normalizedRootPath)
        ) {
            throw RemoteServiceException.Server("연결의 시작 폴더 자체는 변경할 수 없습니다.")
        }
    }

    private suspend fun <T> withSftp(block: suspend (ChannelSftp) -> T): T =
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            val repository = PinnedHostKeyRepository(connection.trustedHostKey)
            val jsch = JSch().apply { setHostKeyRepository(repository) }
            val session = jsch.getSession(connection.username, connection.host, connection.port)
            val credential = SftpCredential.parse(credentialValue)
            var channel: ChannelSftp? = null
            try {
                when (credential) {
                    is SftpCredential.Password -> session.setPassword(credential.value)
                    is SftpCredential.PrivateKey -> {
                        try {
                            jsch.addIdentity("NasFinder-${connection.id}", credential.encoded, null, null)
                        } finally {
                            credential.encoded.fill(0)
                        }
                    }
                }
                session.setConfig("StrictHostKeyChecking", "yes")
                session.setConfig(
                    "PreferredAuthentications",
                    if (credential is SftpCredential.PrivateKey) "publickey" else "password",
                )
                session.timeout = 30_000
                session.connect(30_000)
                currentCoroutineContext().ensureActive()
                channel = session.openChannel("sftp") as ChannelSftp
                channel.connect(20_000)
                block(channel)
            } catch (error: JSchException) {
                repository.rejection?.let { throw it }
                if (error.message?.contains("Auth fail", ignoreCase = true) == true) {
                    throw RemoteServiceException.Authentication()
                }
                throw RemoteServiceException.Connection(
                    "SFTP 서버에 연결할 수 없습니다: ${error.message}",
                    error,
                )
            } catch (error: SftpHostKeyTrustRequired) {
                throw error
            } catch (error: RemoteServiceException) {
                throw error
            } catch (error: SftpException) {
                throw RemoteServiceException.Server("SFTP 작업을 완료하지 못했습니다: ${error.message}")
            } finally {
                runCatching { if (channel?.isConnected == true) channel.disconnect() }
                runCatching { if (session.isConnected) session.disconnect() }
                runCatching { jsch.removeAllIdentity() }
            }
        }
}

internal sealed interface SftpCredential {
    data class Password(val value: String) : SftpCredential
    class PrivateKey internal constructor(internal val encoded: ByteArray) : SftpCredential

    companion object {
        fun parse(value: String): SftpCredential {
            val normalizedStart = value.trimStart()
            val header = PRIVATE_KEY_HEADERS.firstOrNull(normalizedStart::startsWith)
                ?: return Password(value)
            if (header == ENCRYPTED_PKCS8_HEADER || normalizedStart.lineSequence().any {
                    it.trim().equals("Proc-Type: 4,ENCRYPTED", ignoreCase = true)
                }
            ) {
                throw RemoteServiceException.Unsupported(
                    "현재 연결 모델에는 개인키 암호를 별도로 저장할 수 없어 암호화되지 않은 SFTP 개인키만 지원합니다.",
                )
            }
            if (header == OPENSSH_HEADER && opensshCipherName(normalizedStart) != "none") {
                throw RemoteServiceException.Unsupported(
                    "현재 연결 모델에는 개인키 암호를 별도로 저장할 수 없어 암호화되지 않은 SFTP 개인키만 지원합니다.",
                )
            }
            return PrivateKey(normalizedStart.toByteArray(Charsets.UTF_8))
        }

        private fun opensshCipherName(value: String): String {
            val payload = value.lineSequence()
                .filterNot { it.trim().startsWith("-----") }
                .joinToString("") { it.trim() }
            val decoded = try {
                Base64.getDecoder().decode(payload)
            } catch (_: IllegalArgumentException) {
                throw RemoteServiceException.Authentication("SFTP 개인키 형식이 올바르지 않습니다.")
            }
            try {
                val magic = "openssh-key-v1\u0000".toByteArray(Charsets.US_ASCII)
                if (decoded.size < magic.size + 4 || !decoded.copyOfRange(0, magic.size).contentEquals(magic)) {
                    throw RemoteServiceException.Authentication("SFTP 개인키 형식이 올바르지 않습니다.")
                }
                val lengthOffset = magic.size
                val length = ((decoded[lengthOffset].toInt() and 0xff) shl 24) or
                    ((decoded[lengthOffset + 1].toInt() and 0xff) shl 16) or
                    ((decoded[lengthOffset + 2].toInt() and 0xff) shl 8) or
                    (decoded[lengthOffset + 3].toInt() and 0xff)
                val start = lengthOffset + 4
                if (length <= 0 || start + length > decoded.size) {
                    throw RemoteServiceException.Authentication("SFTP 개인키 형식이 올바르지 않습니다.")
                }
                return decoded.copyOfRange(start, start + length).toString(Charsets.US_ASCII)
            } finally {
                decoded.fill(0)
            }
        }

        private const val OPENSSH_HEADER = "-----BEGIN OPENSSH PRIVATE KEY-----"
        private const val ENCRYPTED_PKCS8_HEADER = "-----BEGIN ENCRYPTED PRIVATE KEY-----"
        private val PRIVATE_KEY_HEADERS = listOf(
            OPENSSH_HEADER,
            "-----BEGIN PRIVATE KEY-----",
            ENCRYPTED_PKCS8_HEADER,
            "-----BEGIN RSA PRIVATE KEY-----",
            "-----BEGIN EC PRIVATE KEY-----",
            "-----BEGIN DSA PRIVATE KEY-----",
        )
    }
}

private class CancellationProgressMonitor(private val job: Job?) : SftpProgressMonitor {
    override fun init(op: Int, src: String?, dest: String?, max: Long) = Unit
    override fun count(count: Long): Boolean = job?.isActive != false
    override fun end() = Unit
}

private class PinnedHostKeyRepository(private val expectedKey: String?) : HostKeyRepository {
    var rejection: SftpHostKeyTrustRequired? = null
        private set

    override fun check(host: String, key: ByteArray): Int {
        val algorithm = sshKeyAlgorithm(key)
        val encoded = Base64.getEncoder().encodeToString(key)
        val serialized = "$algorithm $encoded"
        val fingerprint = sha256Fingerprint(key)
        val expected = expectedKey?.trim()?.takeIf { it.isNotEmpty() }
        val matches = expected != null && (
            expected == serialized ||
                expected == encoded ||
                expected == fingerprint
            )
        if (matches) return HostKeyRepository.OK

        rejection = SftpHostKeyTrustRequired(
            serializedHostKey = serialized,
            fingerprint = fingerprint,
            isChangedKey = expected != null,
        )
        return if (expected == null) HostKeyRepository.NOT_INCLUDED else HostKeyRepository.CHANGED
    }

    override fun add(hostkey: HostKey, ui: UserInfo?) = Unit
    override fun remove(host: String, type: String) = Unit
    override fun remove(host: String, type: String, key: ByteArray) = Unit
    override fun getKnownHostsRepositoryID(): String = "NasFinder pinned host key"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String, type: String?): Array<HostKey> = emptyArray()
}

private object SftpPathPolicy {
    fun normalize(path: String, rootPath: String): String {
        if (path.isBlank() || '\u0000' in path || '\r' in path || '\n' in path) {
            throw RemoteServiceException.Server("사용할 수 없는 SFTP 경로입니다.")
        }
        val root = parse(rootPath)
        val candidate = parse(path)
        if (root.absolute != candidate.absolute ||
            candidate.components.size < root.components.size ||
            candidate.components.take(root.components.size) != root.components
        ) {
            throw RemoteServiceException.Server("SFTP 시작 위치 밖의 경로에는 접근할 수 없습니다.")
        }
        return candidate.render(dotPrefixed = !root.absolute && root.components.isEmpty())
    }

    private fun parse(path: String): ParsedPath {
        val raw = path.split('/').filter { it.isNotEmpty() }
        if (raw.any { it == ".." }) {
            throw RemoteServiceException.Server("SFTP 시작 위치 밖의 경로에는 접근할 수 없습니다.")
        }
        return ParsedPath(
            absolute = path.startsWith('/'),
            components = raw.filter { it != "." },
        )
    }

    private data class ParsedPath(val absolute: Boolean, val components: List<String>) {
        fun render(dotPrefixed: Boolean): String {
            if (absolute) return if (components.isEmpty()) "/" else "/${components.joinToString("/")}"
            if (components.isEmpty()) return "."
            val value = components.joinToString("/")
            return if (dotPrefixed) "./$value" else value
        }
    }
}

private fun validateName(name: String) {
    if (name.isBlank() || name == "." || name == ".." ||
        '/' in name || '\u0000' in name || '\r' in name || '\n' in name
    ) {
        throw RemoteServiceException.Server("사용할 수 없는 파일 또는 폴더 이름입니다.")
    }
}

private fun keepBothName(originalName: String, existingNames: Collection<String>): String {
    if (originalName !in existingNames) return originalName
    val dot = originalName.lastIndexOf('.').takeIf { it > 0 } ?: -1
    val extension = if (dot >= 0) originalName.substring(dot) else ""
    val stem = (if (dot >= 0) originalName.substring(0, dot) else originalName)
        .replace(Regex(" \\(\\d+\\)$"), "")
    for (index in 1 until Int.MAX_VALUE) {
        val candidate = "$stem ($index)$extension"
        if (candidate !in existingNames) return candidate
    }
    throw RemoteServiceException.Server("같은 이름의 항목이 너무 많습니다.")
}

private fun appendName(parent: String, name: String): String {
    validateName(name)
    return when {
        parent == "/" -> "/$name"
        parent == "." -> "./$name"
        parent.endsWith('/') -> "$parent$name"
        else -> "$parent/$name"
    }
}

private fun parentPath(path: String): String = when {
    path == "/" || path == "." -> path
    path.startsWith("./") && !path.removePrefix("./").contains('/') -> "."
    path.startsWith('/') -> path.substringBeforeLast('/').ifBlank { "/" }
    else -> path.substringBeforeLast('/', ".")
}

private fun isSameOrDescendant(path: String, root: String): Boolean {
    val normalizedPath = path.trimEnd('/').ifEmpty { "/" }
    val normalizedRoot = root.trimEnd('/').ifEmpty { "/" }
    return normalizedPath == normalizedRoot ||
        normalizedPath.startsWith(if (normalizedRoot == "/") "/" else "$normalizedRoot/")
}

private fun sha256Fingerprint(key: ByteArray): String =
    "SHA256:" + Base64.getEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(key))

private fun sshKeyAlgorithm(key: ByteArray): String {
    if (key.size < 4) return "unknown"
    val length = ((key[0].toInt() and 0xff) shl 24) or
        ((key[1].toInt() and 0xff) shl 16) or
        ((key[2].toInt() and 0xff) shl 8) or
        (key[3].toInt() and 0xff)
    if (length <= 0 || length > key.size - 4) return "unknown"
    return key.copyOfRange(4, 4 + length).toString(Charsets.US_ASCII)
}
