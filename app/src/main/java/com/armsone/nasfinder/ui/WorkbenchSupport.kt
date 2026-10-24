package com.armsone.nasfinder.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Size
import android.webkit.MimeTypeMap
import com.armsone.nasfinder.model.RemoteFileItem
import java.io.File

/** What a workbench leaf is when a run starts. Folders are expanded first; only IMAGE / VIDEO leaves are supported. */
internal enum class WorkbenchLeafKind { IMAGE, VIDEO, OTHER }

internal fun classifyWorkbenchLeaf(name: String, mimeType: String?): WorkbenchLeafKind {
    val probe = RemoteFileItem(id = name, name = name, path = name, isDirectory = false, mimeType = mimeType)
    return when {
        probe.isVideo -> WorkbenchLeafKind.VIDEO
        probe.isImage -> WorkbenchLeafKind.IMAGE
        else -> WorkbenchLeafKind.OTHER
    }
}

/** Deepest folder level a run will walk below the referenced folder (guards symlink / path-growth loops; deeper levels are reported). */
internal const val WORKBENCH_MAX_FOLDER_DEPTH = 32

/**
 * Normalizes a NAS path for COMPARISON only (collapses repeated separators and "." segments, resolves ".."). Returns null when
 * ".." would climb above the root. The original backend path is still what gets listed / downloaded.
 */
internal fun normalizeWorkbenchRemotePath(raw: String): String? {
    val parts = ArrayDeque<String>()
    for (segment in raw.replace('\\', '/').split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> if (parts.isEmpty()) return null else parts.removeLast()
            else -> parts.addLast(segment)
        }
    }
    return "/" + parts.joinToString("/")
}

/** True when [child] is [root] itself or lies below it (both already normalized). */
internal fun isInsideWorkbenchRoot(root: String, child: String): Boolean =
    root == "/" || child == root || child.startsWith(root.trimEnd('/') + "/")

/** User-facing source label of a reference (display only; the stored origin tag is never renamed). */
internal fun workbenchOriginLabel(item: MaterialItem): String = when (item.origin) {
    "GOOGLE_PHOTOS" -> "Google 포토"
    "FILES" -> "파일"
    "FAVORITE" -> "즐겨찾기"
    "NAS" -> "NAS"
    "PHONE_HARD" -> "폰하드"
    "MY_MEDIA" -> "내 미디어"
    else -> when (item.sourceType) {
        MaterialSourceType.LOCAL_LIBRARY -> "내 미디어"
        MaterialSourceType.PHONE_HARD -> "폰하드"
        MaterialSourceType.REMOTE_FILE -> "NAS·원격 저장소"
    }
}

/** A reference that needs the user (다시 연결). A NAS item that was simply not fetched yet (legacy "가져오기 필요") is normal. */
internal fun MaterialItem.needsReconnect(): Boolean =
    state == MaterialPreparationState.MISSING &&
        !(sourceType == MaterialSourceType.REMOTE_FILE && errorMessage == "가져오기 필요")

internal const val WORKBENCH_REMOTE_NOT_FETCHED = "가져오기 필요"

// ---- transfer description of one readable media source (content://, file://) -----------------------------------------

internal data class WorkbenchTransferMedia(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val mediaKind: String,
    val byteLength: Long,
    val thumbnail: Bitmap?,
    val durationMs: Long?,
)

/**
 * Describes [uri] for the transfer wire (name, MIME type, size, photo/video/motionPhoto). [nameHint] / [mimeHint] come from
 * the reference record when the provider cannot answer (file:// has no MIME type of its own).
 */
internal fun describeTransferMedia(context: Context, uri: Uri, nameHint: String?, mimeHint: String?): WorkbenchTransferMedia? {
    var displayName: String? = null
    var byteLength = -1L
    if (uri.scheme == "file") {
        val file = File(uri.path.orEmpty())
        if (!file.isFile) return null
        displayName = file.name
        byteLength = file.length()
    } else {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameColumn >= 0 && !cursor.isNull(nameColumn)) displayName = cursor.getString(nameColumn)
                    if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) byteLength = cursor.getLong(sizeColumn)
                }
            }
        }
        if (byteLength < 0L) {
            byteLength = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
        }
    }
    val name = displayName?.takeIf(String::isNotBlank) ?: nameHint?.takeIf(String::isNotBlank) ?: "media-${uri.toString().hashCode()}"
    val providerMime = if (uri.scheme == "content") runCatching { context.contentResolver.getType(uri) }.getOrNull() else null
    val extensionMime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
    val mime = providerMime?.takeIf { it.startsWith("image/") || it.startsWith("video/") }
        ?: mimeHint?.takeIf { it.startsWith("image/") || it.startsWith("video/") }
        ?: extensionMime
        ?: providerMime
    val leaf = classifyWorkbenchLeaf(name, mime)
    if (leaf == WorkbenchLeafKind.OTHER) return null
    val kind = when {
        leaf == WorkbenchLeafKind.VIDEO -> "video"
        isMotionPhotoSource(context, uri) -> "motionPhoto"
        else -> "photo"
    }
    return WorkbenchTransferMedia(
        uri = uri,
        name = name,
        mimeType = mime ?: if (leaf == WorkbenchLeafKind.VIDEO) "video/mp4" else "image/jpeg",
        mediaKind = kind,
        byteLength = byteLength,
        thumbnail = loadTransferThumbnail(context, uri, leaf == WorkbenchLeafKind.VIDEO),
        durationMs = if (leaf == WorkbenchLeafKind.VIDEO) loadTransferDuration(context, uri) else null,
    )
}

private fun loadTransferThumbnail(context: Context, uri: Uri, isVideo: Boolean): Bitmap? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.scheme == "content") {
        context.contentResolver.loadThumbnail(uri, Size(320, 240), null)
    } else if (isVideo) {
        MediaMetadataRetriever().run {
            try {
                setDataSource(context, uri)
                getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally {
                release()
            }
        }
    } else {
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inSampleSize = 4 })
        }
    }
}.getOrNull()

private fun loadTransferDuration(context: Context, uri: Uri): Long? = runCatching {
    MediaMetadataRetriever().run {
        try {
            setDataSource(context, uri)
            extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } finally {
            release()
        }
    }
}.getOrNull()

private fun isMotionPhotoSource(context: Context, uri: Uri): Boolean {
    val mediaStoreFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && uri.scheme == "content") runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.Files.FileColumns.SPECIAL_FORMAT), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(MediaStore.Files.FileColumns.SPECIAL_FORMAT)
            cursor.moveToFirst() && column >= 0 &&
                cursor.getInt(column) == MediaStore.Files.FileColumns.SPECIAL_FORMAT_MOTION_PHOTO
        } ?: false
    }.getOrDefault(false) else false
    if (mediaStoreFlag) return true

    return runCatching {
        val length = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: return false
        if (length !in 1..512L * 1024 * 1024) return false
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream(length.toInt())
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var remaining = length
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (count <= 0) return false
                output.write(buffer, 0, count)
                remaining -= count
            }
            output.toByteArray()
        } ?: return false
        MotionPhotoCodec.parse(bytes) != null
    }.getOrDefault(false)
}
