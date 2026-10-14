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
 * Result of a batch file/folder operation in PhoneHard.
 */
data class PhoneHardOperationResult(
    val succeeded: List<PhoneHardTarget>,
    val failed: List<Pair<PhoneHardTarget, String>>,
) {
    val isSuccess: Boolean get() = failed.isEmpty()
}
