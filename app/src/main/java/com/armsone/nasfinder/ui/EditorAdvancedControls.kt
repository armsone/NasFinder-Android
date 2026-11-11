package com.armsone.nasfinder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID
import kotlin.math.roundToInt

private enum class AdvancedTool(val title: String) { RATE("속도"), DISSOLVE("전환"), COLOR("색상"), KEYFRAME("움직임"), CUE("자막 타이밍") }

private val RATE_CHOICES = listOf(0.25, 0.5, 0.75, 1.0, 1.5, 2.0, 3.0, 4.0)
private val DISSOLVE_CHOICES = listOf(0.0, 0.3, 0.5, 1.0)
private const val MIN_CUE_SECONDS = 0.3

private fun secs(value: Double) = "%.1f초".format(value)

/**
 * Compact chooser of the selected clip's advanced edits; only the chosen tool shows its details. Every change is one
 * undoable [NasFinderViewModel.editorEditAdjustments] edit, so save/undo/redo and the manual-field marks stay consistent.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SceneAdvancedSection(
    scene: EditorScene,
    source: TrailerSource,
    model: NasFinderViewModel,
    clipPlayback: ClipPlaybackState?,
    showsTitle: Boolean = true,
    asMenu: Boolean = false,
) {
    var tool by rememberSaveable(scene.key) { mutableStateOf<String?>(null) }
    val adjust = scene.adjustments ?: TrailerClipAdjustments()
    val tools = if (source.isImage) AdvancedTool.entries - AdvancedTool.RATE else AdvancedTool.entries
    if (showsTitle) TrailerSectionTitle("세부 편집")
    if (asMenu) {
        // Phone: the five tools live in one dropdown so only the chosen detail control is on screen.
        var open by remember { mutableStateOf(false) }
        val current = tools.firstOrNull { it.name == tool }
        Box {
            OutlinedButton(shape = EditorButtonShape, onClick = { open = true }) {
                Text(current?.let { adjustedLabel(it, adjust) ?: it.title } ?: "세부 조절 선택 ▾", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                tools.forEach { entry ->
                    DropdownMenuItem(
                        text = { Text(adjustedLabel(entry, adjust) ?: entry.title) },
                        onClick = { tool = if (tool == entry.name) null else entry.name; open = false },
                    )
                }
            }
        }
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            tools.forEach { entry ->
                val active = adjustedLabel(entry, adjust)
                TrailerChoice(active ?: entry.title, tool == entry.name, compact = true) { tool = if (tool == entry.name) null else entry.name }
            }
        }
    }
    val shown = tools.firstOrNull { it.name == tool } ?: return
    // Source seconds of the shared playhead inside this clip (the clip's own source time base).
    val playheadSource = scene.start + (clipPlayback?.positionFor(scene.key) ?: 0L) / 1000.0
    when (shown) {
        AdvancedTool.RATE -> {
            Text("재생 속도 ${"%.2f".format(adjust.rate).trimEnd('0').trimEnd('.')}배 · 클립이 영상에서 차지하는 시간이 달라집니다", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RATE_CHOICES.forEach { r ->
                    TrailerChoice("${"%.2f".format(r).trimEnd('0').trimEnd('.')}배", kotlin.math.abs(adjust.rate - r) < 1e-6, compact = true) {
                        model.editorEditAdjustments(scene.key) { it.copy(rate = r) }
                    }
                }
            }
        }
        AdvancedTool.DISSOLVE -> {
            val current = adjust.transition?.dissolveDuration ?: 0.0
            Text("다음 클립으로 넘어갈 때 겹쳐 사라지는 시간", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DISSOLVE_CHOICES.forEach { d ->
                    TrailerChoice(if (d == 0.0) "컷" else secs(d), kotlin.math.abs(current - d) < 1e-6, compact = true) {
                        model.editorEditAdjustments(scene.key) { it.copy(transition = if (d == 0.0) null else TrailerTransition(d)) }
                    }
                }
            }
        }
        AdvancedTool.COLOR -> ColorTool(scene, adjust, model)
        AdvancedTool.KEYFRAME -> KeyframeTool(scene, source, adjust, playheadSource, model)
        AdvancedTool.CUE -> CueTool(scene, source, adjust, playheadSource, model)
    }
}

private fun adjustedLabel(tool: AdvancedTool, a: TrailerClipAdjustments): String? = when (tool) {
    AdvancedTool.RATE -> if (a.rate != 1.0) "속도 ${"%.2f".format(a.rate).trimEnd('0').trimEnd('.')}배" else null
    AdvancedTool.DISSOLVE -> a.transition?.dissolveDuration?.takeIf { it > 0.0 }?.let { "전환 ${secs(it)}" }
    AdvancedTool.COLOR -> if (a.color?.isNeutral == false) "색상 조정됨" else null
    AdvancedTool.KEYFRAME -> if (a.keyframes.isNotEmpty()) "움직임 ${a.keyframes.size}" else null
    AdvancedTool.CUE -> if (a.cues.isNotEmpty()) "자막 ${a.cues.size}" else null
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String, onChange: (Float) -> Unit, onFinished: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(52.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1)
        Slider(value.coerceIn(range), onChange, Modifier.weight(1f).semantics { contentDescription = label }, valueRange = range, onValueChangeFinished = onFinished)
        Text(valueText, Modifier.width(48.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorTool(scene: EditorScene, adjust: TrailerClipAdjustments, model: NasFinderViewModel) {
    val color = adjust.color ?: TrailerColorAdjustment.NEUTRAL
    var brightness by remember(scene.key, color.brightness) { mutableFloatStateOf(color.brightness.toFloat()) }
    var contrast by remember(scene.key, color.contrast) { mutableFloatStateOf(color.contrast.toFloat()) }
    var saturation by remember(scene.key, color.saturation) { mutableFloatStateOf(color.saturation.toFloat()) }
    fun commit() {
        val next = TrailerColorAdjustment((brightness * 20).roundToInt() / 20.0, (contrast * 20).roundToInt() / 20.0, (saturation * 20).roundToInt() / 20.0)
        model.editorEditAdjustments(scene.key) { it.copy(color = next.takeUnless { c -> c.isNeutral }) }
    }
    LabeledSlider("밝기", brightness, -1f..1f, "%+.2f".format(brightness), { brightness = it }, ::commit)
    LabeledSlider("대비", contrast, 0.25f..4f, "%.2f".format(contrast), { contrast = it }, ::commit)
    LabeledSlider("채도", saturation, 0f..3f, "%.2f".format(saturation), { saturation = it }, ::commit)
    if (adjust.color?.isNeutral == false) {
        TextButton(shape = EditorButtonShape, onClick = { model.editorEditAdjustments(scene.key) { it.copy(color = null) } }) { Text("색상 초기화") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyframeTool(scene: EditorScene, source: TrailerSource, adjust: TrailerClipAdjustments, playheadSource: Double, model: NasFinderViewModel) {
    var selectedId by rememberSaveable(scene.key) { mutableStateOf<String?>(null) }
    val sorted = adjust.keyframes.sortedBy { it.sourceTime }
    val selected = sorted.firstOrNull { it.stableId.toString() == selectedId }
    val maxTime = scene.start + scene.length
    Text("재생 위치에 확대·이동·투명도 지점을 찍습니다 (원본 시간 기준)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Button(shape = EditorButtonShape, onClick = {
        val created = TrailerTransformKeyframe(sourceTime = playheadSource.coerceIn(scene.start, maxTime))
        selectedId = created.stableId.toString()
        model.editorEditAdjustments(scene.key) { it.copy(keyframes = it.keyframes + created) }
    }) { Text("현재 위치에 지점 추가", fontSize = 13.sp, maxLines = 1) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        sorted.forEach { kf ->
            TrailerChoice(secs(kf.sourceTime - scene.start), kf.stableId.toString() == selectedId, compact = true) { selectedId = kf.stableId.toString() }
        }
    }
    if (selected != null) {
        fun update(change: (TrailerTransformKeyframe) -> TrailerTransformKeyframe) {
            model.editorEditAdjustments(scene.key) { a -> a.copy(keyframes = a.keyframes.map { if (it.stableId == selected.stableId) change(it) else it }) }
        }
        var time by remember(selected.stableId, selected.sourceTime) { mutableFloatStateOf(selected.sourceTime.toFloat()) }
        var scale by remember(selected.stableId, selected.scale) { mutableFloatStateOf(selected.scale.toFloat()) }
        var x by remember(selected.stableId, selected.offsetX) { mutableFloatStateOf(selected.offsetX.toFloat()) }
        var y by remember(selected.stableId, selected.offsetY) { mutableFloatStateOf(selected.offsetY.toFloat()) }
        var opacity by remember(selected.stableId, selected.opacity) { mutableFloatStateOf(selected.opacity.toFloat()) }
        LabeledSlider("시간", time, scene.start.toFloat()..maxTime.toFloat().coerceAtLeast(scene.start.toFloat() + 0.1f), secs(time - scene.start), { time = it }, { update { k -> k.copy(sourceTime = time.toDouble()) } })
        LabeledSlider("확대", scale, 0.5f..4f, "%.2f배".format(scale), { scale = it }, { update { k -> k.copy(scale = scale.toDouble()) } })
        LabeledSlider("좌우", x, -1f..1f, "%+.2f".format(x), { x = it }, { update { k -> k.copy(offsetX = x.toDouble()) } })
        LabeledSlider("상하", y, -1f..1f, "%+.2f".format(y), { y = it }, { update { k -> k.copy(offsetY = y.toDouble()) } })
        LabeledSlider("투명도", opacity, 0f..1f, "${(opacity * 100).roundToInt()}%", { opacity = it }, { update { k -> k.copy(opacity = opacity.toDouble()) } })
        TextButton(shape = EditorButtonShape, onClick = {
            selectedId = null
            model.editorEditAdjustments(scene.key) { a -> a.copy(keyframes = a.keyframes.filterNot { it.stableId == selected.stableId }) }
        }) { Text("이 지점 삭제") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CueTool(scene: EditorScene, source: TrailerSource, adjust: TrailerClipAdjustments, playheadSource: Double, model: NasFinderViewModel) {
    var editingId by rememberSaveable(scene.key) { mutableStateOf<String?>(null) }
    var draftText by rememberSaveable(scene.key, editingId) { mutableStateOf("") }
    val windowEnd = scene.start + scene.length
    val cues = adjust.cues.filter { it.sourceId == source.id && it.sourceEnd > scene.start && it.sourceStart < windowEnd }.sortedBy { it.sourceStart }
    val editing = cues.firstOrNull { it.stableId.toString() == editingId }
    Text("원본 시간에 붙는 자막입니다. 직접 고치거나 지운 자막은 자동 생성이 다시 만들지 않습니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (editing == null) {
        OutlinedTextField(
            draftText, { draftText = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("자막 내용") },
        )
        Button(shape = EditorButtonShape, enabled = draftText.isNotBlank(), onClick = {
            val start = playheadSource.coerceIn(scene.start, (windowEnd - MIN_CUE_SECONDS).coerceAtLeast(scene.start))
            val cue = TrailerTimedCaptionCue(
                sourceId = source.id, sourceStart = start, sourceEnd = minOf(windowEnd, start + 2.0).coerceAtLeast(start + 0.1),
                text = draftText.trim(), style = scene.caption?.copy(text = "") ?: SceneCaption(text = ""), origin = CueOrigin.MANUAL, manuallyEdited = true,
            )
            if (model.editorEditAdjustments(scene.key) { it.copy(cues = it.cues + cue) }) { draftText = "" }
        }) { Text("현재 위치에 자막 추가", fontSize = 13.sp, maxLines = 1) }
    }
    cues.forEach { cue ->
        val isEditing = cue.stableId.toString() == editingId
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${secs(cue.sourceStart - scene.start)}~${secs(cue.sourceEnd - scene.start)} ${cue.text}" + if (cue.origin == CueOrigin.GENERATED && !cue.manuallyEdited) " · 자동" else "",
                Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            TextButton(shape = EditorButtonShape, onClick = { editingId = if (isEditing) null else cue.stableId.toString(); draftText = cue.text }) { Text(if (isEditing) "닫기" else "고치기") }
            TextButton(shape = EditorButtonShape, onClick = {
                if (isEditing) editingId = null
                model.editorEditAdjustments(scene.key) { a -> deleteCueWithTombstone(a, cue.stableId) }
            }) { Text("삭제") }
        }
    }
    if (editing != null) {
        var range by remember(editing.stableId, editing.sourceStart, editing.sourceEnd) {
            mutableStateOf(editing.sourceStart.toFloat()..editing.sourceEnd.toFloat().coerceAtMost(windowEnd.toFloat()))
        }
        OutlinedTextField(draftText, { draftText = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("자막 내용") })
        androidx.compose.material3.RangeSlider(
            value = range.start.coerceAtLeast(scene.start.toFloat())..range.endInclusive,
            onValueChange = { range = it },
            valueRange = scene.start.toFloat()..windowEnd.toFloat(),
            modifier = Modifier.semantics { contentDescription = "자막 시작과 끝" },
        )
        Text("${secs(range.start - scene.start)} ~ ${secs(range.endInclusive - scene.start)}", style = MaterialTheme.typography.labelSmall)
        OutlinedButton(shape = EditorButtonShape, enabled = draftText.isNotBlank() && range.endInclusive - range.start >= 0.1f, onClick = {
            val text = draftText.trim()
            val start = range.start.toDouble()
            val end = range.endInclusive.toDouble()
            if (model.editorEditAdjustments(scene.key) { a -> editCueWithTombstone(a, editing.stableId, text, start, end) }) editingId = null
        }) { Text("자막 저장", fontSize = 13.sp, maxLines = 1) }
    }
}

/** Deleting a cue (generated or manual) leaves a source-range tombstone so regeneration never resurrects it. */
internal fun deleteCueWithTombstone(a: TrailerClipAdjustments, id: UUID): TrailerClipAdjustments {
    val cue = a.cues.firstOrNull { it.stableId == id } ?: return a
    return a.copy(
        cues = a.cues.filterNot { it.stableId == id },
        suppressions = (a.suppressions + TrailerCueSuppression(cue.sourceId, cue.sourceStart, cue.sourceEnd)).distinct(),
    )
}

/** An edited cue becomes manual-owned; the range of an edited generated cue is also tombstoned. */
internal fun editCueWithTombstone(a: TrailerClipAdjustments, id: UUID, text: String, start: Double, end: Double): TrailerClipAdjustments {
    val cue = a.cues.firstOrNull { it.stableId == id } ?: return a
    val edited = cue.copy(text = text, sourceStart = start, sourceEnd = maxOf(end, start + 0.1), origin = CueOrigin.MANUAL, manuallyEdited = true)
    val tomb = if (cue.origin == CueOrigin.GENERATED && !cue.manuallyEdited) listOf(TrailerCueSuppression(cue.sourceId, cue.sourceStart, cue.sourceEnd)) else emptyList()
    return a.copy(cues = a.cues.map { if (it.stableId == id) edited else it }, suppressions = (a.suppressions + tomb).distinct())
}
