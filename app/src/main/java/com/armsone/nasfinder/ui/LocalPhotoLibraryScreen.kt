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
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import com.armsone.nasfinder.ui.theme.LocalNasFinderTheme
import com.armsone.nasfinder.ui.theme.folderColor
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
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


/**
 * Local-only photo/video picker for "내 사진·영상". Preserves existing user edits and Google Photos system picker.
 * Device MediaStore photos and calendar share one URI basket; files use the toolbar action.
 * Google Photos keeps the existing native cloud-enabled photo picker.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
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
    var sourceRevision by rememberSaveable { mutableStateOf(0) }
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
                sourceRevision++
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

    // PLUS menu sources: every source returns into this same basket and never clears it.
    val appState by model.state.collectAsStateWithLifecycle()
    // Direct source pages (like 사진/달력): swipe or tap tabs; the basket below is shared by every page.
    val pages = remember(initialIntent, googlePhotosSource, appState.googlePhotosVisible) {
        if (initialIntent == LocalMediaEditIntent.NONE || googlePhotosSource) listOf("photos" to "사진", "calendar" to "달력")
        else buildList {
            add("photos" to "사진"); add("calendar" to "달력"); add("phonehard" to "폰하드"); add("favorites" to "즐겨찾기")
            add("network" to "NAS·네트워크"); add("files" to "파일")
            if (appState.googlePhotosVisible) add("google" to "Google 포토")
        }
    }
    var pageKey by rememberSaveable { mutableStateOf("photos") }
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(initialPage = pages.indexOfFirst { it.first == pageKey }.coerceAtLeast(0)) { pages.size }
    LaunchedEffect(pagerState.currentPage, pages) { pages.getOrNull(pagerState.currentPage)?.let { pageKey = it.first } }
    LaunchedEffect(pagerState.settledPage) { sourceRevision++ }
    fun goToPage(key: String) { val index = pages.indexOfFirst { it.first == key }; if (index >= 0) libraryScope.launch { pagerState.animateScrollToPage(index) } }
    var phoneHardPath by rememberSaveable { mutableStateOf<String?>(null) }
    var filesFolderId by rememberSaveable { mutableStateOf<Long?>(null) }
    val chosenListSaver = androidx.compose.runtime.saveable.listSaver<androidx.compose.runtime.snapshots.SnapshotStateList<String>, String>(
        save = { it.toList() },
        restore = { saved -> mutableStateListOf<String>().apply { addAll(saved) } }
    )
    val chosenPhoneHard = rememberSaveable(saver = chosenListSaver) { mutableStateListOf<String>() }
    val chosenFavorites = rememberSaveable(saver = chosenListSaver) { mutableStateListOf<String>() }
    val returnScreen = Screen.LocalPhotoLibrary(initialIntent, googlePhotosSource)
    LaunchedEffect(appState.mediaPickDelivery) {
        val delivery = appState.mediaPickDelivery ?: return@LaunchedEffect
        appendPickedUris(delivery.uris)
        libraryNotice = delivery.message
        model.consumeMediaPickDelivery(delivery.id)
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

    val handleBack: () -> Unit = {
        if (pageKey == "phonehard" && phoneHardPath != null) phoneHardPath = phoneHardParent(phoneHardPath)
        else if (pageKey == "files" && filesFolderId != null) filesFolderId = null
        else if (pageKey != "photos" && pages.any { it.first == "photos" }) goToPage("photos")
        else onBack()
    }

    BackHandler(onBack = handleBack)

    Scaffold(
        topBar = {
            Column(Modifier.fillMaxWidth()) {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            when (initialIntent) {
                                LocalMediaEditIntent.TRAILER -> "영상 만들기"
                                LocalMediaEditIntent.MUSIC -> "뮤직비디오"
                                LocalMediaEditIntent.NONE -> if (googlePhotosSource) "Google 포토" else "내 사진·영상"
                            },
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = handleBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                        }
                    },
                    actions = {
                        if (initialIntent == LocalMediaEditIntent.NONE) {
                            androidx.compose.material3.TextButton(onClick = { documentPicker.launch(arrayOf("image/*", "video/*")) }, enabled = !copying) { Text("파일에서 추가") }
                        }
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
                if (com.armsone.nasfinder.ui.theme.usesUnifiedLayout(com.armsone.nasfinder.ui.theme.LocalNasFinderTheme.current)) {
                    // Approved 03-photos: compact photo-first filter pills (min 36, corner 8) over the same existing pages.
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        pages.forEachIndexed { index, (key, title) ->
                            val active = pagerState.currentPage == index
                            Box(
                                Modifier.heightIn(min = 36.dp).widthIn(min = 64.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (active) com.armsone.nasfinder.ui.theme.themePalette().selected else MaterialTheme.colorScheme.surfaceVariant)
                                    .selectable(selected = active, role = androidx.compose.ui.semantics.Role.Tab, onClick = { goToPage(key) })
                                    .padding(horizontal = 14.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    if (googlePhotosSource) { if (key == "calendar") "기기 달력" else "선택한 사진" } else title,
                                    maxLines = 1,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp,
                                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                } else androidx.compose.material3.ScrollableTabRow(
                    selectedTabIndex = pagerState.currentPage.coerceIn(0, pages.lastIndex),
                    modifier = Modifier.fillMaxWidth(),
                    edgePadding = 8.dp,
                ) {
                    pages.forEachIndexed { index, (key, title) ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = { goToPage(key) },
                            text = { Text(if (googlePhotosSource) { if (key == "calendar") "기기 달력" else "선택한 사진" } else title, maxLines = 1, fontWeight = if (pagerState.currentPage == index) FontWeight.SemiBold else FontWeight.Normal) },
                        )
                    }
                }
            }
        },
        bottomBar = {
            if (selected.isNotEmpty() || copying) {
                Column(
                    Modifier.fillMaxWidth().systemBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (copying) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text("폰하드에 복사 중 · $copyCurrent/${copyTotal}개", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    BasketTray(
                        selected = selected,
                        items = deviceItems,
                        enabled = !copying,
                        onRemove = { raw -> val key = canonicalKeyFor(raw); selected = selected.filterNot { canonicalKeyFor(it) == key } },
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "선택됨 ${selected.size}개",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                        Spacer(Modifier.weight(1f))
                        val actionsEnabled = !loading && !copying && selected.isNotEmpty()
                        fun selectedUris() = selected.map(Uri::parse)
                        if (onCopyToPhoneHard != null) {
                            LocalMediaActionButton("폰하드 복사", actionsEnabled, Modifier, isSecondary = true) {
                                val uris = selectedUris()
                                if (uris.isNotEmpty()) pendingCopyUris = uris.toList()
                            }
                        }
                        LocalMediaActionButton("영상 편집", actionsEnabled, Modifier, isSecondary = false) {
                            // Unified entry starts as a trailer; the editor switches modes inline.
                            // An explicit music route keeps its original music-video start.
                            if (initialIntent == LocalMediaEditIntent.MUSIC && onMusicOverlay != null) onMusicOverlay(selectedUris())
                            else onCreateTrailer(selectedUris())
                        }
                    }
                }
            }
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val isWide = maxWidth >= 840.dp
            val sidePaneWidth = 260.dp
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    androidx.compose.foundation.pager.HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                key = { pages.getOrNull(it)?.first ?: it },
                beyondViewportPageCount = 1,
            ) { pageIndex ->
            // Neighbor pages stay composed (session/basket/path state) but must not take keyboard/D-pad focus.
            // canFocus only reaches the nearest child focus target (e.g. a lazy list group) and not its items,
            // so each page is an explicit focus group whose entry is cancelled while it is not the current page.
            Box(
                Modifier.fillMaxSize()
                    .focusProperties { if (pagerState.currentPage != pageIndex) enter = { FocusRequester.Cancel } }
                    .focusGroup(),
            ) {
            when (pages.getOrNull(pageIndex)?.first) {
                "photos" -> {
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

                "calendar" -> {
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
                            goToPage("photos")
                        },
                    )
                    }
                }

                "phonehard", "favorites", "network" -> MediaSourcePageContent(
                    model = model,
                    kind = pages[pageIndex].first,
                    phoneHardPath = phoneHardPath,
                    chosenPhoneHard = chosenPhoneHard,
                    chosenFavorites = chosenFavorites,
                    returnScreen = returnScreen,
                    revision = sourceRevision,
                    onOpenPhoneHard = { path -> phoneHardPath = path; goToPage("phonehard") },
                    onCommitUris = { uris -> appendPickedUris(uris) },
                )

                "files" -> DeviceFolderBrowserPage(
                    folderId = filesFolderId,
                    onFolderChange = { filesFolderId = it },
                    selectedKeys = selectedKeys,
                    canonicalKey = ::canonicalKeyFor,
                    selectionOrder = { raw -> val key = canonicalKeyFor(raw); selected.indexOfFirst { canonicalKeyFor(it) == key }.takeIf { it >= 0 }?.plus(1) },
                    revision = libraryRevision,
                    onToggle = ::toggleSelected,
                    onLaunchDocumentPicker = { documentPicker.launch(arrayOf("image/*", "video/*")) },
                    enabled = !copying,
                )

                "google" -> InlineGooglePhotosPage(
                    basket = selected,
                    canonicalKey = ::canonicalKeyFor,
                    active = pagerState.settledPage == pageIndex && previewUris.isEmpty(),
                    onGranted = { uris ->
                        uris.forEach { uri -> runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
                        appendPickedUris(uris.map(Uri::toString))
                    },
                    onRevoked = { uris ->
                        val revokedKeys = uris.map { canonicalKeyFor(it.toString()) }.toSet()
                        selected = selected.filterNot { canonicalKeyFor(it) in revokedKeys }
                    },
                        onLaunchFallback = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                    )
                }
            }
        }
    }
                if (isWide && (selected.isNotEmpty() || copying)) {
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Column(
                        Modifier.width(sidePaneWidth).fillMaxHeight().background(MaterialTheme.colorScheme.surface).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "선택한 미디어 (${selected.size}개)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (copying) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text("복사 중 · $copyCurrent/${copyTotal}개", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            BasketTray(
                                selected = selected,
                                items = deviceItems,
                                enabled = !copying,
                                onRemove = { raw -> val key = canonicalKeyFor(raw); selected = selected.filterNot { canonicalKeyFor(it) == key } },
                            )
                        }
                        val actionsEnabled = !loading && !copying && selected.isNotEmpty()
                        fun selectedUris() = selected.map(Uri::parse)
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            LocalMediaActionButton("영상 편집", actionsEnabled, Modifier.fillMaxWidth(), isSecondary = false) {
                                if (initialIntent == LocalMediaEditIntent.MUSIC && onMusicOverlay != null) onMusicOverlay(selectedUris())
                                else onCreateTrailer(selectedUris())
                            }
                            if (onCopyToPhoneHard != null) {
                                LocalMediaActionButton("폰하드 복사", actionsEnabled, Modifier.fillMaxWidth(), isSecondary = true) {
                                    val uris = selectedUris()
                                    if (uris.isNotEmpty()) pendingCopyUris = uris.toList()
                                }
                            }
                        }
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
    appState.mediaPickStaging?.let { staging ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            title = { Text("가져오는 중") },
            text = { Text(staging.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = model::cancelMediaPickStaging) { Text("취소") } },
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
                    copy(snapshot, destination, { current, total -> copyCurrent = current; copyTotal = total }, { _, _ -> copying = false; sourceRevision++; libraryRevision++ })
                }
            },
        )
    }
}

/** Shared basket: the same ordered `selected` list the footer actions use; X removes only from the basket. */
@Composable
private fun BasketTray(selected: List<String>, items: List<LocalMediaItem>, enabled: Boolean, onRemove: (String) -> Unit) {
    if (selected.isEmpty()) return
    val context = LocalContext.current
    androidx.compose.foundation.lazy.LazyRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(selected, key = { it }) { raw ->
            val uri = remember(raw) { Uri.parse(raw) }
            val known = items.firstOrNull { it.uri.toString() == raw }
            val loaded by produceState(known, raw) { value = loadCachedLocalMediaItem(context, uri) }
            val item = loaded ?: known
            val name = item?.displayName ?: uri.lastPathSegment.orEmpty()
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                item?.thumbnail?.let { bitmap ->
                    SkinAwareThumbnailImage(bitmap = bitmap, cacheIdentity = "local-media:$raw", contentDescription = name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                if (item?.isVideo == true) {
                    Icon(Icons.Default.PlayCircle, null, Modifier.align(Alignment.Center).size(20.dp), tint = Color.White)
                    item.durationMs?.let { Text(formatLocalMediaDuration(it), color = Color.White, fontSize = 9.sp, maxLines = 1, modifier = Modifier.align(Alignment.BottomStart).padding(2.dp).background(Color.Black.copy(alpha = .6f), RoundedCornerShape(3.dp)).padding(horizontal = 2.dp)) }
                }
                Box(
                    Modifier.align(Alignment.TopEnd).size(48.dp).clickable(enabled = enabled) { onRemove(raw) }.semantics { contentDescription = "$name 담기 목록에서 제거" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Close, null, Modifier.size(16.dp).background(Color.Black.copy(alpha = .6f), CircleShape), tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun LocalMediaActionButton(label: String, enabled: Boolean, modifier: Modifier, isSecondary: Boolean = false, onClick: () -> Unit) {
    if (isSecondary) {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.heightIn(min = if (com.armsone.nasfinder.ui.theme.usesUnifiedLayout(com.armsone.nasfinder.ui.theme.LocalNasFinderTheme.current)) 44.dp else 32.dp),
            shape = MaterialTheme.shapes.medium,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 7.dp),
        ) {
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, minLines = 1, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else {
        Button(
            onClick = onClick,
            enabled = enabled,
            // Porcelain (03-photos proposal): action min 44; other themes keep 32.
            modifier = modifier.heightIn(min = if (com.armsone.nasfinder.ui.theme.usesUnifiedLayout(com.armsone.nasfinder.ui.theme.LocalNasFinderTheme.current)) 44.dp else 32.dp),
            shape = MaterialTheme.shapes.medium,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 7.dp),
        ) {
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, minLines = 1, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
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
    showSelection: Boolean = true,
) {
    val context = LocalContext.current
    var visibleItem by remember(item.uri) { mutableStateOf(item) }
    LaunchedEffect(item.uri) { visibleItem = loadCachedLocalMediaItem(context, item.uri) }
    val displayedItem = visibleItem
    // iOS NasFinderPhotoGrid cell: corner 8; selected = graphite(folderBlue)×.16 cell fill, image scaled 0.86 with corner 9
    // under a ×.18 overlay and 2.4pt border, and an 18pt check circle at the bottom-right (−5 / −6).
    val selectionTint = folderColor(LocalNasFinderTheme.current)
    val markSelected = showSelection && isSelected
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(if (markSelected) selectionTint.copy(alpha = .16f) else MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
    ) {
        val imageModifier = if (markSelected) Modifier.fillMaxSize().graphicsLayer { scaleX = .86f; scaleY = .86f }.clip(RoundedCornerShape(9.dp)) else Modifier.fillMaxSize()
        displayedItem.thumbnail?.let { bitmap ->
            SkinAwareThumbnailImage(
                bitmap = bitmap,
                cacheIdentity = "local-media:${item.uri}",
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = imageModifier,
            )
        } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp))
        }
        if (markSelected) Box(
            Modifier.fillMaxSize().graphicsLayer { scaleX = .86f; scaleY = .86f }
                .background(selectionTint.copy(alpha = .18f), RoundedCornerShape(9.dp))
                .border(2.4.dp, selectionTint, RoundedCornerShape(9.dp)),
        )
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
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp)
                        .background(Color.Black.copy(alpha = .6f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
        if (showSelection) Box(
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 5.dp).size(18.dp)
                .background(if (isSelected) selectionTint else Color.Black.copy(alpha = .4f), CircleShape)
                .semantics {
                    contentDescription = if (isSelected && selectionOrder != null) "${selectionOrder}번째 선택, 선택 해제" else if (isSelected) "선택 해제" else "선택"
                },
            contentAlignment = Alignment.Center,
        ) {
            // The basket order number stays (Android basket); otherwise the iOS ✓ / ○ marks.
            Text(
                if (isSelected) selectionOrder?.toString() ?: "✓" else "○",
                color = Color.White,
                fontSize = if (isSelected && selectionOrder != null && selectionOrder > 9) 9.sp else 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
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
        val isVideo = mimeType?.startsWith("video/") == true ||
            (mimeType == null && uri.scheme == "file" && uri.lastPathSegment?.let { com.armsone.nasfinder.model.RemoteFileItem(it, it, it, false).isVideo } == true)

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

        if (uri.scheme == "file") {
            // Own staged/PhoneHard copies are plain files without a content provider.
            val file = uri.path?.let { java.io.File(it) }
            if (displayName == null) displayName = uri.lastPathSegment
            if (byteLength == null) byteLength = file?.length()?.takeIf { it > 0 }
            if (captureTimeMs == null) file?.lastModified()?.takeIf { it > 0 }?.let { epochMs ->
                captureTimeMs = epochMs
                addedTimeMs = epochMs
                dateText = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))
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
    } else null
}.getOrNull() ?: runCatching {
    if (isVideo) {
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

private sealed interface MediaSourcePage {
    data object Favorites : MediaSourcePage
    data object Network : MediaSourcePage
    data class PhoneHard(val path: String?) : MediaSourcePage
}

private fun phoneHardParent(path: String?): String? = path?.trim('/')?.substringBeforeLast('/', "")?.takeIf { it.isNotEmpty() }

private enum class MediaSourceViewMode(val title: String) {
    DETAILS("자세히"),
    THUMBNAIL("썸네일"),
    POSTER("포스터"),
}

@Composable
private fun MediaSourceViewModeMenu(
    mode: MediaSourceViewMode,
    onSelectMode: (MediaSourceViewMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.FilterList, contentDescription = "보기 모드")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MediaSourceViewMode.entries.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(entry.title) },
                    leadingIcon = {
                        val icon = when (entry) {
                            MediaSourceViewMode.DETAILS -> Icons.Default.List
                            MediaSourceViewMode.THUMBNAIL -> Icons.Default.GridView
                            MediaSourceViewMode.POSTER -> Icons.Default.GridOn
                        }
                        Icon(icon, null)
                    },
                    trailingIcon = {
                        if (mode == entry) Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    },
                    onClick = {
                        expanded = false
                        onSelectMode(entry)
                    },
                )
            }
        }
    }
}

/** Embedded source page: every selection is explicit; only chosen items return to the same shared basket. */
@Composable
private fun MediaSourcePageContent(
    model: NasFinderViewModel,
    kind: String,
    phoneHardPath: String?,
    chosenPhoneHard: androidx.compose.runtime.snapshots.SnapshotStateList<String>,
    chosenFavorites: androidx.compose.runtime.snapshots.SnapshotStateList<String>,
    returnScreen: Screen.LocalPhotoLibrary,
    revision: Int = 0,
    onOpenPhoneHard: (String?) -> Unit,
    onCommitUris: (List<String>) -> Unit,
) {
    val context = LocalContext.current
    val state by model.state.collectAsStateWithLifecycle()
    var viewMode by rememberSaveable { mutableStateOf(MediaSourceViewMode.THUMBNAIL) }
    val page: MediaSourcePage = when (kind) {
        "favorites" -> MediaSourcePage.Favorites
        "network" -> MediaSourcePage.Network
        else -> MediaSourcePage.PhoneHard(phoneHardPath)
    }
    val onPageChange: (MediaSourcePage?) -> Unit = { target -> if (target is MediaSourcePage.PhoneHard) onOpenPhoneHard(target.path) }
    val onClose: () -> Unit = {}
    val selectedCount = when (page) {
        is MediaSourcePage.PhoneHard -> chosenPhoneHard.size
        MediaSourcePage.Favorites -> chosenFavorites.size
        MediaSourcePage.Network -> 0
    }
    Column(Modifier.fillMaxSize()) {
        if (page is MediaSourcePage.PhoneHard) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (page.path != null) IconButton(onClick = { onOpenPhoneHard(phoneHardParent(page.path)) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "상위 폴더") }
                Text(page.path?.let { "폰하드 / $it" } ?: "폰하드", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                MediaSourceViewModeMenu(mode = viewMode, onSelectMode = { viewMode = it })
            }
        } else if (page is MediaSourcePage.Favorites) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("즐겨찾기", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                MediaSourceViewModeMenu(mode = viewMode, onSelectMode = { viewMode = it })
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (page) {
                        MediaSourcePage.Network -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(state.connections, key = { it.id }) { connection ->
                                androidx.compose.material3.Surface(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        onClose()
                                        model.beginNetworkMediaPick(connection, returnScreen)
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    tonalElevation = 2.dp,
                                ) {
                                    Column(Modifier.padding(16.dp)) {
                                        Text(connection.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(connection.kind.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        MediaSourcePage.Favorites -> {
                            if (state.remoteFavorites.isEmpty()) {
                                Text("즐겨찾기가 없습니다. 파일 목록에서 사진·영상이나 폴더를 즐겨찾기에 추가하면 여기에 나타납니다.", Modifier.padding(24.dp))
                            } else {
                                val lockedConnection = state.remoteFavorites.firstOrNull { it.id in chosenFavorites && it.connectionId != "phonehard" }?.connectionId
                                when (viewMode) {
                                    MediaSourceViewMode.DETAILS -> {
                                        LazyColumn(
                                            modifier = Modifier.fillMaxSize(),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            items(state.remoteFavorites, key = { it.id }) { favorite ->
                                                val phoneHard = favorite.connectionId == "phonehard"
                                                val connection = state.connections.firstOrNull { it.id == favorite.connectionId }
                                                val kind = BuiltInPreviewPolicy.kind(favorite.name, favorite.mimeType)
                                                val isMedia = kind == BuiltInPreviewKind.IMAGE || kind == BuiltInPreviewKind.VIDEO
                                                LaunchedEffect(favorite.id, favorite.connectionId, favorite.path, favorite.size, favorite.modifiedAt, state.favoriteThumbnailGeneration, revision) {
                                                    model.loadFavoriteThumbnail(favorite)
                                                }
                                                val usable = (favorite.isDirectory || isMedia) && (phoneHard || connection != null) &&
                                                    (favorite.isDirectory || phoneHard || lockedConnection == null || lockedConnection == favorite.connectionId)
                                                val metaList = buildList {
                                                    if (phoneHard) add("폰하드") else if (connection != null) add(connection.name) else add("연결 없음")
                                                    if (!favorite.isDirectory) {
                                                        favorite.size?.let { add(formatLocalFileSize(it)) }
                                                        favorite.modifiedAt?.let { modified ->
                                                            runCatching {
                                                                val formatter = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm").withZone(ZoneId.systemDefault())
                                                                add(formatter.format(modified))
                                                            }
                                                        }
                                                    } else {
                                                        add("폴더")
                                                    }
                                                }
                                                SourceRow(
                                                    title = favorite.name,
                                                    subtitle = if (!usable && !favorite.isDirectory && !isMedia) "사진·영상 아님" else metaList.joinToString(" · "),
                                                    bitmap = state.favoriteThumbnails[favorite.id],
                                                    cacheIdentity = "favorite:${favorite.connectionId}:${favorite.remoteItemId ?: favorite.path}:${favorite.path}:${favorite.size}:${favorite.modifiedAt}:${favorite.thumbnailUrl}",
                                                    isFolder = favorite.isDirectory,
                                                    isVideo = kind == BuiltInPreviewKind.VIDEO,
                                                    selectable = !favorite.isDirectory,
                                                    selected = favorite.id in chosenFavorites,
                                                    enabled = usable,
                                                    onClick = {
                                                        when {
                                                            favorite.isDirectory && phoneHard -> onPageChange(MediaSourcePage.PhoneHard(favorite.path.trim('/').ifEmpty { null }))
                                                            favorite.isDirectory && connection != null -> {
                                                                onClose()
                                                                model.beginNetworkMediaPick(connection, returnScreen, favorite.path)
                                                            }
                                                            favorite.id in chosenFavorites -> chosenFavorites.remove(favorite.id)
                                                            else -> chosenFavorites.add(favorite.id)
                                                        }
                                                    },
                                                )
                                            }
                                        }
                                    }
                                    MediaSourceViewMode.THUMBNAIL, MediaSourceViewMode.POSTER -> {
                                        val poster = viewMode == MediaSourceViewMode.POSTER
                                        LazyVerticalGrid(
                                            columns = GridCells.Adaptive(sourceCellMinWidth(poster)),
                                            modifier = Modifier.fillMaxSize(),
                                            contentPadding = PaddingValues(if (poster) 12.dp else 8.dp),
                                            horizontalArrangement = Arrangement.spacedBy(if (poster) 12.dp else 6.dp),
                                            verticalArrangement = Arrangement.spacedBy(if (poster) 16.dp else 6.dp),
                                        ) {
                                            items(state.remoteFavorites, key = { it.id }) { favorite ->
                                                val phoneHard = favorite.connectionId == "phonehard"
                                                val connection = state.connections.firstOrNull { it.id == favorite.connectionId }
                                                val kind = BuiltInPreviewPolicy.kind(favorite.name, favorite.mimeType)
                                                val isMedia = kind == BuiltInPreviewKind.IMAGE || kind == BuiltInPreviewKind.VIDEO
                                                LaunchedEffect(favorite.id, favorite.connectionId, favorite.path, favorite.size, favorite.modifiedAt, state.favoriteThumbnailGeneration, revision) {
                                                    model.loadFavoriteThumbnail(favorite)
                                                }
                                                val usable = (favorite.isDirectory || isMedia) && (phoneHard || connection != null) &&
                                                    (favorite.isDirectory || phoneHard || lockedConnection == null || lockedConnection == favorite.connectionId)
                                                val subtitleText = when {
                                                    !favorite.isDirectory && !isMedia -> "사진·영상 아님"
                                                    !phoneHard && connection == null -> "연결 없음"
                                                    !usable -> "다른 연결과 함께 담을 수 없음"
                                                    phoneHard -> "폰하드"
                                                    else -> connection?.name.orEmpty()
                                                }
                                                val detailSubtitle = if (poster && !favorite.isDirectory) {
                                                    buildList {
                                                        add(subtitleText)
                                                        favorite.size?.let { add(formatLocalFileSize(it)) }
                                                        favorite.modifiedAt?.let { modified ->
                                                            runCatching {
                                                                val formatter = DateTimeFormatter.ofPattern("yyyy.MM.dd").withZone(ZoneId.systemDefault())
                                                                add(formatter.format(modified))
                                                            }
                                                        }
                                                    }.joinToString(" · ")
                                                } else subtitleText
                                                SourceTile(
                                                    title = favorite.name,
                                                    subtitle = detailSubtitle,
                                                    bitmap = state.favoriteThumbnails[favorite.id],
                                                    cacheIdentity = "favorite:${favorite.connectionId}:${favorite.remoteItemId ?: favorite.path}:${favorite.path}:${favorite.size}:${favorite.modifiedAt}:${favorite.thumbnailUrl}",
                                                    isFolder = favorite.isDirectory,
                                                    isVideo = kind == BuiltInPreviewKind.VIDEO,
                                                    selectable = !favorite.isDirectory,
                                                    selected = favorite.id in chosenFavorites,
                                                    enabled = usable,
                                                    poster = poster,
                                                    onClick = {
                                                        when {
                                                            favorite.isDirectory && phoneHard -> onPageChange(MediaSourcePage.PhoneHard(favorite.path.trim('/').ifEmpty { null }))
                                                            favorite.isDirectory && connection != null -> {
                                                                onClose()
                                                                model.beginNetworkMediaPick(connection, returnScreen, favorite.path)
                                                            }
                                                            favorite.id in chosenFavorites -> chosenFavorites.remove(favorite.id)
                                                            else -> chosenFavorites.add(favorite.id)
                                                        }
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        is MediaSourcePage.PhoneHard -> {
                            var retryTick by remember { mutableStateOf(0) }
                            val loaded by produceState<Result<List<com.armsone.nasfinder.data.PhoneHardItem>>?>(null, page.path, revision, retryTick) {
                                value = null
                                value = withContext(Dispatchers.IO) { model.loadPhoneHardFolderResult(page.path) }
                            }
                            val loadError = loaded?.exceptionOrNull()
                            val listing = loaded?.getOrNull()
                            val entries = listing?.filter { entry ->
                                entry.kind == com.armsone.nasfinder.data.PhoneHardEntryKind.DIRECTORY ||
                                    BuiltInPreviewPolicy.kind(entry.name, entry.mimeType).let { it == BuiltInPreviewKind.IMAGE || it == BuiltInPreviewKind.VIDEO }
                            }
                            when {
                                loadError != null -> Column(Modifier.align(Alignment.Center).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("폰하드 폴더를 불러오지 못했습니다: ${loadError.message ?: loadError.javaClass.simpleName}")
                                    Button(onClick = { retryTick++ }) { Text("다시 시도") }
                                    val parentPath = page.path?.trim('/')?.substringBeforeLast('/', "")?.ifEmpty { null }
                                    if (page.path != null) {
                                        OutlinedButton(onClick = { onPageChange(MediaSourcePage.PhoneHard(parentPath)) }) { Text("상위 폴더로") }
                                    }
                                }
                                entries == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                                entries.isEmpty() -> Text("이 폴더에 사진·영상이나 하위 폴더가 없습니다.", Modifier.padding(24.dp))
                                viewMode == MediaSourceViewMode.DETAILS -> {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        items(entries, key = { it.relativePath }) { entry ->
                                            val isDirectory = entry.kind == com.armsone.nasfinder.data.PhoneHardEntryKind.DIRECTORY
                                            val kind = BuiltInPreviewPolicy.kind(entry.name, entry.mimeType)
                                            val thumbnail by produceState<Bitmap?>(null, entry.relativePath, entry.modifiedAt, entry.byteCount) {
                                                value = if (isDirectory) null else withContext(Dispatchers.IO) {
                                                    decodeInboxThumbnail(
                                                        context, null, entry.file,
                                                        isImage = kind == BuiltInPreviewKind.IMAGE,
                                                        isVideo = kind == BuiltInPreviewKind.VIDEO,
                                                        isPdf = false,
                                                        maxPixelSize = 160,
                                                    )
                                                }
                                            }
                                            val metaList = buildList {
                                                if (isDirectory) {
                                                    add("폴더")
                                                } else {
                                                    add("폰하드")
                                                    add(formatLocalFileSize(entry.byteCount))
                                                    entry.modifiedAt?.let { modified ->
                                                        runCatching {
                                                            val formatter = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm").withZone(ZoneId.systemDefault())
                                                            add(formatter.format(modified))
                                                        }
                                                    }
                                                }
                                            }
                                            SourceRow(
                                                title = entry.name,
                                                subtitle = metaList.joinToString(" · "),
                                                bitmap = thumbnail,
                                                cacheIdentity = "phonehard-source:${entry.relativePath}:${entry.byteCount}:${entry.modifiedAt}",
                                                isFolder = isDirectory,
                                                isVideo = kind == BuiltInPreviewKind.VIDEO,
                                                selectable = !isDirectory,
                                                selected = entry.relativePath in chosenPhoneHard,
                                                enabled = true,
                                                onClick = {
                                                    when {
                                                        isDirectory -> onPageChange(MediaSourcePage.PhoneHard(entry.relativePath))
                                                        entry.relativePath in chosenPhoneHard -> chosenPhoneHard.remove(entry.relativePath)
                                                        else -> chosenPhoneHard.add(entry.relativePath)
                                                    }
                                                },
                                            )
                                        }
                                    }
                                }
                                else -> {
                                    val poster = viewMode == MediaSourceViewMode.POSTER
                                    LazyVerticalGrid(
                                        columns = GridCells.Adaptive(sourceCellMinWidth(poster)),
                                        modifier = Modifier.fillMaxSize(),
                                        contentPadding = PaddingValues(if (poster) 12.dp else 8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(if (poster) 12.dp else 6.dp),
                                        verticalArrangement = Arrangement.spacedBy(if (poster) 16.dp else 6.dp),
                                    ) {
                                        items(entries, key = { it.relativePath }) { entry ->
                                            val isDirectory = entry.kind == com.armsone.nasfinder.data.PhoneHardEntryKind.DIRECTORY
                                            val kind = BuiltInPreviewPolicy.kind(entry.name, entry.mimeType)
                                            val thumbnail by produceState<Bitmap?>(null, entry.relativePath, entry.modifiedAt, entry.byteCount) {
                                                value = if (isDirectory) null else withContext(Dispatchers.IO) {
                                                    decodeInboxThumbnail(
                                                        context, null, entry.file,
                                                        isImage = kind == BuiltInPreviewKind.IMAGE,
                                                        isVideo = kind == BuiltInPreviewKind.VIDEO,
                                                        isPdf = false,
                                                        maxPixelSize = if (poster) 320 else 160,
                                                    )
                                                }
                                            }
                                            val metaText = if (isDirectory) "폴더" else if (poster) {
                                                buildList {
                                                    add("폰하드")
                                                    add(formatLocalFileSize(entry.byteCount))
                                                    entry.modifiedAt?.let { modified ->
                                                        runCatching {
                                                            val formatter = DateTimeFormatter.ofPattern("yyyy.MM.dd").withZone(ZoneId.systemDefault())
                                                            add(formatter.format(modified))
                                                        }
                                                    }
                                                }.joinToString(" · ")
                                            } else "폰하드"
                                            SourceTile(
                                                title = entry.name,
                                                subtitle = metaText,
                                                bitmap = thumbnail,
                                                cacheIdentity = "phonehard-source:${entry.relativePath}:${entry.byteCount}:${entry.modifiedAt}",
                                                isFolder = isDirectory,
                                                isVideo = kind == BuiltInPreviewKind.VIDEO,
                                                selectable = !isDirectory,
                                                selected = entry.relativePath in chosenPhoneHard,
                                                enabled = true,
                                                poster = poster,
                                                onClick = {
                                                    when {
                                                        isDirectory -> onPageChange(MediaSourcePage.PhoneHard(entry.relativePath))
                                                        entry.relativePath in chosenPhoneHard -> chosenPhoneHard.remove(entry.relativePath)
                                                        else -> chosenPhoneHard.add(entry.relativePath)
                                                    }
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
            }
        }
        if (page !is MediaSourcePage.Network) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { chosenPhoneHard.clear(); chosenFavorites.clear() },
                    enabled = selectedCount > 0,
                    modifier = Modifier.weight(1f),
                ) { Text("선택 해제") }
                Button(
                    enabled = selectedCount > 0,
                    modifier = Modifier.weight(2f),
                    onClick = {
                        if (page is MediaSourcePage.PhoneHard) {
                            onCommitUris(model.phoneHardFileUris(chosenPhoneHard.toList()))
                            chosenPhoneHard.clear()
                        } else {
                            val picked = state.remoteFavorites.filter { it.id in chosenFavorites }
                            val local = picked.filter { it.connectionId == "phonehard" }
                            val remote = picked.filter { it.connectionId != "phonehard" }
                            onCommitUris(model.phoneHardFileUris(local.map { it.path }))
                            if (remote.isNotEmpty()) model.addFavoriteFilesToBasket(remote, returnScreen)
                            chosenFavorites.clear()
                        }
                    },
                ) { Text("선택한 항목 담기 ($selectedCount)") }
            }
        }
    }
}

@Composable
private fun SourceRow(
    title: String,
    subtitle: String,
    bitmap: Bitmap?,
    cacheIdentity: String,
    isFolder: Boolean,
    isVideo: Boolean,
    selectable: Boolean,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$title, $subtitle" + if (selected) ", 선택됨" else "" },
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                if (bitmap != null && !bitmap.isRecycled) {
                    SkinAwareThumbnailImage(
                        bitmap = bitmap,
                        cacheIdentity = cacheIdentity,
                        contentDescription = title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        isFolderCover = isFolder,
                    )
                } else {
                    Icon(
                        if (isFolder) Icons.Default.Folder else if (isVideo) Icons.Default.PlayCircle else Icons.Default.AddPhotoAlternate,
                        null,
                        Modifier.align(Alignment.Center).size(28.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else .4f),
                    )
                }
                if (isVideo && bitmap != null) Icon(Icons.Default.PlayCircle, null, Modifier.align(Alignment.BottomStart).padding(2.dp).size(16.dp), tint = Color.White)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (selectable) {
                Icon(
                    if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    null,
                    modifier = Modifier.size(24.dp),
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SourceTile(
    title: String,
    subtitle: String,
    bitmap: Bitmap?,
    cacheIdentity: String,
    isFolder: Boolean,
    isVideo: Boolean,
    selectable: Boolean,
    selected: Boolean,
    enabled: Boolean,
    poster: Boolean = false,
    onClick: () -> Unit,
) {
    val cornerRadius = if (poster) 14.dp else 10.dp
    Column(
        Modifier
            .clip(RoundedCornerShape(cornerRadius))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = "$title, $subtitle" + if (selected) ", 선택됨" else "" },
        verticalArrangement = Arrangement.spacedBy(if (poster) 8.dp else 4.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(cornerRadius)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            if (bitmap != null && !bitmap.isRecycled) {
                SkinAwareThumbnailImage(
                    bitmap = bitmap,
                    cacheIdentity = cacheIdentity,
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    isFolderCover = isFolder,
                )
            } else {
                Icon(
                    if (isFolder) Icons.Default.Folder else if (isVideo) Icons.Default.PlayCircle else Icons.Default.AddPhotoAlternate,
                    null,
                    Modifier.align(Alignment.Center).size(if (poster) 48.dp else 36.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else .4f),
                )
            }
            if (isVideo && bitmap != null) Icon(Icons.Default.PlayCircle, null, Modifier.align(Alignment.BottomStart).padding(if (poster) 6.dp else 4.dp).size(if (poster) 24.dp else 20.dp), tint = Color.White)
            if (selectable) Icon(
                if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                null,
                Modifier.align(Alignment.TopEnd).padding(if (poster) 6.dp else 4.dp).size(if (poster) 26.dp else 24.dp),
                tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
            )
        }
        Text(
            title,
            style = if (poster) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = if (poster) 2.dp else 4.dp),
        )
        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Source-basket cells grow with the porcelain theme so covers and names stay readable on wide windows. */
@Composable
private fun sourceCellMinWidth(poster: Boolean) =
    if (com.armsone.nasfinder.ui.theme.usesUnifiedLayout(com.armsone.nasfinder.ui.theme.LocalNasFinderTheme.current)) {
        if (poster) 188.dp else 124.dp
    } else if (poster) 158.dp else 104.dp
