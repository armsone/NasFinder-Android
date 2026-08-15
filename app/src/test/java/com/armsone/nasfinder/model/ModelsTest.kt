package com.armsone.nasfinder.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ModelsTest {
    @Test fun synologyEndpointAndRootAreNormalized() {
        val connection = RemoteConnection(
            name = "Home", kind = ConnectionKind.SYNOLOGY, host = "nas.example.com",
            username = "user", rootPath = "photo", usesTls = true,
        )
        assertEquals("https://nas.example.com:5001", connection.endpoint)
        assertEquals("/photo", connection.normalizedRootPath)
    }

    @Test fun sftpEmptyRootUsesLoginHome() {
        val connection = RemoteConnection(
            name = "SSH", kind = ConnectionKind.SFTP, host = "host",
            username = "user", rootPath = "  ",
        )
        assertEquals(".", connection.normalizedRootPath)
        assertFalse(connection.usesTls)
    }

    @Test fun sortingKeepsFoldersFirstAndHidesDotFiles() {
        val items = listOf(
            RemoteFileItem("2", "z.txt", "/z.txt", false, 2, Instant.ofEpochSecond(2)),
            RemoteFileItem("1", "Beta", "/Beta", true),
            RemoteFileItem("3", ".hidden", "/.hidden", false),
            RemoteFileItem("4", "alpha.txt", "/alpha.txt", false, 1, Instant.ofEpochSecond(1)),
        )
        val result = items.sortedWith(BrowserPreferences())
        assertEquals(listOf("Beta", "alpha.txt", "z.txt"), result.map { it.name })
    }

    @Test fun mediaClassificationIsCaseInsensitive() {
        assertTrue(RemoteFileItem("1", "PHOTO.HEIC", "/PHOTO.HEIC", false).isImage)
        assertTrue(RemoteFileItem("2", "movie.MKV", "/movie.MKV", false).isVideo)
        assertTrue(RemoteFileItem("3", "paper.PDF", "/paper.PDF", false).isPdf)
    }
}
