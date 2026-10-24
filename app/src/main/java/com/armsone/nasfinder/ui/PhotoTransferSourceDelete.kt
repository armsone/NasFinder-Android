package com.armsone.nasfinder.ui

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Exact local MediaStore original a photo/video was selected from, captured before the first byte is sent.
 * Row version (size, modified time, generation, name) and local file identity (device, inode, modified time) together
 * are the "unchanged" evidence re-checked right before the delete request; the sent SHA-256 is the content evidence.
 */
internal data class PhotoTransferSourceSnapshot(
    val mediaUri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val dateModifiedSeconds: Long,
    /** MediaStore GENERATION_MODIFIED; -1 when the platform does not report it. */
    val generationModified: Long,
    val fileDevice: Long,
    val fileInode: Long,
    val fileModifiedSeconds: Long,
)

internal sealed interface PhotoTransferSourceInfo {
    data class Deletable(val snapshot: PhotoTransferSourceSnapshot) : PhotoTransferSourceInfo
    data class NotDeletable(val reason: String) : PhotoTransferSourceInfo
}

/**
 * Optional "delete originals after sending" support (default OFF in the UI).
 *
 * Supported source (positive evidence required, anything else is excluded with a visible reason):
 * a media-provider row (direct `content://media/external*` item, or a media-documents `image:`/`video:` document that
 * maps to one) on the primary shared storage whose file is a regular local file under the external storage root with
 * the sent size. Unknown origins, cloud-only rows, read-only storage, shared staging copies, photo-picker virtual URIs
 * and other apps' providers are never guessed at. The platform's own consent dialog (createDeleteRequest) is a
 * necessary additional condition, not a replacement for this classification.
 * Same file name or same hash never identifies an original by itself.
 */
internal object PhotoTransferSourceDelete {
    private const val MEDIA_DOCUMENTS_AUTHORITY = "com.android.providers.media.documents"

    const val SUPPORTED_SOURCE_NOTE =
        "삭제는 이 기기 저장소에 있는 미디어 원본을 ‘파일’로 담았을 때만 지원합니다. " +
            "‘미디어’이나 ‘Google 포토’로 담은 항목, 클라우드에만 있는 항목, 읽기 전용 저장소, 다른 앱에서 공유받은 복사본은 삭제 대상에서 제외하며 원본은 그대로 둡니다."

    fun inspect(context: Context, uri: Uri, sentByteLength: Long): PhotoTransferSourceInfo {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return PhotoTransferSourceInfo.NotDeletable(
                "Android 10 이하에서는 삭제 대상을 안전하게 확인할 수 없어 원본을 삭제하지 않고 그대로 둡니다.",
            )
        }
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            return PhotoTransferSourceInfo.NotDeletable("원본 위치를 확인할 수 없는 파일이라 삭제 대상에서 제외했습니다.")
        }
        val mediaUri = when (uri.authority) {
            MediaStore.AUTHORITY -> {
                val first = uri.pathSegments.firstOrNull().orEmpty()
                if (!first.startsWith("external")) {
                    return PhotoTransferSourceInfo.NotDeletable(
                        "사진 보관함 선택기로 고른 항목은 원본 위치를 확인할 수 없어 제외했습니다. ‘파일’로 담으면 삭제 대상이 될 수 있습니다.",
                    )
                }
                uri
            }
            MEDIA_DOCUMENTS_AUTHORITY -> {
                val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull().orEmpty()
                if (!(documentId.startsWith("image:") || documentId.startsWith("video:"))) {
                    return PhotoTransferSourceInfo.NotDeletable("이미지·영상 원본 항목으로 확인되지 않아 제외했습니다.")
                }
                runCatching { MediaStore.getMediaUri(context, uri) }.getOrNull()
                    ?: return PhotoTransferSourceInfo.NotDeletable("미디어 보관함의 원본 항목으로 확인되지 않아 제외했습니다.")
            }
            else -> return PhotoTransferSourceInfo.NotDeletable("다른 앱에서 공유받은 복사본이라 원본으로 볼 수 없어 제외했습니다.")
        }
        val segments = mediaUri.pathSegments
        val isSingleMediaRow = mediaUri.authority == MediaStore.AUTHORITY && segments.size == 4 &&
            segments[0].startsWith("external") && segments[1] in setOf("images", "video") && segments[2] == "media" &&
            runCatching { ContentUris.parseId(mediaUri) }.getOrDefault(-1L) > 0L
        if (!isSingleMediaRow) {
            return PhotoTransferSourceInfo.NotDeletable("단일 미디어 원본으로 확인되지 않아 제외했습니다.")
        }
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) {
            return PhotoTransferSourceInfo.NotDeletable("저장소가 읽기 전용이거나 사용할 수 없어 삭제 대상에서 제외했습니다.")
        }
        val snapshot = querySnapshot(context, mediaUri)
            ?: return PhotoTransferSourceInfo.NotDeletable(
                "이 기기 저장소의 실제 원본 파일로 확인되지 않아 제외했습니다(클라우드 전용·보류·휴지통·외부 저장소 항목일 수 있습니다).",
            )
        if (snapshot.sizeBytes != sentByteLength) {
            return PhotoTransferSourceInfo.NotDeletable("원본 크기가 보낼 때 확인한 크기와 다릅니다.")
        }
        return PhotoTransferSourceInfo.Deletable(snapshot)
    }

    /**
     * Current version of [mediaUri] with positive local-file evidence; null when the row is absent, pending, trashed,
     * not on primary shared storage, or its file cannot be shown to be a regular local file of the same size.
     */
    fun querySnapshot(context: Context, mediaUri: Uri): PhotoTransferSourceSnapshot? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            val columns = arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.GENERATION_MODIFIED,
                MediaStore.MediaColumns.VOLUME_NAME,
                MediaStore.MediaColumns.IS_PENDING,
                MediaStore.MediaColumns.IS_TRASHED,
                @Suppress("DEPRECATION") MediaStore.MediaColumns.DATA,
            )
            val row = context.contentResolver.query(mediaUri, columns, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                fun long(name: String): Long {
                    val index = cursor.getColumnIndexOrThrow(name)
                    return if (cursor.isNull(index)) -1L else cursor.getLong(index)
                }
                val volume = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.VOLUME_NAME))
                if (volume != MediaStore.VOLUME_EXTERNAL_PRIMARY) return@use null
                if (long(MediaStore.MediaColumns.IS_PENDING) != 0L || long(MediaStore.MediaColumns.IS_TRASHED) != 0L) return@use null
                val path = cursor.getString(cursor.getColumnIndexOrThrow(@Suppress("DEPRECATION") MediaStore.MediaColumns.DATA)).orEmpty()
                val root = Environment.getExternalStorageDirectory().absolutePath + File.separator
                if (path.isEmpty() || !path.startsWith(root)) return@use null
                SnapshotRow(
                    displayName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)).orEmpty(),
                    sizeBytes = long(MediaStore.MediaColumns.SIZE),
                    dateModifiedSeconds = long(MediaStore.MediaColumns.DATE_MODIFIED),
                    generationModified = long(MediaStore.MediaColumns.GENERATION_MODIFIED),
                )
            } ?: return@runCatching null
            val identity = context.contentResolver.openFileDescriptor(mediaUri, "r")?.use { pfd ->
                val stat = Os.fstat(pfd.fileDescriptor)
                if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_size != row.sizeBytes) null else stat
            } ?: return@runCatching null
            PhotoTransferSourceSnapshot(
                mediaUri = mediaUri,
                displayName = row.displayName,
                sizeBytes = row.sizeBytes,
                dateModifiedSeconds = row.dateModifiedSeconds,
                generationModified = row.generationModified,
                fileDevice = identity.st_dev,
                fileInode = identity.st_ino,
                fileModifiedSeconds = identity.st_mtime,
            )
        }.getOrNull()
    }

    private class SnapshotRow(
        val displayName: String,
        val sizeBytes: Long,
        val dateModifiedSeconds: Long,
        val generationModified: Long,
    )

    /**
     * Re-checks one source right before the delete request: same row version and local file identity as the snapshot
     * taken before sending, and the bytes still hash to [sentSha256] (the hash of the file as it was read for sending,
     * i.e. the origin hash, not a converted output). Returns null when unchanged, otherwise the reason it is excluded.
     */
    fun verifyUnchanged(context: Context, snapshot: PhotoTransferSourceSnapshot, sentSha256: String): String? {
        val current = querySnapshot(context, snapshot.mediaUri) ?: return "원본이 더 이상 같은 위치에 없습니다."
        if (current != snapshot) return "보낸 뒤 원본이 바뀌었습니다."
        val digest = MessageDigest.getInstance("SHA-256")
        val read = runCatching {
            context.contentResolver.openFileDescriptor(snapshot.mediaUri, "r")?.use { pfd ->
                val before = Os.fstat(pfd.fileDescriptor)
                var total = 0L
                FileInputStream(pfd.fileDescriptor).use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        total += count
                    }
                }
                val after = Os.fstat(pfd.fileDescriptor)
                if (before.st_ino != after.st_ino || before.st_mtime != after.st_mtime || before.st_size != after.st_size) -1L else total
            }
        }.getOrNull() ?: return "원본을 다시 읽지 못했습니다."
        if (read < 0L) return "확인하는 동안 원본이 바뀌었습니다."
        if (read != snapshot.sizeBytes) return "원본 크기가 달라졌습니다."
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return if (actual == sentSha256) null else "보낸 뒤 원본 내용이 바뀌었습니다."
    }

    /** Platform delete confirmation for already verified originals; the user can still decline. */
    fun createDeleteRequest(context: Context, mediaUris: List<Uri>): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || mediaUris.isEmpty()) return null
        return runCatching { MediaStore.createDeleteRequest(context.contentResolver, mediaUris).intentSender }.getOrNull()
    }

    fun isGone(context: Context, mediaUri: Uri): Boolean = runCatching {
        context.contentResolver.query(mediaUri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { !it.moveToFirst() } ?: true
    }.getOrDefault(false)
}
