package com.armsone.nasfinder.network

import com.armsone.nasfinder.model.ConnectionKind
import com.armsone.nasfinder.model.RemoteConnection
import com.armsone.nasfinder.model.RemoteFileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import java.io.Closeable
import java.io.ByteArrayOutputStream
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory

sealed class RemoteServiceException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Authentication(message: String = "로그인 정보가 올바르지 않습니다.") : RemoteServiceException(message)
    class Connection(message: String, cause: Throwable? = null) : RemoteServiceException(message, cause)
    class Unsupported(message: String) : RemoteServiceException(message)
    class Server(message: String) : RemoteServiceException(message)
}

interface RemoteFileService : Closeable {
    suspend fun testConnection()
    suspend fun list(path: String): List<RemoteFileItem>
    suspend fun download(item: RemoteFileItem, destination: File, progress: (Long, Long) -> Unit = { _, _ -> })
    val supportsRangeStreaming: Boolean get() = false
    suspend fun readRange(item: RemoteFileItem, offset: Long, length: Int): ByteArray =
        throw RemoteServiceException.Unsupported("이 연결 방식은 범위 읽기를 지원하지 않습니다.")
    suspend fun createFolder(parent: String, name: String): Unit = unsupported("폴더 생성")
    suspend fun rename(item: RemoteFileItem, newName: String): Unit = unsupported("이름 변경")
    suspend fun delete(items: List<RemoteFileItem>): Unit = unsupported("삭제")
    suspend fun move(items: List<RemoteFileItem>, destination: String): Unit = unsupported("이동")
    suspend fun copy(items: List<RemoteFileItem>, destination: String): Unit = unsupported("복사")
    suspend fun upload(parent: String, source: File): Unit = unsupported("업로드")
    override fun close() = Unit

    private fun unsupported(operation: String): Nothing =
        throw RemoteServiceException.Unsupported("이 연결 방식의 $operation 기능은 아직 Android에서 준비 중입니다.")
}

object RemoteFileServiceFactory {
    fun create(connection: RemoteConnection, password: String): RemoteFileService = when (connection.kind) {
        ConnectionKind.SYNOLOGY -> SynologyFileService(connection, password)
        ConnectionKind.SFTP -> SftpFileService(connection, password)
        ConnectionKind.SMB -> SmbFileService(connection, password)
        ConnectionKind.WEBDAV -> WebDavFileService(connection, password)
        ConnectionKind.FTP -> FtpFileService(connection, password)
        ConnectionKind.DROPBOX, ConnectionKind.ONEDRIVE, ConnectionKind.GOOGLE_DRIVE ->
            CloudDriveFileService(connection, password)
        else -> UnsupportedRemoteFileService(connection)
    }
}

private class UnsupportedRemoteFileService(private val connection: RemoteConnection) : RemoteFileService {
    override suspend fun testConnection() {
        throw RemoteServiceException.Unsupported("${connection.kind.title} 네이티브 엔진은 구현 명세에 포함되어 있으며 후속 구현이 필요합니다.")
    }
    override suspend fun list(path: String): List<RemoteFileItem> = testConnection().let { emptyList() }
    override suspend fun download(item: RemoteFileItem, destination: File, progress: (Long, Long) -> Unit) = testConnection()
}

private fun defaultClient() = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .followRedirects(true)
    .build()

class SynologyFileService(
    private val connection: RemoteConnection,
    credentialValue: String,
    private val client: OkHttpClient = defaultClient(),
) : RemoteFileService {
    override val supportsRangeStreaming: Boolean = true
    private val credential = SynologyCredential.parse(credentialValue)
    @Volatile private var sid: String? = null
    private val sessionLock = Any()
    private val baseUrl = connection.endpoint

    override suspend fun testConnection() = withContext(Dispatchers.IO) {
        ensureSession()
        Unit
    }

    override suspend fun list(path: String): List<RemoteFileItem> = withContext(Dispatchers.IO) {
        requireRemotePath(path, connection.normalizedRootPath)
        val isRoot = path == "/"
        var responseSessionId = ""
        val json = authenticatedJson { sessionId ->
            responseSessionId = sessionId
            val urlBuilder = "$baseUrl/webapi/entry.cgi".toHttpUrl().newBuilder()
                .addQueryParameter("api", "SYNO.FileStation.List")
                .addQueryParameter("version", "2")
                .addQueryParameter("method", if (isRoot) "list_share" else "list")
                .addQueryParameter("additional", "[\"real_path\",\"size\",\"time\",\"type\"]")
                .addQueryParameter("_sid", sessionId)
            if (!isRoot) urlBuilder.addQueryParameter("folder_path", path)
            Request.Builder().url(urlBuilder.build()).get().build()
        }
        val data = json.getJSONObject("data")
        val files = data.optJSONArray("files") ?: data.optJSONArray("shares") ?: JSONArray()
        buildList {
            for (index in 0 until files.length()) {
                val file = files.getJSONObject(index)
                val additional = file.optJSONObject("additional")
                val time = additional?.optJSONObject("time")?.optLong("mtime", 0L) ?: 0L
                val itemPath = file.optString("path").ifBlank { joinRemotePath(path, file.getString("name")) }
                add(
                    RemoteFileItem(
                        id = itemPath,
                        name = file.getString("name"),
                        path = itemPath,
                        isDirectory = file.optBoolean("isdir"),
                        size = additional?.optLong("size", 0L) ?: 0L,
                        modifiedAt = time.takeIf { it > 0 }?.let { Instant.ofEpochSecond(it) },
                        thumbnailUrl = thumbnailUrl(itemPath, responseSessionId),
                    )
                )
            }
        }
    }

    override suspend fun download(item: RemoteFileItem, destination: File, progress: (Long, Long) -> Unit) =
        withContext(Dispatchers.IO) {
            requireRemotePath(item.path, connection.normalizedRootPath)
            val url = "$baseUrl/webapi/entry.cgi".toHttpUrl().newBuilder()
                .addQueryParameter("api", "SYNO.FileStation.Download")
                .addQueryParameter("version", "2")
                .addQueryParameter("method", "download")
                .addQueryParameter("path", item.path)
                .addQueryParameter("mode", "download")
                .addQueryParameter("_sid", ensureSession())
                .build()
            httpRangeDownload(client, Request.Builder().url(url), destination, item.size, "Synology", progress)
        }

    override suspend fun readRange(item: RemoteFileItem, offset: Long, length: Int): ByteArray =
        withContext(Dispatchers.IO) {
            requireRemotePath(item.path, connection.normalizedRootPath)
            RemoteRangeContract.validate(item, offset, length)
            val url = "$baseUrl/webapi/entry.cgi".toHttpUrl().newBuilder()
                .addQueryParameter("api", "SYNO.FileStation.Download")
                .addQueryParameter("version", "2").addQueryParameter("method", "download")
                .addQueryParameter("path", item.path).addQueryParameter("mode", "download")
                .addQueryParameter("_sid", ensureSession()).build()
            httpBoundedRangeRead(
                client, Request.Builder().url(url), item, offset, length, "Synology",
            )
        }

    override suspend fun createFolder(parent: String, name: String) = withContext(Dispatchers.IO) {
        requireRemotePath(parent, connection.normalizedRootPath)
        validateRemoteName(name)
        apiJson(
            api = "SYNO.FileStation.CreateFolder",
            version = "2",
            method = "create",
            parameters = mapOf(
                "folder_path" to jsonArray(parent),
                "name" to jsonArray(name),
                "force_parent" to "false",
                "additional" to "[\"size\",\"time\",\"type\"]",
            ),
        )
        Unit
    }

    override suspend fun rename(item: RemoteFileItem, newName: String) = withContext(Dispatchers.IO) {
        requireRemotePath(item.path, connection.normalizedRootPath)
        validateRemoteName(newName)
        apiJson(
            api = "SYNO.FileStation.Rename",
            version = "2",
            method = "rename",
            parameters = mapOf(
                "path" to jsonArray(item.path),
                "name" to jsonArray(newName),
                "additional" to "[\"size\",\"time\",\"type\"]",
            ),
        )
        Unit
    }

    override suspend fun delete(items: List<RemoteFileItem>) = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext
        items.forEach { requireRemotePath(it.path, connection.normalizedRootPath) }
        val taskId = apiJson(
            api = "SYNO.FileStation.Delete",
            version = "2",
            method = "start",
            parameters = mapOf(
                "path" to jsonArray(*items.map { it.path }.toTypedArray()),
                "recursive" to items.any { it.isDirectory }.toString(),
                "accurate_progress" to "true",
            ),
        ).getJSONObject("data").getString("taskid")
        pollTask("SYNO.FileStation.Delete", "2", taskId)
    }

    override suspend fun move(items: List<RemoteFileItem>, destination: String) =
        transfer(items, destination, removeSource = true)

    override suspend fun copy(items: List<RemoteFileItem>, destination: String) =
        transfer(items, destination, removeSource = false)

    override suspend fun upload(parent: String, source: File) = withContext(Dispatchers.IO) {
        if (!source.isFile) throw RemoteServiceException.Server("업로드할 파일을 찾을 수 없습니다.")
        requireRemotePath(parent, connection.normalizedRootPath)
        validateRemoteName(source.name)
        val fileName = keepBothName(source.name, list(parent).map { it.name })
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("api", "SYNO.FileStation.Upload")
            .addFormDataPart("version", "2")
            .addFormDataPart("method", "upload")
            .addFormDataPart("path", parent)
            .addFormDataPart("create_parents", "false")
            .addFormDataPart("file", fileName, source.asRequestBody("application/octet-stream".toMediaType()))
            .build()
        authenticatedJson { sessionId ->
            val url = "$baseUrl/webapi/entry.cgi".toHttpUrl().newBuilder()
                .addQueryParameter("_sid", sessionId)
                .build()
            Request.Builder().url(url).post(body).build()
        }
        Unit
    }

    override fun close() {
        val sessionId = sid ?: return
        sid = null
        val url = "$baseUrl/webapi/auth.cgi".toHttpUrl().newBuilder()
            .addQueryParameter("api", "SYNO.API.Auth").addQueryParameter("version", "6")
            .addQueryParameter("method", "logout").addQueryParameter("session", "FileStation")
            .addQueryParameter("_sid", sessionId).build()
        client.newCall(Request.Builder().url(url).build()).enqueue(DiscardingCallback)
    }

    private fun ensureSession(): String = sid ?: synchronized(sessionLock) {
        sid ?: run {
            val body = FormBody.Builder()
                .add("api", "SYNO.API.Auth").add("version", "6").add("method", "login")
                .add("account", connection.username).add("passwd", credential.password)
                .add("session", "FileStation").add("format", "sid")
                .apply { credential.otp?.let { add("otp_code", it) } }
                .build()
            executeJson(
                Request.Builder().url("$baseUrl/webapi/auth.cgi").post(body).build(),
                authenticationCodes = setOf(400, 401, 402, 403, 404),
            )
                .getJSONObject("data").getString("sid").also { sid = it }
        }
    }

    /** Rebuilds an authenticated request once after DSM reports an expired File Station SID. */
    private fun authenticatedJson(request: (String) -> Request): JSONObject {
        val attemptedSid = ensureSession()
        return try {
            executeJson(request(attemptedSid))
        } catch (error: RemoteServiceException.Authentication) {
            synchronized(sessionLock) {
                if (sid == attemptedSid) sid = null
            }
            executeJson(request(ensureSession()))
        }
    }

    private suspend fun transfer(items: List<RemoteFileItem>, destination: String, removeSource: Boolean) =
        withContext(Dispatchers.IO) {
            if (items.isEmpty()) return@withContext
            requireRemotePath(destination, connection.normalizedRootPath)
            items.forEach { requireRemotePath(it.path, connection.normalizedRootPath) }
            val taskId = apiJson(
                api = "SYNO.FileStation.CopyMove",
                version = "3",
                method = "start",
                parameters = mapOf(
                    "path" to jsonArray(*items.map { it.path }.toTypedArray()),
                    "dest_folder_path" to JSONObject.quote(destination),
                    "remove_src" to removeSource.toString(),
                    "accurate_progress" to "true",
                ),
            ).getJSONObject("data").getString("taskid")
            pollTask("SYNO.FileStation.CopyMove", "3", taskId)
        }

    private fun pollTask(api: String, version: String, taskId: String) {
        while (true) {
            val status = apiJson(
                api = api,
                version = version,
                method = "status",
                parameters = mapOf("taskid" to JSONObject.quote(taskId)),
            ).getJSONObject("data")
            if (status.optBoolean("finished")) return
            Thread.sleep(250)
        }
    }

    private fun apiJson(
        api: String,
        version: String,
        method: String,
        parameters: Map<String, String>,
    ): JSONObject {
        return authenticatedJson { sessionId ->
            val builder = "$baseUrl/webapi/entry.cgi".toHttpUrl().newBuilder()
                .addQueryParameter("api", api)
                .addQueryParameter("version", version)
                .addQueryParameter("method", method)
                .addQueryParameter("_sid", sessionId)
            parameters.forEach { (key, value) -> builder.addQueryParameter(key, value) }
            Request.Builder().url(builder.build()).get().build()
        }
    }

    private fun executeJson(
        request: Request,
        authenticationCodes: Set<Int> = setOf(106, 107, 119),
    ): JSONObject = try {
        client.newCall(request).execute().use { response ->
            response.requireSuccess()
            val json = JSONObject(response.body.string())
            if (!json.optBoolean("success")) {
                val code = json.optJSONObject("error")?.optInt("code")
                if (code in authenticationCodes) throw RemoteServiceException.Authentication()
                throw RemoteServiceException.Server("Synology API 오류${code?.let { " ($it)" } ?: ""}")
            }
            json
        }
    } catch (error: RemoteServiceException) {
        throw error
    } catch (error: Exception) {
        throw RemoteServiceException.Connection("Synology 서버에 연결할 수 없습니다.", error)
    }

    private fun thumbnailUrl(path: String, sessionId: String): String =
        "$baseUrl/webapi/entry.cgi".toHttpUrl().newBuilder()
            .addQueryParameter("api", "SYNO.FileStation.Thumb")
            .addQueryParameter("version", "2").addQueryParameter("method", "get")
            .addQueryParameter("path", path).addQueryParameter("size", "large")
            .addQueryParameter("_sid", sessionId).build().toString()
}

/**
 * Small passive-mode FTP implementation for trusted LAN/VPN servers. FTP sends
 * credentials and file contents without encryption; callers must keep the iOS
 * warning visible instead of presenting this as a secure Internet transport.
 */
class FtpFileService(
    private val connection: RemoteConnection,
    private val password: String,
) : RemoteFileService {
    override val supportsRangeStreaming: Boolean = true
    override suspend fun testConnection() = withContext(Dispatchers.IO) {
        FtpSession(connection, password).use { it.login() }
    }

    override suspend fun list(path: String): List<RemoteFileItem> = withContext(Dispatchers.IO) {
        requireRemotePath(path, connection.normalizedRootPath)
        runCatching { directoryListing("MLSD", path).mapNotNull { parseMlsd(it, path) } }
            .getOrElse { directoryListing("LIST", path).mapNotNull { parseUnixList(it, path) } }
    }

    override suspend fun download(
        item: RemoteFileItem,
        destination: File,
        progress: (Long, Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        requireRemotePath(item.path, connection.normalizedRootPath)
        try {
            FtpSession(connection, password).use { session ->
                session.login()
                session.openPassiveDataSocket().use { dataSocket ->
                    session.command("RETR ${safeFtpArgument(item.path)}", 100..199)
                    var completed = 0L
                    destination.outputStream().buffered().use { output ->
                        dataSocket.getInputStream().buffered().use { input ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                kotlin.coroutines.coroutineContext.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (item.size > 0 && count.toLong() > item.size - completed) {
                                    throw RemoteServiceException.Server("FTP 다운로드 크기가 원격 파일 크기를 초과했습니다.")
                                }
                                output.write(buffer, 0, count)
                                completed += count
                                progress(completed, item.size)
                            }
                        }
                    }
                    session.readReply(200..299)
                    if (item.size > 0 && completed != item.size) {
                        throw RemoteServiceException.Server("파일을 끝까지 내려받지 못했습니다.")
                    }
                }
            }
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
    }

    override suspend fun readRange(item: RemoteFileItem, offset: Long, length: Int): ByteArray =
        withContext(Dispatchers.IO) {
            requireRemotePath(item.path, connection.normalizedRootPath)
            RemoteRangeContract.validate(item, offset, length)
            if (item.size > 0 && offset >= item.size) return@withContext ByteArray(0)
            FtpSession(connection, password).use { session ->
                session.login()
                try {
                    session.command("REST $offset", 300..399)
                } catch (_: RemoteServiceException.Server) {
                    throw RemoteServiceException.Unsupported("FTP 서버가 REST 범위 읽기를 지원하지 않습니다.")
                }
                session.openPassiveDataSocket().use { dataSocket ->
                    session.command("RETR ${safeFtpArgument(item.path)}", 100..199)
                    val output = ByteArrayOutputStream(minOf(length, 256 * 1024))
                    var remaining = length
                    var reachedEof = false
                    dataSocket.getInputStream().buffered().use { input ->
                        val buffer = ByteArray(minOf(64 * 1024, length))
                        while (remaining > 0) {
                            kotlin.coroutines.coroutineContext.ensureActive()
                            val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                            if (count < 0) {
                                reachedEof = true
                                break
                            }
                            output.write(buffer, 0, count)
                            remaining -= count
                        }
                    }
                    if (reachedEof) session.readReply(200..299)
                    output.toByteArray()
                }
            }
        }

    override suspend fun upload(parent: String, source: File) = withContext(Dispatchers.IO) {
        if (!source.isFile) throw RemoteServiceException.Server("업로드할 파일을 찾을 수 없습니다.")
        requireRemotePath(parent, connection.normalizedRootPath)
        validateRemoteName(source.name)
        val fileName = keepBothName(source.name, list(parent).map { it.name })
        val destination = joinRemotePath(parent, fileName)
        FtpSession(connection, password).use { session ->
            session.login()
            session.openPassiveDataSocket().use { dataSocket ->
                session.command("STOR ${safeFtpArgument(destination)}", 100..199)
                source.inputStream().buffered().use { input ->
                    dataSocket.getOutputStream().buffered().use { output -> input.copyTo(output) }
                }
                session.readReply(200..299)
            }
        }
        Unit
    }

    override suspend fun createFolder(parent: String, name: String) = withContext(Dispatchers.IO) {
        requireRemotePath(parent, connection.normalizedRootPath)
        validateRemoteName(name)
        FtpSession(connection, password).use { session ->
            session.login()
            session.command("MKD ${safeFtpArgument(joinRemotePath(parent, name))}", 200..299)
        }
        Unit
    }

    override suspend fun rename(item: RemoteFileItem, newName: String) = withContext(Dispatchers.IO) {
        requireRemotePath(item.path, connection.normalizedRootPath)
        validateRemoteName(newName)
        val parent = item.path.substringBeforeLast('/', "/").ifBlank { "/" }
        FtpSession(connection, password).use { session ->
            session.login()
            session.command("RNFR ${safeFtpArgument(item.path)}", 300..399)
            session.command("RNTO ${safeFtpArgument(joinRemotePath(parent, newName))}", 200..299)
        }
        Unit
    }

    override suspend fun delete(items: List<RemoteFileItem>) = withContext(Dispatchers.IO) {
        items.forEach { deleteRecursively(it) }
    }

    override suspend fun copy(items: List<RemoteFileItem>, destination: String) =
        transfer(items, destination, removeSource = false)

    override suspend fun move(items: List<RemoteFileItem>, destination: String) =
        transfer(items, destination, removeSource = true)

    private suspend fun transfer(
        items: List<RemoteFileItem>,
        destination: String,
        removeSource: Boolean,
    ) = withContext(Dispatchers.IO) {
        requireRemotePath(destination, connection.normalizedRootPath)
        val occupied = list(destination).mapTo(mutableSetOf()) { it.name }
        for (item in items) {
            kotlin.coroutines.coroutineContext.ensureActive()
            requireRemotePath(item.path, connection.normalizedRootPath)
            validateRemoteName(item.name)
            if (item.path == connection.normalizedRootPath) {
                throw RemoteServiceException.Server("FTP 연결의 시작 폴더는 복사하거나 이동할 수 없습니다.")
            }
            if (item.isDirectory && FtpTransferPolicy.isSameOrDescendant(destination, item.path)) {
                throw RemoteServiceException.Server("FTP 폴더를 자기 자신 안으로 복사하거나 이동할 수 없습니다.")
            }
            if (removeSource && FtpTransferPolicy.parent(item.path) == destination) continue
            val finalName = keepBothName(item.name, occupied.toList())
            occupied += finalName
            val finalPath = joinRemotePath(destination, finalName)
            if (removeSource && tryFtpRename(item.path, finalPath)) continue

            val stagingPath = joinRemotePath(destination, ".nasfinder-copy-${UUID.randomUUID()}")
            var stagingCreated = false
            try {
                copyFtpEntry(item, stagingPath) { stagingCreated = true }
                if (!tryFtpRename(stagingPath, finalPath)) {
                    throw RemoteServiceException.Server("FTP 복사 결과의 이름을 확정하지 못했습니다.")
                }
                stagingCreated = false
            } finally {
                if (stagingCreated) runCatching {
                    deleteRecursively(item.copy(id = stagingPath, name = stagingPath.substringAfterLast('/'), path = stagingPath))
                }
            }
            if (removeSource) deleteRecursively(item)
        }
    }

    private suspend fun copyFtpEntry(
        source: RemoteFileItem,
        targetPath: String,
        depth: Int = 0,
        onCreated: () -> Unit = {},
    ) {
        kotlin.coroutines.coroutineContext.ensureActive()
        if (depth > MAX_FTP_RECURSION_DEPTH) {
            throw RemoteServiceException.Server("FTP 폴더 깊이가 안전 제한을 초과했습니다.")
        }
        requireRemotePath(source.path, connection.normalizedRootPath)
        requireRemotePath(targetPath, connection.normalizedRootPath)
        if (source.isDirectory) {
            FtpSession(connection, password).use { session ->
                session.login()
                session.command("MKD ${safeFtpArgument(targetPath)}", 200..299)
            }
            onCreated()
            for (child in directoryChildrenForTransfer(source.path)) {
                copyFtpEntry(child, joinRemotePath(targetPath, child.name), depth = depth + 1)
            }
            return
        }

        FtpSession(connection, password).use { sourceSession ->
            FtpSession(connection, password).use { targetSession ->
                sourceSession.login()
                targetSession.login()
                sourceSession.openPassiveDataSocket().use { sourceSocket ->
                    targetSession.openPassiveDataSocket().use { targetSocket ->
                        sourceSession.command("RETR ${safeFtpArgument(source.path)}", 100..199)
                        targetSession.command("STOR ${safeFtpArgument(targetPath)}", 100..199)
                        onCreated()
                        var copied = 0L
                        sourceSocket.getInputStream().buffered().use { input ->
                            targetSocket.getOutputStream().buffered().use { output ->
                                val buffer = ByteArray(128 * 1024)
                                while (true) {
                                    kotlin.coroutines.coroutineContext.ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                    copied += count
                                }
                            }
                        }
                        sourceSession.readReply(200..299)
                        targetSession.readReply(200..299)
                        if (source.size > 0 && copied != source.size) {
                            throw RemoteServiceException.Server("FTP 복사 결과 크기가 일치하지 않습니다.")
                        }
                    }
                }
            }
        }
    }

    private suspend fun deleteRecursively(item: RemoteFileItem, depth: Int = 0) {
        kotlin.coroutines.coroutineContext.ensureActive()
        if (depth > MAX_FTP_RECURSION_DEPTH) {
            throw RemoteServiceException.Server("FTP 폴더 깊이가 안전 제한을 초과했습니다.")
        }
        requireRemotePath(item.path, connection.normalizedRootPath)
        if (item.path == connection.normalizedRootPath) {
            throw RemoteServiceException.Server("FTP 연결의 시작 폴더는 삭제할 수 없습니다.")
        }
        if (item.isDirectory) directoryChildrenForTransfer(item.path).forEach { deleteRecursively(it, depth + 1) }
        FtpSession(connection, password).use { session ->
            session.login()
            session.command(
                "${if (item.isDirectory) "RMD" else "DELE"} ${safeFtpArgument(item.path)}",
                200..299,
            )
        }
    }

    private fun tryFtpRename(source: String, destination: String): Boolean = try {
        FtpSession(connection, password).use { session ->
            session.login()
            session.command("RNFR ${safeFtpArgument(source)}", 300..399)
            session.command("RNTO ${safeFtpArgument(destination)}", 200..299)
        }
        true
    } catch (_: RemoteServiceException.Server) {
        false
    }

    private fun directoryListing(command: String, path: String): List<String> =
        FtpSession(connection, password).use { session ->
            session.login()
            session.openPassiveDataSocket().use { dataSocket ->
                session.command("$command ${safeFtpArgument(path)}", 100..199)
                val lines = dataSocket.getInputStream().bufferedReader(StandardCharsets.UTF_8).use { it.readLines() }
                session.readReply(200..299)
                lines
            }
        }

    private fun directoryChildrenForTransfer(path: String): List<RemoteFileItem> {
        val mlsd = runCatching { directoryListing("MLSD", path) }.getOrNull()
        if (mlsd != null) {
            return mlsd.mapNotNull { line ->
                val type = line.substringBefore(' ').split(';').mapNotNull {
                    val parts = it.split('=', limit = 2)
                    if (parts.size == 2 && parts[0].equals("type", ignoreCase = true)) parts[1].lowercase() else null
                }.firstOrNull()
                if (type in setOf("cdir", "pdir")) return@mapNotNull null
                if (type !in setOf("file", "dir")) {
                    throw RemoteServiceException.Unsupported("안전을 위해 FTP 링크나 알 수 없는 항목은 재귀 처리하지 않습니다.")
                }
                parseMlsd(line, path) ?: throw RemoteServiceException.Server("FTP 목록 항목을 안전하게 해석하지 못했습니다.")
            }
        }
        return directoryListing("LIST", path).mapNotNull { line ->
            val kind = line.trim().firstOrNull() ?: return@mapNotNull null
            if (kind !in setOf('d', '-')) {
                throw RemoteServiceException.Unsupported("안전을 위해 FTP 링크나 알 수 없는 항목은 재귀 처리하지 않습니다.")
            }
            parseUnixList(line, path) ?: throw RemoteServiceException.Server("FTP 목록 항목을 안전하게 해석하지 못했습니다.")
        }
    }

    private fun parseMlsd(line: String, parent: String): RemoteFileItem? {
        val separator = line.indexOf(' ')
        if (separator <= 0) return null
        val facts = line.substring(0, separator).split(';').mapNotNull {
            val parts = it.split('=', limit = 2)
            if (parts.size == 2) parts[0].lowercase() to parts[1] else null
        }.toMap()
        val name = line.substring(separator + 1).trim()
        if (name.isBlank() || name == "." || name == "..") return null
        runCatching { validateRemoteName(name) }.getOrElse { return null }
        val type = facts["type"]?.lowercase()
        if (type == "cdir" || type == "pdir") return null
        if (type !in setOf("file", "dir")) return null
        val isDirectory = type == "dir"
        val modified = facts["modify"]?.substringBefore('.')?.let {
            runCatching { LocalDateTime.parse(it, FTP_DATE).toInstant(ZoneOffset.UTC) }.getOrNull()
        }
        val itemPath = joinRemotePath(parent, name)
        return RemoteFileItem(
            id = itemPath,
            name = name,
            path = itemPath,
            isDirectory = isDirectory,
            size = if (isDirectory) 0 else facts["size"]?.toLongOrNull() ?: 0,
            modifiedAt = modified,
        )
    }

    private fun parseUnixList(line: String, parent: String): RemoteFileItem? {
        val fields = line.trim().split(Regex("\\s+"), limit = 9)
        if (fields.size != 9 || fields[0].firstOrNull() !in setOf('d', '-')) return null
        val name = fields[8]
        if (name == "." || name == "..") return null
        runCatching { validateRemoteName(name) }.getOrElse { return null }
        val isDirectory = fields[0].startsWith('d')
        val itemPath = joinRemotePath(parent, name)
        return RemoteFileItem(
            id = itemPath,
            name = name,
            path = itemPath,
            isDirectory = isDirectory,
            size = if (isDirectory) 0 else fields[4].toLongOrNull() ?: 0,
        )
    }

    private companion object {
        val FTP_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
        const val MAX_FTP_RECURSION_DEPTH = 128
    }
}

private data class FtpReply(val code: Int, val message: String)

private class FtpSession(
    private val connection: RemoteConnection,
    private val password: String,
) : Closeable {
    private val socket = Socket()
    private lateinit var reader: BufferedReader
    private lateinit var writer: BufferedWriter

    fun login() {
        try {
            socket.connect(InetSocketAddress(connection.host, connection.port), 15_000)
            socket.soTimeout = 60_000
            reader = InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1).buffered()
            writer = OutputStreamWriter(socket.getOutputStream(), StandardCharsets.ISO_8859_1).buffered()
            readReply(200..399)
            val user = command("USER ${safeFtpArgument(connection.username)}", 200..399)
            if (user.code != 230) command("PASS ${safeFtpArgument(password)}", 200..299)
            command("TYPE I", 200..299)
        } catch (error: RemoteServiceException) {
            close()
            throw error
        } catch (error: Exception) {
            close()
            throw RemoteServiceException.Connection("FTP 서버에 연결할 수 없습니다: ${error.message}", error)
        }
    }

    fun command(command: String, accepted: IntRange): FtpReply {
        if (command.contains('\r') || command.contains('\n')) {
            throw RemoteServiceException.Server("FTP 명령에 허용되지 않은 문자가 있습니다.")
        }
        writer.write(command)
        writer.write("\r\n")
        writer.flush()
        return readReply(accepted)
    }

    fun readReply(accepted: IntRange): FtpReply {
        val first = reader.readLine() ?: throw RemoteServiceException.Connection("FTP 연결이 종료되었습니다.")
        val code = first.take(3).toIntOrNull()
            ?: throw RemoteServiceException.Server("FTP 응답을 해석할 수 없습니다: $first")
        val lines = mutableListOf(first)
        if (first.getOrNull(3) == '-') {
            while (true) {
                val line = reader.readLine()
                    ?: throw RemoteServiceException.Connection("FTP 연결이 종료되었습니다.")
                lines += line
                if (line.startsWith("$code ")) break
            }
        }
        val message = lines.joinToString("\n")
        if (code !in accepted) {
            if (code == 530) throw RemoteServiceException.Authentication()
            throw RemoteServiceException.Server("FTP 서버가 요청을 거부했습니다. (응답 코드 $code)")
        }
        return FtpReply(code, message)
    }

    fun openPassiveDataSocket(): Socket {
        val endpoint = runCatching {
            val reply = command("EPSV", 200..299)
            val port = Regex("\\(\\|\\|\\|(\\d+)\\|\\)").find(reply.message)
                ?.groupValues?.get(1)?.toIntOrNull()
                ?: throw RemoteServiceException.Server("FTP EPSV 응답을 해석할 수 없습니다.")
            connection.host to port
        }.getOrElse {
            val reply = command("PASV", 200..299)
            val values = Regex("(\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)")
                .find(reply.message)?.groupValues?.drop(1)?.map(String::toInt)
                ?: throw RemoteServiceException.Server("FTP PASV 응답을 해석할 수 없습니다.")
            // Never follow a PASV-advertised host: it enables FTP bounce/SSRF and often contains a NAT-private address.
            connection.host to (values[4] * 256 + values[5])
        }
        return Socket().apply {
            connect(InetSocketAddress(endpoint.first, endpoint.second), 15_000)
            soTimeout = 60_000
        }
    }

    override fun close() {
        if (!socket.isClosed && socket.isConnected) runCatching {
            writer.write("QUIT\r\n")
            writer.flush()
        }
        runCatching { socket.close() }
    }
}

class WebDavFileService(
    private val connection: RemoteConnection,
    private val password: String,
    private val client: OkHttpClient = defaultClient(),
) : RemoteFileService {
    override val supportsRangeStreaming: Boolean = true
    override suspend fun testConnection() = withContext(Dispatchers.IO) { list(connection.normalizedRootPath); Unit }

    override suspend fun list(path: String): List<RemoteFileItem> = withContext(Dispatchers.IO) {
        val url = remoteUrl(path)
        val request = authorized(Request.Builder().url(url).header("Depth", "1"))
            .method("PROPFIND", WEBDAV_XML_BODY).build()
        executeDav(request).use { response ->
            val document = try {
                secureDocumentBuilderFactory().newDocumentBuilder().parse(response.body.byteStream())
            } catch (_: Exception) {
                throw invalidWebDavResponse()
            }
            val responses = document.getElementsByTagNameNS("DAV:", "response")
            buildList {
                for (index in 0 until responses.length) {
                    val element = responses.item(index) as Element
                    val href = element.text("href") ?: continue
                    val itemUrl = url.resolve(href) ?: throw invalidWebDavResponse()
                    requireSameWebDavOrigin(itemUrl)
                    val decodedPath = itemUrl.pathSegments.filter { it.isNotEmpty() }
                        .joinToString(separator = "/", prefix = "/").trimEnd('/').ifBlank { "/" }
                    requireRemotePath(decodedPath, connection.normalizedRootPath)
                    if (decodedPath == normalizedDavPath(path)) continue
                    val name = itemUrl.pathSegments.lastOrNull { it.isNotEmpty() } ?: continue
                    val isDirectory = element.getElementsByTagNameNS("DAV:", "collection").length > 0
                    add(RemoteFileItem(decodedPath, name, decodedPath, isDirectory,
                        element.text("getcontentlength")?.toLongOrNull() ?: 0L,
                        element.text("getlastmodified")?.let(::parseWebDavInstant)))
                }
            }
        }
    }

    override suspend fun download(item: RemoteFileItem, destination: File, progress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        val request = authorized(Request.Builder().url(remoteUrl(item.path)))
        httpRangeDownload(client, request, destination, item.size, "WebDAV", progress)
    }

    override suspend fun readRange(item: RemoteFileItem, offset: Long, length: Int): ByteArray =
        withContext(Dispatchers.IO) {
            RemoteRangeContract.validate(item, offset, length)
            val request = authorized(Request.Builder().url(remoteUrl(item.path)))
            httpBoundedRangeRead(client, request, item, offset, length, "WebDAV")
        }

    override suspend fun createFolder(parent: String, name: String) = withContext(Dispatchers.IO) {
        requireRemotePath(parent, connection.normalizedRootPath)
        validateRemoteName(name)
        val request = authorized(Request.Builder().url(remoteUrl(joinRemotePath(parent, name))))
            .method("MKCOL", null).build()
        executeDav(request).close()
    }

    override suspend fun rename(item: RemoteFileItem, newName: String) = withContext(Dispatchers.IO) {
        val source = checkedMutablePath(item.path)
        validateRemoteName(newName)
        val parent = source.substringBeforeLast('/', "").ifBlank { "/" }
        moveOrCopy("MOVE", source, joinRemotePath(parent, newName))
    }

    override suspend fun move(items: List<RemoteFileItem>, destination: String) = withContext(Dispatchers.IO) {
        requireRemotePath(destination, connection.normalizedRootPath)
        items.forEach { item ->
            moveOrCopy("MOVE", checkedMutablePath(item.path), joinRemotePath(destination, item.name))
        }
    }

    override suspend fun copy(items: List<RemoteFileItem>, destination: String) = withContext(Dispatchers.IO) {
        requireRemotePath(destination, connection.normalizedRootPath)
        items.forEach { item ->
            moveOrCopy("COPY", checkedMutablePath(item.path), joinRemotePath(destination, item.name))
        }
    }

    override suspend fun delete(items: List<RemoteFileItem>) = withContext(Dispatchers.IO) {
        items.forEach { item ->
            val request = authorized(Request.Builder().url(remoteUrl(checkedMutablePath(item.path))))
                .delete().build()
            executeDav(request).close()
        }
    }

    override suspend fun upload(parent: String, source: File) = withContext(Dispatchers.IO) {
        requireRemotePath(parent, connection.normalizedRootPath)
        if (!source.isFile) throw RemoteServiceException.Server("업로드할 파일을 찾을 수 없습니다.")
        validateRemoteName(source.name)
        repeat(WEBDAV_KEEP_BOTH_ATTEMPTS) {
            val uploadName = keepBothName(source.name, list(parent).map { item -> item.name })
            val request = authorized(Request.Builder().url(remoteUrl(joinRemotePath(parent, uploadName))))
                .header("If-None-Match", "*")
                .put(source.asRequestBody("application/octet-stream".toMediaType()))
                .build()
            val response = executeDav(request, allowConflict = true)
            val conflict = response.code == 409 || response.code == 412
            response.close()
            if (!conflict) return@withContext
        }
        throw RemoteServiceException.Server("같은 이름의 파일이 계속 생성되어 업로드를 완료하지 못했습니다.")
    }

    private fun moveOrCopy(method: String, source: String, destination: String) {
        requireRemotePath(destination, connection.normalizedRootPath)
        val destinationUrl = remoteUrl(destination)
        requireSameWebDavOrigin(destinationUrl)
        val request = authorized(Request.Builder().url(remoteUrl(source)))
            .header("Destination", destinationUrl.toString())
            .header("Overwrite", "F")
            .method(method, null)
            .build()
        executeDav(request).close()
    }

    private fun executeDav(request: Request, allowConflict: Boolean = false): Response {
        val response = try {
            client.newCall(request).execute()
        } catch (error: Exception) {
            throw RemoteServiceException.Connection("WebDAV 서버에 연결할 수 없습니다.", error)
        }
        if (response.isSuccessful || response.code == 207 || (allowConflict && response.code in setOf(409, 412))) {
            return response
        }
        val status = response.code
        response.close()
        if (status == 401 || status == 403) throw RemoteServiceException.Authentication()
        throw RemoteServiceException.Server("WebDAV 서버가 요청을 거부했습니다 (HTTP $status).")
    }

    private fun authorized(builder: Request.Builder): Request.Builder =
        builder.header("Authorization", Credentials.basic(connection.username, password))

    private fun remoteUrl(path: String): okhttp3.HttpUrl {
        requireRemotePath(path, connection.normalizedRootPath)
        val builder = connection.endpoint.toHttpUrl().newBuilder()
        path.split('/').filter { it.isNotEmpty() }.forEach(builder::addPathSegment)
        return builder.build().also(::requireSameWebDavOrigin)
    }

    private fun requireSameWebDavOrigin(url: okhttp3.HttpUrl) {
        val endpoint = connection.endpoint.toHttpUrl()
        if (url.scheme != endpoint.scheme || !url.host.equals(endpoint.host, ignoreCase = true) || url.port != endpoint.port) {
            throw RemoteServiceException.Server("WebDAV 응답의 서버 경계가 올바르지 않습니다.")
        }
    }

    private fun checkedMutablePath(path: String): String {
        requireRemotePath(path, connection.normalizedRootPath)
        val normalized = normalizedDavPath(path)
        if (normalized == normalizedDavPath(connection.normalizedRootPath)) {
            throw RemoteServiceException.Server("연결의 시작 폴더는 변경하거나 삭제할 수 없습니다.")
        }
        return normalized
    }

    private fun normalizedDavPath(path: String): String {
        val components = path.split('/').filter { it.isNotEmpty() && it != "." }
        return if (components.isEmpty()) "/" else "/" + components.joinToString("/")
    }

    private fun Element.text(localName: String): String? =
        getElementsByTagNameNS("DAV:", localName).item(0)?.textContent

    private companion object {
        val WEBDAV_XML_BODY = "".toRequestBody("application/xml; charset=utf-8".toMediaType())
        const val WEBDAV_KEEP_BOTH_ATTEMPTS = 8
    }
}

private fun secureDocumentBuilderFactory(): DocumentBuilderFactory =
    DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isXIncludeAware = false
        setExpandEntityReferences(false)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
        runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
    }

private fun parseWebDavInstant(value: String): Instant? =
    runCatching { Instant.parse(value) }.getOrElse {
        runCatching { java.time.ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull()
    }

private fun invalidWebDavResponse() =
    RemoteServiceException.Server("WebDAV 서버의 응답 경로를 확인하지 못했습니다.")

private fun Response.requireSuccess() {
    if (isSuccessful) return
    if (code == 401 || code == 403) throw RemoteServiceException.Authentication()
    throw RemoteServiceException.Server("서버 오류: HTTP $code")
}

private fun joinRemotePath(parent: String, name: String) =
    if (parent == "/") "/$name" else "${parent.trimEnd('/')}/$name"

private fun jsonArray(vararg values: String): String =
    JSONArray().apply { values.forEach { put(it) } }.toString()

private fun validateRemoteName(name: String) {
    if (name.isBlank() || name == "." || name == ".." || '/' in name || '\u0000' in name ||
        '\r' in name || '\n' in name
    ) {
        throw RemoteServiceException.Server("사용할 수 없는 파일 또는 폴더 이름입니다.")
    }
}

private fun requireRemotePath(path: String, rootPath: String) {
    if (path.isBlank() || '\u0000' in path || '\r' in path || '\n' in path) {
        throw RemoteServiceException.Server("사용할 수 없는 원격 경로입니다.")
    }
    val rootAbsolute = rootPath.startsWith('/')
    if (path.startsWith('/') != rootAbsolute) {
        throw RemoteServiceException.Server("연결의 시작 위치와 맞지 않는 원격 경로입니다.")
    }
    fun components(value: String): List<String> {
        val result = value.split('/').filter { it.isNotBlank() && it != "." }
        if (result.any { it == ".." }) {
            throw RemoteServiceException.Server("시작 위치 밖의 경로에는 접근할 수 없습니다.")
        }
        return result
    }
    val root = components(rootPath)
    val candidate = components(path)
    if (candidate.size < root.size || candidate.take(root.size) != root) {
        throw RemoteServiceException.Server("시작 위치 밖의 경로에는 접근할 수 없습니다.")
    }
}

private fun keepBothName(originalName: String, existingNames: List<String>): String {
    val existing = existingNames.toHashSet()
    if (originalName !in existing) return originalName
    val dot = originalName.lastIndexOf('.').takeIf { it > 0 } ?: -1
    val extension = if (dot >= 0) originalName.substring(dot) else ""
    val stem = (if (dot >= 0) originalName.substring(0, dot) else originalName)
        .replace(Regex(" \\(\\d+\\)$"), "")
    for (index in 1..9_999) {
        val candidate = "$stem ($index)$extension"
        if (candidate !in existing) return candidate
    }
    return "${stem}-${java.util.UUID.randomUUID()}$extension"
}

private fun safeFtpArgument(value: String): String {
    if ('\r' in value || '\n' in value || '\u0000' in value) {
        throw RemoteServiceException.Server("FTP 경로에 허용되지 않은 문자가 있습니다.")
    }
    return value
}

internal data class SynologyCredential(val password: String, val otp: String?) {
    companion object {
        fun parse(value: String): SynologyCredential {
            val envelope = runCatching { JSONObject(value) }.getOrNull()
            if (envelope?.optString("_nasfinder") != "synology-v1") {
                return SynologyCredential(value, null)
            }
            val password = envelope.optString("password")
            val otp = envelope.optString("otp").takeIf(String::isNotBlank)
            if (otp != null && (otp.length !in 4..12 || otp.any { !it.isDigit() })) {
                throw RemoteServiceException.Authentication("Synology OTP 형식이 올바르지 않습니다.")
            }
            return SynologyCredential(password, otp)
        }
    }
}

internal object HttpRangeContract {
    fun partialFile(destination: File): File =
        File(destination.absoluteFile.parentFile, ".${destination.name}.nasfinder.part")

    fun contentRangeStart(value: String?): Long? =
        value?.let { Regex("^bytes (\\d+)-(\\d+)/(\\d+|\\*)$").matchEntire(it.trim()) }
            ?.groupValues?.get(1)?.toLongOrNull()

    fun contentRangeTotal(value: String?): Long? =
        value?.let { Regex("^bytes (\\d+)-(\\d+)/(\\d+|\\*)$").matchEntire(it.trim()) }
            ?.groupValues?.get(3)?.takeUnless { it == "*" }?.toLongOrNull()

    fun parsed(value: String?): ParsedContentRange? {
        val match = value?.let { Regex("^bytes (\\d+)-(\\d+)/(\\d+|\\*)$").matchEntire(it.trim()) }
            ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        if (end < start) return null
        val total = match.groupValues[3].takeUnless { it == "*" }?.toLongOrNull()
        if (total != null && end >= total) return null
        return ParsedContentRange(start, end, total)
    }
}

internal data class ParsedContentRange(val start: Long, val end: Long, val total: Long?)

internal object RemoteRangeContract {
    const val MAX_BYTES = 8 * 1024 * 1024

    fun validate(item: RemoteFileItem, offset: Long, length: Int): Long {
        if (item.isDirectory) throw RemoteServiceException.Unsupported("폴더는 범위 읽기를 지원하지 않습니다.")
        if (offset < 0 || length <= 0 || length > MAX_BYTES) {
            throw RemoteServiceException.Server("요청한 파일 범위가 올바르지 않습니다.")
        }
        val end = offset + length - 1L
        if (end < offset) throw RemoteServiceException.Server("요청한 파일 범위가 너무 큽니다.")
        return end
    }
}

internal suspend fun httpBoundedRangeRead(
    client: OkHttpClient,
    requestBuilder: Request.Builder,
    item: RemoteFileItem,
    offset: Long,
    length: Int,
    serviceName: String,
): ByteArray {
    val requestedEnd = RemoteRangeContract.validate(item, offset, length)
    if (item.size > 0 && offset >= item.size) return ByteArray(0)
    val request = requestBuilder.header("Range", "bytes=$offset-$requestedEnd").build()
    kotlin.coroutines.coroutineContext.ensureActive()
    val call = client.newCall(request)
    val cancellation = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion {
        if (it is kotlinx.coroutines.CancellationException) call.cancel()
    }
    try {
        val response = try {
            call.execute()
        } catch (error: Exception) {
            kotlin.coroutines.coroutineContext.ensureActive()
            throw RemoteServiceException.Connection("$serviceName 서버에 연결할 수 없습니다.", error)
        }
        response.use {
        if (response.code == 401 || response.code == 403) throw RemoteServiceException.Authentication()
        if (response.code == 416 && item.size > 0 && offset >= item.size) return ByteArray(0)
        if (response.code !in setOf(200, 206)) {
            throw RemoteServiceException.Server("$serviceName 서버가 범위 읽기를 거부했습니다 (HTTP ${response.code}).")
        }

        val parsed = HttpRangeContract.parsed(response.header("Content-Range"))
        if (response.code == 206) {
            if (parsed == null || parsed.start != offset || parsed.end > requestedEnd) {
                throw RemoteServiceException.Server("$serviceName 범위 응답이 올바르지 않습니다.")
            }
            if (item.size > 0 && parsed.total != item.size) {
                throw RemoteServiceException.Server("$serviceName 파일 크기 정보가 일치하지 않습니다.")
            }
        } else if (offset > 0) {
            throw RemoteServiceException.Unsupported("$serviceName 서버가 HTTP Range 요청을 지원하지 않습니다.")
        }

        val advertised = response.body.contentLength()
        if (response.code == 206 && advertised > length) {
            throw RemoteServiceException.Server("$serviceName 범위 응답이 요청 크기를 초과했습니다.")
        }
        val output = ByteArrayOutputStream(minOf(length, 256 * 1024))
        response.body.byteStream().use { input ->
            val buffer = ByteArray(minOf(length + 1, 64 * 1024))
            var remaining = length
            while (remaining > 0) {
                kotlin.coroutines.coroutineContext.ensureActive()
                val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (count < 0) break
                output.write(buffer, 0, count)
                remaining -= count
            }
            if (response.code == 206 && remaining == 0) {
                kotlin.coroutines.coroutineContext.ensureActive()
                if (input.read() >= 0) {
                    throw RemoteServiceException.Server("$serviceName 범위 응답이 요청 크기를 초과했습니다.")
                }
            }
        }
        val bytes = output.toByteArray()
        if (response.code == 206) {
            val range = parsed ?: throw RemoteServiceException.Server("$serviceName 범위 응답이 올바르지 않습니다.")
            val expected = range.end - range.start + 1
            if (bytes.size.toLong() != expected || (advertised >= 0 && advertised != expected)) {
                throw RemoteServiceException.Server("$serviceName 범위 응답 크기가 일치하지 않습니다.")
            }
        }
            return bytes
        }
    } finally {
        cancellation?.dispose()
    }
}

internal object FtpTransferPolicy {
    fun parent(path: String): String = path.substringBeforeLast('/', "").ifBlank { "/" }

    fun isSameOrDescendant(candidate: String, ancestor: String): Boolean =
        candidate == ancestor || candidate.startsWith("${ancestor.trimEnd('/')}/")
}

private suspend fun httpRangeDownload(
    client: OkHttpClient,
    requestBuilder: Request.Builder,
    destination: File,
    expectedSize: Long,
    serviceName: String,
    progress: (Long, Long) -> Unit,
) {
    val parent = destination.absoluteFile.parentFile
        ?: throw RemoteServiceException.Server("다운로드 저장 위치가 올바르지 않습니다.")
    if (!parent.exists() && !parent.mkdirs()) {
        throw RemoteServiceException.Server("다운로드 저장 위치를 만들 수 없습니다.")
    }
    val partial = HttpRangeContract.partialFile(destination)
    if (expectedSize > 0 && partial.length() > expectedSize) partial.delete()
    var offset = partial.length()
    val request = requestBuilder.header("Range", "bytes=$offset-").get().build()
    kotlin.coroutines.coroutineContext.ensureActive()
    val call = client.newCall(request)
    val cancellation = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion {
        if (it is kotlinx.coroutines.CancellationException) call.cancel()
    }
    try {
        val response = try {
            call.execute()
        } catch (error: Exception) {
            kotlin.coroutines.coroutineContext.ensureActive()
            throw RemoteServiceException.Connection("$serviceName 서버에 연결할 수 없습니다.", error)
        }
        response.use {
        if (response.code == 401 || response.code == 403) throw RemoteServiceException.Authentication()
        if (response.code == 416 && expectedSize > 0 && offset == expectedSize) {
            moveDownloadedFile(partial, destination)
            progress(expectedSize, expectedSize)
            return
        }
        if (response.code !in setOf(200, 206)) {
            if (response.code == 416) partial.delete()
            throw RemoteServiceException.Server("$serviceName 서버가 다운로드를 거부했습니다 (HTTP ${response.code}).")
        }
        val rangeHeader = response.header("Content-Range")
        if (response.code == 206 && HttpRangeContract.contentRangeStart(rangeHeader) != offset) {
            partial.delete()
            throw RemoteServiceException.Server("$serviceName 다운로드 범위 응답이 올바르지 않습니다.")
        }
        val append = response.code == 206 && offset > 0
        if (!append) offset = 0
        val total = expectedSize.takeIf { it > 0 }
            ?: HttpRangeContract.contentRangeTotal(rangeHeader)
            ?: response.body.contentLength().takeIf { it >= 0 }?.plus(offset)
            ?: -1L
        var completed = offset
        FileOutputStream(partial, append).buffered().use { output ->
            response.body.byteStream().use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    kotlin.coroutines.coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (total >= 0 && (completed > total || count.toLong() > total - completed)) {
                        throw RemoteServiceException.Server("$serviceName 다운로드 크기가 원격 파일 크기를 초과했습니다.")
                    }
                    output.write(buffer, 0, count)
                    completed += count
                    progress(completed, total)
                }
            }
        }
            if (total >= 0 && completed != total) {
                partial.delete()
                throw RemoteServiceException.Server("$serviceName 다운로드 크기가 일치하지 않습니다.")
            }
        }
    } finally {
        cancellation?.dispose()
    }
    moveDownloadedFile(partial, destination)
}

private fun moveDownloadedFile(source: File, destination: File) {
    try {
        Files.move(
            source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

private object DiscardingCallback : okhttp3.Callback {
    override fun onFailure(call: okhttp3.Call, e: java.io.IOException) = Unit
    override fun onResponse(call: okhttp3.Call, response: Response) = response.close()
}
