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

    @Test fun serverVideoDoesNotRequireRangeCapabilityButUnsupportedFileIsRejected() {
        val serverVideo = item("movie.mp4", size = 0, thumbnailUrl = "https://nas.test/thumb")
        assertEquals(
            512L * 1024L,
            SuperThumbnailEligibility.estimatedBytes(serverVideo, supportsRangeStreaming = false),
        )
        assertNull(SuperThumbnailEligibility.estimatedBytes(item("notes.txt", 100), true))
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
