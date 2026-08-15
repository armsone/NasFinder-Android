package com.armsone.nasfinder.network

import com.armsone.nasfinder.model.ConnectionKind
import com.armsone.nasfinder.model.RemoteConnection
import com.armsone.nasfinder.model.RemoteFileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * REST implementation shared by Dropbox, OneDrive and Google Drive.
 *
 * [accessToken] is the already persisted OAuth access token. Token refresh and
 * interactive sign-in deliberately live outside this transport service.
 */
class CloudDriveFileService(
    private val connection: RemoteConnection,
    private val accessToken: String,
    private val client: OkHttpClient = cloudHttpClient(),
) : RemoteFileService {
    override val supportsRangeStreaming: Boolean = true
    private val monitorClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val googleFolderIds = mutableMapOf("/" to "root")
    private val oneDriveFolderReferences = mutableMapOf<String, OneDriveFolderReference>()
    @Volatile private var cachedOneDriveDriveId: String? = null

    init {
        require(connection.kind in SUPPORTED_KINDS) { "지원하지 않는 클라우드 연결입니다." }
        if (accessToken.isBlank() || '\r' in accessToken || '\n' in accessToken) {
            throw RemoteServiceException.Authentication("저장된 클라우드 로그인이 없습니다.")
        }
    }

    override suspend fun testConnection() {
        list(connection.normalizedRootPath)
    }

    override suspend fun list(path: String): List<RemoteFileItem> = withContext(Dispatchers.IO) {
        val directory = checkedPath(path)
        when (connection.kind) {
            ConnectionKind.DROPBOX -> listDropbox(directory)
            ConnectionKind.ONEDRIVE -> listOneDrive(directory)
            ConnectionKind.GOOGLE_DRIVE -> listGoogle(directory)
            else -> unsupportedProvider()
        }
    }

    override suspend fun download(
        item: RemoteFileItem,
        destination: File,
        progress: (Long, Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        checkedPath(item.path)
        if (item.isDirectory) throw RemoteServiceException.Unsupported("폴더는 파일로 다운로드할 수 없습니다.")
        if (connection.kind == ConnectionKind.GOOGLE_DRIVE && isGoogleNativeMime(item.mimeType)) {
            throw RemoteServiceException.Unsupported(
                "Google 문서·시트·슬라이드는 원본 파일 다운로드를 지원하지 않습니다. Google Drive에서 내보내기를 사용하세요.",
            )
        }
        val request = when (connection.kind) {
            ConnectionKind.DROPBOX -> authorized(
                Request.Builder().url(DROPBOX_CONTENT_DOWNLOAD).post(EMPTY_BODY)
                    .header("Dropbox-API-Arg", JSONObject().put("path", item.path).toString()),
            )
            ConnectionKind.ONEDRIVE -> authorized(
                Request.Builder().url(oneDriveItemUrl(requiredId(item), "content")).get(),
            )
            ConnectionKind.GOOGLE_DRIVE -> authorized(
                Request.Builder().url(
                    GOOGLE_FILES.newBuilder().addPathSegment(requiredId(item))
                        .addQueryParameter("alt", "media").build(),
                ).get(),
            )
            else -> unsupportedProvider()
        }.build()
        downloadTo(request, destination, item.size, progress)
    }

    override suspend fun readRange(item: RemoteFileItem, offset: Long, length: Int): ByteArray =
        withContext(Dispatchers.IO) {
            checkedPath(item.path)
            RemoteRangeContract.validate(item, offset, length)
            if (connection.kind == ConnectionKind.GOOGLE_DRIVE && isGoogleNativeMime(item.mimeType)) {
                throw RemoteServiceException.Unsupported(
                    "Google 문서·시트·슬라이드는 범위 읽기를 지원하지 않습니다.",
                )
            }
            val request = when (connection.kind) {
                ConnectionKind.DROPBOX -> authorized(
                    Request.Builder().url(DROPBOX_CONTENT_DOWNLOAD).post(EMPTY_BODY)
                        .header("Dropbox-API-Arg", JSONObject().put("path", item.path).toString()),
                )
                ConnectionKind.ONEDRIVE -> authorized(
                    Request.Builder().url(oneDriveItemUrl(requiredId(item), "content")).get(),
                )
                ConnectionKind.GOOGLE_DRIVE -> authorized(
                    Request.Builder().url(
                        GOOGLE_FILES.newBuilder().addPathSegment(requiredId(item))
                            .addQueryParameter("alt", "media").build(),
                    ).get(),
                )
                else -> unsupportedProvider()
            }
            httpBoundedRangeRead(client, request, item, offset, length, connection.kind.title)
        }

    override suspend fun createFolder(parent: String, name: String) = withContext(Dispatchers.IO) {
        val safeParent = checkedPath(parent)
        validateName(name)
        val path = childPath(safeParent, name)
        when (connection.kind) {
            ConnectionKind.DROPBOX -> executeJson(
                authorized(jsonPost(DROPBOX_CREATE_FOLDER, JSONObject().put("path", path).put("autorename", false))).build(),
            )
            ConnectionKind.ONEDRIVE -> executeJson(
                authorized(
                    jsonPost(
                        oneDriveChildrenUrl(safeParent),
                        JSONObject().put("name", name).put("folder", JSONObject())
                            .put("@microsoft.graph.conflictBehavior", "fail"),
                    ),
                ).build(),
            ).also { cacheOneDriveFolder(path, it) }
            ConnectionKind.GOOGLE_DRIVE -> {
                val parentId = googleFolderId(safeParent)
                val result = executeJson(
                    authorized(
                        jsonPost(
                            GOOGLE_FILES,
                            JSONObject().put("name", name).put("mimeType", GOOGLE_FOLDER_MIME)
                                .put("parents", JSONArray().put(parentId)),
                        ),
                    ).build(),
                )
                result.optString("id").takeIf { it.isNotBlank() }?.let { cacheGoogleFolder(path, it) }
            }
            else -> unsupportedProvider()
        }
        Unit
    }

    override suspend fun rename(item: RemoteFileItem, newName: String) = withContext(Dispatchers.IO) {
        val oldPath = checkedPath(item.path)
        rejectRootMutation(oldPath)
        validateName(newName)
        val newPath = childPath(parentPath(oldPath), newName)
        when (connection.kind) {
            ConnectionKind.DROPBOX -> executeJson(
                authorized(
                    jsonPost(
                        DROPBOX_MOVE,
                        JSONObject().put("from_path", oldPath).put("to_path", newPath)
                            .put("autorename", false).put("allow_ownership_transfer", false),
                    ),
                ).build(),
            )
            ConnectionKind.ONEDRIVE -> executeJson(
                authorized(
                    Request.Builder().url(oneDriveItemUrl(requiredId(item)))
                        .patch(jsonBody(JSONObject().put("name", newName))),
                ).build(),
            ).also {
                if (item.isDirectory) replaceOneDriveFolderPath(oldPath, newPath, requiredId(item))
            }
            ConnectionKind.GOOGLE_DRIVE -> {
                executeJson(
                    authorized(
                        Request.Builder().url(googleItemUrl(requiredId(item)))
                            .patch(jsonBody(JSONObject().put("name", newName))),
                    ).build(),
                )
                if (item.isDirectory) replaceGoogleFolderPath(oldPath, newPath, item.id)
            }
            else -> unsupportedProvider()
        }
        Unit
    }

    override suspend fun delete(items: List<RemoteFileItem>) = withContext(Dispatchers.IO) {
        for (item in items) {
            val path = checkedPath(item.path)
            rejectRootMutation(path)
            when (connection.kind) {
                ConnectionKind.DROPBOX -> executeJson(
                    authorized(jsonPost(DROPBOX_DELETE, JSONObject().put("path", path))).build(),
                )
                ConnectionKind.ONEDRIVE -> executeEmpty(
                    authorized(Request.Builder().url(oneDriveItemUrl(requiredId(item))).delete()).build(),
                ).also { if (item.isDirectory) removeOneDriveFolderPath(path) }
                ConnectionKind.GOOGLE_DRIVE -> {
                    executeEmpty(
                        authorized(Request.Builder().url(googleItemUrl(requiredId(item))).delete()).build(),
                    )
                    if (item.isDirectory) removeGoogleFolderPath(path)
                }
                else -> unsupportedProvider()
            }
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
    ) = withContext(Dispatchers.IO) {
        val safeDestination = checkedPath(destination)
        val checkedItems = items.map { item ->
            val path = checkedPath(item.path)
            rejectRootMutation(path)
            validateName(item.name)
            requiredId(item)
            if (item.isDirectory && (safeDestination == path || safeDestination.startsWith("$path/"))) {
                throw RemoteServiceException.Server("폴더를 자기 안으로 복사하거나 이동할 수 없습니다.")
            }
            item to path
        }
        if (checkedItems.isEmpty()) return@withContext

        when (connection.kind) {
            ConnectionKind.DROPBOX -> checkedItems.forEach { (item, path) ->
                if (removeSource && parentPath(path) == safeDestination) return@forEach
                val endpoint = if (removeSource) DROPBOX_MOVE else DROPBOX_COPY
                val arguments = JSONObject().put("from_path", path)
                    .put("to_path", childPath(safeDestination, item.name))
                    .put("autorename", true)
                if (removeSource) arguments.put("allow_ownership_transfer", false)
                executeJson(
                    authorized(
                        jsonPost(endpoint, arguments),
                    ).build(),
                )
            }
            ConnectionKind.ONEDRIVE -> transferOneDrive(checkedItems, safeDestination, removeSource)
            ConnectionKind.GOOGLE_DRIVE -> transferGoogle(checkedItems, safeDestination, removeSource)
            else -> unsupportedProvider()
        }
    }

    override suspend fun upload(parent: String, source: File) = withContext(Dispatchers.IO) {
        if (!source.isFile) throw RemoteServiceException.Server("업로드할 파일을 찾을 수 없습니다.")
        val safeParent = checkedPath(parent)
        validateName(source.name)
        when (connection.kind) {
            ConnectionKind.DROPBOX -> uploadDropbox(safeParent, source)
            ConnectionKind.ONEDRIVE -> uploadOneDrive(safeParent, source)
            ConnectionKind.GOOGLE_DRIVE -> uploadGoogle(safeParent, source)
            else -> unsupportedProvider()
        }
    }

    private fun listDropbox(directory: String): List<RemoteFileItem> {
        var request = authorized(
            jsonPost(
                DROPBOX_LIST,
                JSONObject().put("path", if (directory == "/") "" else directory)
                    .put("recursive", false).put("include_deleted", false).put("limit", 2_000),
            ),
        ).build()
        val result = mutableListOf<RemoteFileItem>()
        val seenCursors = mutableSetOf<String>()
        repeat(MAX_PAGES) {
            val page = executeJson(request)
            val entries = page.optJSONArray("entries") ?: JSONArray()
            for (index in 0 until entries.length()) {
                val value = entries.optJSONObject(index) ?: continue
                val name = value.optString("name").takeIf { it.isNotBlank() } ?: continue
                val tag = value.optString(".tag")
                if (tag !in setOf("file", "folder")) continue
                val path = value.optString("path_display").ifBlank { childPath(directory, name) }
                result += cloudItem(
                    id = value.optString("id").ifBlank { path }, name = name, path = path,
                    isDirectory = tag == "folder", size = value.optLong("size", 0),
                    modified = value.optString("server_modified"), mimeType = null,
                )
            }
            if (!page.optBoolean("has_more")) return result
            val cursor = page.optString("cursor")
            if (cursor.isBlank() || !seenCursors.add(cursor)) throw invalidCloudResponse()
            request = authorized(jsonPost(DROPBOX_LIST_CONTINUE, JSONObject().put("cursor", cursor))).build()
        }
        throw invalidCloudResponse()
    }

    private fun listOneDrive(directory: String): List<RemoteFileItem> {
        var next: HttpUrl? = oneDriveChildrenUrl(directory)
        val result = mutableListOf<RemoteFileItem>()
        val seenPages = mutableSetOf<String>()
        repeat(MAX_PAGES) {
            val pageUrl = next ?: return result
            if (!isMicrosoftGraphUrl(pageUrl) || !seenPages.add(pageUrl.toString())) throw invalidCloudResponse()
            val page = executeJson(authorized(Request.Builder().url(pageUrl).get()).build())
            val entries = page.optJSONArray("value") ?: JSONArray()
            for (index in 0 until entries.length()) {
                val value = entries.optJSONObject(index) ?: continue
                val id = value.optString("id").takeIf { it.isNotBlank() } ?: continue
                val name = value.optString("name").takeIf { it.isNotBlank() } ?: continue
                val file = value.optJSONObject("file")
                val path = childPath(directory, name)
                if (value.has("folder")) cacheOneDriveFolder(path, value)
                result += cloudItem(
                    id = id, name = name, path = path,
                    isDirectory = value.has("folder"), size = value.optLong("size", 0),
                    modified = value.optString("lastModifiedDateTime"),
                    mimeType = file?.optString("mimeType")?.takeIf { it.isNotBlank() },
                )
            }
            next = page.optString("@odata.nextLink").takeIf { it.isNotBlank() }?.toHttpUrlOrNull()
        }
        throw invalidCloudResponse()
    }

    private suspend fun transferOneDrive(
        items: List<Pair<RemoteFileItem, String>>,
        destination: String,
        removeSource: Boolean,
    ) {
        val target = oneDriveFolderReference(destination)
        val occupied = listOneDrive(destination).mapTo(mutableSetOf()) { it.name }
        for ((item, sourcePath) in items) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (removeSource && parentPath(sourcePath) == destination) continue
            val finalName = keepBothName(item.name, occupied)
            occupied += finalName
            val id = requiredId(item)
            if (removeSource) {
                val body = JSONObject().put("parentReference", JSONObject().put("id", target.id))
                    .put("name", finalName)
                executeJson(
                    authorized(Request.Builder().url(oneDriveItemUrl(id)).patch(jsonBody(body))).build(),
                )
                if (item.isDirectory) {
                    replaceOneDriveFolderPath(sourcePath, childPath(destination, finalName), id)
                }
            } else {
                val body = JSONObject().put(
                    "parentReference",
                    JSONObject().put("id", target.id).put("driveId", target.driveId),
                ).put("name", finalName)
                val monitor = executeResponse(
                    authorized(Request.Builder().url(oneDriveItemUrl(id, "copy")).post(jsonBody(body))).build(),
                ).use { response ->
                    if (response.code != 202) throw invalidCloudResponse()
                    response.header("Location")?.toHttpUrlOrNull() ?: throw invalidCloudResponse()
                }
                pollOneDriveCopy(monitor)
            }
        }
    }

    private suspend fun pollOneDriveCopy(initialUrl: HttpUrl) {
        var monitor = initialUrl
        repeat(MAX_COPY_POLLS) {
            if (!isAllowedMicrosoftMonitorUrl(monitor)) throw invalidCloudResponse()
            val result = executeMonitorResponse(Request.Builder().url(monitor).get().build())
            val status = result.code
            val next = result.header("Location")?.toHttpUrlOrNull()
            val retryAfter = result.header("Retry-After")?.toLongOrNull()?.coerceIn(0, 5) ?: 0
            result.close()
            if (status in setOf(200, 201, 204)) return
            if (status != 202) throw invalidCloudResponse()
            next?.let { monitor = it }
            if (retryAfter > 0) delay(retryAfter * 1_000)
        }
        throw RemoteServiceException.Server("OneDrive 복사 작업이 제시간에 완료되지 않았습니다.")
    }

    private suspend fun transferGoogle(
        items: List<Pair<RemoteFileItem, String>>,
        destination: String,
        removeSource: Boolean,
    ) {
        if (!removeSource && items.any { it.first.isDirectory }) {
            throw RemoteServiceException.Unsupported("Google Drive API는 폴더 복사를 지원하지 않습니다.")
        }
        val targetId = googleFolderId(destination)
        val occupied = listGoogle(destination).mapTo(mutableSetOf()) { it.name }
        for ((item, sourcePath) in items) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (removeSource && parentPath(sourcePath) == destination) continue
            val finalName = keepBothName(item.name, occupied)
            occupied += finalName
            val id = requiredId(item)
            if (removeSource) {
                val metadata = executeJson(
                    authorized(
                        Request.Builder().url(
                            googleItemUrl(id).newBuilder()
                                .addQueryParameter("fields", "parents")
                                .addQueryParameter("supportsAllDrives", "true")
                                .build(),
                        ).get(),
                    ).build(),
                )
                val parents = metadata.optJSONArray("parents") ?: throw invalidCloudResponse()
                val previous = buildList {
                    for (index in 0 until parents.length()) {
                        parents.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
                if (previous.isEmpty()) throw invalidCloudResponse()
                val url = googleItemUrl(id).newBuilder()
                    .addQueryParameter("addParents", targetId)
                    .addQueryParameter("removeParents", previous.joinToString(","))
                    .addQueryParameter("supportsAllDrives", "true")
                    .addQueryParameter("fields", "id,parents,name")
                    .build()
                executeJson(
                    authorized(Request.Builder().url(url).patch(jsonBody(JSONObject().put("name", finalName)))).build(),
                )
                if (item.isDirectory) replaceGoogleFolderPath(sourcePath, childPath(destination, finalName), id)
            } else {
                val body = JSONObject().put("name", finalName).put("parents", JSONArray().put(targetId))
                val url = googleItemUrl(id).newBuilder().addPathSegment("copy")
                    .addQueryParameter("supportsAllDrives", "true").build()
                executeJson(authorized(Request.Builder().url(url).post(jsonBody(body))).build())
            }
        }
    }

    private fun listGoogle(directory: String): List<RemoteFileItem> {
        val parentId = googleFolderId(directory)
        var pageToken: String? = null
        val seenTokens = mutableSetOf<String>()
        val result = mutableListOf<RemoteFileItem>()
        repeat(MAX_PAGES) {
            val builder = GOOGLE_FILES.newBuilder()
                .addQueryParameter("q", "'${escapeGoogleQuery(parentId)}' in parents and trashed = false")
                .addQueryParameter("fields", "nextPageToken,files(id,name,mimeType,size,modifiedTime,md5Checksum,parents)")
                .addQueryParameter("pageSize", "1000")
            pageToken?.let { builder.addQueryParameter("pageToken", it) }
            val page = executeJson(authorized(Request.Builder().url(builder.build()).get()).build())
            val entries = page.optJSONArray("files") ?: JSONArray()
            for (index in 0 until entries.length()) {
                val value = entries.optJSONObject(index) ?: continue
                val id = value.optString("id").takeIf { it.isNotBlank() } ?: continue
                val name = value.optString("name").takeIf { it.isNotBlank() } ?: continue
                val mime = value.optString("mimeType").takeIf { it.isNotBlank() }
                val folder = mime == GOOGLE_FOLDER_MIME
                val path = childPath(directory, name)
                if (folder) cacheGoogleFolder(path, id)
                result += cloudItem(
                    id = id, name = name, path = path, isDirectory = folder,
                    size = value.optString("size").toLongOrNull() ?: 0,
                    modified = value.optString("modifiedTime"), mimeType = mime,
                )
            }
            val token = page.optString("nextPageToken").takeIf { it.isNotBlank() } ?: return result
            if (!seenTokens.add(token)) throw invalidCloudResponse()
            pageToken = token
        }
        throw invalidCloudResponse()
    }

    private fun uploadDropbox(parent: String, source: File) {
        if (source.length() > DROPBOX_SINGLE_UPLOAD_LIMIT) {
            uploadDropboxSession(parent, source)
            return
        }
        val argument = JSONObject().put("path", childPath(parent, source.name))
            .put("mode", "add").put("autorename", true).put("mute", false).put("strict_conflict", false)
        val request = authorized(
            Request.Builder().url(DROPBOX_CONTENT_UPLOAD)
                .header("Dropbox-API-Arg", argument.toString())
                .post(source.asRequestBody(OCTET_STREAM)),
        ).build()
        executeJson(request)
    }

    private fun uploadDropboxSession(parent: String, source: File) {
        val session = executeJson(
            authorized(
                Request.Builder().url(DROPBOX_UPLOAD_SESSION_START)
                    .header("Dropbox-API-Arg", JSONObject().put("close", false).toString())
                    .post(EMPTY_BODY),
            ).build(),
        ).optString("session_id").takeIf { it.isNotBlank() } ?: throw invalidCloudResponse()
        RandomAccessFile(source, "r").use { file ->
            var offset = 0L
            while (source.length() - offset > DROPBOX_UPLOAD_CHUNK_BYTES) {
                val length = DROPBOX_UPLOAD_CHUNK_BYTES
                val cursor = JSONObject().put("session_id", session).put("offset", offset)
                executeEmpty(
                    authorized(
                        Request.Builder().url(DROPBOX_UPLOAD_SESSION_APPEND)
                            .header("Dropbox-API-Arg", JSONObject().put("cursor", cursor).put("close", false).toString())
                            .post(FileSegmentRequestBody(file, offset, length)),
                    ).build(),
                )
                offset += length
            }
            val finalLength = (source.length() - offset).toInt()
            val cursor = JSONObject().put("session_id", session).put("offset", offset)
            val commit = JSONObject().put("path", childPath(parent, source.name))
                .put("mode", "add").put("autorename", true).put("mute", false).put("strict_conflict", false)
            executeJson(
                authorized(
                    Request.Builder().url(DROPBOX_UPLOAD_SESSION_FINISH)
                        .header("Dropbox-API-Arg", JSONObject().put("cursor", cursor).put("commit", commit).toString())
                        .post(FileSegmentRequestBody(file, offset, finalLength)),
                ).build(),
            )
        }
    }

    private fun uploadOneDrive(parent: String, source: File) {
        val chosenName = keepBothName(source.name, listOneDrive(parent).map { it.name })
        val path = childPath(parent, chosenName)
        if (source.length() == 0L) {
            executeJson(
                authorized(
                    Request.Builder().url(oneDriveContentUrl(path)).put(source.asRequestBody(OCTET_STREAM)),
                ).build(),
            )
            return
        }
        val session = executeJson(
            authorized(
                jsonPost(
                    oneDriveCreateUploadSessionUrl(path),
                    JSONObject().put(
                        "item",
                        JSONObject().put("@microsoft.graph.conflictBehavior", "rename").put("name", chosenName),
                    ),
                ),
            ).build(),
        )
        val uploadUrl = session.optString("uploadUrl").takeIf { it.isNotBlank() }?.toHttpUrlOrNull()
            ?: throw invalidCloudResponse()
        if (!uploadUrl.isHttps) throw invalidCloudResponse()
        RandomAccessFile(source, "r").use { file ->
            var offset = 0L
            while (offset < source.length()) {
                val length = minOf(ONEDRIVE_CHUNK_BYTES.toLong(), source.length() - offset).toInt()
                val body = FileSegmentRequestBody(file, offset, length)
                val request = Request.Builder().url(uploadUrl).put(body)
                    .header("Content-Range", "bytes $offset-${offset + length - 1}/${source.length()}").build()
                executeUploadChunk(request, finalChunk = offset + length == source.length())
                offset += length
            }
        }
    }

    private fun uploadGoogle(parent: String, source: File) {
        val chosenName = keepBothName(source.name, listGoogle(parent).map { it.name })
        val metadata = JSONObject().put("name", chosenName)
            .put("parents", JSONArray().put(googleFolderId(parent)))
        val startUrl = GOOGLE_UPLOAD.newBuilder().addQueryParameter("uploadType", "resumable").build()
        val sessionUrl = executeResponse(
            authorized(
                Request.Builder().url(startUrl).header("X-Upload-Content-Type", "application/octet-stream")
                    .post(jsonBody(metadata)),
            ).build(),
        ).use { response ->
            response.header("Location")?.toHttpUrlOrNull() ?: throw invalidCloudResponse()
        }
        if (!sessionUrl.isHttps || !sessionUrl.host.endsWith(".googleapis.com")) throw invalidCloudResponse()
        executeJson(
            authorized(Request.Builder().url(sessionUrl).put(source.asRequestBody(OCTET_STREAM))).build(),
        )
    }

    private suspend fun downloadTo(
        request: Request,
        destination: File,
        expectedSize: Long,
        progress: (Long, Long) -> Unit,
    ) {
        val parent = destination.absoluteFile.parentFile
            ?: throw RemoteServiceException.Server("다운로드 저장 위치가 올바르지 않습니다.")
        if (!parent.exists() && !parent.mkdirs()) {
            throw RemoteServiceException.Server("다운로드 저장 위치를 만들 수 없습니다.")
        }
        val temporary = File(parent, ".nasfinder-cloud-${UUID.randomUUID()}.part")
        try {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val call = client.newCall(request)
            val cancellation = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.invokeOnCompletion {
                if (it is kotlinx.coroutines.CancellationException) call.cancel()
            }
            try {
                val response = try {
                    call.execute().also(::validateResponse)
                } catch (error: RemoteServiceException) {
                    throw error
                } catch (error: IOException) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    throw RemoteServiceException.Connection("클라우드 서비스에 연결할 수 없습니다.", error)
                }
                response.use {
                val body = response.body
                val total = body.contentLength()
                if (expectedSize > 0 && total >= 0 && total != expectedSize) {
                    throw RemoteServiceException.Connection("클라우드 다운로드 크기가 원격 파일 크기와 일치하지 않습니다.")
                }
                var downloaded = 0L
                temporary.outputStream().buffered().use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(DOWNLOAD_CHUNK_BYTES)
                        while (true) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (expectedSize > 0 && count.toLong() > expectedSize - downloaded) {
                                throw RemoteServiceException.Connection("클라우드 다운로드 크기가 원격 파일 크기를 초과했습니다.")
                            }
                            output.write(buffer, 0, count)
                            downloaded += count
                            progress(downloaded, total)
                        }
                    }
                }
                if (total >= 0 && downloaded != total) {
                    throw RemoteServiceException.Connection("클라우드 다운로드 크기가 일치하지 않습니다.")
                }
                }
            } finally {
                cancellation?.dispose()
            }
            try {
                Files.move(
                    temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    private fun googleFolderId(path: String): String {
        synchronized(googleFolderIds) { googleFolderIds[path]?.let { return it } }
        var currentPath = "/"
        var parentId = "root"
        for (component in pathComponents(path)) {
            currentPath = childPath(currentPath, component)
            synchronized(googleFolderIds) { googleFolderIds[currentPath] }?.let {
                parentId = it
                return@let
            } ?: run {
                val query = "'${escapeGoogleQuery(parentId)}' in parents and " +
                    "name = '${escapeGoogleQuery(component)}' and mimeType = '$GOOGLE_FOLDER_MIME' and trashed = false"
                val url = GOOGLE_FILES.newBuilder().addQueryParameter("q", query)
                    .addQueryParameter("fields", "files(id)").addQueryParameter("pageSize", "2").build()
                val values = executeJson(authorized(Request.Builder().url(url).get()).build())
                    .optJSONArray("files") ?: JSONArray()
                if (values.length() != 1) throw RemoteServiceException.Server("클라우드 폴더 경로를 안전하게 확인하지 못했습니다.")
                parentId = values.getJSONObject(0).getString("id")
                cacheGoogleFolder(currentPath, parentId)
            }
        }
        return parentId
    }

    private fun cacheGoogleFolder(path: String, id: String) {
        synchronized(googleFolderIds) { googleFolderIds[path] = id }
    }

    private fun removeGoogleFolderPath(path: String) {
        synchronized(googleFolderIds) {
            googleFolderIds.keys.filter { it == path || it.startsWith("$path/") }.forEach(googleFolderIds::remove)
        }
    }

    private fun replaceGoogleFolderPath(oldPath: String, newPath: String, id: String) {
        synchronized(googleFolderIds) {
            val descendants = googleFolderIds.filterKeys { it == oldPath || it.startsWith("$oldPath/") }
            descendants.keys.forEach(googleFolderIds::remove)
            descendants.forEach { (path, value) ->
                googleFolderIds[newPath + path.removePrefix(oldPath)] = value
            }
            googleFolderIds[newPath] = id
        }
    }

    private fun oneDriveFolderReference(path: String): OneDriveFolderReference {
        synchronized(oneDriveFolderReferences) { oneDriveFolderReferences[path]?.let { return it } }
        val value = executeJson(
            authorized(
                Request.Builder().url(
                    oneDrivePathItemUrl(path).newBuilder()
                        .addQueryParameter("\$select", "id,parentReference").build(),
                ).get(),
            ).build(),
        )
        val id = value.optString("id").takeIf { it.isNotBlank() } ?: throw invalidCloudResponse()
        val driveId = value.optJSONObject("parentReference")?.optString("driveId")
            ?.takeIf { it.isNotBlank() } ?: oneDriveDriveId()
        return OneDriveFolderReference(id, driveId).also {
            synchronized(oneDriveFolderReferences) { oneDriveFolderReferences[path] = it }
        }
    }

    private fun oneDriveDriveId(): String {
        cachedOneDriveDriveId?.let { return it }
        val id = executeJson(
            authorized(
                Request.Builder().url(
                    "$GRAPH_BASE/me/drive".toHttpUrl().newBuilder().addQueryParameter("\$select", "id").build(),
                ).get(),
            ).build(),
        ).optString("id").takeIf { it.isNotBlank() } ?: throw invalidCloudResponse()
        cachedOneDriveDriveId = id
        return id
    }

    private fun cacheOneDriveFolder(path: String, value: JSONObject) {
        val id = value.optString("id").takeIf { it.isNotBlank() } ?: return
        val driveId = value.optJSONObject("parentReference")?.optString("driveId")
            ?.takeIf { it.isNotBlank() } ?: return
        synchronized(oneDriveFolderReferences) {
            oneDriveFolderReferences[path] = OneDriveFolderReference(id, driveId)
        }
    }

    private fun removeOneDriveFolderPath(path: String) {
        synchronized(oneDriveFolderReferences) {
            oneDriveFolderReferences.keys.filter { it == path || it.startsWith("$path/") }
                .forEach(oneDriveFolderReferences::remove)
        }
    }

    private fun replaceOneDriveFolderPath(oldPath: String, newPath: String, id: String) {
        synchronized(oneDriveFolderReferences) {
            val values = oneDriveFolderReferences.filterKeys { it == oldPath || it.startsWith("$oldPath/") }
            values.keys.forEach(oneDriveFolderReferences::remove)
            values.forEach { (path, reference) ->
                oneDriveFolderReferences[newPath + path.removePrefix(oldPath)] = reference
            }
            val driveId = values[oldPath]?.driveId ?: cachedOneDriveDriveId
            if (driveId != null) oneDriveFolderReferences[newPath] = OneDriveFolderReference(id, driveId)
        }
    }

    private fun executeJson(request: Request): JSONObject = executeResponse(request).use { response ->
        try {
            JSONObject(response.body.string())
        } catch (_: Exception) {
            throw invalidCloudResponse()
        }
    }

    private fun executeEmpty(request: Request) {
        executeResponse(request).close()
    }

    private fun executeUploadChunk(request: Request, finalChunk: Boolean) {
        executeResponse(request).use { response ->
            if (finalChunk && response.code !in setOf(200, 201)) throw invalidCloudResponse()
            if (!finalChunk && response.code != 202) throw invalidCloudResponse()
        }
    }

    private fun executeResponse(request: Request): Response = try {
        client.newCall(request).execute().also(::validateResponse)
    } catch (error: RemoteServiceException) {
        throw error
    } catch (error: IOException) {
        throw RemoteServiceException.Connection("클라우드 서비스에 연결할 수 없습니다.", error)
    }

    private fun validateResponse(response: Response) {
        if (response.isSuccessful) return
        val status = response.code
        response.close()
        if (status == 401 || status == 403) throw RemoteServiceException.Authentication()
        if (status == 404) throw RemoteServiceException.Server("클라우드 파일 또는 폴더를 찾을 수 없습니다.")
        if (status == 409) throw RemoteServiceException.Server("같은 이름의 파일 또는 폴더가 이미 있습니다.")
        throw RemoteServiceException.Server("클라우드 요청이 거부되었습니다 (HTTP $status).")
    }

    private fun executeMonitorResponse(request: Request): Response = try {
        val response = monitorClient.newCall(request).execute()
        if (response.isSuccessful) {
            response
        } else {
            val status = response.code
            response.close()
            throw RemoteServiceException.Server("OneDrive 복사 상태를 확인하지 못했습니다 (HTTP $status).")
        }
    } catch (error: RemoteServiceException) {
        throw error
    } catch (error: IOException) {
        throw RemoteServiceException.Connection("OneDrive 복사 상태를 확인할 수 없습니다.", error)
    }

    private fun authorized(builder: Request.Builder): Request.Builder =
        builder.header("Authorization", "Bearer $accessToken").header("Accept", "application/json")

    private fun checkedPath(raw: String): String {
        if (raw.isBlank() || '\u0000' in raw || '\r' in raw || '\n' in raw || !raw.startsWith('/')) {
            throw RemoteServiceException.Server("사용할 수 없는 클라우드 경로입니다.")
        }
        val components = pathComponents(raw)
        val normalized = if (components.isEmpty()) "/" else "/" + components.joinToString("/")
        val rootComponents = pathComponents(connection.normalizedRootPath)
        if (components.size < rootComponents.size || components.take(rootComponents.size) != rootComponents) {
            throw RemoteServiceException.Server("연결의 시작 위치 밖에는 접근할 수 없습니다.")
        }
        return normalized
    }

    private fun pathComponents(path: String): List<String> {
        val values = path.split('/').filter { it.isNotEmpty() }
        if (values.any { it == "." || it == ".." }) {
            throw RemoteServiceException.Server("연결의 시작 위치 밖에는 접근할 수 없습니다.")
        }
        return values
    }

    private fun validateName(name: String) {
        if (name.isBlank() || name == "." || name == ".." || '/' in name || '\u0000' in name ||
            '\r' in name || '\n' in name
        ) throw RemoteServiceException.Server("사용할 수 없는 파일 또는 폴더 이름입니다.")
    }

    private fun requiredId(item: RemoteFileItem): String {
        val id = item.id
        if (id.isBlank() || '\u0000' in id || '\r' in id || '\n' in id) throw invalidCloudResponse()
        return id
    }

    private fun rejectRootMutation(path: String) {
        val rootComponents = pathComponents(connection.normalizedRootPath)
        val root = if (rootComponents.isEmpty()) "/" else "/" + rootComponents.joinToString("/")
        if (path == root) {
            throw RemoteServiceException.Server("연결의 시작 폴더는 변경하거나 삭제할 수 없습니다.")
        }
    }

    private fun oneDriveChildrenUrl(path: String): HttpUrl = if (path == "/") {
        "$GRAPH_BASE/me/drive/root/children".toHttpUrl()
    } else {
        "$GRAPH_BASE/me/drive/root:${encodedDrivePath(path)}:/children".toHttpUrl()
    }

    private fun oneDriveContentUrl(path: String): HttpUrl =
        "$GRAPH_BASE/me/drive/root:${encodedDrivePath(path)}:/content".toHttpUrl()

    private fun oneDriveCreateUploadSessionUrl(path: String): HttpUrl =
        "$GRAPH_BASE/me/drive/root:${encodedDrivePath(path)}:/createUploadSession".toHttpUrl()

    private fun oneDriveItemUrl(id: String, child: String? = null): HttpUrl =
        GRAPH_BASE.toHttpUrl().newBuilder().addPathSegments("me/drive/items").addPathSegment(id)
            .apply { child?.let(::addPathSegment) }.build()

    private fun oneDrivePathItemUrl(path: String): HttpUrl = if (path == "/") {
        "$GRAPH_BASE/me/drive/root".toHttpUrl()
    } else {
        "$GRAPH_BASE/me/drive/root:${encodedDrivePath(path)}:".toHttpUrl()
    }

    private fun googleItemUrl(id: String): HttpUrl = GOOGLE_FILES.newBuilder().addPathSegment(id).build()

    private fun isMicrosoftGraphUrl(url: HttpUrl): Boolean =
        url.isHttps && url.host.equals("graph.microsoft.com", ignoreCase = true) && url.encodedPath.startsWith("/v1.0/")

    private fun isAllowedMicrosoftMonitorUrl(url: HttpUrl): Boolean {
        if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty()) return false
        val host = url.host.lowercase()
        return host == "graph.microsoft.com" || MICROSOFT_MONITOR_SUFFIXES.any(host::endsWith)
    }

    private fun cloudItem(
        id: String,
        name: String,
        path: String,
        isDirectory: Boolean,
        size: Long,
        modified: String?,
        mimeType: String?,
    ) = RemoteFileItem(
        id = id,
        name = name,
        path = path,
        isDirectory = isDirectory,
        size = size,
        modifiedAt = modified?.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it) }.getOrNull() },
        mimeType = mimeType,
    )

    private fun unsupportedProvider(): Nothing =
        throw RemoteServiceException.Unsupported("지원하지 않는 클라우드 연결입니다.")

    private fun invalidCloudResponse() =
        RemoteServiceException.Server("클라우드 서비스의 응답을 확인하지 못했습니다.")

    private data class OneDriveFolderReference(val id: String, val driveId: String)

    private companion object {
        val SUPPORTED_KINDS = setOf(ConnectionKind.DROPBOX, ConnectionKind.ONEDRIVE, ConnectionKind.GOOGLE_DRIVE)
        val OCTET_STREAM = "application/octet-stream".toMediaType()
        val EMPTY_BODY = ByteArray(0).toRequestBody(OCTET_STREAM)
        const val MAX_PAGES = 10_000
        const val MAX_COPY_POLLS = 600
        const val DOWNLOAD_CHUNK_BYTES = 256 * 1024
        const val ONEDRIVE_CHUNK_BYTES = 10 * 1024 * 1024
        const val DROPBOX_UPLOAD_CHUNK_BYTES = 8 * 1024 * 1024
        const val DROPBOX_SINGLE_UPLOAD_LIMIT = 140L * 1024 * 1024
        const val GRAPH_BASE = "https://graph.microsoft.com/v1.0"
        const val GOOGLE_FOLDER_MIME = "application/vnd.google-apps.folder"
        val DROPBOX_LIST = "https://api.dropboxapi.com/2/files/list_folder".toHttpUrl()
        val DROPBOX_LIST_CONTINUE = "https://api.dropboxapi.com/2/files/list_folder/continue".toHttpUrl()
        val DROPBOX_CREATE_FOLDER = "https://api.dropboxapi.com/2/files/create_folder_v2".toHttpUrl()
        val DROPBOX_COPY = "https://api.dropboxapi.com/2/files/copy_v2".toHttpUrl()
        val DROPBOX_MOVE = "https://api.dropboxapi.com/2/files/move_v2".toHttpUrl()
        val DROPBOX_DELETE = "https://api.dropboxapi.com/2/files/delete_v2".toHttpUrl()
        val DROPBOX_CONTENT_DOWNLOAD = "https://content.dropboxapi.com/2/files/download".toHttpUrl()
        val DROPBOX_CONTENT_UPLOAD = "https://content.dropboxapi.com/2/files/upload".toHttpUrl()
        val DROPBOX_UPLOAD_SESSION_START = "https://content.dropboxapi.com/2/files/upload_session/start".toHttpUrl()
        val DROPBOX_UPLOAD_SESSION_APPEND = "https://content.dropboxapi.com/2/files/upload_session/append_v2".toHttpUrl()
        val DROPBOX_UPLOAD_SESSION_FINISH = "https://content.dropboxapi.com/2/files/upload_session/finish".toHttpUrl()
        val GOOGLE_FILES = "https://www.googleapis.com/drive/v3/files".toHttpUrl()
        val GOOGLE_UPLOAD = "https://www.googleapis.com/upload/drive/v3/files".toHttpUrl()
        val MICROSOFT_MONITOR_SUFFIXES = setOf(
            ".sharepoint.com", ".sharepoint.us", ".sharepoint.cn", ".sharepoint.de",
            ".1drv.com", ".onedrive.com",
        )
    }
}

private class FileSegmentRequestBody(
    private val file: RandomAccessFile,
    private val offset: Long,
    private val length: Int,
) : RequestBody() {
    override fun contentType(): MediaType = "application/octet-stream".toMediaType()
    override fun contentLength(): Long = length.toLong()

    override fun writeTo(sink: BufferedSink) {
        file.seek(offset)
        var remaining = length
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0) {
            val count = file.read(buffer, 0, minOf(buffer.size, remaining))
            if (count < 0) throw IOException("클라우드 업로드 파일을 읽지 못했습니다.")
            sink.write(buffer, 0, count)
            remaining -= count
        }
    }
}

private fun jsonPost(url: HttpUrl, value: JSONObject): Request.Builder =
    Request.Builder().url(url).post(jsonBody(value))

private fun jsonBody(value: JSONObject): RequestBody =
    value.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

private fun childPath(parent: String, name: String): String =
    if (parent == "/") "/$name" else "${parent.trimEnd('/')}/$name"

private fun parentPath(path: String): String = path.substringBeforeLast('/', "").ifBlank { "/" }

private fun encodedDrivePath(path: String): String = path.split('/').filter { it.isNotEmpty() }.joinToString(
    separator = "/",
    prefix = "/",
) { URLEncoder.encode(it, StandardCharsets.UTF_8.name()).replace("+", "%20") }

private fun String.toHttpUrlOrNull(): HttpUrl? = runCatching { toHttpUrl() }.getOrNull()

private fun escapeGoogleQuery(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'")

private fun isGoogleNativeMime(mimeType: String?): Boolean =
    mimeType?.startsWith("application/vnd.google-apps.") == true && mimeType != "application/vnd.google-apps.folder"

private fun keepBothName(originalName: String, existingNames: Collection<String>): String {
    if (originalName !in existingNames) return originalName
    val dot = originalName.lastIndexOf('.').takeIf { it > 0 } ?: -1
    val extension = if (dot >= 0) originalName.substring(dot) else ""
    val stem = (if (dot >= 0) originalName.substring(0, dot) else originalName)
        .replace(Regex(" \\(\\d+\\)$"), "")
    for (index in 1..9_999) {
        val candidate = "$stem ($index)$extension"
        if (candidate !in existingNames) return candidate
    }
    return "${UUID.randomUUID()}-$originalName"
}

private fun cloudHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(90, TimeUnit.SECONDS)
    .writeTimeout(90, TimeUnit.SECONDS)
    .followRedirects(true)
    .build()
