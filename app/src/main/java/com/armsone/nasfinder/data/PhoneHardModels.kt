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
) {
    val isSuccess: Boolean get() = failed.isEmpty()
}
