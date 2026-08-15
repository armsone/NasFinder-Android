package com.armsone.nasfinder.network

import com.armsone.nasfinder.model.ConnectionKind
import com.armsone.nasfinder.model.RemoteConnection
import com.armsone.nasfinder.model.RemoteFileItem
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class CloudDriveFileServiceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `server error never exposes access token or response body`() {
        val token = "top-secret-access-token"
        val client = fakeClient { request ->
            response(request, 500, "{\"detail\":\"raw-server-detail $token\"}")
        }
        val service = service(ConnectionKind.DROPBOX, token, client)

        val error = assertThrows(RemoteServiceException.Server::class.java) {
            runBlocking { service.list("/") }
        }

        assertFalse(error.message.orEmpty().contains(token))
        assertFalse(error.message.orEmpty().contains("raw-server-detail"))
        assertEquals(500, error.message.orEmpty().substringAfter("HTTP ").substringBefore(')').toInt())
    }

    @Test
    fun `Dropbox list follows cursor and returns every page`() = runBlocking {
        val calls = mutableListOf<Request>()
        val client = fakeClient { request ->
            calls += request
            when (calls.size) {
                1 -> response(
                    request,
                    body = """{"entries":[{".tag":"folder","name":"A","path_display":"/A","id":"id:a"}],"has_more":true,"cursor":"cursor-1"}""",
                )
                2 -> response(
                    request,
                    body = """{"entries":[{".tag":"file","name":"b.txt","path_display":"/b.txt","id":"id:b","size":3}],"has_more":false,"cursor":"cursor-2"}""",
                )
                else -> error("unexpected request")
            }
        }

        val items = service(ConnectionKind.DROPBOX, client = client).list("/")

        assertEquals(listOf("A", "b.txt"), items.map { it.name })
        assertTrue(calls[1].url.encodedPath.endsWith("/list_folder/continue"))
        assertTrue(calls[1].bodyUtf8().contains("\"cursor\":\"cursor-1\""))
    }

    @Test
    fun `OneDrive rejects pagination links outside Microsoft Graph`() {
        val calls = AtomicInteger()
        val client = fakeClient { request ->
            calls.incrementAndGet()
            response(request, body = """{"value":[],"@odata.nextLink":"https://evil.example/collect"}""")
        }

        val error = assertThrows(RemoteServiceException.Server::class.java) {
            runBlocking { service(ConnectionKind.ONEDRIVE, client = client).list("/") }
        }

        assertEquals(1, calls.get())
        assertFalse(error.message.orEmpty().contains("evil.example"))
    }

    @Test
    fun `Google pagination caches folder id and blocks native document download`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = fakeClient { request ->
            requests += request
            when (requests.size) {
                1 -> response(
                    request,
                    body = """{"files":[{"id":"folder-id","name":"Folder","mimeType":"application/vnd.google-apps.folder"},{"id":"sheet-id","name":"Sheet","mimeType":"application/vnd.google-apps.spreadsheet"}],"nextPageToken":"page-2"}""",
                )
                2 -> response(
                    request,
                    body = """{"files":[{"id":"file-id","name":"file.bin","mimeType":"application/octet-stream","size":"4"}]}""",
                )
                3 -> response(request, body = """{"files":[]}""")
                else -> error("unexpected request")
            }
        }
        val service = service(ConnectionKind.GOOGLE_DRIVE, client = client)

        val root = service.list("/")
        service.list("/Folder")
        val native = root.single { it.name == "Sheet" }
        assertThrows(RemoteServiceException.Unsupported::class.java) {
            runBlocking { service.download(native, temporaryFolder.newFile("native")) }
        }

        assertEquals(3, requests.size)
        assertEquals("page-2", requests[1].url.queryParameter("pageToken"))
        assertTrue(requests[2].url.queryParameter("q").orEmpty().contains("'folder-id' in parents"))
        assertFalse(requests[2].url.queryParameter("q").orEmpty().contains("name = 'Folder'"))
    }

    @Test
    fun `download rejects a body shorter than declared content length`() {
        val client = fakeClient { request ->
            response(request, body = DeclaredLengthBody("abc".toByteArray(), 5))
        }
        val destination = File(temporaryFolder.root, "download.bin")
        val item = RemoteFileItem("id:file", "file.bin", "/file.bin", false, size = 5)

        assertThrows(RemoteServiceException.Connection::class.java) {
            runBlocking { service(ConnectionKind.DROPBOX, client = client).download(item, destination) }
        }

        assertFalse(destination.exists())
        assertTrue(temporaryFolder.root.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    @Test
    fun `Dropbox upload requests provider atomic keepBoth`() = runBlocking {
        lateinit var upload: Request
        val client = fakeClient { request ->
            upload = request
            response(request, body = """{"id":"id:new","name":"a.txt"}""")
        }
        val source = sourceFile("a.txt", "dropbox")

        service(ConnectionKind.DROPBOX, client = client).upload("/", source)

        val argument = upload.header("Dropbox-API-Arg").orEmpty()
        assertTrue(argument.contains("\"mode\":\"add\""))
        assertTrue(argument.contains("\"autorename\":true"))
        assertEquals("dropbox", upload.bodyUtf8())
    }

    @Test
    fun `OneDrive upload chooses keepBoth name and does not send token to upload URL`() = runBlocking {
        val requests = mutableListOf<Request>()
        var uploadedBody = ""
        val client = fakeClient { request ->
            requests += request
            when (requests.size) {
                1 -> response(request, body = """{"value":[{"id":"old","name":"a.txt","size":1,"file":{"mimeType":"text/plain"}}]}""")
                2 -> response(request, body = """{"uploadUrl":"https://tenant.up.1drv.com/upload/session"}""")
                3 -> {
                    uploadedBody = request.bodyUtf8()
                    response(request, 201, """{"id":"new"}""")
                }
                else -> error("unexpected request")
            }
        }

        service(ConnectionKind.ONEDRIVE, client = client).upload("/", sourceFile("a.txt", "one"))

        assertTrue(requests[1].url.toString().contains("a%20%281%29.txt"))
        assertTrue(requests[1].bodyUtf8().contains("\"@microsoft.graph.conflictBehavior\":\"rename\""))
        assertEquals(null, requests[2].header("Authorization"))
        assertEquals("bytes 0-2/3", requests[2].header("Content-Range"))
        assertEquals("one", uploadedBody)
    }

    @Test
    fun `Google resumable upload keeps both and confines bearer token to Google host`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = fakeClient { request ->
            requests += request
            when (requests.size) {
                1 -> response(request, body = """{"files":[{"id":"old","name":"a.txt","mimeType":"text/plain"}]}""")
                2 -> response(
                    request,
                    body = "{}",
                    extraHeaders = mapOf("Location" to "https://www.googleapis.com/upload/drive/v3/files?upload_id=safe"),
                )
                3 -> response(request, body = """{"id":"new"}""")
                else -> error("unexpected request")
            }
        }

        service(ConnectionKind.GOOGLE_DRIVE, client = client).upload("/", sourceFile("a.txt", "google"))

        assertTrue(requests[1].bodyUtf8().contains("\"name\":\"a (1).txt\""))
        assertEquals("Bearer test-token", requests[1].header("Authorization"))
        assertEquals("Bearer test-token", requests[2].header("Authorization"))
        assertEquals("google", requests[2].bodyUtf8())
    }

    @Test
    fun `Dropbox copy and move use provider atomic autorename endpoints`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = fakeClient { request ->
            requests += request
            response(request, body = """{"metadata":{"id":"id:new"}}""")
        }
        val item = RemoteFileItem("id:a", "a.txt", "/Source/a.txt", false)
        val service = service(ConnectionKind.DROPBOX, client = client)

        service.copy(listOf(item), "/Dest")
        service.move(listOf(item), "/Dest")

        assertTrue(requests[0].url.encodedPath.endsWith("/files/copy_v2"))
        assertTrue(requests[1].url.encodedPath.endsWith("/files/move_v2"))
        requests.forEach { request ->
            assertTrue(request.bodyUtf8().contains("\"to_path\":\"/Dest/a.txt\""))
            assertTrue(request.bodyUtf8().contains("\"autorename\":true"))
            assertEquals("Bearer test-token", request.header("Authorization"))
        }
        assertFalse(requests[0].bodyUtf8().contains("allow_ownership_transfer"))
        assertTrue(requests[1].bodyUtf8().contains("\"allow_ownership_transfer\":false"))
    }

    @Test
    fun `OneDrive copy monitors without token and move patches resolved parent`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = fakeClient { request ->
            requests += request
            when (requests.size) {
                1 -> response(request, body = """{"id":"dest-id","parentReference":{"driveId":"drive-id"}}""")
                2 -> response(request, body = """{"value":[{"id":"old","name":"a.txt","file":{"mimeType":"text/plain"}}]}""")
                3 -> response(
                    request,
                    202,
                    "",
                    extraHeaders = mapOf("Location" to "https://tenant.sharepoint.com/_api/v2.0/monitor/job"),
                )
                4 -> response(request, body = "{}")
                5 -> response(request, body = """{"value":[{"id":"old","name":"a.txt","file":{}},{"id":"copy","name":"a (1).txt","file":{}}]}""")
                6 -> response(request, body = """{"id":"source"}""")
                else -> error("unexpected request")
            }
        }
        val item = RemoteFileItem("source", "a.txt", "/Source/a.txt", false)
        val service = service(ConnectionKind.ONEDRIVE, client = client)

        service.copy(listOf(item), "/Dest")
        service.move(listOf(item), "/Dest")

        assertTrue(requests[2].url.encodedPath.endsWith("/items/source/copy"))
        assertTrue(requests[2].bodyUtf8().contains("\"id\":\"dest-id\""))
        assertTrue(requests[2].bodyUtf8().contains("\"driveId\":\"drive-id\""))
        assertTrue(requests[2].bodyUtf8().contains("\"name\":\"a (1).txt\""))
        assertEquals(null, requests[3].header("Authorization"))
        assertTrue(requests[5].bodyUtf8().contains("\"parentReference\":{\"id\":\"dest-id\"}"))
        assertTrue(requests[5].bodyUtf8().contains("\"name\":\"a (2).txt\""))
    }

    @Test
    fun `OneDrive copy rejects monitor URL outside Microsoft hosts before sending a request`() {
        val requests = mutableListOf<Request>()
        val client = fakeClient { request ->
            requests += request
            when (requests.size) {
                1 -> response(request, body = """{"id":"dest-id","parentReference":{"driveId":"drive-id"}}""")
                2 -> response(request, body = """{"value":[]}""")
                3 -> response(
                    request,
                    202,
                    "",
                    extraHeaders = mapOf("Location" to "https://evil.example/collect"),
                )
                else -> error("monitor URL boundary was bypassed")
            }
        }
        val service = service(ConnectionKind.ONEDRIVE, client = client)

        val error = assertThrows(RemoteServiceException.Server::class.java) {
            runBlocking {
                service.copy(
                    listOf(RemoteFileItem("source", "a.txt", "/Source/a.txt", false)),
                    "/Dest",
                )
            }
        }

        assertEquals(3, requests.size)
        assertFalse(error.message.orEmpty().contains("evil.example"))
    }

    @Test
    fun `Google file copy and move preserve parent cache and reject folder copy`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = fakeClient { request ->
            requests += request
            when (requests.size) {
                1 -> response(request, body = """{"files":[{"id":"dest-id"}]}""")
                2 -> response(request, body = """{"files":[{"id":"old","name":"a.txt","mimeType":"text/plain"}]}""")
                3 -> response(request, body = """{"id":"copy-id","name":"a (1).txt"}""")
                4 -> response(request, body = """{"files":[{"id":"old","name":"a.txt","mimeType":"text/plain"},{"id":"copy-id","name":"a (1).txt","mimeType":"text/plain"}]}""")
                5 -> response(request, body = """{"parents":["source-parent"]}""")
                6 -> response(request, body = """{"id":"source","parents":["dest-id"],"name":"a (2).txt"}""")
                else -> error("unexpected request")
            }
        }
        val item = RemoteFileItem("source", "a.txt", "/Source/a.txt", false, mimeType = "text/plain")
        val service = service(ConnectionKind.GOOGLE_DRIVE, client = client)

        service.copy(listOf(item), "/Dest")
        service.move(listOf(item), "/Dest")

        assertTrue(requests[2].url.encodedPath.endsWith("/files/source/copy"))
        assertTrue(requests[2].bodyUtf8().contains("\"parents\":[\"dest-id\"]"))
        assertTrue(requests[2].bodyUtf8().contains("\"name\":\"a (1).txt\""))
        assertEquals("dest-id", requests[5].url.queryParameter("addParents"))
        assertEquals("source-parent", requests[5].url.queryParameter("removeParents"))
        assertTrue(requests[5].bodyUtf8().contains("\"name\":\"a (2).txt\""))

        assertThrows(RemoteServiceException.Unsupported::class.java) {
            runBlocking {
                service.copy(listOf(RemoteFileItem("folder", "Folder", "/Source/Folder", true)), "/Dest")
            }
        }
        assertEquals(6, requests.size)
    }

    private fun service(
        kind: ConnectionKind,
        token: String = "test-token",
        client: OkHttpClient,
    ) = CloudDriveFileService(
        RemoteConnection(name = kind.title, kind = kind, host = "", username = "", rootPath = "/"),
        token,
        client,
    )

    private fun sourceFile(name: String, contents: String): File =
        File(temporaryFolder.root, name).apply { writeText(contents) }

    private fun fakeClient(block: (Request) -> Response): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain -> block(chain.request()) })
        .build()

    private fun response(
        request: Request,
        code: Int = 200,
        body: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Response = response(request, code, body.toResponseBody(JSON), extraHeaders)

    private fun response(
        request: Request,
        code: Int = 200,
        body: ResponseBody,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("fake")
        .body(body)
        .apply { extraHeaders.forEach { (name, value) -> header(name, value) } }
        .build()

    private fun Request.bodyUtf8(): String = Buffer().also { body?.writeTo(it) }.readUtf8()

    private class DeclaredLengthBody(private val bytes: ByteArray, private val length: Long) : ResponseBody() {
        override fun contentType(): MediaType = OCTET
        override fun contentLength(): Long = length
        override fun source(): BufferedSource = Buffer().write(bytes)
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        val OCTET = "application/octet-stream".toMediaType()
    }
}
