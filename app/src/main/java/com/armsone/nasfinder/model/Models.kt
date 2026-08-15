package com.armsone.nasfinder.model

import java.time.Instant
import java.text.Collator
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

enum class ConnectionKind(
    val title: String,
    val subtitle: String,
    val defaultPort: Int,
    val defaultRootPath: String,
    val supportsTls: Boolean = false,
    val oauth: Boolean = false,
) {
    SYNOLOGY("Synology NAS", "DSM File Station API를 사용합니다", 5001, "/", true),
    SFTP("SFTP 서버", "SSH를 통한 안전한 파일 전송", 22, "."),
    SMB("SMB", "ipTIME·Windows·NAS의 SMB 2 파일 공유", 445, "/"),
    WEBDAV("WebDAV", "ipTIME NAS와 일반 WebDAV 서버", 9800, "/", true),
    FTP("FTP", "ipTIME 공유기·NAS·일반 FTP 서버", 21, "/"),
    DROPBOX("Dropbox", "Dropbox 계정의 파일과 폴더", 443, "/", true, true),
    ONEDRIVE("OneDrive", "Microsoft 계정의 OneDrive 파일", 443, "/", true, true),
    GOOGLE_DRIVE("Google Drive", "Google 계정의 Drive 파일", 443, "/", true, true),
}

data class RemoteConnection(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val kind: ConnectionKind,
    val host: String,
    val port: Int = kind.defaultPort,
    val username: String,
    val rootPath: String = kind.defaultRootPath,
    val usesTls: Boolean = kind.supportsTls,
    val trustedHostKey: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val normalizedRootPath: String
        get() {
            val value = rootPath.trim()
            if (kind == ConnectionKind.SFTP) return value.ifBlank { "." }
            if (value.isBlank() || value == "/") return "/"
            return if (value.startsWith('/')) value else "/$value"
        }

    val endpoint: String
        get() = when (kind) {
            ConnectionKind.SYNOLOGY, ConnectionKind.WEBDAV ->
                "${if (usesTls) "https" else "http"}://$host:$port"
            ConnectionKind.SFTP -> "sftp://$host:$port"
            ConnectionKind.SMB -> "smb://$host:$port"
            ConnectionKind.FTP -> "ftp://$host:$port"
            else -> username.ifBlank { kind.title }
        }
}

data class RemoteFileItem(
    val id: String,
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val modifiedAt: Instant? = null,
    val mimeType: String? = null,
    val thumbnailUrl: String? = null,
) {
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
    private val normalizedMimeType: String get() = mimeType.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
    val isImage: Boolean get() = normalizedMimeType.startsWith("image/") || extension in IMAGE_EXTENSIONS
    val isVideo: Boolean get() = normalizedMimeType.startsWith("video/") || extension in VIDEO_EXTENSIONS
    val isPdf: Boolean get() = normalizedMimeType == "application/pdf" || extension == "pdf"

    private companion object {
        val IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "jpe", "png", "gif", "heic", "heif", "webp", "bmp", "dib",
            "tif", "tiff", "avif", "svg",
        )
        val VIDEO_EXTENSIONS = setOf(
            "mp4", "mov", "m4v", "mkv", "avi", "webm", "ts", "mts", "m2ts", "mpeg", "mpg",
            "3gp", "3g2", "wmv", "flv", "ogv",
        )
    }
}

enum class BrowserLayout { LIST, SMALL_GRID, LARGE_GRID }
enum class SortField { NAME, KIND, SIZE, MODIFIED }
enum class SortDirection { ASCENDING, DESCENDING }
enum class NamePriority { NUMBERS_FIRST, KOREAN_FIRST, LATIN_FIRST }
enum class AppTheme {
    SYSTEM,
    DAY,
    NIGHT,
    DIGITAL_RAIN,
    WINDY_MEADOW,
    WORKBENCH;

    val next: AppTheme
        get() = entries[(ordinal + 1) % entries.size]
}

data class BrowserPreferences(
    val layout: BrowserLayout = BrowserLayout.LIST,
    val sortField: SortField = SortField.NAME,
    val sortDirection: SortDirection = SortDirection.ASCENDING,
    val namePriority: NamePriority = NamePriority.NUMBERS_FIRST,
    val foldersFirst: Boolean = true,
    val showHiddenFiles: Boolean = false,
)

fun List<RemoteFileItem>.sortedWith(preferences: BrowserPreferences): List<RemoteFileItem> {
    val collator = Collator.getInstance(Locale.getDefault()).apply {
        strength = Collator.PRIMARY
        decomposition = Collator.CANONICAL_DECOMPOSITION
    }
    val nameComparator = Comparator<RemoteFileItem> { left, right ->
        compareNames(left.name, right.name, collator, preferences.namePriority).takeIf { it != 0 }
            ?: compareNames(left.path, right.path, collator, preferences.namePriority)
    }
    val primaryComparator = Comparator<RemoteFileItem> { left, right ->
        when (preferences.sortField) {
            SortField.NAME -> nameComparator.compare(left, right)
            SortField.KIND -> compareValues(left.kindSortKey(), right.kindSortKey())
            SortField.SIZE -> left.size.compareTo(right.size)
            SortField.MODIFIED -> compareNullableMetadata(left.modifiedAt, right.modifiedAt)
        }
    }
    val comparator = Comparator<RemoteFileItem> { left, right ->
        if (preferences.foldersFirst && left.isDirectory != right.isDirectory) {
            return@Comparator if (left.isDirectory) -1 else 1
        }

        val primary = primaryComparator.compare(left, right)
        // Missing metadata always stays last, even when the selected direction is descending.
        val directed = if (
            preferences.sortField == SortField.MODIFIED &&
            (left.modifiedAt == null || right.modifiedAt == null)
        ) primary else if (preferences.sortDirection == SortDirection.ASCENDING) primary else -primary
        if (directed != 0) return@Comparator directed

        val name = nameComparator.compare(left, right)
        if (name != 0) name else left.path.compareTo(right.path)
    }
    return asSequence()
        .filter { preferences.showHiddenFiles || !it.name.startsWith('.') }
        .sortedWith(comparator)
        .toList()
}

/** Case, accent and width insensitive matching using the device locale. */
fun String.containsLocalized(query: String): Boolean {
    val needle = query.trim().normalizedLookupKey()
    return needle.isEmpty() || normalizedLookupKey().contains(needle)
}

private fun RemoteFileItem.kindSortKey(): String = when {
    isDirectory -> ""
    extension.isNotEmpty() -> extension
    !mimeType.isNullOrBlank() -> mimeType.lowercase(Locale.getDefault())
    else -> "file"
}

private fun <T : Comparable<T>> compareNullableMetadata(left: T?, right: T?): Int = when {
    left == null && right == null -> 0
    left == null -> 1
    right == null -> -1
    else -> left.compareTo(right)
}

private fun compareNames(left: String, right: String, collator: Collator, priority: NamePriority): Int {
    val leftValue = Normalizer.normalize(left, Normalizer.Form.NFKC)
    val rightValue = Normalizer.normalize(right, Normalizer.Form.NFKC)
    val group = nameGroup(leftValue, priority).compareTo(nameGroup(rightValue, priority))
    return if (group != 0) group else naturalCompare(leftValue, rightValue, collator)
}

private fun nameGroup(value: String, priority: NamePriority): Int {
    val first = value.firstOrNull { !it.isWhitespace() } ?: return 3
    val type = when {
        first.isDigit() -> NamePriority.NUMBERS_FIRST
        first.code in 0x1100..0x11ff || first.code in 0x3130..0x318f || first.code in 0xac00..0xd7af -> NamePriority.KOREAN_FIRST
        else -> NamePriority.LATIN_FIRST
    }
    val order = when (priority) {
        NamePriority.NUMBERS_FIRST -> listOf(NamePriority.NUMBERS_FIRST, NamePriority.KOREAN_FIRST, NamePriority.LATIN_FIRST)
        NamePriority.KOREAN_FIRST -> listOf(NamePriority.KOREAN_FIRST, NamePriority.NUMBERS_FIRST, NamePriority.LATIN_FIRST)
        NamePriority.LATIN_FIRST -> listOf(NamePriority.LATIN_FIRST, NamePriority.NUMBERS_FIRST, NamePriority.KOREAN_FIRST)
    }
    return order.indexOf(type)
}

private fun naturalCompare(left: String, right: String, collator: Collator): Int {
    var leftIndex = 0
    var rightIndex = 0
    while (leftIndex < left.length && rightIndex < right.length) {
        val leftDigit = left[leftIndex].isDigit()
        val rightDigit = right[rightIndex].isDigit()
        if (leftDigit && rightDigit) {
            val leftEnd = left.runEnd(leftIndex, Char::isDigit)
            val rightEnd = right.runEnd(rightIndex, Char::isDigit)
            val number = compareDigitRuns(left.substring(leftIndex, leftEnd), right.substring(rightIndex, rightEnd))
            if (number != 0) return number
            leftIndex = leftEnd
            rightIndex = rightEnd
        } else {
            val leftEnd = left.runEnd(leftIndex) { !it.isDigit() }
            val rightEnd = right.runEnd(rightIndex) { !it.isDigit() }
            val text = collator.compare(left.substring(leftIndex, leftEnd), right.substring(rightIndex, rightEnd))
            if (text != 0) return text
            leftIndex = leftEnd
            rightIndex = rightEnd
        }
    }
    return (left.length - leftIndex).compareTo(right.length - rightIndex)
}

private fun String.runEnd(start: Int, predicate: (Char) -> Boolean): Int {
    var index = start
    while (index < length && predicate(this[index])) index++
    return index
}

private fun compareDigitRuns(left: String, right: String): Int {
    val leftSignificant = left.dropWhile { it == '0' }.ifEmpty { "0" }
    val rightSignificant = right.dropWhile { it == '0' }.ifEmpty { "0" }
    val length = leftSignificant.length.compareTo(rightSignificant.length)
    if (length != 0) return length
    val value = leftSignificant.compareTo(rightSignificant)
    if (value != 0) return value
    return left.length.compareTo(right.length)
}

private fun String.normalizedLookupKey(): String = Normalizer
    .normalize(this, Normalizer.Form.NFKD)
    .filterNot {
        when (Character.getType(it)) {
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt() -> true
            else -> false
        }
    }
    .lowercase(Locale.getDefault())
