package com.armsone.nasfinder.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.armsone.nasfinder.model.RemoteFileItem
import kotlin.math.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VideoTrailerScreen(session: TrailerSession, model: NasFinderViewModel) {
    var settings by rememberSaveable(session.directory.absolutePath) { mutableStateOf(false) }
    Dialog(onDismissRequest = model::dismissTrailer, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler { model.dismissTrailer() }
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            val output = session.output
            if (output != null) {
                val item = remember(output) { RemoteFileItem(output.name, "예고편.mp4", output.absolutePath, false, output.length(), mimeType = "video/mp4") }
                val preview = remember(output) { ImagePreviewState(null, listOf(item), 0, BuiltInPreviewKind.VIDEO, cachedFile = output, localFiles = listOf(output)) }
                BuiltInPreviewScreen(preview = preview, onClose = model::dismissTrailer, onShare = {},
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
                        TextButton(onClick = model::dismissTrailer) { Text("취소") }
                    }
                    Spacer(Modifier.weight(1f))
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (session.busy) {
                            if (session.progress != null) LinearProgressIndicator(progress = { session.progress }, modifier = Modifier.fillMaxWidth())
                            else CircularProgressIndicator()
                            Text(session.status, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (session.error != null) {
                            Text(session.error, color = MaterialTheme.colorScheme.error)
                            Button(onClick = model::retryTrailer) { Text("다시 시도") }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text("기기에서 분석합니다. 원본은 변경하지 않습니다.", style = MaterialTheme.typography.labelSmall)
                }
            }
            if (output != null && (session.busy || session.error != null)) {
                Surface(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 110.dp, start = 16.dp, end = 16.dp), color = Color.Black.copy(alpha = .8f), shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (session.busy) LinearProgressIndicator()
                        Text(session.error ?: session.status, color = Color.White)
                        if (session.error != null) TextButton(onClick = model::retryTrailer) { Text("다시 만들기") }
                    }
                }
            }
        }
        if (settings) TrailerSettings(session, onClose = { settings = false }) { order, total, length, format ->
            settings = false
            model.regenerateTrailer(order, total, length, format)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrailerSettings(session: TrailerSession, onClose: () -> Unit, regenerate: (List<Int>, Double, Double, TrailerFormat) -> Unit) {
    var sources by remember { mutableStateOf(session.sources) }
    var total by remember { mutableDoubleStateOf(session.scenes.sumOf { it.length }) }
    var length by remember { mutableDoubleStateOf(session.clipLength) }
    var format by remember { mutableStateOf(session.format) }
    var customTotal by remember { mutableStateOf(false) }
    var customLength by remember { mutableStateOf(false) }
    val scenes = remember(sources, total, length) { trailerScenes(sources, total, length) }
    val actual = scenes.sumOf { it.length }
    val original = sources.sumOf { it.duration }
    val maxLength = min(30.0, sources.maxOfOrNull { it.duration } ?: 30.0)
    val excluded = sources.count { source -> scenes.none { it.source.id == source.id } }
    val liveSources by rememberUpdatedState(sources)
    fun move(id: Int, direction: Int) {
        val list = liveSources.toMutableList()
        val from = list.indexOfFirst { it.id == id }; val to = from + direction
        if (from >= 0 && to in list.indices) { list.add(to, list.removeAt(from)); sources = list }
    }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(trailerLengthTitle(actual), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("${scenes.size}장면 · ${length.roundToInt()}초씩", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("원본 ${trailerLengthTitle(original)} 중 ${"%.1f".format(if (original > 0) actual / original * 100 else 0.0)}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            Text("얼마나 길게 볼까요?", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf(30.0, 60.0, 120.0, 180.0, 300.0).forEach { value ->
                    TrailerChoice(trailerLengthTitle(value), abs(total - value) < .01, Modifier.weight(1f), value <= original) { total = value; customTotal = false }
                }
            }
            TextButton(onClick = { customTotal = !customTotal }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
                Text("다른 길이", Modifier.weight(1f)); Text(trailerLengthTitle(total)); Icon(if (customTotal) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            if (customTotal && original > 1) Slider(value = total.toFloat(), onValueChange = { total = it.roundToInt().toDouble() }, valueRange = 1f..original.toFloat(), modifier = Modifier.semantics { contentDescription = "예고편 총길이" })
            Text("한 장면은 얼마나 볼까요?", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf(2.0, 4.0, 6.0, 8.0).filter { it <= maxLength }.forEach { value ->
                    TrailerChoice("${value.toInt()}초", abs(length - value) < .01, Modifier.weight(1f)) { length = value; customLength = false }
                }
            }
            TextButton(onClick = { customLength = !customLength }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
                Text("다른 길이", Modifier.weight(1f)); Text("${length.roundToInt()}초"); Icon(if (customLength) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            if (customLength && maxLength > 1) Slider(value = length.toFloat(), onValueChange = { length = it.roundToInt().toDouble() }, valueRange = 1f..maxLength.toFloat(), modifier = Modifier.semantics { contentDescription = "모든 장면의 공통 길이" })
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
            val stride = with(LocalDensity.current) { 82.dp.toPx() }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sources, key = { it.id }) { source ->
                    var drag by remember { mutableFloatStateOf(0f) }
                    var dragging by remember { mutableStateOf(false) }
                    var moves by remember { mutableStateOf(false) }
                    Column(Modifier.width(74.dp).semantics {
                        contentDescription = "${source.name}, 원본 ${trailerLengthTitle(source.duration)}"
                        customActions = listOf(CustomAccessibilityAction("앞으로 이동") { move(source.id, -1); true }, CustomAccessibilityAction("뒤로 이동") { move(source.id, 1); true })
                    }.clickable(onClickLabel = "영상 순서 변경") { moves = true }.pointerInput(source.id) {
                        detectDragGesturesAfterLongPress(onDragStart = { dragging = true; drag = 0f }, onDragCancel = { dragging = false }, onDragEnd = { dragging = false }) { change, amount ->
                            change.consume(); drag += amount.x
                            if (abs(drag) >= stride * .6f) { val direction = if (drag > 0) 1 else -1; move(source.id, direction); drag = 0f }
                        }
                    }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(74.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant).then(if (dragging) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)) else Modifier)) {
                            source.thumbnail?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                            Surface(Modifier.align(Alignment.BottomStart).padding(3.dp), color = Color.Black.copy(alpha = .65f), shape = RoundedCornerShape(5.dp)) { Text("${sources.indexOfFirst { it.id == source.id } + 1}", Modifier.padding(horizontal = 5.dp), color = Color.White, style = MaterialTheme.typography.labelSmall) }
                        }
                        DropdownMenu(expanded = moves, onDismissRequest = { moves = false }) {
                            DropdownMenuItem(text = { Text("앞으로 이동") }, leadingIcon = { Icon(Icons.Default.ArrowBack, null) }, enabled = sources.first().id != source.id, onClick = { moves = false; move(source.id, -1) })
                            DropdownMenuItem(text = { Text("뒤로 이동") }, leadingIcon = { Icon(Icons.Default.ArrowForward, null) }, enabled = sources.last().id != source.id, onClick = { moves = false; move(source.id, 1) })
                        }
                        Text(trailerTime(source.duration), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (excluded > 0) Text("장면 길이와 개수에 따라 ${excluded}개 영상은 제외됩니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { regenerate(sources.map { it.id }, total, length, format) }, enabled = scenes.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("${trailerLengthTitle(actual)} 예고편 다시 만들기") }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun TrailerChoice(title: String, selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, click: () -> Unit) {
    Surface(onClick = click, enabled = enabled, modifier = modifier.heightIn(min = 42.dp).semantics { this.selected = selected }, shape = RoundedCornerShape(9.dp), color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
        Box(Modifier.padding(horizontal = 2.dp), contentAlignment = Alignment.Center) { Text(title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}
