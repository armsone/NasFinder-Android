@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.armsone.nasfinder.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max

private class MotionPreviewResources {
    private var closed = false
    private var file: File? = null
    @Synchronized fun retain(value: File) { if (closed) value.delete() else { file?.delete(); file = value } }
    @Synchronized fun close() { closed = true; file?.delete(); file = null }
}

/** HanClip-style local card preview. All media is read through existing URI grants. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun LocalMediaPreviewCard(
    uris: List<String>, index: Int, selected: Boolean, onToggleSelection: () -> Unit,
    onIndexChanged: (Int) -> Unit, onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val raw = uris.getOrNull(index) ?: return
    val uri = remember(raw) { Uri.parse(raw) }
    var metadata by remember(raw) { mutableStateOf<LocalMediaItem?>(null) }
    var bitmap by remember(raw) { mutableStateOf<Bitmap?>(null) }
    var videoUri by remember(raw) { mutableStateOf<Uri?>(null) }
    val motionResources = remember(raw) { MotionPreviewResources() }
    DisposableEffect(motionResources) { onDispose { motionResources.close() } }
    var error by remember(raw) { mutableStateOf<String?>(null) }
    var loading by remember(raw) { mutableStateOf(true) }
    var showInfo by remember { mutableStateOf(false) }
    var zoom by remember(raw) { mutableStateOf(1f) }
    var translation by remember(raw) { mutableStateOf(Offset.Zero) }
    var looping by remember(raw) { mutableStateOf(true) }
    LaunchedEffect(raw) {
        loading = true
        try {
            val item = loadLocalMediaMetadata(context, uri)
            metadata = item
            if (item.isVideo) videoUri = uri
            else {
                bitmap = withContext(Dispatchers.IO) {
                    if (Build.VERSION.SDK_INT >= 28) ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val factor = minOf(1f, 1800f / max(info.size.width, info.size.height))
                        decoder.setTargetSize((info.size.width * factor).toInt().coerceAtLeast(1), (info.size.height * factor).toInt().coerceAtLeast(1))
                    } else {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = (max(bounds.outWidth, bounds.outHeight) / 1800).coerceAtLeast(1) }) }
                    }
                }
                if (item.isMotionPhoto) {
                    val motion = withContext(Dispatchers.IO) {
                        val range = LocalMotionPhotoReader.inspect(context, uri) ?: return@withContext null
                        LocalMotionPhotoReader.extractPreview(context, uri, range, motionResources::retain)
                    }
                    if (motion != null) { videoUri = Uri.fromFile(motion); looping = false }
                    else error = "모션 영상은 이 사진 형식에서 재생할 수 없습니다."
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { error = "미디어를 불러오지 못했습니다." }
        finally { loading = false }
    }
    val player = remember(videoUri) { videoUri?.let { source -> ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(source)); repeatMode = if (looping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF; playWhenReady = true; prepare() } } }
    DisposableEffect(player) { onDispose { player?.release() } }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        var resumePlayback = false
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) { resumePlayback = player?.isPlaying == true; player?.pause() }
            else if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && resumePlayback) { resumePlayback = false; player?.play() }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(player, looping) { player?.repeatMode = if (looping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF }
    var buffering by remember(player) { mutableStateOf(false) }
    var playing by remember(player) { mutableStateOf(false) }
    var position by remember(player) { mutableStateOf(0f) }
    var duration by remember(player) { mutableStateOf(0L) }
    var seeking by remember(player) { mutableStateOf(false) }
    var resumeAfterSeek by remember(player) { mutableStateOf(false) }
    LaunchedEffect(player) {
        while (player != null) {
            duration = player.duration.coerceAtLeast(0)
            if (!seeking && duration > 0) position = (player.currentPosition.toFloat() / duration).coerceIn(0f, 1f)
            playing = player.isPlaying
            buffering = player.playbackState == Player.STATE_BUFFERING
            if (player.playerError != null) error = "영상을 재생할 수 없습니다."
            delay(100)
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .18f)).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
            val cardWidth = minOf(maxWidth * .70f, maxHeight - 220.dp).coerceAtLeast(120.dp)
            Column(Modifier.width(cardWidth).clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .96f), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("${index + 1}/${uris.size}", Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.labelMedium)
                            IconButton(onClick = { showInfo = !showInfo }) { Icon(Icons.Default.Info, "미디어 정보") }
                            IconButton(onClick = onToggleSelection) { Icon(if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, if (selected) "선택 해제" else "선택") }
                            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "미리보기 닫기") }
                        }
                        if (player != null) Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { if (player.isPlaying) player.pause() else { if (player.playbackState == Player.STATE_ENDED) player.seekTo(0); player.play() } }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "일시 정지" else "재생") }
                            if (metadata?.isMotionPhoto == true) LinearProgressIndicator(progress = { position }, modifier = Modifier.weight(1f))
                            else Slider(value = position, onValueChange = {
                                if (!seeking) { resumeAfterSeek = player.isPlaying; player.pause() }; seeking = true; position = it
                            }, onValueChangeFinished = { player.seekTo((duration * position).toLong()); seeking = false; if (resumeAfterSeek) player.play() }, enabled = duration > 0, modifier = Modifier.weight(1f))
                            if (metadata?.isVideo == true) IconButton(onClick = { looping = !looping }) { Icon(Icons.Default.AllInclusive, if (looping) "무한 재생 켜짐" else "무한 재생 꺼짐", tint = if (looping) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
                Surface(shape = RoundedCornerShape(18.dp), color = Color.Black, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
                    Box(Modifier.fillMaxSize().pointerInput(raw) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var singleDelta = 0f
                            var multiTouch = false
                            do {
                                val event = awaitPointerEvent()
                                if (event.changes.count { it.pressed } >= 2) {
                                    multiTouch = true
                                    zoom = (zoom * event.calculateZoom()).coerceIn(1f, 5f)
                                    val pan = translation + event.calculatePan()
                                    val maxX = size.width * (zoom - 1f) / 2f; val maxY = size.height * (zoom - 1f) / 2f
                                    translation = Offset(pan.x.coerceIn(-maxX, maxX), pan.y.coerceIn(-maxY, maxY))
                                    event.changes.forEach { it.consume() }
                                } else if (player == null) {
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                    if (change != null) {
                                        val delta = change.position - change.previousPosition
                                        if (zoom > 1f) {
                                            val pan = translation + delta
                                            val maxX = size.width * (zoom - 1f) / 2f; val maxY = size.height * (zoom - 1f) / 2f
                                            translation = Offset(pan.x.coerceIn(-maxX, maxX), pan.y.coerceIn(-maxY, maxY)); change.consume()
                                        }
                                        else singleDelta += delta.x
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                            if (!multiTouch && zoom <= 1f && abs(singleDelta) > 60.dp.toPx()) {
                                onIndexChanged((index + if (singleDelta < 0) 1 else -1).coerceIn(0, uris.lastIndex))
                            }
                            if (zoom <= 1f) translation = Offset.Zero
                        }
                    }, contentAlignment = Alignment.Center) {
                        if (player != null) AndroidView(factory = { PlayerView(it).apply { useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; this.player = player } }, update = { it.player = player }, modifier = Modifier.fillMaxSize())
                        else bitmap?.let { image -> SkinAwareThumbnailImage(image, "library-preview:$raw", metadata?.displayName, Modifier.fillMaxSize().graphicsLayer(scaleX = zoom, scaleY = zoom, translationX = translation.x, translationY = translation.y), ContentScale.Fit) }
                        if (loading || buffering) CircularProgressIndicator()
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    FilledIconButton(onClick = { onIndexChanged(index - 1) }, enabled = index > 0) { Icon(Icons.Default.ChevronLeft, "이전 미디어") }
                    FilledIconButton(onClick = { onIndexChanged(index + 1) }, enabled = index < uris.lastIndex) { Icon(Icons.Default.ChevronRight, "다음 미디어") }
                }
                if (showInfo) Surface(shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.fillMaxWidth().padding(10.dp)) {
                        metadata?.let { item ->
                            Text(item.displayName ?: "미디어", style = MaterialTheme.typography.labelLarge)
                            val date = item.captureTimeMs?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm")) }
                            Text(listOfNotNull(date, if (item.width != null && item.height != null) "${item.width} × ${item.height}" else null, item.byteLength?.let { "${it / 1024} KB" }, item.durationMs?.let { "${it / 1000}초" }).joinToString(" · "), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
