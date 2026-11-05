@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.armsone.nasfinder.ui

import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.armsone.nasfinder.ui.theme.HomeType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Unapplied caption form of exactly one scene with the 적용 범위 the user chose for it ([targetKeys] are the
 * CURRENT/SELECTED clips; ALL means the whole video when committed). Never applied to another scene implicitly.
 */
data class CaptionDraft(
    val sceneKey: Long,
    val caption: SceneCaption,
    val scope: CaptionScope = CaptionScope.ALL,
    val targetKeys: Set<Long> = setOf(sceneKey),
) {
    /** The clips 적용/편집본 저장/영상으로 만들기 write this form to; empty when its own clip no longer exists. */
    fun commitKeys(project: EditorProject): Set<Long> {
        if (project.scene(sceneKey) == null) return emptySet()
        val live = project.scenes.map { it.key }.toSet()
        return when (scope) {
            CaptionScope.CURRENT -> setOf(sceneKey)
            CaptionScope.SELECTED -> targetKeys.filter { it in live }.toSet()
            CaptionScope.ALL -> live
        }
    }
}

/** Keeps an unapplied caption form (and its scope) across rotation/recreation; older saves restore as 전체 영상. */
internal val CaptionDraftSaver = Saver<CaptionDraft?, String>(
    save = { draft ->
        draft?.let {
            JSONObject().put("sceneKey", it.sceneKey).put("caption", it.caption.toJson())
                .put("scope", it.scope.name).put("targetKeys", org.json.JSONArray(it.targetKeys.toList())).toString()
        }
    },
    restore = { raw ->
        runCatching {
            val obj = JSONObject(raw)
            val caption = sceneCaptionFromJson(obj.optJSONObject("caption"), allowBlank = true) ?: return@runCatching null
            val sceneKey = obj.getLong("sceneKey")
            val scope = CaptionScope.entries.firstOrNull { it.name == obj.optString("scope") } ?: CaptionScope.ALL
            val targets = obj.optJSONArray("targetKeys")?.let { array -> (0 until array.length()).map(array::getLong).toSet() }
            CaptionDraft(sceneKey, caption, scope, targets?.takeIf { it.isNotEmpty() } ?: setOf(sceneKey))
        }.getOrNull()
    },
)

/**
 * Writes [caption] to [keys] as the one undoable caption edit; true only when every target now carries it
 * (a busy project or vanished clips report false, so the caller keeps the form instead of discarding it).
 */
internal fun NasFinderViewModel.commitSceneCaption(keys: Set<Long>, caption: SceneCaption?): Boolean {
    if (keys.isEmpty()) return false
    val normalized = caption?.normalizedOrNull()
    editorApplySceneCaption(keys, normalized)
    val live = state.value.trailer?.editor ?: return false
    return keys.all { key -> live.scene(key)?.let { it.caption?.normalizedOrNull() == normalized } == true }
}

enum class CaptionScope { CURRENT, SELECTED, ALL }

private val CAPTION_TEXT_COLORS = listOf(
    "흰색" to 0xFFFFFFFF.toInt(),
    "검정" to 0xFF111111.toInt(),
    "노랑" to 0xFFFFE45C.toInt(),
    "하늘" to 0xFF8FD3FF.toInt(),
    "분홍" to 0xFFFF9EC7.toInt(),
    "민트" to 0xFF8EF0C8.toInt(),
)

private val CAPTION_SHADOW_COLORS = listOf(
    "검정" to 0xFF000000.toInt(),
    "흰색" to 0xFFFFFFFF.toInt(),
    "남색" to 0xFF1A2A6C.toInt(),
    "보라" to 0xFF642BFF.toInt(),
    "청록" to 0xFF18A8FF.toInt(),
    "딥그린" to 0xFF3F6F63.toInt(),
)

private val CAPTION_ROW_NAMES = listOf("맨 위", "위", "가운데", "아래", "맨 아래")
private val CAPTION_COLUMN_NAMES = listOf("왼쪽", "왼쪽 안쪽", "가운데", "오른쪽 안쪽", "오른쪽")

/**
 * Compact choice helper for radio-like button choices (Scope, Size, LineSpacing).
 * Provides 48dp touch target, 32dp visual pill, 12sp text, avoids FilterChip's heavy internal padding.
 */
@Composable
internal fun CompactChoice(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    Box(
        modifier = modifier
            .height(48.dp)
            .semantics {
                role = Role.RadioButton
                this.selected = selected
                if (contentDescription != null) {
                    this.contentDescription = contentDescription
                }
            }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp),
            shape = RoundedCornerShape(8.dp),
            color = when {
                !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)
                selected -> MaterialTheme.colorScheme.primaryContainer
                else -> Color.Transparent
            },
            contentColor = when {
                !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                selected -> MaterialTheme.colorScheme.onPrimaryContainer
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            border = if (!selected && enabled) BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant) else null
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    ),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 적용 범위(현재/선택/전체)와 호출자가 넘기는 추가 선택지(시작/마지막 화면)를 한 줄로 그린다. 폭이 모자라면 줄바꿈한다.
 * [scope] 가 null 이면 범위 선택지는 모두 해제 상태(카드 편집 중)다. 본문 inspector 와 카드 inspector 가 이 행 하나를 공유한다.
 */
@Composable
internal fun CaptionScopeChoices(
    scope: CaptionScope?,
    selectedCount: Int,
    onChoose: (CaptionScope) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    extra: @Composable () -> Unit = {},
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        val choiceModifier = Modifier.width(80.dp)
        CompactChoice(
            text = "현재 클립",
            selected = scope == CaptionScope.CURRENT,
            onClick = { onChoose(CaptionScope.CURRENT) },
            modifier = choiceModifier,
            enabled = enabled,
        )
        CompactChoice(
            text = "선택 클립",
            selected = scope == CaptionScope.SELECTED,
            onClick = { onChoose(CaptionScope.SELECTED) },
            enabled = enabled && selectedCount >= 2,
            contentDescription = if (selectedCount >= 2) "선택 클립 $selectedCount" else "선택 클립",
            modifier = choiceModifier,
        )
        CompactChoice(
            text = "전체 영상",
            selected = scope == CaptionScope.ALL,
            onClick = { onChoose(CaptionScope.ALL) },
            modifier = choiceModifier,
            enabled = enabled,
        )
        extra()
    }
}

/**
 * Helper to wrap atomic visual groups in the Home porcelain card pattern:
 * left-aligned section title outside the Surface card with an 8dp gap,
 * clean Surface card with 12dp rounded corners and 0.5dp outlineVariant border.
 */
@Composable
private fun CaptionVisualGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            style = HomeType.Section.copy(fontSize = 17.sp, lineHeight = 24.sp),
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.fillMaxWidth()
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                content = content
            )
        }
    }
}

/**
 * 자막 inspector section: edits a local draft (live preview, no undo entry per keystroke) and writes it with 적용, or
 * with 편집본 저장/영상으로 만들기, to the chosen current, selected or all scenes as one undoable edit. One controller (its state lives here)
 * returns two groups — text/적용/범위 and the look (프리셋·글꼴·색·위치) — that the page places in one column or
 * side by side, so a relayout never duplicates or resets the form.
 */
@Composable
internal fun sceneCaptionColumns(
    session: TrailerSession,
    scene: EditorScene?,
    selectedKeys: Set<Long>,
    draft: CaptionDraft?,
    onDraftChange: (CaptionDraft?) -> Unit,
    model: NasFinderViewModel,
    onSelectScene: (Long) -> Unit,
    scopeExtras: @Composable () -> Unit = {},
    pendingScope: CaptionScope? = null,
    onPendingScopeHandled: () -> Unit = {},
): InspectorColumns {
    val project = session.editor ?: return InspectorColumns(left = {})
    if (scene == null) {
        return InspectorColumns(left = {
            CaptionScopeChoices(scope = null, selectedCount = 0, onChoose = {}, enabled = false, extra = scopeExtras)
            Text("자막을 넣을 클립을 클립 목록에서 선택해 주세요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        })
    }
    // A pending form of another scene is never shown/applied here: the user returns to it or discards it.
    if (draft != null && draft.sceneKey != scene.key) {
        val owner = project.scene(draft.sceneKey)
        val ownerDirty = owner != null && draft.caption.normalizedOrNull() != owner.caption?.normalizedOrNull()
        if (!ownerDirty && owner != null) {
            LaunchedEffect(draft) { onDraftChange(null) }
        } else return InspectorColumns(left = {
            CaptionScopeChoices(scope = null, selectedCount = 0, onChoose = {}, enabled = false, extra = scopeExtras)
            val ownerIndex = project.scenes.indexOfFirst { it.key == draft.sceneKey }
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(10.dp)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (ownerIndex >= 0) "${ownerIndex + 1}번 클립에서 고친 자막을 아직 적용하지 않았습니다." else "삭제된 클립에서 고친 자막이 적용되지 않은 채 남아 있습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (ownerIndex >= 0) {
                            Button(shape = EditorButtonShape, onClick = { onSelectScene(draft.sceneKey) }) { Text("${ownerIndex + 1}번 클립으로 돌아가기") }
                        }
                        OutlinedButton(shape = EditorButtonShape, onClick = { onDraftChange(null) }) { Text("고친 내용 버리기") }
                    }
                }
            }
        })
    }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val isTablet = configuration.smallestScreenWidthDp >= 600

    val index = project.scenes.indexOfFirst { it.key == scene.key }
    val stored = scene.caption?.normalizedOrNull()
    val ownDraft = draft?.takeIf { it.sceneKey == scene.key }
    // A new caption starts at the top left; committed captions and pending forms keep their own position.
    val form = ownDraft?.caption ?: stored ?: SceneCaption(text = "", row = 0, column = 0)
    val dirty = ownDraft != null && ownDraft.caption.normalizedOrNull() != stored

    val sceneKeys = remember(project.scenes) { project.scenes.map { it.key }.toSet() }
    val selectedInProject = selectedKeys.filter { it in sceneKeys }.toSet().ifEmpty { setOf(scene.key) }
    // Defaults to the current clip so typing autosaves into exactly this clip; 선택/전체 are explicit choices that keep the
    // manual 적용. A pending form carries its own scope, so tab/relayout/rotation never resets it.
    var chosenScope by rememberSaveable(scene.key) { mutableStateOf(ownDraft?.scope ?: CaptionScope.CURRENT) }
    val requestedScope = ownDraft?.scope ?: chosenScope
    fun shownScope(requested: CaptionScope) = if (requested == CaptionScope.SELECTED && selectedInProject.size < 2) CaptionScope.CURRENT else requested
    fun targetsOf(requested: CaptionScope) = when (shownScope(requested)) {
        CaptionScope.CURRENT -> setOf(scene.key)
        CaptionScope.SELECTED -> selectedInProject
        CaptionScope.ALL -> sceneKeys
    }
    val scope = shownScope(requestedScope)
    val targetKeys = targetsOf(requestedScope)
    fun update(next: SceneCaption) = onDraftChange(CaptionDraft(scene.key, next, requestedScope, targetKeys))
    fun choose(next: CaptionScope) {
        chosenScope = next
        ownDraft?.let { onDraftChange(it.copy(scope = next, targetKeys = targetsOf(next))) }
    }
    // A pending form follows a selection change of its scope; equal targets write nothing, so this never loops.
    LaunchedEffect(ownDraft, targetKeys) {
        if (ownDraft != null && ownDraft.targetKeys != targetKeys) onDraftChange(ownDraft.copy(targetKeys = targetKeys))
    }
    // 카드 화면에서 범위 선택지를 눌러 본문으로 돌아온 경우, 그 범위를 기존 choose() 로 한 번만 적용한다.
    LaunchedEffect(pendingScope) {
        if (pendingScope != null) {
            choose(pendingScope)
            onPendingScopeHandled()
        }
    }

    var fieldValue by remember(scene.key) { mutableStateOf(TextFieldValue(form.text, TextRange(form.text.length))) }
    LaunchedEffect(form.text) {
        if (fieldValue.text != form.text) fieldValue = TextFieldValue(form.text, TextRange(form.text.length))
    }
    val scopeSources = remember(targetKeys, project.scenes, session.sources) {
        val byId = session.sources.associateBy { it.id }
        project.scenes.filter { it.key in targetKeys }.mapNotNull { byId[it.sourceId] }
    }
    val captureText = captionCaptureRangeText(scopeSources)

    // Presets state & SharedPreferences persistence
    var activeCaptionPreset by remember(scene.key) { mutableStateOf<CaptionStylePreset?>(null) }
    var presetAppearances by remember(context) { mutableStateOf(CaptionPresetStore.load(context)) }

    // Synchronize custom appearance edits back to active preset storage
    LaunchedEffect(
        scene.key,
        activeCaptionPreset,
        form.effectiveFontName,
        form.textColor,
        form.shadowEnabled,
        form.shadowColor,
        form.shadowOpacity,
        form.size,
        form.lineSpacing,
    ) {
        val active = activeCaptionPreset ?: return@LaunchedEffect
        if (form.effectiveFontName != active.fontName) return@LaunchedEffect
        val currentApp = CaptionPresetAppearance.from(form)
        val updated = presetAppearances + (active to currentApp)
        if (updated != presetAppearances) {
            presetAppearances = updated
            CaptionPresetStore.save(context, updated)
        }
    }

    // Imported fonts list & launcher
    var importedFonts by remember { mutableStateOf(CaptionFontStore.list(context)) }
    var selectedFontCategory by remember { mutableStateOf<CaptionFontCategory?>(null) }

    val currentSceneKey by rememberUpdatedState(scene.key)
    val currentForm by rememberUpdatedState(form)
    val currentRequestedScope by rememberUpdatedState(requestedScope)
    val currentTargetKeys by rememberUpdatedState(targetKeys)
    val currentOnDraftChange by rememberUpdatedState(onDraftChange)

    val fontPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val ownerSceneKey = currentSceneKey
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val imported = CaptionFontStore.import(context, uri)
                    withContext(Dispatchers.Main) {
                        importedFonts = CaptionFontStore.list(context)
                        if (currentSceneKey == ownerSceneKey) {
                            currentOnDraftChange(
                                CaptionDraft(
                                    sceneKey = ownerSceneKey,
                                    caption = currentForm.withFont(imported.id),
                                    scope = currentRequestedScope,
                                    targetKeys = currentTargetKeys,
                                )
                            )
                        }
                        Toast.makeText(context, "${imported.displayName} 글꼴을 가져왔습니다.", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (error: Throwable) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            error.message ?: "글꼴 파일을 가져오지 못했습니다.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    fun insert(text: String, replaceBasic: Boolean = false) {
        val (merged, cursor) = CaptionDateFormatter.insertDateText(
            currentText = fieldValue.text,
            selectionStart = fieldValue.selection.start,
            selectionEnd = fieldValue.selection.end,
            dateText = text,
            replaceBasicText = replaceBasic,
        )
        if (merged.length > MAX_SCENE_CAPTION_CHARS) return
        fieldValue = TextFieldValue(merged, TextRange(cursor))
        update(form.copy(text = merged))
    }

    // Tab selection mode for in-panel catalog in group B: "style" (default) or "font"
    var activeCatalogTab by rememberSaveable(scene.key) { mutableStateOf("style") }

    val positionTitle: @Composable () -> Unit = {
        Text(
            "위치 · ${CAPTION_ROW_NAMES[form.row.coerceIn(0, 4)]} ${CAPTION_COLUMN_NAMES[form.column.coerceIn(0, 4)]}",
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }

    val positionControls: @Composable () -> Unit = {
        Surface(
            modifier = Modifier.size(160.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceEvenly,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                for (r in 0..4) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(32.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        for (c in 0..4) {
                            val isSelected = form.row == r && form.column == c
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .semantics {
                                        contentDescription = "자막 위치 ${CAPTION_ROW_NAMES[r]} ${CAPTION_COLUMN_NAMES[c]}"
                                        this.selected = isSelected
                                    }
                                    .clickable { update(form.copy(row = r, column = c)) },
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(MaterialTheme.colorScheme.primary),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("T", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Black, fontSize = 12.sp)
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(4.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val contentControls: @Composable () -> Unit = {
        CaptionVisualGroup("내용과 위치") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${index + 1}번 클립",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
                if (dirty) {
                    TextButton(
                        onClick = { onDraftChange(null) },
                        modifier = Modifier.minimumInteractiveComponentSize().height(32.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text("취소", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Top
            ) {
                CaptionScopeChoices(
                    scope = scope,
                    selectedCount = selectedInProject.size,
                    onChoose = ::choose,
                    modifier = Modifier.weight(1f),
                    extra = scopeExtras,
                )
                val blank = form.text.isBlank()
                Button(
                    modifier = Modifier
                        .padding(top = 7.dp) // 첫 줄 선택지(48dp 터치 영역, 32dp 알약)와 세로 중심을 맞춘다.
                        .width(64.dp)
                        .height(34.dp),
                    shape = EditorButtonShape,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    colors = if (blank) {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                    onClick = {
                        if (model.commitSceneCaption(targetKeys, form)) onDraftChange(null)
                    },
                    enabled = !session.busy && (dirty || scope != CaptionScope.CURRENT),
                ) {
                    Text(
                        if (blank) "지우기" else "적용",
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            val captionSupportingText: (@Composable () -> Unit)? = if (fieldValue.text.length >= MAX_SCENE_CAPTION_CHARS - 200) {
                { Text("${fieldValue.text.length} / ${MAX_SCENE_CAPTION_CHARS}자") }
            } else null

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
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * form.lineSpacing,
                ),
                supportingText = captionSupportingText,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(6.dp))

            val isBasic = CaptionDateFormatter.isBasicCaptionText(fieldValue.text, captureText)

            val dateActionsColumn: @Composable (Modifier) -> Unit = { mod ->
                Column(
                    modifier = mod,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { insert(CaptionDateFormatter.single(), replaceBasic = isBasic) }
                            .semantics {
                                role = Role.Button
                                contentDescription = "오늘 날짜 자막 입력"
                            },
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                "오늘 날짜",
                                style = HomeType.Compact.copy(fontSize = 12.sp),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    val hasCapture = captureText != null
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = hasCapture) { captureText?.let { insert(it, replaceBasic = isBasic) } }
                            .semantics {
                                role = Role.Button
                                contentDescription = if (captureText == null) "촬영일 없음" else if (captureText.contains('~')) "촬영 기간 입력" else "촬영일 입력"
                            },
                        shape = RoundedCornerShape(8.dp),
                        color = if (hasCapture) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f),
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                if (captureText == null) "촬영일 없음" else if (captureText.contains('~')) "촬영 기간" else "촬영일",
                                style = HomeType.Compact.copy(fontSize = 12.sp),
                                color = if (hasCapture) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                if (maxWidth >= 300.dp) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        positionTitle()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            positionControls()
                            dateActionsColumn(Modifier.weight(1f).height(160.dp))
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        positionTitle()
                        positionControls()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { insert(CaptionDateFormatter.single(), replaceBasic = isBasic) }
                                    .semantics {
                                        role = Role.Button
                                        contentDescription = "오늘 날짜 자막 입력"
                                    },
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("오늘 날짜", style = HomeType.Compact.copy(fontSize = 12.sp), maxLines = 1)
                                }
                            }
                            val hasCapture = captureText != null
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = hasCapture) { captureText?.let { insert(it, replaceBasic = isBasic) } }
                                    .semantics {
                                        role = Role.Button
                                        contentDescription = if (captureText == null) "촬영일 없음" else if (captureText.contains('~')) "촬영 기간 입력" else "촬영일 입력"
                                    },
                                shape = RoundedCornerShape(8.dp),
                                color = if (hasCapture) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f),
                                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        if (captureText == null) "촬영일 없음" else if (captureText.contains('~')) "촬영 기간" else "촬영일",
                                        style = HomeType.Compact.copy(fontSize = 12.sp),
                                        color = if (hasCapture) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val typographyControls: @Composable () -> Unit = {
        val activePreset = CaptionStylePreset.entries.firstOrNull { preset ->
            val appearance = presetAppearances[preset] ?: preset.defaultAppearance
            preset.matches(form, appearance)
        }
        val styleTitle = activePreset?.title ?: "사용자 설정"
        val currentFontFamily = remember(form.effectiveFontName) {
            CaptionFontStore.fontFamilyForName(context, form.effectiveFontName)
        }
        val currentFontDisplayName = CaptionFontStore.displayName(form.effectiveFontName)

        val columnsCount = if (isTablet) 3 else 2
        val isStyleOpen = activeCatalogTab == "style"
        val isFontOpen = activeCatalogTab == "font"

        CaptionVisualGroup("스타일과 글꼴") {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 1. STYLE Section Header (48dp height, stacked)
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { activeCatalogTab = "style" }
                        .semantics {
                            role = Role.Button
                            stateDescription = if (isStyleOpen) "펼쳐짐" else "접힘"
                            contentDescription = "스타일 선택, 현재 $styleTitle"
                        },
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Transparent,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "스타일",
                            style = HomeType.Compact,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            modifier = Modifier.width(56.dp)
                        )
                        Text(
                            styleTitle,
                            style = HomeType.Description,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Box(
                            modifier = Modifier.size(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (isStyleOpen) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // STYLE grid IF activeStyle
                if (isStyleOpen) {
                    val presets = CaptionStylePreset.entries
                    val rows = presets.chunked(columnsCount)
                    rows.forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            rowItems.forEach { preset ->
                                val appearance = presetAppearances[preset] ?: preset.defaultAppearance
                                val isSelected = preset.matches(form, appearance)
                                val previewFontFamily = remember(preset.fontName) {
                                    CaptionFontStore.fontFamilyForName(context, preset.fontName)
                                }
                                val textColor = Color(parseCaptionHex(appearance.textColorHex) ?: 0xFFFFFFFF.toInt())
                                val isBrightText = (textColor.red * 0.299f + textColor.green * 0.587f + textColor.blue * 0.114f) > 0.55f
                                val badgeBackground = if (isBrightText) Color(0xFF222222) else Color(0xFFEEEEEE)
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            activeCaptionPreset = preset
                                            update(preset.applyTo(form, appearance))
                                        }
                                        .semantics {
                                            this.selected = isSelected
                                            role = Role.RadioButton
                                            contentDescription = "${preset.title} 스타일"
                                        },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                    border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            if (isSelected) "✓ ${preset.title}" else preset.title,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontSize = 13.sp,
                                                lineHeight = 18.sp,
                                                color = MaterialTheme.colorScheme.onSurface
                                            ),
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(badgeBackground),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                "Aa",
                                                fontFamily = previewFontFamily,
                                                fontWeight = FontWeight.Black,
                                                fontSize = 14.sp,
                                                color = textColor
                                            )
                                        }
                                    }
                                }
                            }
                            // Fill empty slots with Spacers
                            for (i in 0 until (columnsCount - rowItems.size)) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            modifier = Modifier.height(32.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            onClick = {
                                activeCaptionPreset = null
                                presetAppearances = emptyMap()
                                CaptionPresetStore.reset(context)
                                Toast.makeText(context, "프리셋 기본 모양을 초기화했습니다.", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Text("프리셋 기본값 초기화", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp))
                        }
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.fillMaxWidth(),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                // 2. FONT Section Header (48dp height, stacked below style)
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { activeCatalogTab = "font" }
                        .semantics {
                            role = Role.Button
                            stateDescription = if (isFontOpen) "펼쳐짐" else "접힘"
                            contentDescription = "글꼴 선택, 현재 $currentFontDisplayName"
                        },
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Transparent,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "글꼴",
                            style = HomeType.Compact,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            modifier = Modifier.width(56.dp)
                        )
                        Text(
                            currentFontDisplayName,
                            fontFamily = currentFontFamily,
                            style = HomeType.Description,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Box(
                            modifier = Modifier.size(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (isFontOpen) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // FONT category + font grid IF activeFont
                if (isFontOpen) {
                    // Category chips & font import button in one subtle background strip
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                "글꼴 분류",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium
                                )
                            )
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                FilterChip(
                                    modifier = Modifier.minimumInteractiveComponentSize().height(32.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    border = null,
                                    selected = selectedFontCategory == null,
                                    onClick = { selectedFontCategory = null },
                                    label = { Text("전체", style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp)) }
                                )
                                CaptionFontCategory.entries.forEach { category ->
                                    val count = when (category) {
                                        CaptionFontCategory.USER -> importedFonts.size
                                        else -> CaptionFontStore.BUNDLED_FONTS.count { it.category == category }
                                    }
                                    if (count > 0 || category == CaptionFontCategory.USER) {
                                        FilterChip(
                                            modifier = Modifier.minimumInteractiveComponentSize().height(32.dp),
                                            shape = RoundedCornerShape(8.dp),
                                            border = null,
                                            selected = selectedFontCategory == category,
                                            onClick = { selectedFontCategory = if (selectedFontCategory == category) null else category },
                                            label = { Text("${category.title} ($count)", style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp)) }
                                        )
                                    }
                                }
                                OutlinedButton(
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .height(32.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    border = null,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    onClick = {
                                        fontPicker.launch(
                                            arrayOf(
                                                "font/ttf",
                                                "font/otf",
                                                "application/x-font-ttf",
                                                "application/x-font-opentype",
                                                "application/octet-stream",
                                            )
                                        )
                                    }
                                ) {
                                    Text("+ 가져오기", style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp))
                                }
                            }
                        }
                    }

                    val currentCategory = selectedFontCategory
                    val candidateBundled = if (currentCategory == null) {
                        CaptionFontStore.BUNDLED_FONTS
                    } else {
                        CaptionFontStore.BUNDLED_FONTS.filter { it.category == currentCategory }
                    }
                    val candidateImported = if (currentCategory == null || currentCategory == CaptionFontCategory.USER) {
                        importedFonts
                    } else {
                        emptyList()
                    }

                    data class FontTileItem(val id: String, val displayName: String, val isUser: Boolean)
                    val allFontItems = candidateBundled.map { FontTileItem(it.id, it.displayName, false) } +
                            candidateImported.map { FontTileItem(it.id, it.displayName, true) }

                    val fontRows = allFontItems.chunked(columnsCount)
                    fontRows.forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            rowItems.forEach { item ->
                                val isSelected = form.effectiveFontName == item.id
                                val previewFontFamily = remember(item.id) {
                                    CaptionFontStore.fontFamilyForName(context, item.id)
                                }
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            update(form.withFont(item.id))
                                        }
                                        .semantics {
                                            this.selected = isSelected
                                            role = Role.RadioButton
                                            contentDescription = "${item.displayName} 글꼴"
                                        },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                    border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            if (item.isUser) "★ ${item.displayName}" else item.displayName,
                                            fontFamily = previewFontFamily,
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontSize = 13.sp,
                                                lineHeight = 18.sp
                                            ),
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        if (isSelected) {
                                            Text(
                                                "✓",
                                                color = MaterialTheme.colorScheme.primary,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(start = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }
                            for (i in 0 until (columnsCount - rowItems.size)) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }

    val basicControls: @Composable () -> Unit = {
        CaptionVisualGroup("세부 조절") {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Repeated helper row layout for C: label width 52dp, end-aligned 12sp/18sp, gap 8dp, controls Column.weight(1f)
                @Composable
                fun CaptionControlRow(
                    label: String,
                    labelTopPadding: androidx.compose.ui.unit.Dp = 0.dp,
                    controls: @Composable ColumnScope.() -> Unit
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.Start
                    ) {
                        Text(
                            text = label,
                            style = HomeType.Caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                            modifier = Modifier
                                .width(52.dp)
                                .padding(top = labelTopPadding)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            horizontalAlignment = Alignment.Start,
                            content = controls
                        )
                    }
                }

                // 1: 크기
                CaptionControlRow(label = "크기", labelTopPadding = 14.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CaptionSize.entries.forEach { size ->
                            CompactChoice(
                                text = size.title,
                                selected = form.size == size,
                                onClick = { update(form.copy(size = size)) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.fillMaxWidth(),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                // Paired equal width TEXT COLOR and SHADOW columns side by side Row gap 12, same geometry, one C card (no separate big cards).
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    // Left column: 글자 색
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.Start
                    ) {
                        // Header row 48: left '글자 색' HomeType.Compact
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "글자 색",
                                style = HomeType.Compact,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        CaptionVisualPalette(
                            targetLabel = "글자 색",
                            currentColor = form.textColor,
                            enabled = true,
                            swatches = CAPTION_TEXT_COLORS,
                            onPick = { update(form.copy(textColor = it, shadowColor = captionComplementaryShadowColor(it))) }
                        )
                    }

                    // Right column: 그림자
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.Start
                    ) {
                        // Header row 48: right '그림자' same font + existing 48 Switch at Right. Both aligned top baseline.
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "그림자",
                                style = HomeType.Compact,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Switch(
                                checked = form.shadowEnabled,
                                onCheckedChange = { enabled ->
                                    val nextOpacity = if (enabled && form.shadowOpacity <= 0f) 0.5f else form.shadowOpacity
                                    update(form.copy(shadowEnabled = enabled, shadowOpacity = nextOpacity))
                                },
                                modifier = Modifier
                                    .width(48.dp)
                                    .height(32.dp)
                            )
                        }
                        CaptionVisualPalette(
                            targetLabel = "그림자",
                            currentColor = form.shadowColor,
                            enabled = form.shadowEnabled,
                            swatches = CAPTION_SHADOW_COLORS,
                            onPick = { update(form.copy(shadowColor = it)) },
                            bottomContent = {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Slider(
                                        value = form.shadowOpacity.coerceIn(0f, 1f),
                                        onValueChange = { update(form.copy(shadowOpacity = (it * 20).roundToInt() / 20f)) },
                                        valueRange = 0f..1f,
                                        modifier = Modifier
                                            .weight(1f)
                                            .semantics { contentDescription = "그림자 진하기" },
                                        enabled = form.shadowEnabled,
                                    )
                                    Text(
                                        "${(form.shadowOpacity * 100).roundToInt()}%",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                        color = if (form.shadowEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                        modifier = Modifier.align(Alignment.CenterVertically)
                                    )
                                }
                            }
                        )
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.fillMaxWidth(),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                // 3: 줄 간격
                CaptionControlRow(label = "줄 간격", labelTopPadding = 14.dp) {
                    // First row: four equal width presets
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CAPTION_LINE_SPACINGS.forEach { (title, value) ->
                            CompactChoice(
                                text = title,
                                selected = kotlin.math.abs(form.lineSpacing - value) < 0.05f,
                                onClick = { update(form.copy(lineSpacing = value)) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    // Second row: fine adjustments in three equal width slots
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .height(32.dp),
                            shape = EditorButtonShape,
                            border = null,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            onClick = {
                                val next = ((form.lineSpacing - 0.2f) * 10).roundToInt() / 10f
                                update(form.copy(lineSpacing = next.coerceIn(0.5f, 2.0f)))
                            },
                            enabled = form.lineSpacing > 0.55f
                        ) {
                            Text("-0.2", style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp))
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                String.format(Locale.US, "%.1f배", form.lineSpacing),
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            )
                        }
                        OutlinedButton(
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .height(32.dp),
                            shape = EditorButtonShape,
                            border = null,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            onClick = {
                                val next = ((form.lineSpacing + 0.2f) * 10).roundToInt() / 10f
                                update(form.copy(lineSpacing = next.coerceIn(0.5f, 2.0f)))
                            },
                            enabled = form.lineSpacing < 1.95f
                        ) {
                            Text("+0.2", style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp))
                        }
                    }
                }
            }
        }
    }

    return InspectorColumns(
        left = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                contentControls()
            }
        },
        right = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                typographyControls()
                basicControls()
            }
        },
        single = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                contentControls()
                typographyControls()
                basicControls()
            }
        },
        dividerBetween = false
    )
}

private fun captionComplementaryShadowColor(textColor: Int): Int =
    0xFF000000.toInt() or (textColor.inv() and 0x00FFFFFF)

@Composable
private fun CaptionVisualPalette(
    targetLabel: String,
    currentColor: Int,
    enabled: Boolean,
    swatches: List<Pair<String, Int>>,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    bottomContent: (@Composable () -> Unit)? = null,
) {
    val latestOnPick by rememberUpdatedState(onPick)

    var currentH by remember(targetLabel) {
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(currentColor, hsv)
        mutableFloatStateOf(hsv[0])
    }
    var currentS by remember(targetLabel) {
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(currentColor, hsv)
        mutableFloatStateOf(hsv[1])
    }
    var currentV by remember(targetLabel) {
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(currentColor, hsv)
        mutableFloatStateOf(hsv[2])
    }
    var lastEmittedColor by remember(targetLabel) {
        mutableIntStateOf(currentColor or 0xFF000000.toInt())
    }

    LaunchedEffect(currentColor) {
        val normalized = currentColor or 0xFF000000.toInt()
        if (normalized != lastEmittedColor) {
            lastEmittedColor = normalized
            val hsv = FloatArray(3)
            AndroidColor.colorToHSV(normalized, hsv)
            if (hsv[1] > 0.01f || (hsv[2] > 0.01f && hsv[2] < 0.99f && hsv[0] > 0f)) {
                currentH = hsv[0]
            }
            currentS = hsv[1]
            currentV = hsv[2]
        }
    }

    fun emitColor(h: Float, s: Float, v: Float) {
        val clampedS = s.coerceIn(0f, 1f)
        val clampedV = v.coerceIn(0f, 1f)
        val clampedH = (h % 360f + 360f) % 360f
        currentH = clampedH
        currentS = clampedS
        currentV = clampedV
        val nextColor = AndroidColor.HSVToColor(0xFF, floatArrayOf(clampedH, clampedS, clampedV))
        lastEmittedColor = nextColor
        latestOnPick(nextColor)
    }

    fun emitColorFromSwatch(color: Int) {
        val normalized = color or 0xFF000000.toInt()
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(normalized, hsv)
        if (hsv[1] > 0.01f || (hsv[2] > 0.01f && hsv[2] < 0.99f && hsv[0] > 0f)) {
            currentH = hsv[0]
        }
        currentS = hsv[1]
        currentV = hsv[2]
        lastEmittedColor = normalized
        latestOnPick(normalized)
    }

    val pureHueColor = remember(currentH) {
        AndroidColor.HSVToColor(0xFF, floatArrayOf(currentH, 1f, 1f))
    }

    val paletteContentDesc = if (targetLabel.endsWith("색")) "$targetLabel 세부 팔레트" else "$targetLabel 색 세부 팔레트"
    val hueSliderDesc = "$targetLabel 색상 띠"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.38f),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        // 1. Inline SV gradient rectangle (height 88dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(88.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                .semantics {
                    contentDescription = paletteContentDesc
                    customActions = listOf(
                        CustomAccessibilityAction("채도 높이기") {
                            if (enabled) {
                                emitColor(currentH, (currentS + 0.1f).coerceIn(0f, 1f), currentV)
                                true
                            } else false
                        },
                        CustomAccessibilityAction("채도 낮추기") {
                            if (enabled) {
                                emitColor(currentH, (currentS - 0.1f).coerceIn(0f, 1f), currentV)
                                true
                            } else false
                        },
                        CustomAccessibilityAction("밝기 높이기") {
                            if (enabled) {
                                emitColor(currentH, currentS, (currentV + 0.1f).coerceIn(0f, 1f))
                                true
                            } else false
                        },
                        CustomAccessibilityAction("밝기 낮추기") {
                            if (enabled) {
                                emitColor(currentH, currentS, (currentV - 0.1f).coerceIn(0f, 1f))
                                true
                            } else false
                        },
                    )
                }
                .then(
                    if (enabled) {
                        Modifier
                            .pointerInput(targetLabel) {
                                detectTapGestures { offset ->
                                    if (size.width > 0 && size.height > 0) {
                                        val s = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                        val v = (1f - (offset.y / size.height.toFloat())).coerceIn(0f, 1f)
                                        emitColor(currentH, s, v)
                                    }
                                }
                            }
                            .pointerInput(targetLabel) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        if (size.width > 0 && size.height > 0) {
                                            val s = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                            val v = (1f - (offset.y / size.height.toFloat())).coerceIn(0f, 1f)
                                            emitColor(currentH, s, v)
                                        }
                                    },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        if (size.width > 0 && size.height > 0) {
                                            val s = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                                            val v = (1f - (change.position.y / size.height.toFloat())).coerceIn(0f, 1f)
                                            emitColor(currentH, s, v)
                                        }
                                    }
                                )
                            }
                    } else Modifier
                )
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.White, Color(pureHueColor))
                    )
                )
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black)
                    )
                )
                val thumbX = (currentS * size.width).coerceIn(0f, size.width)
                val thumbY = ((1f - currentV) * size.height).coerceIn(0f, size.height)
                val thumbCenter = Offset(thumbX, thumbY)
                drawCircle(
                    color = Color.Black,
                    radius = 8.dp.toPx(),
                    center = thumbCenter,
                    style = Stroke(width = 2.5.dp.toPx())
                )
                drawCircle(
                    color = Color.White,
                    radius = 6.dp.toPx(),
                    center = thumbCenter,
                    style = Stroke(width = 2.dp.toPx())
                )
                drawCircle(
                    color = Color(currentColor or 0xFF000000.toInt()),
                    radius = 4.dp.toPx(),
                    center = thumbCenter
                )
            }
        }

        // 2. Native hue-strip / Slider 48dp touch
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xFFFF0000),
                                Color(0xFFFFFF00),
                                Color(0xFF00FF00),
                                Color(0xFF00FFFF),
                                Color(0xFF0000FF),
                                Color(0xFFFF00FF),
                                Color(0xFFFF0000),
                            )
                        )
                    )
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            )

            Slider(
                value = currentH,
                onValueChange = { nextHue ->
                    var nextS = currentS
                    var nextV = currentV
                    if (nextS <= 0.05f) nextS = 0.8f
                    if (nextV <= 0.05f) nextV = 0.65f
                    emitColor(nextHue, nextS, nextV)
                },
                valueRange = 0f..360f,
                enabled = enabled,
                colors = SliderDefaults.colors(
                    thumbColor = Color(pureHueColor),
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                    disabledThumbColor = Color(pureHueColor).copy(alpha = 0.38f),
                    disabledActiveTrackColor = Color.Transparent,
                    disabledInactiveTrackColor = Color.Transparent,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = hueSliderDesc
                    }
            )
        }

        // 3. Quick swatch grid (3 columns when panel width >= 144dp else 2, equal width 48dp cells, 28dp circle, zero vertical gaps)
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val cols = if (maxWidth >= 144.dp) 3 else 2
            val chunked = swatches.chunked(cols)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                chunked.forEach { rowColors ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        rowColors.forEach { (name, color) ->
                            val selected = (currentColor or 0xFF000000.toInt()) == (color or 0xFF000000.toInt())
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .semantics {
                                        role = Role.RadioButton
                                        this.selected = selected
                                        contentDescription = "$targetLabel $name"
                                    }
                                    .clickable(enabled = enabled) {
                                        emitColorFromSwatch(color)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(Color(color))
                                        .border(
                                            if (selected) 2.5.dp else 1.dp,
                                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                            CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (selected) {
                                        val c = Color(color)
                                        val isBright = (c.red * 0.299f + c.green * 0.587f + c.blue * 0.114f) > 0.55f
                                        Text(
                                            "✓",
                                            color = if (isBright) Color.Black else Color.White,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                        for (i in 0 until (cols - rowColors.size)) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // 4. Optional bottom content (shadow opacity row 48dp)
        bottomContent?.invoke()
    }
}
