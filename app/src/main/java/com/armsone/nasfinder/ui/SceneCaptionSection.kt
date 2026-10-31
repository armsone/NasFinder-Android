@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.armsone.nasfinder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import kotlin.math.roundToInt

/** Unapplied caption form of exactly one scene; never applied to another scene implicitly. */
data class CaptionDraft(val sceneKey: Long, val caption: SceneCaption)

/** Keeps an unapplied caption form across rotation/recreation of the editor. */
internal val CaptionDraftSaver = Saver<CaptionDraft?, String>(
    save = { draft -> draft?.let { JSONObject().put("sceneKey", it.sceneKey).put("caption", it.caption.toJson()).toString() } },
    restore = { raw ->
        runCatching {
            val obj = JSONObject(raw)
            val caption = sceneCaptionFromJson(obj.optJSONObject("caption"), allowBlank = true) ?: return@runCatching null
            CaptionDraft(obj.getLong("sceneKey"), caption)
        }.getOrNull()
    },
)

private enum class CaptionScope { CURRENT, SELECTED, ALL }

private val CAPTION_TEXT_COLORS = listOf(
    "흰색" to 0xFFFFFFFF.toInt(), "검정" to 0xFF111111.toInt(), "노랑" to 0xFFFFE45C.toInt(),
    "하늘" to 0xFF8FD3FF.toInt(), "분홍" to 0xFFFF9EC7.toInt(), "민트" to 0xFF8EF0C8.toInt(),
)
private val CAPTION_SHADOW_COLORS = listOf("검정" to 0xFF000000.toInt(), "흰색" to 0xFFFFFFFF.toInt(), "남색" to 0xFF1A2A6C.toInt())
private val CAPTION_ROW_NAMES = listOf("맨 위", "위", "가운데", "아래", "맨 아래")
private val CAPTION_COLUMN_NAMES = listOf("왼쪽", "왼쪽 안쪽", "가운데", "오른쪽 안쪽", "오른쪽")

/**
 * 자막 inspector section: edits a local draft (live preview, no undo entry per keystroke) and writes it only with
 * an explicit 적용 to the current, selected or all scenes as one undoable edit.
 */
@Composable
internal fun SceneCaptionSection(
    session: TrailerSession,
    scene: EditorScene?,
    selectedKeys: Set<Long>,
    draft: CaptionDraft?,
    onDraftChange: (CaptionDraft?) -> Unit,
    model: NasFinderViewModel,
    onSelectScene: (Long) -> Unit,
) {
    val project = session.editor ?: return
    if (scene == null) {
        Text("자막을 넣을 장면을 장면 보드에서 선택해 주세요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    // A pending form of another scene is never shown/applied here: the user returns to it or discards it.
    if (draft != null && draft.sceneKey != scene.key) {
        val owner = project.scene(draft.sceneKey)
        val ownerDirty = owner != null && draft.caption.normalizedOrNull() != owner.caption?.normalizedOrNull()
        if (!ownerDirty && owner != null) {
            LaunchedEffect(draft) { onDraftChange(null) }
        } else {
            val ownerIndex = project.scenes.indexOfFirst { it.key == draft.sceneKey }
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(10.dp)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (ownerIndex >= 0) "${ownerIndex + 1}번 장면에서 고친 자막을 아직 적용하지 않았습니다." else "삭제된 장면에서 고친 자막이 적용되지 않은 채 남아 있습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (ownerIndex >= 0) {
                            Button(onClick = { onSelectScene(draft.sceneKey) }) { Text("${ownerIndex + 1}번 장면으로 돌아가기") }
                        }
                        OutlinedButton(onClick = { onDraftChange(null) }) { Text("고친 내용 버리기") }
                    }
                }
            }
            return
        }
    }

    val index = project.scenes.indexOfFirst { it.key == scene.key }
    val stored = scene.caption?.normalizedOrNull()
    val ownDraft = draft?.takeIf { it.sceneKey == scene.key }
    val form = ownDraft?.caption ?: stored ?: SceneCaption(text = "")
    val dirty = ownDraft != null && ownDraft.caption.normalizedOrNull() != stored
    fun update(next: SceneCaption) = onDraftChange(CaptionDraft(scene.key, next))

    val sceneKeys = remember(project.scenes) { project.scenes.map { it.key }.toSet() }
    val selectedInProject = selectedKeys.filter { it in sceneKeys }.toSet().ifEmpty { setOf(scene.key) }
    var chosenScope by remember(scene.key) { mutableStateOf(CaptionScope.CURRENT) }
    val scope = if (chosenScope == CaptionScope.SELECTED && selectedInProject.size < 2) CaptionScope.CURRENT else chosenScope
    val targetKeys = when (scope) {
        CaptionScope.CURRENT -> setOf(scene.key)
        CaptionScope.SELECTED -> selectedInProject
        CaptionScope.ALL -> sceneKeys
    }

    var fieldValue by remember(scene.key) { mutableStateOf(TextFieldValue(form.text, TextRange(form.text.length))) }
    LaunchedEffect(form.text) {
        if (fieldValue.text != form.text) fieldValue = TextFieldValue(form.text, TextRange(form.text.length))
    }

    TrailerSectionTitle("자막 · ${index + 1}번 장면")
    Text(
        when {
            dirty -> "적용하지 않은 변경이 있습니다 · 미리보기에만 보입니다"
            stored != null -> "이 장면에 자막이 적용되어 있습니다"
            else -> "이 장면에는 자막이 없습니다"
        },
        style = MaterialTheme.typography.labelMedium,
        fontWeight = if (dirty) FontWeight.SemiBold else FontWeight.Normal,
        color = if (dirty) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // Scope + Apply/Cancel stay above the text field so the keyboard never hides them.
    Text("적용 범위", style = MaterialTheme.typography.labelMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FilterChip(selected = scope == CaptionScope.CURRENT, onClick = { chosenScope = CaptionScope.CURRENT }, label = { Text("현재 장면") })
        FilterChip(
            selected = scope == CaptionScope.SELECTED,
            onClick = { chosenScope = CaptionScope.SELECTED },
            enabled = selectedInProject.size >= 2,
            label = { Text("선택한 장면 ${selectedInProject.size}개") },
        )
        FilterChip(selected = scope == CaptionScope.ALL, onClick = { chosenScope = CaptionScope.ALL }, label = { Text("전체 ${project.scenes.size}개") })
    }
    val blank = form.text.isBlank()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(
            onClick = {
                model.editorApplySceneCaption(targetKeys, form.normalizedOrNull())
                onDraftChange(null)
            },
            enabled = !session.busy && (dirty || scope != CaptionScope.CURRENT),
        ) {
            Text(
                when {
                    blank && scope == CaptionScope.CURRENT -> "자막 지우기 적용"
                    blank -> "${targetKeys.size}개 장면 자막 지우기"
                    scope == CaptionScope.CURRENT -> "적용"
                    else -> "${targetKeys.size}개 장면에 적용"
                },
            )
        }
        OutlinedButton(onClick = { onDraftChange(null) }, enabled = dirty) { Text("취소") }
    }
    Text(
        "적용하면 글자와 모양 전체가 범위의 장면에 들어갑니다. 내용을 비우고 적용하면 자막이 지워지며, 실행 취소로 되돌릴 수 있습니다.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    OutlinedTextField(
        value = fieldValue,
        onValueChange = { next ->
            if (next.text.length > MAX_SCENE_CAPTION_CHARS) return@OutlinedTextField
            fieldValue = next
            if (next.text != form.text) update(form.copy(text = next.text))
        },
        label = { Text("자막 내용") },
        placeholder = { Text("여러 줄로 입력할 수 있습니다") },
        minLines = 2,
        maxLines = 6,
        supportingText = { Text("${fieldValue.text.length} / ${MAX_SCENE_CAPTION_CHARS}자") },
        modifier = Modifier.fillMaxWidth(),
    )
    val scopeSources = remember(targetKeys, project.scenes, session.sources) {
        val byId = session.sources.associateBy { it.id }
        project.scenes.filter { it.key in targetKeys }.mapNotNull { byId[it.sourceId] }
    }
    val captureText = captionCaptureRangeText(scopeSources)
    fun insert(text: String) {
        val (merged, cursor) = insertCaptionText(fieldValue.text, fieldValue.selection.start, fieldValue.selection.end, text)
        if (merged.length > MAX_SCENE_CAPTION_CHARS) return
        fieldValue = TextFieldValue(merged, TextRange(cursor))
        update(form.copy(text = merged))
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        AssistChip(onClick = { insert(captionDateText(System.currentTimeMillis())) }, label = { Text("오늘 날짜 삽입") })
        AssistChip(
            onClick = { captureText?.let(::insert) },
            enabled = captureText != null,
            label = { Text(if (captureText == null) "촬영일 정보 없음" else if (captureText.contains('~')) "촬영 기간 삽입" else "촬영일 삽입") },
        )
    }

    Text("프리셋", style = MaterialTheme.typography.labelMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SceneCaption.PRESETS.forEach { (title, preset) ->
            FilterChip(selected = form.withStyleOf(preset) == form, onClick = { update(form.withStyleOf(preset)) }, label = { Text(title) })
        }
    }
    Text("글꼴", style = MaterialTheme.typography.labelMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        CaptionFont.entries.forEach { font ->
            FilterChip(selected = form.font == font, onClick = { update(form.copy(font = font)) }, label = { Text(font.title) })
        }
    }
    Text("크기", style = MaterialTheme.typography.labelMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        CaptionSize.entries.forEach { size ->
            FilterChip(selected = form.size == size, onClick = { update(form.copy(size = size)) }, label = { Text(size.title) })
        }
    }
    Text("글자 색", style = MaterialTheme.typography.labelMedium)
    CaptionColorRow(CAPTION_TEXT_COLORS, form.textColor) { update(form.copy(textColor = it)) }
    var hexInput by remember(scene.key, form.textColor) { mutableStateOf(captionHex(form.textColor)) }
    OutlinedTextField(
        value = hexInput,
        onValueChange = { input ->
            hexInput = input.take(7)
            parseCaptionHex(hexInput)?.let { if (it != form.textColor) update(form.copy(textColor = it)) }
        },
        label = { Text("직접 입력 (#RRGGBB)") },
        singleLine = true,
        isError = parseCaptionHex(hexInput) == null,
        modifier = Modifier.widthIn(max = 220.dp),
    )

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("그림자", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        Switch(checked = form.shadowEnabled, onCheckedChange = { update(form.copy(shadowEnabled = it)) })
    }
    if (form.shadowEnabled) {
        CaptionColorRow(CAPTION_SHADOW_COLORS, form.shadowColor) { update(form.copy(shadowColor = it)) }
        Text("그림자 진하기 ${(form.shadowOpacity * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall)
        Slider(
            value = form.shadowOpacity.coerceIn(0f, 1f),
            onValueChange = { update(form.copy(shadowOpacity = (it * 20).roundToInt() / 20f)) },
            valueRange = 0f..1f,
            modifier = Modifier.semantics { contentDescription = "그림자 진하기" },
        )
    }
    Text("줄 간격", style = MaterialTheme.typography.labelMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        CAPTION_LINE_SPACINGS.forEach { (title, value) ->
            FilterChip(selected = kotlin.math.abs(form.lineSpacing - value) < 0.01f, onClick = { update(form.copy(lineSpacing = value)) }, label = { Text(title) })
        }
    }
    Text("위치 · ${CAPTION_ROW_NAMES[form.row.coerceIn(0, 4)]} ${CAPTION_COLUMN_NAMES[form.column.coerceIn(0, 4)]}", style = MaterialTheme.typography.labelMedium)
    Column(
        Modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = .78f)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (row in 0..4) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (column in 0..4) {
                    val selected = form.row == row && form.column == column
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = .12f))
                            .border(1.dp, Color.White.copy(alpha = if (selected) .9f else .3f), RoundedCornerShape(6.dp))
                            .semantics {
                                contentDescription = "자막 위치 ${CAPTION_ROW_NAMES[row]} ${CAPTION_COLUMN_NAMES[column]}"
                                this.selected = selected
                            }
                            .clickable { update(form.copy(row = row, column = column)) },
                    )
                }
            }
        }
    }
    Text("자막은 장면 전체 길이 동안 영상 화면 기준 안전 영역(가장자리 8%) 안에 표시되며, 사진 무빙이나 화면 위치와 함께 움직이지 않습니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CaptionColorRow(colors: List<Pair<String, Int>>, current: Int, onPick: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        colors.forEach { (name, color) ->
            val selected = (current or 0xFF000000.toInt()) == color
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color(color))
                    .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                    .semantics {
                        contentDescription = name
                        this.selected = selected
                    }
                    .clickable { onPick(color) },
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Text("✓", color = if (color == 0xFFFFFFFF.toInt()) Color.Black else Color.White, fontSize = 14.sp)
            }
        }
    }
}
