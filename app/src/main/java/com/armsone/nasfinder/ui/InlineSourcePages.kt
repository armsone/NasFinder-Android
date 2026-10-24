package com.armsone.nasfinder.ui

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ext.SdkExtensions
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.photopicker.compose.ExperimentalPhotoPickerComposeApi
import androidx.photopicker.compose.EmbeddedPhotoPicker
import androidx.photopicker.compose.rememberEmbeddedPhotoPickerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Official embedded photo picker needs API 34 plus SDK extension 15; anything else uses the explicit fallback button. */
internal fun embeddedPhotoPickerSupported(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
        runCatching { SdkExtensions.getExtensionVersion(Build.VERSION_CODES.UPSIDE_DOWN_CAKE) >= 15 }.getOrDefault(false)

/**
 * Google Photos page: the system-rendered picker (device + cloud library) is embedded inline above the shared basket.
 * Grants append to the basket in selection order, revokes remove them; basket removals are mirrored back via deselectUris.
 */
@Composable
internal fun InlineGooglePhotosPage(
    basket: List<String>,
    canonicalKey: (String) -> String,
    active: Boolean,
    onGranted: (List<Uri>) -> Unit,
    onRevoked: (List<Uri>) -> Unit,
    onLaunchFallback: () -> Unit,
) {
    var failure by rememberSaveable { mutableStateOf<String?>(null) }
    var retry by rememberSaveable { mutableStateOf(0) }
    val supported = remember { embeddedPhotoPickerSupported() }
    if (!supported || failure != null) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(
                if (!supported) "이 기기는 화면 안 Google 포토 선택을 지원하지 않습니다." else "Google 포토 선택 화면을 불러오지 못했습니다.",
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
            )
            Text("담은 항목은 그대로 유지됩니다. 시스템 선택 화면에서 고른 항목은 같은 담기 목록에 추가됩니다.", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 12.dp))
            Button(onClick = onLaunchFallback) { Text("시스템 선택 화면 열기") }
            if (supported) OutlinedButton(onClick = { failure = null; retry++ }, modifier = Modifier.padding(top = 8.dp)) { Text("다시 시도") }
        }
        return
    }
    if (!active) {
        // Offscreen pages never host the remote surface; it is created only while this page is settled.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    EmbeddedGooglePicker(basket, canonicalKey, retry, onGranted, onRevoked) { failure = it.message ?: it.javaClass.simpleName }
}

@OptIn(ExperimentalPhotoPickerComposeApi::class)
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
@Composable
private fun EmbeddedGooglePicker(
    basket: List<String>,
    canonicalKey: (String) -> String,
    retry: Int,
    onGranted: (List<Uri>) -> Unit,
    onRevoked: (List<Uri>) -> Unit,
    onError: (Throwable) -> Unit,
) {
    val preselected = remember(retry) {
        basket.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
            .filter { it.authority == MediaStore.AUTHORITY && it.path.orEmpty().startsWith("/picker") }.toSet()
    }
    val state = rememberEmbeddedPhotoPickerState(
        retry,
        initialExpandedValue = true,
        initialMediaSelection = preselected,
        onSessionError = onError,
        onUriPermissionGranted = onGranted,
        onUriPermissionRevoked = onRevoked,
    )
    LaunchedEffect(state) { state.isExpanded = true }
    // Basket X removal -> deselect inside the picker. Items still present (by raw or canonical identity) are kept.
    LaunchedEffect(basket, state.selectedMedia) {
        val raw = basket.toSet()
        val keys = basket.map(canonicalKey).toSet()
        val stale = state.selectedMedia.filter { it.toString() !in raw && canonicalKey(it.toString()) !in keys }
        if (stale.isNotEmpty()) runCatching { state.deselectUris(stale) }
    }
    EmbeddedPhotoPicker(state = state, modifier = Modifier.fillMaxSize())
}

private data class DeviceFolder(val id: Long, val name: String, val count: Int, val cover: Uri, val coverIsVideo: Boolean)
private data class DeviceFolderMedia(val uri: Uri, val isVideo: Boolean)

private suspend fun loadDeviceFolders(context: Context): List<DeviceFolder> = withContext(Dispatchers.IO) {
    class Acc(val name: String, var count: Int, val cover: Uri, val video: Boolean, val added: Long)
    val folders = linkedMapOf<Long, Acc>()
    listOf(false, true).forEach { video ->
        if (!(if (video) LocalPhotoLibraryCalendarRepository.canQueryVideos(context) else LocalPhotoLibraryCalendarRepository.canQueryImages(context))) return@forEach
        val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.BUCKET_ID, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME, MediaStore.MediaColumns.DATE_ADDED)
        context.contentResolver.query(base, projection, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { cursor ->
            while (cursor.moveToNext()) {
                val bucket = cursor.getLong(1)
                val added = cursor.getLong(3)
                val existing = folders[bucket]
                if (existing == null) folders[bucket] = Acc(cursor.getString(2).orEmpty().ifBlank { "기타" }, 1, ContentUris.withAppendedId(base, cursor.getLong(0)), video, added)
                else {
                    existing.count++
                    if (added > existing.added) folders[bucket] = Acc(existing.name, existing.count, ContentUris.withAppendedId(base, cursor.getLong(0)), video, added)
                }
            }
        }
    }
    folders.map { (id, acc) -> DeviceFolder(id, acc.name, acc.count, acc.cover, acc.video) }.sortedBy { it.name.lowercase() }
}

private suspend fun loadDeviceFolderMedia(context: Context, bucketId: Long): List<DeviceFolderMedia> = withContext(Dispatchers.IO) {
    val result = mutableListOf<Pair<Long, DeviceFolderMedia>>()
    listOf(false, true).forEach { video ->
        if (!(if (video) LocalPhotoLibraryCalendarRepository.canQueryVideos(context) else LocalPhotoLibraryCalendarRepository.canQueryImages(context))) return@forEach
        val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED)
        context.contentResolver.query(base, projection, "${MediaStore.MediaColumns.BUCKET_ID} = ?", arrayOf(bucketId.toString()), "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { cursor ->
            while (cursor.moveToNext()) result += cursor.getLong(1) to DeviceFolderMedia(ContentUris.withAppendedId(base, cursor.getLong(0)), video)
        }
    }
    result.sortedByDescending { it.first }.map { it.second }
}

/** Files page: authorized device photos/videos grouped by folder (MediaStore bucket), thumbnails load lazily per cell. */
@Composable
internal fun DeviceFolderBrowserPage(
    folderId: Long?,
    onFolderChange: (Long?) -> Unit,
    selectedKeys: Set<String>,
    canonicalKey: (String) -> String,
    selectionOrder: (String) -> Int?,
    revision: Int,
    onToggle: (String) -> Unit,
    onLaunchDocumentPicker: () -> Unit,
    enabled: Boolean,
) {
    val context = LocalContext.current
    var retry by rememberSaveable { mutableStateOf(0) }
    var folderError by remember { mutableStateOf(false) }
    var mediaError by remember { mutableStateOf(false) }
    val folders by produceState<List<DeviceFolder>?>(null, revision, retry) {
        folderError = false
        value = try {
            loadDeviceFolders(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            folderError = true
            emptyList()
        }
    }
    val media by produceState<List<DeviceFolderMedia>?>(null, folderId, revision, retry) {
        value = null
        mediaError = false
        value = if (folderId == null) emptyList() else try {
            loadDeviceFolderMedia(context, folderId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mediaError = true
            emptyList()
        }
    }
    val folderName = folders?.firstOrNull { it.id == folderId }?.name
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (folderId != null) TextButton(onClick = { onFolderChange(null) }) { Text("상위 폴더") }
            Text(folderName ?: "기기 폴더", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = onLaunchDocumentPicker, enabled = enabled) { Text("다른 저장소에서 가져오기") }
        }
        val list = folders
        when {
            list == null || (folderId != null && media == null) -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            (folderId == null && folderError) || (folderId != null && mediaError) -> Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("기기 미디어 목록을 불러오지 못했습니다. 담은 항목은 그대로 유지됩니다.", textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
                Button(onClick = { retry++ }, modifier = Modifier.padding(top = 12.dp)) { Text("다시 시도") }
                OutlinedButton(onClick = onLaunchDocumentPicker, enabled = enabled, modifier = Modifier.padding(top = 8.dp)) { Text("다른 저장소에서 가져오기") }
            }
            folderId == null && list.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("표시할 미디어 폴더가 없습니다. 사진 접근 권한을 확인하거나 '다른 저장소에서 가져오기'를 사용하세요.", textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
            }
            folderId == null -> LazyVerticalGrid(GridCells.Adaptive(110.dp), Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(list, key = { it.id }) { folder ->
                    Column(Modifier.clickable { onFolderChange(folder.id) }) {
                        Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                            Box(Modifier.fillMaxSize()) { LocalMediaThumbnail(LocalMediaItem(folder.cover, null, folder.coverIsVideo, null), false, { onFolderChange(folder.id) }, selectionOrder = null, showSelection = false) }
                            Icon(Icons.Default.Folder, null, Modifier.align(Alignment.BottomEnd).padding(4.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        Text("${folder.name} (${folder.count})", style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.heightIn(min = 48.dp).padding(top = 4.dp))
                    }
                }
            }
            else -> {
                val folderMedia = media.orEmpty()
                if (folderMedia.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("이 폴더에 미디어가 없습니다.", style = MaterialTheme.typography.bodySmall) }
                else LazyVerticalGrid(GridCells.Adaptive(96.dp), Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(folderMedia, key = { it.uri.toString() }) { entry ->
                        val raw = entry.uri.toString()
                        val isSelected = canonicalKey(raw) in selectedKeys
                        LocalMediaThumbnail(LocalMediaItem(entry.uri, null, entry.isVideo, null), isSelected, { onToggle(raw) }, selectionOrder = if (isSelected) selectionOrder(raw) else null)
                    }
                }
            }
        }
    }
}
