@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.armsone.nasfinder.ui

import android.widget.VideoView
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.media.AudioManager
import android.os.BatteryManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.armsone.nasfinder.model.*
import com.armsone.nasfinder.data.SuperThumbnailWorkStatus
import com.armsone.nasfinder.data.SuperThumbnailVaultTiming
import com.armsone.nasfinder.data.ScreenAwakeMode
import com.armsone.nasfinder.data.RemoteThumbnailCachePolicy
import com.armsone.nasfinder.BuildConfig
import com.armsone.nasfinder.platform.InboxBatchContracts
import com.armsone.nasfinder.platform.LauncherIconVariant
import com.armsone.nasfinder.platform.WebHardFileStore
import com.armsone.nasfinder.R
import com.armsone.nasfinder.ui.theme.NasFinderTheme
import com.armsone.nasfinder.ui.theme.LocalNasFinderTheme
import com.armsone.nasfinder.ui.theme.PhoneHardMark
import com.armsone.nasfinder.ui.theme.folderColor
import com.armsone.nasfinder.ui.theme.serviceColor
import com.armsone.nasfinder.ui.theme.serviceForegroundColor
import com.armsone.nasfinder.ui.theme.WorkbenchAccent
import com.armsone.nasfinder.ui.theme.BrowserOrange
import androidx.core.content.FileProvider
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun NasFinderApp(model: NasFinderViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val hostView = LocalView.current
    val hasActiveWork = state.isBusy || state.download != null || state.pendingInboxUpload?.isUploading == true ||
        state.pendingLocalUpload?.isUploading == true ||
        state.imagePreview?.kind == BuiltInPreviewKind.VIDEO ||
        (state.screen == Screen.SuperThumbnailProgress && state.superThumbnailWork?.status in setOf(SuperThumbnailWorkStatus.WAITING, SuperThumbnailWorkStatus.RUNNING))
    val keepScreenOn = state.screenAwakeMode == ScreenAwakeMode.ALWAYS ||
        (state.screenAwakeMode == ScreenAwakeMode.AUTOMATIC && hasActiveWork)
    DisposableEffect(hostView, keepScreenOn) {
        hostView.keepScreenOn = keepScreenOn
        onDispose { if (hostView.keepScreenOn == keepScreenOn) hostView.keepScreenOn = false }
    }
    val webBrowserSession = remember { WebBrowserSessionController() }
    DisposableEffect(webBrowserSession) { onDispose(webBrowserSession::close) }
    NasFinderTheme(state.theme) {
        Box(Modifier.fillMaxSize().background(skyBrush(state.theme))) {
            SkyThemeDecoration(state.theme)
            when (val screen = state.screen) {
                Screen.Dashboard -> DashboardScreen(state, model)
                is Screen.AddConnection -> ConnectionEditor(screen.editing, state, model)
                is Screen.Browser -> BrowserScreen(screen, state, model)
                Screen.Inbox, Screen.WebHard -> InboxScreen(state, model)
                Screen.PhotoTransfer -> PhotoTransferScreen(
                    onBack = { model.show(Screen.Dashboard) },
                )
                Screen.Settings -> SettingsScreen(state, model)
                Screen.ThumbnailCache -> ThumbnailCacheScreen(state, model)
                Screen.SuperThumbnail -> SuperThumbnailScreen(state, model)
                Screen.SuperThumbnailFolderPicker -> SuperThumbnailFolderPickerScreen(state, model)
                Screen.SuperThumbnailProgress -> SuperThumbnailProgressScreen(state, model)
                Screen.SuperThumbnailReport -> SuperThumbnailReportScreen(state, model)
                Screen.WebBrowser -> WebBrowserScreen(
                    sessionController = webBrowserSession,
                    initialUrl = state.browserFavorites.firstOrNull { it.isHomepage }?.url
                        ?: state.browserFavorites.firstOrNull()?.url
                        ?: "https://www.google.com",
                    favorites = state.browserFavorites,
                    connections = state.connections,
                    preferredConnectionId = state.preferredId,
                    resumedDownload = state.pendingLocalUpload,
                    onClose = { model.show(Screen.Dashboard) },
                    onToggleFavorite = model::toggleBrowserFavorite,
                    onSetHomepage = model::setBrowserHomepage,
                    onEditFavorite = model::editBrowserFavorite,
                    onDeleteFavorite = model::deleteBrowserFavorite,
                    onSaveDownloadedFile = model::importWebDownloadToInbox,
                    onSendDownloadedFile = model::importWebDownloadToNas,
                    onDiscardDownloadedFile = model::discardWebDownload,
                )
            }
            state.imagePreview?.let { preview ->
                BuiltInPreviewScreen(
                    preview = preview,
                    onClose = model::closeImagePreview,
                    onShare = model::shareImagePreview,
                    onPrevious = { model.moveImagePreview(-1) },
                    onNext = { model.moveImagePreview(1) },
                    onPreviousPdfPage = { model.movePdfPreview(-1) },
                    onNextPdfPage = { model.movePdfPreview(1) },
                    onPlaybackFailure = model::fallbackPreviewToExternal,
                )
            }
            if (state.isBusy) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .18f)), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.download?.let { download ->
                DownloadProgressBanner(
                    download = download,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
        state.message?.let { message ->
            AlertDialog(onDismissRequest = model::dismissMessage, confirmButton = { TextButton(onClick = model::dismissMessage) { Text("확인") } }, title = { Text("알림") }, text = { Text(message) })
        }
    }
    BackHandler(enabled = state.imagePreview != null) {
        model.closeImagePreview()
    }
    BackHandler(enabled = state.imagePreview == null && state.screen != Screen.Dashboard) {
        model.show(Screen.Dashboard)
    }
}

@Composable
private fun BoxScope.SkyThemeDecoration(theme: AppTheme) {
    when (theme) {
        AppTheme.DIGITAL_RAIN -> {
            CodeRainDecoration(Modifier.fillMaxSize().graphicsLayer(alpha = .78f))
        }
        AppTheme.WINDY_MEADOW -> {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                Box(
                    Modifier.align(Alignment.TopCenter)
                        .offset(x = maxWidth * (-.18f), y = maxHeight * .58f)
                        .size(width = maxWidth * 1.35f, height = maxHeight * .46f)
                        .background(Color(red = .43f, green = .68f, blue = .20f).copy(alpha = .18f), CircleShape),
                )
                Icon(
                    Icons.Default.Air,
                    null,
                    Modifier.align(Alignment.TopCenter).offset(x = maxWidth * .24f, y = 112.dp).size(minOf(maxWidth * .20f, 86.dp)),
                    tint = Color.White.copy(alpha = .18f),
                )
            }
        }
        AppTheme.WORKBENCH -> {
            WorkbenchDecoration(Modifier.fillMaxSize().graphicsLayer(alpha = .78f))
        }
        AppTheme.SKEUOMORPHIC -> {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                Box(
                    Modifier.align(Alignment.TopEnd)
                        .offset(x = maxWidth * .18f, y = 38.dp)
                        .size(maxWidth * .72f)
                        .border(
                            maxOf(maxWidth * .025f, 8.dp),
                            Brush.linearGradient(listOf(Color.White.copy(alpha = .95f), Color.Black.copy(alpha = .18f))),
                            CircleShape,
                        )
                        .graphicsLayer(alpha = .32f),
                )
            }
        }
        AppTheme.DAY, AppTheme.SYSTEM, AppTheme.NIGHT -> {
            val dark = theme == AppTheme.NIGHT || (theme == AppTheme.SYSTEM && isSystemInDarkTheme())
            BoxWithConstraints(Modifier.fillMaxSize()) {
                Icon(Icons.Default.Cloud, null, Modifier.align(Alignment.TopCenter).offset(x = maxWidth * .28f, y = 34.dp).size(minOf(maxWidth * .42f, 190.dp)), tint = Color.White.copy(alpha = if (dark) .045f else .28f))
                Icon(Icons.Default.Cloud, null, Modifier.align(Alignment.TopCenter).offset(x = maxWidth * (-.34f), y = 122.dp).size(minOf(maxWidth * .24f, 110.dp)), tint = Color.White.copy(alpha = if (dark) .03f else .17f))
                Icon(Icons.Default.Air, null, Modifier.align(Alignment.TopCenter).offset(x = maxWidth * .24f, y = 210.dp).size(minOf(maxWidth * .20f, 86.dp)), tint = MaterialTheme.colorScheme.primary.copy(alpha = .07f))
            }
        }
    }
}

@Composable
private fun CodeRainDecoration(modifier: Modifier = Modifier) {
    val columns = listOf("10110", "01A9F", "11001", "0FF10", "10101", "01110", "10C0D", "00111", "F0101", "11010", "0A011", "10100")
    BoxWithConstraints(modifier.clipToBounds()) {
        val spacing = maxOf(maxWidth * .035f, 8.dp)
        val codeSize = (maxWidth.value * .025f).coerceIn(6f, 11f).sp
        Row(
            Modifier.align(Alignment.TopCenter),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(spacing),
        ) {
            columns.forEachIndexed { index, value ->
                Column(Modifier.offset(y = (((index * 29) % 90) - 18).dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    Text(
                        value.take(1),
                        color = Color(red = .74f, green = 1f, blue = .90f),
                        fontSize = codeSize,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        style = androidx.compose.ui.text.TextStyle(
                            shadow = androidx.compose.ui.graphics.Shadow(
                                color = MaterialTheme.colorScheme.primary.copy(alpha = .85f),
                                offset = Offset.Zero,
                                blurRadius = 4f,
                            ),
                        ),
                    )
                    Text(
                        value.drop(1),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = .20f),
                        fontSize = codeSize,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkbenchDecoration(modifier: Modifier = Modifier) {
    val lines = listOf(
        "let client = NAS()",
        "await client.connect()",
        "git status --short",
        "func browse(path: URL)",
        "guard result.isReady else",
        "return .success(files)",
    )
    BoxWithConstraints(modifier.clipToBounds()) {
        val rowSpacing = maxOf(maxHeight * .022f, 12.dp)
        val topPadding = maxOf(maxHeight * .08f, 24.dp)
        val trailingPadding = maxOf(maxWidth * .04f, 12.dp)
        val codeSize = (maxWidth.value * .032f).coerceIn(7f, 12f).sp
        Column(
            Modifier.align(Alignment.TopEnd).padding(top = topPadding, end = trailingPadding),
            verticalArrangement = Arrangement.spacedBy(rowSpacing),
            horizontalAlignment = Alignment.Start,
        ) {
            lines.forEachIndexed { index, line ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("%02d".format(index + 1), color = Color.White.copy(alpha = .18f), fontSize = codeSize, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    Text(
                        line,
                        color = if (index % 3 == 0) WorkbenchAccent.copy(alpha = .19f) else Color.White.copy(alpha = .10f),
                        fontSize = codeSize,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberTouchExplorationEnabled(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) {
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    }
    var enabled by remember(manager) { mutableStateOf(manager.isTouchExplorationEnabled) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        manager.addTouchExplorationStateChangeListener(listener)
        onDispose { manager.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}

@Composable
private fun BuiltInPreviewScreen(
    preview: ImagePreviewState,
    onClose: () -> Unit,
    onShare: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPreviousPdfPage: () -> Unit,
    onNextPdfPage: () -> Unit,
    onPlaybackFailure: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val haptics = LocalHapticFeedback.current
    val touchExploration = rememberTouchExplorationEnabled()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var controlsVisible by rememberSaveable(preview.connection.id) { mutableStateOf(true) }
    var controlInteraction by remember(preview.connection.id) { mutableIntStateOf(0) }
    var slideshowPlaying by rememberSaveable(preview.connection.id) { mutableStateOf(false) }
    fun revealControls() { controlsVisible = true; controlInteraction++ }
    LaunchedEffect(preview.cachedFile.absolutePath, controlsVisible, controlInteraction) {
        if (controlsVisible && !touchExploration) { delay(2_500); controlsVisible = false }
    }
    LaunchedEffect(preview.index, slideshowPlaying) {
        if (slideshowPlaying && preview.kind == BuiltInPreviewKind.IMAGE) {
            delay(3_000)
            if (preview.index < preview.images.lastIndex) onNext() else slideshowPlaying = false
        }
    }
    Scaffold(
        containerColor = Color.Black,
        topBar = {
            if (controlsVisible) {
            TopAppBar(
                title = {
                    Column {
                        Text(preview.images[preview.index].name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        when {
                            preview.kind == BuiltInPreviewKind.IMAGE && preview.images.size > 1 -> Text("${preview.index + 1} / ${preview.images.size}", color = Color.White.copy(alpha = .68f), style = MaterialTheme.typography.labelSmall)
                            preview.kind == BuiltInPreviewKind.PDF -> Text("${preview.pdfPageIndex + 1} / ${preview.pdfPageCount}", color = Color.White.copy(alpha = .68f), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, "닫기", tint = Color.White) } },
                actions = { IconButton(onClick = onShare) { Icon(Icons.Default.Share, "공유", tint = Color.White) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black),
            )
            } else Spacer(Modifier.height(64.dp).fillMaxWidth().background(Color.Black))
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().background(Color.Black).clickable(onClick = { if (touchExploration) revealControls() else if (controlsVisible) controlsVisible = false else revealControls() }), contentAlignment = Alignment.Center) {
            when (preview.kind) {
                BuiltInPreviewKind.IMAGE, BuiltInPreviewKind.PDF -> preview.bitmap?.let { bitmap ->
                    if (preview.kind == BuiltInPreviewKind.IMAGE && landscape && preview.images.size > 1) {
                        LandscapeCoverFlowPreview(bitmap, preview.images[preview.index].name)
                    } else ZoomablePreviewBitmap(bitmap, preview.images[preview.index].name, "${preview.cachedFile.absolutePath}:${preview.pdfPageIndex}")
                }
                BuiltInPreviewKind.AUDIO, BuiltInPreviewKind.VIDEO -> MediaPreviewContent(preview, controlsVisible, ::revealControls, onClose, onPlaybackFailure)
            }
            if (preview.kind == BuiltInPreviewKind.IMAGE && controlsVisible) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .10f)))
            if (controlsVisible && preview.kind == BuiltInPreviewKind.IMAGE && preview.images.size > 1) {
                FilledIconButton(
                    onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onPrevious() },
                    enabled = preview.index > 0,
                    modifier = Modifier.align(Alignment.CenterStart).padding(12.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.Black.copy(alpha = .56f), contentColor = Color.White),
                ) { Icon(Icons.Default.ChevronLeft, "이전 이미지") }
                FilledIconButton(
                    onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onNext() },
                    enabled = preview.index < preview.images.lastIndex,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(12.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.Black.copy(alpha = .56f), contentColor = Color.White),
                ) { Icon(Icons.Default.ChevronRight, "다음 이미지") }
                FilledIconButton(
                    onClick = { slideshowPlaying = !slideshowPlaying; revealControls() },
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(14.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.Black.copy(alpha = .56f), contentColor = Color.White),
                ) { Icon(if (slideshowPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (slideshowPlaying) "슬라이드쇼 일시정지" else "슬라이드쇼 재생") }
            }
            if (controlsVisible && preview.kind == BuiltInPreviewKind.PDF && preview.pdfPageCount > 1) {
                FilledIconButton(
                    onClick = onPreviousPdfPage,
                    enabled = preview.pdfPageIndex > 0,
                    modifier = Modifier.align(Alignment.CenterStart).padding(12.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.Black.copy(alpha = .56f), contentColor = Color.White),
                ) { Icon(Icons.Default.ChevronLeft, "이전 페이지") }
                FilledIconButton(
                    onClick = onNextPdfPage,
                    enabled = preview.pdfPageIndex < preview.pdfPageCount - 1,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(12.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.Black.copy(alpha = .56f), contentColor = Color.White),
                ) { Icon(Icons.Default.ChevronRight, "다음 페이지") }
            }
        }
    }
}

@Composable
private fun LandscapeCoverFlowPreview(bitmap: android.graphics.Bitmap, description: String) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Image(bitmap.asImageBitmap(), description, Modifier.fillMaxWidth(.58f).weight(.68f).clip(RoundedCornerShape(18.dp)), contentScale = ContentScale.Fit)
        Image(
            bitmap.asImageBitmap(), null,
            Modifier.fillMaxWidth(.58f).weight(.22f).graphicsLayer { rotationX = 180f; alpha = .20f },
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
private fun ZoomablePreviewBitmap(bitmap: android.graphics.Bitmap, description: String, stateKey: String) {
    val touchExploration = rememberTouchExplorationEnabled()
    var scale by remember(stateKey) { mutableFloatStateOf(1f) }
    var translation by remember(stateKey) { mutableStateOf(Offset.Zero) }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = description,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize().then(if (touchExploration) Modifier else Modifier.pointerInput(stateKey) {
            detectTransformGestures { _, pan, zoom, _ ->
                val nextScale = (scale * zoom).coerceIn(1f, 5f)
                scale = nextScale
                translation = if (nextScale == 1f) Offset.Zero else translation + pan
            }
        }).graphicsLayer {
            scaleX = scale
            scaleY = scale
            translationX = translation.x
            translationY = translation.y
        },
    )
}

@Composable
private fun MediaPreviewContent(
    preview: ImagePreviewState,
    controlsVisible: Boolean,
    onInteraction: () -> Unit,
    onDismiss: () -> Unit,
    onPlaybackFailure: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val touchExploration = rememberTouchExplorationEnabled()
    var player by remember(preview.cachedFile.absolutePath) { mutableStateOf<VideoView?>(null) }
    var duration by remember(preview.cachedFile.absolutePath) { mutableIntStateOf(0) }
    var position by rememberSaveable(preview.cachedFile.absolutePath) { mutableIntStateOf(0) }
    var playing by rememberSaveable(preview.cachedFile.absolutePath) { mutableStateOf(false) }
    var scale by rememberSaveable(preview.cachedFile.absolutePath) { mutableFloatStateOf(1f) }
    var translation by remember(preview.cachedFile.absolutePath) { mutableStateOf(Offset.Zero) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var gestureMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(player, playing) {
        while (playing) {
            position = player?.currentPosition?.coerceAtLeast(0) ?: position
            delay(250)
        }
    }
    LaunchedEffect(gestureMessage) { if (gestureMessage != null) { delay(850); gestureMessage = null } }
    DisposableEffect(lifecycleOwner, preview.cachedFile.absolutePath) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                position = player?.currentPosition ?: position
                player?.pause()
                playing = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            position = player?.currentPosition ?: position
            player?.stopPlayback()
            player = null
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (preview.kind == BuiltInPreviewKind.AUDIO) {
            Icon(Icons.Default.Audiotrack, null, tint = Color.White.copy(alpha = .78f), modifier = Modifier.size(100.dp))
        }
        AndroidView(
            modifier = if (preview.kind == BuiltInPreviewKind.VIDEO) Modifier.fillMaxSize()
                .then(if (touchExploration) Modifier else Modifier
                    .pointerInput(preview.cachedFile.absolutePath) {
                        detectTapGestures(onDoubleTap = { scale = 1f; translation = Offset.Zero; onInteraction() })
                    }
                    .pointerInput(preview.cachedFile.absolutePath) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var anyPressed: Boolean
                            do {
                                val event = awaitPointerEvent()
                                if (event.changes.count { it.pressed } >= 2) {
                                    val zoom = event.calculateZoom()
                                    val pan = event.calculatePan()
                                    scale = (scale * zoom).coerceIn(1f, 4f)
                                    translation = if (scale == 1f) Offset.Zero else translation + pan
                                    event.changes.forEach { it.consume() }
                                    onInteraction()
                                }
                                anyPressed = event.changes.any { it.pressed }
                            } while (anyPressed)
                        }
                    }
                    .pointerInput(preview.cachedFile.absolutePath, duration) {
                        detectDragGestures(
                            onDragStart = { dragOffset = Offset.Zero; onInteraction() },
                            onDrag = { change, amount ->
                                change.consume(); dragOffset += amount
                                if (kotlin.math.abs(dragOffset.x) > kotlin.math.abs(dragOffset.y) && duration > 0) {
                                    val delta = (amount.x / size.width.coerceAtLeast(1)) * duration
                                    position = (position + delta.toInt()).coerceIn(0, duration)
                                    player?.seekTo(position)
                                    gestureMessage = "${formatDuration(position)} / ${formatDuration(duration)}"
                                } else if (dragOffset.y < 0) {
                                    val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                    val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                                    val delta = (-amount.y / size.height.coerceAtLeast(1) * max * 2).toInt()
                                    if (delta != 0) {
                                        val next = (current + delta).coerceIn(0, max)
                                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
                                        gestureMessage = "볼륨 ${(next * 100f / max).toInt()}%"
                                    }
                                } else if (dragOffset.y > 80) {
                                    gestureMessage = "아래로 놓아 닫기"
                                }
                            },
                            onDragEnd = { if (dragOffset.y > 180 && kotlin.math.abs(dragOffset.y) > kotlin.math.abs(dragOffset.x)) onDismiss(); dragOffset = Offset.Zero },
                            onDragCancel = { dragOffset = Offset.Zero },
                        )
                    }
                ).graphicsLayer {
                    scaleX = scale; scaleY = scale
                    translationX = translation.x; translationY = translation.y
                } else Modifier.size(1.dp),
            factory = { context ->
                VideoView(context).apply {
                    setOnPreparedListener { media ->
                        duration = media.duration.coerceAtLeast(0)
                        seekTo(position.coerceIn(0, duration.coerceAtLeast(0)))
                        if (playing) start()
                    }
                    setOnCompletionListener { playing = false; position = duration }
                    setOnErrorListener { _, _, _ -> onPlaybackFailure(); true }
                    setVideoPath(preview.cachedFile.absolutePath)
                    player = this
                }
            },
            update = { player = it },
            onRelease = { released ->
                position = released.currentPosition.coerceAtLeast(0)
                released.stopPlayback()
                if (player === released) player = null
            },
        )
        gestureMessage?.let { message ->
            Text(message, color = Color.White, modifier = Modifier.background(Color.Black.copy(alpha = .72f), RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.labelLarge)
        }
        if (controlsVisible) Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = .72f)).navigationBarsPadding().padding(16.dp),
        ) {
            Slider(
                value = position.toFloat().coerceIn(0f, duration.coerceAtLeast(1).toFloat()),
                onValueChange = { value -> onInteraction(); position = value.toInt(); player?.seekTo(position) },
                valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                IconButton(onClick = {
                    onInteraction()
                    if (playing) player?.pause() else player?.start()
                    playing = !playing
                }, enabled = duration > 0) {
                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "일시정지" else "재생", tint = Color.White)
                }
                Spacer(Modifier.width(10.dp))
                Text("${formatDuration(position)} / ${formatDuration(duration)}", color = Color.White, style = MaterialTheme.typography.labelMedium)
            }
        } else if (duration > 0) {
            LinearProgressIndicator(
                progress = { (position.toFloat() / duration).coerceIn(0f, 1f) },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().height(2.dp),
                color = Color.White.copy(alpha = .82f),
                trackColor = Color.White.copy(alpha = .18f),
            )
        }
    }
}

private fun formatDuration(milliseconds: Int): String {
    val seconds = (milliseconds.coerceAtLeast(0) / 1000)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

@Composable
private fun skyBrush(theme: AppTheme): Brush = Brush.linearGradient(
    when (theme) {
        AppTheme.DIGITAL_RAIN -> listOf(
            Color(red = .005f, green = .035f, blue = .032f),
            Color(red = .018f, green = .09f, blue = .075f),
            Color(red = .01f, green = .045f, blue = .042f),
        )
        AppTheme.WINDY_MEADOW -> listOf(
            Color(red = .23f, green = .74f, blue = .95f),
            Color(red = .72f, green = .90f, blue = .88f),
            Color(red = .89f, green = .94f, blue = .70f),
        )
        AppTheme.WORKBENCH -> listOf(
            Color(red = .07f, green = .10f, blue = .15f),
            Color(red = .035f, green = .055f, blue = .08f),
            Color(red = .055f, green = .12f, blue = .18f),
        )
        AppTheme.SKEUOMORPHIC -> listOf(
            Color(0xFFDAD8D3),
            Color(0xFFF8F6F0),
            Color.White,
        )
        AppTheme.NIGHT -> listOf(Color(red = .035f, green = .12f, blue = .19f), Color(red = .055f, green = .09f, blue = .13f))
        AppTheme.SYSTEM -> if (isSystemInDarkTheme()) {
            listOf(Color(red = .035f, green = .12f, blue = .19f), Color(red = .055f, green = .09f, blue = .13f))
        } else {
            listOf(Color(red = .59f, green = .85f, blue = 1f), Color(red = .86f, green = .95f, blue = 1f), Color.White)
        }
        AppTheme.DAY -> listOf(Color(red = .59f, green = .85f, blue = 1f), Color(red = .86f, green = .95f, blue = 1f), Color.White)
    }
)

@Composable
private fun DashboardScreen(state: AppState, model: NasFinderViewModel) {
    var connectionPendingDeletion by remember { mutableStateOf<RemoteConnection?>(null) }
    val fileImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        model.importPickedFiles(uris)
    }
    val badgeDark = when (state.theme) {
        AppTheme.SYSTEM -> isSystemInDarkTheme()
        AppTheme.NIGHT, AppTheme.DIGITAL_RAIN, AppTheme.WORKBENCH -> true
        AppTheme.DAY, AppTheme.WINDY_MEADOW, AppTheme.SKEUOMORPHIC -> false
    }
    Scaffold(containerColor = Color.Transparent, topBar = {
        TopAppBar(title = {
            Row(
                modifier = Modifier
                    .semantics(mergeDescendants = true) { contentDescription = "NasFinder" }
                    .clickable(
                        onClickLabel = "마지막으로 보던 네트워크 폴더를 엽니다.",
                        role = Role.Button,
                        onClick = model::resumeLastLocation,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Image(
                    painterResource(launcherIconDrawable(state.launcherIcon)),
                    null,
                    Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).border(.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .08f), RoundedCornerShape(10.dp)),
                )
                Text(
                    "NasFinder",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .82f),
                )
            }
        }, actions = {
            Surface(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(width = 38.dp, height = 18.dp)
                    .shadow(1.5.dp, CircleShape, ambientColor = Color.Black.copy(alpha = .18f), spotColor = Color.Black.copy(alpha = .18f))
                    .semantics { contentDescription = "현재 테마, ${themeTitle(state.theme)}" }
                    .clickable(
                        onClickLabel = "두 번 탭하면 다음 테마로 바뀝니다.",
                        role = Role.Button,
                        onClick = { model.setTheme(state.theme.next) },
                    ),
                shape = CircleShape,
                color = Color.Transparent,
                border = BorderStroke(2.dp, if (badgeDark) Color.Black else Color.White),
            ) {
                androidx.compose.animation.Crossfade(
                    targetState = state.theme,
                    animationSpec = androidx.compose.animation.core.tween(
                        durationMillis = 200,
                        easing = androidx.compose.animation.core.CubicBezierEasing(.42f, 0f, .58f, 1f),
                    ),
                    label = "themeBadge",
                ) { theme ->
                    Box(Modifier.fillMaxSize().background(skyBrush(theme)), contentAlignment = Alignment.Center) {
                        Icon(
                            themeIcon(theme),
                            contentDescription = null,
                            modifier = Modifier.size(9.dp),
                            tint = if (theme in setOf(AppTheme.NIGHT, AppTheme.DIGITAL_RAIN, AppTheme.WORKBENCH)) Color.White else Color.Black.copy(alpha = .72f),
                        )
                    }
                }
            }
        }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent))
    }) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { SectionTitle("내 파일", Icons.Default.Folder) }
            item {
                DashboardCard {
                    if (state.remoteFavorites.isEmpty()) {
                        Text(
                            "길게 눌러 즐겨찾기에 추가하세요.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 23.dp),
                        )
                    } else {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                            FavoriteShelf(
                                favorites = state.remoteFavorites,
                                connections = state.connections,
                                theme = state.theme,
                                onOpen = model::openFavorite,
                            )
                        }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    PhoneHardDashboardRow("${state.inboxFiles.size}개") { model.show(Screen.Inbox) }
                    DashboardRowDivider()
                    DashboardRow(Icons.AutoMirrored.Filled.CompareArrows, "Live Photos & Motion Photos", null) {
                        model.show(Screen.PhotoTransfer)
                    }
                    DashboardRowDivider()
                    DashboardRow(Icons.Default.PhotoLibrary, "썸네일 캐시", formatDashboardCacheBytes(state.thumbnailCacheStatistics?.totalBytes), MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant) { model.show(Screen.ThumbnailCache) }
                    DashboardRowDivider()
                    SuperThumbnailDashboardRow(if (state.superThumbnailSessionReport == null) "0 B" else "관리") { model.show(Screen.SuperThumbnail) }
                }
            }
            item {
                Box(Modifier.padding(top = 8.dp)) {
                    SectionTitle("네트워크", Icons.Default.Hub)
                }
            }
            item {
                DashboardCard {
                    state.connections.forEach { connection ->
                        ConnectionRow(connection, state.preferredId == connection.id, state.theme,
                            onOpen = { model.resumeConnection(connection) },
                            onEdit = { model.show(Screen.AddConnection(connection)) },
                            onDelete = { connectionPendingDeletion = connection },
                            onPreferred = { model.setPreferred(if (state.preferredId == connection.id) null else connection) },
                            onUp = { model.moveConnection(connection, -1) }, onDown = { model.moveConnection(connection, 1) })
                        DashboardRowDivider()
                    }
                    BrowserDashboardRow { model.show(Screen.WebBrowser) }
                    DashboardRowDivider()
                    DashboardRow(
                        Icons.Default.Add,
                        if (state.connections.isEmpty()) "네트워크를 추가해 주세요" else "네트워크 추가",
                        null,
                        iconTint = if (state.connections.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                        titleColor = if (state.connections.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    ) { model.show(Screen.AddConnection()) }
                }
            }
            item {
                Text(
                    when {
                        state.preferredId != null -> "기본 위치 ‘${state.connections.firstOrNull { it.id == state.preferredId }?.name.orEmpty()}’ · 앱 실행 시 자동으로 열림"
                        state.connections.isEmpty() -> "NAS 또는 SFTP 서버를 연결하면 파일을 탐색할 수 있습니다."
                        else -> "기본 위치 없음 · 앱 실행 시 연결 목록에서 시작"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .90f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 14.dp).offset(y = (-8).dp),
                )
            }
            item { SectionTitle("저장공간", Icons.Default.Storage) }
            item { DeviceStorageCard { fileImporter.launch(arrayOf("*/*")) } }
            item {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .88f), contentColor = MaterialTheme.colorScheme.onSurface) {
                    Column {
                        DashboardRow(Icons.Default.Settings, "설정", null) {
                            model.show(Screen.Settings)
                        }
                        Text(
                            "버전 ${BuildConfig.VERSION_NAME} · 빌드 ${BuildConfig.VERSION_CODE}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().padding(start = 48.dp, end = 14.dp, bottom = 10.dp),
                        )
                    }
                }
            }
        }
    }
    connectionPendingDeletion?.let { connection ->
        AlertDialog(
            onDismissRequest = { connectionPendingDeletion = null },
            title = { Text("연결을 삭제할까요?") },
            text = { Text("${connection.name}의 저장된 로그인 정보와 파일 앱 위치가 이 Android 기기에서 제거됩니다. 서버의 파일은 삭제되지 않습니다.") },
            dismissButton = {
                TextButton(onClick = { connectionPendingDeletion = null }) { Text("취소") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        connectionPendingDeletion = null
                        model.removeConnection(connection)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("삭제") }
            },
        )
    }
}

private fun launcherIconDrawable(icon: LauncherIconVariant) = when (icon) {
    LauncherIconVariant.DEFAULT -> R.drawable.app_icon_blue_nas
    LauncherIconVariant.CYBER_VAULT -> R.drawable.app_icon_cyber_vault
    LauncherIconVariant.VIBE_CODER -> R.drawable.app_icon_vibe_coder
    LauncherIconVariant.PURPLE_NAS -> R.drawable.app_icon_purple_nas
    LauncherIconVariant.NAS_RADAR -> R.drawable.app_icon_nas_radar
    LauncherIconVariant.ENAMEL -> R.drawable.app_icon_enamel
}

@Composable
private fun FavoriteShelf(
    favorites: List<RemoteFavorite>,
    connections: List<RemoteConnection>,
    theme: AppTheme,
    onOpen: (RemoteFavorite) -> Unit,
) {
    if (favorites.isEmpty()) return

    LazyRow(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        items(favorites, key = { it.id }) { favorite ->
            val connection = connections.firstOrNull { it.id == favorite.connectionId }
            val tint = connection?.let { serviceColor(it.kind.name, theme) }
                ?: MaterialTheme.colorScheme.onSurfaceVariant
            Column(
                modifier = Modifier.width(56.dp).clickable { onOpen(favorite) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box {
                    if (theme == AppTheme.SKEUOMORPHIC) {
                        EnamelIconWell(favoriteIcon(favorite), MaterialTheme.colorScheme.onSurface, 52.dp, 28.dp)
                    } else {
                        Surface(
                            modifier = Modifier.size(52.dp),
                            shape = RoundedCornerShape(11.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = .90f),
                            border = BorderStroke(1.dp, tint.copy(alpha = .28f)),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(favoriteIcon(favorite), null, tint = tint, modifier = Modifier.size(30.dp))
                            }
                        }
                    }
                    connection?.let {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 4.dp, y = (-4).dp)
                                .size(17.dp)
                                .background(
                                    if (theme == AppTheme.SKEUOMORPHIC) Brush.linearGradient(listOf(Color.White, Color(0xFFD1D0CC)))
                                    else Brush.linearGradient(listOf(tint, tint)),
                                    CircleShape,
                                )
                                .border(if (theme == AppTheme.SKEUOMORPHIC) 1.dp else 0.dp, Color(0xFF777B7E), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                connectionKindBadge(it.kind),
                                color = if (theme == AppTheme.SKEUOMORPHIC) MaterialTheme.colorScheme.onSurface else serviceForegroundColor(it.kind.name, theme),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    favorite.name,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

private fun favoriteIcon(favorite: RemoteFavorite) = when {
    favorite.isDirectory -> Icons.Default.Folder
    favorite.name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "gif", "heic", "webp") -> Icons.Default.Image
    favorite.name.substringAfterLast('.', "").lowercase() in setOf("mp4", "mov", "m4v", "mkv", "avi", "webm") -> Icons.Default.Movie
    else -> Icons.Default.InsertDriveFile
}

private fun connectionKindBadge(kind: ConnectionKind) = when (kind) {
    ConnectionKind.SYNOLOGY -> "N"
    ConnectionKind.SFTP -> "S"
    ConnectionKind.SMB -> "M"
    ConnectionKind.WEBDAV -> "W"
    ConnectionKind.FTP -> "F"
    ConnectionKind.DROPBOX -> "D"
    ConnectionKind.ONEDRIVE -> "O"
    ConnectionKind.GOOGLE_DRIVE -> "G"
}

@Composable
private fun SectionTitle(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    val theme = LocalNasFinderTheme.current
    Row(
        Modifier.padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(if (theme == AppTheme.SKEUOMORPHIC) 7.dp else 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            if (theme == AppTheme.SKEUOMORPHIC) EnamelIconWell(it, MaterialTheme.colorScheme.onSurfaceVariant)
            else Icon(it, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EnamelIconWell(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    size: Dp = 25.dp,
    iconSize: Dp = 13.dp,
) {
    Box(
        Modifier.size(size)
            .shadow(2.dp, CircleShape, ambientColor = Color.Black.copy(alpha = .20f), spotColor = Color.Black.copy(alpha = .24f))
            .background(Brush.linearGradient(listOf(Color.White, Color(0xFFD1D0CC))), CircleShape)
            .border(1.5.dp, Brush.linearGradient(listOf(Color.White, Color(0xFF5C6165))), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(iconSize), tint = tint)
    }
}

private val DashboardIconWellSize = 32.dp
private val DashboardIconGlyphSize = 17.dp

@Composable
private fun DashboardCard(content: @Composable ColumnScope.() -> Unit) {
    val enamel = LocalNasFinderTheme.current == AppTheme.SKEUOMORPHIC
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = if (enamel) .96f else .88f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = if (enamel) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .72f)) else null,
        shadowElevation = if (enamel) 2.dp else 0.dp,
    ) { Column(content = content) }
}

@Composable
private fun DashboardRowDivider() {
    HorizontalDivider(Modifier.padding(start = 58.dp, end = 16.dp))
}

@Composable
private fun DeviceStorageCard(onImportFiles: () -> Unit) {
    val filesDir = LocalContext.current.filesDir
    val total = filesDir.totalSpace.coerceAtLeast(0L)
    val available = filesDir.usableSpace.coerceIn(0L, total.coerceAtLeast(1L))
    val usedFraction = if (total > 0L) ((total - available).toDouble() / total).coerceIn(0.0, 1.0).toFloat() else 0f
    DashboardCard {
        Row(
            Modifier.fillMaxWidth().clickable(onClickLabel = "Android 파일 선택기에서 파일을 고릅니다.", onClick = onImportFiles)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (LocalNasFinderTheme.current == AppTheme.SKEUOMORPHIC) {
                EnamelIconWell(
                    Icons.Default.Folder,
                    MaterialTheme.colorScheme.onSurface,
                    DashboardIconWellSize,
                    DashboardIconGlyphSize,
                )
            } else {
                Icon(Icons.Default.Folder, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Android 저장공간", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (total > 0L) "전체 ${formatBytes(total)} · 사용 가능 ${formatBytes(available)}" else "저장공간 정보를 확인할 수 없습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("파일 선택기 열기 · 클라우드 저장소 지원", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .72f))
            }
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (total > 0L) {
            HorizontalDivider(Modifier.padding(horizontal = 18.dp))
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("저장공간", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(progress = { usedFraction }, modifier = Modifier.fillMaxWidth())
                Text("${(usedFraction * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DashboardRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String?, iconTint: Color = MaterialTheme.colorScheme.primary, titleColor: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (LocalNasFinderTheme.current == AppTheme.SKEUOMORPHIC) {
            EnamelIconWell(icon, iconTint, DashboardIconWellSize, DashboardIconGlyphSize)
        }
        else Icon(icon, null, tint = iconTint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp)); Text(title, Modifier.weight(1f), color = titleColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp)) }
        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SuperThumbnailDashboardRow(detail: String, onClick: () -> Unit) {
    val enamel = LocalNasFinderTheme.current == AppTheme.SKEUOMORPHIC
    Row(
        Modifier.fillMaxWidth().heightIn(min = 54.dp).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (enamel) {
            EnamelIconWell(
                Icons.Default.AutoAwesome,
                MaterialTheme.colorScheme.onSurface,
                DashboardIconWellSize,
                DashboardIconGlyphSize,
            )
        } else Box(
            Modifier.size(30.dp).background(
                Brush.linearGradient(listOf(Color(0xFF5856D6), Color(0xFF0A84FF), Color(0xFF32ADE6))),
                RoundedCornerShape(8.dp),
            ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(width = 19.dp, height = 15.dp).offset(x = (-2).dp, y = 2.dp)
                    .background(Color.White.copy(alpha = .16f), RoundedCornerShape(3.dp))
                    .border(1.1.dp, Color.White.copy(alpha = .88f), RoundedCornerShape(3.dp)),
            )
            Icon(Icons.Default.Image, null, Modifier.size(11.dp).offset(x = (-2).dp, y = 2.dp), tint = Color.White.copy(alpha = .94f))
            Icon(Icons.Default.AutoAwesome, null, Modifier.size(11.dp).offset(x = 7.dp, y = (-7).dp).graphicsLayer(rotationZ = -14f), tint = Color.White)
        }
        Spacer(Modifier.width(11.dp))
        Text("Super Thumbnail", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BrowserDashboardRow(onClick: () -> Unit) {
    val enamel = LocalNasFinderTheme.current == AppTheme.SKEUOMORPHIC
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onClick).padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            if (enamel) EnamelIconWell(
                Icons.Default.Language,
                MaterialTheme.colorScheme.onSurface,
                DashboardIconWellSize,
                DashboardIconGlyphSize,
            )
            else Icon(Icons.Default.Language, null, Modifier.size(32.dp), tint = BrowserOrange)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Browser", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "WWW",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = BrowserOrange,
                    modifier = Modifier.background(BrowserOrange.copy(alpha = .10f), CircleShape).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Box(Modifier.size(width = 44.dp, height = 48.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.ArrowForward, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun PhoneHardDashboardRow(detail: String? = null, onClick: () -> Unit) {
    val enamel = LocalNasFinderTheme.current == AppTheme.SKEUOMORPHIC
    Row(
        Modifier.fillMaxWidth().heightIn(min = 54.dp).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (enamel) {
            PhoneHardMark(DashboardIconWellSize)
        } else {
            Image(
                painter = painterResource(R.drawable.phone_hard_logo),
                contentDescription = null,
                modifier = Modifier.size(DashboardIconWellSize).clip(RoundedCornerShape(7.dp)),
                contentScale = ContentScale.Fit,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text("폰하드", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        detail?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
        }
        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ConnectionRow(connection: RemoteConnection, preferred: Boolean, theme: AppTheme, onOpen: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, onPreferred: () -> Unit, onUp: () -> Unit, onDown: () -> Unit) {
    val color = serviceColor(connection.kind.name, theme)
    Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).clickable(onClick = onOpen).padding(start = 14.dp, top = 2.dp, bottom = 2.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            if (theme == AppTheme.SKEUOMORPHIC) {
                EnamelIconWell(
                    connectionKindIcon(connection.kind),
                    MaterialTheme.colorScheme.onSurface,
                    DashboardIconWellSize,
                    DashboardIconGlyphSize,
                )
            } else {
                Icon(connectionKindIcon(connection.kind), null, Modifier.size(32.dp), tint = color)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(connection.name, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(6.dp))
                if (preferred) { Spacer(Modifier.width(5.dp)); Icon(Icons.Default.Star, "기본 연결", tint = color, modifier = Modifier.size(14.dp)) }
                Text(connectionKindDashboardLabel(connection.kind), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = color, modifier = Modifier.background(color.copy(alpha = .10f), CircleShape).padding(horizontal = 6.dp, vertical = 2.dp))
                }
                Text(connection.host, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        var menu by remember { mutableStateOf(false) }
        IconButton(onClick = { menu = true }, modifier = Modifier.size(width = 44.dp, height = 54.dp)) { Icon(Icons.Default.MoreHoriz, "더 보기") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(if (preferred) "기본 연결 해제" else "기본 연결") }, leadingIcon = { Icon(Icons.Default.Star, null) }, onClick = { menu = false; onPreferred() })
            DropdownMenuItem(text = { Text("위로") }, leadingIcon = { Icon(Icons.Default.ArrowUpward, null) }, onClick = { menu = false; onUp() })
            DropdownMenuItem(text = { Text("아래로") }, leadingIcon = { Icon(Icons.Default.ArrowDownward, null) }, onClick = { menu = false; onDown() })
            DropdownMenuItem(text = { Text("수정") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menu = false; onEdit() })
            DropdownMenuItem(text = { Text("삭제") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menu = false; onDelete() })
        }
    }
}

private fun connectionKindDashboardLabel(kind: ConnectionKind) = when (kind) {
    ConnectionKind.SYNOLOGY -> "NAS"
    ConnectionKind.SFTP -> "SFTP"
    ConnectionKind.SMB -> "SMB"
    ConnectionKind.WEBDAV -> "WebDAV"
    ConnectionKind.FTP -> "FTP"
    ConnectionKind.DROPBOX -> "Dropbox"
    ConnectionKind.ONEDRIVE -> "OneDrive"
    ConnectionKind.GOOGLE_DRIVE -> "Google Drive"
}

@Composable
private fun ConnectionEditor(editing: RemoteConnection?, state: AppState, model: NasFinderViewModel) {
    val draftId = remember { editing?.id ?: java.util.UUID.randomUUID().toString() }
    var kind by remember { mutableStateOf(editing?.kind ?: ConnectionKind.SYNOLOGY) }
    var name by remember { mutableStateOf(editing?.name.orEmpty()) }; var host by remember { mutableStateOf(editing?.host.orEmpty()) }
    var port by remember { mutableStateOf((editing?.port ?: kind.defaultPort).toString()) }; var username by remember { mutableStateOf(editing?.username.orEmpty()) }
    var password by remember { mutableStateOf("") }; var rootPath by remember { mutableStateOf(editing?.rootPath ?: kind.defaultRootPath) }
    var synologyOtp by remember { mutableStateOf("") }
    var tls by remember { mutableStateOf(editing?.usesTls ?: kind.supportsTls) }
    var trustedHostKey by remember { mutableStateOf(editing?.trustedHostKey) }
    var useSftpPrivateKey by remember { mutableStateOf(false) }
    var pendingHostKey by remember { mutableStateOf<com.armsone.nasfinder.network.SftpHostKeyTrustRequired?>(null) }
    val oauthProvider = CloudOAuthProvider.from(kind)
    var oauthClientId by remember(kind) { mutableStateOf(oauthProvider?.let(state.oauthClientIds::get).orEmpty()) }
    val oauthConnected = draftId in state.oauthConnectedConnectionIds
    val oauthConfigured = oauthClientId.isNotBlank()
    val oauthPending = state.oauthPendingConnectionId == draftId
    val otpValid = synologyOtp.isEmpty() || synologyOtp.length in 6..8
    val valid = if (kind.oauth) name.isNotBlank() && (password.isNotBlank() || oauthConnected)
        else name.isNotBlank() && host.isNotBlank() && username.isNotBlank() && password.isNotBlank() && port.toIntOrNull() in 1..65535 && (kind != ConnectionKind.SYNOLOGY || otpValid)
    fun candidate() = RemoteConnection(draftId, name.trim(), kind, (host.ifBlank { cloudApiHost(kind) }).trim().removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore(':'), port.toIntOrNull() ?: kind.defaultPort, username.trim(), rootPath, tls, trustedHostKey, editing?.createdAt ?: System.currentTimeMillis())
    Scaffold(containerColor = Color.Transparent, topBar = { TopAppBar(title = { Text(if (editing == null) "연결 추가" else "연결 수정") }, navigationIcon = { IconButton({ model.show(Screen.Dashboard) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } }, actions = { TextButton(onClick = { val oneTimeOtp = synologyOtp; synologyOtp = ""; model.testConnection(candidate(), password, oneTimeOtp, onTrustRequired = { pendingHostKey = it }) { model.saveConnection(candidate(), password) } }, enabled = valid && !state.isBusy) { Text(if (editing == null) "연결" else "저장") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("연결 방식", style = MaterialTheme.typography.titleSmall) }
            item {
                ConnectionKindGrid(
                    selected = kind,
                    theme = state.theme,
                    onSelected = { value ->
                        kind = value
                        port = value.defaultPort.toString()
                        rootPath = value.defaultRootPath
                        tls = value.supportsTls
                        trustedHostKey = null
                        useSftpPrivateKey = false
                        password = ""
                        synologyOtp = ""
                        if (value.oauth) host = cloudApiHost(value)
                    },
                )
            }
            item { Text(kind.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (kind.oauth) {
                item { Text("계정 정보", style = MaterialTheme.typography.titleSmall) }
                item { OutlinedTextField(name, { name = it }, label = { Text("표시 이름") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                item { OutlinedTextField(username, { username = it }, label = { Text("계정 표시 (선택)") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                item {
                    OutlinedTextField(
                        oauthClientId,
                        { oauthClientId = it },
                        label = { Text("${kind.title} client ID") },
                        supportingText = { Text("공개 앱 client ID만 입력합니다. client secret은 사용하지 않습니다.") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
                item {
                    OutlinedButton(
                        onClick = { model.saveOAuthClientId(kind, oauthClientId) },
                        enabled = oauthConfigured && !state.isBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("client ID 저장") }
                }
                if (oauthConfigured) {
                    item {
                        Button(
                            onClick = { model.beginOAuthLogin(candidate(), oauthClientId) },
                            enabled = name.isNotBlank() && !oauthPending && !state.isBusy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (oauthPending) "로그인 응답 대기 중" else "브라우저로 로그인") }
                    }
                    if (oauthPending) {
                        item { TextButton(onClick = model::cancelOAuthLogin, modifier = Modifier.fillMaxWidth()) { Text("로그인 대기 취소") } }
                    }
                    if (oauthConnected) {
                        item {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text("로그인 토큰이 저장되어 있습니다.", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                TextButton(onClick = { model.deleteOAuthToken(candidate()) }) { Text("로그아웃") }
                            }
                        }
                    }
                } else {
                    item { OutlinedTextField(password, { password = it }, label = { Text("수동 OAuth 액세스 토큰") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true) }
                }
                item { OutlinedButton(onClick = { model.testConnection(candidate(), password) }, enabled = valid && !state.isBusy, modifier = Modifier.fillMaxWidth()) { Text("연결만 확인") } }
                item { Text(if (oauthConfigured) "PKCE 브라우저 로그인으로 받은 토큰은 Android Keystore로 보호됩니다." else "client ID가 없으면 기존 수동 액세스 토큰 방식만 사용합니다. 토큰은 Android Keystore로 보호됩니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("서버 정보", style = MaterialTheme.typography.titleSmall)
                        Text("필수 항목을 입력하면 연결할 수 있습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item { OutlinedTextField(name, { name = it }, label = { Text("표시 이름 · 필수 (예: 우리집 NAS)") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                item { OutlinedTextField(host, { host = it }, label = { Text("서버 주소 · 필수 (예: nas.example.com)") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                item { OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("포트 · 필수") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true) }
                if (kind.supportsTls) item { Row(verticalAlignment = Alignment.CenterVertically) { Text("보안 연결(HTTPS)", Modifier.weight(1f)); Switch(tls, { tls = it; if (kind == ConnectionKind.SYNOLOGY && port in setOf("5000", "5001")) port = if (it) "5001" else "5000" }) } }
                item { OutlinedTextField(rootPath, { rootPath = it }, label = { Text("시작 폴더 · 선택") }, supportingText = { Text("변경하지 않으면 서비스의 기본 위치에서 시작합니다.") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                item { OutlinedTextField(username, { username = it }, label = { Text("사용자 이름 · 필수") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                if (kind == ConnectionKind.SFTP) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("인증 방식", style = MaterialTheme.typography.labelLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = !useSftpPrivateKey,
                                    onClick = { useSftpPrivateKey = false; password = "" },
                                    label = { Text("비밀번호") },
                                    leadingIcon = { Icon(Icons.Default.Password, null, modifier = Modifier.size(18.dp)) },
                                )
                                FilterChip(
                                    selected = useSftpPrivateKey,
                                    onClick = { useSftpPrivateKey = true; password = "" },
                                    label = { Text("개인키") },
                                    leadingIcon = { Icon(Icons.Default.Key, null, modifier = Modifier.size(18.dp)) },
                                )
                            }
                        }
                    }
                    item {
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text(if (useSftpPrivateKey) "PEM / OpenSSH 개인키 · 필수" else "비밀번호 · 필수") },
                            visualTransformation = if (useSftpPrivateKey) VisualTransformation.None else PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = !useSftpPrivateKey,
                            minLines = if (useSftpPrivateKey) 7 else 1,
                            maxLines = if (useSftpPrivateKey) 12 else 1,
                        )
                    }
                    if (useSftpPrivateKey) {
                        item {
                            Text(
                                "암호가 없는 PEM/OpenSSH 개인키만 지원합니다. 암호화된 개인키와 passphrase는 현재 연결 모델에서 지원하지 않습니다. 개인키는 비밀번호와 같은 Keystore 보호 필드에 저장됩니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    item { OutlinedTextField(password, { password = it }, label = { Text("비밀번호 · 필수") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true) }
                }
                if (kind == ConnectionKind.SYNOLOGY) {
                    item {
                        OutlinedTextField(
                            value = synologyOtp,
                            onValueChange = { synologyOtp = it.filter(Char::isDigit).take(8) },
                            label = { Text("OTP (선택)") },
                            supportingText = { Text("2단계 인증을 사용할 때 6~8자리 숫자를 입력하세요. OTP는 이번 연결 요청에만 사용하고 저장하지 않으므로, 나중에 다시 로그인할 때 새 OTP가 필요할 수 있습니다.") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            isError = synologyOtp.isNotEmpty() && !otpValid,
                        )
                    }
                }
                item { OutlinedButton(onClick = { val oneTimeOtp = synologyOtp; synologyOtp = ""; model.testConnection(candidate(), password, oneTimeOtp, onTrustRequired = { pendingHostKey = it }) }, enabled = valid && !state.isBusy, modifier = Modifier.fillMaxWidth()) { Text("연결만 확인") } }
                item { Text(securityGuidance(kind, tls), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
    pendingHostKey?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingHostKey = null },
            title = { Text("SSH 서버 키 확인") },
            text = { Text("${pending.message}\n\n서버 관리자에게 지문을 확인한 뒤에만 신뢰하세요.") },
            dismissButton = { TextButton(onClick = { pendingHostKey = null }) { Text("취소") } },
            confirmButton = {
                TextButton(onClick = {
                    trustedHostKey = pending.serializedHostKey
                    pendingHostKey = null
                }) { Text(if (pending.isChangedKey) "새 키 신뢰" else "이 키 신뢰") }
            },
        )
    }
}

private fun cloudApiHost(kind: ConnectionKind): String = when (kind) {
    ConnectionKind.DROPBOX -> "api.dropboxapi.com"
    ConnectionKind.ONEDRIVE -> "graph.microsoft.com"
    ConnectionKind.GOOGLE_DRIVE -> "www.googleapis.com"
    else -> ""
}

@Composable
private fun ConnectionKindGrid(
    selected: ConnectionKind,
    theme: AppTheme,
    onSelected: (ConnectionKind) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ConnectionKind.entries.chunked(3).forEach { rowKinds ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowKinds.forEach { kind ->
                    val selectedKind = kind == selected
                    val color = serviceColor(kind.name, theme)
                    val shape = RoundedCornerShape(11.dp)
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 64.dp)
                            .border(
                                width = 1.dp,
                                color = color.copy(alpha = if (selectedKind) .55f else .18f),
                                shape = shape,
                            )
                            .clickable { onSelected(kind) }
                            .semantics(mergeDescendants = true) { this.selected = selectedKind },
                        shape = shape,
                        color = if (selectedKind) color.copy(alpha = .14f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .72f),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            if (theme == AppTheme.SKEUOMORPHIC) {
                                EnamelIconWell(connectionKindIcon(kind), MaterialTheme.colorScheme.onSurface)
                            } else {
                                Icon(connectionKindIcon(kind), null, tint = color, modifier = Modifier.size(22.dp))
                            }
                            Spacer(Modifier.height(5.dp))
                            Text(
                                kind.title,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (selectedKind) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = color,
                            )
                        }
                    }
                }
                repeat(3 - rowKinds.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private fun connectionKindIcon(kind: ConnectionKind) = when (kind) {
    ConnectionKind.SYNOLOGY -> Icons.Default.Storage
    ConnectionKind.SFTP -> Icons.Default.Security
    ConnectionKind.SMB -> Icons.Default.FolderShared
    ConnectionKind.WEBDAV -> Icons.Default.Cloud
    ConnectionKind.FTP -> Icons.Default.SwapVert
    ConnectionKind.DROPBOX -> Icons.Default.CloudQueue
    ConnectionKind.ONEDRIVE -> Icons.Default.CloudCircle
    ConnectionKind.GOOGLE_DRIVE -> Icons.Default.AddToDrive
}

private fun securityGuidance(kind: ConnectionKind, tls: Boolean) = when (kind) {
    ConnectionKind.SYNOLOGY -> if (tls) "QuickConnect ID 대신 기기에서 접근 가능한 DDNS·도메인 또는 VPN 주소와 신뢰 가능한 인증서를 사용하세요." else "HTTP는 같은 로컬 네트워크에서만 사용하세요."
    ConnectionKind.SFTP -> "연결 시 SSH 호스트 키 지문을 확인하고 고정해야 합니다."
    ConnectionKind.SMB -> "SMB 2.0 이상을 사용합니다."
    ConnectionKind.WEBDAV -> "HTTPS와 서비스에서 발급한 앱 비밀번호를 권장합니다."
    ConnectionKind.FTP -> "FTP는 암호화되지 않습니다. 신뢰하는 로컬 네트워크에서만 사용하세요."
    else -> "로그인 토큰은 Android Keystore로 보호됩니다."
}

@Composable
private fun BrowserScreen(browser: Screen.Browser, state: AppState, model: NasFinderViewModel) {
    var searchText by remember(browser.connection.id, browser.path) { mutableStateOf("") }
    var searchVisible by rememberSaveable(browser.connection.id, browser.path) { mutableStateOf(false) }
    var creatingFolder by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }
    var renamingItem by remember { mutableStateOf<RemoteFileItem?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deletingItem by remember { mutableStateOf<RemoteFileItem?>(null) }
    var actionItem by remember { mutableStateOf<RemoteFileItem?>(null) }
    var selectionMode by remember(browser.connection.id, browser.path) { mutableStateOf(false) }
    val selectedIds = remember(browser.connection.id, browser.path) { mutableStateListOf<String>() }
    var batchTransfer by remember { mutableStateOf<RemoteTransferAction?>(null) }
    var confirmBatchDelete by remember { mutableStateOf(false) }
    val capabilities = remember(browser.connection.kind) { browserCapabilities(browser.connection.kind) }
    val accessibilityLayout = LocalConfiguration.current.fontScale >= 1.3f
    val showsCoverFlow = browser.preferences.layout == BrowserLayout.LARGE_GRID &&
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var coverFlowDark by rememberSaveable(browser.connection.id) { mutableStateOf(false) }
    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(model::uploadDocument)
    }
    val displayedItems = remember(browser.items, searchText) {
        val query = searchText.trim()
        if (query.isEmpty()) browser.items
        else browser.items.filter { it.name.containsLocalized(query) }
    }
    val favoritePaths = remember(state.remoteFavorites, browser.connection.id) {
        state.remoteFavorites
            .filter { it.connectionId == browser.connection.id }
            .mapTo(mutableSetOf()) { it.path }
    }
    val parentPath = remoteParentPath(browser.path, browser.connection.normalizedRootPath)
    val selectedItems = browser.items.filter { it.id in selectedIds }
    LaunchedEffect(browser.items) { selectedIds.removeAll { id -> browser.items.none { it.id == id } } }
    if (showsCoverFlow) {
        BackHandler {
            if (parentPath == null) model.show(Screen.Dashboard)
            else model.openConnection(browser.connection, parentPath)
        }
        RemoteBrowserCoverFlow(
            items = displayedItems,
            thumbnails = state.remoteThumbnails,
            theme = state.theme,
            title = browser.connection.name,
            usesDarkBackground = coverFlowDark,
            onBack = {
                if (parentPath == null) model.show(Screen.Dashboard)
                else model.openConnection(browser.connection, parentPath)
            },
            onToggleBackground = { coverFlowDark = it },
            onActivate = model::openItem,
            onLoadThumbnail = model::loadRemoteThumbnail,
        )
        return
    }
    BackHandler(selectionMode) { selectedIds.clear(); selectionMode = false }
    Scaffold(containerColor = Color.Transparent, topBar = { TopAppBar(title = { Column { Text(if (selectionMode) "${selectedIds.size}개 선택" else browser.connection.name, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(browser.path, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) } }, navigationIcon = {
        CompositionLocalProvider(LocalViewConfiguration provides longPress500Configuration()) {
        Box(
            modifier = Modifier.size(48.dp).semantics(mergeDescendants = true) {
                contentDescription = if (selectionMode) "선택 종료" else if (parentPath == null && (state.pendingInboxUpload != null || state.pendingLocalUpload != null)) "NAS로 보내기 취소" else "이전 폴더"
                if (!selectionMode) {
                    customActions = listOf(
                        CustomAccessibilityAction("NasFinder 첫 화면") {
                            model.show(Screen.Dashboard)
                            true
                        }
                    )
                }
            }.combinedClickable(
                onClick = {
            if (selectionMode) { selectedIds.clear(); selectionMode = false }
            else if (parentPath == null && state.pendingLocalUpload != null) model.cancelLocalUploadDestination()
            else if (parentPath == null && state.pendingInboxUpload != null) model.cancelInboxUpload()
            else if (parentPath == null) model.show(Screen.Dashboard)
            else model.openConnection(browser.connection, parentPath)
                },
                onLongClick = if (selectionMode) null else ({ model.show(Screen.Dashboard) }),
                onLongClickLabel = if (selectionMode) null else "NasFinder 첫 화면",
            ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(32.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (selectionMode) Icons.Default.Close else Icons.Default.ChevronLeft,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        }
    }, actions = {
        if (!selectionMode) {
            IconButton(onClick = { searchVisible = true }) { Icon(Icons.Default.Search, "현재 폴더 검색") }
            BrowserMenu(
                preferences = browser.preferences,
                canCreateFolder = capabilities.createFolder,
                canPaste = capabilities.upload || state.pendingTransfer?.connectionId == browser.connection.id,
                onSelect = { selectionMode = true },
                onCreateFolder = { folderName = ""; creatingFolder = true },
                onPaste = {
                    if (state.pendingTransfer?.connectionId == browser.connection.id) model.applyTransferDestination()
                    else uploadLauncher.launch(arrayOf("*/*"))
                },
                onRefresh = model::refreshBrowser,
                update = model::updateBrowserPreferences,
            )
        }
    }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)) }, bottomBar = {
        if (selectionMode) BottomAppBar {
            IconButton(onClick = { model.shareItems(selectedItems) }, enabled = selectedItems.isNotEmpty() && selectedItems.none(RemoteFileItem::isDirectory)) { Icon(Icons.Default.Share, "선택 파일 공유") }
            IconButton(onClick = { batchTransfer = RemoteTransferAction.COPY }, enabled = selectedItems.isNotEmpty() && capabilities.copy) { Icon(Icons.Default.ContentCopy, "선택 항목 복사") }
            IconButton(onClick = { batchTransfer = RemoteTransferAction.MOVE }, enabled = selectedItems.isNotEmpty() && capabilities.move) { Icon(Icons.Default.DriveFileMove, "선택 항목 이동") }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { confirmBatchDelete = true }, enabled = selectedItems.isNotEmpty() && capabilities.delete) { Icon(Icons.Default.Delete, "선택 항목 삭제", tint = MaterialTheme.colorScheme.error) }
        }
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ThumbnailTrafficStatus(state.thumbnailTraffic)
            state.pendingInboxUpload?.let { pending ->
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .94f),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CloudUpload, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (pending.isUploading) "NAS로 보내는 중…" else "NAS로 보낼 폴더를 선택하세요", style = MaterialTheme.typography.labelLarge)
                            Text("${pending.items.size}개 파일 → ${browser.path}", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(onClick = model::cancelInboxUpload, enabled = !pending.isUploading) { Text("취소") }
                        Button(
                            onClick = model::applyInboxUploadDestination,
                            enabled = pending.connectionId == browser.connection.id && capabilities.upload && !pending.isUploading && !state.isBusy,
                        ) { Text("여기로 보내기") }
                    }
                }
            }
            state.pendingLocalUpload?.let { pending ->
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .94f),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CloudUpload, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (pending.isUploading) "네트워크에 저장하는 중…" else "저장할 폴더를 선택하세요", style = MaterialTheme.typography.labelLarge)
                            Text("${pending.filename} → ${browser.path}", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(onClick = model::cancelLocalUploadDestination, enabled = !pending.isUploading) { Text("취소") }
                        Button(
                            onClick = model::applyLocalUploadDestination,
                            enabled = pending.connectionId == browser.connection.id && capabilities.upload && !pending.isUploading && !state.isBusy,
                        ) { Text("여기에 저장") }
                    }
                }
            }
            state.pendingTransfer?.let { pending ->
                val verb = if (pending.action == RemoteTransferAction.COPY) "복사" else "이동"
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .92f),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${pending.items.size}개 항목 $verb 대상: ${browser.path}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        TextButton(onClick = model::cancelTransfer) { Text("취소") }
                        Button(
                            onClick = model::applyTransferDestination,
                            enabled = pending.connectionId == browser.connection.id && !state.isBusy,
                        ) { Text("여기에 $verb") }
                    }
                }
            }
            if (searchVisible || searchText.isNotEmpty()) {
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    placeholder = { Text("현재 폴더 검색") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        IconButton(onClick = { searchText = ""; searchVisible = false }) {
                            Icon(Icons.Default.Cancel, "검색 닫기")
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                )
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (parentPath != null) TextButton(onClick = { model.openConnection(browser.connection, parentPath) }) { Icon(Icons.Default.ArrowUpward, null); Spacer(Modifier.width(4.dp)); Text("상위 폴더") }
                Spacer(Modifier.weight(1f))
                Text(if (searchText.isBlank()) "${browser.items.size}개 항목" else "${displayedItems.size}/${browser.items.size}개 검색", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                browser.items.isEmpty() && !state.isBusy -> EmptyState(
                    icon = Icons.Default.Folder,
                    title = "빈 폴더",
                    description = "이 폴더에는 표시할 파일이 없습니다.",
                    modifier = Modifier.weight(1f),
                )
                displayedItems.isEmpty() -> EmptyState(
                    icon = Icons.Default.Search,
                    title = "검색 결과가 없습니다",
                    description = "‘${searchText.trim()}’과 일치하는 항목을 찾지 못했습니다.",
                    modifier = Modifier.weight(1f),
                )
                browser.preferences.layout == BrowserLayout.LIST -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(displayedItems, key = { it.id }) { item ->
                        if (item.isImage || item.isVideo) LaunchedEffect(browser.connection.id, browser.path, state.thumbnailGeneration, item.id, item.size, item.modifiedAt) { model.loadRemoteThumbnail(item) }
                        FileRow(item, thumbnail = state.remoteThumbnails[item.id], theme = state.theme, selected = item.id in selectedIds, selectionMode = selectionMode, accessibilityLayout = accessibilityLayout, onClick = { if (selectionMode) toggleRemoteSelection(selectedIds, item.id) else model.openItem(item) }, onLongClick = { actionItem = item })
                    }
                }
                else -> {
                    val poster = browser.preferences.layout == BrowserLayout.LARGE_GRID
                    val minimumCellWidth = when {
                        poster && accessibilityLayout -> 270.dp
                        poster -> 158.dp
                        accessibilityLayout -> 118.dp
                        else -> 78.dp
                    }
                    LazyVerticalGrid(
                        GridCells.Adaptive(minimumCellWidth),
                        Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(if (poster) 16.dp else 10.dp),
                        verticalArrangement = Arrangement.spacedBy(if (poster) 20.dp else 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(if (poster) 16.dp else 8.dp),
                    ) {
                    items(displayedItems, key = { it.id }) { item ->
                        if (item.isImage || item.isVideo) LaunchedEffect(browser.connection.id, browser.path, state.thumbnailGeneration, item.id, item.size, item.modifiedAt) { model.loadRemoteThumbnail(item) }
                        FileTile(item, thumbnail = state.remoteThumbnails[item.id], theme = state.theme, poster = poster, selected = item.id in selectedIds, selectionMode = selectionMode, onClick = { if (selectionMode) toggleRemoteSelection(selectedIds, item.id) else model.openItem(item) }, onLongClick = { actionItem = item })
                    }
                    }
                }
            }
        }
    }
    if (creatingFolder) {
        NameInputDialog(
            title = "새 폴더",
            value = folderName,
            confirmLabel = "만들기",
            onValueChange = { folderName = it },
            onDismiss = { creatingFolder = false },
            onConfirm = { creatingFolder = false; model.createFolder(folderName) },
        )
    }
    renamingItem?.let { item ->
        NameInputDialog(
            title = "이름 변경",
            value = renameText,
            confirmLabel = "변경",
            onValueChange = { renameText = it },
            onDismiss = { renamingItem = null },
            onConfirm = { renamingItem = null; model.renameItem(item, renameText) },
        )
    }
    deletingItem?.let { item ->
        AlertDialog(
            onDismissRequest = { deletingItem = null },
            title = { Text(if (item.isDirectory) "폴더를 삭제할까요?" else "파일을 삭제할까요?") },
            text = { Text(if (item.isDirectory) "${item.name} 폴더와 내부의 모든 항목이 원격 서버에서 삭제됩니다. 되돌릴 수 없습니다." else "${item.name} 파일이 원격 서버에서 삭제됩니다. 되돌릴 수 없습니다.") },
            dismissButton = { TextButton(onClick = { deletingItem = null }) { Text("취소") } },
            confirmButton = {
                TextButton(
                    onClick = { deletingItem = null; model.deleteItem(item) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("삭제") }
            },
        )
    }
    actionItem?.let { item ->
        ModalBottomSheet(onDismissRequest = { actionItem = null }) {
            ListItem(
                headlineContent = { Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(if (item.isDirectory) "폴더" else formatBytes(item.size)) },
                leadingContent = { Icon(if (item.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile, null) },
            )
            ListItem(headlineContent = { Text(if (item.id in selectedIds) "선택 해제" else "선택") }, leadingContent = { Icon(Icons.Default.CheckCircle, null) }, modifier = Modifier.clickable { selectionMode = true; toggleRemoteSelection(selectedIds, item.id); actionItem = null })
            ListItem(
                headlineContent = { Text(if (item.path in favoritePaths) "즐겨찾기 해제" else "즐겨찾기 추가") },
                leadingContent = { Icon(if (item.path in favoritePaths) Icons.Default.Star else Icons.Default.StarBorder, null) },
                modifier = Modifier.clickable { actionItem = null; model.toggleFavorite(item) },
            )
            if (!item.isDirectory) ListItem(headlineContent = { Text("공유") }, leadingContent = { Icon(Icons.Default.Share, null) }, modifier = Modifier.clickable { actionItem = null; model.shareItem(item) })
            if (capabilities.copy) ListItem(headlineContent = { Text("복사") }, leadingContent = { Icon(Icons.Default.ContentCopy, null) }, modifier = Modifier.clickable { actionItem = null; model.beginTransfer(item, RemoteTransferAction.COPY) })
            if (capabilities.move) ListItem(headlineContent = { Text("이동") }, leadingContent = { Icon(Icons.Default.DriveFileMove, null) }, modifier = Modifier.clickable { actionItem = null; model.beginTransfer(item, RemoteTransferAction.MOVE) })
            if (capabilities.rename) ListItem(headlineContent = { Text("이름 변경") }, leadingContent = { Icon(Icons.Default.Edit, null) }, modifier = Modifier.clickable { actionItem = null; renameText = item.name; renamingItem = item })
            if (capabilities.delete) ListItem(headlineContent = { Text("삭제", color = MaterialTheme.colorScheme.error) }, leadingContent = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }, modifier = Modifier.clickable { actionItem = null; deletingItem = item })
            Spacer(Modifier.navigationBarsPadding().height(12.dp))
        }
    }
    batchTransfer?.let { action ->
        val verb = if (action == RemoteTransferAction.COPY) "복사" else "이동"
        AlertDialog(
            onDismissRequest = { batchTransfer = null },
            title = { Text("${selectedItems.size}개 항목을 ${verb}할까요?") },
            text = { Text("다음 화면에서 대상 폴더를 선택합니다.") },
            dismissButton = { TextButton(onClick = { batchTransfer = null }) { Text("취소") } },
            confirmButton = { TextButton(onClick = { batchTransfer = null; model.beginTransfer(selectedItems, action); selectedIds.clear(); selectionMode = false }) { Text(verb) } },
        )
    }
    if (confirmBatchDelete) {
        AlertDialog(
            onDismissRequest = { confirmBatchDelete = false },
            title = { Text("${selectedItems.size}개 항목을 삭제할까요?") },
            text = { Text("선택한 폴더의 내부 항목도 원격 서버에서 삭제되며 되돌릴 수 없습니다.") },
            dismissButton = { TextButton(onClick = { confirmBatchDelete = false }) { Text("취소") } },
            confirmButton = { TextButton(onClick = { confirmBatchDelete = false; model.deleteItems(selectedItems); selectedIds.clear(); selectionMode = false }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("삭제") } },
        )
    }
}

@Composable
internal fun RemoteBrowserCoverFlow(
    items: List<RemoteFileItem>,
    thumbnails: Map<String, android.graphics.Bitmap>,
    theme: AppTheme,
    title: String,
    usesDarkBackground: Boolean,
    onBack: () -> Unit,
    onToggleBackground: (Boolean) -> Unit,
    onActivate: (RemoteFileItem) -> Unit,
    onLoadThumbnail: (RemoteFileItem) -> Unit,
) {
    var backgroundMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    var selectedIndex by remember(items) { mutableIntStateOf(0) }
    LaunchedEffect(listState, items) {
        snapshotFlow {
            val layout = listState.layoutInfo
            val center = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
            layout.visibleItemsInfo.minByOrNull { kotlin.math.abs((it.offset + it.size / 2) - center) }?.index
        }.collect { index -> if (index != null) selectedIndex = index }
    }
    val background = if (usesDarkBackground) Color.Black else Color.White
    val chromeForeground = if (usesDarkBackground) Color.White.copy(alpha = .88f) else Color.Black.copy(alpha = .82f)
    val chromeBackground = if (usesDarkBackground) Color.White.copy(alpha = .10f) else Color.White.copy(alpha = .92f)
    val chromeBorder = if (usesDarkBackground) Color.White.copy(alpha = .16f) else Color.Black.copy(alpha = .10f)

    BoxWithConstraints(Modifier.fillMaxSize().background(background)) {
        val cardSide = minOf(maxWidth * .38f, maxHeight * .76f).coerceIn(230.dp, 460.dp)
        val step = (maxWidth * .055f).coerceIn(42.dp, 66.dp)
        val reflectionHeight = if (usesDarkBackground) 44.dp else 20.dp
        if (items.isEmpty()) {
            EmptyState(Icons.Default.Folder, "빈 폴더", "이 폴더에는 표시할 파일이 없습니다.", Modifier.fillMaxSize())
        } else {
            LazyRow(
                state = listState,
                flingBehavior = rememberSnapFlingBehavior(listState),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = (maxWidth - step) / 2),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                items(items, key = { it.id }) { item ->
                    val index = items.indexOf(item)
                    val selected = index == selectedIndex
                    if (item.isImage || item.isVideo) LaunchedEffect(item.id, item.size, item.modifiedAt) { onLoadThumbnail(item) }
                    Box(Modifier.width(step).padding(bottom = 18.dp).zIndex(10f - kotlin.math.abs(index - selectedIndex).toFloat()), contentAlignment = Alignment.BottomCenter) {
                        Column(
                            Modifier.requiredWidth(cardSide).graphicsLayer {
                                scaleX = if (selected) 1f else .80f
                                scaleY = if (selected) 1f else .80f
                                rotationY = when { selected -> 0f; index < selectedIndex -> 42f; else -> -42f }
                                cameraDistance = 14f * density
                                alpha = if (kotlin.math.abs(index - selectedIndex) > 7) 0f else 1f
                            }.semantics { contentDescription = item.name }.clickable { onActivate(item) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Surface(
                                modifier = Modifier.requiredSize(cardSide),
                                shape = RoundedCornerShape(if (selected) 18.dp else 13.dp),
                                color = Color.Black,
                                border = BorderStroke(1.dp, if (usesDarkBackground) Color.White.copy(alpha = .16f) else Color.Black.copy(alpha = .15f)),
                                shadowElevation = if (selected) 10.dp else 2.dp,
                            ) {
                                RemoteFileArtwork(item, thumbnails[item.id], theme, Modifier.fillMaxSize(), cardSide * .42f, if (selected) 18.dp else 13.dp)
                            }
                            RemoteFileArtwork(
                                item,
                                thumbnails[item.id],
                                theme,
                                Modifier.width(cardSide).height(reflectionHeight).graphicsLayer { rotationX = 180f; alpha = if (usesDarkBackground) .24f else .12f },
                                22.dp,
                                8.dp,
                            )
                        }
                    }
                }
            }
        }
        Box(
            Modifier.fillMaxWidth().height(if (usesDarkBackground) 72.dp else 82.dp).align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, if (usesDarkBackground) Color.White.copy(alpha = .10f) else Color.Black.copy(alpha = .06f))))
        )
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(onClick = onBack, modifier = Modifier.size(44.dp), shape = CircleShape, color = chromeBackground, border = BorderStroke(1.dp, chromeBorder), shadowElevation = 2.dp) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.ChevronLeft, "이전 폴더", tint = chromeForeground) }
            }
            Text(title, color = chromeForeground, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Box {
                Surface(onClick = { backgroundMenu = true }, modifier = Modifier.size(44.dp), shape = CircleShape, color = chromeBackground, border = BorderStroke(1.dp, chromeBorder), shadowElevation = 2.dp) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.MoreHoriz, "Cover Flow 배경", tint = MaterialTheme.colorScheme.primary) }
                }
                DropdownMenu(backgroundMenu, onDismissRequest = { backgroundMenu = false }) {
                    DropdownMenuItem(text = { Text("밝은 배경") }, trailingIcon = { if (!usesDarkBackground) Icon(Icons.Default.Check, null) }, onClick = { backgroundMenu = false; onToggleBackground(false) })
                    DropdownMenuItem(text = { Text("어두운 배경") }, trailingIcon = { if (usesDarkBackground) Icon(Icons.Default.Check, null) }, onClick = { backgroundMenu = false; onToggleBackground(true) })
                }
            }
        }
    }
}

private data class BrowserCapabilities(
    val createFolder: Boolean,
    val rename: Boolean,
    val delete: Boolean,
    val upload: Boolean,
    val copy: Boolean,
    val move: Boolean,
)

private fun browserCapabilities(kind: ConnectionKind): BrowserCapabilities = when (kind) {
    ConnectionKind.SYNOLOGY, ConnectionKind.SFTP, ConnectionKind.SMB, ConnectionKind.WEBDAV,
    ConnectionKind.FTP, ConnectionKind.DROPBOX, ConnectionKind.ONEDRIVE, ConnectionKind.GOOGLE_DRIVE ->
        BrowserCapabilities(true, true, true, true, true, true)
}

private fun toggleRemoteSelection(selectedIds: MutableList<String>, id: String) {
    if (id in selectedIds) selectedIds.remove(id) else selectedIds.add(id)
}

@Composable
private fun ThumbnailTrafficStatus(traffic: com.armsone.nasfinder.data.RemoteThumbnailTrafficSnapshot) {
    if (traffic.requestCount == 0 && !traffic.limitReached) return
    val maxBytes = 64L * 1024 * 1024
    val fraction = if (traffic.limitReached) 1f else
        (maxOf(traffic.expectedBytes, traffic.actualBytes).toDouble() / maxBytes)
            .coerceIn(0.0, 1.0).toFloat()
    Column(Modifier.fillMaxWidth()) {
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(3.dp))
        Text(
            if (traffic.limitReached) "썸네일 사용 한도 도달 · 요청 ${traffic.requestCount}회 · 예상 ${formatBytes(traffic.expectedBytes)} · 실제 ${formatBytes(traffic.actualBytes)}"
            else "썸네일 요청 ${traffic.requestCount}회 · 예상 ${formatBytes(traffic.expectedBytes)} · 실제 ${formatBytes(traffic.actualBytes)}",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (traffic.limitReached) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun remoteParentPath(path: String, rootPath: String): String? {
    val root = rootPath.trimEnd('/').ifBlank { "/" }
    val current = path.trimEnd('/').ifBlank { "/" }
    if (current == root || current == "/") return null
    val parent = current.substringBeforeLast('/', "").ifBlank { "/" }
    return if (root != "/" && !parent.startsWith(root)) root else parent
}

@Composable
private fun FileRow(item: RemoteFileItem, thumbnail: android.graphics.Bitmap?, theme: AppTheme, selected: Boolean, selectionMode: Boolean, accessibilityLayout: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    CompositionLocalProvider(LocalViewConfiguration provides longPress450Configuration()) { Surface(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "작업 보기"), color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            val artworkSide = if (accessibilityLayout) 50.dp else 58.dp
            RemoteFileArtwork(item, thumbnail, theme, Modifier.size(artworkSide), 30.dp, 9.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = if (accessibilityLayout) 3 else 2, overflow = TextOverflow.Ellipsis)
                if (!item.isDirectory) Text(
                    listOfNotNull(item.extension.uppercase().takeIf(String::isNotBlank), formatBytes(item.size)).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                item.modifiedAt?.let { modified ->
                    Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date.from(modified)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (selectionMode) Checkbox(selected, { onClick() })
        }
    } }
}

@Composable
private fun FileTile(item: RemoteFileItem, thumbnail: android.graphics.Bitmap?, theme: AppTheme, poster: Boolean, selected: Boolean, selectionMode: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val corner = if (poster) 15.dp else 11.dp
    CompositionLocalProvider(LocalViewConfiguration provides longPress450Configuration()) {
        Column(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "작업 보기"), verticalArrangement = Arrangement.spacedBy(if (poster) 9.dp else 6.dp)) {
            Box {
                Surface(Modifier.fillMaxWidth().aspectRatio(1f), shape = RoundedCornerShape(corner), color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = .88f), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    RemoteFileArtwork(item, thumbnail, theme, Modifier.fillMaxSize(), if (poster) 52.dp else 42.dp, corner)
                }
                if (selectionMode) Checkbox(selected, { onClick() }, modifier = Modifier.align(Alignment.TopEnd))
            }
                Text(
                    item.name,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = if (poster) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelMedium,
                    fontWeight = if (poster) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.fillMaxWidth(),
                )
            if (!item.isDirectory) {
                Text(
                    listOfNotNull(item.extension.uppercase().takeIf(String::isNotBlank), formatBytes(item.size)).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (poster) {
                item.modifiedAt?.let { modified ->
                    Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date.from(modified)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun longPress450Configuration(): ViewConfiguration {
    val base = LocalViewConfiguration.current
    return remember(base) {
        object : ViewConfiguration by base {
            override val longPressTimeoutMillis: Long = 450L
        }
    }
}

@Composable
private fun longPress500Configuration(): ViewConfiguration {
    val base = LocalViewConfiguration.current
    return remember(base) {
        object : ViewConfiguration by base {
            override val longPressTimeoutMillis: Long = 500L
        }
    }
}

@Composable
private fun RemoteFileArtwork(item: RemoteFileItem, thumbnail: android.graphics.Bitmap?, theme: AppTheme, modifier: Modifier, iconSize: androidx.compose.ui.unit.Dp, cornerRadius: androidx.compose.ui.unit.Dp) {
    if ((item.isImage || item.isVideo) && thumbnail != null && !thumbnail.isRecycled) {
        Image(
            bitmap = thumbnail.asImageBitmap(),
            contentDescription = "${item.name} 썸네일",
            modifier = modifier.clip(RoundedCornerShape(cornerRadius)),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(modifier, contentAlignment = Alignment.Center) {
            Icon(
                if (item.isDirectory) Icons.Default.Folder else if (item.isImage) Icons.Default.Image else if (item.isVideo) Icons.Default.Movie else Icons.Default.InsertDriveFile,
                null,
                modifier = Modifier.size(iconSize),
                tint = if (item.isDirectory) folderColor(theme) else MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun FileActionsMenu(item: RemoteFileItem, capabilities: BrowserCapabilities, onRename: () -> Unit, onDelete: () -> Unit, onCopy: () -> Unit, onMove: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, "${item.name} 작업") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (capabilities.rename) DropdownMenuItem(text = { Text("이름 변경") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { expanded = false; onRename() })
            if (capabilities.copy) DropdownMenuItem(text = { Text("복사") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) }, onClick = { expanded = false; onCopy() })
            if (capabilities.move) DropdownMenuItem(text = { Text("이동") }, leadingIcon = { Icon(Icons.Default.DriveFileMove, null) }, onClick = { expanded = false; onMove() })
            if (capabilities.delete) DropdownMenuItem(text = { Text("삭제") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { expanded = false; onDelete() })
        }
    }
}

@Composable
private fun NameInputDialog(title: String, value: String, confirmLabel: String, onValueChange: (String) -> Unit, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value, onValueChange, singleLine = true, label = { Text("이름") }) },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
        confirmButton = { TextButton(onClick = onConfirm, enabled = value.trim().isNotEmpty()) { Text(confirmLabel) } },
    )
}

@Composable
private fun DownloadProgressBanner(download: RemoteDownloadState, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .96f),
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (download.action == RemoteFileAction.PREVIEW) Icons.Default.Visibility else Icons.Default.Share,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(download.action.progressTitle, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(download.filename, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            download.fraction?.let { fraction ->
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            } ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                if (download.totalBytes > 0) "${formatBytes(download.completedBytes)} / ${formatBytes(download.totalBytes)}"
                else "${formatBytes(download.completedBytes)} 받음",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BrowserMenu(
    preferences: BrowserPreferences,
    canCreateFolder: Boolean,
    canPaste: Boolean,
    onSelect: () -> Unit,
    onCreateFolder: () -> Unit,
    onPaste: () -> Unit,
    onRefresh: () -> Unit,
    update: (BrowserPreferences) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    IconButton({ expanded = true }) { Icon(Icons.Default.MoreVert, "파일과 보기 작업") }
    if (expanded) ModalBottomSheet(
        onDismissRequest = { expanded = false },
        sheetState = sheetState,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("파일 작업", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BrowserActionTile("선택", Icons.Default.CheckCircle, Modifier.weight(1f)) { expanded = false; onSelect() }
                BrowserActionTile("붙여넣기", Icons.Default.ContentPaste, Modifier.weight(1f), enabled = canPaste) { expanded = false; onPaste() }
                BrowserActionTile("새 폴더", Icons.Default.CreateNewFolder, Modifier.weight(1f), enabled = canCreateFolder) { expanded = false; onCreateFolder() }
                BrowserActionTile("새로고침", Icons.Default.Refresh, Modifier.weight(1f)) { expanded = false; onRefresh() }
            }
            BrowserMenuSection("보기") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    BrowserLayout.entries.forEach { layout ->
                        val title = when (layout) { BrowserLayout.LIST -> "자세히"; BrowserLayout.SMALL_GRID -> "작은 썸네일"; BrowserLayout.LARGE_GRID -> "포스터" }
                        val icon = when (layout) { BrowserLayout.LIST -> Icons.Default.List; BrowserLayout.SMALL_GRID -> Icons.Default.GridView; BrowserLayout.LARGE_GRID -> Icons.Default.GridOn }
                        BrowserChoice(title, preferences.layout == layout, Modifier.weight(1f), icon) { update(preferences.copy(layout = layout)); expanded = false }
                    }
                }
            }
            BrowserMenuSection("정렬") {
                Text("기준", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SortField.entries.forEach { sort -> BrowserChoice(sortTitle(sort), preferences.sortField == sort, Modifier.weight(1f)) { update(preferences.copy(sortField = sort)) } }
                }
                Text("순서", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    BrowserChoice("오름차순", preferences.sortDirection == SortDirection.ASCENDING, Modifier.weight(1f)) { update(preferences.copy(sortDirection = SortDirection.ASCENDING)) }
                    BrowserChoice("내림차순", preferences.sortDirection == SortDirection.DESCENDING, Modifier.weight(1f)) { update(preferences.copy(sortDirection = SortDirection.DESCENDING)) }
                }
                Text("이름 우선", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    BrowserChoice("숫자 먼저", preferences.namePriority == NamePriority.NUMBERS_FIRST, Modifier.weight(1f)) { update(preferences.copy(namePriority = NamePriority.NUMBERS_FIRST)) }
                    BrowserChoice("한글 먼저", preferences.namePriority == NamePriority.KOREAN_FIRST, Modifier.weight(1f)) { update(preferences.copy(namePriority = NamePriority.KOREAN_FIRST)) }
                    BrowserChoice("외국어 먼저", preferences.namePriority == NamePriority.LATIN_FIRST, Modifier.weight(1f)) { update(preferences.copy(namePriority = NamePriority.LATIN_FIRST)) }
                }
                Text("폴더 먼저", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    BrowserChoice("끔", !preferences.foldersFirst, Modifier.weight(1f)) { update(preferences.copy(foldersFirst = false)) }
                    BrowserChoice("켬", preferences.foldersFirst, Modifier.weight(1f)) { update(preferences.copy(foldersFirst = true)) }
                }
            }
        }
    }
}

@Composable
private fun BrowserMenuSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun BrowserActionTile(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, modifier = modifier.height(58.dp), shape = RoundedCornerShape(11.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .72f), contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, null, modifier = Modifier.size(25.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun BrowserChoice(label: String, selected: Boolean, modifier: Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(if (icon == null) 47.dp else 58.dp).semantics { this.selected = selected },
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .72f),
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            icon?.let { Icon(it, null, modifier = Modifier.size(24.dp)) }
            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun sortTitle(sort: SortField) = when (sort) {
    SortField.NAME -> "이름"
    SortField.KIND -> "종류"
    SortField.SIZE -> "크기"
    SortField.MODIFIED -> "수정일"
}

private enum class InboxLayout(val title: String) {
    DETAILS("자세히"),
    THUMBNAILS("썸네일"),
    POSTERS("포스터"),
    OVERFLOW("오버플로우"),
}

private fun inboxLayoutIcon(layout: InboxLayout) = when (layout) {
    InboxLayout.DETAILS -> Icons.Default.List
    InboxLayout.THUMBNAILS -> Icons.Default.GridView
    InboxLayout.POSTERS -> Icons.Default.GridOn
    InboxLayout.OVERFLOW -> Icons.Default.ViewCarousel
}

@Composable
private fun InboxScreen(state: AppState, model: NasFinderViewModel) {
    val files = state.inboxFiles
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val webHardStore = remember(context) { WebHardFileStore(context.applicationContext) }
    val webHardConnection = rememberWebHardConnectionState(webHardStore)
    val inboxPreferences = remember(context) {
        context.getSharedPreferences("inbox_ui", Context.MODE_PRIVATE)
    }
    var layout by rememberSaveable {
        mutableStateOf(
            runCatching {
                InboxLayout.valueOf(inboxPreferences.getString("layout", null).orEmpty())
            }.getOrDefault(InboxLayout.DETAILS).let {
                if (it == InboxLayout.OVERFLOW) InboxLayout.POSTERS else it
            },
        )
    }
    val configuredLayout = if (layout == InboxLayout.OVERFLOW) InboxLayout.POSTERS else layout
    val inboxLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val showsAutomaticOverflow = configuredLayout == InboxLayout.POSTERS && inboxLandscape
    val displayedLayout = if (showsAutomaticOverflow) InboxLayout.OVERFLOW else configuredLayout
    var layoutMenuExpanded by remember { mutableStateOf(false) }
    var overflowUsesDarkBackground by rememberSaveable {
        mutableStateOf(inboxPreferences.getBoolean("overflow_dark", false))
    }
    fun setLayout(value: InboxLayout) {
        layout = value
        inboxPreferences.edit().putString("layout", value.name).apply()
    }
    var pendingSendIds by remember { mutableStateOf<List<java.util.UUID>?>(null) }
    var confirmBatchDelete by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<java.util.UUID>() }
    val fileImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        model.importPickedFiles(uris)
    }
    LaunchedEffect(layout) {
        if (layout == InboxLayout.OVERFLOW ||
            inboxPreferences.getString("layout", null) == InboxLayout.OVERFLOW.name
        ) {
            layout = InboxLayout.POSTERS
            inboxPreferences.edit().putString("layout", InboxLayout.POSTERS.name).apply()
        }
    }
    LaunchedEffect(Unit) { model.refreshInbox() }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) model.refreshInbox()
            if (event == Lifecycle.Event.ON_STOP) webHardConnection.stop()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            webHardConnection.stop()
        }
    }
    LaunchedEffect(webHardConnection.server) {
        while (webHardConnection.server != null) {
            delay(900)
            model.refreshInbox()
        }
    }
    LaunchedEffect(files) {
        val available = files.mapTo(hashSetOf()) { it.id }
        selectedIds.removeAll { it !in available }
        if (selectedIds.isEmpty() && files.isEmpty()) selectionMode = false
    }
    BackHandler(selectionMode) {
        selectedIds.clear()
        selectionMode = false
    }
    val selectableIds = files.take(InboxBatchContracts.MAX_SELECTED_ITEMS).map { it.id }
    val allSelected = selectableIds.isNotEmpty() && selectedIds.size == selectableIds.size && selectedIds.containsAll(selectableIds)
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            if (!showsAutomaticOverflow) TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (!selectionMode && state.theme == AppTheme.SKEUOMORPHIC) PhoneHardMark(28.dp)
                        Text(if (selectionMode) "${selectedIds.size}개 선택" else "폰하드")
                    }
                },
                navigationIcon = {
                    if (selectionMode) {
                        TextButton(onClick = {
                            selectedIds.clear()
                            if (!allSelected) selectedIds.addAll(selectableIds)
                        }) { Text(if (allSelected) "전체 해제" else "전체 선택") }
                    } else {
                        IconButton(onClick = { model.show(Screen.Dashboard) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로")
                        }
                    }
                },
                actions = {
                    if (!selectionMode) {
                        if (files.isNotEmpty()) Box {
                            IconButton(onClick = { layoutMenuExpanded = true }) {
                                Icon(inboxLayoutIcon(configuredLayout), "보기: ${configuredLayout.title}")
                            }
                            DropdownMenu(
                                expanded = layoutMenuExpanded,
                                onDismissRequest = { layoutMenuExpanded = false },
                            ) {
                                listOf(
                                    InboxLayout.DETAILS,
                                    InboxLayout.THUMBNAILS,
                                    InboxLayout.POSTERS,
                                ).forEach { candidate ->
                                    DropdownMenuItem(
                                        text = { Text(candidate.title) },
                                        leadingIcon = { Icon(inboxLayoutIcon(candidate), null) },
                                        trailingIcon = {
                                            if (candidate == configuredLayout) Icon(Icons.Default.Check, null)
                                        },
                                        onClick = {
                                            layoutMenuExpanded = false
                                            setLayout(candidate)
                                        },
                                    )
                                }
                            }
                        }
                        IconButton(onClick = { fileImporter.launch(arrayOf("*/*")) }) {
                            Icon(Icons.AutoMirrored.Filled.NoteAdd, "파일에서 가져오기")
                        }
                    }
                    if (files.isNotEmpty()) {
                        if (selectionMode) {
                            IconButton(
                                onClick = { confirmBatchDelete = true },
                                enabled = selectedIds.isNotEmpty(),
                            ) {
                                Icon(Icons.Default.Delete, "선택한 파일 삭제", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                        TextButton(
                            onClick = {
                                if (selectionMode) selectedIds.clear()
                                selectionMode = !selectionMode
                            },
                        ) { Text(if (selectionMode) "완료" else "선택") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        bottomBar = {
            if (selectionMode) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxWidth()) {
                        HorizontalDivider()
                        Column(
                            Modifier.fillMaxWidth().navigationBarsPadding()
                                .padding(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(6.dp))
                                Text("${selectedIds.size}개 선택", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.weight(1f))
                                TextButton(
                                    onClick = {
                                        selectedIds.clear()
                                        if (!allSelected) selectedIds.addAll(selectableIds)
                                    },
                                ) { Text(if (allSelected) "전체 해제" else "전체 선택") }
                            }
                            Button(
                                onClick = { pendingSendIds = selectedIds.toList() },
                                enabled = selectedIds.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Default.CloudUpload, null)
                                Spacer(Modifier.width(8.dp))
                                Text("NAS로 보내기")
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (files.isEmpty() && !showsAutomaticOverflow) {
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                item { PhoneHardConnectionHeader(webHardConnection) }
                item {
                    EmptyState(
                        icon = Icons.Default.MoveToInbox,
                        title = "폰하드가 비어 있습니다",
                        description = "내가 저장하거나 다른 기기에서 보낸 파일이 이곳에 모입니다.",
                        modifier = Modifier.fillParentMaxWidth().fillParentMaxHeight(),
                        action = {
                            Button(onClick = { fileImporter.launch(arrayOf("*/*")) }) {
                                Icon(Icons.AutoMirrored.Filled.NoteAdd, null)
                                Spacer(Modifier.width(8.dp))
                                Text("파일 가져오기")
                            }
                        },
                    )
                }
            }
        } else {
            when (displayedLayout) {
                InboxLayout.THUMBNAILS, InboxLayout.POSTERS ->
                    InboxGrid(
                        connection = webHardConnection,
                        files = files,
                        poster = displayedLayout == InboxLayout.POSTERS,
                        selectionMode = selectionMode,
                        selectedIds = selectedIds,
                        onActivate = { file ->
                            if (selectionMode) {
                                if (file.id in selectedIds) selectedIds.remove(file.id)
                                else if (selectedIds.size < InboxBatchContracts.MAX_SELECTED_ITEMS) selectedIds.add(file.id)
                            } else model.previewInboxFile(file)
                        },
                        onSend = { pendingSendIds = listOf(it.id) },
                        onShare = model::shareInboxFile,
                        onDelete = model::deleteInboxFile,
                        modifier = Modifier.padding(padding).fillMaxSize(),
                    )
                InboxLayout.OVERFLOW ->
                    InboxOverflow(
                        files = files,
                        theme = state.theme,
                        usesDarkBackground = overflowUsesDarkBackground,
                        onBack = { model.show(Screen.Dashboard) },
                        onToggleBackground = { dark ->
                            overflowUsesDarkBackground = dark
                            inboxPreferences.edit().putBoolean("overflow_dark", dark).apply()
                        },
                        onActivate = model::previewInboxFile,
                        modifier = Modifier.padding(padding).fillMaxSize(),
                    )
                InboxLayout.DETAILS -> LazyColumn(
                    Modifier.padding(padding).fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                ) {
                    item { PhoneHardConnectionHeader(webHardConnection, horizontalPadding = 0.dp) }
                    items(files, key = { it.id }) { file ->
                    val selected = file.id in selectedIds
                    var showContextMenu by remember(file.id) { mutableStateOf(false) }
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { value ->
                            if (!selectionMode && value == SwipeToDismissBoxValue.EndToStart) {
                                model.deleteInboxFile(file)
                                true
                            } else false
                        },
                    )
                    SwipeToDismissBox(
                        state = dismissState,
                        enableDismissFromStartToEnd = false,
                        enableDismissFromEndToStart = !selectionMode,
                        backgroundContent = {
                            val active = dismissState.targetValue == SwipeToDismissBoxValue.EndToStart
                            Box(
                                Modifier.fillMaxSize().background(
                                    if (active) MaterialTheme.colorScheme.error else Color.Transparent,
                                ).padding(end = 20.dp),
                                contentAlignment = Alignment.CenterEnd,
                            ) {
                                if (active) Icon(Icons.Default.Delete, "삭제", tint = MaterialTheme.colorScheme.onError)
                            }
                        },
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                Modifier.weight(1f).combinedClickable(
                                    onClick = {
                                        if (selectionMode) {
                                            if (selected) selectedIds.remove(file.id)
                                            else if (selectedIds.size < InboxBatchContracts.MAX_SELECTED_ITEMS) selectedIds.add(file.id)
                                        } else {
                                            model.previewInboxFile(file)
                                        }
                                    },
                                    onLongClick = { if (!selectionMode) showContextMenu = true },
                                ),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    if (selectionMode) {
                                        Icon(
                                            if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                            null,
                                            Modifier.size(24.dp),
                                            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    InboxLeadingPreview(file)
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(file.originalFilename, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                                            ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                                                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                                    Text(formatBytes(file.byteCount))
                                                    Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date.from(file.importedAt)))
                                                }
                                            }
                                        }
                                    }
                                }
                                DropdownMenu(expanded = showContextMenu, onDismissRequest = { showContextMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("NAS로 보내기") },
                                        leadingIcon = { Icon(Icons.Default.CloudUpload, null) },
                                        onClick = { showContextMenu = false; pendingSendIds = listOf(file.id) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("공유") },
                                        leadingIcon = { Icon(Icons.Default.Share, null) },
                                        onClick = { showContextMenu = false; model.shareInboxFile(file) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("삭제") },
                                        leadingIcon = { Icon(Icons.Default.Delete, null) },
                                        colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.error, leadingIconColor = MaterialTheme.colorScheme.error),
                                        onClick = { showContextMenu = false; model.deleteInboxFile(file) },
                                    )
                                }
                            }
                            if (!selectionMode) {
                                IconButton(
                                    onClick = { model.shareInboxFile(file) },
                                    modifier = Modifier.size(38.dp),
                                ) { Icon(Icons.Default.Share, "${file.originalFilename} 공유") }
                            }
                        }
                    }
                    HorizontalDivider()
                }
                }
            }
        }
    }
    if (confirmBatchDelete) {
        AlertDialog(
            onDismissRequest = { confirmBatchDelete = false },
            title = { Text("선택한 파일을 지울까요?") },
            text = { Text("선택한 ${selectedIds.size}개 파일이 이 기기에서 삭제됩니다.") },
            dismissButton = { TextButton(onClick = { confirmBatchDelete = false }) { Text("취소") } },
            confirmButton = {
                TextButton(
                    onClick = {
                        val ids = selectedIds.toList()
                        confirmBatchDelete = false
                        selectedIds.clear()
                        selectionMode = false
                        model.deleteInboxFiles(ids)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("지우기") }
            },
        )
    }
    pendingSendIds?.let { ids ->
        val selectedFiles = ids.mapNotNull { id -> files.firstOrNull { it.id == id } }
        val connections = state.connections.sortedBy { if (it.id == state.preferredId) 0 else 1 }
        AlertDialog(
            onDismissRequest = {
                pendingSendIds = null
                if (selectionMode) { selectedIds.clear(); selectionMode = false }
            },
            title = { Text("NAS로 보내기") },
            text = {
                if (connections.isEmpty()) {
                    Text("먼저 NAS 또는 원격 서버 연결을 저장해 주세요.")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${selectedFiles.size}개 파일을 보낼 연결을 선택하세요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        connections.forEach { connection ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    pendingSendIds = null
                                    selectedIds.clear(); selectionMode = false
                                    model.beginInboxUpload(ids, connection)
                                }.padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (state.theme == AppTheme.SKEUOMORPHIC) {
                                    EnamelIconWell(connectionKindIcon(connection.kind), MaterialTheme.colorScheme.onSurface)
                                } else {
                                    Icon(connectionKindIcon(connection.kind), null, tint = serviceColor(connection.kind.name, state.theme))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(connection.name)
                                    Text(connection.endpoint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (connection.id == state.preferredId) {
                                    Text("기본", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingSendIds = null
                    if (selectionMode) { selectedIds.clear(); selectionMode = false }
                }) { Text("취소") }
            },
            confirmButton = {
                if (connections.isEmpty()) {
                    TextButton(onClick = { pendingSendIds = null; model.show(Screen.AddConnection()) }) { Text("연결 추가") }
                }
            },
        )
    }
    state.inboxErrorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = model::dismissInboxError,
            title = { Text("폰하드 오류") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = model::dismissInboxError) { Text("확인") } },
        )
    }
}

@Composable
private fun PhoneHardConnectionHeader(
    connection: WebHardConnectionState,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 16.dp,
) {
    Box(
        modifier.fillMaxWidth().padding(start = horizontalPadding, top = 8.dp, end = horizontalPadding, bottom = 10.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        PhoneHardConnectionPanel(connection, Modifier.fillMaxWidth().widthIn(max = 720.dp))
    }
}

@Composable
private fun InboxGrid(
    connection: WebHardConnectionState,
    files: List<InboxDisplayItem>,
    poster: Boolean,
    selectionMode: Boolean,
    selectedIds: List<java.util.UUID>,
    onActivate: (InboxDisplayItem) -> Unit,
    onSend: (InboxDisplayItem) -> Unit,
    onShare: (InboxDisplayItem) -> Unit,
    onDelete: (InboxDisplayItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (poster) 164.dp else 104.dp),
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(if (poster) 20.dp else 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            PhoneHardConnectionHeader(connection, horizontalPadding = 0.dp)
        }
        items(files, key = { it.id }) { file ->
            InboxGridTile(
                file = file,
                poster = poster,
                selected = file.id in selectedIds,
                selectionMode = selectionMode,
                onActivate = { onActivate(file) },
                onSend = { onSend(file) },
                onShare = { onShare(file) },
                onDelete = { onDelete(file) },
            )
        }
    }
}

@Composable
private fun InboxGridTile(
    file: InboxDisplayItem,
    poster: Boolean,
    selected: Boolean,
    selectionMode: Boolean,
    onActivate: () -> Unit,
    onSend: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    var showContextMenu by remember(file.id) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().combinedClickable(
            onClick = onActivate,
            onLongClick = { if (!selectionMode) showContextMenu = true },
            onLongClickLabel = "작업 보기",
        ),
        verticalArrangement = Arrangement.spacedBy(if (poster) 9.dp else 6.dp),
    ) {
        Box {
            Surface(
                Modifier.fillMaxWidth().aspectRatio(1f),
                shape = RoundedCornerShape(if (poster) 15.dp else 11.dp),
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = .88f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                InboxArtwork(
                    file = file,
                    requestedPixels = if (poster) 420 else 240,
                    modifier = Modifier.fillMaxSize(),
                    cornerRadius = if (poster) 15.dp else 11.dp,
                )
            }
            if (selectionMode) {
                Icon(
                    if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    null,
                    Modifier.align(Alignment.TopEnd).padding(8.dp).size(25.dp),
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(expanded = showContextMenu, onDismissRequest = { showContextMenu = false }) {
                DropdownMenuItem(text = { Text("NAS로 보내기") }, leadingIcon = { Icon(Icons.Default.CloudUpload, null) }, onClick = { showContextMenu = false; onSend() })
                DropdownMenuItem(text = { Text("공유") }, leadingIcon = { Icon(Icons.Default.Share, null) }, onClick = { showContextMenu = false; onShare() })
                DropdownMenuItem(
                    text = { Text("삭제") },
                    leadingIcon = { Icon(Icons.Default.Delete, null) },
                    colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.error, leadingIconColor = MaterialTheme.colorScheme.error),
                    onClick = { showContextMenu = false; onDelete() },
                )
            }
        }
        Text(
            file.originalFilename,
            style = if (poster) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelMedium,
            fontWeight = if (poster) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(formatBytes(file.byteCount), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (poster) {
            Text(
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date.from(file.importedAt)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun InboxOverflow(
    files: List<InboxDisplayItem>,
    theme: AppTheme,
    usesDarkBackground: Boolean,
    onBack: () -> Unit,
    onToggleBackground: (Boolean) -> Unit,
    onActivate: (InboxDisplayItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val thumbnails = remember(files) { mutableStateMapOf<String, android.graphics.Bitmap>() }
    val requested = remember(files) { mutableSetOf<String>() }
    val items = remember(files) {
        files.map { file ->
            RemoteFileItem(
                id = file.id.toString(),
                name = file.originalFilename,
                path = file.file.path,
                isDirectory = false,
                size = file.byteCount,
                modifiedAt = file.importedAt,
                mimeType = file.mimeType,
            )
        }
    }
    val filesByID = remember(files) { files.associateBy { it.id.toString() } }

    Box(modifier) {
        RemoteBrowserCoverFlow(
            items = items,
            thumbnails = thumbnails,
            theme = theme,
            title = "폰하드",
            usesDarkBackground = usesDarkBackground,
            onBack = onBack,
            onToggleBackground = onToggleBackground,
            onActivate = { item -> filesByID[item.id]?.let(onActivate) },
            onLoadThumbnail = { item ->
                val file = filesByID[item.id] ?: return@RemoteBrowserCoverFlow
                if (requested.add(item.id)) {
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            loadInboxThumbnail(
                                context,
                                file.file,
                                file.isInboxImage,
                                file.isInboxVideo,
                                file.isInboxPdf,
                                640,
                            )
                        }
                        result.bitmap?.let { thumbnails[item.id] = it }
                    }
                }
            },
        )
    }
}

private val InboxDisplayItem.isInboxImage: Boolean
    get() = mimeType?.startsWith("image/") == true ||
        originalFilename.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "gif", "heic", "webp")

private val InboxDisplayItem.isInboxVideo: Boolean
    get() = mimeType?.startsWith("video/") == true ||
        originalFilename.substringAfterLast('.', "").lowercase() in setOf("mp4", "mov", "m4v", "mkv", "avi", "webm")

private val InboxDisplayItem.isInboxPdf: Boolean
    get() = mimeType == "application/pdf" || originalFilename.endsWith(".pdf", ignoreCase = true)

@Composable
private fun InboxLeadingPreview(file: InboxDisplayItem) {
    InboxArtwork(file, requestedPixels = 112, modifier = Modifier.size(56.dp), cornerRadius = 9.dp)
}

@Composable
private fun InboxArtwork(
    file: InboxDisplayItem,
    requestedPixels: Int,
    modifier: Modifier,
    cornerRadius: Dp,
) {
    val context = LocalContext.current
    val isImage = file.isInboxImage
    val isVideo = file.isInboxVideo
    val isPdf = file.isInboxPdf
    val preview by produceState(
        initialValue = InboxThumbnailResult(),
        key1 = if (isImage || isVideo || isPdf) "${file.file.path}:${file.file.lastModified()}:${file.file.length()}:$requestedPixels" else null,
    ) {
        value = if (isImage || isVideo || isPdf) withContext(Dispatchers.IO) {
            loadInboxThumbnail(context, file.file, isImage, isVideo, isPdf, requestedPixels)
        } else InboxThumbnailResult()
    }
    val icon = when {
        isImage -> Icons.Default.Image
        isVideo -> Icons.Default.Movie
        isPdf -> Icons.Default.PictureAsPdf
        else -> Icons.Default.InsertDriveFile
    }
    val thumbnail = preview.bitmap
    Box(
        modifier.clip(RoundedCornerShape(cornerRadius))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .08f)),
        contentAlignment = Alignment.Center,
    ) {
        if (thumbnail != null) {
            Image(thumbnail.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (isVideo && thumbnail != null) {
            Icon(
                Icons.Default.PlayCircle,
                "영상",
                Modifier.size(25.dp),
                tint = Color.White.copy(alpha = .94f),
            )
        }
        if (preview.isMotionPhoto) {
            Text(
                "Motion Photo",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = .68f))
                    .padding(horizontal = 3.dp, vertical = 2.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

private data class InboxThumbnailResult(
    val bitmap: android.graphics.Bitmap? = null,
    val isMotionPhoto: Boolean = false,
)

private object InboxThumbnailMemoryCache {
    private val cache = object : android.util.LruCache<String, android.graphics.Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap): Int = value.allocationByteCount
    }

    @Synchronized fun get(key: String): android.graphics.Bitmap? = cache.get(key)
    @Synchronized fun put(key: String, bitmap: android.graphics.Bitmap) { cache.put(key, bitmap) }
}

private fun loadInboxThumbnail(
    context: Context,
    file: File,
    isImage: Boolean,
    isVideo: Boolean,
    isPdf: Boolean,
    maxPixelSize: Int,
): InboxThumbnailResult {
    val cacheKey = "${file.canonicalPath}:${file.lastModified()}:${file.length()}:$maxPixelSize"
    val cached = InboxThumbnailMemoryCache.get(cacheKey)
    val motionPhoto = isImage && isInboxMotionPhoto(file)
    if (cached != null && !cached.isRecycled) return InboxThumbnailResult(cached, motionPhoto)
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.sharefiles", file)
    }.getOrNull()
    val bitmap = decodeInboxThumbnail(context, uri, file, isImage, isVideo, isPdf, maxPixelSize)
    if (bitmap != null) InboxThumbnailMemoryCache.put(cacheKey, bitmap)
    return InboxThumbnailResult(bitmap, motionPhoto)
}

private fun decodeInboxThumbnail(
    context: Context,
    uri: android.net.Uri?,
    file: File,
    isImage: Boolean,
    isVideo: Boolean,
    isPdf: Boolean,
    maxPixelSize: Int,
): android.graphics.Bitmap? = runCatching {
        val resolverThumbnail = if (
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q && uri != null && (isImage || isVideo)
        ) {
            runCatching {
                context.contentResolver.loadThumbnail(uri, android.util.Size(maxPixelSize, maxPixelSize), null)
            }.getOrNull()
        } else null
        when {
            resolverThumbnail != null -> scaleInboxThumbnail(resolverThumbnail, maxPixelSize)
            isImage -> {
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(file.path, bounds)
                var sample = 1
                while (bounds.outWidth / sample > maxPixelSize || bounds.outHeight / sample > maxPixelSize) sample *= 2
                android.graphics.BitmapFactory.decodeFile(
                    file.path,
                    android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
                )
            }
            isVideo -> {
                val platformThumbnail = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    android.media.ThumbnailUtils.createVideoThumbnail(
                        file,
                        android.util.Size(maxPixelSize, maxPixelSize),
                        null,
                    )
                } else null
                platformThumbnail?.let { scaleInboxThumbnail(it, maxPixelSize) } ?: android.media.MediaMetadataRetriever().run {
                    try {
                        setDataSource(file.path)
                        getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            ?.let { scaleInboxThumbnail(it, maxPixelSize) }
                    } finally {
                        release()
                    }
                }
            }
            isPdf -> android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                android.graphics.pdf.PdfRenderer(descriptor).use rendererBlock@ { renderer ->
                    if (renderer.pageCount == 0) return@rendererBlock null
                    renderer.openPage(0).use { page ->
                        val ratio = minOf(maxPixelSize.toFloat() / page.width, maxPixelSize.toFloat() / page.height, 1f)
                        android.graphics.Bitmap.createBitmap(
                            (page.width * ratio).toInt().coerceAtLeast(1),
                            (page.height * ratio).toInt().coerceAtLeast(1),
                            android.graphics.Bitmap.Config.ARGB_8888,
                        ).also { bitmap ->
                            bitmap.eraseColor(android.graphics.Color.WHITE)
                            page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            }
            else -> null
        }
    }.getOrNull()

internal fun isInboxMotionPhoto(file: File): Boolean = runCatching {
    if (file.name.endsWith("MP.jpg")) return@runCatching true
    val prefix = ByteArray(minOf(file.length(), 256L * 1024L).toInt())
    val count = file.inputStream().buffered().use { input ->
        var offset = 0
        while (offset < prefix.size) {
            val read = input.read(prefix, offset, prefix.size - offset)
            if (read <= 0) break
            offset += read
        }
        offset
    }
    if (count <= 0) return@runCatching false
    val xmp = prefix.copyOf(count).toString(Charsets.UTF_8)
    (xmp.contains("MotionPhoto=\"1\"") || xmp.contains("MotionPhoto='1'")) &&
        xmp.contains("MotionPhotoVersion") && xmp.contains("MotionPhotoPresentationTimestampUs")
}.getOrDefault(false)

private fun scaleInboxThumbnail(bitmap: android.graphics.Bitmap, maxPixelSize: Int): android.graphics.Bitmap {
    val maxSide = maxOf(bitmap.width, bitmap.height)
    if (maxSide <= maxPixelSize) return bitmap
    val ratio = maxPixelSize.toFloat() / maxSide
    return android.graphics.Bitmap.createScaledBitmap(
        bitmap,
        (bitmap.width * ratio).toInt().coerceAtLeast(1),
        (bitmap.height * ratio).toInt().coerceAtLeast(1),
        true,
    ).also { scaled -> if (scaled !== bitmap) bitmap.recycle() }
}

@Composable
private fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = if (compact) 18.dp else 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, modifier = Modifier.size(if (compact) 34.dp else 46.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .72f))
        Spacer(Modifier.height(10.dp))
        Text(
            title,
            style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(5.dp))
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(18.dp))
            action()
        }
    }
}

private data class SuperThumbnailRuntimeConditions(
    val hasUnmeteredWifi: Boolean,
    val hasExternalPower: Boolean,
) {
    val isReady: Boolean get() = hasUnmeteredWifi && hasExternalPower
}

@Composable
private fun rememberSuperThumbnailRuntimeConditions(): SuperThumbnailRuntimeConditions {
    val context = LocalContext.current
    fun readConditions(): SuperThumbnailRuntimeConditions {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return SuperThumbnailRuntimeConditions(
            hasUnmeteredWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            hasExternalPower = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL || plugged != 0,
        )
    }
    var conditions by remember { mutableStateOf(readConditions()) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                conditions = readConditions()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
        }
        context.registerReceiver(receiver, filter)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return conditions
}

@Composable
private fun SuperThumbnailScreen(state: AppState, model: NasFinderViewModel) {
    val selected = state.connections.firstOrNull { it.id == state.superThumbnailConnectionId }
    val selectedPath = state.superThumbnailPath ?: selected?.normalizedRootPath
    val selectedTitle = state.superThumbnailTitle ?: selected?.name
    val history = state.superThumbnailHistory.filter { location ->
        state.connections.any { it.id == location.connectionId }
    }
    val work = state.superThumbnailWork
    val selectedLocationId = state.superThumbnailConnectionId?.let { connectionId ->
        selectedPath?.let { path -> "$connectionId\u0000$path" }
    }
    val selectedWork = work.takeIf { state.superThumbnailWorkLocation?.id == selectedLocationId }
    val sessionReport = state.superThumbnailSessionReport.takeIf {
        state.superThumbnailReportLocationId == selectedLocationId
    }
    val hasPendingSession = sessionReport?.hasWorkToResume == true
    val active = work?.status in setOf(SuperThumbnailWorkStatus.WAITING, SuperThumbnailWorkStatus.RUNNING)
    val conditions = rememberSuperThumbnailRuntimeConditions()
    val haptics = LocalHapticFeedback.current
    var hiddenStartTapCount by rememberSaveable { mutableIntStateOf(0) }
    var confirmVaultRemoval by remember { mutableStateOf(false) }
    var confirmSuperCacheReset by remember { mutableStateOf(false) }
    var vaultTimingMenuExpanded by remember { mutableStateOf(false) }
    val hiddenStartAvailable = selected != null && !conditions.isReady && !active

    BackHandler { model.show(Screen.Dashboard) }

    LaunchedEffect(selected?.id, selectedPath) { hiddenStartTapCount = 0 }
    LaunchedEffect(conditions.isReady, active) {
        if (conditions.isReady || active) hiddenStartTapCount = 0
    }
    DisposableEffect(Unit) { onDispose { hiddenStartTapCount = 0 } }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Super Thumbnail", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                navigationIcon = { IconButton({ model.show(Screen.Dashboard) }) { Icon(Icons.Default.ArrowBack, "뒤로") } },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .88f), contentColor = MaterialTheme.colorScheme.onSurface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column {
                        Column(
                            Modifier.fillMaxWidth().clickable(enabled = !active, onClickLabel = "NAS에서 처리할 폴더 선택", onClick = model::openSuperThumbnailFolderPicker),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 40.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(selectedTitle ?: "처리할 폴더 선택", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                                    Text(selectedPath ?: "NAS 폴더를 선택하세요", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("하위 폴더 포함 · 완료된 항목은 다시 만들지 않음", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
                        }
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Surface(Modifier.size(40.dp), shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                Icon(Icons.Default.AutoAwesome, null, Modifier.padding(8.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                            Text("선택한 폴더의 영상과 사진 썸네일을 미리 만듭니다.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = MaterialTheme.typography.bodyMedium.lineHeight)
                        }
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Storage, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(8.dp))
                                Text("NAS에도 보관", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                Switch(
                                    checked = state.superThumbnailVaultEnabled,
                                    onCheckedChange = model::setSuperThumbnailVaultEnabled,
                                    enabled = !active,
                                )
                            }
                            if (state.superThumbnailVaultEnabled) {
                                HorizontalDivider()
                                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("보관 시점", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    Box {
                                        TextButton(onClick = { vaultTimingMenuExpanded = true }, enabled = !active) {
                                            Text(if (state.superThumbnailVaultTiming == SuperThumbnailVaultTiming.NOW) "즉시" else "나중에")
                                            Icon(Icons.Default.ArrowDropDown, null)
                                        }
                                        DropdownMenu(expanded = vaultTimingMenuExpanded, onDismissRequest = { vaultTimingMenuExpanded = false }) {
                                            listOf(
                                                SuperThumbnailVaultTiming.NOW to "폴더별 완료 즉시",
                                                SuperThumbnailVaultTiming.LATER to "작업 완료 후 한 번에",
                                            ).forEach { (timing, label) ->
                                                DropdownMenuItem(
                                                    text = { Text(label) },
                                                    leadingIcon = if (state.superThumbnailVaultTiming == timing) ({ Icon(Icons.Default.Check, null) }) else null,
                                                    onClick = {
                                                        vaultTimingMenuExpanded = false
                                                        model.setSuperThumbnailVaultTiming(timing)
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            Text(
                                when {
                                    !state.superThumbnailVaultEnabled -> "이 Android 기기의 Super Cache에만 저장합니다."
                                    state.superThumbnailVaultTiming == SuperThumbnailVaultTiming.NOW -> "각 폴더가 완료될 때마다 NAS에 보관합니다."
                                    else -> "모든 작업이 완료된 뒤 NAS에 한 번에 보관합니다."
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .88f), contentColor = MaterialTheme.colorScheme.onSurface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Icon(if (conditions.isReady && selected != null) Icons.Default.CheckCircle else Icons.Default.Schedule, null, tint = if (conditions.isReady && selected != null) Color(0xFF34C759) else MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                when {
                                    selected == null -> "먼저 처리할 폴더를 선택하세요."
                                    hiddenStartTapCount in 5..9 -> "제한 없이 시작하려면 ${10 - hiddenStartTapCount}번 더 누르세요."
                                    conditions.isReady -> "시작할 준비가 됐습니다."
                                    else -> "Wi‑Fi 연결과 충전을 기다립니다."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (active) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(superThumbnailStatus(work!!.status), style = MaterialTheme.typography.bodyMedium)
                            OutlinedButton(onClick = model::cancelSuperThumbnail, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(14.dp)) { Text("작업 중단") }
                        } else {
                            Box(Modifier.fillMaxWidth()) {
                                Button(
                                    onClick = {
                                        hiddenStartTapCount = 0
                                        model.startSuperThumbnail(resumeExisting = hasPendingSession)
                                    },
                                    enabled = selected != null && conditions.isReady,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                                    shape = RoundedCornerShape(14.dp),
                                ) {
                                    Icon(Icons.Default.AutoAwesome, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(if (hasPendingSession) "미완료 작업 이어서 하기" else "시작")
                                }
                                if (hiddenStartAvailable) {
                                    Box(
                                        Modifier.matchParentSize().pointerInput(selected?.id) {
                                            detectTapGestures {
                                                hiddenStartTapCount += 1
                                                if (hiddenStartTapCount >= 10) {
                                                    hiddenStartTapCount = 0
                                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    model.startSuperThumbnail(
                                                        allowsConstrainedRun = true,
                                                        resumeExisting = hasPendingSession,
                                                    )
                                                } else if (hiddenStartTapCount >= 5) {
                                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (history.isNotEmpty()) {
                item {
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .88f), contentColor = MaterialTheme.colorScheme.onSurface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("최근 작업", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            history.forEachIndexed { index, location ->
                                val dismissState = rememberSwipeToDismissBoxState(
                                    confirmValueChange = { value ->
                                        if (value == SwipeToDismissBoxValue.EndToStart) {
                                            model.removeSuperThumbnailHistory(location)
                                            true
                                        } else false
                                    },
                                )
                                SwipeToDismissBox(
                                    state = dismissState,
                                    enableDismissFromStartToEnd = false,
                                    backgroundContent = {
                                        val deleting = dismissState.targetValue == SwipeToDismissBoxValue.EndToStart
                                        Box(
                                            Modifier.fillMaxSize().background(
                                                if (deleting) MaterialTheme.colorScheme.error else Color.Transparent,
                                            ).padding(end = 20.dp),
                                            contentAlignment = Alignment.CenterEnd,
                                        ) {
                                            if (deleting) Icon(Icons.Default.Delete, "최근 작업에서 삭제", tint = MaterialTheme.colorScheme.onError)
                                        }
                                    },
                                ) {
                                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .035f), contentColor = MaterialTheme.colorScheme.onSurface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .7f))) {
                                        Row(
                                            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { hiddenStartTapCount = 0; model.showSuperThumbnailReport(location) }.padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(11.dp),
                                        ) {
                                            Surface(Modifier.size(24.dp), CircleShape, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f), contentColor = MaterialTheme.colorScheme.onSurface) {
                                                Icon(if (index == 0) Icons.Default.History else Icons.Default.Schedule, null, Modifier.padding(5.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Text(location.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text(location.path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .72f), contentColor = MaterialTheme.colorScheme.onSurface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .7f))) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("네트워크 ${formatBytes(selectedWork?.estimatedBytes ?: 0L)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("생성 ${selectedWork?.generated ?: 0}개", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider()
                        Text(
                            selectedWork?.let { "확인 ${it.visitedItems}개 · 실패 ${it.failed}개${if (it.budgetReached) " · 예산 도달" else ""}" }
                                ?: "아직 실행하지 않았습니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        if (sessionReport != null) {
                            Text(
                                "완료 ${sessionReport.successfulCount + sessionReport.cachedCount} · 미완료 ${sessionReport.pendingCount + sessionReport.failures.size + sessionReport.vaultPendingCount + sessionReport.vaultFailedCount} · NAS 보관 ${sessionReport.vaultUploadedCount}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                        OutlinedButton(
                            onClick = { confirmVaultRemoval = true },
                            enabled = selected != null && !active && !state.isRemovingSuperThumbnailVault,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) {
                            if (state.isRemovingSuperThumbnailVault) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Default.Delete, null)
                            Spacer(Modifier.width(8.dp))
                            Text("선택 폴더 NAS Vault 삭제")
                        }
                        OutlinedButton(
                            onClick = { confirmSuperCacheReset = true },
                            enabled = !active && !state.isBusy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Refresh, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Super Cache 초기화")
                        }
                    }
                }
            }
        }
    }
    if (confirmVaultRemoval) {
        AlertDialog(
            onDismissRequest = { confirmVaultRemoval = false },
            title = { Text("선택한 폴더와 하위 폴더의 NAS 보관본을 삭제할까요?") },
            text = { Text("원본 영상과 이 Android 기기의 Super Cache는 삭제되지 않습니다.") },
            dismissButton = { TextButton(onClick = { confirmVaultRemoval = false }) { Text("취소") } },
            confirmButton = {
                TextButton(
                    onClick = { confirmVaultRemoval = false; model.removeSelectedSuperThumbnailVault() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("NAS 보관본 삭제") }
            },
        )
    }
    if (confirmSuperCacheReset) {
        AlertDialog(
            onDismissRequest = { confirmSuperCacheReset = false },
            title = { Text("Super Thumbnail을 초기화할까요?") },
            text = { Text("Super Cache와 작업 기록을 초기화합니다. 원본 영상과 NAS Vault는 삭제하지 않습니다.") },
            dismissButton = { TextButton(onClick = { confirmSuperCacheReset = false }) { Text("취소") } },
            confirmButton = {
                TextButton(
                    onClick = { confirmSuperCacheReset = false; model.resetSuperCache() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("초기화") }
            },
        )
    }
    state.superThumbnailVaultResultMessage?.let { message ->
        AlertDialog(
            onDismissRequest = model::dismissSuperThumbnailVaultResult,
            title = { Text("NAS Vault") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = model::dismissSuperThumbnailVaultResult) { Text("확인") } },
        )
    }
}

@Composable
private fun SuperThumbnailFolderPickerScreen(state: AppState, model: NasFinderViewModel) {
    val picker = state.superThumbnailPicker
    val connection = state.connections.firstOrNull { it.id == picker.connectionId }
    val currentTitle = picker.path?.trimEnd('/')?.substringAfterLast('/')
        ?.takeIf(String::isNotBlank) ?: connection?.name ?: "NAS 선택"
    BackHandler {
        if (connection == null) model.closeSuperThumbnailFolderPicker()
        else model.openSuperThumbnailPickerParent()
    }
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(if (connection == null) "NAS 선택" else currentTitle) },
                navigationIcon = {
                    if (connection != null) {
                        IconButton(model::openSuperThumbnailPickerParent) { Icon(Icons.Default.ArrowBack, "상위 폴더") }
                    }
                },
                actions = { TextButton(model::closeSuperThumbnailFolderPicker) { Text("취소") } },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent),
            )
        },
        bottomBar = {
            if (connection != null && picker.path != null && !picker.isLoading) {
                Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = .94f), contentColor = MaterialTheme.colorScheme.onSurface, tonalElevation = 3.dp) {
                    Button(
                        onClick = model::selectCurrentSuperThumbnailFolder,
                        modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 54.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(Icons.Default.CheckCircle, null)
                        Spacer(Modifier.width(8.dp))
                        Text("이 폴더 선택")
                    }
                }
            }
        },
    ) { padding ->
        when {
            connection == null && state.connections.isEmpty() -> EmptyState(
                Icons.Default.CreateNewFolder,
                "연결된 NAS가 없습니다",
                "먼저 첫 화면에서 NAS 연결을 추가해 주세요.",
                Modifier.padding(padding).fillMaxSize(),
            )
            connection == null -> LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(state.connections, key = { it.id }) { item ->
                    ListItem(
                        headlineContent = { Text(item.name) },
                        supportingContent = { Text(item.normalizedRootPath, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = {
                            if (state.theme == AppTheme.SKEUOMORPHIC) {
                                EnamelIconWell(connectionKindIcon(item.kind), MaterialTheme.colorScheme.onSurface)
                            } else {
                                Icon(connectionKindIcon(item.kind), null, tint = serviceColor(item.kind.name, state.theme))
                            }
                        },
                        trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                        modifier = Modifier.clickable { model.openSuperThumbnailPickerConnection(item.id) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
            picker.isLoading -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator()
                    Text("폴더를 불러오는 중…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            picker.error != null -> EmptyState(
                Icons.Default.WifiOff,
                "폴더를 열 수 없습니다",
                picker.error,
                Modifier.padding(padding).fillMaxSize(),
            )
            else -> LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                if (picker.items.isEmpty()) {
                    item { EmptyState(Icons.Default.FolderOpen, "하위 폴더 없음", "현재 폴더를 선택할 수 있습니다.") }
                } else {
                    items(picker.items, key = { it.id + "\u0000" + it.path }) { folder ->
                        ListItem(
                            headlineContent = { Text(folder.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                            modifier = Modifier.clickable { model.openSuperThumbnailPickerFolder(folder) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SuperThumbnailProgressScreen(state: AppState, model: NasFinderViewModel) {
    val work = state.superThumbnailWork
    val location = state.superThumbnailWorkLocation
    val report = state.superThumbnailSessionReport.takeIf { state.superThumbnailReportLocationId == location?.id }
    val active = work?.status in setOf(SuperThumbnailWorkStatus.WAITING, SuperThumbnailWorkStatus.RUNNING)
    BackHandler(enabled = active) { }
    BackHandler(enabled = !active, onBack = model::closeSuperThumbnailProgressOrReport)
    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = .94f), contentColor = MaterialTheme.colorScheme.onSurface, tonalElevation = 3.dp) {
                if (active) {
                    OutlinedButton(
                        onClick = model::cancelSuperThumbnail,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp).heightIn(min = 54.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("작업 중단") }
                } else {
                    Button(
                        onClick = model::closeSuperThumbnailProgressOrReport,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp).heightIn(min = 54.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("완료") }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Surface(Modifier.size(32.dp), RoundedCornerShape(8.dp), MaterialTheme.colorScheme.primaryContainer) {
                        Icon(Icons.Default.AutoAwesome, null, Modifier.padding(6.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    Text(
                        when (work?.status) {
                            SuperThumbnailWorkStatus.WAITING, SuperThumbnailWorkStatus.RUNNING -> "썸네일 만드는 중"
                            SuperThumbnailWorkStatus.SUCCESS -> "처리 완료"
                            SuperThumbnailWorkStatus.PARTIAL -> "일부 항목을 제외하고 완료"
                            SuperThumbnailWorkStatus.FAILED -> "처리 실패"
                            SuperThumbnailWorkStatus.CANCELLED -> "작업 중단"
                            null -> "작업 준비 중"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Icon(Icons.Default.Folder, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(location?.title ?: "선택한 폴더", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    location?.path?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            item {
                Surface(Modifier.fillMaxWidth(), RoundedCornerShape(15.dp), MaterialTheme.colorScheme.surface.copy(alpha = .88f), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("확인 ${work?.visitedItems ?: 0}개", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                        if (active) LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp))
                        Text(work?.let { superThumbnailStatus(it.status) } ?: "작업 정보를 기다리는 중", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    SuperThumbnailMetric("완료", report?.successfulCount ?: work?.generated ?: 0, Modifier.weight(1f))
                    SuperThumbnailMetric("건너뜀", report?.cachedCount ?: 0, Modifier.weight(1f))
                    SuperThumbnailMetric("실패", report?.failures?.size ?: work?.failed ?: 0, Modifier.weight(1f))
                }
            }
            item {
                Surface(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp), MaterialTheme.colorScheme.surface.copy(alpha = .82f)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("세부 정보", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text("세션 네트워크 · ${formatBytes(work?.estimatedBytes ?: 0L)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        report?.takeIf { it.vaultFolders.isNotEmpty() }?.let {
                            Text(
                                "NAS 보관 ${it.vaultUploadedCount} · 대기 ${it.vaultPendingCount} · 실패 ${it.vaultFailedCount}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (work?.budgetReached == true) Text("안전 예산에 도달해 일부 항목을 남겼습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
        }
    }
}

@Composable
private fun SuperThumbnailMetric(title: String, value: Int, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("$value", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SuperThumbnailReportScreen(state: AppState, model: NasFinderViewModel) {
    val location = state.superThumbnailHistory.firstOrNull {
        it.connectionId == state.superThumbnailConnectionId && it.path == state.superThumbnailPath
    } ?: state.superThumbnailConnectionId?.let { id ->
        state.superThumbnailPath?.let { path -> SuperThumbnailLocation(id, path, state.superThumbnailTitle ?: path.substringAfterLast('/')) }
    }
    val workSnapshot = state.superThumbnailWork?.takeIf { state.superThumbnailWorkLocation?.id == location?.id }
    val report = state.superThumbnailSessionReport.takeIf { state.superThumbnailReportLocationId == location?.id }
    BackHandler(onBack = model::closeSuperThumbnailProgressOrReport)
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Super Thumbnail") },
                navigationIcon = { IconButton(model::closeSuperThumbnailProgressOrReport) { Icon(Icons.Default.ArrowBack, "뒤로") } },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(Modifier.size(32.dp), RoundedCornerShape(8.dp), MaterialTheme.colorScheme.primaryContainer) {
                        Icon(Icons.Default.AutoAwesome, null, Modifier.padding(6.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("작업 보고서", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                        Text(location?.title ?: "선택한 폴더", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(location?.path.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (report == null) {
                item { EmptyState(Icons.Default.FindInPage, "저장된 보고서 없음", "이 폴더의 Android Super Thumbnail 작업 기록이 없습니다.") }
            } else {
                item {
                    Surface(Modifier.fillMaxWidth(), RoundedCornerShape(16.dp), MaterialTheme.colorScheme.surface.copy(alpha = .88f)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(
                                if (report.hasWorkToResume) {
                                    "미완료 ${report.pendingCount + report.failures.size + report.vaultPendingCount + report.vaultFailedCount}개"
                                } else "모두 완료",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "실패 ${report.failures.size} · 업로드 대기 ${report.vaultPendingCount + report.vaultFailedCount}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            workSnapshot?.takeIf { it.budgetReached }?.let {
                                Text("안전 예산에 도달했습니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                            }
                        }
                    }
                }
                if (report.hasWorkToResume) {
                    item {
                        Button(
                            onClick = { model.startSuperThumbnail(resumeExisting = true) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Icon(Icons.Default.Refresh, null)
                            Spacer(Modifier.width(8.dp))
                            Text("미완료 작업 이어서 하기")
                        }
                    }
                }
                item {
                    Surface(Modifier.fillMaxWidth(), RoundedCornerShape(16.dp), MaterialTheme.colorScheme.surface.copy(alpha = .88f)) {
                        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("총계", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${report.successfulCount + report.cachedCount + report.failures.size + report.pendingCount}개", style = MaterialTheme.typography.titleMedium)
                            }
                            HorizontalDivider()
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("사진 미리보기", color = MaterialTheme.colorScheme.onSurfaceVariant); Text("성공 ${report.photoSuccessCount}개") }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("영상 미리보기", color = MaterialTheme.colorScheme.onSurfaceVariant); Text("성공 ${report.successCounts.sum()}개") }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("이미 완료", color = MaterialTheme.colorScheme.onSurfaceVariant); Text("${report.cachedCount}개") }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("실패", color = MaterialTheme.colorScheme.onSurfaceVariant); Text("${report.failures.size}개") }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("미처리", color = MaterialTheme.colorScheme.onSurfaceVariant); Text("${report.pendingCount}개") }
                            report.successCounts.forEachIndexed { index, success ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(listOf("5초", "20초", "40초").getOrElse(index) { "${index + 1}차" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("성공 ${success}개")
                                }
                            }
                            workSnapshot?.let {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("예상 네트워크", color = MaterialTheme.colorScheme.onSurfaceVariant); Text(formatBytes(it.estimatedBytes)) }
                            }
                        }
                    }
                }
                if (report.vaultFolders.isNotEmpty()) {
                    item {
                        Surface(Modifier.fillMaxWidth(), RoundedCornerShape(16.dp), MaterialTheme.colorScheme.surface.copy(alpha = .88f)) {
                            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("NAS 보관 상세", fontWeight = FontWeight.Medium)
                                    Text("보관 ${report.vaultUploadedCount} · 대기 ${report.vaultPendingCount} · 실패 ${report.vaultFailedCount}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                report.vaultFolders.forEach { folder ->
                                    HorizontalDivider()
                                    Text(folder.path.substringAfterLast('/').ifBlank { folder.path }, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "보관 ${folder.uploadedCount}/${folder.totalCount} · 생성 대기 ${folder.waitingThumbnailCount} · 업로드 대기 ${folder.pendingCount} · 실패 ${folder.failedCount}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    folder.errorDescription?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary, maxLines = 2) }
                                }
                                report.vaultLastVerifiedAt?.let { verified ->
                                    Text(
                                        "마지막 전체 확인 · ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date.from(verified))}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                if (report.failures.isNotEmpty()) {
                    item {
                        Surface(Modifier.fillMaxWidth(), RoundedCornerShape(16.dp), MaterialTheme.colorScheme.surface.copy(alpha = .88f)) {
                            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("미완료 파일 ${report.failures.size}개", fontWeight = FontWeight.Medium)
                                report.failures.forEachIndexed { index, failure ->
                                    if (index > 0) HorizontalDivider()
                                    Text(failure.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${failure.extension.ifBlank { "기타" }} · ${failure.size?.let(::formatBytes) ?: "크기 미상"}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(failure.reason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                                }
                            }
                        }
                    }
                }
            }
            item {
                OutlinedButton(onClick = model::closeSuperThumbnailProgressOrReport, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Default.Folder, null)
                    Spacer(Modifier.width(8.dp))
                    Text("이 폴더 다시 선택")
                }
            }
        }
    }
}

@Composable
private fun ThumbnailCacheScreen(state: AppState, model: NasFinderViewModel) {
    var limitMenuExpanded by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val statistics = state.thumbnailCacheStatistics
    LaunchedEffect(Unit) { model.refreshThumbnailCacheStatistics() }
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("썸네일 캐시") },
                navigationIcon = { IconButton({ model.show(Screen.Dashboard) }) { Icon(Icons.Default.ArrowBack, "뒤로") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { SectionTitle("현재 캐시", Icons.Default.Storage) }
            item {
                DashboardCard {
                    Row(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text("사용량", Modifier.weight(1f))
                        Text(statistics?.totalBytes?.let(::formatBytes) ?: "계산 중…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text("파일", Modifier.weight(1f))
                        Text(statistics?.let { "${it.fileCount}개" } ?: "—", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { SectionTitle("자동 정리", Icons.Default.AutoDelete) }
            item {
                DashboardCard {
                    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("자동 정리 기준", Modifier.weight(1f))
                        Box {
                            TextButton(onClick = { limitMenuExpanded = true }, enabled = statistics != null) {
                                Text(statistics?.automaticLimitBytes?.let(::formatBytes) ?: "—")
                                Icon(Icons.Default.ArrowDropDown, null)
                            }
                            DropdownMenu(expanded = limitMenuExpanded, onDismissRequest = { limitMenuExpanded = false }) {
                                RemoteThumbnailCachePolicy.automaticLimitOptions.forEach { bytes ->
                                    DropdownMenuItem(
                                        text = { Text(formatBytes(bytes)) },
                                        leadingIcon = if (statistics?.automaticLimitBytes == bytes) ({ Icon(Icons.Default.Check, null) }) else null,
                                        onClick = { limitMenuExpanded = false; model.setThumbnailCacheLimit(bytes) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    "용량을 넘거나 30일이 지나면 오래된 캐시부터 정리합니다.\n최대 5,000개를 보관합니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            item { Spacer(Modifier.height(8.dp)) }
            item {
                DashboardCard {
                    TextButton(
                        onClick = { confirmClear = true },
                        enabled = (statistics?.fileCount ?: 0) > 0 && !state.isBusy,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) {
                        Icon(Icons.Default.Delete, null)
                        Spacer(Modifier.width(8.dp))
                        Text("지금 캐시 비우기")
                    }
                }
            }
            item {
                Text(
                    "원본 영상과 폰하드 파일은 삭제하지 않습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("썸네일 캐시를 비울까요?") },
            text = { Text("현재 ${statistics?.totalBytes?.let(::formatBytes) ?: "0 B"} · 원본 파일은 삭제되지 않습니다.") },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("취소") } },
            confirmButton = {
                TextButton(
                    onClick = { confirmClear = false; model.clearThumbnailCache() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("캐시 비우기") }
            },
        )
    }
}

@Composable
private fun SettingsScreen(state: AppState, model: NasFinderViewModel) {
    val uriHandler = LocalUriHandler.current
    var confirmCacheClear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { model.refreshDownloadCacheSize() }
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("설정") },
                navigationIcon = {
                    IconButton(onClick = { model.show(Screen.Dashboard) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 6.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { SectionTitle("테마", Icons.Default.Palette) }
            item {
                val columns = 3
                Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .94f), contentColor = MaterialTheme.colorScheme.onSurface) {
                    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        AppTheme.entries.chunked(columns).forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { theme ->
                                    val selected = state.theme == theme
                                    val foreground = if (theme == AppTheme.NIGHT || theme == AppTheme.DIGITAL_RAIN || theme == AppTheme.WORKBENCH) Color.White else Color(0xFF1A2629)
                                    Box(
                                        Modifier.weight(1f).height(104.dp)
                                            .background(themePreviewBrush(theme), RoundedCornerShape(12.dp))
                                            .border(
                                                if (selected) 1.5.dp else .5.dp,
                                                if (selected) MaterialTheme.colorScheme.primary else themePreviewSecondaryColor(theme).copy(alpha = .18f),
                                                RoundedCornerShape(12.dp),
                                            )
                                            .clickable { model.setTheme(theme) }
                                            .semantics(mergeDescendants = true) { this.selected = selected }
                                    ) {
                                        if (theme == AppTheme.DIGITAL_RAIN) CodeRainDecoration(Modifier.matchParentSize().graphicsLayer(alpha = .92f))
                                        if (theme == AppTheme.WORKBENCH) WorkbenchDecoration(Modifier.matchParentSize())
                                        Column(Modifier.fillMaxSize().padding(9.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                Icon(themeIcon(theme), null, Modifier.size(12.dp), tint = foreground)
                                                Spacer(Modifier.weight(1f))
                                                if (selected) Icon(Icons.Default.CheckCircle, "선택됨", Modifier.size(12.dp), tint = if (theme == AppTheme.NIGHT || theme == AppTheme.DIGITAL_RAIN || theme == AppTheme.WORKBENCH) Color.White else MaterialTheme.colorScheme.primary)
                                            }
                                            Spacer(Modifier.weight(1f))
                                            AutoShrinkThemeTitle(themeTitle(theme), foreground)
                                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                                themePreviewAccentColors(theme).forEach { color ->
                                                    Box(Modifier.size(5.dp).background(color, CircleShape))
                                                }
                                            }
                                        }
                                    }
                                }
                                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                        HorizontalDivider()
                        Text(
                            if (state.theme == AppTheme.SYSTEM) "Android의 라이트·다크 모드에 맞춰 자동으로 바뀝니다."
                            else "선택한 테마는 앱을 다시 열어도 유지됩니다.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item { SectionTitle("앱 아이콘", Icons.Default.Apps) }
            item {
                val choices = listOf(
                    Triple(LauncherIconVariant.DEFAULT, "블루 NAS", R.drawable.app_icon_blue_nas),
                    Triple(LauncherIconVariant.PURPLE_NAS, "퍼플 NAS", R.drawable.app_icon_purple_nas),
                    Triple(LauncherIconVariant.VIBE_CODER, "Vibe Coder", R.drawable.app_icon_vibe_coder),
                    Triple(LauncherIconVariant.CYBER_VAULT, "사이버 볼트", R.drawable.app_icon_cyber_vault),
                    Triple(LauncherIconVariant.NAS_RADAR, "네트워크 NAS", R.drawable.app_icon_nas_radar),
                    Triple(LauncherIconVariant.ENAMEL, "BK Style", R.drawable.app_icon_enamel),
                )
                Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .94f), contentColor = MaterialTheme.colorScheme.onSurface) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        choices.chunked(3).forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { (variant, label, drawable) ->
                                    val selected = state.launcherIcon == variant
                                    Column(
                                        Modifier.weight(1f).height(104.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .56f))
                                            .border(
                                                BorderStroke(
                                                    if (selected) 1.5.dp else 1.dp,
                                                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .75f)
                                                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f),
                                                ),
                                                RoundedCornerShape(12.dp),
                                            )
                                            .clickable(enabled = state.pendingLauncherIcon == null && !selected && state.theme != AppTheme.SKEUOMORPHIC) { model.setLauncherIcon(variant) }
                                            .padding(8.dp)
                                            .semantics(mergeDescendants = true) { this.selected = selected },
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
                                    ) {
                                        Image(
                                            bitmap = ImageBitmap.imageResource(drawable),
                                            contentDescription = "$label 앱 아이콘 미리보기",
                                            modifier = Modifier.size(54.dp).clip(RoundedCornerShape(12.dp)),
                                            filterQuality = FilterQuality.High,
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            if (state.pendingLauncherIcon == variant) {
                                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                            } else if (selected) {
                                                Icon(Icons.Default.CheckCircle, "선택됨", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                            }
                                        }
                                    }
                                }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                        HorizontalDivider()
                        Text(
                            if (state.theme == AppTheme.SKEUOMORPHIC) "BK Style에서는 같은 이름의 앱 아이콘을 사용합니다."
                            else "선택한 아이콘은 홈 화면과 앱 보관함에 적용됩니다.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item { SectionTitle("저장공간", Icons.Default.Storage) }
            item {
                DashboardCard {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Cached, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("현재 사용량")
                            Text(
                                "${state.downloadCacheBytes?.let(::formatBytes) ?: "계산 중…"} · 7일 · 최대 512 MB",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(
                            onClick = { confirmCacheClear = true },
                            enabled = (state.downloadCacheBytes ?: 0L) > 0L && !state.isBusy,
                        ) { Text("전체 지우기") }
                    }
                }
            }
            item { SectionTitle("화면", Icons.Default.PhoneAndroid) }
            item {
                DashboardCard {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            ScreenAwakeMode.entries.forEachIndexed { index, mode ->
                                SegmentedButton(
                                    selected = state.screenAwakeMode == mode,
                                    onClick = { model.setScreenAwakeMode(mode) },
                                    shape = SegmentedButtonDefaults.itemShape(index, ScreenAwakeMode.entries.size),
                                ) { Text(when (mode) { ScreenAwakeMode.AUTOMATIC -> "오토"; ScreenAwakeMode.ALWAYS -> "항상 켜짐"; ScreenAwakeMode.OFF -> "끔" }) }
                            }
                        }
                        Text(
                            when (state.screenAwakeMode) {
                                ScreenAwakeMode.AUTOMATIC -> "다운로드·썸네일 생성·파일 작업 중에만 화면을 켜 둡니다."
                                ScreenAwakeMode.ALWAYS -> "NasFinder가 화면에 열려 있는 동안 화면을 켜 둡니다."
                                ScreenAwakeMode.OFF -> "Android의 화면 자동 잠금 설정을 그대로 따릅니다."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item { SectionTitle("Super Thumbnail", Icons.Default.AutoAwesome) }
            item {
                DashboardCard {
                    DashboardRow(Icons.Default.AutoAwesome, "Super Thumbnail", "폴더의 영상과 사진 미리보기를 미리 만듭니다.") {
                        model.show(Screen.SuperThumbnail)
                    }
                }
            }
            item { SectionTitle("프로토콜", Icons.Default.Hub) }
            item {
                DashboardCard {
                    ConnectionKind.entries.forEachIndexed { index, kind ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (state.theme == AppTheme.SKEUOMORPHIC) {
                                EnamelIconWell(connectionKindIcon(kind), MaterialTheme.colorScheme.onSurface)
                            } else {
                                Icon(connectionKindIcon(kind), null, tint = serviceColor(kind.name, state.theme), modifier = Modifier.size(22.dp))
                            }
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f)) {
                                Text(kind.title, style = MaterialTheme.typography.bodyMedium)
                                Text(protocolSupport(kind), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (index != ConnectionKind.entries.lastIndex) HorizontalDivider()
                    }
                }
            }
            item { SectionTitle("앱 정보", Icons.Default.Info) }
            item {
                DashboardCard {
                    Row(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text("버전", Modifier.weight(1f))
                        Text(state.appVersion, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text("저장된 연결", Modifier.weight(1f))
                        Text("${state.connections.size}개", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { SectionTitle("파일 앱 연동", Icons.Default.FolderOpen) }
            item {
                DashboardCard {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(if (state.connections.isEmpty()) "먼저 NAS 또는 SFTP 연결을 추가해 주세요." else "현재 저장된 원격 위치 ${state.connections.size}개를 Android 파일 선택기에서도 열 수 있습니다.", style = MaterialTheme.typography.bodyMedium)
                        Text("1. 파일 앱 또는 파일 선택 화면의 저장 위치 메뉴를 엽니다.\n2. ‘NasFinder’를 선택합니다.\n3. 서버 이름을 선택해 원격 파일을 엽니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("지원되는 작업은 연결 방식과 서버 권한에 따라 달라집니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { SectionTitle("오픈 소스", Icons.Default.Code) }
            item {
                DashboardCard {
                    DashboardRow(Icons.Default.Code, "오픈 소스 구성요소", "구성요소·버전·라이선스·공식 소스 보기") {
                        uriHandler.openUri("https://github.com/armsone/NasFinder-Android/blob/main/docs/OPEN_SOURCE.md")
                    }
                    HorizontalDivider()
                    DashboardRow(Icons.Default.Description, "Apache 2.0 라이선스", "구성요소별 고지와 소스는 각 프로젝트에서 확인") { uriHandler.openUri("https://www.apache.org/licenses/LICENSE-2.0") }
                }
            }
            item { SectionTitle("만든 사람", Icons.Default.Person) }
            item {
                DashboardCard {
                    DashboardRow(Icons.Default.Link, "GitHub · armsone", "NasFinder를 만든 사람의 GitHub입니다.") {
                        uriHandler.openUri("https://github.com/armsone")
                    }
                    HorizontalDivider()
                    DashboardRow(Icons.Default.Public, "공식 사이트 · nasfinder.com", "NasFinder 공식 홈페이지입니다.") {
                        uriHandler.openUri("https://nasfinder.com")
                    }
                }
            }
            item {
                Text(
                    "자격 증명은 Android Keystore로 암호화됩니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (confirmCacheClear) {
        AlertDialog(
            onDismissRequest = { confirmCacheClear = false },
            title = { Text("다운로드 캐시를 지울까요?") },
            text = { Text("미리보기와 공유를 위해 내려받은 임시 파일을 모두 삭제합니다. 원격 서버의 원본은 삭제되지 않습니다.") },
            dismissButton = { TextButton(onClick = { confirmCacheClear = false }) { Text("취소") } },
            confirmButton = {
                TextButton(
                    onClick = { confirmCacheClear = false; model.clearDownloadCache() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("전체 지우기") }
            },
        )
    }
}

private fun superThumbnailStatus(status: SuperThumbnailWorkStatus) = when (status) {
    SuperThumbnailWorkStatus.WAITING -> "네트워크와 충전 조건을 기다리는 중"
    SuperThumbnailWorkStatus.RUNNING -> "원격 사진을 확인하는 중"
    SuperThumbnailWorkStatus.SUCCESS -> "완료"
    SuperThumbnailWorkStatus.PARTIAL -> "일부 항목을 제외하고 완료"
    SuperThumbnailWorkStatus.FAILED -> "완료하지 못함"
    SuperThumbnailWorkStatus.CANCELLED -> "취소됨"
}

private fun protocolSupport(kind: ConnectionKind) = when (kind) {
    ConnectionKind.SYNOLOGY, ConnectionKind.SFTP, ConnectionKind.SMB ->
        "탐색 · 다운로드 · 업로드 · 관리 · 복사/이동"
    ConnectionKind.FTP -> "탐색 · 다운로드 · 업로드 · 폴더/이름/삭제"
    ConnectionKind.WEBDAV -> "탐색 · 다운로드"
    ConnectionKind.DROPBOX, ConnectionKind.ONEDRIVE, ConnectionKind.GOOGLE_DRIVE -> "계정 연결 준비 중"
}

private fun themeTitle(theme: AppTheme) = when (theme) { AppTheme.SYSTEM -> "자동"; AppTheme.DAY -> "낮"; AppTheme.NIGHT -> "밤"; AppTheme.DIGITAL_RAIN -> "Vibe Coder"; AppTheme.WINDY_MEADOW -> "Windy Meadow"; AppTheme.WORKBENCH -> "Workbench"; AppTheme.SKEUOMORPHIC -> "BK Style" }
private fun themeDescription(theme: AppTheme) = when (theme) { AppTheme.SYSTEM -> "Android 설정"; AppTheme.DAY -> "맑고 밝게"; AppTheme.NIGHT -> "차분하고 어둡게"; AppTheme.DIGITAL_RAIN -> "Black · Mint"; AppTheme.WINDY_MEADOW -> "Sky · Meadow"; AppTheme.WORKBENCH -> "Slate · Syntax"; AppTheme.SKEUOMORPHIC -> "White Enamel · Chrome" }
private fun themeIcon(theme: AppTheme) = when (theme) { AppTheme.SYSTEM -> Icons.Default.Brightness6; AppTheme.DAY -> Icons.Default.WbSunny; AppTheme.NIGHT -> Icons.Default.NightsStay; AppTheme.DIGITAL_RAIN -> Icons.Default.Code; AppTheme.WINDY_MEADOW -> Icons.Default.Air; AppTheme.WORKBENCH -> Icons.Default.Code; AppTheme.SKEUOMORPHIC -> Icons.Default.Tune }
private fun themePreviewBrush(theme: AppTheme): Brush {
    val colors = when (theme) {
        AppTheme.SYSTEM -> listOf(Color.White.copy(alpha = .96f), Color.Black.copy(alpha = .18f))
        AppTheme.DAY -> listOf(Color(red = .72f, green = .91f, blue = 1f), Color.White)
        AppTheme.NIGHT -> listOf(Color(red = .06f, green = .15f, blue = .21f), Color.Black)
        AppTheme.DIGITAL_RAIN -> listOf(Color(red = .005f, green = .05f, blue = .045f), Color.Black)
        AppTheme.WINDY_MEADOW -> listOf(Color(red = .28f, green = .76f, blue = .96f), Color(red = .73f, green = .84f, blue = .38f))
        AppTheme.WORKBENCH -> listOf(
            Color(red = .08f, green = .12f, blue = .18f),
            Color(red = .025f, green = .04f, blue = .065f),
        )
        AppTheme.SKEUOMORPHIC -> listOf(Color(0xFFC7C7C4), Color(0xFFF5F3ED), Color.White)
    }
    return if (theme == AppTheme.WINDY_MEADOW) Brush.verticalGradient(colors) else Brush.linearGradient(colors)
}
@Composable
private fun AutoShrinkThemeTitle(text: String, color: Color) {
    var size by remember(text) { mutableFloatStateOf(12f) }
    Text(
        text,
        color = color,
        fontSize = size.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        onTextLayout = { result ->
            if (result.hasVisualOverflow && size > 8.16f) size = (size - .5f).coerceAtLeast(8.16f)
        },
    )
}
private fun themePreviewAccentColors(theme: AppTheme): List<Color> = when (theme) {
    AppTheme.WORKBENCH -> listOf(Color(0xFF5CC8FF), Color(0xFF65D6AD), Color(0xFFF4C76B), Color(0xFFC792EA))
    AppTheme.SKEUOMORPHIC -> listOf(Color(0xFFE41E25), Color(0xFF6F7478), Color(0xFFB7BBC0), Color(0xFF34383B))
    else -> listOf("SYNOLOGY", "SFTP", "FTP", "WEBDAV").map { serviceColor(it, theme) }
}
private fun themePreviewSecondaryColor(theme: AppTheme): Color = when (theme) {
    AppTheme.DIGITAL_RAIN -> Color(0xFF99BFB3)
    AppTheme.WINDY_MEADOW -> Color(0xFF59636B)
    AppTheme.WORKBENCH -> Color(red = .61f, green = .69f, blue = .76f)
    AppTheme.SKEUOMORPHIC -> Color(0xFF575B5E)
    AppTheme.NIGHT -> Color(0xFFB5C1C9)
    AppTheme.SYSTEM, AppTheme.DAY -> Color(0xFF59636B)
}
private fun formatDashboardCacheBytes(value: Long?): String = if (value == null || value <= 0L) "0 B" else formatBytes(value)
private fun formatBytes(value: Long): String { if (value < 1024) return "$value B"; val units = arrayOf("KB", "MB", "GB", "TB"); var size = value.toDouble(); var index = -1; do { size /= 1024; index++ } while (size >= 1024 && index < units.lastIndex); return "%.1f %s".format(size, units[index]) }
