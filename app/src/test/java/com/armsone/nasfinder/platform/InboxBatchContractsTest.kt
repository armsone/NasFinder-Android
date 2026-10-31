package com.armsone.nasfinder.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        assertEquals("2개 파일을 NAS로 보냈습니다. 폰하드 파일은 기기에 유지됩니다.", summary.message)
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
        assertEquals("2개 중 1개를 NAS로 보냈고 1개는 실패했습니다. 폰하드 파일은 기기에 유지됩니다.", partial.message)
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

    @Test fun uploadedCopyAcceptsNewSameSizeFileWithOriginalName() {
        assertEquals(
            "photo.jpg",
            InboxBatchContracts.findUploadedCopy("photo.jpg", 10L, setOf("other.txt"), listOf("other.txt" to 3L, "photo.jpg" to 10L)),
        )
        assertEquals("notes", InboxBatchContracts.findUploadedCopy("notes", 4L, emptySet(), listOf("notes" to 4L)))
    }

    @Test fun uploadedCopyAcceptsCollisionRenamedFileOnlyWhenNew() {
        assertEquals(
            "photo (1).jpg",
            InboxBatchContracts.findUploadedCopy(
                "photo.jpg", 10L, setOf("photo.jpg"), listOf("photo.jpg" to 10L, "photo (1).jpg" to 10L),
            ),
        )
        // 이미 번호 이름이 있었다면 서비스가 다음 번호를 붙인다.
        assertEquals(
            "photo (2).jpg",
            InboxBatchContracts.findUploadedCopy(
                "photo.jpg", 10L, setOf("photo.jpg", "photo (1).jpg"),
                listOf("photo.jpg" to 10L, "photo (1).jpg" to 10L, "photo (2).jpg" to 10L),
            ),
        )
        // 원래 이름이 이미 번호를 가졌다면 서비스는 그 번호를 떼고 다음 번호를 붙인다.
        assertEquals(
            "photo (2).jpg",
            InboxBatchContracts.findUploadedCopy(
                "photo (1).jpg", 10L, setOf("photo (1).jpg"), listOf("photo (1).jpg" to 10L, "photo (2).jpg" to 10L),
            ),
        )
    }

    @Test fun uploadedCopyRejectsPreexistingSameNameAndSize() {
        // 업로드 전에 있던 파일만 보이면 이번 업로드의 결과로 볼 수 없다.
        assertNull(InboxBatchContracts.findUploadedCopy("photo.jpg", 10L, setOf("photo.jpg"), listOf("photo.jpg" to 10L)))
        assertNull(
            InboxBatchContracts.findUploadedCopy(
                "photo.jpg", 10L, setOf("photo.jpg", "photo (1).jpg"),
                listOf("photo.jpg" to 10L, "photo (1).jpg" to 10L),
            ),
        )
    }

    @Test fun uploadedCopyRejectsWrongSizeUnknownAndUnrelatedNames() {
        assertNull(InboxBatchContracts.findUploadedCopy("photo.jpg", 10L, emptySet(), listOf("photo.jpg" to 9L)))
        assertNull(InboxBatchContracts.findUploadedCopy("photo.jpg", 10L, emptySet(), listOf("photo (1).jpg" to 11L)))
        assertNull(InboxBatchContracts.findUploadedCopy("photo.jpg", 10L, emptySet(), emptyList()))
        // 크기가 같아도 이름이 다른 새 파일, 비슷해 보이는 이름은 받지 않는다.
        assertNull(
            InboxBatchContracts.findUploadedCopy(
                "photo.jpg", 10L, emptySet(),
                listOf("other.jpg" to 10L, "photo(1).jpg" to 10L, "photo (x).jpg" to 10L, "xphoto (1).jpg" to 10L,
                    "photo (1).png" to 10L, "photo (1).jpg.bak" to 10L),
            ),
        )
    }

    @Test fun uploadedCopyPicksTheNewCandidateAmongPreexistingFiles() {
        assertEquals(
            "photo (3).jpg",
            InboxBatchContracts.findUploadedCopy(
                "photo.jpg", 10L, setOf("photo.jpg", "photo (1).jpg", "photo (2).jpg"),
                listOf("photo.jpg" to 10L, "photo (1).jpg" to 10L, "photo (2).jpg" to 10L, "photo (3).jpg" to 10L),
            ),
        )
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
