package com.armsone.nasfinder.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferContractsTest {
    @Test fun sftpRejectsTraversalAndRootBoundaryConfusion() {
        assertEquals("/vault/folder", TransferContracts.normalizeSftpPath("/vault/./folder", "/vault"))
        assertFails { TransferContracts.normalizeSftpPath("/vault/../secret", "/vault") }
        assertFails { TransferContracts.normalizeSftpPath("/vault-copy/file", "/vault") }
        assertFails { TransferContracts.normalizeSftpPath("relative/file", "/vault") }
        assertFails { TransferContracts.normalizeSftpPath("/vault/a\r\nfile", "/vault") }
        assertFails { TransferContracts.normalizeSftpPath("/vault/a\u0000file", "/vault") }
    }

    @Test fun smbCanonicalizationNeverEscapesConfiguredShareRoot() {
        assertEquals("/media/photos/today", TransferContracts.normalizeSmbPath("/media/photos/./today", "/media/photos"))
        assertFails { TransferContracts.normalizeSmbPath("/media/photos/../../private", "/media/photos") }
        assertFails { TransferContracts.normalizeSmbPath("/media/photos-copy", "/media/photos") }
        assertFails { TransferContracts.normalizeSmbPath("/media\\photos", "/media") }
    }

    @Test fun remoteNamesAndFtpArgumentsRejectCommandSeparators() {
        listOf("../file", "a/b", "a\\b", "a\rDELE x", "a\n", "a\u0000b").forEach {
            assertFails { TransferContracts.requireSafeName(it) }
        }
        listOf("path\r\nDELE secret", "path\u0000tail", "path\n").forEach {
            assertFails { TransferContracts.requireSafeFtpArgument(it) }
        }
        assertEquals("ordinary file.txt", TransferContracts.requireSafeFtpArgument("ordinary file.txt"))
    }

    @Test fun keepBothPreservesExtensionAndDoesNotStackExistingSuffix() {
        assertEquals("photo.jpg", TransferContracts.keepBothName("photo.jpg", emptyList()))
        assertEquals("photo (2).jpg", TransferContracts.keepBothName("photo.jpg", listOf("photo.jpg", "photo (1).jpg")))
        assertEquals("photo (2).jpg", TransferContracts.keepBothName("photo (1).jpg", listOf("photo (1).jpg")))
        assertEquals(".env (1)", TransferContracts.keepBothName(".env", listOf(".env")))
    }

    @Test fun hiddenFileContractOnlyHidesLeadingDot() {
        assertFalse(TransferContracts.isVisibleName(".secret"))
        assertFalse(TransferContracts.isVisibleName("..partial"))
        assertTrue(TransferContracts.isVisibleName("photo.hidden.jpg"))
    }

    @Test fun webHardPathAndEncodedUrlCannotTraverse() {
        assertEquals("/folder/file.txt", TransferContracts.normalizeWebHardPath("//folder/file.txt"))
        assertFails { TransferContracts.normalizeWebHardPath("/folder/../secret") }
        assertFails { TransferContracts.normalizeWebHardPath("/folder\u0000/file") }
        assertFails { TransferContracts.normalizeWebHardPath("/", allowingRoot = false) }

        val target = TransferContracts.parseWebHardTarget("/api/file?path=%2Ffolder%2Fa%20b.txt&password=p%2Bq")
        assertEquals("/api/file", target.route)
        assertEquals("/folder/a b.txt", target.query["path"])
        assertEquals("p+q", target.query["password"])
        assertEquals(
            "first",
            TransferContracts.parseWebHardTarget("/api/list?password=first&password=second").query["password"],
        )
        assertFails {
            val traversal = TransferContracts.parseWebHardTarget("/api/file?path=%2Ffolder%2F..%2Fsecret")
            TransferContracts.normalizeWebHardPath(traversal.query.getValue("path"))
        }
        assertFails { TransferContracts.parseWebHardTarget("http://attacker.test/api/file") }
        assertFails { TransferContracts.parseWebHardTarget("/api/file\r\nX-Evil: yes") }
    }

    @Test fun downloadCacheKeyIsStableAndSeparatesVersions() {
        val first = DownloadCacheContract.key("connection", "/photo.jpg", 12, 1_000)
        assertEquals(64, first.length)
        assertTrue(first.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(first, DownloadCacheContract.key("connection", "/photo.jpg", 12, 1_000))
        assertNotEquals(first, DownloadCacheContract.key("connection", "/photo.jpg", 13, 1_000))
        assertNotEquals(first, DownloadCacheContract.key("connection", "/photo.jpg", 12, 1_001))
        assertNotEquals(first, DownloadCacheContract.key("other", "/photo.jpg", 12, 1_000))
    }

    @Test fun downloadCacheFilenameRemovesTraversalAndHonorsUtf8Limit() {
        assertEquals("passwd", DownloadCacheContract.safeFilename("../../etc/passwd"))
        assertEquals("bad_name_.txt", DownloadCacheContract.safeFilename("bad:name?.txt"))
        assertEquals("remote-file", DownloadCacheContract.safeFilename("..."))
        val longName = DownloadCacheContract.safeFilename("가".repeat(100) + ".jpeg")
        assertTrue(longName.endsWith(".jpeg"))
        assertTrue(longName.toByteArray(Charsets.UTF_8).size <= 180)
    }

    private fun assertFails(block: () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue("Expected IllegalArgumentException", failed)
    }
}
