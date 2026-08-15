package com.armsone.nasfinder.data

import androidx.work.WorkInfo
import com.armsone.nasfinder.model.RemoteFileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

class SuperThumbnailCacheResetTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun trackedKeysSurviveProcessRestartAndRejectUnsafeNames() {
        val file = temporary.root.resolve("state/cache-keys-v1.json")
        val first = "1".repeat(64)
        val second = "a".repeat(64)
        SuperThumbnailCacheKeyStore(file).apply {
            record(first)
            record(second)
        }

        assertEquals(setOf(first, second), SuperThumbnailCacheKeyStore(file).keys())
        assertThrows(IllegalArgumentException::class.java) {
            SuperThumbnailCacheKeyStore(file).record("../browser-cache")
        }
    }

    @Test fun resetDeletesOnlyTrackedSuperThumbnailFilesAndPreservesBrowserCache() {
        val cache = ThumbnailDiskCache(temporary.newFolder("cache"), 1_000, 10_000)
        val tracked = "2".repeat(64)
        val missing = "3".repeat(64)
        val browserOnly = "4".repeat(64)
        cache.commit(tracked, cache.temporaryFile("tracked").apply { writeBytes(byteArrayOf(1, 2, 3)) })
        cache.commit(browserOnly, cache.temporaryFile("browser").apply { writeBytes(byteArrayOf(7, 8)) })

        val result = cache.removeTracked(setOf(tracked, missing))

        assertEquals(1, result.removedFileCount)
        assertEquals(3L, result.removedBytes)
        assertEquals(1, result.alreadyMissingCount)
        assertTrue(result.failedKeys.isEmpty())
        assertNull(cache.get(tracked))
        assertEquals(2L, cache.get(browserOnly)?.length())
    }

    @Test fun clearAllRemovesEveryQueueAndReportWithoutTouchingExternalPreferences() {
        val file = temporary.root.resolve("sessions/session-v2.json")
        val store = SuperThumbnailSessionStore(file)
        val item = RemoteFileItem(
            id = "a",
            name = "a.jpg",
            path = "/photos/a.jpg",
            isDirectory = false,
            size = 10,
            modifiedAt = Instant.EPOCH,
        )
        store.prepare("one|/photos", "/photos", listOf(item), vaultEnabled = true)
        store.prepare("two|/videos", "/videos", listOf(item.copy(id = "b")), vaultEnabled = false)

        assertEquals(2, store.clearAll())
        assertNull(SuperThumbnailSessionStore(file).report("one|/photos"))
        assertNull(SuperThumbnailSessionStore(file).report("two|/videos"))
    }

    @Test fun activeOrQueuedWorkBlocksResetAndCoordinatorBlocksRacingEnqueue() {
        assertEquals(
            3,
            SuperThumbnailCacheResetPolicy.activeWorkCount(
                listOf(
                    WorkInfo.State.ENQUEUED,
                    WorkInfo.State.RUNNING,
                    WorkInfo.State.BLOCKED,
                    WorkInfo.State.SUCCEEDED,
                    WorkInfo.State.FAILED,
                    WorkInfo.State.CANCELLED,
                )
            ),
        )
        assertTrue(SuperThumbnailCacheResetCoordinator.tryBegin())
        try {
            assertFalse(SuperThumbnailCacheResetCoordinator.tryBegin())
            assertThrows(IllegalStateException::class.java) {
                SuperThumbnailCacheResetCoordinator.withEnqueueAllowed { Unit }
            }
        } finally {
            SuperThumbnailCacheResetCoordinator.end()
        }
        assertEquals("allowed", SuperThumbnailCacheResetCoordinator.withEnqueueAllowed { "allowed" })
    }
}
