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
                "${successCount}개 파일을 NAS로 보냈습니다. 폰하드 파일은 기기에 유지됩니다."
            successCount == 0 -> "${failureCount}개 파일을 NAS로 보내지 못했습니다."
            else ->
                "${totalCount}개 중 ${successCount}개를 NAS로 보냈고 ${failureCount}개는 실패했습니다. 폰하드 파일은 기기에 유지됩니다."
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

    /**
     * 이동(NAS로 보낸 뒤 원본 삭제) 전 확인용. 업로드 직후 대상 폴더 목록([afterFiles]: 파일 이름 to 크기)에서
     * 이번 업로드가 새로 만든 파일을 찾는다. 업로드 전에 없던 이름이어야 하고, 이름은 원래 이름이거나
     * 같은 이름 충돌 때 서비스가 붙이는 "이름 (n).확장자" 형태여야 하며, 크기가 원본과 같아야 한다.
     * 찾지 못하면 null — 호출자는 원본을 지우지 않는다. 내용 해시 비교가 아니라 이름·크기 확인이다.
     */
    fun findUploadedCopy(
        originalName: String,
        expectedSize: Long,
        namesBefore: Set<String>,
        afterFiles: List<Pair<String, Long>>,
    ): String? {
        val dot = originalName.lastIndexOf('.').takeIf { it > 0 } ?: -1
        val extension = if (dot >= 0) originalName.substring(dot) else ""
        val stem = if (dot >= 0) originalName.substring(0, dot) else originalName
        val strippedStem = stem.replace(Regex(" \\(\\d+\\)$"), "")
        val numbered = Regex(
            "^(?:${Regex.escape(stem)}|${Regex.escape(strippedStem)}) \\(\\d+\\)${Regex.escape(extension)}$",
        )
        return afterFiles.firstOrNull { (name, size) ->
            name !in namesBefore && size == expectedSize && (name == originalName || numbered.matches(name))
        }?.first
    }

    /** Outcomes must be appended in upload order; aggregation preserves that order. */
    fun summarizeSequential(outcomes: Iterable<InboxUploadOutcome>): InboxUploadSummary {
        val values = outcomes.toList()
        require(values.map { it.id }.distinct().size == values.size) {
            "같은 폰하드 파일의 업로드 결과가 중복되었습니다."
        }
        return InboxUploadSummary(values)
    }
}
