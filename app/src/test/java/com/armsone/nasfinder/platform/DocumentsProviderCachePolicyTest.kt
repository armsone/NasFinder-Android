package com.armsone.nasfinder.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime

class DocumentsProviderCachePolicyTest {
    @Test fun `age and size pruning only remove owned completed files`() = withTempDirectory { root ->
        val now = 10_000L
        val old = completed(root, 'a', 4, 1)
        val newest = completed(root, 'b', 6, now)
        val older = completed(root, 'c', 6, now - 1)
        val unrelated = File(root, "notes.txt").apply { writeText("keep") }

        DocumentsProviderCachePolicy.prune(root, now, preserve = newest, maxAgeMillis = 5_000, maxBytes = 8)

        assertFalse(old.exists())
        assertTrue(newest.exists())
        assertFalse(older.exists())
        assertTrue(unrelated.exists())
    }

    @Test fun `failed download cleanup removes owned temp and sibling partial`() = withTempDirectory { root ->
        val destination = File(root, "download-owned.part").apply { writeText("destination") }
        val sibling = File(root, ".download-owned.part.nasfinder.part").apply { writeText("partial") }

        DocumentsProviderCachePolicy.cleanupFailedDownload(root, destination)

        assertFalse(destination.exists())
        assertFalse(sibling.exists())
    }

    @Test fun `service sibling is removed even if service removed destination first`() = withTempDirectory { root ->
        val destination = File(root, "download-missing.part")
        val sibling = File(root, ".download-missing.part.nasfinder.part").apply { writeText("partial") }

        DocumentsProviderCachePolicy.cleanupFailedDownload(root, destination)

        assertFalse(sibling.exists())
    }

    @Test fun `cleanup rejects arbitrary sibling names and paths outside root`() = withTempDirectory { root ->
        val outside = Files.createTempDirectory("documents-provider-outside").toFile()
        try {
            val external = File(outside, "download-owned.part").apply { writeText("outside") }
            val arbitrary = File(root, ".victim.nasfinder.part").apply { writeText("keep") }
            DocumentsProviderCachePolicy.cleanupFailedDownload(root, external)
            assertTrue(external.exists())
            assertTrue(arbitrary.exists())
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test fun `symlink cache entries are neither pruned nor followed for cleanup`() = withTempDirectory { root ->
        val outside = File(root.parentFile, "outside-${System.nanoTime()}").apply { writeText("secret") }
        try {
            val completedLink = File(root, "d".repeat(64) + ".jpg")
            val tempLink = File(root, "download-link.part")
            runCatching { Files.createSymbolicLink(completedLink.toPath(), outside.toPath()) }
                .getOrElse { return@withTempDirectory }
            Files.createSymbolicLink(tempLink.toPath(), outside.toPath())

            DocumentsProviderCachePolicy.prune(root, nowMillis = Long.MAX_VALUE, maxAgeMillis = 0, maxBytes = 0)
            DocumentsProviderCachePolicy.cleanupFailedDownload(root, tempLink)

            assertTrue(outside.exists())
            assertTrue(Files.isSymbolicLink(completedLink.toPath()))
            assertTrue(Files.isSymbolicLink(tempLink.toPath()))
        } finally {
            outside.delete()
        }
    }

    private fun completed(root: File, character: Char, bytes: Int, modified: Long) =
        File(root, character.toString().repeat(64) + ".bin").apply {
            writeBytes(ByteArray(bytes))
            Files.setLastModifiedTime(toPath(), FileTime.fromMillis(modified))
        }

    private fun withTempDirectory(block: (File) -> Unit) {
        val root = Files.createTempDirectory("documents-provider-cache").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
}
