package com.armsone.nasfinder.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private enum class LocalPhotoLibraryMode(val title: String) {
    PHOTOS("사진"),
    CALENDAR("달력"),
}

/**
 * Local-only photo/video picker for "내 사진·영상". Preserves existing user edits and Google Photos system picker.
 * Device MediaStore photos and calendar share one URI basket; files use the toolbar action.
 * Google Photos keeps the existing native cloud-enabled photo picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalPhotoLibraryScreen(
    model: NasFinderViewModel,
    onBack: () -> Unit,
    onPreview: (Uri) -> Unit,
    onCreateTrailer: (List<Uri>) -> Unit,
    onMusicOverlay: ((List<Uri>) -> Unit)? = null,
    onCopyToPhoneHard: ((List<Uri>, destinationSubdirectory: String?, onProgress: (Int, Int) -> Unit, onComplete: (Int, Int) -> Unit) -> Unit)? = null,
    initialIntent: LocalMediaEditIntent = LocalMediaEditIntent.NONE,
    googlePhotosSource: Boolean = false,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var permissionState by remember { mutableStateOf(LocalPhotoLibraryCalendarRepository.checkPermissionState(context)) }
    var libraryRevision by remember { mutableStateOf(0) }
    var recentLimit by rememberSaveable { mutableStateOf(180) }
    var libraryQuery by rememberSaveable { mutableStateOf(LocalLibraryQuery()) }
    var previewUris by remember { mutableStateOf<List<String>>(emptyList()) }
    var previewIndex by remember { mutableStateOf(0) }
    val libraryScope = rememberCoroutineScope()
    var photoDateCounts by remember { mutableStateOf<Map<java.time.LocalDate, Int>>(emptyMap()) }
    var jumpItems by remember { mutableStateOf<List<LocalMediaItem>>(emptyList()) }
    var recentItems by remember { mutableStateOf<List<CalendarDayMedia>>(emptyList()) }
    var recentLoading by remember { mutableStateOf(false) }
    var libraryError by remember { mutableStateOf<String?>(null) }
    var motionPhotoSupported by remember { mutableStateOf<Boolean?>(null) }
    var libraryNotice by remember { mutableStateOf<String?>(null) }
    var showLibraryMenu by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionState = LocalPhotoLibraryCalendarRepository.checkPermissionState(context)
                libraryRevision++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val mediaPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionState = LocalPhotoLibraryCalendarRepository.checkPermissionState(context)
        libraryRevision++
    }
    fun requestMediaPermission() {
        mediaPermission.launch(when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO, android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
            else -> arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        })
    }
    LaunchedEffect(googlePhotosSource, permissionState, libraryRevision, recentLimit, libraryQuery) {
        if (googlePhotosSource) { photoDateCounts = emptyMap(); return@LaunchedEffect }
        recentLoading = true
        libraryError = null
        try {
            motionPhotoSupported = true
            recentItems = LocalPhotoLibraryCalendarRepository.loadRecentMedia(context, recentLimit, libraryQuery)
            photoDateCounts = LocalPhotoLibraryCalendarRepository.loadMediaDateCounts(context, libraryQuery)
        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (_: SecurityException) { recentItems = emptyList(); libraryError = "사진·영상 접근 권한을 확인해 주세요." }
        catch (_: Exception) { recentItems = emptyList(); libraryError = "사진·영상 목록을 불러오지 못했습니다. 더 보기에서 새로고침해 주세요." }
        finally { recentLoading = false }
    }

    val modeStateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    var currentMode by rememberSaveable { mutableStateOf(LocalPhotoLibraryMode.PHOTOS) }

    var pickedUris by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var items by remember { mutableStateOf<List<LocalMediaItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var loadedCount by remember { mutableStateOf(0) }
    var totalLoadingCount by remember { mutableStateOf(0) }
    var selected by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }

    var copying by remember { mutableStateOf(false) }
    var pendingCopyUris by remember { mutableStateOf<List<Uri>?>(null) }
    var copyCurrent by remember { mutableStateOf(0) }
    var copyTotal by remember { mutableStateOf(0) }

    val canonicalKeys = remember { mutableMapOf<String, String>() }
    // Canonicalize a picked URI string for dedup across Files/MediaStore/system picker sources.
    // Only uses the officially supported DocumentsProvider -> MediaStore mapping; opaque/cloud
    // provider URIs fall back to their raw string (no parsing of provider-internal IDs).
    fun canonicalKeyFor(uriString: String): String {
        canonicalKeys[uriString]?.let { return it }
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return uriString
        if (uri.authority == MediaStore.AUTHORITY && !uri.path.orEmpty().startsWith("/picker")) {
            canonicalKeys[uriString] = uriString
            return uriString
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val mapped = runCatching { MediaStore.getMediaUri(context, uri) }.getOrNull()
            if (mapped != null) return mapped.toString().also { canonicalKeys[uriString] = it }
        }
        canonicalKeys[uriString] = uriString
        return uriString
    }

    // Shared append used by all 3 pick sources (photo picker, file picker, calendar) to keep
    // ordering/selection logic consistent and dedup the same identity across sources.
    fun appendPickedUris(newUriStrings: List<String>) {
        if (newUriStrings.isEmpty()) return
        val existingKeys = pickedUris.map(::canonicalKeyFor).toSet()
        val seenNewKeys = mutableSetOf<String>()
        val toAdd = mutableListOf<String>()
        for (s in newUriStrings) {
            val key = canonicalKeyFor(s)
            if (key in existingKeys || !seenNewKeys.add(key)) continue
            toAdd.add(s)
        }
        if (toAdd.isNotEmpty()) pickedUris = pickedUris + toAdd
        val selectedKeys = selected.map(::canonicalKeyFor).toSet()
        selected = selected + newUriStrings.mapNotNull { value ->
            val key = canonicalKeyFor(value)
            if (key in selectedKeys) null else pickedUris.firstOrNull { canonicalKeyFor(it) == key }
        }.distinctBy(::canonicalKeyFor)
    }

    fun toggleSelected(uriString: String) {
        val key = canonicalKeyFor(uriString)
        if (selected.any { canonicalKeyFor(it) == key }) selected = selected.filterNot { canonicalKeyFor(it) == key }
        else appendPickedUris(listOf(uriString))
    }

    // Mode 1: Photo picker (Google Photos cloud provider supported, native system photo picker)
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 100),
    ) { uris ->
        // An empty result means the user cancelled; the previous selection must be preserved.
        if (uris.isNotEmpty()) {
            uris.forEach { uri -> runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
            appendPickedUris(uris.map(Uri::toString))
        }
    }

    // File addition: OpenMultipleDocuments file picker
    val documentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        // An empty result means cancel; preserve previous selections
        if (uris.isNotEmpty()) {
            for (uri in uris) {
                // Best-effort take persistable permission if supported
                runCatching {
                    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    context.contentResolver.takePersistableUriPermission(uri, flags)
                }
            }
            appendPickedUris(uris.map(Uri::toString))
        }
    }

    var hasAutoOpenedPicker by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(googlePhotosSource) {
        if (googlePhotosSource) currentMode = LocalPhotoLibraryMode.PHOTOS
        if (googlePhotosSource && !hasAutoOpenedPicker && pickedUris.isEmpty()) {
            hasAutoOpenedPicker = true
            picker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
            )
        }
    }

    LaunchedEffect(pickedUris) {
        val known = (items + recentItems.map { it.localMediaItem() } + jumpItems).filter { it.metadataLoaded }.associateBy { canonicalKeyFor(it.uri.toString()) }
        items = pickedUris.map { raw -> known[canonicalKeyFor(raw)]?.copy(uri = Uri.parse(raw)) ?: LocalMediaItem(Uri.parse(raw), null, false, null) }
        val pending = items.withIndex().filter { !it.value.metadataLoaded }
        loading = pending.isNotEmpty()
        try {
            val semaphore = Semaphore(4)
            coroutineScope {
                pending.forEach { (index, placeholder) -> launch {
                    val metadata = semaphore.withPermit { loadLocalMediaMetadata(context, placeholder.uri) }
                    items = items.toMutableList().also { if (index in it.indices) it[index] = metadata }
                } }
            }
        } finally { loading = false }
    }
    val selectedKeys = selected.map(::canonicalKeyFor).toSet()
    val deviceItems = remember(recentItems, items, jumpItems) {
        (recentItems.map { it.localMediaItem() } + items + jumpItems)
            .distinctBy { canonicalKeyFor(it.uri.toString()) }
    }

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            Column(Modifier.fillMaxWidth()) {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            when (initialIntent) {
                                LocalMediaEditIntent.TRAILER -> "예고편 만들기"
                                LocalMediaEditIntent.MUSIC -> "뮤직비디오"
                                LocalMediaEditIntent.NONE -> if (googlePhotosSource) "Google 포토" else "내 사진·영상"
                            },
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                        }
                    },
                    actions = {
                        androidx.compose.material3.TextButton(onClick = { documentPicker.launch(arrayOf("image/*", "video/*")) }, enabled = !copying) { Text("파일에서 추가") }
                        Box {
                            IconButton(onClick = { showLibraryMenu = true }, enabled = !copying) { Icon(Icons.Default.MoreVert, "더 보기") }
                            androidx.compose.material3.DropdownMenu(expanded = showLibraryMenu, onDismissRequest = { showLibraryMenu = false }) {
                                androidx.compose.material3.DropdownMenuItem(text = { Text("사진 접근 권한") }, onClick = { showLibraryMenu = false; requestMediaPermission() })
                                if (!googlePhotosSource) {
                                    androidx.compose.material3.DropdownMenuItem(text = { Text("새로고침") }, onClick = { showLibraryMenu = false; libraryRevision++ })
                                    if (recentItems.size >= recentLimit) androidx.compose.material3.DropdownMenuItem(text = { Text("이전 사진·영상 더 보기") }, onClick = { showLibraryMenu = false; recentLimit += 180 }, enabled = !recentLoading)
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(),
                )
                TabRow(
                    selectedTabIndex = currentMode.ordinal,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    LocalPhotoLibraryMode.values().forEach { mode ->
                        Tab(
                            selected = currentMode == mode,
                            onClick = { currentMode = mode },
                            text = { Text(if (googlePhotosSource) { if (mode == LocalPhotoLibraryMode.CALENDAR) "기기 달력" else "선택한 사진" } else mode.title, fontWeight = if (currentMode == mode) FontWeight.Bold else FontWeight.Normal) },
                        )
                    }
                }

            }
        },
        bottomBar = {
            Column(
                Modifier.fillMaxWidth().systemBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (copying) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("폰하드에 복사 중 · $copyCurrent/${copyTotal}개", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text("${selected.size}개 선택됨", style = MaterialTheme.typography.bodyMedium)
                val actionsEnabled = !loading && !copying && selected.isNotEmpty()
                fun selectedUris() = pickedUris.filter { it in selected.toSet() }.map(Uri::parse)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onCopyToPhoneHard != null) {
                        LocalMediaActionButton("폰하드 복사", actionsEnabled, Modifier.weight(1f)) {
                            val uris = selectedUris()
                            if (uris.isNotEmpty()) pendingCopyUris = uris.toList()
                        }
                    }
                    LocalMediaActionButton("예고편 제작", actionsEnabled, Modifier.weight(1f)) { onCreateTrailer(selectedUris()) }
                    if (onMusicOverlay != null) {
                        LocalMediaActionButton("뮤직비디오", actionsEnabled, Modifier.weight(1f)) { onMusicOverlay(selectedUris()) }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (currentMode) {
                LocalPhotoLibraryMode.PHOTOS -> {
                    Column(Modifier.fillMaxSize()) {
                        if (!googlePhotosSource) {
                            if (permissionState != CalendarMediaPermissionState.FULL_ACCESS) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (permissionState == CalendarMediaPermissionState.DENIED) "사진·영상 접근 권한이 필요합니다." else "허용한 사진·영상만 표시합니다.", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                                    if (permissionState == CalendarMediaPermissionState.DENIED) androidx.compose.material3.TextButton(onClick = { requestMediaPermission() }) { Text("권한 허용") }
                                }
                            }
                            libraryError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
                            libraryNotice?.let { Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 12.dp)) }
                        }
                    modeStateHolder.SaveableStateProvider("photos") {
                    LocalPhotoLibraryGrid(
                        media = if (googlePhotosSource) items else deviceItems,
                        query = libraryQuery,
                        motionPhotoSupported = motionPhotoSupported == true,
                        onQueryChanged = { jumpItems = emptyList(); libraryNotice = null; libraryQuery = it },
                        loading = if (googlePhotosSource) loading else recentLoading,
                        selectedKeys = selectedKeys,
                        canonicalKey = ::canonicalKeyFor,
                        onSetSelection = { uris, shouldSelect ->
                            if (shouldSelect) appendPickedUris(uris)
                            else {
                                val keys = uris.map(::canonicalKeyFor).toSet()
                                selected = selected.filterNot { canonicalKeyFor(it) in keys }
                            }
                        },
                        onToggle = ::toggleSelected,
                        onClearDeviceSelection = {
                            selected = selected.filterNot { raw ->
                                val key = canonicalKeyFor(raw)
                                key.startsWith("content://media/") && !Uri.parse(key).path.orEmpty().startsWith("/picker")
                            }
                        },
                        dateCounts = photoDateCounts,
                        onRequestDay = { day, completion ->
                            if (googlePhotosSource) {
                                completion(items.filter { item ->
                                    val time = if (libraryQuery.captureSort) item.captureTimeMs ?: item.addedTimeMs else item.addedTimeMs ?: item.captureTimeMs
                                    time?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() } == day
                                })
                            } else libraryScope.launch {
                                try {
                                    val loaded = LocalPhotoLibraryCalendarRepository.loadRecentMedia(context, Int.MAX_VALUE, libraryQuery, day).map { it.localMediaItem() }
                                    jumpItems = (jumpItems + loaded).distinctBy { canonicalKeyFor(it.uri.toString()) }
                                    completion(loaded)
                                } catch (error: kotlinx.coroutines.CancellationException) { throw error }
                                catch (_: Exception) { libraryError = "해당 날짜의 사진·영상을 불러오지 못했습니다."; completion(emptyList()) }
                            }
                        },
                        onPreview = { sequence, item ->
                            previewUris = sequence.map { it.uri.toString() }
                            previewIndex = sequence.indexOfFirst { it.uri == item.uri }.coerceAtLeast(0)
                        },
                        onLaunchPicker = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                        onLoadMore = if (!googlePhotosSource && recentItems.size >= recentLimit) ({ recentLimit += 180 }) else null,
                        copying = copying,
                    )
                    }
                    }
                }

                LocalPhotoLibraryMode.CALENDAR -> {
                    modeStateHolder.SaveableStateProvider("calendar") {
                    LocalPhotoLibraryCalendarScreen(
                        commonPickedUris = selected,
                        canonicalKey = ::canonicalKeyFor,
                        onToggleUri = ::toggleSelected,
                        onAddUris = { addedUris -> appendPickedUris(addedUris) },
                        onPreview = onPreview,
                        onPreviewItems = { sequence, uri ->
                            previewUris = sequence.map(Uri::toString)
                            previewIndex = sequence.indexOfFirst { it == uri }.coerceAtLeast(0)
                        },
                        onSwitchToPhotoTab = {
                            currentMode = LocalPhotoLibraryMode.PHOTOS
                        },
                    )
                    }
                }


            }
        }
    }
    if (previewUris.isNotEmpty()) {
        LocalMediaPreviewCard(
            uris = previewUris,
            index = previewIndex,
            selected = previewUris.getOrNull(previewIndex)?.let { canonicalKeyFor(it) in selectedKeys } == true,
            onToggleSelection = { previewUris.getOrNull(previewIndex)?.let(::toggleSelected) },
            onIndexChanged = { previewIndex = it },
            onDismiss = { previewUris = emptyList() },
        )
    }
    pendingCopyUris?.let { snapshot ->
        SelectPhoneHardDestinationDialog(
            title = "폰하드 복사 대상 폴더 선택",
            confirmLabel = "이 폴더에 복사",
            model = model,
            targets = emptyList(),
            onDismiss = { pendingCopyUris = null },
            onSelectDestination = { destination ->
                pendingCopyUris = null
                val copy = onCopyToPhoneHard
                if (copy != null && snapshot.isNotEmpty()) {
                    copying = true
                    copyCurrent = 0
                    copyTotal = snapshot.size
                    copy(snapshot, destination, { current, total -> copyCurrent = current; copyTotal = total }, { _, _ -> copying = false })
                }
            },
        )
    }
}

@Composable
private fun LocalMediaActionButton(label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(52.dp),
        shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp), textAlign = TextAlign.Center, minLines = 1, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

internal data class LocalMediaItem(
    val uri: Uri,
    val thumbnail: Bitmap?,
    val isVideo: Boolean,
    val durationMs: Long?,
    val displayName: String? = null,
    val byteLength: Long? = null,
    val dateText: String? = null,
    val captureTimeMs: Long? = null,
    val addedTimeMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val isMotionPhoto: Boolean = false,
    val metadataLoaded: Boolean = false,
)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun LocalMediaThumbnail(
    item: LocalMediaItem,
    isSelected: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit = onTap,
    selectionOrder: Int? = null,
) {
    val context = LocalContext.current
    var visibleItem by remember(item.uri) { mutableStateOf(item) }
    LaunchedEffect(item.uri) { visibleItem = loadCachedLocalMediaItem(context, item.uri) }
    val displayedItem = visibleItem
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
    ) {
        displayedItem.thumbnail?.let { bitmap ->
            SkinAwareThumbnailImage(
                bitmap = bitmap,
                cacheIdentity = "local-media:${item.uri}",
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp))
        }
        if (displayedItem.isMotionPhoto) Icon(Icons.Default.MotionPhotosOn, contentDescription = "모션 포토", modifier = Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = .6f), androidx.compose.foundation.shape.CircleShape).padding(3.dp).size(18.dp), tint = Color.White)
        if (displayedItem.isVideo) {
            Icon(
                Icons.Default.PlayCircle,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.align(Alignment.Center).size(28.dp),
            )
            displayedItem.durationMs?.let { durationMs ->
                Text(
                    formatLocalMediaDuration(durationMs),
                    maxLines = 1,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp)
                        .background(Color.Black.copy(alpha = .6f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
        Box(
            modifier = Modifier.align(Alignment.TopEnd).size(44.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (isSelected && selectionOrder != null) {
                Box(Modifier.size(24.dp).background(MaterialTheme.colorScheme.primary, CircleShape).semantics { contentDescription = "${selectionOrder}번째 선택, 선택 해제" }, contentAlignment = Alignment.Center) {
                    Text(selectionOrder.toString(), color = MaterialTheme.colorScheme.onPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            } else Icon(
                if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = if (isSelected) "선택 해제" else "선택",
                tint = Color.White,
                modifier = Modifier.size(22.dp).background(Color.Black.copy(alpha = .4f), CircleShape),
            )
        }
    }
}

private val localMediaItemCache = object : android.util.LruCache<String, LocalMediaItem>(12 * 1024 * 1024) {
    override fun sizeOf(key: String, value: LocalMediaItem): Int = value.thumbnail?.allocationByteCount ?: 256
}
private val thumbnailLoads = Semaphore(4)
internal suspend fun loadCachedLocalMediaItem(context: Context, uri: Uri): LocalMediaItem {
    localMediaItemCache.get(uri.toString())?.let { return it }
    return thumbnailLoads.withPermit {
        localMediaItemCache.get(uri.toString()) ?: loadLocalMediaItem(context, uri).also { localMediaItemCache.put(uri.toString(), it) }
    }
}
internal suspend fun loadLocalLibraryThumbnail(context: Context, uri: Uri): Bitmap? = loadCachedLocalMediaItem(context, uri).thumbnail

internal suspend fun loadLocalMediaMetadata(context: Context, uri: Uri): LocalMediaItem = loadLocalMediaItem(context, uri, includeThumbnail = false)

internal fun CalendarDayMedia.localMediaItem() = LocalMediaItem(uri, null, isVideo, durationMs, displayName, byteLength,
    captureTimeMs = LocalPhotoLibraryCalendarRepository.resolveEffectiveTimeMs(this), addedTimeMs = dateAddedSec?.times(1000L), width = width, height = height, isMotionPhoto = isMotionPhoto, metadataLoaded = true)

private suspend fun loadLocalMediaItem(context: Context, uri: Uri, includeThumbnail: Boolean = true): LocalMediaItem =
    withContext(Dispatchers.IO) {
        val mimeType = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        val isVideo = mimeType?.startsWith("video/") == true

        var displayName: String? = null
        var byteLength: Long? = null
        var dateText: String? = null
        var captureTimeMs: Long? = null
        var addedTimeMs: Long? = null
        var width: Int? = null
        var height: Int? = null
        var motionPhoto = false
        var modifiedTimeSec: Long? = null

        // Query openable columns or media columns
        runCatching {
            val projection = arrayOf(
                OpenableColumns.DISPLAY_NAME,
                OpenableColumns.SIZE,
            )
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameCol = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameCol >= 0 && !cursor.isNull(nameCol)) displayName = cursor.getString(nameCol)
                    if (sizeCol >= 0 && !cursor.isNull(sizeCol)) byteLength = cursor.getLong(sizeCol)
                }
            }
        }

        // Query date column if available via content resolver
        runCatching {
            val dateProjection = arrayOf(
                android.provider.MediaStore.MediaColumns.DATE_TAKEN,
                android.provider.MediaStore.MediaColumns.DATE_ADDED,
                android.provider.MediaStore.MediaColumns.DATE_MODIFIED,
            )
            context.contentResolver.query(uri, dateProjection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val takenCol = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.DATE_TAKEN)
                    val addedCol = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.DATE_ADDED)
                    val modifiedCol = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.DATE_MODIFIED)

                    val taken = if (takenCol >= 0 && !cursor.isNull(takenCol)) cursor.getLong(takenCol).takeIf { it > 0 } else null
                    val added = if (addedCol >= 0 && !cursor.isNull(addedCol)) cursor.getLong(addedCol).takeIf { it > 0 } else null
                    val modified = if (modifiedCol >= 0 && !cursor.isNull(modifiedCol)) cursor.getLong(modifiedCol).takeIf { it > 0 } else null

                    modifiedTimeSec = modified
                    val epochMs = taken ?: added?.times(1000L) ?: modified?.times(1000L)
                    captureTimeMs = epochMs
                    addedTimeMs = added?.times(1000L) ?: epochMs
                    if (epochMs != null) {
                        val localDateTime = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDateTime()
                        dateText = localDateTime.format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))
                    }
                }
            }
        }

        if (dateText == null) {
            dateText = "날짜 정보 없음"
        }

        runCatching {
            val columns = mutableListOf(MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT)
            context.contentResolver.query(uri, columns.toTypedArray(), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    width = cursor.getInt(0).takeIf { it > 0 }; height = cursor.getInt(1).takeIf { it > 0 }
                }
            }
        }
        if (!isVideo) motionPhoto = runCatching { LocalMotionPhotoReader.inspect(context, uri, byteLength, modifiedTimeSec) != null }.getOrDefault(false)
        LocalMediaItem(
            uri = uri,
            thumbnail = if (includeThumbnail) loadLocalMediaThumbnail(context, uri, isVideo) else null,
            isVideo = isVideo,
            durationMs = if (isVideo) loadLocalMediaDuration(context, uri) else null,
            displayName = displayName,
            byteLength = byteLength,
            dateText = dateText,
            captureTimeMs = captureTimeMs, addedTimeMs = addedTimeMs, width = width, height = height, isMotionPhoto = motionPhoto, metadataLoaded = true,
        )
    }

private fun loadLocalMediaThumbnail(context: Context, uri: Uri, isVideo: Boolean): Bitmap? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        // Platform thumbnails never read the full-resolution media into memory.
        context.contentResolver.loadThumbnail(uri, Size(320, 320), null)
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

private fun loadLocalMediaDuration(context: Context, uri: Uri): Long? = runCatching {
    MediaMetadataRetriever().run {
        try {
            setDataSource(context, uri)
            extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } finally {
            release()
        }
    }
}.getOrNull()

private fun formatLocalMediaDuration(durationMs: Long): String {
    val seconds = (durationMs / 1_000.0).roundToInt().coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

private fun formatLocalFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var size = bytes.toDouble()
    var index = -1
    do {
        size /= 1024
        index++
    } while (size >= 1024 && index < units.lastIndex)
    return "%.1f %s".format(size, units[index])
}
