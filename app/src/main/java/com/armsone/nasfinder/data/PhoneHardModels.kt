package com.armsone.nasfinder.data

import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * Kind of entry in PhoneHard (file or directory).
 */
enum class PhoneHardEntryKind {
    FILE,
    DIRECTORY,
}

/**
 * Immediate item listed in a PhoneHard directory.
 */
data class PhoneHardItem(
    val id: UUID?,
    val name: String,
    val relativePath: String,
    val kind: PhoneHardEntryKind,
    val byteCount: Long,
    val modifiedAt: Instant,
    val mimeType: String? = null,
    val file: File,
    val durationMillis: Long? = null,
    // 실제 비공개 여부: 자기 플래그 또는 상위 폴더가 비공개(기본 false).
    val isPrivate: Boolean = false,
)

/**
 * Targeted item specification for batch PhoneHard file-and-folder operations.
 */
data class PhoneHardTarget(
    val id: UUID? = null,
    val relativePath: String,
    val isDirectory: Boolean,
)

/**
 * Committed relocation of one PhoneHard entry (root-relative paths, '/' separated).
 * A directory mapping also covers every descendant under [sourcePath].
 */
data class PhoneHardPathMapping(
    val sourcePath: String,
    val destinationPath: String,
    val isDirectory: Boolean,
)

/**
 * Result of a batch file/folder operation in PhoneHard.
 * [pathMappings] (move/rename) and [removedPaths] (delete) list only entries whose filesystem
 * change and manifest update both committed; failed or unchanged targets never appear.
 */
data class PhoneHardOperationResult(
    val succeeded: List<PhoneHardTarget>,
    val failed: List<Pair<PhoneHardTarget, String>>,
    val pathMappings: List<PhoneHardPathMapping> = emptyList(),
    val removedPaths: List<String> = emptyList(),
    // 복사된 항목의 원본 → 새 경로(이동 매핑과 구분한다: 복사 결과로 참조를 옮기면 안 된다).
    val copiedPaths: List<PhoneHardPathMapping> = emptyList(),
) {
    val isSuccess: Boolean get() = failed.isEmpty()
}
