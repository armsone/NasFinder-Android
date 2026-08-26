package com.armsone.nasfinder.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaBufferingProgressPolicyTest {
    @Test
    fun `unknown duration and zero percentage stays indeterminate`() {
        assertNull(MediaBufferingProgressPolicy.fraction(0, 0L, -1L))
    }

    @Test
    fun `meaningful Media3 percentage becomes determinate`() {
        assertEquals(0.37f, MediaBufferingProgressPolicy.fraction(37, 0L, -1L)!!, 0.0001f)
    }

    @Test
    fun `buffered position is divided by known duration`() {
        assertEquals(0.25f, MediaBufferingProgressPolicy.fraction(0, 2_500L, 10_000L)!!, 0.0001f)
    }

    @Test
    fun `most advanced valid Media3 signal wins and result is bounded`() {
        assertEquals(0.8f, MediaBufferingProgressPolicy.fraction(40, 8_000L, 10_000L)!!, 0.0001f)
        assertEquals(1f, MediaBufferingProgressPolicy.fraction(100, 20_000L, 10_000L)!!, 0f)
    }

    @Test
    fun `invalid Media3 values do not create false download progress`() {
        assertNull(MediaBufferingProgressPolicy.fraction(-1, -1L, 0L))
        assertNull(MediaBufferingProgressPolicy.fraction(101, 0L, 10_000L))
    }

    @Test
    fun `only advancing positive progress remains determinate`() {
        assertTrue(MediaBufferingProgressPolicy.hasAdvanced(null, 0.1f))
        assertTrue(MediaBufferingProgressPolicy.hasAdvanced(0.1f, 0.2f))
        assertFalse(MediaBufferingProgressPolicy.hasAdvanced(0.2f, 0.2f))
    }

    @Test
    fun `buffering overlay uses accessible horizontal progress and no dot spinner`() {
        val app = sourceFile("app/src/main/java/com/armsone/nasfinder/ui/NasFinderApp.kt").readText()
        val start = app.indexOf("if (isBuffering && playbackError == null)")
        val end = app.indexOf("playbackError?.let", start)
        val overlay = app.substring(start, end)
        assertTrue(overlay.contains("LinearProgressIndicator("))
        assertTrue(overlay.contains("동영상 준비 진행률"))
        assertFalse(overlay.contains("CircularProgressIndicator("))
    }

    private fun sourceFile(relativePath: String): File {
        val working = File(System.getProperty("user.dir")).canonicalFile
        return generateSequence(working) { it.parentFile }
            .map { File(it, relativePath) }
            .firstOrNull(File::isFile)
            ?: error("Cannot locate $relativePath from $working")
    }
}
