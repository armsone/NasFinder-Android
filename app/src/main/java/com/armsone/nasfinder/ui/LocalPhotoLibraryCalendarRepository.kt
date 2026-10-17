package com.armsone.nasfinder.ui

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

internal data class CalendarDayMedia(
    val uri: Uri,
    val isVideo: Boolean,
    val durationMs: Long?,
    val displayName: String?,
    val dateTakenMs: Long?,
    val dateAddedSec: Long?,
    val dateModifiedSec: Long?,
    val byteLength: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val isMotionPhoto: Boolean = false,
)

internal enum class CalendarMediaPermissionState {
    FULL_ACCESS,
    LIMITED_ACCESS,
    DENIED,
}

internal data class LocalLibraryQuery(
    val photos: Boolean = true,
    val motionPhotos: Boolean = true,
    val videos: Boolean = true,
    val captureSort: Boolean = true,
    val ascending: Boolean = true,
    val durationMs: Long? = null,
    val durationAtLeast: Boolean = true,
) : java.io.Serializable

internal object LocalPhotoLibraryCalendarRepository {

    private val motionMetadataCapabilities = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** SDK constants do not guarantee that an OEM MediaProvider implements the column. */
    internal fun motionPhotoMetadataSupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val key = collection.toString()
        motionMetadataCapabilities[key]?.let { return it }
        return try {
            context.contentResolver.query(collection, arrayOf(MediaStore.Files.FileColumns.SPECIAL_FORMAT), "${MediaStore.MediaColumns._ID} = -1", null, null)?.use { cursor ->
                (cursor.getColumnIndex(MediaStore.Files.FileColumns.SPECIAL_FORMAT) >= 0).also { motionMetadataCapabilities[key] = it }
            } ?: false
        } catch (_: IllegalArgumentException) {
            motionMetadataCapabilities[key] = false
            false
        } catch (_: SecurityException) {
            // Permission changes must not cache a provider capability result.
            false
        } catch (_: Exception) { false }
    }

    fun checkPermissionState(context: Context): CalendarMediaPermissionState {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                val hasImages = ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.READ_MEDIA_IMAGES
                ) == PackageManager.PERMISSION_GRANTED
                val hasVideo = ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.READ_MEDIA_VIDEO
                ) == PackageManager.PERMISSION_GRANTED
                val hasVisualUserSelected = ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                ) == PackageManager.PERMISSION_GRANTED

                when {
                    hasImages && hasVideo -> CalendarMediaPermissionState.FULL_ACCESS
                    hasImages || hasVideo -> CalendarMediaPermissionState.LIMITED_ACCESS
                    hasVisualUserSelected -> CalendarMediaPermissionState.LIMITED_ACCESS
                    else -> CalendarMediaPermissionState.DENIED
                }
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                val hasImages = ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.READ_MEDIA_IMAGES
                ) == PackageManager.PERMISSION_GRANTED
                val hasVideo = ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.READ_MEDIA_VIDEO
                ) == PackageManager.PERMISSION_GRANTED

                if (hasImages && hasVideo) {
                    CalendarMediaPermissionState.FULL_ACCESS
                } else if (hasImages || hasVideo) {
                    CalendarMediaPermissionState.LIMITED_ACCESS
                } else {
                    CalendarMediaPermissionState.DENIED
                }
            }
            else -> {
                val hasStorage = ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED

                if (hasStorage) {
                    CalendarMediaPermissionState.FULL_ACCESS
                } else {
                    CalendarMediaPermissionState.DENIED
                }
            }
        }
    }

    fun canQueryImages(context: Context): Boolean {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.READ_MEDIA_IMAGES
                ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                ) == PackageManager.PERMISSION_GRANTED
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.READ_MEDIA_IMAGES
                ) == PackageManager.PERMISSION_GRANTED
            }
            else -> {
                ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
    }

    fun canQueryVideos(context: Context): Boolean {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.READ_MEDIA_VIDEO
                ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                ) == PackageManager.PERMISSION_GRANTED
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.READ_MEDIA_VIDEO
                ) == PackageManager.PERMISSION_GRANTED
            }
            else -> {
                ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
    }

    /**
     * Month-scoped MediaStore query.
     * Filter condition:
     * (taken >= startMs AND taken < endMs)
     * OR ((taken IS NULL OR taken = 0) AND added >= startSec AND added < endSec)
     * OR ((taken IS NULL OR taken = 0) AND (added IS NULL OR added = 0) AND modified >= startSec AND modified < endSec)
     */
    suspend fun loadMonthMedia(
        context: Context,
        yearMonth: YearMonth,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): List<CalendarDayMedia> = withContext(Dispatchers.IO) {
        val permissionState = checkPermissionState(context)
        if (permissionState == CalendarMediaPermissionState.DENIED) {
            return@withContext emptyList()
        }

        val startOfMonth = yearMonth.atDay(1).atStartOfDay(zoneId)
        val startOfNextMonth = yearMonth.plusMonths(1).atDay(1).atStartOfDay(zoneId)

        val startMs = startOfMonth.toInstant().toEpochMilli()
        val endMs = startOfNextMonth.toInstant().toEpochMilli()
        val startSec = startMs / 1000L
        val endSec = endMs / 1000L

        val selection = "(" +
                "(${MediaStore.MediaColumns.DATE_TAKEN} >= ? AND ${MediaStore.MediaColumns.DATE_TAKEN} < ?)" +
                " OR " +
                "(((${MediaStore.MediaColumns.DATE_TAKEN} IS NULL) OR (${MediaStore.MediaColumns.DATE_TAKEN} = 0)) AND (${MediaStore.MediaColumns.DATE_ADDED} >= ? AND ${MediaStore.MediaColumns.DATE_ADDED} < ?))" +
                " OR " +
                "(((${MediaStore.MediaColumns.DATE_TAKEN} IS NULL) OR (${MediaStore.MediaColumns.DATE_TAKEN} = 0)) AND ((${MediaStore.MediaColumns.DATE_ADDED} IS NULL) OR (${MediaStore.MediaColumns.DATE_ADDED} = 0)) AND (${MediaStore.MediaColumns.DATE_MODIFIED} >= ? AND ${MediaStore.MediaColumns.DATE_MODIFIED} < ?))" +
                ")"

        val selectionArgs = arrayOf(
            startMs.toString(),
            endMs.toString(),
            startSec.toString(),
            endSec.toString(),
            startSec.toString(),
            endSec.toString(),
        )

        val results = mutableListOf<CalendarDayMedia>()
        val resolver = context.contentResolver

        val queryImages = canQueryImages(context)
        val queryVideos = canQueryVideos(context)

        if (queryImages) {
            val imageUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DATE_MODIFIED,
            )
            runCatching {
                resolver.query(
                    imageUri,
                    projection,
                    selection,
                    selectionArgs,
                    "${MediaStore.Images.Media.DATE_TAKEN} DESC",
                )?.use { cursor ->
                    extractItems(cursor, imageUri, isVideo = false, results)
                }
            }
        }

        if (queryVideos) {
            val videoUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.DATE_TAKEN,
                MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.DATE_MODIFIED,
            )
            runCatching {
                resolver.query(
                    videoUri,
                    projection,
                    selection,
                    selectionArgs,
                    "${MediaStore.Video.Media.DATE_TAKEN} DESC",
                )?.use { cursor ->
                    extractItems(cursor, videoUri, isVideo = true, results)
                }
            }
        }

        // Sort descending by effective timestamp
        results.sortedByDescending { item ->
            resolveEffectiveTimeMs(item) ?: Long.MIN_VALUE
        }
    }

    /** Full accessible-library date index. Keeps counts only, never thumbnails or original data. */
    suspend fun loadMediaDateCounts(context: Context, query: LocalLibraryQuery = LocalLibraryQuery()): Map<LocalDate, Int> = withContext(Dispatchers.IO) {
        val counts = mutableMapOf<LocalDate, Int>()
        val columns = arrayOf(MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE)
        listOf(false, true).forEach { video ->
            if (if (video) query.videos && canQueryVideos(context) else (query.photos || query.motionPhotos) && canQueryImages(context)) {
                val uri = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                try {
                    context.contentResolver.query(uri, columns, libraryKindSelection(video, query), null, null)?.use { cursor ->
                        var count = 0
                        while (cursor.moveToNext()) {
                            if (++count % 256 == 0) kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            if (!video && query.photos != query.motionPhotos) {
                                val media = ContentUris.withAppendedId(uri, cursor.getLong(3))
                                val motion = inspectMotion(context, media, cursor.getLong(4), cursor.getLong(2))
                                if (if (motion) !query.motionPhotos else !query.photos) continue
                            }
                            val taken = cursor.getLong(0).takeIf { it > 0 }
                            val added = cursor.getLong(1).takeIf { it > 0 }?.times(1000L)
                            val modified = cursor.getLong(2).takeIf { it > 0 }?.times(1000L)
                            val time = (if (query.captureSort) taken ?: added ?: modified else added ?: taken ?: modified) ?: continue
                            val date = java.time.Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate()
                            counts[date] = (counts[date] ?: 0) + 1
                        }
                    }
                } catch (_: SecurityException) { }
            }
        }
        counts
    }

    suspend fun datesForUris(context: Context, uris: List<String>): Set<LocalDate> = withContext(Dispatchers.IO) {
        val dates = mutableSetOf<LocalDate>()
        val projection = arrayOf(MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DATE_MODIFIED)
        uris.forEach { raw ->
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            try {
                val source = Uri.parse(raw)
                val mediaUri = if (source.authority == MediaStore.AUTHORITY && !source.path.orEmpty().startsWith("/picker")) source
                    else if (Build.VERSION.SDK_INT >= 29) MediaStore.getMediaUri(context, source) else null
                val segments = mediaUri?.pathSegments.orEmpty()
                val isDeviceMedia = mediaUri?.authority == MediaStore.AUTHORITY &&
                    ((segments.size == 4 && segments[1] in setOf("images", "video") && segments[2] == "media") ||
                        (segments.size == 3 && segments[1] == "file"))
                if (!isDeviceMedia) return@forEach
                context.contentResolver.query(mediaUri!!, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val time = cursor.getLong(0).takeIf { it > 0 }
                            ?: cursor.getLong(1).takeIf { it > 0 }?.times(1000L)
                            ?: cursor.getLong(2).takeIf { it > 0 }?.times(1000L)
                        time?.let { dates += java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
                    }
                }
            } catch (_: Exception) { }
        }
        dates
    }

    suspend fun loadDatesMedia(context: Context, dates: Set<LocalDate>): List<CalendarDayMedia> = withContext(Dispatchers.IO) {
        dates.map(YearMonth::from).distinct().flatMap { month ->
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            loadMonthMedia(context, month).filter { resolveLocalDate(it) in dates }.map { item ->
                if (item.isVideo) item else item.copy(isMotionPhoto = inspectMotion(context, item.uri, item.byteLength, item.dateModifiedSec))
            }
        }.sortedByDescending { resolveEffectiveTimeMs(it) ?: 0L }
    }

    private fun libraryKindSelection(video: Boolean, query: LocalLibraryQuery): String? = buildList {
        if (video && query.durationMs != null) add("${MediaStore.Video.Media.DURATION} ${if (query.durationAtLeast) ">=" else "<="} ${query.durationMs}")

    }.takeIf { it.isNotEmpty() }?.joinToString(" AND ")

    /** A stable window of the newest matching metadata; decoding stays in visible cells. */
    suspend fun loadRecentMedia(context: Context, limit: Int, query: LocalLibraryQuery = LocalLibraryQuery(), day: LocalDate? = null): List<CalendarDayMedia> = withContext(Dispatchers.IO) {
        val result = mutableListOf<CalendarDayMedia>()
        listOf(false, true).forEach { video ->
            if (!(if (video) query.videos && canQueryVideos(context) else (query.photos || query.motionPhotos) && canQueryImages(context))) return@forEach
            val uri = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val columns = mutableListOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT)
            if (video) columns += MediaStore.Video.Media.DURATION
            val priority = if (query.captureSort) listOf(MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DATE_MODIFIED)
                else listOf(MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_MODIFIED)
            priority.forEachIndexed { index, column ->
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val selection = buildList {
                    priority.take(index).forEach { add("($it IS NULL OR $it <= 0)") }
                    add("$column > 0")
                    if (day != null) {
                        val start = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        val end = day.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        val unit = if (column == MediaStore.MediaColumns.DATE_TAKEN) 1L else 1000L
                        add("$column >= ${start / unit} AND $column < ${end / unit}")
                    }
                    if (video && query.durationMs != null) add("${MediaStore.Video.Media.DURATION} ${if (query.durationAtLeast) ">=" else "<="} ${query.durationMs}")

                }.joinToString(" AND ")
                val args = android.os.Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(column, MediaStore.MediaColumns._ID))
                    putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
                    if (video || query.photos == query.motionPhotos) putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                }
                try {
                    context.contentResolver.query(uri, columns.toTypedArray(), args, null)?.use { cursor -> extractItems(cursor, uri, video, result, maxItems = limit, motionContext = if (!video) context else null, motionQuery = query) }
                } catch (_: SecurityException) { }
            }
        }
        val visible = result.distinctBy { it.uri.toString() }.sortedWith(compareByDescending<CalendarDayMedia> {
            if (query.captureSort) resolveEffectiveTimeMs(it) ?: 0L else it.dateAddedSec?.times(1000L) ?: resolveEffectiveTimeMs(it) ?: 0L
        }.thenByDescending { it.uri.toString() }).take(limit)
        android.util.Log.d("LocalMotionPhoto", "visible=${visible.size} motion=${visible.count { it.isMotionPhoto }}")
        visible
    }

    private suspend fun extractItems(
        cursor: Cursor,
        contentUri: Uri,
        isVideo: Boolean,
        outList: MutableList<CalendarDayMedia>,
        maxItems: Int = Int.MAX_VALUE,
        motionContext: Context? = null,
        motionQuery: LocalLibraryQuery = LocalLibraryQuery(),
    ) {
        val idCol = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
        val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
        val takenCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
        val addedCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
        val modifiedCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
        val durationCol = if (isVideo) cursor.getColumnIndex(MediaStore.Video.Media.DURATION) else -1
        val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
        val widthCol = cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH)
        val heightCol = cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT)

        var extracted = 0
        var inspected = 0
        while (extracted < maxItems && cursor.moveToNext()) {
            if (++inspected % 16 == 0) kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val id = cursor.getLong(idCol)
            val uri = ContentUris.withAppendedId(contentUri, id)
            val displayName = if (nameCol >= 0 && !cursor.isNull(nameCol)) cursor.getString(nameCol) else null
            val taken = if (takenCol >= 0 && !cursor.isNull(takenCol)) cursor.getLong(takenCol).takeIf { it > 0 } else null
            val added = if (addedCol >= 0 && !cursor.isNull(addedCol)) cursor.getLong(addedCol).takeIf { it > 0 } else null
            val modified = if (modifiedCol >= 0 && !cursor.isNull(modifiedCol)) cursor.getLong(modifiedCol).takeIf { it > 0 } else null
            val duration = if (durationCol >= 0 && !cursor.isNull(durationCol)) cursor.getLong(durationCol) else null

            val size = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else null
            val motion = !isVideo && motionContext != null && inspectMotion(motionContext, uri, size, modified)
            if (!isVideo && motionContext != null && (if (motion) !motionQuery.motionPhotos else !motionQuery.photos)) continue
            extracted++
            outList.add(
                CalendarDayMedia(
                    uri = uri,
                    isVideo = isVideo,
                    durationMs = duration,
                    displayName = displayName,
                    dateTakenMs = taken,
                    dateAddedSec = added,
                    dateModifiedSec = modified,
                    byteLength = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else null,
                    width = if (widthCol >= 0 && !cursor.isNull(widthCol)) cursor.getInt(widthCol) else null,
                    height = if (heightCol >= 0 && !cursor.isNull(heightCol)) cursor.getInt(heightCol) else null,
                    isMotionPhoto = motion,
                )
            )
        }
    }

    private fun inspectMotion(context: Context, uri: Uri, size: Long?, modified: Long?): Boolean = try {
        LocalMotionPhotoReader.inspect(context, uri, size, modified) != null
    } catch (error: SecurityException) { throw error }
      catch (_: Exception) { false }

    fun resolveEffectiveTimeMs(item: CalendarDayMedia): Long? {
        return item.dateTakenMs
            ?: item.dateAddedSec?.times(1000L)
            ?: item.dateModifiedSec?.times(1000L)
    }

    fun resolveLocalDate(item: CalendarDayMedia, zoneId: ZoneId = ZoneId.systemDefault()): LocalDate? {
        val ms = resolveEffectiveTimeMs(item) ?: return null
        return java.time.Instant.ofEpochMilli(ms).atZone(zoneId).toLocalDate()
    }
}
