package com.armsone.nasfinder.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.armsone.nasfinder.platform.NasFinderShareIntentFactory
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.armsone.nasfinder.model.RemoteFileItem
import kotlin.math.*

private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}

private fun sceneLengthLabel(seconds: Double): String =
    if (seconds % 1.0 == 0.0) "${seconds.toInt()}초" else "${seconds}초"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VideoTrailerScreen(session: TrailerSession, model: NasFinderViewModel, browsingForMusic: Boolean = false) {
    var settings by rememberSaveable(session.directory.absolutePath) { mutableStateOf(session.awaitingInitialSettings) }
    var confirmClose by remember { mutableStateOf(false) }
    // Hoisted above the Dialog so the draft (order/subset/total/length/format/music choice)
    // survives while the trailer dialog is suspended for a nested music browser visit.
    val draftSources = remember(session.directory.absolutePath) { mutableStateOf(session.sources) }
    val draftTotal = remember(session.directory.absolutePath) { mutableStateOf(if (session.total > 0.0) session.total else session.scenes.sumOf { it.length }) }
    val draftLength = remember(session.directory.absolutePath) { mutableStateOf(session.clipLength) }
    val draftFormat = remember(session.directory.absolutePath) { mutableStateOf(session.format) }
    val draftMusicTrack = remember(session.directory.absolutePath) { mutableStateOf(session.musicTrack) }
    val draftMusicPolicy = remember(session.directory.absolutePath) { mutableStateOf(session.musicPolicy) }
    val draftMusicFitCount = remember(session.directory.absolutePath) { mutableStateOf(session.musicFitCount) }
    val draftAutoDucking = remember(session.directory.absolutePath) { mutableStateOf(session.autoDucking) }
    val draftOrderMode = remember(session.directory.absolutePath) { mutableStateOf(session.orderMode ?: "추가순") }
    var draftInitialized by rememberSaveable(session.directory.absolutePath) { mutableStateOf(false) }
    fun requestClose() {
        if (settings) settings = false
        else if (session.busy) confirmClose = true
        else {
            model.suspendTrailerDraft(
                showingSettings = false,
                sources = draftSources.value,
                total = draftTotal.value,
                length = draftLength.value,
                photoLength = if (session.isMusicOverlayMode) 1.0 else defaultPhotoClipLength(draftLength.value, draftSources.value.any { !it.isImage }),
                format = draftFormat.value,
                musicTrack = draftMusicTrack.value,
                musicPolicy = draftMusicPolicy.value,
                musicFitCount = draftMusicFitCount.value,
                autoDucking = draftAutoDucking.value,
                orderMode = draftOrderMode.value,
            )
        }
    }
    LaunchedEffect(session.directory.absolutePath, session.busy, session.output, session.error, session.awaitingInitialSettings, session.isMusicOverlayMode) {
        if ((session.awaitingInitialSettings || session.isMusicOverlayMode) && session.output == null && !session.busy && session.error == null) {
            if (!draftInitialized && session.sources.isNotEmpty()) {
                draftSources.value = session.sources
                draftTotal.value = if (session.total > 0.0) session.total else session.scenes.sumOf { it.length }
                draftLength.value = session.clipLength
                draftFormat.value = session.format
                draftMusicTrack.value = session.musicTrack
                draftMusicPolicy.value = session.musicPolicy
                draftMusicFitCount.value = session.musicFitCount
                draftAutoDucking.value = session.autoDucking
                draftOrderMode.value = session.orderMode ?: "추가순"
                draftInitialized = true
            }
            settings = true
        }
    }
    // After an in-editor mode switch finishes (analysis included), reload the derived plan values from the
    // session; music choice, ducking and the source order stay as the user left them.
    var syncedMusicMode by remember(session.directory.absolutePath) { mutableStateOf(session.isMusicOverlayMode) }
    LaunchedEffect(session.directory.absolutePath, session.isMusicOverlayMode, session.busy) {
        if (!session.busy && syncedMusicMode != session.isMusicOverlayMode) {
            syncedMusicMode = session.isMusicOverlayMode
            draftSources.value = session.sources
            draftTotal.value = if (session.total > 0.0) session.total else session.scenes.sumOf { it.length }
            draftLength.value = session.clipLength
            draftInitialized = true
        }
    }
    if (browsingForMusic) return
    val context = LocalContext.current
    BackHandler { requestClose() }
    DisposableEffect(context) {
        val window = context.findHostActivity()?.window
        val priorVisible = window?.let { w -> ViewCompat.getRootWindowInsets(w.decorView)?.isVisible(WindowInsetsCompat.Type.statusBars()) } ?: true
        if (window != null) {
            WindowInsetsControllerCompat(window, window.decorView).run {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.statusBars())
            }
        }
        onDispose {
            if (window != null && priorVisible) {
                WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.statusBars())
            }
        }
    }

    fun shareOutput() {
        val target = session.output ?: return
        if (session.busy || !target.exists() || target.length() <= 0) return
        runCatching {
            val shareIntent = NasFinderShareIntentFactory.create(context, listOf(target))
            context.startActivity(
                android.content.Intent.createChooser(shareIntent, "예고편 공유")
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize().zIndex(80f),
        color = MaterialTheme.colorScheme.background,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val isWide = maxWidth >= 720.dp
            val output = session.output
            if (isWide) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1.2f).fillMaxHeight()) {
                        if (output != null) {
                            val item = remember(output) { RemoteFileItem(output.name, "예고편.mp4", output.absolutePath, false, output.length(), mimeType = "video/mp4") }
                            val preview = remember(output) { ImagePreviewState(null, listOf(item), 0, BuiltInPreviewKind.VIDEO, cachedFile = output, localFiles = listOf(output)) }
                            BuiltInPreviewScreen(
                                preview = preview,
                                onClose = ::requestClose,
                                onShare = ::shareOutput,
                                onPrevious = {}, onNext = {}, onSelectIndex = {},
                                onPreviousPdfPage = {}, onNextPdfPage = {},
                                onPlaybackFailure = null,
                                showShare = false,
                                playbackSuspended = session.busy,
                                accessoryControls = {
                                    IconButton(
                                        onClick = ::shareOutput,
                                        enabled = !session.busy && session.output?.exists() == true && (session.output?.length() ?: 0L) > 0L,
                                    ) {
                                        Icon(Icons.Default.Share, "공유", tint = Color.White)
                                    }
                                    IconButton(onClick = model::saveTrailer, enabled = !session.busy && !session.saved) {
                                        Icon(if (session.saved) Icons.Default.CheckCircle else Icons.Default.SaveAlt, if (session.saved) "폰하드에 저장됨" else "폰하드에 저장", tint = Color.White)
                                    }
                                }
                            )
                            if (session.busy || session.error != null) {
                                TrailerStatusOverlay(
                                    session = session,
                                    onRetry = model::retryTrailer,
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .appSafeArea()
                                        .padding(bottom = 110.dp, start = 16.dp, end = 16.dp),
                                )
                            }
                        } else {
                            Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 18.dp)) {
                                Row(
                                    Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    IconButton(onClick = ::requestClose) {
                                        Icon(Icons.Default.ArrowBack, contentDescription = "뒤로")
                                    }
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        if (session.isMusicOverlayMode) "뮤직비디오 준비" else "영상 편집 준비",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                if (session.busy || session.error != null) {
                                    TrailerStatusCard(session = session, onRetry = model::retryTrailer)
                                    Spacer(Modifier.height(10.dp))
                                }
                                TrailerSourceGrid(
                                    sourcesState = draftSources,
                                    totalState = draftTotal,
                                    lengthState = draftLength,
                                    isMusicOverlayMode = session.isMusicOverlayMode,
                                    orderModeState = draftOrderMode,
                                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                                )
                            }
                        }
                    }

                    Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)))

                    Box(Modifier.weight(1f).fillMaxHeight().systemBarsPadding()) {
                        TrailerSettings(
                            session = session,
                            model = model,
                            sourcesState = draftSources,
                            totalState = draftTotal,
                            lengthState = draftLength,
                            formatState = draftFormat,
                            musicTrackState = draftMusicTrack,
                            musicPolicyState = draftMusicPolicy,
                            musicFitCountState = draftMusicFitCount,
                            autoDuckingState = draftAutoDucking,
                            onClose = ::requestClose,
                            switchMode = { toMusic, order -> model.switchTrailerMode(toMusic, order) },
                            regenerate = { order, total, length, photoLength, format, musicTrack, musicPolicy, musicFitCount, autoDucking ->
                                model.regenerateTrailer(order, total, length, photoLength, format, musicTrack, musicPolicy, musicFitCount, autoDucking)
                            },
                            orderModeState = draftOrderMode,
                            showHeader = false,
                            showSourceGrid = output != null,
                        )
                    }
                }
            } else {
                // Phone vertical
                if (output != null) {
                    if (settings) {
                        TrailerSettings(
                            session = session,
                            model = model,
                            sourcesState = draftSources,
                            totalState = draftTotal,
                            lengthState = draftLength,
                            formatState = draftFormat,
                            musicTrackState = draftMusicTrack,
                            musicPolicyState = draftMusicPolicy,
                            musicFitCountState = draftMusicFitCount,
                            autoDuckingState = draftAutoDucking,
                            onClose = { settings = false },
                            switchMode = { toMusic, order -> model.switchTrailerMode(toMusic, order) },
                            regenerate = { order, total, length, photoLength, format, musicTrack, musicPolicy, musicFitCount, autoDucking ->
                                settings = false
                                model.regenerateTrailer(order, total, length, photoLength, format, musicTrack, musicPolicy, musicFitCount, autoDucking)
                            },
                            orderModeState = draftOrderMode,
                            showHeader = true,
                            showSourceGrid = true,
                        )
                    } else {
                        val item = remember(output) { RemoteFileItem(output.name, "예고편.mp4", output.absolutePath, false, output.length(), mimeType = "video/mp4") }
                        val preview = remember(output) { ImagePreviewState(null, listOf(item), 0, BuiltInPreviewKind.VIDEO, cachedFile = output, localFiles = listOf(output)) }
                        BuiltInPreviewScreen(
                            preview = preview,
                            onClose = ::requestClose,
                            onShare = ::shareOutput,
                            onPrevious = {}, onNext = {}, onSelectIndex = {},
                            onPreviousPdfPage = {}, onNextPdfPage = {},
                            onPlaybackFailure = null,
                            showShare = false,
                            playbackSuspended = session.busy,
                            accessoryControls = {
                                IconButton(
                                    onClick = ::shareOutput,
                                    enabled = !session.busy && session.output?.exists() == true && (session.output?.length() ?: 0L) > 0L,
                                ) {
                                    Icon(Icons.Default.Share, "공유", tint = Color.White)
                                }
                                IconButton(onClick = model::saveTrailer, enabled = !session.busy && !session.saved) {
                                    Icon(if (session.saved) Icons.Default.CheckCircle else Icons.Default.SaveAlt, if (session.saved) "폰하드에 저장됨" else "폰하드에 저장", tint = Color.White)
                                }
                                IconButton(onClick = { settings = true }, enabled = !session.busy) {
                                    Icon(Icons.Default.Tune, "영상 편집", tint = Color.White)
                                }
                            }
                        )
                        if (session.busy || session.error != null) {
                            TrailerStatusOverlay(
                                session = session,
                                onRetry = model::retryTrailer,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .appSafeArea()
                                    .padding(bottom = 110.dp, start = 16.dp, end = 16.dp),
                            )
                        }
                    }
                } else {
                    // Uncreated preparation state on phone
                    TrailerSettings(
                        session = session,
                        model = model,
                        sourcesState = draftSources,
                        totalState = draftTotal,
                        lengthState = draftLength,
                        formatState = draftFormat,
                        musicTrackState = draftMusicTrack,
                        musicPolicyState = draftMusicPolicy,
                        musicFitCountState = draftMusicFitCount,
                        autoDuckingState = draftAutoDucking,
                        onClose = ::requestClose,
                        switchMode = { toMusic, order -> model.switchTrailerMode(toMusic, order) },
                        regenerate = { order, total, length, photoLength, format, musicTrack, musicPolicy, musicFitCount, autoDucking ->
                            model.regenerateTrailer(order, total, length, photoLength, format, musicTrack, musicPolicy, musicFitCount, autoDucking)
                        },
                        orderModeState = draftOrderMode,
                        showHeader = true,
                        showSourceGrid = true,
                    )
                }
            }
        }
    }

    if (confirmClose) AlertDialog(
        onDismissRequest = { confirmClose = false },
        title = { Text(if (session.isMusicOverlayMode) "뮤직비디오를 중단할까요?" else "예고편 만들기를 중단할까요?") },
        text = { Text("지금까지의 진행 상태는 보관되며 나중에 이어서 편집할 수 있습니다.") },
        dismissButton = {
            TextButton(onClick = { confirmClose = false }) {
                Text("계속 만들기")
            }
        },
        confirmButton = {
            TextButton(onClick = {
                confirmClose = false
                model.suspendTrailerDraft(
                    showingSettings = settings,
                    sources = draftSources.value,
                    total = draftTotal.value,
                    length = draftLength.value,
                    photoLength = if (session.isMusicOverlayMode) 1.0 else defaultPhotoClipLength(draftLength.value, draftSources.value.any { !it.isImage }),
                    format = draftFormat.value,
                    musicTrack = draftMusicTrack.value,
                    musicPolicy = draftMusicPolicy.value,
                    musicFitCount = draftMusicFitCount.value,
                    autoDucking = draftAutoDucking.value,
                    orderMode = draftOrderMode.value,
                    wasCancelled = true,
                )
            }) { Text("중단") }
        },
    )
}

@Composable
private fun TrailerStatusCard(session: TrailerSession, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .8f))
    ) {
        Column(
            Modifier.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (session.busy) {
                if (session.progress != null) {
                    val percent = (session.progress * 100).toInt().coerceIn(0, 100)
                    LinearProgressIndicator(progress = { session.progress }, modifier = Modifier.fillMaxWidth())
                    val label = if (session.status.contains("%") || session.status.contains("개 클립 연결 준비")) session.status else "${session.status} ($percent%)"
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    Text(session.status, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (session.error != null) {
                Text(session.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onRetry) { Text("다시 시도") }
            }
        }
    }
}

@Composable
private fun TrailerStatusOverlay(session: TrailerSession, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = .8f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (session.busy) {
                if (session.progress != null) {
                    val percent = (session.progress * 100).toInt().coerceIn(0, 100)
                    LinearProgressIndicator(progress = { session.progress }, modifier = Modifier.fillMaxWidth())
                    val label = if (session.status.contains("%") || session.status.contains("개 클립 연결 준비")) session.status else "${session.status} ($percent%)"
                    Text(label, color = Color.White)
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(session.status, color = Color.White)
                }
            }
            if (session.error != null) {
                Text(session.error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetry) { Text("다시 만들기") }
            }
        }
    }
}

@Composable
private fun TrailerSourceGrid(
    sourcesState: MutableState<List<TrailerSource>>,
    totalState: MutableState<Double>,
    lengthState: MutableState<Double>,
    isMusicOverlayMode: Boolean,
    orderModeState: MutableState<String>? = null,
    modifier: Modifier = Modifier,
) {
    var sources by sourcesState
    var total by totalState
    var length by lengthState
    val liveSources by rememberUpdatedState(sources)
    var currentOrderMode by remember { mutableStateOf(orderModeState?.value ?: "추가순") }

    fun move(id: Int, direction: Int) {
        val list = liveSources.toMutableList()
        val from = list.indexOfFirst { it.id == id }; val to = from + direction
        if (from >= 0 && to in list.indices) {
            list.add(to, list.removeAt(from))
            sources = list
            currentOrderMode = ""
            orderModeState?.value = ""
        }
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TrailerSectionTitle("영상 순서", Modifier.weight(1f))
            Text("1초간 누른 뒤 옮기세요", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf("추가순", "시간순", "가나다순", "랜덤").forEach { mode ->
                TrailerChoice(mode, currentOrderMode == mode, Modifier.weight(1f)) {
                    currentOrderMode = mode
                    orderModeState?.value = mode
                    sources = when (mode) {
                        "추가순" -> liveSources.sortedBy { it.id }
                        "시간순" -> liveSources.sortedWith(
                            compareBy<TrailerSource> {
                                if (it.captureTimeMillis > 0L) it.captureTimeMillis
                                else it.file.lastModified().takeIf { t -> t > 0L } ?: Long.MAX_VALUE
                            }.thenBy { it.id }
                        )
                        "가나다순" -> liveSources.sortedWith(
                            compareBy<TrailerSource, String>(NaturalOrderComparator) { it.name }.thenBy { it.id }
                        )
                        "랜덤" -> liveSources.shuffled()
                        else -> liveSources
                    }
                }
            }
        }
        if (sources.isNotEmpty()) {
            val longPressViewConfiguration = object : ViewConfiguration by LocalViewConfiguration.current {
                override val longPressTimeoutMillis get() = 1000L
            }
            var draggingId by remember { mutableStateOf<Int?>(null) }
            var dragTotalOffset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
            var gridWidthPx by remember { mutableFloatStateOf(0f) }
            var cellWidthPx by remember { mutableFloatStateOf(0f) }
            var cellHeightPx by remember { mutableFloatStateOf(0f) }
            val spacingPx = with(LocalDensity.current) { 6.dp.toPx() }

            var revealedTrashSourceId by remember { mutableStateOf<Int?>(null) }
            var expandedMovesSourceId by remember { mutableStateOf<Int?>(null) }

            CompositionLocalProvider(LocalViewConfiguration provides longPressViewConfiguration) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .onSizeChanged { size ->
                            gridWidthPx = size.width.toFloat()
                            cellWidthPx = (size.width.toFloat() - spacingPx * 3f) / 4f
                        }
                        .pointerInput(Unit) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { offset ->
                                    val currentList = liveSources
                                    if (cellWidthPx > 0f && cellHeightPx > 0f) {
                                        val col = (offset.x / (cellWidthPx + spacingPx)).toInt().coerceIn(0, 3)
                                        val row = (offset.y / (cellHeightPx + spacingPx)).toInt()
                                        val index = row * 4 + col
                                        if (index in currentList.indices) {
                                            draggingId = currentList[index].id
                                            dragTotalOffset = androidx.compose.ui.geometry.Offset.Zero
                                        }
                                    }
                                },
                                onDragCancel = {
                                    draggingId = null
                                    dragTotalOffset = androidx.compose.ui.geometry.Offset.Zero
                                },
                                onDragEnd = {
                                    draggingId = null
                                    dragTotalOffset = androidx.compose.ui.geometry.Offset.Zero
                                }
                            ) { change, amount ->
                                change.consume()
                                dragTotalOffset += amount
                                val currentId = draggingId ?: return@detectDragGesturesAfterLongPress
                                val currentList = liveSources
                                val currentIndex = currentList.indexOfFirst { it.id == currentId }
                                if (currentIndex < 0 || cellWidthPx <= 0f || cellHeightPx <= 0f) return@detectDragGesturesAfterLongPress

                                val strideX = cellWidthPx + spacingPx
                                val strideY = cellHeightPx + spacingPx

                                var deltaCol = 0
                                if (dragTotalOffset.x >= strideX * 0.5f) {
                                    deltaCol = 1
                                } else if (dragTotalOffset.x <= -strideX * 0.5f) {
                                    deltaCol = -1
                                }

                                var deltaRow = 0
                                if (dragTotalOffset.y >= strideY * 0.5f) {
                                    deltaRow = 1
                                } else if (dragTotalOffset.y <= -strideY * 0.5f) {
                                    deltaRow = -1
                                }

                                if (deltaCol != 0 || deltaRow != 0) {
                                    val curRow = currentIndex / 4
                                    val curCol = currentIndex % 4
                                    val targetRow = curRow + deltaRow
                                    val targetCol = curCol + deltaCol
                                    val targetIndex = (targetRow * 4 + targetCol).coerceIn(0, currentList.lastIndex)

                                    if (targetIndex != currentIndex) {
                                        val list = currentList.toMutableList()
                                        val item = list.removeAt(currentIndex)
                                        list.add(targetIndex, item)
                                        sources = list
                                        currentOrderMode = ""
                                        orderModeState?.value = ""

                                        val actualMovedX = (targetIndex % 4 - curCol) * strideX
                                        val actualMovedY = (targetIndex / 4 - curRow) * strideY
                                        dragTotalOffset = androidx.compose.ui.geometry.Offset(
                                            dragTotalOffset.x - actualMovedX,
                                            dragTotalOffset.y - actualMovedY
                                        )
                                    }
                                }
                            }
                        }
                ) {
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val rows = sources.chunked(4)
                        rows.forEachIndexed { rowIndex, rowSources ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .onSizeChanged { size ->
                                        if (size.height > 0) cellHeightPx = size.height.toFloat()
                                    },
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                rowSources.forEachIndexed { colIndex, source ->
                                    val isDragging = source.id == draggingId
                                    val showTrash = revealedTrashSourceId == source.id
                                    val moves = expandedMovesSourceId == source.id

                                    Column(
                                        Modifier
                                            .weight(1f)
                                            .zIndex(if (isDragging) 10f else 0f)
                                            .semantics {
                                                contentDescription = "${source.name}, ${if (source.isImage) "이미지" else "원본 " + trailerLengthTitle(source.duration)}"
                                                customActions = listOf(
                                                    CustomAccessibilityAction("앞으로 이동") { move(source.id, -1); true },
                                                    CustomAccessibilityAction("뒤로 이동") { move(source.id, 1); true }
                                                )
                                            }
                                            .offset {
                                                if (isDragging) {
                                                    androidx.compose.ui.unit.IntOffset(
                                                        dragTotalOffset.x.roundToInt(),
                                                        dragTotalOffset.y.roundToInt()
                                                    )
                                                } else {
                                                    androidx.compose.ui.unit.IntOffset.Zero
                                                }
                                            },
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Box(
                                            Modifier
                                                .fillMaxWidth()
                                                .aspectRatio(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                                .clickable {
                                                    revealedTrashSourceId = if (showTrash) null else source.id
                                                }
                                                .then(if (isDragging) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)) else Modifier)
                                        ) {
                                            source.thumbnail?.let {
                                                Image(
                                                    it.asImageBitmap(),
                                                    null,
                                                    Modifier.fillMaxSize(),
                                                    contentScale = ContentScale.Crop
                                                )
                                            }
                                            Surface(
                                                Modifier.align(Alignment.BottomStart).padding(2.dp),
                                                color = Color.Black.copy(alpha = .65f),
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text(
                                                    "${sources.indexOfFirst { it.id == source.id } + 1}",
                                                    Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                                    color = Color.White,
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            }
                                            if (source.isImage) {
                                                val ext = source.name.substringAfterLast('.', "").uppercase().takeIf { it.isNotBlank() } ?: "IMG"
                                                Surface(
                                                    Modifier.align(Alignment.TopStart).padding(2.dp),
                                                    color = Color.Black.copy(alpha = .65f),
                                                    shape = RoundedCornerShape(4.dp)
                                                ) {
                                                    Text(
                                                        ext,
                                                        Modifier.padding(horizontal = 3.dp, vertical = 1.dp),
                                                        color = Color.White,
                                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                                                    )
                                                }
                                            }
                                            if (showTrash) {
                                                Box(
                                                    Modifier
                                                        .fillMaxSize()
                                                        .background(Color.Black.copy(alpha = .55f)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    IconButton(onClick = {
                                                        if (revealedTrashSourceId == source.id) {
                                                            revealedTrashSourceId = null
                                                        }
                                                        val remaining = liveSources.filterNot { it.id == source.id }
                                                        if (!isMusicOverlayMode && remaining.isNotEmpty() && automaticTrailerPlan(liveSources, total, length) != null) {
                                                            val defaults = recommendedTrailerDefaults(remaining)
                                                            length = defaults.clipLength
                                                            total = defaults.total
                                                        }
                                                        sources = remaining
                                                    }) {
                                                        Icon(
                                                            Icons.Default.Delete,
                                                            "예고편에서 제외",
                                                            tint = MaterialTheme.colorScheme.error
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        DropdownMenu(expanded = moves, onDismissRequest = { expandedMovesSourceId = null }) {
                                            DropdownMenuItem(
                                                text = { Text("앞으로 이동") },
                                                leadingIcon = { Icon(Icons.Default.ArrowBack, null) },
                                                enabled = sources.first().id != source.id,
                                                onClick = { expandedMovesSourceId = null; move(source.id, -1) }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("뒤로 이동") },
                                                leadingIcon = { Icon(Icons.Default.ArrowForward, null) },
                                                enabled = sources.last().id != source.id,
                                                onClick = { expandedMovesSourceId = null; move(source.id, 1) }
                                            )
                                        }
                                        Text(
                                            if (source.isImage) "이미지" else trailerTime(source.duration),
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                val emptySlots = 4 - rowSources.size
                                if (emptySlots > 0) {
                                    repeat(emptySlots) {
                                        Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Text("모든 미디어가 제외되었습니다. 미디어를 추가해 주세요.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrailerSettings(
    session: TrailerSession,
    model: NasFinderViewModel,
    sourcesState: MutableState<List<TrailerSource>>,
    totalState: MutableState<Double>,
    lengthState: MutableState<Double>,
    formatState: MutableState<TrailerFormat>,
    musicTrackState: MutableState<TrailerMusicTrack?>,
    musicPolicyState: MutableState<TrailerMusicPolicy>,
    musicFitCountState: MutableState<Int>,
    autoDuckingState: MutableState<Boolean>,
    onClose: () -> Unit,
    switchMode: (Boolean, List<Int>) -> Unit,
    regenerate: (List<Int>, Double, Double, Double, TrailerFormat, TrailerMusicTrack?, TrailerMusicPolicy, Int, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    orderModeState: MutableState<String>? = null,
    showHeader: Boolean = true,
    showSourceGrid: Boolean = true,
) {
    var sources by sourcesState
    var total by totalState
    var length by lengthState
    val hasVideos = sources.any { !it.isImage }
    val photoLength = if (session.isMusicOverlayMode) 1.0 else defaultPhotoClipLength(length, hasVideos)
    var format by formatState
    var musicTrack by musicTrackState
    var musicPolicy by musicPolicyState
    var musicFitCount by musicFitCountState
    var autoDucking by autoDuckingState
    val initialShowMusicPicker = session.isMusicOverlayMode && session.musicTrack == null
    var showMusicPicker by remember { mutableStateOf(initialShowMusicPicker) }
    var customTotal by remember { mutableStateOf(false) }
    var customLength by remember { mutableStateOf(false) }
    val scenes = remember(session.isMusicOverlayMode, sources, total, length, photoLength, musicTrack, musicPolicy, musicFitCount) {
        if (session.isMusicOverlayMode) {
            musicOverlayScenes(sources, photoLength, musicTrack, musicPolicy, musicFitCount)
        } else {
            trailerScenes(sources, total, length, photoLength, musicTrack, musicPolicy, musicFitCount)
        }
    }
    val actual = scenes.sumOf { it.length }
    val original = sources.sumOf { if (it.isImage) (if (session.isMusicOverlayMode) 1.0 else it.weightedPhotoLength(length)) else it.duration }
    val maxLength = min(30.0, sources.maxOfOrNull { if (it.isImage) 30.0 else it.duration } ?: 30.0)
    val excluded = sources.count { source -> scenes.none { it.source.id == source.id } }
    val liveSources by rememberUpdatedState(sources)

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        if (showHeader) {
            BackHandler { onClose() }
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (showHeader) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "뒤로")
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (session.isMusicOverlayMode) "뮤직비디오 설정" else "영상 편집 설정",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "편집 설정",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            if (session.busy || session.error != null) {
                TrailerStatusCard(session = session, onRetry = model::retryTrailer)
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TrailerSectionTitle("제작 방식")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(false to "예고편", true to "뮤직비디오").forEach { (music, title) ->
                        TrailerChoice(title, session.isMusicOverlayMode == music, Modifier.weight(1f), enabled = !session.busy) {
                            if (session.isMusicOverlayMode != music) switchMode(music, liveSources.map { it.id })
                        }
                    }
                }
                if (session.busy) Text(session.status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(trailerLengthTitle(actual), Modifier.weight(1f), fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
                    val rateSubtitle = when {
                        session.isMusicOverlayMode -> "${scenes.size}장면 · 영상 원본 그대로"
                        else -> "${scenes.size}장면"
                    }
                    Text(rateSubtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if ((hasVideos || session.isMusicOverlayMode) && actual <= original + 1e-6) {
                    Text("원본 ${trailerLengthTitle(original)} 중 ${"%.1f".format(if (original > 0) actual / original * 100 else 0.0)}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                if (showSourceGrid) {
                    HorizontalDivider()
                    TrailerSourceGrid(
                        sourcesState = sourcesState,
                        totalState = totalState,
                        lengthState = lengthState,
                        isMusicOverlayMode = session.isMusicOverlayMode,
                        orderModeState = orderModeState,
                    )
                    if (excluded > 0) Text("장면 길이와 개수에 따라 ${excluded}개 미디어는 제외됩니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                HorizontalDivider()
                TrailerSectionTitle("음악")
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = musicTrack?.name ?: "음악 없음",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (musicTrack != null) {
                                    Text(
                                        text = trailerLengthTitle(musicTrack!!.durationSeconds),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (musicTrack != null) {
                                TextButton(onClick = {
                                    musicTrack = null
                                    musicPolicy = TrailerMusicPolicy.LOOP_MUSIC
                                    musicFitCount = 1
                                }) {
                                    Text("음악 제거")
                                }
                            }
                            Button(onClick = { showMusicPicker = true }) {
                                Text("음악 선택")
                            }
                        }

                        if (musicTrack != null) {
                            HorizontalDivider()
                            Text("음악 적용 방식", style = MaterialTheme.typography.labelMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                val fitTitle = if (musicPolicy == TrailerMusicPolicy.FIT_MUSIC_LENGTH) {
                                    when (musicFitCount) {
                                        1 -> "음악 길이에 맞추기"
                                        2 -> "두 번 맞추기"
                                        3 -> "세 번 맞추기"
                                        4 -> "네 번 맞추기"
                                        else -> "${musicFitCount}번 맞추기"
                                    }
                                } else {
                                    "음악 길이에 맞추기"
                                }
                                TrailerChoice(
                                    title = fitTitle,
                                    selected = musicPolicy == TrailerMusicPolicy.FIT_MUSIC_LENGTH,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    if (musicPolicy == TrailerMusicPolicy.FIT_MUSIC_LENGTH) {
                                        musicFitCount += 1
                                    } else {
                                        musicPolicy = TrailerMusicPolicy.FIT_MUSIC_LENGTH
                                        musicFitCount = 1
                                    }
                                    val track = musicTrack
                                    if (track != null && track.durationSeconds > 0) {
                                        total = track.durationSeconds * musicFitCount
                                    }
                                }
                                TrailerChoice(
                                    title = "음악 반복",
                                    selected = musicPolicy == TrailerMusicPolicy.LOOP_MUSIC,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    musicPolicy = TrailerMusicPolicy.LOOP_MUSIC
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("자동 오디오 더킹", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "영상 원본 오디오가 나올 때 음악 볼륨을 자동으로 줄입니다",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(checked = autoDucking, onCheckedChange = { autoDucking = it })
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                model.show(Screen.WebBrowser)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Language, null)
                            Spacer(Modifier.width(6.dp))
                            Text("브라우저에서 음악 받기")
                        }
                    }
                }

                if (session.isMusicOverlayMode) {
                    Text("원본 영상을 자르지 않고 그대로 이어 붙입니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    TrailerSectionTitle("얼마나 길게 볼까요?")
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        listOf(30.0, 60.0, 120.0, 180.0, 300.0).forEach { value ->
                            TrailerChoice(trailerLengthTitle(value), abs(total - value) < .01, Modifier.weight(1f)) {
                                total = value
                                customTotal = false
                                if (musicTrack != null) {
                                    musicPolicy = TrailerMusicPolicy.SINGLE
                                }
                            }
                        }
                    }
                    TextButton(onClick = { customTotal = !customTotal }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
                        Text("다른 길이", Modifier.weight(1f)); Text(trailerLengthTitle(total)); Icon(if (customTotal) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                    }
                    if (customTotal) {
                        TrailerDurationSlider(value = total, onValueChange = {
                            total = it
                            if (musicTrack != null) musicPolicy = TrailerMusicPolicy.SINGLE
                        })
                    }
                    if (sources.isNotEmpty()) {
                        TrailerSectionTitle("한 장면은 얼마나 볼까요?")
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            listOf(1.0, 1.5, 2.0, 4.0, 6.0).filter { it <= maxLength }.forEach { value ->
                                TrailerChoice(sceneLengthLabel(value), abs(length - value) < .01, Modifier.weight(1f)) {
                                    length = value
                                    customLength = false
                                }
                            }
                        }
                        TextButton(onClick = { customLength = !customLength }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
                            Text("다른 길이", Modifier.weight(1f)); Text(sceneLengthLabel(length)); Icon(if (customLength) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                        }
                        if (customLength && maxLength >= 1) Slider(value = length.toFloat().coerceIn(1f, maxLength.toFloat()), onValueChange = {
                            val newLen = it.roundToInt().toDouble().coerceAtLeast(1.0)
                            length = newLen
                        }, valueRange = 1f..maxLength.toFloat().coerceAtLeast(1f), modifier = Modifier.semantics { contentDescription = "클립 길이" })
                    }
                }
                TrailerSectionTitle("화면 모양")
                // Six equal square ratio tiles in one row (order: 원본 1:1 3:4 4:3 9:16 16:9) with the value label below;
                // the row only scrolls horizontally when the width cannot hold all six.
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TrailerFormat.entries.forEach { candidate ->
                        val isSelected = format == candidate
                        val accent = MaterialTheme.colorScheme.primary
                        Column(Modifier.width(46.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Surface(
                                onClick = { format = candidate },
                                modifier = Modifier.size(46.dp).semantics { contentDescription = "화면 비율 ${candidate.title}"; selected = isSelected },
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) accent.copy(alpha = .12f) else MaterialTheme.colorScheme.surfaceVariant,
                                border = if (isSelected) BorderStroke(1.dp, accent) else null,
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    val (candW, candH) = candidate.resolveDimensions(sources)
                                    val ratio = if (candH > 0) candW.toFloat() / candH else 1f
                                    val boxW = if (ratio >= 1f) 28f else 28f * ratio
                                    val boxH = if (ratio >= 1f) 28f / ratio else 28f
                                    Box(Modifier.size(boxW.coerceAtLeast(4f).dp, boxH.coerceAtLeast(4f).dp).border(2.dp, if (isSelected) accent else MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(3.dp)))
                                }
                            }
                            Text(
                                candidate.title,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isSelected) accent else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .32f))
            Button(
                onClick = { regenerate(sources.map { it.id }, total, length, photoLength, format, musicTrack, musicPolicy, musicFitCount, autoDucking) },
                enabled = !session.busy && sources.isNotEmpty() && scenes.isNotEmpty() && (!session.isMusicOverlayMode || musicTrack != null),
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    if (session.isMusicOverlayMode && musicTrack == null) "배경 음악을 선택해 주세요"
                    else if (session.output == null) "${trailerLengthTitle(actual)} ${if (session.isMusicOverlayMode) "뮤직비디오" else "예고편"} 만들기"
                    else "${trailerLengthTitle(actual)} ${if (session.isMusicOverlayMode) "뮤직비디오" else "예고편"} 다시 만들기",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        if (showMusicPicker) {
            UnifiedMusicPickerDialog(
                model = model,
                currentTrack = musicTrack,
                onDismiss = { showMusicPicker = false },
                onSelectTrack = { selected ->
                    musicTrack = selected
                    musicPolicy = TrailerMusicPolicy.LOOP_MUSIC
                    musicFitCount = 1
                    showMusicPicker = false
                },
            )
        }
    }
}

/** iOS section titles: subheadline semibold. */
@Composable
private fun TrailerSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
}

/**
 * iOS TrailerChoiceButton: minHeight 44, corner 10, footnote medium; selected = accent×.12 fill, 1pt accent stroke,
 * accent text with a caption2 checkmark. tertiarySystemFill has no numeric source value; surfaceVariant stands in.
 */
@Composable
private fun TrailerChoice(title: String, selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, click: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val content = (if (selected) accent else MaterialTheme.colorScheme.onSurface).let { if (enabled) it else it.copy(alpha = .38f) }
    Surface(
        onClick = click,
        enabled = enabled,
        modifier = modifier.heightIn(min = 44.dp).semantics { this.selected = selected },
        shape = RoundedCornerShape(10.dp),
        color = if (selected) accent.copy(alpha = .12f) else MaterialTheme.colorScheme.surfaceVariant,
        border = if (selected) BorderStroke(1.dp, accent) else null,
        contentColor = content,
    ) {
        Row(Modifier.padding(horizontal = 2.dp), horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (selected) Icon(Icons.Default.Check, null, Modifier.size(11.dp))
        }
    }
}
