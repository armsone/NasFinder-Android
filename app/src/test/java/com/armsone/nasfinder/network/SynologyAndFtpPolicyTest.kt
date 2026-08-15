package com.armsone.nasfinder.network

import com.armsone.nasfinder.model.ConnectionKind
import com.armsone.nasfinder.model.RemoteConnection
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
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
import java.util.concurrent.atomic.AtomicInteger

class SynologyAndFtpPolicyTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `Synology credential preserves plain password and recognizes explicit OTP envelope`() {
        assertEquals(SynologyCredential("plain password", null), SynologyCredential.parse("plain password"))
        val credential = SynologyCredential.parse(
            """{"_nasfinder":"synology-v1","password":"secret","otp":"123456"}""",
        )
        assertEquals("secret", credential.password)
        assertEquals("123456", credential.otp)

        val error = assertThrows(RemoteServiceException.Authentication::class.java) {
            SynologyCredential.parse("""{"_nasfinder":"synology-v1","password":"marker","otp":"12x"}""")
        }
        assertFalse(error.message.orEmpty().contains("marker"))
    }

    @Test
    fun `Synology login sends OTP only from explicit envelope`() = runBlocking {
        lateinit var login: Request
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            login = chain.request()
            Response.Builder().request(login).protocol(Protocol.HTTP_1_1).code(200).message("fake")
                .body("""{"success":true,"data":{"sid":"sid"}}""".toResponseBody()).build()
        }).build()
        val connection = RemoteConnection(
            name = "DSM", kind = ConnectionKind.SYNOLOGY, host = "dsm.test", username = "user",
            usesTls = false,
        )
        val service = SynologyFileService(
            connection,
            """{"_nasfinder":"synology-v1","password":"secret","otp":"123456"}""",
            client,
        )

        service.testConnection()

        val body = Buffer().also { login.body?.writeTo(it) }.readUtf8()
        assertTrue(body.contains("passwd=secret"))
        assertTrue(body.contains("otp_code=123456"))
    }

    @Test
    fun `Synology upload keeps SID in query and out of multipart body`() = runBlocking {
        lateinit var upload: Request
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val responseBody = when {
                request.url.encodedPath.endsWith("auth.cgi") ->
                    """{"success":true,"data":{"sid":"session-value"}}"""
                request.method == "GET" ->
                    """{"success":true,"data":{"files":[]}}"""
                else -> {
                    upload = request
                    """{"success":true}"""
                }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fake")
                .body(responseBody.toResponseBody()).build()
        }).build()
        val connection = RemoteConnection(
            name = "DSM", kind = ConnectionKind.SYNOLOGY, host = "dsm.test", username = "user",
            usesTls = false,
        )
        val source = temporary.newFile("upload.txt").apply { writeText("payload") }

        SynologyFileService(connection, "secret", client).upload("/share", source)

        assertEquals("session-value", upload.url.queryParameter("_sid"))
        val multipart = Buffer().also { upload.body?.writeTo(it) }.readUtf8()
        assertFalse(multipart.contains("name=\"_sid\""))
        assertTrue(multipart.contains("name=\"file\""))
    }

    @Test
    fun `Synology upload refreshes an expired SID once and rebuilds the request`() = runBlocking {
        val logins = AtomicInteger()
        val uploads = mutableListOf<String?>()
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val responseBody = when {
                request.url.encodedPath.endsWith("auth.cgi") ->
                    """{"success":true,"data":{"sid":"sid-${logins.incrementAndGet()}"}}"""
                request.method == "GET" -> """{"success":true,"data":{"files":[]}}"""
                else -> {
                    uploads += request.url.queryParameter("_sid")
                    if (uploads.size == 1) """{"success":false,"error":{"code":119}}"""
                    else """{"success":true}"""
                }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fake")
                .body(responseBody.toResponseBody()).build()
        }).build()
        val connection = RemoteConnection(
            name = "DSM", kind = ConnectionKind.SYNOLOGY, host = "dsm.test", username = "user",
            usesTls = false,
        )
        val source = temporary.newFile("refresh.txt").apply { writeText("payload") }

        SynologyFileService(connection, "secret", client).upload("/share", source)

        assertEquals(2, logins.get())
        assertEquals(listOf("sid-1", "sid-2"), uploads)
    }

    @Test
    fun `FTP transfer policy blocks self descendants without prefix confusion`() {
        assertEquals("/folder", FtpTransferPolicy.parent("/folder/file"))
        assertTrue(FtpTransferPolicy.isSameOrDescendant("/folder/child", "/folder"))
        assertFalse(FtpTransferPolicy.isSameOrDescendant("/folder-copy", "/folder"))
    }
}
