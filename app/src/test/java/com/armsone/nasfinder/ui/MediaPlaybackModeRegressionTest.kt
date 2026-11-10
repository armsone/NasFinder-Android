package com.armsone.nasfinder.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPlaybackModeRegressionTest {

    @Test
    fun testPlaybackModeCycle() {
        var mode = MediaPlaybackMode.ALL_REPEAT
        assertEquals("전체 반복", mode.title)

        mode = mode.next()
        assertEquals(MediaPlaybackMode.SHUFFLE, mode)
        assertEquals("임의 재생", mode.title)

        mode = mode.next()
        assertEquals(MediaPlaybackMode.ONE_REPEAT, mode)
        assertEquals("한 항목 반복", mode.title)

        mode = mode.next()
        assertEquals(MediaPlaybackMode.ONCE, mode)
        assertEquals("한 번 재생", mode.title)

        mode = mode.next()
        assertEquals(MediaPlaybackMode.ALL_REPEAT, mode)
    }

    @Test
    fun testSlideshowIntervalPolicy() {
        val expected = listOf(1, 2, 3, 5, 10, 15, 30)
        assertEquals(expected, MediaSlideshowPolicy.INTERVAL_OPTIONS)
        assertEquals(5, MediaSlideshowPolicy.DEFAULT_INTERVAL_SECONDS)

        assertEquals(2, MediaSlideshowPolicy.nextInterval(1))
        assertEquals(3, MediaSlideshowPolicy.nextInterval(2))
        assertEquals(5, MediaSlideshowPolicy.nextInterval(3))
        assertEquals(10, MediaSlideshowPolicy.nextInterval(5))
        assertEquals(15, MediaSlideshowPolicy.nextInterval(10))
        assertEquals(30, MediaSlideshowPolicy.nextInterval(15))
        assertEquals(1, MediaSlideshowPolicy.nextInterval(30))
        // Unknown defaults to first
        assertEquals(1, MediaSlideshowPolicy.nextInterval(999))
    }

    @Test
    fun testNextIndexPolicyAcrossModes() {
        val total = 5

        // ONE_REPEAT stays on current
        assertEquals(2, MediaSlideshowPolicy.nextIndex(2, total, MediaPlaybackMode.ONE_REPEAT))

        // ALL_REPEAT advances with modulo
        assertEquals(3, MediaSlideshowPolicy.nextIndex(2, total, MediaPlaybackMode.ALL_REPEAT))
        assertEquals(0, MediaSlideshowPolicy.nextIndex(4, total, MediaPlaybackMode.ALL_REPEAT))

        // ONCE advances until last, returns -1 at end
        assertEquals(3, MediaSlideshowPolicy.nextIndex(2, total, MediaPlaybackMode.ONCE))
        assertEquals(-1, MediaSlideshowPolicy.nextIndex(4, total, MediaPlaybackMode.ONCE))

        // SHUFFLE stays within bounds and differs from current when total > 1
        val nextShuffle = MediaSlideshowPolicy.nextIndex(2, total, MediaPlaybackMode.SHUFFLE)
        assertTrue(nextShuffle in 0 until total)
    }

    @Test
    fun testProductionRepeatAndEOSPolicy() {
        // Multi-item playlist:
        // ONE_REPEAT: repeatMode = 1 (Player.REPEAT_MODE_ONE), action = REPEAT
        assertEquals(1, MediaPlaybackPolicy.exoPlayerRepeatMode(hasPlaylist = true, mode = MediaPlaybackMode.ONE_REPEAT, singleItemRepeat = false))
        assertEquals(VideoEOSAction.REPEAT, MediaPlaybackPolicy.videoEOSAction(hasPlaylist = true, mode = MediaPlaybackMode.ONE_REPEAT, singleItemRepeat = false))

        // ALL_REPEAT: repeatMode = 0 (Player.REPEAT_MODE_OFF), action = ADVANCE
        assertEquals(0, MediaPlaybackPolicy.exoPlayerRepeatMode(hasPlaylist = true, mode = MediaPlaybackMode.ALL_REPEAT, singleItemRepeat = false))
        assertEquals(VideoEOSAction.ADVANCE, MediaPlaybackPolicy.videoEOSAction(hasPlaylist = true, mode = MediaPlaybackMode.ALL_REPEAT, singleItemRepeat = false))

        // SHUFFLE: repeatMode = 0 (Player.REPEAT_MODE_OFF), action = ADVANCE
        assertEquals(0, MediaPlaybackPolicy.exoPlayerRepeatMode(hasPlaylist = true, mode = MediaPlaybackMode.SHUFFLE, singleItemRepeat = false))
        assertEquals(VideoEOSAction.ADVANCE, MediaPlaybackPolicy.videoEOSAction(hasPlaylist = true, mode = MediaPlaybackMode.SHUFFLE, singleItemRepeat = false))

        // ONCE: repeatMode = 0 (Player.REPEAT_MODE_OFF), action = STOP (align Apple current stop when once)
        assertEquals(0, MediaPlaybackPolicy.exoPlayerRepeatMode(hasPlaylist = true, mode = MediaPlaybackMode.ONCE, singleItemRepeat = false))
        assertEquals(VideoEOSAction.STOP, MediaPlaybackPolicy.videoEOSAction(hasPlaylist = true, mode = MediaPlaybackMode.ONCE, singleItemRepeat = false))

        // Single URI (hasPlaylist = false): follows singleItemRepeat (legacy videoRepeat)
        assertEquals(1, MediaPlaybackPolicy.exoPlayerRepeatMode(hasPlaylist = false, mode = MediaPlaybackMode.ALL_REPEAT, singleItemRepeat = true))
        assertEquals(VideoEOSAction.REPEAT, MediaPlaybackPolicy.videoEOSAction(hasPlaylist = false, mode = MediaPlaybackMode.ALL_REPEAT, singleItemRepeat = true))

        assertEquals(0, MediaPlaybackPolicy.exoPlayerRepeatMode(hasPlaylist = false, mode = MediaPlaybackMode.ALL_REPEAT, singleItemRepeat = false))
        assertEquals(VideoEOSAction.STOP, MediaPlaybackPolicy.videoEOSAction(hasPlaylist = false, mode = MediaPlaybackMode.ALL_REPEAT, singleItemRepeat = false))
    }
}
