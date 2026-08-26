package com.armsone.nasfinder.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaViewerVolumeGranularityTest {
    private val maxVolume = 15 // typical STREAM_MUSIC index range on phones and tablets
    private val fullRangePx = MediaViewerGesturePolicy.logicalDistancePx(MediaViewerGesturePolicy.VOLUME_FULL_RANGE_DRAG_DP, 1f)

    @Test fun `full-range drag sweeps the whole volume range in both directions`() {
        assertEquals(1f, MediaViewerGesturePolicy.computeVolumeFraction(0, maxVolume, -fullRangePx, fullRangePx), 0.0001f)
        assertEquals(0f, MediaViewerGesturePolicy.computeVolumeFraction(maxVolume, maxVolume, fullRangePx, fullRangePx), 0.0001f)
        assertEquals(1f, MediaViewerGesturePolicy.computeVolumeFraction(7, maxVolume, -10_000f, fullRangePx), 0.0001f)
        assertEquals(0f, MediaViewerGesturePolicy.computeVolumeFraction(7, maxVolume, 10_000f, fullRangePx), 0.0001f)
        assertEquals(0f, MediaViewerGesturePolicy.computeVolumeFraction(0, 0, -100f, fullRangePx), 0.0001f)
    }

    @Test fun `continuous drag produces a percent readout far finer than one native step`() {
        val percents = linkedSetOf<Int>()
        val indices = linkedSetOf<Int>()
        var dragY = 0f
        while (dragY >= -fullRangePx) {
            val fraction = MediaViewerGesturePolicy.computeVolumeFraction(0, maxVolume, dragY, fullRangePx)
            percents += MediaViewerGesturePolicy.volumePercent(fraction)
            indices += MediaViewerGesturePolicy.nativeVolumeIndex(fraction, maxVolume)
            dragY -= 1f
        }
        assertTrue("readout should move in roughly 1% steps, saw ${percents.size}", percents.size >= 95)
        assertEquals((0..maxVolume).toList(), indices.toList())
    }

    @Test fun `native index never skips a step and stays stable at the starting volume`() {
        for (start in 0..maxVolume) {
            val unchanged = MediaViewerGesturePolicy.computeVolumeFraction(start, maxVolume, 0f, fullRangePx)
            assertEquals(start, MediaViewerGesturePolicy.nativeVolumeIndex(unchanged, maxVolume))
        }
        var previous = MediaViewerGesturePolicy.nativeVolumeIndex(0f, maxVolume)
        var dragY = 0f
        while (dragY >= -fullRangePx) {
            val fraction = MediaViewerGesturePolicy.computeVolumeFraction(0, maxVolume, dragY, fullRangePx)
            val index = MediaViewerGesturePolicy.nativeVolumeIndex(fraction, maxVolume)
            assertTrue("index jumped from $previous to $index", index - previous in 0..1)
            previous = index
            dragY -= 1f
        }
        // Between two neighbouring native steps the readout still changes several times.
        val between = (0..24).map { step ->
            val dragBetween = -(fullRangePx / maxVolume) * (7f + step / 24f)
            MediaViewerGesturePolicy.volumePercent(MediaViewerGesturePolicy.computeVolumeFraction(0, maxVolume, dragBetween, fullRangePx))
        }.toSet()
        assertTrue("expected several readouts between step 7 and 8, saw $between", between.size >= 4)
    }

    @Test fun `system step and player gain produce the requested audible fraction without seven percent jumps`() {
        var previous = 0f
        for (percent in 0..100) {
            val requested = percent / 100f
            val setting = MediaViewerGesturePolicy.fineVolumeSetting(requested, maxVolume)
            val effective = setting.effectiveFraction(maxVolume)
            assertEquals(requested, effective, 0.0001f)
            assertTrue(setting.streamIndex in 0..maxVolume)
            assertTrue(setting.playerGain in 0f..1f)
            assertTrue("audible fraction moved backwards", effective >= previous)
            previous = effective
        }
    }

    @Test fun `player volume gesture applies native steps only on change while other gestures keep their axes`() {
        val app = sourceFile("app/src/main/java/com/armsone/nasfinder/ui/NasFinderApp.kt").readText()
        val gesture = app.substring(app.indexOf("MediaViewerGestureMode.VOLUME -> {"), app.indexOf("MediaViewerGestureMode.DISMISS -> {", app.indexOf("MediaViewerGestureMode.VOLUME -> {")))
        assertTrue(gesture.contains("MediaViewerGesturePolicy.computeVolumeFraction("))
        assertTrue(gesture.contains("volumeFullRangePx"))
        assertTrue(gesture.contains("MediaViewerGesturePolicy.fineVolumeSetting(fraction, maxVolume)"))
        assertTrue(gesture.contains("if (setting.streamIndex != appliedVolume)"))
        assertTrue(gesture.contains("audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, setting.streamIndex, 0)"))
        assertTrue(gesture.contains("player?.volume = setting.playerGain"))
        assertTrue(gesture.contains("MediaViewerGesturePolicy.volumePercent(fraction)"))
        assertFalse(gesture.contains("containerSize.height"))
        assertTrue(app.contains("MediaViewerGesturePolicy.VOLUME_FULL_RANGE_DRAG_DP"))
        // Axis lock, horizontal seek, downward dismiss and accessibility click path remain in place.
        assertTrue(app.contains("MediaViewerGesturePolicy.determineGestureMode("))
        assertTrue(app.contains("MediaViewerGesturePolicy.computeSeekPosition("))
        assertTrue(app.contains("MediaViewerGesturePolicy.shouldDismiss(dismissTranslationY, dismissThreshold)"))
        assertTrue(app.contains("Modifier.clickable(onClick = onInteraction)"))
        assertEquals(MediaViewerGestureMode.VOLUME, MediaViewerGesturePolicy.determineGestureMode(3f, -40f, 18f))
        assertEquals(MediaViewerGestureMode.DISMISS, MediaViewerGesturePolicy.determineGestureMode(3f, 40f, 18f))
        assertEquals(MediaViewerGestureMode.SEEK, MediaViewerGesturePolicy.determineGestureMode(40f, -3f, 18f))
    }

    private fun sourceFile(relativePath: String): File {
        val working = File(System.getProperty("user.dir")).canonicalFile
        return generateSequence(working) { it.parentFile }
            .map { File(it, relativePath) }
            .firstOrNull(File::isFile)
            ?: error("Cannot locate $relativePath from $working")
    }
}
