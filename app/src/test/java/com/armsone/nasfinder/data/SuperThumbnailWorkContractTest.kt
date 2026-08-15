package com.armsone.nasfinder.data

import androidx.work.Data
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuperThumbnailWorkContractTest {
    @Test
    fun persistedProgressDataRestoresTheSameSnapshotAfterObserverRecreation() {
        val persisted = Data.Builder()
            .putString(SuperThumbnailWorker.KEY_STATUS, SuperThumbnailWorkStatus.RUNNING.name)
            .putInt(SuperThumbnailWorker.KEY_VISITED, 41)
            .putInt(SuperThumbnailWorker.KEY_GENERATED, 29)
            .putInt(SuperThumbnailWorker.KEY_FAILED, 3)
            .putLong(SuperThumbnailWorker.KEY_ESTIMATED_BYTES, 8_388_608L)
            .putBoolean(SuperThumbnailWorker.KEY_BUDGET_REACHED, true)
            .build()

        assertEquals(
            SuperThumbnailWorkSnapshot(
                status = SuperThumbnailWorkStatus.RUNNING,
                visitedItems = 41,
                generated = 29,
                failed = 3,
                estimatedBytes = 8_388_608L,
                budgetReached = true,
            ),
            SuperThumbnailWorkController.snapshot(persisted),
        )
    }

    @Test
    fun corruptOrNegativePersistedProgressFailsClosedAndNeverShowsNegativeCounts() {
        val persisted = Data.Builder()
            .putString(SuperThumbnailWorker.KEY_STATUS, "unknown-future-status")
            .putInt(SuperThumbnailWorker.KEY_VISITED, -1)
            .putInt(SuperThumbnailWorker.KEY_GENERATED, -2)
            .putInt(SuperThumbnailWorker.KEY_FAILED, -3)
            .putLong(SuperThumbnailWorker.KEY_ESTIMATED_BYTES, -4L)
            .putBoolean(SuperThumbnailWorker.KEY_BUDGET_REACHED, false)
            .build()

        val restored = SuperThumbnailWorkController.snapshot(persisted)
        assertEquals(SuperThumbnailWorkStatus.FAILED, restored.status)
        assertEquals(0, restored.visitedItems)
        assertEquals(0, restored.generated)
        assertEquals(0, restored.failed)
        assertEquals(0L, restored.estimatedBytes)
        assertFalse(restored.budgetReached)
    }

    @Test
    fun uniqueWorkIdentityIsStablePrivateAndIsolatedPerConnection() {
        val first = SuperThumbnailWorkController.uniqueName("connection-a")
        assertEquals(first, SuperThumbnailWorkController.uniqueName("connection-a"))
        assertNotEquals(first, SuperThumbnailWorkController.uniqueName("connection-b"))
        assertFalse(first.contains("connection-a"))
        assertTrue(first.startsWith("super-thumbnail-"))
    }
}
