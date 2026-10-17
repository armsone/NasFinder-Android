package com.armsone.nasfinder.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.armsone.nasfinder.model.RemoteFileItem
import kotlin.math.*

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
                draftInitialized = true
            }
            settings = true
        }
    }
    if (browsingForMusic) return
    Dialog(onDismissRequest = ::requestClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false, dismissOnBackPress = false)) {
        BackHandler { requestClose() }
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).then(if (session.output != null) Modifier else Modifier.appSafeArea())) {
            val output = session.output
            if (output != null) {
                val item = remember(output) { RemoteFileItem(output.name, "예고편.mp4", output.absolutePath, false, output.length(), mimeType = "video/mp4") }
                val preview = remember(output) { ImagePreviewState(null, listOf(item), 0, BuiltInPreviewKind.VIDEO, cachedFile = output, localFiles = listOf(output)) }
                BuiltInPreviewScreen(preview = preview, onClose = ::requestClose, onShare = {},
                    onPrevious = {}, onNext = {}, onSelectIndex = {}, onPreviousPdfPage = {}, onNextPdfPage = {},
                    onPlaybackFailure = null, showShare = false, playbackSuspended = settings || session.busy,
                    accessoryControls = {
                        IconButton(onClick = model::saveTrailer, enabled = !session.busy && !session.saved) {
                            Icon(if (session.saved) Icons.Default.CheckCircle else Icons.Default.SaveAlt, if (session.saved) "폰하드에 저장됨" else "폰하드에 저장", tint = Color.White)
                        }
                        IconButton(onClick = { settings = true }, enabled = !session.busy) {
                            Icon(Icons.Default.Tune, "예고편 조절", tint = Color.White)
                        }
                    })
            } else {
                Column(Modifier.fillMaxSize().systemBarsPadding().padding(20.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("예고편 보기", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        TextButton(onClick = ::requestClose) { Text("취소") }
                    }
                    Spacer(Modifier.weight(1f))
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (session.busy) {
                            if (session.progress != null) {
                                val percent = (session.progress * 100).toInt().coerceIn(0, 100)
                                LinearProgressIndicator(progress = { session.progress }, modifier = Modifier.fillMaxWidth())
                                val label = if (session.status.contains("%") || session.status.contains("개 클립 연결 준비")) session.status else "${session.status} ($percent%)"
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                            } else {
                                CircularProgressIndicator()
                                Text(session.status, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        if (session.error != null) {
                            Text(session.error, color = MaterialTheme.colorScheme.error)
                            Button(onClick = model::retryTrailer) { Text("다시 시도") }
                        }
                        if (!session.busy && session.error == null) {
                            Text(
                                if (session.isMusicOverlayMode) "영상을 준비했습니다. 배경 음악을 선택해 주세요."
                                else "장면 분석을 마쳤습니다. 예고편 설정을 확인해 주세요.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Button(onClick = { settings = true }) {
                                Text(if (session.isMusicOverlayMode) "음악 선택" else "예고편 조절")
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text("기기에서 분석합니다. 원본은 변경하지 않습니다.", style = MaterialTheme.typography.labelSmall)
                }
            }
            if (output != null && (session.busy || session.error != null)) {
                Surface(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 110.dp, start = 16.dp, end = 16.dp), color = Color.Black.copy(alpha = .8f), shape = RoundedCornerShape(12.dp)) {
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
                            TextButton(onClick = model::retryTrailer) { Text("다시 만들기") }
                        }
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
                        wasCancelled = true,
                    )
                }) { Text("중단") }
            },
        )
        if (settings) TrailerSettings(
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
        ) { order, total, length, photoLength, format, musicTrack, musicPolicy, musicFitCount, autoDucking ->
            settings = false
            model.regenerateTrailer(
                order = order,
                total = total,
                length = length,
                photoLength = photoLength,
                format = format,
                musicTrack = musicTrack,
                musicPolicy = musicPolicy,
                musicFitCount = musicFitCount,
                autoDucking = autoDucking,
            )
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
    regenerate: (List<Int>, Double, Double, Double, TrailerFormat, TrailerMusicTrack?, TrailerMusicPolicy, Int, Boolean) -> Unit,
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
    val scenes = remember(sources, total, length, photoLength, musicTrack, musicPolicy, musicFitCount) {
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
    fun move(id: Int, direction: Int) {
        val list = liveSources.toMutableList()
        val from = list.indexOfFirst { it.id == id }; val to = from + direction
        if (from >= 0 && to in list.indices) { list.add(to, list.removeAt(from)); sources = list }
    }
    ModalBottomSheet(modifier = Modifier.appBottomSafety(), onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = 18.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(trailerLengthTitle(actual), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                val rateSubtitle = when {
                    session.isMusicOverlayMode -> "${scenes.size}장면 · 영상 원본 그대로"
                    else -> "${scenes.size}장면"
                }
                Text(rateSubtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if ((hasVideos || session.isMusicOverlayMode) && actual <= original + 1e-6) Text("원본 ${trailerLengthTitle(original)} 중 ${"%.1f".format(if (original > 0) actual / original * 100 else 0.0)}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            Text("배경 음악", style = MaterialTheme.typography.titleSmall)
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
                                fontWeight = FontWeight.SemiBold,
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
                            // Keep the trailer session and this draft alive; VideoTrailerScreen
                            // suspends its Dialog (without dismissing) while the browser is shown,
                            // and reappears with this same sheet once the browser is closed.
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
            Text("얼마나 길게 볼까요?", style = MaterialTheme.typography.titleSmall)
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
                Text("클립 길이", style = MaterialTheme.typography.titleSmall)
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
                if (customLength && maxLength >= 1) Slider(value = length.toFloat(), onValueChange = {
                    val newLen = it.roundToInt().toDouble().coerceAtLeast(1.0)
                    length = newLen
                }, valueRange = 1f..maxLength.toFloat().coerceAtLeast(1f), modifier = Modifier.semantics { contentDescription = "클립 길이" })
            }
            }
            Text("화면 크기", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TrailerFormat.entries.forEach { candidate ->
                    Surface(onClick = { format = candidate }, modifier = Modifier.weight(1f).semantics { contentDescription = "화면 비율 ${candidate.title}"; selected = format == candidate }, shape = RoundedCornerShape(10.dp), color = if (format == candidate) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                        Column(Modifier.padding(vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val ratio = candidate.width.toFloat() / candidate.height
                            Box(Modifier.size(if (ratio >= 1) 28.dp else (28 * ratio).dp, if (ratio >= 1) (28 / ratio).dp else 28.dp).border(2.dp, if (format == candidate) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(3.dp)))
                            Text(candidate.title, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            Text("영상 순서", style = MaterialTheme.typography.titleSmall)
            var currentOrderMode by remember { mutableStateOf("시간순") }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf("랜덤", "시간순", "가나다순").forEach { mode ->
                    TrailerChoice(mode, currentOrderMode == mode, Modifier.weight(1f)) {
                        currentOrderMode = mode
                        sources = when (mode) {
                            "랜덤" -> liveSources.shuffled()
                            "시간순" -> liveSources.sortedWith(
                                compareBy<TrailerSource> { if (it.captureTimeMillis > 0L) it.captureTimeMillis else Long.MAX_VALUE }
                                    .thenBy { it.name }
                            )
                            "가나다순" -> {
                                val collator = java.text.Collator.getInstance(java.util.Locale.KOREAN)
                                liveSources.sortedWith { a, b -> collator.compare(a.name, b.name) }
                            }
                            else -> liveSources
                        }
                    }
                }
            }
            Text("1초 이상 누른 뒤 끌어 순서를 바꿀 수 있어요.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                                            sources = liveSources.filterNot { it.id == source.id }
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
                                    // Fill empty slots in the last row to maintain 4 equal columns
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
            if (excluded > 0) Text("장면 길이와 개수에 따라 ${excluded}개 미디어는 제외됩니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(
                onClick = { regenerate(sources.map { it.id }, total, length, photoLength, format, musicTrack, musicPolicy, musicFitCount, autoDucking) },
                enabled = sources.isNotEmpty() && scenes.isNotEmpty() && (!session.isMusicOverlayMode || musicTrack != null),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (session.isMusicOverlayMode && musicTrack == null) "배경 음악을 선택해 주세요"
                    else if (session.output == null) "${trailerLengthTitle(actual)} 예고편 만들기"
                    else "${trailerLengthTitle(actual)} 예고편 다시 만들기"
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

@Composable
private fun TrailerChoice(title: String, selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, click: () -> Unit) {
    Surface(onClick = click, enabled = enabled, modifier = modifier.heightIn(min = 42.dp).semantics { this.selected = selected }, shape = RoundedCornerShape(9.dp), color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
        Box(Modifier.padding(horizontal = 2.dp), contentAlignment = Alignment.Center) { Text(title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}
