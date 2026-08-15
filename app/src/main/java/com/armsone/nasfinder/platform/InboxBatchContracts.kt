package com.armsone.nasfinder.platform

import java.util.UUID

internal enum class ShareIntentMode { SINGLE, MULTIPLE }

internal fun shareIntentMode(itemCount: Int, forceMultiple: Boolean): ShareIntentMode {
    require(itemCount > 0) { "공유할 파일이 없습니다." }
    return if (forceMultiple || itemCount > 1) ShareIntentMode.MULTIPLE else ShareIntentMode.SINGLE
}

data class InboxUploadOutcome(
    val id: UUID,
    val succeeded: Boolean,
    val failureMessage: String? = null,
) {
    init {
        require(succeeded || !failureMessage.isNullOrBlank()) {
            "실패한 Inbox 업로드에는 오류 문구가 필요합니다."
        }
        require(!succeeded || failureMessage == null) {
            "성공한 Inbox 업로드에는 오류 문구가 없어야 합니다."
        }
    }
}

class InboxUploadSummary internal constructor(
    val outcomes: List<InboxUploadOutcome>,
) {
    val totalCount: Int get() = outcomes.size
    val successCount: Int get() = outcomes.count(InboxUploadOutcome::succeeded)
    val failureCount: Int get() = totalCount - successCount
    val isPartialSuccess: Boolean get() = successCount > 0 && failureCount > 0
    val failedItems: List<InboxUploadOutcome> get() = outcomes.filterNot(InboxUploadOutcome::succeeded)

    val message: String
        get() = when {
            totalCount == 0 -> "선택한 파일이 없습니다."
            failureCount == 0 ->
                "${successCount}개 파일을 NAS로 보냈습니다. 받은 파일은 기기에 유지됩니다."
            successCount == 0 -> "${failureCount}개 파일을 NAS로 보내지 못했습니다."
            else ->
                "${totalCount}개 중 ${successCount}개를 NAS로 보냈고 ${failureCount}개는 실패했습니다. 받은 파일은 기기에 유지됩니다."
        }
}

/** Pure selection and sequential-result contract for the Inbox batch UI. */
object InboxBatchContracts {
    const val MAX_SELECTED_ITEMS = 50

    /** Preserves tap order, removes duplicate IDs, and rejects an oversized selection. */
    fun normalizeSelection(ids: Iterable<UUID>): List<UUID> {
        val normalized = LinkedHashSet<UUID>()
        ids.forEach(normalized::add)
        require(normalized.size <= MAX_SELECTED_ITEMS) {
            "한 번에 최대 ${MAX_SELECTED_ITEMS}개까지 선택할 수 있습니다."
        }
        return normalized.toList()
    }

    /** Outcomes must be appended in upload order; aggregation preserves that order. */
    fun summarizeSequential(outcomes: Iterable<InboxUploadOutcome>): InboxUploadSummary {
        val values = outcomes.toList()
        require(values.map { it.id }.distinct().size == values.size) {
            "같은 받은 파일의 업로드 결과가 중복되었습니다."
        }
        return InboxUploadSummary(values)
    }
}
