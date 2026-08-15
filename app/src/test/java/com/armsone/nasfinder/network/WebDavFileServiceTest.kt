package com.armsone.nasfinder.network

import com.armsone.nasfinder.model.ConnectionKind
import com.armsone.nasfinder.model.RemoteConnection
import com.armsone.nasfinder.model.RemoteFileItem
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WebDavFileServiceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `mutation methods preserve auth destination overwrite and server recursive delete contracts`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = fakeClient { request ->
            requests += request
            response(request, if (request.method == "DELETE") 204 else 201)
        }
        val service = service(client)
        val file = item("/a.txt", "a.txt")

        service.createFolder("/", "Folder")
        service.rename(file, "renamed.txt")
        service.move(listOf(file), "/Move")
        service.copy(listOf(file), "/Copy")
        service.delete(listOf(file))

        assertEquals(listOf("MKCOL", "MOVE", "MOVE", "COPY", "DELETE"), requests.map { it.method })
        requests.forEach { assertEquals(Credentials.basic("user", "secret"), it.header("Authorization")) }
        assertDestination(requests[1], "/renamed.txt")
        assertDestination(requests[2], "/Move/a.txt")
        assertDestination(requests[3], "/Copy/a.txt")
        assertTrue(requests[4].url.encodedPath.endsWith("/a.txt"))
    }

    @Test
    fun `upload uses PROPFIND keepBoth and conditional PUT`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = fakeClient { request ->
            requests += request
            when (request.method) {
                "PROPFIND" -> response(request, 207, davXml("/", "/a.txt"))
                "PUT" -> response(request, 201)
                else -> error("unexpected ${request.method}")
            }
        }
        val source = temporaryFolder.newFile("a.txt").apply { writeText("contents") }

        service(client).upload("/", source)

        assertEquals(listOf("PROPFIND", "PUT"), requests.map { it.method })
        assertEquals("1", requests[0].header("Depth"))
        assertTrue(requests[1].url.encodedPath.endsWith("/a%20(1).txt"))
        assertEquals("*", requests[1].header("If-None-Match"))
        assertEquals("contents", requests[1].bodyUtf8())
    }

    @Test
    fun `upload retries a raced conflict with a newly checked keepBoth name`() = runBlocking {
        val requests = mutableListOf<Request>()
        var propfindCount = 0
        val client = fakeClient { request ->
            requests += request
            when (request.method) {
                "PROPFIND" -> {
                    propfindCount += 1
                    response(
                        request,
                        207,
                        if (propfindCount == 1) davXml("/", "/a.txt") else davXml("/", "/a.txt", "/a%20(1).txt"),
                    )
                }
                "PUT" -> response(request, if (requests.count { it.method == "PUT" } == 1) 412 else 201)
                else -> error("unexpected ${request.method}")
            }
        }

        service(client).upload("/", temporaryFolder.newFile("a.txt").apply { writeText("x") })

        val puts = requests.filter { it.method == "PUT" }
        assertEquals(2, puts.size)
        assertTrue(puts[0].url.encodedPath.endsWith("/a%20(1).txt"))
        assertTrue(puts[1].url.encodedPath.endsWith("/a%20(2).txt"))
        assertTrue(puts.all { it.header("If-None-Match") == "*" })
    }

    @Test
    fun `PROPFIND rejects foreign href host without exposing it`() {
        val client = fakeClient { request ->
            response(request, 207, davXml("/", "https://evil.example/stolen"))
        }

        val error = assertThrows(RemoteServiceException.Server::class.java) {
            runBlocking { service(client).list("/") }
        }

        assertFalse(error.message.orEmpty().contains("evil.example"))
    }

    @Test
    fun `failure exposes status only and never response body or password`() {
        val client = fakeClient { request ->
            response(request, 500, "raw server secret response secret")
        }

        val error = assertThrows(RemoteServiceException.Server::class.java) {
            runBlocking { service(client).createFolder("/", "Folder") }
        }

        assertTrue(error.message.orEmpty().contains("HTTP 500"))
        assertFalse(error.message.orEmpty().contains("raw server"))
        assertFalse(error.message.orEmpty().contains("secret"))
    }

    @Test
    fun `connection root cannot be mutated and traversal never reaches interceptor`() {
        var calls = 0
        val client = fakeClient { request -> calls += 1; response(request, 204) }
        val service = service(client, root = "/safe")

        assertThrows(RemoteServiceException.Server::class.java) {
            runBlocking { service.delete(listOf(item("/safe", "safe", directory = true))) }
        }
        assertThrows(RemoteServiceException.Server::class.java) {
            runBlocking { service.createFolder("/safe/../outside", "Folder") }
        }

        assertEquals(0, calls)
    }

    @Test
    fun `download resumes deterministic partial file with validated range`() = runBlocking {
        lateinit var request: Request
        val client = fakeClient {
            request = it
            response(
                it,
                206,
                "def",
                mapOf("Content-Range" to "bytes 3-5/6", "Content-Length" to "3"),
            )
        }
        val destination = temporaryFolder.root.resolve("file.bin")
        HttpRangeContract.partialFile(destination).writeText("abc")

        service(client).download(RemoteFileItem("/file.bin", "file.bin", "/file.bin", false, 6), destination)

        assertEquals("bytes=3-", request.header("Range"))
        assertEquals("abcdef", destination.readText())
        assertFalse(HttpRangeContract.partialFile(destination).exists())
    }

    private fun assertDestination(request: Request, expectedPath: String) {
        assertEquals("F", request.header("Overwrite"))
        assertEquals("http://dav.test:8080$expectedPath", request.header("Destination"))
    }

    private fun service(client: OkHttpClient, root: String = "/") = WebDavFileService(
        RemoteConnection(
            name = "WebDAV",
            kind = ConnectionKind.WEBDAV,
            host = "dav.test",
            port = 8080,
            username = "user",
            rootPath = root,
            usesTls = false,
        ),
        "secret",
        client,
    )

    private fun item(path: String, name: String, directory: Boolean = false) =
        RemoteFileItem(path, name, path, directory)

    private fun fakeClient(block: (Request) -> Response): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain -> block(chain.request()) })
        .build()

    private fun response(
        request: Request,
        code: Int,
        body: String = "",
        headers: Map<String, String> = emptyMap(),
    ): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("fake")
        .body(body.toResponseBody("application/xml; charset=utf-8".toMediaType()))
        .apply { headers.forEach { (name, value) -> header(name, value) } }
        .build()

    private fun davXml(vararg paths: String): String = buildString {
        append("<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\">")
        paths.forEach { path ->
            append("<d:response><d:href>").append(path).append("</d:href><d:propstat><d:prop>")
            if (path.endsWith('/')) append("<d:resourcetype><d:collection/></d:resourcetype>")
            else append("<d:resourcetype/><d:getcontentlength>1</d:getcontentlength>")
            append("</d:prop></d:propstat></d:response>")
        }
        append("</d:multistatus>")
    }

    private fun Request.bodyUtf8(): String = Buffer().also { body?.writeTo(it) }.readUtf8()
}
