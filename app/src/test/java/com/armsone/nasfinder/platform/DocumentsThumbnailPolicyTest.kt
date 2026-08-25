package com.armsone.nasfinder.platform

import androidx.compose.ui.geometry.Offset
import com.armsone.nasfinder.model.RemoteFileItem
import com.armsone.nasfinder.ui.MediaPlaybackMode
import com.armsone.nasfinder.ui.MediaSlideshowPolicy
import com.armsone.nasfinder.ui.MediaViewerGestureMode
import com.armsone.nasfinder.ui.MediaViewerGesturePolicy
import com.armsone.nasfinder.ui.CoverFlowLayoutPolicy
import com.armsone.nasfinder.ui.AdaptiveOverflowPolicy
import com.armsone.nasfinder.ui.BuiltInPreviewKind
import com.armsone.nasfinder.ui.BuiltInPreviewPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DocumentsThumbnailPolicyTest {
    @Test fun `built in preview policy recognizes local media consistently`() {
        assertEquals(BuiltInPreviewKind.IMAGE, BuiltInPreviewPolicy.kind("photo.JPG", null))
        assertEquals(BuiltInPreviewKind.VIDEO, BuiltInPreviewPolicy.kind("download", "video/mp4; charset=binary"))
        assertEquals(BuiltInPreviewKind.AUDIO, BuiltInPreviewPolicy.kind("track.flac", null))
        assertEquals(BuiltInPreviewKind.PDF, BuiltInPreviewPolicy.kind("document", "application/pdf"))
        assertEquals(null, BuiltInPreviewPolicy.kind("notes.txt", "text/plain"))
    }

    @Test fun `requested size is square bounded and defensive`() {
        assertEquals(64, DocumentsThumbnailPolicy.requestedPixelSize(0, -10))
        assertEquals(512, DocumentsThumbnailPolicy.requestedPixelSize(120, 512))
        assertEquals(1_024, DocumentsThumbnailPolicy.requestedPixelSize(8_000, 4_000))
    }

    @Test fun `thumbnail advertisement follows item and active service capability`() {
        assertTrue(DocumentsThumbnailPolicy.supports(item("photo.jpg"), supportsRangeStreaming = false))
        assertFalse(DocumentsThumbnailPolicy.supports(item("movie.mp4"), supportsRangeStreaming = false))
        assertTrue(DocumentsThumbnailPolicy.supports(item("movie.mp4"), supportsRangeStreaming = true))
        assertTrue(
            DocumentsThumbnailPolicy.supports(
                item("movie.mp4", thumbnailUrl = "https://nas.test/thumb"),
                supportsRangeStreaming = false,
            )
        )
        assertTrue(DocumentsThumbnailPolicy.supports(item("folder.jpg", directory = true), true))
        assertFalse(DocumentsThumbnailPolicy.supports(item("notes.txt"), true))
        assertFalse(DocumentsThumbnailPolicy.supports(item("empty.mp4").copy(size = 0), true))
    }

    @Test fun `cache key is opaque stable and changes with content identity`() {
        val item = item("photo.jpg").copy(
            path = "/photos/photo.jpg",
            size = 42,
            modifiedAt = Instant.parse("2026-08-14T00:00:00Z"),
        )
        val original = DocumentsThumbnailPolicy.cacheKey("connection", item, 512)
        assertEquals(original, DocumentsThumbnailPolicy.cacheKey("connection", item, 512))
        assertTrue(original.matches(Regex("[0-9a-f]{64}")))
        assertFalse(original.contains("connection") || original.contains("photo"))
        assertNotEquals(original, DocumentsThumbnailPolicy.cacheKey("connection", item.copy(size = 43), 512))
        assertNotEquals(original, DocumentsThumbnailPolicy.cacheKey("connection", item, 1_024))
    }

    @Test fun `gesture mode locks axis based on touch slop and direction`() {
        assertEquals(36f, MediaViewerGesturePolicy.logicalDistancePx(12f, 3f), 0.001f)
        assertEquals(
            MediaViewerGestureMode.NONE,
            MediaViewerGesturePolicy.determineGestureMode(10f, 10f, touchSlop = 18f),
        )
        assertEquals(
            MediaViewerGestureMode.SEEK,
            MediaViewerGesturePolicy.determineGestureMode(25f, 10f, touchSlop = 18f),
        )
        assertEquals(
            MediaViewerGestureMode.SEEK,
            MediaViewerGesturePolicy.determineGestureMode(-25f, 10f, touchSlop = 18f),
        )
        assertEquals(
            MediaViewerGestureMode.VOLUME,
            MediaViewerGesturePolicy.determineGestureMode(5f, -25f, touchSlop = 18f),
        )
        assertEquals(
            MediaViewerGestureMode.DISMISS,
            MediaViewerGesturePolicy.determineGestureMode(5f, 25f, touchSlop = 18f),
        )
    }

    @Test fun `small-delta volume calculation computes smoothly and clamps within range`() {
        assertEquals(0, MediaViewerGesturePolicy.computeVolume(0, 0, -100f, 1000f))
        assertEquals(5, MediaViewerGesturePolicy.computeVolume(5, 10, 0f, 1000f))
        assertEquals(6, MediaViewerGesturePolicy.computeVolume(5, 10, -100f, 1000f))
        assertEquals(4, MediaViewerGesturePolicy.computeVolume(5, 10, 100f, 1000f))
        assertEquals(10, MediaViewerGesturePolicy.computeVolume(5, 10, -10000f, 1000f))
        assertEquals(0, MediaViewerGesturePolicy.computeVolume(5, 10, 10000f, 1000f))
        assertEquals(60, MediaViewerGesturePolicy.computeVolumePercent(5, 10, -100f, 1000f))
        assertEquals(0, MediaViewerGesturePolicy.computeVolumePercent(0, 0, -100f, 1000f))
    }

    @Test fun `seek position and dismiss split compute accurately`() {
        assertEquals(0, MediaViewerGesturePolicy.computeSeekPosition(0, 0, 100f, 1000f))
        assertEquals(6000, MediaViewerGesturePolicy.computeSeekPosition(5000, 10000, 100f, 1000f))
        assertEquals(10000, MediaViewerGesturePolicy.computeSeekPosition(5000, 10000, 1000f, 1000f))
        assertEquals(0, MediaViewerGesturePolicy.computeSeekPosition(5000, 10000, -1000f, 1000f))

        assertEquals(1.0f, MediaViewerGesturePolicy.computeDismissProgress(0f, 160f), 0.001f)
        assertEquals(1.0f, MediaViewerGesturePolicy.computeDismissProgress(-50f, 160f), 0.001f)
        assertEquals(0.6f, MediaViewerGesturePolicy.computeDismissProgress(160f, 160f), 0.001f)
        assertEquals(0.15f, MediaViewerGesturePolicy.computeDismissProgress(1000f, 160f), 0.001f)

        assertFalse(MediaViewerGesturePolicy.shouldDismiss(159f, 160f))
        assertTrue(MediaViewerGesturePolicy.shouldDismiss(160f, 160f))
        assertTrue(MediaViewerGesturePolicy.shouldDismiss(200f, 160f))
    }

    @Test fun `media navigation triggers at base scale and translation clamps to zoomed bounds`() {
        assertEquals(-1, MediaViewerGesturePolicy.shouldNavigateImage(70f, 1.0f, 60f))
        assertEquals(1, MediaViewerGesturePolicy.shouldNavigateImage(-70f, 1.0f, 60f))
        assertEquals(0, MediaViewerGesturePolicy.shouldNavigateImage(30f, 1.0f, 60f))
        assertEquals(0, MediaViewerGesturePolicy.shouldNavigateImage(100f, 2.0f, 60f))

        assertEquals(Offset.Zero, MediaViewerGesturePolicy.clampImageTranslation(Offset(100f, 100f), 1.0f, 1000f, 1000f))
        val clamped = MediaViewerGesturePolicy.clampImageTranslation(Offset(1000f, -1000f), 2.0f, 1000f, 800f)
        assertEquals(500f, clamped.x, 0.001f)
        assertEquals(-400f, clamped.y, 0.001f)
        val videoClamped = MediaViewerGesturePolicy.clampImageTranslation(Offset(-900f, 900f), 3.0f, 320f, 180f)
        assertEquals(-320f, videoClamped.x, 0.001f)
        assertEquals(180f, videoClamped.y, 0.001f)
    }

    @Test fun `playback mode cycles and computes next index correctly`() {
        assertEquals(MediaPlaybackMode.SHUFFLE, MediaPlaybackMode.ALL_REPEAT.next())
        assertEquals(MediaPlaybackMode.ONE_REPEAT, MediaPlaybackMode.SHUFFLE.next())
        assertEquals(MediaPlaybackMode.ALL_REPEAT, MediaPlaybackMode.ONE_REPEAT.next())

        assertEquals(0, MediaSlideshowPolicy.nextIndex(0, 1, MediaPlaybackMode.ALL_REPEAT))
        assertEquals(3, MediaSlideshowPolicy.nextIndex(2, 5, MediaPlaybackMode.ALL_REPEAT))
        assertEquals(0, MediaSlideshowPolicy.nextIndex(4, 5, MediaPlaybackMode.ALL_REPEAT))
        assertEquals(2, MediaSlideshowPolicy.nextIndex(2, 5, MediaPlaybackMode.ONE_REPEAT))
        val shuffleTarget = MediaSlideshowPolicy.nextIndex(2, 5, MediaPlaybackMode.SHUFFLE) { 3 }
        assertEquals(3, shuffleTarget)
        val shuffleAvoidSelf = MediaSlideshowPolicy.nextIndex(2, 5, MediaPlaybackMode.SHUFFLE) { 2 }
        assertEquals(3, shuffleAvoidSelf)
    }

    @Test fun `slideshow interval cycles across valid intervals`() {
        assertEquals(2, MediaSlideshowPolicy.nextInterval(1))
        assertEquals(3, MediaSlideshowPolicy.nextInterval(2))
        assertEquals(5, MediaSlideshowPolicy.nextInterval(3))
        assertEquals(10, MediaSlideshowPolicy.nextInterval(5))
        assertEquals(15, MediaSlideshowPolicy.nextInterval(10))
        assertEquals(30, MediaSlideshowPolicy.nextInterval(15))
        assertEquals(1, MediaSlideshowPolicy.nextInterval(30))
        assertEquals(1, MediaSlideshowPolicy.nextInterval(999))
    }

    @Test fun `cover flow keeps an adaptive centered square with reflection room`() {
        val center = CoverFlowLayoutPolicy.centralCardSide(
            containerWidth = 1280f,
            containerHeight = 720f,
            topContentInset = 24f,
            bottomContentInset = 24f,
        )
        val base = CoverFlowLayoutPolicy.baseCardSide(1280f, center)
        val firstSide = CoverFlowLayoutPolicy.renderedCardSide(1f, base, center)
        val farSide = CoverFlowLayoutPolicy.renderedCardSide(5f, base, center)
        assertEquals(294.4f, base, 0.01f)
        assertEquals(550.16f, center, 0.01f)
        assertTrue(firstSide < center * 0.65f)
        assertTrue(farSide < firstSide)
        assertTrue(CoverFlowLayoutPolicy.horizontalPush(1f, base, center) > 44f)
        assertEquals(1f, CoverFlowLayoutPolicy.opacity(0f), 0.001f)
        assertTrue(CoverFlowLayoutPolicy.opacity(5f) < CoverFlowLayoutPolicy.opacity(1f))

        val unfoldedCenter = CoverFlowLayoutPolicy.centralCardSide(
            containerWidth = 1280f,
            containerHeight = 945f,
            topContentInset = 24f,
            bottomContentInset = 24f,
        )
        assertEquals(614.4f, unfoldedCenter, 0.01f)
        assertEquals(2.5f, CoverFlowLayoutPolicy.continuousPosition(2, 40f, 60f, 40f), 0.001f)
    }

    @Test fun `automatic overflow follows content bounds and never interrupts selection`() {
        assertTrue(AdaptiveOverflowPolicy.shouldUseAutomaticOverflow(1280f, 945f, 8, false))
        assertTrue(AdaptiveOverflowPolicy.shouldUseAutomaticOverflow(800f, 360f, 8, false))
        assertFalse(AdaptiveOverflowPolicy.shouldUseAutomaticOverflow(1280f, 945f, 8, true))
        assertFalse(AdaptiveOverflowPolicy.shouldUseAutomaticOverflow(1280f, 945f, 0, false))
        assertFalse(AdaptiveOverflowPolicy.shouldUseAutomaticOverflow(560f, 320f, 8, false))
        assertFalse(AdaptiveOverflowPolicy.shouldUseAutomaticOverflow(800f, 700f, 8, false))
    }

    private fun item(name: String, directory: Boolean = false, thumbnailUrl: String? = null) = RemoteFileItem(
        id = name,
        name = name,
        path = "/$name",
        isDirectory = directory,
        size = 1,
        thumbnailUrl = thumbnailUrl,
    )
}
