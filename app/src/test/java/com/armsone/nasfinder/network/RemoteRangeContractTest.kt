package com.armsone.nasfinder.network

import com.armsone.nasfinder.model.ConnectionKind
import com.armsone.nasfinder.model.RemoteConnection
import com.armsone.nasfinder.model.RemoteFileItem
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RemoteRangeContractTest {
    private val item = RemoteFileItem("id", "video.mp4", "/video.mp4", false, size = 6)

    @Test fun `HTTP partial response validates exact requested range`() = runBlocking {
        lateinit var captured: Request
        val client = fakeClient { request ->
            captured = request
            response(request, 206, "cde", mapOf("Content-Range" to "bytes 2-4/6"))
        }

        val bytes = httpBoundedRangeRead(
            client, Request.Builder().url("https://range.test/file"), item, 2, 3, "test",
        )

        assertEquals("bytes=2-4", captured.header("Range"))
        assertArrayEquals("cde".toByteArray(), bytes)
    }

    @Test fun `ignored HTTP Range is bounded only for zero offset`() = runBlocking {
        val client = fakeClient { request -> response(request, 200, "0123456789") }

        assertArrayEquals(
            "012".toByteArray(),
            httpBoundedRangeRead(
                client, Request.Builder().url("https://range.test/file"), item.copy(size = 10), 0, 3, "test",
            ),
        )
        assertThrows(RemoteServiceException.Unsupported::class.java) {
            runBlocking {
                httpBoundedRangeRead(
                    client, Request.Builder().url("https://range.test/file"), item.copy(size = 10), 3, 3, "test",
                )
            }
        }
        Unit
    }

    @Test fun `invalid content range and oversized requests are rejected`() {
        val invalidClient = fakeClient { request ->
            response(request, 206, "abc", mapOf("Content-Range" to "bytes 0-2/6"))
        }
        assertThrows(RemoteServiceException.Server::class.java) {
            runBlocking {
                httpBoundedRangeRead(
                    invalidClient, Request.Builder().url("https://range.test/file"), item, 2, 3, "test",
                )
            }
        }
        assertThrows(RemoteServiceException.Server::class.java) {
            RemoteRangeContract.validate(item, 0, RemoteRangeContract.MAX_BYTES + 1)
        }
        assertThrows(RemoteServiceException.Unsupported::class.java) {
            RemoteRangeContract.validate(item.copy(isDirectory = true), 0, 1)
        }
    }

    @Test fun `Dropbox bounded read preserves POST and authorization contracts`() = runBlocking {
        lateinit var captured: Request
        val client = fakeClient { request ->
            captured = request
            response(request, 206, "abc", mapOf("Content-Range" to "bytes 0-2/3"))
        }
        val connection = RemoteConnection(
            name = "Dropbox", kind = ConnectionKind.DROPBOX, host = "api.dropboxapi.com",
            username = "", usesTls = true,
        )
        val service = CloudDriveFileService(connection, "secret-token", client)

        val bytes = service.readRange(item.copy(size = 3), 0, 3)

        assertArrayEquals("abc".toByteArray(), bytes)
        assertEquals("POST", captured.method)
        assertEquals("bytes=0-2", captured.header("Range"))
        assertEquals("Bearer secret-token", captured.header("Authorization"))
    }

    @Test fun `Synology bounded read authenticates and sends exact Range`() = runBlocking {
        lateinit var rangeRequest: Request
        val client = fakeClient { request ->
            if (request.url.encodedPath.endsWith("auth.cgi")) {
                response(request, 200, """{"success":true,"data":{"sid":"sid-value"}}""")
            } else {
                rangeRequest = request
                response(request, 206, "bc", mapOf("Content-Range" to "bytes 1-2/6"))
            }
        }
        val connection = RemoteConnection(
            name = "DSM", kind = ConnectionKind.SYNOLOGY, host = "dsm.test", username = "user",
            usesTls = false,
        )

        val bytes = SynologyFileService(connection, "password", client).readRange(item, 1, 2)

        assertArrayEquals("bc".toByteArray(), bytes)
        assertEquals("bytes=1-2", rangeRequest.header("Range"))
        assertEquals("sid-value", rangeRequest.url.queryParameter("_sid"))
    }

    @Test fun `WebDAV bounded read keeps Basic auth and path`() = runBlocking {
        lateinit var rangeRequest: Request
        val client = fakeClient { request ->
            rangeRequest = request
            response(request, 206, "de", mapOf("Content-Range" to "bytes 3-4/6"))
        }
        val connection = RemoteConnection(
            name = "DAV", kind = ConnectionKind.WEBDAV, host = "dav.test", username = "user",
            usesTls = false,
        )

        val bytes = WebDavFileService(connection, "password", client).readRange(item, 3, 2)

        assertArrayEquals("de".toByteArray(), bytes)
        assertEquals("bytes=3-4", rangeRequest.header("Range"))
        assertEquals("Basic dXNlcjpwYXNzd29yZA==", rangeRequest.header("Authorization"))
        assertEquals("/video.mp4", rangeRequest.url.encodedPath)
    }

    private fun fakeClient(block: (Request) -> Response): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(Interceptor { chain -> block(chain.request()) }).build()

    private fun response(
        request: Request,
        code: Int,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ): Response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
        .code(code).message("fake").apply { headers.forEach { (name, value) -> header(name, value) } }
        .body(body.toResponseBody()).build()
}
