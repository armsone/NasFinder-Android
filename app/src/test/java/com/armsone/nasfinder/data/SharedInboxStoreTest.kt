package com.armsone.nasfinder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SharedInboxStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun importUsesBasenameUuidExtensionAndPersistsManifest() {
        val files = temporary.newFolder("files")
        val store = SharedInboxStore(files)
        val id = UUID.fromString("78ef8aed-8ac9-4f0c-bad9-aea5f52a35e3")
        val importedAt = Instant.parse("2026-08-14T12:00:00Z")

        val record = store.import(
            originalFilename = "../../휴가 사진.PDF",
            mimeType = "application/pdf",
            input = ByteArrayInputStream("payload".toByteArray()),
            id = id,
            importedAt = importedAt,
        )

        assertEquals("휴가 사진.PDF", record.originalFilename)
        assertEquals("$id.pdf", record.storedFilename)
        assertEquals(7L, record.byteCount)
        assertEquals("payload", store.file(record).readText())
        assertEquals(listOf(record), SharedInboxStore(files).records())
        assertTrue(File(files, "SharedInbox/manifest.json").readText().startsWith("{\"version\":1"))
        assertTrue(File(files, "SharedInbox").listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test fun unsafeOrMissingExtensionIsDroppedAndMimeIsValidated() {
        val files = temporary.newFolder("files")
        val store = SharedInboxStore(files)
        val noExtension = store.import("archive.bad-ext", null, ByteArrayInputStream(byteArrayOf(1)))
        assertEquals(noExtension.id.toString(), noExtension.storedFilename)
        assertNull(noExtension.mimeType)

        val fallback = store.import("..", "application/octet-stream", ByteArrayInputStream(byteArrayOf(2)))
        assertEquals("받은 파일", fallback.originalFilename)
        assertFails<IllegalArgumentException> {
            store.import("file.txt", "invalid\r\ntype", ByteArrayInputStream(byteArrayOf()))
        }
    }

    @Test fun duplicateUuidIsRejectedWithoutOverwritingPayload() {
        val files = temporary.newFolder("files")
        val store = SharedInboxStore(files)
        val id = UUID.randomUUID()
        val first = store.import("first.txt", "text/plain", ByteArrayInputStream("first".toByteArray()), id)

        assertFails<IllegalArgumentException> {
            store.import("second.txt", "text/plain", ByteArrayInputStream("second".toByteArray()), id)
        }

        assertEquals("first", store.file(first).readText())
        assertEquals(1, store.records().size)
    }

    @Test fun manifestCommitFailureRollsBackCopiedPayload() {
        val files = temporary.newFolder("files")
        val id = UUID.randomUUID()
        var payloadExistedBeforeManifest = false
        val hooks = SharedInboxStore.Hooks(
            commitManifest = { _, _ ->
                payloadExistedBeforeManifest = File(files, "SharedInbox/$id.txt").isFile
                throw IOException("injected manifest failure")
            },
            deletePayload = File::delete,
        )
        val store = SharedInboxStore(files, hooks, Unit)

        assertFails<IOException> {
            store.import("file.txt", "text/plain", ByteArrayInputStream("data".toByteArray()), id)
        }

        assertTrue(payloadExistedBeforeManifest)
        assertFalse(File(files, "SharedInbox/$id.txt").exists())
        assertTrue(store.records().isEmpty())
        assertTrue(File(files, "SharedInbox").listFiles().orEmpty().none { it.name.startsWith(".import-") })
    }

    @Test fun payloadDeleteFailureReloadsManifestAndKeepsRecord() {
        val files = temporary.newFolder("files")
        val initial = SharedInboxStore(files)
        val record = initial.import("keep.bin", "application/octet-stream", ByteArrayInputStream(byteArrayOf(1, 2)))
        val failing = SharedInboxStore(
            files,
            SharedInboxStore.Hooks(commitManifest = { target, bytes ->
                val temporaryManifest = File(target.parentFile, ".test-manifest.tmp")
                temporaryManifest.writeBytes(bytes)
                java.nio.file.Files.move(
                    temporaryManifest.toPath(),
                    target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            }, deletePayload = { false }),
            Unit,
        )

        assertFails<IllegalStateException> { failing.delete(record.id) }

        assertEquals(listOf(record), failing.records())
        assertTrue(failing.file(record).isFile)
    }

    @Test fun corruptManifestRejectsPathAndDuplicateContracts() {
        val files = temporary.newFolder("files")
        val inbox = File(files, "SharedInbox").apply { mkdirs() }
        val id = UUID.randomUUID()
        File(inbox, "manifest.json").writeText(
            """{"version":1,"records":[{"id":"$id","originalFilename":"safe.txt","storedFilename":"../escape.txt","mimeType":"text/plain","byteCount":1,"importedAt":"2026-08-14T12:00:00Z"}]}""",
        )
        assertFails<IllegalArgumentException> { SharedInboxStore(files) }

        File(inbox, "manifest.json").writeText(
            """{"version":1,"records":[{"id":"$id","originalFilename":"safe.txt","storedFilename":"$id.txt","mimeType":"text/plain","byteCount":1,"importedAt":"2026-08-14T12:00:00Z"},{"id":"$id","originalFilename":"safe.txt","storedFilename":"$id.txt","mimeType":"text/plain","byteCount":1,"importedAt":"2026-08-14T12:00:00Z"}]}""",
        )
        assertFails<IllegalArgumentException> { SharedInboxStore(files) }
    }

    @Test fun separateInstancesSerializeConcurrentImportsWithoutLostRecords() {
        val files = temporary.newFolder("files")
        val first = SharedInboxStore(files)
        val second = SharedInboxStore(files)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = listOf(first, second).mapIndexed { index, store ->
                executor.submit {
                    ready.countDown()
                    start.await()
                    store.import(
                        "file-$index.txt",
                        "text/plain",
                        ByteArrayInputStream("$index".toByteArray()),
                    )
                }
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            start.countDown()
            futures.forEach { it.get(2, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(2, SharedInboxStore(files).records().size)
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
