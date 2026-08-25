package com.armsone.nasfinder.data

import com.armsone.nasfinder.model.RemoteFileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuperThumbnailPolicyTest {
    @Test
    fun standardRunRequiresUnmeteredNetworkExternalPowerAndSafeBattery() {
        assertEquals(
            SuperThumbnailRuntimeConstraints(
                requiresUnmeteredNetwork = true,
                requiresExternalPower = true,
                requiresBatteryNotLow = true,
            ),
            SuperThumbnailRuntimeConstraints.forRun(allowsConstrainedRun = false),
        )
    }

    @Test
    fun constrainedRunRelaxesOnlyNetworkAndExternalPower() {
        assertEquals(
            SuperThumbnailRuntimeConstraints(
                requiresUnmeteredNetwork = false,
                requiresExternalPower = false,
                requiresBatteryNotLow = true,
            ),
            SuperThumbnailRuntimeConstraints.forRun(allowsConstrainedRun = true),
        )
    }

    @Test fun traversalIsBreadthFirstAndRootBounded() {
        val traversal = SuperThumbnailTraversal("/photos", SuperThumbnailBudget(maxDepth = 3))
        assertEquals(SuperThumbnailTraversalNode("/photos", 0), traversal.nextDirectory())
        assertTrue(traversal.enqueueDirectory("/photos/2025", 0))
        assertTrue(traversal.enqueueDirectory("/photos/2026", 0))
        assertFalse(traversal.enqueueDirectory("/photos/2025", 0))
        assertEquals("/photos/2025", traversal.nextDirectory()?.path)
        assertEquals("/photos/2026", traversal.nextDirectory()?.path)
        assertNull(traversal.nextDirectory())
        assertFalse(traversal.enqueueDirectory("/photos/too-deep", 3))
        assertFails { traversal.requireInsideRoot("/photos-private/secret") }
        assertFails { traversal.requireInsideRoot("/photos/../secret") }
    }

    @Test fun relativeSftpRootCannotBecomeAbsoluteOrEscape() {
        val traversal = SuperThumbnailTraversal(".", SuperThumbnailBudget())
        assertEquals("folder/file.jpg", traversal.requireInsideRoot("./folder/file.jpg"))
        assertFails { traversal.requireInsideRoot("/absolute/file.jpg") }
        assertFails { traversal.requireInsideRoot("folder\r\nfile.jpg") }
    }

    @Test fun depthItemAndByteBudgetsStopAtExactBoundary() {
        val budget = SuperThumbnailBudget(maxItems = 2, maxDepth = 1, maxEstimatedBytes = 10)
        assertTrue(budget.acceptsItem(0))
        assertTrue(budget.acceptsItem(1))
        assertFalse(budget.acceptsItem(2))
        assertTrue(budget.acceptsDirectory(0))
        assertFalse(budget.acceptsDirectory(1))
        assertTrue(budget.acceptsBytes(4, 6))
        assertFalse(budget.acceptsBytes(4, 7))
    }

    @Test fun uniqueWorkNameIsStableAndDoesNotExposeConnectionId() {
        val connectionId = "private-connection-id"
        val first = SuperThumbnailWorkController.uniqueName(connectionId)
        assertEquals(first, SuperThumbnailWorkController.uniqueName(connectionId))
        assertFalse(first.contains(connectionId))
        assertTrue(first.startsWith("super-thumbnail-"))
    }

    @Test fun rangeCapableVideoReachesRepositoryWithBoundedEstimate() {
        val video = item("movie.mp4", size = 2L * 1024L * 1024L * 1024L)
        assertNull(SuperThumbnailEligibility.estimatedBytes(video, supportsRangeStreaming = false))
        assertEquals(
            8L * 1024L * 1024L,
            SuperThumbnailEligibility.estimatedBytes(video, supportsRangeStreaming = true),
        )
    }

    @Test fun videoSuperThumbnailRejectsServerThumbnailFallbackWithoutRangeStreaming() {
        val serverVideo = item("movie.mp4", size = 10_000_000L, thumbnailUrl = "https://nas.test/thumb")
        assertNull(SuperThumbnailEligibility.estimatedBytes(serverVideo, supportsRangeStreaming = false))
        assertEquals(
            8L * 1024L * 1024L,
            SuperThumbnailEligibility.estimatedBytes(serverVideo, supportsRangeStreaming = true),
        )
        val zeroSizeVideo = item("zero.mp4", size = 0, thumbnailUrl = "https://nas.test/thumb")
        assertNull(SuperThumbnailEligibility.estimatedBytes(zeroSizeVideo, supportsRangeStreaming = true))
        assertNull(SuperThumbnailEligibility.estimatedBytes(item("notes.txt", 100), true))
    }

    @Test fun videoSuperThumbnailCalculatesExactThreeThirteenthsAndSixThirteenthsFractions() {
        // 13 seconds = 13_000 ms = 13_000_000 us
        val durationMs = 13_000L
        val durationUs = 13_000_000L

        assertEquals(3_000L, SuperThumbnailVideoPolicy.primaryTimeMs(durationMs))
        assertEquals(3_000_000L, SuperThumbnailVideoPolicy.primaryTimeUs(durationUs))
        assertEquals(6_000L, SuperThumbnailVideoPolicy.retryTimeMs(durationMs))
        assertEquals(6_000_000L, SuperThumbnailVideoPolicy.retryTimeUs(durationUs))

        // 65 seconds
        val longerMs = 65_000L
        assertEquals(15_000L, SuperThumbnailVideoPolicy.primaryTimeMs(longerMs))
        assertEquals(30_000L, SuperThumbnailVideoPolicy.retryTimeMs(longerMs))

        // 0 duration
        assertEquals(0L, SuperThumbnailVideoPolicy.primaryTimeMs(0L))
        assertEquals(0L, SuperThumbnailVideoPolicy.retryTimeMs(0L))

        // Retry is exactly twice the primary timestamp
        assertEquals(
            SuperThumbnailVideoPolicy.primaryTimeUs(durationUs) * 2,
            SuperThumbnailVideoPolicy.retryTimeUs(durationUs),
        )
    }

    @Test fun blackPixelPolicyIdentifiesDarkLuminanceAndRejectsBrighterColors() {
        // Pure black and the shared 5%-luminance boundary (13)
        assertTrue(SuperThumbnailVideoPolicy.isBlackPixel(0, 0, 0))
        assertTrue(SuperThumbnailVideoPolicy.isBlackPixel(13, 13, 13))
        assertTrue(SuperThumbnailVideoPolicy.isBlackPixel(10, 13, 8))

        // Grayscale luminance above the boundary is not black
        assertFalse(SuperThumbnailVideoPolicy.isBlackPixel(14, 14, 14))
        assertFalse(SuperThumbnailVideoPolicy.isBlackPixel(255, 255, 255))
        assertFalse(SuperThumbnailVideoPolicy.isBlackPixel(200, 10, 10))

        // Packed ARGB Int evaluation
        assertTrue(SuperThumbnailVideoPolicy.isBlackPixel(0xFF000000.toInt()))
        assertTrue(SuperThumbnailVideoPolicy.isBlackPixel(0xFF0D0D0D.toInt()))
        assertFalse(SuperThumbnailVideoPolicy.isBlackPixel(0xFF0E0E0E.toInt()))
        assertFalse(SuperThumbnailVideoPolicy.isBlackPixel(0xFFFFFFFF.toInt()))
    }

    @Test fun inclusiveFiftyPercentBlackThresholdPolicyEvaluatesExactBoundaries() {
        // Exactly 50% (inclusive 50% must return true)
        assertTrue(SuperThumbnailVideoPolicy.isBlackFrame(50, 100))
        assertTrue(SuperThumbnailVideoPolicy.isBlackFrame(72, 144))
        assertTrue(SuperThumbnailVideoPolicy.isBlackFrame(128, 256))
        assertTrue(SuperThumbnailVideoPolicy.isBlackFrame(1, 2))

        // Above 50%
        assertTrue(SuperThumbnailVideoPolicy.isBlackFrame(51, 100))
        assertTrue(SuperThumbnailVideoPolicy.isBlackFrame(73, 144))
        assertTrue(SuperThumbnailVideoPolicy.isBlackFrame(129, 256))
        assertTrue(SuperThumbnailVideoPolicy.isBlackFrame(100, 100))

        // Below 50%
        assertFalse(SuperThumbnailVideoPolicy.isBlackFrame(49, 100))
        assertFalse(SuperThumbnailVideoPolicy.isBlackFrame(71, 144))
        assertFalse(SuperThumbnailVideoPolicy.isBlackFrame(127, 256))
        assertFalse(SuperThumbnailVideoPolicy.isBlackFrame(0, 100))

        // Empty samples
        assertFalse(SuperThumbnailVideoPolicy.isBlackFrame(0, 0))
    }

    @Test fun frameChoiceDecisionMatrixSelectsCorrectFrameAccordingToContract() {
        // 1. Primary is NOT black (< 50%) -> Keep primary, retry not needed
        assertEquals(
            SuperThumbnailVideoFrameChoice.PRIMARY,
            SuperThumbnailVideoPolicy.chooseFrame(
                primaryIsBlack = false,
                retryExtracted = false,
                retryIsBlack = false,
            ),
        )

        // 2. Primary IS black (>= 50%), retry extraction fails -> Keep primary
        assertEquals(
            SuperThumbnailVideoFrameChoice.PRIMARY,
            SuperThumbnailVideoPolicy.chooseFrame(
                primaryIsBlack = true,
                retryExtracted = false,
                retryIsBlack = false,
            ),
        )

        // 3. Primary IS black (>= 50%), retry extracted and retry is ALSO black (>= 50%) -> Keep primary
        assertEquals(
            SuperThumbnailVideoFrameChoice.PRIMARY,
            SuperThumbnailVideoPolicy.chooseFrame(
                primaryIsBlack = true,
                retryExtracted = true,
                retryIsBlack = true,
            ),
        )

        // 4. Primary IS black (>= 50%), retry extracted and retry is NOT black (< 50%) -> Use retry!
        assertEquals(
            SuperThumbnailVideoFrameChoice.RETRY,
            SuperThumbnailVideoPolicy.chooseFrame(
                primaryIsBlack = true,
                retryExtracted = true,
                retryIsBlack = false,
            ),
        )
    }

    @Test fun sampleCoordinatesProducesDeterministicUniformGrid() {
        val samples = SuperThumbnailVideoPolicy.sampleCoordinates(384, 216, 4, 4)
        assertEquals(16, samples.size)
        // All coordinates are strictly within bitmap boundaries
        assertTrue(samples.all { (x, y) -> x in 0 until 384 && y in 0 until 216 })
        // Coordinates are unique
        assertEquals(16, samples.distinct().size)
    }

    private fun assertFails(block: () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }

    private fun item(name: String, size: Long, thumbnailUrl: String? = null) = RemoteFileItem(
        id = name,
        name = name,
        path = "/$name",
        isDirectory = false,
        size = size,
        thumbnailUrl = thumbnailUrl,
    )
}
