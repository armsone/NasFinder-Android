package com.armsone.nasfinder.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class InboxBatchContractsTest {
    @Test fun batchShareAlwaysUsesMultipleMode() {
        assertEquals(ShareIntentMode.MULTIPLE, shareIntentMode(itemCount = 1, forceMultiple = true))
        assertEquals(ShareIntentMode.MULTIPLE, shareIntentMode(itemCount = 2, forceMultiple = false))
        assertEquals(ShareIntentMode.SINGLE, shareIntentMode(itemCount = 1, forceMultiple = false))
    }

    @Test fun selectionRemovesDuplicateIdsWithoutChangingOrder() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()

        assertEquals(
            listOf(first, second),
            InboxBatchContracts.normalizeSelection(listOf(first, second, first, second)),
        )
    }

    @Test fun selectionAllowsFiftyUniqueItemsAndRejectsFiftyOne() {
        val fifty = List(50) { UUID.randomUUID() }
        assertEquals(50, InboxBatchContracts.normalizeSelection(fifty + fifty.first()).size)
        assertFails<IllegalArgumentException> {
            InboxBatchContracts.normalizeSelection(fifty + UUID.randomUUID())
        }
    }

    @Test fun allSuccessSummaryPromisesLocalFilesArePreserved() {
        val summary = InboxBatchContracts.summarizeSequential(
            listOf(
                InboxUploadOutcome(UUID.randomUUID(), succeeded = true),
                InboxUploadOutcome(UUID.randomUUID(), succeeded = true),
            ),
        )

        assertEquals(2, summary.successCount)
        assertEquals(0, summary.failureCount)
        assertFalse(summary.isPartialSuccess)
        assertEquals("2개 파일을 NAS로 보냈습니다. 받은 파일은 기기에 유지됩니다.", summary.message)
    }

    @Test fun allFailureAndPartialSuccessHaveDistinctMessages() {
        val failedId = UUID.randomUUID()
        val failed = InboxBatchContracts.summarizeSequential(
            listOf(InboxUploadOutcome(failedId, succeeded = false, failureMessage = "연결 실패")),
        )
        assertEquals("1개 파일을 NAS로 보내지 못했습니다.", failed.message)
        assertEquals(listOf(failedId), failed.failedItems.map { it.id })

        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val partial = InboxBatchContracts.summarizeSequential(
            listOf(
                InboxUploadOutcome(first, succeeded = true),
                InboxUploadOutcome(second, succeeded = false, failureMessage = "권한 없음"),
            ),
        )
        assertTrue(partial.isPartialSuccess)
        assertEquals("2개 중 1개를 NAS로 보냈고 1개는 실패했습니다. 받은 파일은 기기에 유지됩니다.", partial.message)
        assertEquals(listOf(first, second), partial.outcomes.map { it.id })
    }

    @Test fun duplicateOutcomesAndFailureWithoutReasonAreRejected() {
        val id = UUID.randomUUID()
        assertFails<IllegalArgumentException> { InboxUploadOutcome(id, succeeded = false) }
        assertFails<IllegalArgumentException> {
            InboxUploadOutcome(id, succeeded = true, failureMessage = "모순된 오류")
        }
        assertFails<IllegalArgumentException> {
            InboxBatchContracts.summarizeSequential(
                listOf(
                    InboxUploadOutcome(id, succeeded = true),
                    InboxUploadOutcome(id, succeeded = false, failureMessage = "실패"),
                ),
            )
        }
    }

    private inline fun <reified T : Throwable> assertFails(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected ${T::class.java.simpleName}")
        } catch (error: Throwable) {
            if (error !is T) throw error
        }
    }
}
