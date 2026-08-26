package com.armsone.nasfinder.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPlaybackProgressPolicyTest {
    @Test
    fun `playback fraction requires a valid duration`() {
        assertNull(MediaPlaybackProgressPolicy.fraction(0L, 0L))
        assertNull(MediaPlaybackProgressPolicy.fraction(500L, -1L))
    }

    @Test
    fun `playback fraction follows position and clamps to the media range`() {
        assertEquals(0.25f, MediaPlaybackProgressPolicy.fraction(2_500L, 10_000L)!!, 0.0001f)
        assertEquals(0f, MediaPlaybackProgressPolicy.fraction(-100L, 10_000L)!!, 0f)
        assertEquals(1f, MediaPlaybackProgressPolicy.fraction(12_000L, 10_000L)!!, 0f)
    }

    @Test
    fun `hidden controls render a one dp thirty five percent black and white bottom progress line`() {
        val app = sourceFile("app/src/main/java/com/armsone/nasfinder/ui/NasFinderApp.kt").readText()
        val start = app.indexOf("} else if (playing) {")
        val end = app.indexOf("\n        }\n    }\n}\n\nprivate fun formatDuration", start)
        val progress = app.substring(start, end)

        assertTrue(progress.contains("MediaPlaybackProgressPolicy.fraction"))
        assertTrue(progress.contains(".align(Alignment.BottomCenter)"))
        assertTrue(progress.contains(".fillMaxWidth()"))
        assertTrue(progress.contains(".height(1.dp)"))
        assertTrue(progress.contains("color = Color.White.copy(alpha = .35f)"))
        assertTrue(progress.contains("trackColor = Color.Black.copy(alpha = .35f)"))
        assertTrue(progress.contains("재생 위치"))
        assertFalse(progress.contains("navigationBarsPadding()"))
    }

    private fun sourceFile(relativePath: String): File {
        val working = File(System.getProperty("user.dir")).canonicalFile
        return generateSequence(working) { it.parentFile }
            .map { File(it, relativePath) }
            .firstOrNull(File::isFile)
            ?: error("Cannot locate $relativePath from $working")
    }
}
