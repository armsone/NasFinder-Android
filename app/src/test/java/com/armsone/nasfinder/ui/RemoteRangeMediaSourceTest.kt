package com.armsone.nasfinder.ui

import com.armsone.nasfinder.model.ConnectionKind
import com.armsone.nasfinder.model.RemoteConnection
import com.armsone.nasfinder.model.RemoteFileItem
import com.armsone.nasfinder.network.SftpFileService
import com.armsone.nasfinder.network.SmbFileService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteRangeMediaSourceTest {
    private val item = RemoteFileItem("media", "clip.mp4", "/media/clip.mp4", false, size = 20L * 1024 * 1024)

    @Test fun `data source reads ahead in the same eight MiB chunks as iOS playback`() {
        val calls = mutableListOf<Pair<Long, Int>>()
        val source = RemoteRangeReadSession(item) { _, offset, length ->
            calls += offset to length
            ByteArray(length) { ((offset + it) % 251).toByte() }
        }
        assertEquals(12L * 1024 * 1024, source.open(512, 12L * 1024 * 1024))
        val first = ByteArray(32 * 1024)
        assertEquals(first.size, source.read(first, 0, first.size))
        assertEquals(512L to RemoteRangeDataSource.PLAYBACK_CHUNK_BYTES, calls.single())
        val second = ByteArray(32 * 1024)
        assertEquals(second.size, source.read(second, 0, second.size))
        assertEquals(1, calls.size)
        source.close()
    }

    @Test fun `closing data source cancels an in flight range read`() {
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        var cancelled = false
        val source = RemoteRangeReadSession(item) { _, _, _ ->
            entered.countDown()
            try {
                delay(Long.MAX_VALUE)
                byteArrayOf(1)
            } finally {
                cancelled = true
            }
        }
        source.open(0, -1)
        Thread {
            try {
                source.read(ByteArray(8), 0, 8)
            } catch (_: java.io.InterruptedIOException) {
                // Expected: close owns cancellation of the active suspend range read.
            } finally {
                finished.countDown()
            }
        }.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        source.close()
        assertTrue(finished.await(2, TimeUnit.SECONDS))
        assertTrue(cancelled)
    }

    @Test fun `subtitle matching is exact same folder and prefers SRT`() {
        val vtt = remote("clip.vtt", "/media/clip.vtt")
        val srt = remote("clip.srt", "/media/clip.srt")
        assertEquals(srt, RemoteSubtitlePolicy.match(item, listOf(vtt, remote("clip.en.srt", "/media/clip.en.srt"), srt)))
        assertEquals(null, RemoteSubtitlePolicy.match(item, listOf(remote("clip.srt", "/other/clip.srt"), remote("clip-copy.srt", "/media/clip-copy.srt"))))
    }

    @Test fun `route policy streams supported containers and forces legacy external player`() {
        assertEquals(RemoteMediaRoute.RANGE_MEDIA3, RemoteMediaRoutePolicy.route("movie.mkv", true))
        assertEquals(RemoteMediaRoute.DOWNLOAD_MEDIA3, RemoteMediaRoutePolicy.route("movie.mkv", false))
        listOf("avi", "asf", "wmv").forEach { extension ->
            assertEquals(RemoteMediaRoute.EXTERNAL_PLAYER, RemoteMediaRoutePolicy.route("movie.$extension", true))
        }
        assertFalse(BuiltInPreviewPolicy.kind("movie.avi", "video/x-msvideo") == BuiltInPreviewKind.VIDEO)
        assertTrue(BuiltInPreviewPolicy.kind("movie.mp4", "video/mp4") == BuiltInPreviewKind.VIDEO)
    }

    @Test fun `media viewer and cover flow contracts ensure auto play, buffering indicator, retry, and reflection fade`() {
        val app = sourceFile("app/src/main/java/com/armsone/nasfinder/ui/NasFinderApp.kt").readText()
        assertTrue(app.contains("playWhenReady = true"))
        assertTrue(app.contains("동영상 준비 중…"))
        assertTrue(app.contains("다시 시도"))
        assertTrue(app.contains("CompositingStrategy.Offscreen"))
        assertTrue(app.contains("BlendMode.DstIn"))
        assertTrue(app.contains("if (selected || index == selectedIndex)"))
        assertTrue(app.contains("var initialPositionRestored = false"))
        assertTrue(app.contains("if (!initialPositionRestored)"))
        assertTrue(app.contains("if (position > 0)"))
    }

    @Test fun `SFTP media routes through download media3 while range streaming backends stream directly`() {
        val sftpConnection = RemoteConnection(name = "SFTP", kind = ConnectionKind.SFTP, host = "sftp.test", username = "user")
        val sftpService = SftpFileService(sftpConnection, "password")
        assertFalse(sftpService.supportsRangeStreaming)
        assertEquals(RemoteMediaRoute.DOWNLOAD_MEDIA3, RemoteMediaRoutePolicy.route("movie.mp4", sftpService.supportsRangeStreaming))

        val smbConnection = RemoteConnection(name = "SMB", kind = ConnectionKind.SMB, host = "smb.test", username = "user")
        val smbService = SmbFileService(smbConnection, "password")
        assertTrue(smbService.supportsRangeStreaming)
        assertEquals(RemoteMediaRoute.RANGE_MEDIA3, RemoteMediaRoutePolicy.route("movie.mp4", smbService.supportsRangeStreaming))
    }

    @Test fun `video preview contract enforces edge to edge rendering behind transparent overlay chrome`() {
        val app = sourceFile("app/src/main/java/com/armsone/nasfinder/ui/NasFinderApp.kt").readText()
        assertTrue(app.contains("if (edgeToEdgePreview) Color.Transparent else Color.Black"))
        assertTrue(app.contains("else if (!edgeToEdgePreview)"))
        assertTrue(app.contains("if (edgeToEdgePreview) Modifier else Modifier.padding(padding)"))
    }

    @Test fun `image viewer stays the same across device orientation and normalizes EXIF`() {
        val app = sourceFile("app/src/main/java/com/armsone/nasfinder/ui/NasFinderApp.kt").readText()
        val model = sourceFile("app/src/main/java/com/armsone/nasfinder/ui/NasFinderViewModel.kt").readText()
        assertFalse(app.contains("LandscapeCoverFlowPreview("))
        assertTrue(model.contains("ExifInterface.TAG_ORIENTATION"))
        assertTrue(model.contains("exifPixelTransform(orientation)"))
        assertTrue(app.contains("preview.kind == BuiltInPreviewKind.IMAGE || preview.kind == BuiltInPreviewKind.VIDEO"))
        assertTrue(app.contains("if (edgeToEdgePreview) Modifier else Modifier.padding(padding)"))
        assertTrue(app.contains("PreviewLoadProgressStrip(download"))
        assertTrue(app.contains(".height(2.dp)"))
        assertTrue(app.contains("trackColor = Color.Black"))
    }

    private fun remote(name: String, path: String) = RemoteFileItem(name, name, path, false, size = 256)

    private fun sourceFile(relativePath: String): java.io.File {
        val working = java.io.File(System.getProperty("user.dir")).canonicalFile
        return generateSequence(working) { it.parentFile }
            .map { java.io.File(it, relativePath) }
            .firstOrNull(java.io.File::isFile)
            ?: error("Cannot locate $relativePath from $working")
    }
}
