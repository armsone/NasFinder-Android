package com.armsone.nasfinder.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

class WebHardFileStoreJvmTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun listHidesDotItemsAndSortsFoldersBeforeFiles() {
        val root = temporary.newFolder("webhard")
        File(root, "z.txt").writeText("z")
        File(root, ".secret").writeText("hidden")
        File(root, "Folder").mkdir()

        val items = WebHardFileStore(root).list("/")

        assertEquals(listOf("Folder", "z.txt"), items.map { it.name })
        assertTrue(items.first().isDirectory)
        assertEquals(1L, items.last().size)
    }

    @Test fun createAndRecursiveDeleteStayInsideRoot() {
        val root = temporary.newFolder("webhard")
        val store = WebHardFileStore(root)

        store.createDirectory("/parent/child")
        File(root, "parent/child/file.txt").writeText("payload")
        assertTrue(store.list("/parent").single().isDirectory)

        store.delete("/parent")
        assertFalse(File(root, "parent").exists())
        assertFails<WebHardFileStoreException.InvalidPath> { store.delete("/") }
    }

    @Test fun traversalControlCharactersAndHiddenComponentsAreRejected() {
        val store = WebHardFileStore(temporary.newFolder("webhard"))

        listOf("/../outside", "/folder/./file", "/.hidden/file", "/a\r/b", "/a\n/b", "/a\u0000b")
            .forEach { path ->
                assertFails<WebHardFileStoreException.InvalidPath> { store.list(path) }
                assertFails<WebHardFileStoreException.InvalidPath> { store.prepareUpload(path) }
            }
    }

    @Test fun concurrentUploadReservationsApplyKeepBothAndDiscardCleansTemporaryFiles() {
        val root = temporary.newFolder("webhard")
        val store = WebHardFileStore(root)
        File(root, "photo.jpg").writeText("old")

        val first = store.prepareUpload("/photo.jpg")
        val second = store.prepareUpload("/photo.jpg")
        first.outputStream().write("first".toByteArray())
        second.outputStream().write("second".toByteArray())

        val committed = store.commitUpload(first)
        store.discardUpload(second)

        assertEquals("photo (1).jpg", committed.name)
        assertEquals("first", File(root, "photo (1).jpg").readText())
        assertFalse(File(root, "photo (2).jpg").exists())
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".nasfinder-upload-") })
    }

    private inline fun <reified T : Throwable> assertFails(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected ${T::class.java.simpleName}")
        } catch (error: Throwable) {
            if (error !is T) throw error
        }
    }
}

class WebHardHttpServerJvmTest {
    @get:Rule val temporary = TemporaryFolder()
    private val loopback: InetAddress = InetAddress.getLoopbackAddress()

    @Test fun homePageExposesPhoneHardTransferAndViewContracts() {
        val root = temporary.newFolder("webhard")
        runningServer(root) { port ->
            val response = request(port, "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n")

            assertEquals(200, response.status)
            response.assertSecurityHeaders()
            listOf(
                "id=\"uploadQueue\"",
                "webkitdirectory",
                "data-view=\"list\"",
                "data-view=\"small\"",
                "data-view=\"poster\"",
                "id=\"receive\"",
                "addEventListener('drop'",
                "webkitGetAsEntry",
                "xhr.upload.onprogress",
                "/api/preview",
            ).forEach { contract -> assertTrue("Missing PhoneHard contract: $contract", response.body.contains(contract)) }
        }
    }

    @Test fun listRequiresAuthenticationAndEveryResponseHasSecurityHeaders() {
        val root = temporary.newFolder("webhard")
        File(root, "visible.txt").writeText("hello")
        runningServer(root, password = "correct") { port ->
            val unauthorized = request(port, "GET /api/list?path=%2F HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assertEquals(401, unauthorized.status)
            unauthorized.assertSecurityHeaders()

            val authorized = request(
                port,
                "GET /api/list?path=%2F HTTP/1.1\r\nHost: localhost\r\nX-WebHard-Password: correct\r\n\r\n",
            )
            assertEquals(200, authorized.status)
            assertTrue(authorized.body.contains("visible.txt"))
            authorized.assertSecurityHeaders()
        }
    }

    @Test fun httpCreateListAndDeleteRoundTrip() {
        val root = temporary.newFolder("webhard")
        runningServer(root) { port ->
            assertEquals(201, request(port, "POST /api/folder?path=%2FNew HTTP/1.1\r\nHost: localhost\r\n\r\n").status)
            assertTrue(File(root, "New").isDirectory)
            assertTrue(request(port, "GET /api/list?path=%2F HTTP/1.1\r\nHost: localhost\r\n\r\n").body.contains("New"))
            assertEquals(200, request(port, "DELETE /api/item?path=%2FNew HTTP/1.1\r\nHost: localhost\r\n\r\n").status)
            assertFalse(File(root, "New").exists())
        }
    }

    @Test fun putRequiresLengthEnforcesReserveAndUsesKeepBoth() {
        val root = temporary.newFolder("webhard")
        File(root, "same.txt").writeText("old")
        runningServer(root) { port ->
            val missing = request(port, "PUT /api/file?path=%2Fupload.txt HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assertEquals(411, missing.status)

            val tooLarge = request(
                port,
                "PUT /api/file?path=%2Fhuge.bin HTTP/1.1\r\nHost: localhost\r\nContent-Length: ${Long.MAX_VALUE}\r\n\r\n",
            )
            assertEquals(507, tooLarge.status)

            val interrupted = request(
                port,
                "PUT /api/file?path=%2Fpartial.bin HTTP/1.1\r\nHost: localhost\r\nContent-Length: 10\r\n\r\nshort",
                endRequestBody = true,
            )
            assertEquals(400, interrupted.status)
            assertFalse(File(root, "partial.bin").exists())
            assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".nasfinder-upload-") })

            val uploaded = request(
                port,
                "PUT /api/file?path=%2Fsame.txt HTTP/1.1\r\nHost: localhost\r\nContent-Length: 3\r\n\r\nnew",
            )
            assertEquals(201, uploaded.status)
            assertEquals("old", File(root, "same.txt").readText())
            assertEquals("new", File(root, "same (1).txt").readText())
        }
    }

    @Test fun encodedTraversalIsRejectedAndCloseReleasesListener() {
        val root = temporary.newFolder("webhard")
        val server = WebHardHttpServer(WebHardFileStore(root), bindAddress = loopback)
        val port = server.start()
        val traversal = request(port, "GET /api/list?path=%2F..%2Foutside HTTP/1.1\r\nHost: localhost\r\n\r\n")
        assertEquals(400, traversal.status)

        server.close()
        assertEquals(0, server.localPort)
        assertFails<IllegalStateException> { server.start() }
        assertFails<java.io.IOException> {
            Socket().use { it.connect(InetSocketAddress(loopback, port), 500) }
        }
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".nasfinder-upload-") })
    }

    private fun runningServer(root: File, password: String = "", block: (Int) -> Unit) {
        WebHardHttpServer(WebHardFileStore(root), password, loopback).use { server ->
            block(server.start())
        }
    }

    private fun request(port: Int, raw: String, endRequestBody: Boolean = false): HttpResponse {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(loopback, port), 2_000)
            socket.soTimeout = 3_000
            socket.getOutputStream().apply {
                write(raw.toByteArray(StandardCharsets.ISO_8859_1))
                flush()
            }
            if (endRequestBody) socket.shutdownOutput()
            val response = socket.getInputStream().readBytes().toString(StandardCharsets.UTF_8)
            val head = response.substringBefore("\r\n\r\n")
            val body = response.substringAfter("\r\n\r\n", "")
            val lines = head.split("\r\n")
            val status = lines.first().split(' ')[1].toInt()
            val headers = lines.drop(1).associate { line ->
                line.substringBefore(':').lowercase() to line.substringAfter(':').trim()
            }
            return HttpResponse(status, headers, body)
        }
    }

    private fun HttpResponse.assertSecurityHeaders() {
        assertEquals("no-store", headers["cache-control"])
        assertEquals("nosniff", headers["x-content-type-options"])
        assertEquals("close", headers["connection"])
        assertNotNull(headers["content-length"])
    }

    private inline fun <reified T : Throwable> assertFails(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected ${T::class.java.simpleName}")
        } catch (error: Throwable) {
            if (error !is T) throw error
        }
    }

    private data class HttpResponse(
        val status: Int,
        val headers: Map<String, String>,
        val body: String,
    )
}
