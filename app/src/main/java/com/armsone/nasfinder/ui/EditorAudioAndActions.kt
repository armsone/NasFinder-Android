package com.armsone.nasfinder.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Output-timeline seconds of the shared playhead inside the selected clip (the exporter's time map). */
private fun playheadOutputSeconds(project: EditorProject, scene: EditorScene, clip: ClipPlaybackState?): Double {
    val start = project.outputStarts(includeIntro = true)[scene.key] ?: 0.0
    val rate = scene.adjustments?.rate?.takeIf { it.isFinite() && it in 0.25..4.0 } ?: 1.0
    return start + (clip?.positionFor(scene.key) ?: 0L) / 1000.0 / rate
}

/** Essential per-clip actions: recommendation (primary), split at the playhead, ONE protect state, include/exclude. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SceneActionRow(
    session: TrailerSession,
    scene: EditorScene,
    model: NasFinderViewModel,
    clipPlayback: ClipPlaybackState?,
    onSelectScene: (Long) -> Unit,
) {
    val project = session.editor ?: return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(
            shape = EditorButtonShape,
            enabled = !session.busy && !scene.automationProtected,
            onClick = { model.editorRecommendScenes(setOf(scene.key)) },
        ) { Text("추천 구간", fontSize = 13.sp, maxLines = 1) }
        OutlinedButton(
            shape = EditorButtonShape,
            enabled = !session.busy && !scene.excluded,
            onClick = {
                model.editorSplitScene(scene.key, playheadOutputSeconds(project, scene, clipPlayback))?.let { _ -> }
            },
        ) { Text("분할", fontSize = 13.sp, maxLines = 1) }
        OutlinedButton(
            shape = EditorButtonShape,
            enabled = !session.busy,
            onClick = { model.editorSetSceneProtected(setOf(scene.key), !scene.automationProtected) },
        ) { Text(if (scene.automationProtected) "보호 중 · 해제" else "보호", fontSize = 13.sp, maxLines = 1) }
        OutlinedButton(
            shape = EditorButtonShape,
            enabled = !session.busy,
            onClick = { model.editorSetSceneExcluded(setOf(scene.key), !scene.excluded) },
        ) { Text(if (scene.excluded) "다시 포함" else "영상에서 제외", fontSize = 13.sp, maxLines = 1) }
    }
}

/** Local mic recording, imported voice files, take list and on-device speech to timed cues for the selected clip. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VoiceSection(
    session: TrailerSession,
    scene: EditorScene?,
    source: TrailerSource?,
    model: NasFinderViewModel,
    clipPlayback: ClipPlaybackState?,
) {
    val project = session.editor ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val directoryKey = session.directory.absolutePath
    val recorder = remember(directoryKey) { VoiceRecorder() }
    var recording by remember(directoryKey) { mutableStateOf(false) }
    var recordStartSec by remember(directoryKey) { mutableStateOf(0.0) }
    var recordFile by remember(directoryKey) { mutableStateOf<File?>(null) }
    var notice by remember(directoryKey) { mutableStateOf<String?>(null) }
    // Latest explicit request wins: a permission result for an older tap never starts a recording.
    var requestToken by remember(directoryKey) { mutableIntStateOf(0) }
    DisposableEffect(directoryKey) { onDispose { recorder.cancel(); requestToken++ } }

    fun beginRecording() {
        val dir = model.editorVoiceDirectory() ?: return
        val file = File(dir, "voice-${UUID.randomUUID()}.m4a")
        if (recorder.start(context, file)) {
            recordFile = file
            recordStartSec = if (scene != null) playheadOutputSeconds(project, scene, clipPlayback) else 0.0
            recording = true
            notice = null
        } else notice = "마이크를 시작하지 못했습니다. 다른 앱이 마이크를 쓰고 있는지 확인해 주세요."
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && requestToken > 0 && !recording) beginRecording()
        else if (!granted) notice = "마이크 권한이 없어 녹음할 수 없습니다. 음성 파일 불러오기는 그대로 쓸 수 있어요."
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.editorImportVoice(uri, if (scene != null) playheadOutputSeconds(project, scene, clipPlayback) else 0.0)
    }

    TrailerSectionTitle("음성")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(
            shape = EditorButtonShape,
            enabled = !session.busy,
            onClick = {
                if (recording) {
                    val saved = recorder.stop()
                    recording = false
                    val duration = saved?.let { f ->
                        runCatching {
                            val r = MediaMetadataRetriever()
                            try { r.setDataSource(f.absolutePath); r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { it / 1000.0 } } finally { r.release() }
                        }.getOrNull()
                    }
                    if (saved != null && duration != null && model.editorAddVoiceTake(saved, duration, recordStartSec)) notice = "녹음을 추가했습니다."
                    else { saved?.delete(); notice = "녹음이 너무 짧거나 저장하지 못했습니다." }
                    recordFile = null
                } else {
                    requestToken++
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) beginRecording()
                    else permission.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
        ) { Text(if (recording) "녹음 끝내기" else "녹음", fontSize = 13.sp, maxLines = 1) }
        if (recording) {
            TextButton(shape = EditorButtonShape, onClick = {
                recorder.cancel(); recording = false; recordFile = null; requestToken++; notice = "녹음을 취소했습니다."
            }) { Text("취소") }
        } else {
            OutlinedButton(shape = EditorButtonShape, enabled = !session.busy, onClick = { picker.launch(arrayOf("audio/*")) }) {
                Text("음성 파일 불러오기", fontSize = 13.sp, maxLines = 1)
            }
        }
    }
    notice?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    project.voiceovers.forEachIndexed { index, take ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "음성 ${index + 1} · ${seconds1Voice(take.outputStart)}부터 ${seconds1Voice(take.sourceOut - take.sourceIn)}",
                Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            TextButton(shape = EditorButtonShape, onClick = { model.editorRemoveVoiceTake(take.stableId) }) { Text("삭제") }
        }
    }

    // ---- 음성 → 시간 지정 자막 (on-device only) ----
    TrailerSectionTitle("음성으로 자막 만들기")
    var availability by remember(directoryKey) { mutableStateOf<SpeechAvailability?>(null) }
    var progress by remember(directoryKey) { mutableFloatStateOf(0f) }
    var job by remember(directoryKey) { mutableStateOf<Job?>(null) }
    var generation by remember(directoryKey) { mutableIntStateOf(0) }
    DisposableEffect(directoryKey) { onDispose { job?.cancel() } }
    androidx.compose.runtime.LaunchedEffect(directoryKey) { availability = OnDeviceSpeech.availability(context) }
    val status = availability
    Text(status?.message ?: "음성 인식 상태를 확인하는 중…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (scene == null || source == null || source.isImage) {
        Text("영상 클립을 선택하면 그 구간의 말소리를 자막으로 만들 수 있어요.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (job?.isActive == true) {
                OutlinedButton(shape = EditorButtonShape, onClick = { job?.cancel(); job = null; generation++; notice = "자막 만들기를 취소했습니다." }) { Text("취소") }
            } else {
                Button(
                    shape = EditorButtonShape,
                    enabled = !session.busy && status?.state == SpeechState.READY,
                    onClick = {
                        val my = ++generation
                        val start = scene.start
                        val length = scene.length
                        val key = scene.key
                        progress = 0f
                        notice = null
                        job = scope.launch {
                            try {
                                val result = OnDeviceSpeech.transcribe(context, source, start, start + length) { if (generation == my) progress = it }
                                if (generation != my) return@launch
                                result.onSuccess { cues ->
                                    notice = when {
                                        cues.isEmpty() -> "이 구간에서 인식된 말이 없습니다."
                                        model.editorApplyGeneratedCues(session.directory, source, key, start, length, cues) -> "자막 ${cues.size}개를 만들었습니다. 직접 고친 자막은 그대로 둡니다."
                                        else -> "그 사이 클립이 바뀌어 자막을 적용하지 않았습니다. 다시 만들어 주세요."
                                    }
                                }.onFailure { notice = it.message }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } finally {
                                if (generation == my) job = null
                            }
                        }
                    },
                ) { Text("이 클립 자막 만들기", fontSize = 13.sp, maxLines = 1) }
                if (status?.state == SpeechState.MODEL_MISSING) {
                    OutlinedButton(shape = EditorButtonShape, onClick = {
                        notice = if (OnDeviceSpeech.requestModelDownload(context)) "언어 모델 내려받기를 요청했습니다. 끝난 뒤 다시 확인해 주세요." else "언어 모델 내려받기를 요청하지 못했습니다."
                    }) { Text("언어 모델 받기", fontSize = 13.sp, maxLines = 1) }
                }
                if (status != null && status.state != SpeechState.READY) {
                    TextButton(shape = EditorButtonShape, onClick = { availability = null; scope.launch { availability = OnDeviceSpeech.availability(context) } }) { Text("다시 확인") }
                }
            }
        }
        if (job?.isActive == true) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
    }
}

private fun seconds1Voice(value: Double): String = "%.1f초".format(value)
