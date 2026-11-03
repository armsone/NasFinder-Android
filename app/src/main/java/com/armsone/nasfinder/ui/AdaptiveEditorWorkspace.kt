@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.armsone.nasfinder.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.armsone.nasfinder.model.RemoteConnection
import com.armsone.nasfinder.model.RemoteFileItem
import com.armsone.nasfinder.ui.theme.themePalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt

/** Width tiers of the editing workspace, decided from the actual available width (never the device type). */
private enum class EditorTier { WIDE, MEDIUM, RAIL_ONLY, FOCUSED }

private fun editorTier(width: Dp): EditorTier = when {
    width >= 1280.dp -> EditorTier.WIDE
    width >= 920.dp -> EditorTier.MEDIUM
    width >= 760.dp -> EditorTier.RAIL_ONLY
    else -> EditorTier.FOCUSED
}

// PLAN stays only so a saved older tab restores; it is shown as 클립, which now holds the whole-video plan too.
private enum class InspectorTab(val title: String) { SCENE("클립"), CAPTION("자막"), SOUND("음악"), SCREEN("화면"), PLAN("클립") }

/**
 * Two screens of one project, without visible navigation tabs: editing (the draft is saved from the header) and the
 * made video (rendering progress → whole-video player → 저장·공유). EXPORT opens only from the edit footer's
 * 내보내기 (after the draft save succeeds, or straight to the current video); back always returns to the same editing view.
 */
private enum class EditorPhase { EDIT, EXPORT }

// Very short scenes (e.g. near-duplicate photos at 1% weight) would otherwise all read "0.0초".
private fun seconds1(value: Double): String = if (value > 0.0 && value < 0.095) "%.2f초".format(value) else "%.1f초".format(value)

/**
 * The current-project editing workspace: 64dp rail, Finder panel that adds into THIS project, editor stage with the
 * explicit scene strip, and the 장면/소리/화면 inspector. All edits go through the view model (undo + autosave).
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun AdaptiveEditorWorkspace(
    session: TrailerSession,
    model: NasFinderViewModel,
    onRequestClose: () -> Unit,
    fullscreenPlayback: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
) {
    val appState by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val palette = themePalette()
    val project = session.editor ?: return
    val directoryKey = session.directory.absolutePath

    // Workspace UI state survives the temporary picker, panels and rotation for this project only.
    var selectedSceneKey by rememberSaveable(directoryKey) { mutableStateOf(project.scenes.firstOrNull()?.key) }
    var selectedSceneKeys by rememberSaveable(directoryKey) { mutableStateOf(setOfNotNull(project.scenes.firstOrNull()?.key)) }
    var multiSelectMode by rememberSaveable(directoryKey) { mutableStateOf(false) }
    val effectiveSelectedKeys = if (selectedSceneKeys.isNotEmpty()) selectedSceneKeys else setOfNotNull(selectedSceneKey)
    val multiActive = multiSelectMode || effectiveSelectedKeys.size > 1
    val gridScrollState = rememberLazyGridState()
    var moveTargetDialogKeys by remember { mutableStateOf<Set<Long>?>(null) }
    // Unapplied 자막 form of one scene (preview only until 적용); survives rotation, never moves to another scene.
    var captionDraft by rememberSaveable(directoryKey, stateSaver = CaptionDraftSaver) { mutableStateOf<CaptionDraft?>(null) }
    val coroutineScope = rememberCoroutineScope()
    var finderOpen by rememberSaveable(directoryKey) { mutableStateOf(false) }
    var inspectorTab by rememberSaveable(directoryKey) { mutableStateOf(InspectorTab.SCENE) }
    // Entering a project always starts at 편집; a render finishing never moves the user to another view.
    var phase by rememberSaveable(directoryKey) { mutableStateOf(EditorPhase.EDIT) }
    var showMusicPicker by rememberSaveable(directoryKey) { mutableStateOf(false) }
    // UI-only preview size (1 = default); never part of the project, revision or undo.
    var previewScale by rememberSaveable { mutableFloatStateOf(1f) }
    // UI-only clips per row (phone / two-pane board); never part of the project, revision or undo.
    var phoneColumns by rememberSaveable { mutableIntStateOf(3) }
    var tabletColumns by rememberSaveable { mutableIntStateOf(4) }
    var renameOpen by remember { mutableStateOf(false) }
    var exportSheet by remember { mutableStateOf(false) }
    // Keeps the editing screen's saveable state (e.g. the inspector scroll) while the made video is shown.
    val editStateHolder = rememberSaveableStateHolder()
    // Finder selections are held in the view model so route changes, web roundtrips and minimization preserve them.
    val finderSelection = remember(directoryKey) { model.editorFinderSelection(directoryKey) }

    var knownSceneKeys by remember(directoryKey) { mutableStateOf(project.scenes.map { it.key }) }
    LaunchedEffect(project.scenes.map { it.key }) {
        val keys = project.scenes.map { it.key }
        val keySet = keys.toSet()
        val validSelected = selectedSceneKeys.filter { it in keySet }.toSet()
        if (validSelected != selectedSceneKeys) {
            selectedSceneKeys = validSelected
        }
        if (selectedSceneKey == null || project.scene(selectedSceneKey!!) == null) {
            // A removed scene hands the selection to the scene that took its place, not back to scene 1.
            val oldIndex = knownSceneKeys.indexOf(selectedSceneKey).takeIf { it >= 0 }
            selectedSceneKey = if (oldIndex == null) keys.firstOrNull() else (keys.getOrNull(oldIndex) ?: keys.lastOrNull())
        }
        if (selectedSceneKeys.isEmpty() && selectedSceneKey != null) {
            selectedSceneKeys = setOf(selectedSceneKey!!)
        }
        knownSceneKeys = keys
    }
    val selectedScene = selectedSceneKey?.let(project::scene)
    val selectedSource = selectedScene?.let { scene -> session.sources.firstOrNull { it.id == scene.sourceId } }
    val previewCaption = captionDraft?.takeIf { it.sceneKey == selectedScene?.key }?.caption?.normalizedOrNull()
        ?: selectedScene?.caption?.normalizedOrNull().takeIf { captionDraft?.sceneKey != selectedScene?.key }
    val output = session.output?.takeIf { it.isFile && it.length() > 0L }
    val outputCurrent = output != null && session.outputRevision == session.revision
    val rendering = session.export?.running == true
    val saveReady = outputCurrent && !rendering && !session.busy
    val fullscreenAllowed = phase == EditorPhase.EXPORT && outputCurrent && !rendering
    LaunchedEffect(fullscreenPlayback, fullscreenAllowed) { if (fullscreenPlayback && !fullscreenAllowed) onFullscreenChange(false) }
    // A cancelled render (or one outdated by edits) leaves nothing to show: the made-video screen returns to 편집.
    val resultEmpty = !rendering && session.export?.error == null && !outputCurrent
    LaunchedEffect(phase, resultEmpty) { if (phase == EditorPhase.EXPORT && resultEmpty) phase = EditorPhase.EDIT }
    LaunchedEffect(saveReady) { if (!saveReady) exportSheet = false }
    // An unapplied 자막 form (its scene changed, or deleted) is never carried silently into the whole video.
    val captionPending = captionDraft?.let { draft ->
        val owner = project.scene(draft.sceneKey)
        owner == null || draft.caption.normalizedOrNull() != owner.caption?.normalizedOrNull()
    } == true
    val usedSourceIds = project.scenes.map { it.sourceId }.toSet()
    val usedOrigins = session.sources.filter { it.id in usedSourceIds }.mapNotNull { it.originKey }.toSet()
    val originToSource = session.sources.filter { it.originKey != null }.associateBy { it.originKey!! }

    fun shareOutput() {
        // A render of an older edit is never shared as if it were the current project.
        if (output == null || !outputCurrent) return
        // The model stages a copy in the share-only cache root off the main thread and reports failures.
        model.shareTrailerOutput()
    }

    BackHandler(enabled = !fullscreenPlayback) {
        when {
            finderOpen -> finderOpen = false
            phase != EditorPhase.EDIT -> phase = EditorPhase.EDIT
            else -> onRequestClose()
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || event.nativeKeyEvent.repeatCount != 0) return@onPreviewKeyEvent false
                when {
                    // Undo/redo belong to 편집; the made-video screen never changes the project underneath.
                    phase == EditorPhase.EDIT && event.isCtrlPressed && event.key == Key.Z && !event.isShiftPressed -> { model.editorUndo(); true }
                    phase == EditorPhase.EDIT && event.isCtrlPressed && (event.key == Key.Y || (event.isShiftPressed && event.key == Key.Z)) -> { model.editorRedo(); true }
                    (event.key == Key.F11 || (event.isCtrlPressed && event.isShiftPressed && event.key == Key.F)) && fullscreenAllowed -> {
                        onFullscreenChange(!fullscreenPlayback); true
                    }
                    else -> false
                }
            },
    ) {
        val workspaceWidth = maxWidth
        val tier = editorTier(maxWidth)
        // Two panes only when both stay readable (board ≥ ~500dp, preview+inspector ≥ ~250dp, height ≥ 480dp);
        // a phone in landscape or any short/narrow window uses the one-screen phone layout instead.
        val isLandscape = maxWidth > maxHeight && maxWidth >= 840.dp && maxHeight >= 480.dp
        val isPhone = !isLandscape && (tier == EditorTier.FOCUSED || maxHeight < 560.dp)
        val panelFinderInline = finderOpen && !isPhone && (tier == EditorTier.WIDE || tier == EditorTier.MEDIUM)
        // While typing, stages keep only their (smaller) picture so the inspector field and 적용/취소 stay reachable.
        val imeVisible = WindowInsets.isImeVisible

        // Sends a 자막 form that could not be committed back to its own 적용/취소 (no silent apply or discard).
        fun showPendingCaption() {
            phase = EditorPhase.EDIT
            inspectorTab = InspectorTab.CAPTION
            val ownerKey = captionDraft?.sceneKey?.takeIf { project.scene(it) != null }
            if (ownerKey != null && ownerKey != selectedSceneKey) {
                selectedSceneKey = ownerKey
                selectedSceneKeys = setOf(ownerKey)
            }
        }

        // 편집본 저장/내보내기 include the pending 자막 form: it is written in its chosen scope as the one undoable
        // caption edit before the action reads the live project. A deleted owner or a refused edit keeps the form.
        fun commitPendingCaption(): Boolean {
            val draft = captionDraft
            if (draft == null || !captionPending) return true
            val live = model.state.value.trailer?.editor
            if (live == null || !model.commitSceneCaption(draft.commitKeys(live), draft.caption)) {
                showPendingCaption()
                return false
            }
            captionDraft = null
            return true
        }

        // 내보내기 (new video): the model saves this exact draft first and starts the render only after that save succeeds;
        // only then does the made-video screen open (a failed save or a music prompt keeps 편집 in place).
        fun startVideo() {
            if (rendering || !commitPendingCaption()) return
            finderOpen = false
            model.exportEditorProject(onStarted = { phase = EditorPhase.EXPORT })
        }

        // Shows the running render or the current video; never an older video while a 자막 form is still pending.
        fun openResult() {
            if (!rendering && (!outputCurrent || captionPending)) return
            finderOpen = false
            phase = EditorPhase.EXPORT
        }

        // Explicit 편집본 저장: writes the project now (no render needed); the header shows the real saved/failed result.
        fun requestDraftSave() {
            if (!commitPendingCaption()) return
            model.retryEditorAutosave()
        }

        // The scene board's lazy grid: on a phone its [page] (preview/tabs group + the selected tab's body) comes first,
        // then only on 클립 the board toolbar and clips; a wide page's right column passes no page. 영상 만들기 replaces the area.
        @Composable
        fun Board(fixedColumns: Int, maxColumns: Int, onColumnsChange: (Int) -> Unit, thumbnailAspect: Float, compactCells: Boolean, modifier: Modifier, page: (@Composable () -> Unit)?) {
            SceneBoard(
                session = session,
                selectedKey = selectedSceneKey,
                selectedKeys = selectedSceneKeys,
                multiSelectMode = multiSelectMode,
                onToggleMultiSelectMode = {
                    if (multiActive) {
                        multiSelectMode = false
                        if (effectiveSelectedKeys.size > 1) selectedSceneKeys = emptySet()
                    } else {
                        multiSelectMode = true
                    }
                },
                onSelectKey = { key ->
                    selectedSceneKey = key
                    selectedSceneKeys = setOf(key)
                },
                onToggleSelectKey = { key ->
                    selectedSceneKeys = if (key in selectedSceneKeys) selectedSceneKeys - key else selectedSceneKeys + key
                    if (key in selectedSceneKeys) selectedSceneKey = key
                },
                onSelectAll = { selectedSceneKeys = project.scenes.map { it.key }.toSet() },
                onClearSelection = { selectedSceneKeys = emptySet() },
                onMoveTo = { keys, targetIdx -> model.editorMoveScenesTo(keys, targetIdx) },
                onMoveStep ={ key, delta -> model.editorMoveScene(key, delta) },
                onDuplicate = { keys ->
                    if (keys.size == 1) {
                        model.editorDuplicateScene(keys.first())?.let { newKey ->
                            selectedSceneKey = newKey
                            selectedSceneKeys = setOf(newKey)
                        }
                    } else {
                        val newKeys = model.editorDuplicateScenes(keys)
                        if (newKeys.isNotEmpty()) {
                            selectedSceneKeys = newKeys.toSet()
                            selectedSceneKey = newKeys.first()
                        }
                    }
                },
                onRemove = { keys ->
                    if (keys.size == 1) model.editorRemoveScene(keys.first())
                    else model.editorRemoveScenes(keys)
                },
                onMarkImportant = { keys, important ->
                    model.editorSetPhotosImportant(keys, important)
                },
                onAddMaterial = { finderOpen = true },
                gridState = gridScrollState,
                onRequestMoveDialog = { keys -> moveTargetDialogKeys = keys },
                thumbnailAspect = thumbnailAspect,
                compactCells = compactCells,
                fixedColumns = fixedColumns,
                maxColumns = maxColumns,
                onColumnsChange = onColumnsChange,
                showClips = inspectorTab == InspectorTab.SCENE || inspectorTab == InspectorTab.PLAN,
                showToolbar = false,
                page = page,
                modifier = modifier,
                model = model,
            )
        }

        // Most clips per row that still keep every cell at least a 48dp touch target (8dp gaps and edges).
        fun widthSafeColumns(boardWidth: Dp, cap: Int): Int = (((boardWidth - 8.dp) / 56.dp).toInt()).coerceIn(2, cap)

        @Composable
        fun Stage(paneHeight: Dp, baseShare: Float, baseMax: Dp, maxShare: Float, horizontalPadding: Dp) {
            // paneHeight is the editing area left above the keyboard (IME insets are in the Row's padding).
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val paneWidth = (maxWidth - horizontalPadding * 2).coerceAtLeast(1.dp)
                val safePaneHeight = paneHeight.coerceAtLeast(72.dp)
                val cap = (safePaneHeight * baseShare).coerceIn(72.dp, baseMax)
                EditorStage(
                    session = session,
                    scene = selectedScene,
                    source = selectedSource,
                    caption = previewCaption,
                    visualHeight = scaledStageHeight(session, paneWidth, cap, safePaneHeight * maxShare, previewScale, imeVisible),
                    baseVisualHeight = stageVisualHeight(session, paneWidth, cap),
                    maxVisualHeight = scaledStageHeight(session, paneWidth, cap, safePaneHeight * maxShare, 2f, imeVisible),
                    previewScale = previewScale,
                    onPreviewScaleChange = { previewScale = it },
                    showControls = !imeVisible,
                    inlinePlaybackAllowed = !fullscreenPlayback,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding, vertical = 4.dp),
                    belowPicture = { dragModifier, resizeActions ->
                        InspectorTabs(inspectorTab, onTab = { inspectorTab = it }, resizeActions = resizeActions, modifier = dragModifier)
                    },
                )
            }
        }

        @Composable
        fun inspectorColumns(): InspectorColumns =
            editorInspectorColumns(
                session = session,
                scene = selectedScene,
                source = selectedSource,
                selectedKeys = selectedSceneKeys,
                captionDraft = captionDraft,
                onCaptionDraftChange = { captionDraft = it },
                tab = inspectorTab,
                model = model,
                onSelectScene = { key ->
                    selectedSceneKey = key
                    selectedSceneKeys = setOf(key)
                },
                onOpenMusicPicker = { showMusicPicker = true },
            )

        // The made video of the current revision: while rendering, the snapshot scene the progress has reached (still
        // frames, no player, no sound) and 취소; on failure 다시 시도; when ready the whole-video player and one
        // 저장·공유 (폰하드/공유 sheet). An older video is never shown, and 저장됨 appears only after the actual
        // 폰하드 save of the current video. Nothing here closes the project.
        @Composable
        fun ResultPhase(modifier: Modifier) {
            val savedCurrent = outputCurrent && session.saved
            val export = session.export
            Column(modifier) {
                ExportStatusBar(session)
                if (rendering && export != null) {
                    RenderScenePreview(session, export, Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp))
                    PhaseActionRow { PhasePrimaryButton("취소", enabled = true, modifier = Modifier.weight(1f), onClick = model::cancelEditorExport) }
                } else if (export?.error != null) {
                    Spacer(Modifier.weight(1f))
                    PhaseActionRow { PhasePrimaryButton("다시 시도", enabled = !session.busy, modifier = Modifier.weight(1f), onClick = ::startVideo) }
                } else if (outputCurrent && output != null) {
                    // Left on exit (the player is released); fullscreen pauses it in the same frame, nothing autoplays.
                    Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                        OutputPlayer(output, !fullscreenPlayback, Modifier.fillMaxSize())
                        IconButton(
                            onClick = { onFullscreenChange(true) },
                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(IconHitTarget).clip(EditorButtonShape).background(Color.Black.copy(alpha = .45f)),
                        ) { Icon(Icons.Default.Fullscreen, "전체화면", Modifier.size(24.dp), tint = Color.White) }
                    }
                    PhaseActionRow {
                        if (savedCurrent) {
                            Row(Modifier.weight(1f).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.CheckCircle, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                                Text("폰하드에 저장됨", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        PhasePrimaryButton(
                            if (session.busy && session.status == PHONEHARD_SAVING_STATUS) "폰하드에 저장 중…" else "저장·공유",
                            enabled = saveReady,
                            modifier = if (savedCurrent) Modifier.widthIn(min = 140.dp) else Modifier.weight(1f),
                            onClick = { exportSheet = true },
                        )
                    }
                }
            }
        }

        // The editing screen's primary actions, fixed below the scrolling page:
        // Left: SaveDraft (quieter outlined), Right: "저장 후 내보내기" (primary) or running render/open current.
        @Composable
        fun EditFooter() {
            val export = session.export
            val saveButtonLabel = when (session.saveState) {
                EditorSaveState.SAVING -> "저장 중…"
                EditorSaveState.FAILED -> "저장 다시 시도"
                EditorSaveState.SAVED, EditorSaveState.IDLE -> "편집본 저장"
            }
            PhaseActionRow {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!rendering && export?.error != null) {
                        Text(export.error, style = MaterialTheme.typography.labelMedium, color = palette.danger, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!isPhone) {
                            Spacer(Modifier.weight(1f))
                        }
                        val buttonModifier = if (isPhone) {
                            Modifier.weight(1f)
                        } else {
                            Modifier.widthIn(min = 120.dp, max = 190.dp)
                        }
                        OutlinedButton(
                            shape = EditorButtonShape,
                            onClick = ::requestDraftSave,
                            enabled = session.saveState != EditorSaveState.SAVING && !session.busy,
                            modifier = buttonModifier.heightIn(min = 48.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(
                                saveButtonLabel,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Medium,
                                color = if (session.saveState == EditorSaveState.FAILED) palette.danger else Color.Unspecified,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        when {
                            rendering && export?.status == EDITOR_EXPORT_SAVING_STATUS ->
                                PhasePrimaryButton("편집본 저장 중…", enabled = false, modifier = buttonModifier, onClick = {})
                            rendering -> {
                                val percent = export?.progress?.let { " ${(it * 100).roundToInt().coerceIn(0, 100)}%" } ?: ""
                                PhasePrimaryButton("영상 만드는 중$percent · 보기", enabled = true, modifier = buttonModifier, onClick = ::openResult)
                            }
                            // 내보내기 opens the unchanged current video; a pending 자막 form makes it outdated, so 내보내기
                            // then commits the form, saves the draft and renders anew.
                            outputCurrent && !captionPending -> PhasePrimaryButton("내보내기", enabled = true, modifier = buttonModifier, onClick = ::openResult)
                            else -> PhasePrimaryButton(
                                "저장 후 내보내기",
                                enabled = !session.busy && project.scenes.isNotEmpty(),
                                modifier = buttonModifier,
                                onClick = ::startVideo,
                            )
                        }
                    }
                }
            }
        }

        // Insets are consumed exactly once here: rail, header, stage controls and footer stay in the safe area,
        // and consumeWindowInsets prevents inner focus/scroll containers from double-panning above the keyboard.
        Row(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .consumeWindowInsets(WindowInsets.safeDrawing),
        ) {
            if (tier != EditorTier.FOCUSED && !isPhone) {
                PorcelainNavigationSidebar(appState, model, workspaceWidth, editingVideo = true)
                VerticalHairline()
            }
            if (panelFinderInline) {
                EditorFinderPanel(
                    session = session,
                    appState = appState,
                    model = model,
                    selection = finderSelection,
                    usedOrigins = usedOrigins,
                    originToSource = originToSource,
                    selectedSceneKey = selectedSceneKey,
                    onUsedAgain = { key -> selectedSceneKey = key },
                    onClose = { finderOpen = false },
                    modifier = Modifier.width(if (tier == EditorTier.WIDE) 340.dp else 300.dp).fillMaxHeight(),
                )
                VerticalHairline()
            }
            // One header across every width (edit-only controls hidden on the made video); below it only the current screen.
            Column(Modifier.weight(1f).fillMaxHeight()) {
                EditorHeader(
                    session = session,
                    editing = phase == EditorPhase.EDIT,
                    onClose = { if (phase == EditorPhase.EDIT) onRequestClose() else { phase = EditorPhase.EDIT } },
                    onRename = { renameOpen = true },
                    onUndo = model::editorUndo,
                    onRedo = model::editorRedo,
                )
                SessionStatusLine(session)
                when (phase) {
                    EditorPhase.EXPORT -> ResultPhase(Modifier.weight(1f).fillMaxWidth())
                    EditorPhase.EDIT -> editStateHolder.SaveableStateProvider(directoryKey) {
                        // Phone: one page scroll (the board's lazy grid; its 8dp edge padding is part of the page) —
                        // preview with the four tabs right under it, then the selected tab's body, then on 클립 only the
                        // board toolbar and clips (2..6 per row). Expanded portrait and landscape (≥ 640dp): two bounded
                        // columns that scroll independently — left the preview/tabs and the selected clip's group, right
                        // the related whole-video/look group, or on 클립 the board alone (its toolbar and clips in its own
                        // lazy grid). A clip tap only selects it.
                        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                            val paneHeight = maxHeight
                            val twoColumns = !isPhone && maxWidth >= 640.dp
                            // On a wide page the board fills the right column only.
                            val boardWidth = if (twoColumns) (maxWidth - 4.dp) / 2 else maxWidth
                            val maxColumns = widthSafeColumns(boardWidth, 6)
                            val columns = (if (isPhone) phoneColumns else tabletColumns).coerceIn(2, maxColumns)
                            // Phone: wider thumbnails at 2 columns, square once cells get narrow. Tablet: shorter/narrower aspect ~1.55.
                            val thumbAspect = if (!isPhone) 1.55f else when (columns) { 2 -> 2.2f; 3 -> 1.9f; 4 -> 1.5f; else -> 1f }
                            val compactCells = (boardWidth - 16.dp - 8.dp * (columns - 1)) / columns < 80.dp
                            // The tab body is built once, whatever the arrangement; its groups are only placed, never composed twice.
                            val body = inspectorColumns()
                            @Composable
                            fun PreviewAndTabs() {
                                when {
                                    isPhone -> Stage(paneHeight = paneHeight, baseShare = 0.17f, baseMax = 120.dp, maxShare = if (imeVisible) 0.18f else 0.30f, horizontalPadding = 4.dp)
                                    isLandscape -> Stage(paneHeight = paneHeight, baseShare = 0.20f, baseMax = 150.dp, maxShare = 0.42f, horizontalPadding = 0.dp)
                                    else -> Stage(paneHeight = paneHeight, baseShare = 0.165f, baseMax = 140.dp, maxShare = 0.30f, horizontalPadding = 4.dp)
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
                            }
                            if (twoColumns) {
                                val leftScroll = rememberScrollState()
                                val rightScroll = rememberScrollState()
                                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(leftScroll).padding(horizontal = 8.dp, vertical = 6.dp)) {
                                        PreviewAndTabs()
                                        if (inspectorTab == InspectorTab.SCENE || inspectorTab == InspectorTab.PLAN) {
                                            EditorSettingsPanel(
                                                session = session,
                                                selectedScene = selectedScene,
                                                selectedSource = selectedSource,
                                                selectedKeys = selectedSceneKeys,
                                                multiSelectMode = multiSelectMode,
                                                onToggleMultiSelectMode = {
                                                    if (multiActive) {
                                                        multiSelectMode = false
                                                        if (effectiveSelectedKeys.size > 1) selectedSceneKeys = emptySet()
                                                    } else {
                                                        multiSelectMode = true
                                                    }
                                                },
                                                onSelectAll = { selectedSceneKeys = project.scenes.map { it.key }.toSet() },
                                                onClearSelection = { selectedSceneKeys = emptySet() },
                                                onMarkImportant = { keys, important -> model.editorSetPhotosImportant(keys, important) },
                                                onMove = { keys -> moveTargetDialogKeys = keys },
                                                onDuplicate = { keys ->
                                                    if (keys.size == 1) {
                                                        model.editorDuplicateScene(keys.first())?.let { newKey ->
                                                            selectedSceneKey = newKey
                                                            selectedSceneKeys = setOf(newKey)
                                                        }
                                                    } else {
                                                        val newKeys = model.editorDuplicateScenes(keys)
                                                        if (newKeys.isNotEmpty()) {
                                                            selectedSceneKeys = newKeys.toSet()
                                                            selectedSceneKey = newKeys.first()
                                                        }
                                                    }
                                                },
                                                onRemove = { keys ->
                                                    if (keys.size == 1) model.editorRemoveScene(keys.first())
                                                    else model.editorRemoveScenes(keys)
                                                },
                                                fixedColumns = columns,
                                                maxColumns = maxColumns,
                                                onColumnsChange = { tabletColumns = it },
                                                model = model,
                                            )
                                        } else {
                                            InspectorBody(Modifier.fillMaxWidth()) { body.left() }
                                        }
                                    }
                                    if (inspectorTab == InspectorTab.SCENE || inspectorTab == InspectorTab.PLAN) {
                                        Board(
                                            fixedColumns = columns,
                                            maxColumns = maxColumns,
                                            onColumnsChange = { tabletColumns = it },
                                            thumbnailAspect = thumbAspect,
                                            compactCells = compactCells,
                                            modifier = Modifier.weight(1f).fillMaxHeight(),
                                            page = null,
                                        )
                                    } else {
                                        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rightScroll).padding(horizontal = 8.dp, vertical = 6.dp)) {
                                            InspectorBody(Modifier.fillMaxWidth()) { body.right?.invoke() }
                                        }
                                    }
                                }
                            } else {
                                if (inspectorTab == InspectorTab.SCENE || inspectorTab == InspectorTab.PLAN) {
                                    var previewHeightPx by remember { mutableFloatStateOf(0f) }
                                    var previewOffsetPx by rememberSaveable(directoryKey) { mutableFloatStateOf(0f) }
                                    val nestedScrollConnection = remember(previewHeightPx) {
                                        object : NestedScrollConnection {
                                            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                                                val delta = available.y
                                                return if (delta < 0f && previewHeightPx > 0f && previewOffsetPx > -previewHeightPx) {
                                                    val newOffset = (previewOffsetPx + delta).coerceIn(-previewHeightPx, 0f)
                                                    val consumed = newOffset - previewOffsetPx
                                                    previewOffsetPx = newOffset
                                                    Offset(0f, consumed)
                                                } else {
                                                    Offset.Zero
                                                }
                                            }

                                            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                                                val delta = available.y
                                                return if (delta > 0f && previewHeightPx > 0f && previewOffsetPx < 0f) {
                                                    val newOffset = (previewOffsetPx + delta).coerceIn(-previewHeightPx, 0f)
                                                    val consumed = newOffset - previewOffsetPx
                                                    previewOffsetPx = newOffset
                                                    Offset(0f, consumed)
                                                } else {
                                                    Offset.Zero
                                                }
                                            }
                                        }
                                    }

                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .nestedScroll(nestedScrollConnection),
                                    ) {
                                        val collapseDp = if (previewHeightPx > 0f) {
                                            with(LocalDensity.current) { (previewHeightPx + previewOffsetPx).coerceAtLeast(0f).toDp() }
                                        } else null

                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .then(if (collapseDp != null) Modifier.height(collapseDp) else Modifier)
                                                .clipToBounds(),
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .wrapContentHeight(unbounded = true)
                                                    .onGloballyPositioned { coords ->
                                                        val h = coords.size.height.toFloat()
                                                        if (h > 0f && h != previewHeightPx) {
                                                            previewHeightPx = h
                                                            previewOffsetPx = previewOffsetPx.coerceIn(-h, 0f)
                                                        }
                                                    },
                                            ) {
                                                PreviewAndTabs()
                                            }
                                        }

                                        EditorSettingsPanel(
                                            session = session,
                                            selectedScene = selectedScene,
                                            selectedSource = selectedSource,
                                            selectedKeys = selectedSceneKeys,
                                            multiSelectMode = multiSelectMode,
                                            onToggleMultiSelectMode = {
                                                if (multiActive) {
                                                    multiSelectMode = false
                                                    if (effectiveSelectedKeys.size > 1) selectedSceneKeys = emptySet()
                                                } else {
                                                    multiSelectMode = true
                                                }
                                            },
                                            onSelectAll = { selectedSceneKeys = project.scenes.map { it.key }.toSet() },
                                            onClearSelection = { selectedSceneKeys = emptySet() },
                                            onMarkImportant = { keys, important -> model.editorSetPhotosImportant(keys, important) },
                                            onMove = { keys -> moveTargetDialogKeys = keys },
                                            onDuplicate = { keys ->
                                                if (keys.size == 1) {
                                                    model.editorDuplicateScene(keys.first())?.let { newKey ->
                                                        selectedSceneKey = newKey
                                                        selectedSceneKeys = setOf(newKey)
                                                    }
                                                } else {
                                                    val newKeys = model.editorDuplicateScenes(keys)
                                                    if (newKeys.isNotEmpty()) {
                                                        selectedSceneKeys = newKeys.toSet()
                                                        selectedSceneKey = newKeys.first()
                                                    }
                                                }
                                            },
                                            onRemove = { keys ->
                                                if (keys.size == 1) model.editorRemoveScene(keys.first())
                                                else model.editorRemoveScenes(keys)
                                            },
                                            fixedColumns = columns,
                                            maxColumns = maxColumns,
                                            onColumnsChange = { phoneColumns = it },
                                            model = model,
                                        )

                                        Board(
                                            fixedColumns = columns,
                                            maxColumns = maxColumns,
                                            onColumnsChange = { phoneColumns = it },
                                            thumbnailAspect = thumbAspect,
                                            compactCells = compactCells,
                                            modifier = Modifier.weight(1f).fillMaxWidth(),
                                            page = null,
                                        )
                                    }
                                } else {
                                    Column(
                                        Modifier
                                            .fillMaxSize()
                                            .verticalScroll(rememberScrollState()),
                                    ) {
                                        PreviewAndTabs()
                                        InspectorBody(Modifier.fillMaxWidth()) {
                                            val single = body.single
                                            if (single != null) {
                                                single()
                                            } else {
                                                body.left()
                                                body.right?.let { right ->
                                                    if (body.dividerBetween) HorizontalDivider()
                                                    right()
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        // The footer sits outside the page scroll; it yields to the keyboard while typing.
                        if (!imeVisible) EditFooter()
                    }
                }
            }
        }

        // Temporary Finder: beside the rail at 760..919, full-screen picker below 760. The editor stays composed
        // underneath, so the project, selected scene, playback position and Finder selections are preserved.
        val finderFullScreen = tier == EditorTier.FOCUSED || isPhone
        if (finderOpen && !panelFinderInline) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .32f)).clickable(onClick = { finderOpen = false }))
            Surface(
                modifier = if (finderFullScreen) Modifier.fillMaxSize() else Modifier.fillMaxHeight().width(380.dp).padding(start = 65.dp),
                color = MaterialTheme.colorScheme.background,
                tonalElevation = 4.dp,
                shadowElevation = 8.dp,
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .consumeWindowInsets(WindowInsets.safeDrawing),
                ) {
                    EditorFinderPanel(
                        session = session,
                        appState = appState,
                        model = model,
                        selection = finderSelection,
                        usedOrigins = usedOrigins,
                        originToSource = originToSource,
                        selectedSceneKey = selectedSceneKey,
                        onUsedAgain = { key -> selectedSceneKey = key },
                        onClose = { finderOpen = false },
                        onAdded = { if (finderFullScreen) finderOpen = false },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    if (fullscreenPlayback && fullscreenAllowed && output != null) {
        // Only the current whole video of 영상 만들기 goes full-bleed; the existing viewer keeps its own safe controls.
        val item = remember(output) { RemoteFileItem(output.name, "${session.displayTitle}.mp4", output.absolutePath, false, output.length(), mimeType = "video/mp4") }
        val preview = remember(output) { ImagePreviewState(null, listOf(item), 0, BuiltInPreviewKind.VIDEO, cachedFile = output, localFiles = listOf(output)) }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            BuiltInPreviewScreen(
                preview = preview,
                onClose = { onFullscreenChange(false) },
                onShare = ::shareOutput,
                onPrevious = {}, onNext = {}, onSelectIndex = {},
                onPreviousPdfPage = {}, onNextPdfPage = {},
                onPlaybackFailure = null,
                showShare = false,
                accessoryControls = {
                    if (outputCurrent) IconButton(onClick = ::shareOutput) { Icon(Icons.Default.Share, "공유", tint = Color.White) }
                    IconButton(onClick = { onFullscreenChange(false) }) { Icon(Icons.Default.FullscreenExit, "전체화면 종료", tint = Color.White) }
                },
            )
        }
    }

    if (renameOpen) {
        var title by remember { mutableStateOf(session.title ?: session.displayTitle) }
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            title = { Text("프로젝트 이름") },
            text = { OutlinedTextField(title, { title = it }, singleLine = true, label = { Text("제목") }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(shape = EditorButtonShape, onClick = { model.editorRename(title); renameOpen = false }) { Text("확인") } },
            dismissButton = { TextButton(shape = EditorButtonShape, onClick = { renameOpen = false }) { Text("취소") } },
        )
    }

    if (moveTargetDialogKeys != null) {
        val targetKeys = moveTargetDialogKeys!!
        val totalScenes = project.scenes.size
        val selectedCount = targetKeys.size
        val maxAllowed = totalScenes - selectedCount + 1
        var inputStr by remember(targetKeys) { mutableStateOf("") }
        val parsed = inputStr.trim().toIntOrNull()
        val isError = inputStr.isNotBlank() && (parsed == null || parsed !in 1..maxAllowed)
        val isValid = parsed != null && parsed in 1..maxAllowed

        AlertDialog(
            onDismissRequest = { moveTargetDialogKeys = null },
            title = {
                Text(if (selectedCount == 1) "클립 위치 이동" else "${selectedCount}개 클립 위치 이동")
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (selectedCount == 1) {
                            val currentPos = project.scenes.indexOfFirst { it.key in targetKeys } + 1
                            "현재 ${currentPos}번 클립입니다.\n이동할 새 위치 번호를 입력하세요 (1 ~ ${totalScenes}번)."
                        } else {
                            "선택한 ${selectedCount}개 클립의 원래 순서를 유지하며 연속 이동합니다.\n첫 번째 선택 클립이 위치할 새 번호를 입력하세요 (1 ~ ${maxAllowed}번)."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = inputStr,
                        onValueChange = { inputStr = it.filter { ch -> ch.isDigit() } },
                        label = { Text("새 위치 번호 (1 ~ $maxAllowed)") },
                        placeholder = { Text("예: 3") },
                        singleLine = true,
                        isError = isError,
                        supportingText = {
                            if (isError) {
                                Text("1부터 ${maxAllowed}까지의 숫자를 입력해 주세요.", color = MaterialTheme.colorScheme.error)
                            } else {
                                Text("1 ~ ${maxAllowed}번 중 입력")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    shape = EditorButtonShape,
                    onClick = {
                        val dest1Based = parsed ?: return@Button
                        model.editorMoveScenesTo(targetKeys, dest1Based - 1)
                        moveTargetDialogKeys = null
                        coroutineScope.launch {
                            // Items before the clips: page (phone only) and toolbar; the 재료 추가 tile follows them.
                            val leadingItems = (gridScrollState.layoutInfo.totalItemsCount - project.scenes.size - 1).coerceAtLeast(0)
                            gridScrollState.animateScrollToItem((dest1Based - 1).coerceAtLeast(0) + leadingItems)
                        }
                    },
                    enabled = isValid,
                ) {
                    Text("이동")
                }
            },
            dismissButton = {
                TextButton(shape = EditorButtonShape, onClick = { moveTargetDialogKeys = null }) {
                    Text("취소")
                }
            },
        )
    }

    if (session.musicExportPrompt && session.musicTrack == null && session.unavailableMusic != null) {
        // 음악 없이 만들기 started from 편집 opens the made-video screen once its draft save succeeds, like 내보내기.
        MissingMusicExportDialog(session, model, onStarted = { phase = EditorPhase.EXPORT })
    }

    if (showMusicPicker) {
        UnifiedMusicPickerDialog(
            model = model,
            currentTrack = session.musicTrack,
            onDismiss = { showMusicPicker = false },
            onSelectTrack = { selected ->
                showMusicPicker = false
                selectReplacementMusic(session, selected, model)
            },
        )
    }

    // 저장·공유: only the existing two actions for the current video; dismissed by back or the scrim. 폰하드 shows
    // 저장됨 only after its actual write, 공유 only hands the file to the chooser, and nothing closes the project.
    if (exportSheet && output != null && outputCurrent) {
        ModalBottomSheet(onDismissRequest = { exportSheet = false }) {
            Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                val phoneHardSaved = session.saved
                ListItem(
                    headlineContent = { Text(if (phoneHardSaved) "폰하드에 저장됨" else "폰하드에 저장") },
                    leadingContent = { Icon(if (phoneHardSaved) Icons.Default.CheckCircle else Icons.Default.Download, null) },
                    modifier = Modifier.clickable(enabled = saveReady && !phoneHardSaved) {
                        exportSheet = false
                        model.saveTrailer()
                    },
                )
                ListItem(
                    headlineContent = { Text("공유") },
                    leadingContent = { Icon(Icons.Default.Share, null) },
                    modifier = Modifier.clickable(enabled = saveReady) {
                        exportSheet = false
                        shareOutput()
                    },
                )
            }
        }
    }
}

@Composable
private fun VerticalHairline() {
    Box(Modifier.fillMaxHeight().width(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f)))
}

internal val EditorButtonShape = RoundedCornerShape(10.dp)

@Composable
private fun EditorHeader(
    session: TrailerSession,
    editing: Boolean,
    onClose: () -> Unit,
    onRename: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
) {
    val palette = themePalette()
    val project = session.editor
    val saveLabel = when (session.saveState) {
        EditorSaveState.SAVING -> "편집본 저장 중…"
        EditorSaveState.SAVED -> "편집본 저장됨"
        EditorSaveState.FAILED -> "편집본 저장 실패"
        EditorSaveState.IDLE -> ""
    }
    val counts = "재료 ${session.sources.size}개 · 클립 ${project?.scenes?.size ?: 0}개 · ${trailerLengthTitle(session.totalDuration)}"
    val metadataText = listOf(counts, saveLabel, if (project?.autoPlan == false) "직접 편집" else "자동 추천").filter { it.isNotBlank() }.joinToString(" · ")

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val isCompactHeader = maxWidth < 480.dp
        if (isCompactHeader && editing) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose, modifier = Modifier.size(IconHitTarget)) {
                        PackIcon(PackGlyph.BACK, "편집 닫기 (편집본 자동 저장)", Modifier.size(ToolbarActionIcon))
                    }
                    Text(
                        session.displayTitle,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 17.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).clickable(onClickLabel = "프로젝트 이름 바꾸기", onClick = onRename).padding(horizontal = 4.dp),
                    )
                    IconButton(onClick = onUndo, enabled = session.canUndo && !session.busy, modifier = Modifier.size(IconHitTarget)) {
                        Icon(Icons.AutoMirrored.Filled.Undo, "실행 취소", Modifier.size(ToolbarActionIcon))
                    }
                    IconButton(onClick = onRedo, enabled = session.canRedo && !session.busy, modifier = Modifier.size(IconHitTarget)) {
                        Icon(Icons.AutoMirrored.Filled.Redo, "다시 실행", Modifier.size(ToolbarActionIcon))
                    }
                }
                Text(
                    metadataText,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (session.saveState == EditorSaveState.FAILED) palette.danger else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 2.dp),
                )
            }
        } else {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose, modifier = Modifier.size(IconHitTarget)) {
                    PackIcon(PackGlyph.BACK, if (editing) "편집 닫기 (편집본 자동 저장)" else "편집으로 돌아가기", Modifier.size(ToolbarActionIcon))
                }
                Column(
                    Modifier.weight(1f).clickable(enabled = editing, onClickLabel = "프로젝트 이름 바꾸기", onClick = onRename).padding(vertical = 2.dp),
                ) {
                    Text(session.displayTitle, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        metadataText,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (session.saveState == EditorSaveState.FAILED) palette.danger else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (editing) {
                    IconButton(onClick = onUndo, enabled = session.canUndo && !session.busy, modifier = Modifier.size(IconHitTarget)) {
                        Icon(Icons.AutoMirrored.Filled.Undo, "실행 취소", Modifier.size(ToolbarActionIcon))
                    }
                    IconButton(onClick = onRedo, enabled = session.canRedo && !session.busy, modifier = Modifier.size(IconHitTarget)) {
                        Icon(Icons.AutoMirrored.Filled.Redo, "다시 실행", Modifier.size(ToolbarActionIcon))
                    }
                }
                Spacer(Modifier.width(4.dp))
            }
        }
    }
}

private const val PHONEHARD_SAVING_STATUS = "폰하드에 저장하는 중…"

/** The fixed bottom row of each screen (never inside a scrolling pane) holding its single primary action. */
@Composable
private fun PhaseActionRow(content: @Composable RowScope.() -> Unit) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun PhasePrimaryButton(label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val palette = themePalette()
    Button(
        shape = EditorButtonShape,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        colors = ButtonDefaults.buttonColors(containerColor = palette.solidAction, contentColor = palette.onAccent),
    ) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

/** Actual work or failure only (e.g. 폰하드에 저장하는 중…, a save error); a normal finished state adds no line. */
@Composable
private fun SessionStatusLine(session: TrailerSession) {
    if (!session.busy && session.error == null) return
    Text(
        session.error ?: session.status,
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = if (session.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Render progress, render errors and recovered/excluded notes only; no normal "ready/saved" row. Its 취소/다시 시도
 * live in the screen's single bottom primary, never duplicated here.
 */
@Composable
private fun ExportStatusBar(session: TrailerSession) {
    val export = session.export ?: return
    val palette = themePalette()
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (export.running) {
                val percent = export.progress?.let { " ${(it * 100).roundToInt().coerceIn(0, 100)}%" } ?: ""
                Text("전체 영상 만드는 중: ${export.status}$percent", style = MaterialTheme.typography.labelMedium, maxLines = 2)
                val progress = export.progress
                if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                if (session.revision != export.snapshotRevision) {
                    Text("만들기 시작 후 바뀐 편집은 이 영상에 들어가지 않습니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (export.error != null) {
                Text(export.error, color = palette.danger, style = MaterialTheme.typography.labelMedium)
                Text("편집본은 그대로 남아 있습니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (export.status.isNotBlank()) {
                // Finished-with-notes summary: a render result, never a 폰하드 save confirmation.
                Text("전체 영상 만들기 ${export.status}", style = MaterialTheme.typography.labelMedium, maxLines = 3)
            }
        }
    }
}

/** Output aspect of the whole video for the preview frame. */
private fun outputAspect(session: TrailerSession): Float {
    val (w, h) = session.format.resolveDimensions(session.scenes.map { it.source }.ifEmpty { session.sources })
    return if (h > 0) w.toFloat() / h else 16f / 9f
}

/** Picture height of a stage: the pane width at the output aspect, never above [cap] (a share of the pane height). */
private fun stageVisualHeight(session: TrailerSession, paneWidth: Dp, cap: Dp): Dp =
    minOf(paneWidth / outputAspect(session), cap)

/**
 * Default stage height times the user's preview size, never taller than the picture at full pane width nor
 * [maxCap] (a share of the pane height, so the inspector keeps the rest). While typing, a compact cap is used
 * so the keyboard leaves ample height for the inspector and its buttons.
 */
private fun scaledStageHeight(session: TrailerSession, paneWidth: Dp, cap: Dp, maxCap: Dp, scale: Float, imeVisible: Boolean): Dp {
    val base = stageVisualHeight(session, paneWidth, cap)
    if (imeVisible) return minOf(base, maxCap.coerceAtLeast(56.dp), 112.dp)
    val limit = maxOf(base, stageVisualHeight(session, paneWidth, maxCap))
    return (base * scale).coerceIn(minOf(base, 48.dp), limit)
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun EditorStage(
    session: TrailerSession,
    scene: EditorScene?,
    source: TrailerSource?,
    caption: SceneCaption?,
    visualHeight: Dp,
    modifier: Modifier,
    showControls: Boolean = true,
    previewScale: Float = 1f,
    onPreviewScaleChange: ((Float) -> Unit)? = null,
    baseVisualHeight: Dp = visualHeight,
    maxVisualHeight: Dp = visualHeight,
    // False while the fullscreen viewer is open: inline players pause (and stay paused on return) so only one plays.
    inlinePlaybackAllowed: Boolean = true,
    // Row directly under the picture (the four inspector tabs); it receives the resize drag modifier and the
    // TalkBack resize actions, both empty while typing.
    belowPicture: (@Composable (dragModifier: Modifier, resizeActions: List<CustomAccessibilityAction>) -> Unit)? = null,
) {
    // 편집 stage: the selected scene only (the whole video lives in 영상 만들기). The picture has its own bounded
    // height and the tab row under it doubles as its resize handle. While typing (showControls=false) only the
    // picture and the tabs stay, without resizing.
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(visualHeight), contentAlignment = Alignment.Center) {
            if (scene != null && source != null) {
                ScenePreview(session, scene, source, caption, inlinePlaybackAllowed, Modifier.fillMaxSize())
            } else {
                Text("클립을 선택하거나 재료를 추가해 주세요.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (belowPicture != null) {
            if (onPreviewScaleChange != null && showControls) {
                // Preview size is a UI preference only: dragging the tab row under the picture changes this picture's
                // height (down = larger), not framing or export. The drag starts only past touch slop, so a short tap
                // still selects a tab and a drag never does. The scale is derived from the height actually shown so
                // aspect/pane caps never leave a hidden overshoot (dead zone) to drag back through.
                val currentVisual by rememberUpdatedState(visualHeight)
                var dragHeight by remember { mutableStateOf(visualHeight) }
                val minHeight = maxOf(baseVisualHeight * 0.5f, minOf(baseVisualHeight, 48.dp))
                val maxHeight = maxOf(baseVisualHeight, maxVisualHeight)
                fun applyHeight(height: Dp) {
                    dragHeight = height.coerceIn(minHeight, maxHeight)
                    if (baseVisualHeight > 0.dp) onPreviewScaleChange((dragHeight / baseVisualHeight).coerceIn(0.5f, 2f))
                }
                val density = LocalDensity.current
                val dragState = rememberDraggableState { deltaPx -> applyHeight(dragHeight + with(density) { deltaPx.toDp() }) }
                val step = baseVisualHeight * 0.1f
                belowPicture(
                    Modifier.draggable(dragState, Orientation.Vertical, onDragStarted = { dragHeight = currentVisual }),
                    listOf(
                        CustomAccessibilityAction("미리보기 크게") { dragHeight = currentVisual; applyHeight(currentVisual + step); true },
                        CustomAccessibilityAction("미리보기 작게") { dragHeight = currentVisual; applyHeight(currentVisual - step); true },
                    ),
                )
            } else {
                belowPicture(Modifier, emptyList())
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun OutputPlayer(output: File, playbackAllowed: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val player = remember(output) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(output)))
            playWhenReady = false
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    // Paused in the same frame fullscreen opens; position is kept and nothing auto-resumes on return.
    if (!playbackAllowed) SideEffect { player.pause() }
    AndroidView(
        factory = { PlayerView(it).apply { useController = true; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; this.player = player } },
        update = { it.player = player },
        modifier = modifier.clip(RoundedCornerShape(10.dp)).background(Color.Black),
    )
}

/**
 * While the whole video renders: the still of the snapshot scene its progress has reached (by scene length), framed
 * like the scene preview. Silent and control-free; it never shows an older render as the result.
 */
@Composable
private fun RenderScenePreview(session: TrailerSession, export: EditorExportState, modifier: Modifier) {
    val scenes = export.scenes
    val totalLength = scenes.sumOf { it.length }
    val reached = (export.progress ?: 0f).coerceIn(0f, 1f) * totalLength
    var index = 0
    var elapsed = 0.0
    while (index < scenes.lastIndex && elapsed + scenes[index].length <= reached) {
        elapsed += scenes[index].length
        index++
    }
    val scene = scenes.getOrNull(index)
    // Keyed by the shown frame only; the previous still stays until the next one is decoded (no flashing).
    val frame by produceState<Bitmap?>(null, scene?.source?.file, scene?.start) {
        value = scene?.let { withContext(Dispatchers.IO) { loadFrame(it.source.file, it.source.isImage, it.start, 960) } }
    }
    val (outputWidth, outputHeight) = session.format.resolveDimensions(scenes.map { it.source }.ifEmpty { session.sources })
    val aspect = if (outputHeight > 0) outputWidth.toFloat() / outputHeight else 16f / 9f
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val boxWidth = minOf(maxWidth, maxHeight * aspect)
        Box(
            Modifier.width(boxWidth).aspectRatio(aspect).clip(RoundedCornerShape(10.dp)).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = frame
            if (scene != null && bitmap != null) {
                val isFit = scene.fit == SceneFit.FIT
                val image = remember(bitmap) { bitmap.asImageBitmap() }
                Image(
                    image,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = if (isFit) ContentScale.Fit else ContentScale.Crop,
                    alignment = if (isFit) Alignment.Center else BiasAlignment(scene.focusX.coerceIn(-1f, 1f), scene.focusY.coerceIn(-1f, 1f)),
                )
            } else CircularProgressIndicator(Modifier.size(28.dp))
        }
    }
}

/**
 * Framed still of the selected scene at its trim start, drawn with the scene's fit/focus in the whole-video aspect,
 * plus an optional clip player for video scenes (trimmed range, scene volume).
 */
private data class PhotoPlanKey(
    val filePath: String,
    val lastModified: Long,
    val length: Long,
    val sourceId: Int,
    val fit: SceneFit,
    val focusX: Float,
    val focusY: Float,
    val outputWidth: Int,
    val outputHeight: Int,
    val duration: Double,
)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun ScenePreview(session: TrailerSession, scene: EditorScene, source: TrailerSource, caption: SceneCaption?, playbackAllowed: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val aspect = outputAspect(session)
    var playing by remember(scene.key) { mutableStateOf(false) }
    // Fullscreen opened: the clip player leaves composition this frame (released) and play is reset, so returning
    // shows the paused preview instead of auto-resuming. Photo progress is kept.
    if (!playbackAllowed && playing) SideEffect { playing = false }
    val (outputWidth, outputHeight) = session.format.resolveDimensions(session.scenes.map { it.source }.ifEmpty { session.sources })
    val planKey = if (source.isImage) {
        PhotoPlanKey(
            filePath = source.file.absolutePath,
            lastModified = source.file.lastModified(),
            length = source.file.length(),
            sourceId = source.id,
            fit = scene.fit,
            focusX = scene.focusX,
            focusY = scene.focusY,
            outputWidth = outputWidth,
            outputHeight = outputHeight,
            duration = scene.length,
        )
    } else null
    // Until analysis answers, the preview uses the center-aimed plan (FILL still covers the frame, FIT stays whole).
    val pendingPlan = if (planKey == null) null else {
        TrailerPhotoMotionPlanner.pendingPlan(source, scene.fit, scene.focusX, scene.focusY, outputWidth, outputHeight, scene.length)
    }
    val photoPlanEntry by produceState<Pair<PhotoPlanKey, PhotoMotionPlan>?>(
        initialValue = planKey?.let { k -> pendingPlan?.let { p -> k to p } },
        key1 = planKey,
    ) {
        value = planKey?.let { k -> pendingPlan?.let { p -> k to p } }
        if (planKey != null) {
            val computed = withContext(Dispatchers.Default) {
                TrailerPhotoMotionPlanner.planMotion(
                    source = source,
                    fit = scene.fit,
                    focusX = scene.focusX,
                    focusY = scene.focusY,
                    outputWidth = outputWidth,
                    outputHeight = outputHeight,
                    durationSeconds = scene.length,
                )
            }
            value = planKey to computed
        }
    }
    val photoPlan = if (photoPlanEntry?.first == planKey) photoPlanEntry?.second else pendingPlan
    var photoPlayProgress by remember(scene.key, planKey) { mutableFloatStateOf(0f) }
    LaunchedEffect(playing, source.isImage, scene.length, planKey, scene.key) {
        if (!playing || !source.isImage) {
            return@LaunchedEffect
        }
        val durationMs = (scene.length * 1000).toLong().coerceAtLeast(100L)
        val startOffsetMs = ((photoPlayProgress.coerceIn(0f, 1f) % 1f) * durationMs).toLong()
        val startTime = android.os.SystemClock.uptimeMillis() - startOffsetMs
        while (isActive && playing) {
            val elapsed = android.os.SystemClock.uptimeMillis() - startTime
            photoPlayProgress = ((elapsed % durationMs).toFloat() / durationMs).coerceIn(0f, 1f)
            delay(16)
        }
    }
    val frame by produceState<Bitmap?>(null, source.file, scene.start, source.isImage) {
        value = withContext(Dispatchers.IO) { loadFrame(source.file, source.isImage, scene.start, 960, exact = true) }
    }
    // The exporter's own caption bitmap (exact output size, same layout) scaled into this output-shaped box.
    val captionBitmap by produceState<Bitmap?>(null, caption, outputWidth, outputHeight) {
        if (caption == null) {
            value = null
            return@produceState
        }
        delay(120)
        value = withContext(Dispatchers.Default) { SceneCaptionRenderer.render(context, caption, outputWidth, outputHeight) }
    }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val boxWidth = minOf(maxWidth, maxHeight * aspect)
        Box(
            Modifier.width(boxWidth).aspectRatio(aspect).clip(RoundedCornerShape(10.dp)).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (playing && playbackAllowed && !source.isImage) {
                // The clip plays through the SAME framing effects the exporter applies (fit/focus in the output size).
                // If this device's renderer rejects them, playback falls back to centered framing with a visible notice.
                var framingEffectsFailed by remember(scene.key, scene.fit, scene.focusX, scene.focusY, outputWidth, outputHeight) { mutableStateOf(false) }
                val player = remember(scene.key, scene.start, scene.length, source.file, scene.fit, scene.focusX, scene.focusY, outputWidth, outputHeight, framingEffectsFailed) {
                    ExoPlayer.Builder(context).build().apply {
                        if (!framingEffectsFailed) {
                            runCatching {
                                setVideoEffects(sceneFramingVideoEffects(scene.fit, scene.focusX, scene.focusY, source.width, source.height, outputWidth, outputHeight))
                            }.onFailure { framingEffectsFailed = true }
                        }
                        addListener(object : Player.Listener {
                            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                                if (!framingEffectsFailed) framingEffectsFailed = true
                            }
                        })
                        setMediaItem(
                            MediaItem.Builder().setUri(Uri.fromFile(source.file)).setClippingConfiguration(
                                MediaItem.ClippingConfiguration.Builder()
                                     .setStartPositionMs((scene.start * 1000).toLong())
                                     .setEndPositionMs(((scene.start + scene.length) * 1000).toLong())
                                     .build(),
                            ).build(),
                        )
                        repeatMode = Player.REPEAT_MODE_ONE
                        playWhenReady = true
                        prepare()
                    }
                }
                LaunchedEffect(player, scene.sourceVolume) { player.volume = scene.sourceVolume.coerceIn(0f, 1f) }
                DisposableEffect(player) { onDispose { player.release() } }
                // With effects the frames are already output-shaped, so FIT shows them 1:1 in this output-aspect box.
                val resize = when {
                    !framingEffectsFailed || scene.fit == SceneFit.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    else -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                }
                AndroidView(
                    factory = {
                        PlayerView(it).apply {
                            useController = false
                            this.player = player
                            resizeMode = resize
                        }
                    },
                    update = {
                        it.player = player
                        it.resizeMode = resize
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                if (framingEffectsFailed && scene.fit == SceneFit.FILL && (scene.focusX != 0f || scene.focusY != 0f)) {
                    Text(
                        "이 기기 재생 미리보기는 가운데 기준입니다 · 전체 영상에는 위치가 반영됩니다",
                        Modifier.align(Alignment.TopStart).padding(8.dp).background(Color.Black.copy(alpha = .65f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            } else if (source.isImage) {
                // Same camera path, easing and timing as the exporter's PhotoMotionGlTransformation: the viewport
                // (photo coordinates) is mapped onto this output-aspect box, so zoom and pan preview exactly.
                val plan = photoPlan
                val bitmap = frame
                if (bitmap != null && plan != null) {
                    val image = remember(bitmap) { bitmap.asImageBitmap() }
                    Canvas(Modifier.fillMaxSize()) {
                        if (size.width <= 0f || size.height <= 0f) return@Canvas
                        val viewport = plan.viewportAt(
                            photoPlayProgress,
                            bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1),
                            size.width / size.height,
                        )
                        val drawWidth = size.width / viewport.width
                        val drawHeight = size.height / viewport.height
                        drawImage(
                            image = image,
                            dstOffset = IntOffset((-viewport.left * drawWidth).roundToInt(), (-viewport.top * drawHeight).roundToInt()),
                            dstSize = IntSize(drawWidth.roundToInt().coerceAtLeast(1), drawHeight.roundToInt().coerceAtLeast(1)),
                            filterQuality = FilterQuality.Medium,
                        )
                    }
                } else {
                    CircularProgressIndicator(Modifier.size(28.dp))
                }
                val statusText = when (plan?.mode) {
                    PhotoMotionMode.PAN_HORIZONTAL -> "가로 무빙·확대축소 · ${plan.reason}"
                    PhotoMotionMode.PAN_VERTICAL -> "세로 무빙·확대축소 · ${plan.reason}"
                    PhotoMotionMode.ZOOM -> "확대축소 무빙 · ${plan.reason}"
                    PhotoMotionMode.FIT_MOTION -> "맞추기 · 사진 전체 · 확대·축소"
                    PhotoMotionMode.MANUAL_FILL -> "지정 구도 · ${plan.reason}"
                    PhotoMotionMode.STILL -> "사진 고정 · ${plan.reason}"
                    null -> "사진 미리보기"
                }
                if (caption == null) {
                    Text(
                        statusText,
                        Modifier.align(Alignment.TopStart).padding(8.dp).background(Color.Black.copy(alpha = .65f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            } else {
                val isFit = scene.fit == SceneFit.FIT
                frame?.let {
                    Image(
                        it.asImageBitmap(),
                        contentDescription = "${source.name} 클립 화면",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = if (isFit) ContentScale.Fit else ContentScale.Crop,
                        alignment = if (isFit) Alignment.Center else BiasAlignment(scene.focusX.coerceIn(-1f, 1f), scene.focusY.coerceIn(-1f, 1f)),
                    )
                } ?: CircularProgressIndicator(Modifier.size(28.dp))
            }
            captionBitmap?.let { bitmap ->
                val captionImage = remember(bitmap) { bitmap.asImageBitmap() }
                Image(captionImage, contentDescription = caption?.text, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            }
            FilledIconButton(
                shape = EditorButtonShape,
                onClick = { playing = !playing },
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
            ) {
                PackIcon(if (playing) PackGlyph.PAUSE else PackGlyph.PLAY, if (playing) "클립 재생 멈춤" else "클립 재생", Modifier.size(24.dp))
            }
            Text(
                "${seconds1(scene.length)}${if (!source.isImage) " · ${seconds1(scene.start)}부터" else ""}",
                Modifier.align(Alignment.BottomEnd).padding(8.dp).background(Color.Black.copy(alpha = .6f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** Decodes a downscaled frame: the photo itself, or the video frame nearest [atSeconds]. */
internal fun loadFrame(file: File, isImage: Boolean, atSeconds: Double, maxDimension: Int, exact: Boolean = false): Bitmap? = runCatching {
    if (isImage) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val sample = Integer.highestOneBit(maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / maxDimension)).coerceAtLeast(1)
        val decoded = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@runCatching null
        val orientation = runCatching {
            androidx.exifinterface.media.ExifInterface(file.absolutePath).getAttributeInt(
                androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)
        try {
            val matrix = android.graphics.Matrix()
            when (orientation) {
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSPOSE -> {
                    matrix.postRotate(90f)
                    matrix.postScale(-1f, 1f)
                }
                androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSVERSE -> {
                    matrix.postRotate(270f)
                    matrix.postScale(-1f, 1f)
                }
            }
            if (!matrix.isIdentity) {
                val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                if (rotated !== decoded) decoded.recycle()
                rotated
            } else {
                decoded
            }
        } catch (e: Throwable) {
            decoded.recycle()
            throw e
        }
    } else {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val timeUs = (atSeconds.coerceAtLeast(0.0) * 1_000_000).toLong()
            // exact=true는 선택 장면 미리보기용: 키프레임이 드문 영상에서도 실제 자르는 지점의 프레임을 보여준다.
            val option = if (exact) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            if (Build.VERSION.SDK_INT >= 27) retriever.getScaledFrameAtTime(timeUs, option, maxDimension, maxDimension)
            else retriever.getFrameAtTime(timeUs, option)
        } finally {
            runCatching { retriever.release() }
        }
    }
}.getOrNull()

/** Grid items before the first clip: the page (preview/tabs group + tab body) and the board toolbar. */

/**
 * The explicit scene board (separate from source files): lazy multirow grid, visible numbered thumbnails,
 * duration and source filename, every scene in project order, text-labelled multi-select, and target position dialog.
 * On a phone it is the editing page's only vertical scroll: [page] is its first full-width item and, only when
 * [showClips], the toolbar and the clips follow, so hundreds of clips stay lazy under one scroll. A wide page's right
 * column uses it without a page, as that column's own scroll.
 */
@Composable
internal fun EditorSettingsPanel(
    session: TrailerSession,
    selectedScene: EditorScene?,
    selectedSource: TrailerSource?,
    selectedKeys: Set<Long>,
    multiSelectMode: Boolean,
    onToggleMultiSelectMode: () -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onMarkImportant: (Set<Long>, Boolean) -> Unit,
    onMove: (Set<Long>) -> Unit,
    onDuplicate: (Set<Long>) -> Unit,
    onRemove: (Set<Long>) -> Unit,
    fixedColumns: Int,
    maxColumns: Int,
    onColumnsChange: (Int) -> Unit,
    model: NasFinderViewModel,
    modifier: Modifier = Modifier,
) {
    val project = session.editor ?: return
    val sourcesById = remember(session.sources) { session.sources.associateBy { it.id } }
    val effectiveSelectedKeys = if (selectedKeys.isNotEmpty()) selectedKeys else setOfNotNull(selectedScene?.key)
    val multiActive = multiSelectMode || effectiveSelectedKeys.size > 1
    val hasSelection = effectiveSelectedKeys.isNotEmpty()
    val configuration = LocalConfiguration.current
    val isTablet = configuration.smallestScreenWidthDp >= 600
    val baselines = remember(session.directory) { mutableStateMapOf<Long, Pair<Double, Double>>() }

    // Photo selection statistics for shared importance toggle
    val selectedScenes = remember(project.scenes, effectiveSelectedKeys) {
        project.scenes.filter { it.key in effectiveSelectedKeys }
    }
    val photoScenes = remember(selectedScenes, sourcesById) {
        selectedScenes.filter { sourcesById[it.sourceId]?.isImage == true }
    }
    val photoKeys = remember(photoScenes) { photoScenes.map { it.key }.toSet() }
    val hasPhotos = photoKeys.isNotEmpty()
    val allPhotosImportant = hasPhotos && photoScenes.all { sourcesById[it.sourceId]?.isImportant == true }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // Row 1 (Info row): columns dropdown + compact count + photo importance toggle + 전체선택/선택해제 (when multiActive)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                var columnsMenu by remember { mutableStateOf(false) }
                Box {
                    TextButton(
                        shape = EditorButtonShape,
                        onClick = { columnsMenu = true },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .heightIn(min = if (isTablet) 28.dp else 30.dp)
                            .semantics(mergeDescendants = true) {
                                contentDescription = "한 줄에 클립 ${fixedColumns}개"
                            },
                    ) {
                        Text(
                            "한 줄 ${fixedColumns}개",
                            maxLines = 1,
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                        )
                        Icon(Icons.Default.ArrowDropDown, null, Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = columnsMenu, onDismissRequest = { columnsMenu = false }) {
                        (2..maxColumns.coerceAtLeast(2)).forEach { count ->
                            DropdownMenuItem(
                                text = { Text("한 줄 ${count}개", fontSize = 13.sp) },
                                trailingIcon = {
                                    if (count == fixedColumns) {
                                        Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                    }
                                },
                                onClick = {
                                    columnsMenu = false
                                    onColumnsChange(count)
                                },
                            )
                        }
                    }
                }

                val clipSummary = when {
                    multiActive && effectiveSelectedKeys.isNotEmpty() -> "${effectiveSelectedKeys.size}/${project.scenes.size}개 선택"
                    selectedScene != null -> {
                        val idx = project.scenes.indexOfFirst { it.key == selectedScene.key }
                        if (idx >= 0) "${idx + 1}/${project.scenes.size}번" else ""
                    }
                    else -> "전체 ${project.scenes.size}개"
                }
                if (clipSummary.isNotEmpty()) {
                    Text(
                        clipSummary,
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                        color = if (multiActive && effectiveSelectedKeys.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (hasPhotos) {
                        val importantBorder = if (allPhotosImportant) Color(0xFFE65100) else MaterialTheme.colorScheme.outlineVariant
                        val importantBg = if (allPhotosImportant) Color(0xFFFFF3E0) else Color.Transparent
                        val importantContentColor = if (allPhotosImportant) Color(0xFFE65100) else MaterialTheme.colorScheme.onSurfaceVariant
                        OutlinedButton(
                            onClick = { onMarkImportant(photoKeys, !allPhotosImportant) },
                            shape = EditorButtonShape,
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = importantBg,
                                contentColor = importantContentColor,
                            ),
                            border = BorderStroke(1.dp, importantBorder),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .heightIn(min = if (isTablet) 28.dp else 30.dp)
                                .semantics {
                                    contentDescription = if (allPhotosImportant) "중요 사진 해제" else "중요 사진 설정"
                                },
                        ) {
                            Text(
                                "중요",
                                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                                fontWeight = if (allPhotosImportant) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                            )
                        }
                    }

                    if (multiActive) {
                        val allSelected = project.scenes.isNotEmpty() && effectiveSelectedKeys.size == project.scenes.size
                        TextButton(
                            shape = EditorButtonShape,
                            onClick = { if (allSelected) onClearSelection() else onSelectAll() },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .heightIn(min = if (isTablet) 28.dp else 30.dp),
                        ) {
                            Text(
                                if (allSelected) "선택 해제" else "전체 선택",
                                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                            )
                        }
                    }
                }
            }

            // Row 2 (Action row): Outlined text buttons in exact order 선택(또는 완료), 복사, 이동, 삭제
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Button 1: 선택 (multiActive일 땐 완료)
                val selectLabel = if (multiActive) "완료" else "선택"
                OutlinedButton(
                    shape = EditorButtonShape,
                    onClick = onToggleMultiSelectMode,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .heightIn(min = if (isTablet) 28.dp else 30.dp),
                ) {
                    Text(
                        selectLabel,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = 12.sp,
                            fontWeight = if (multiActive) FontWeight.Bold else FontWeight.Normal,
                        ),
                        maxLines = 1,
                    )
                }

                // Button 2: 복사
                OutlinedButton(
                    shape = EditorButtonShape,
                    onClick = { onDuplicate(effectiveSelectedKeys) },
                    enabled = hasSelection,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .heightIn(min = if (isTablet) 28.dp else 30.dp),
                ) {
                    Text(
                        "복사",
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                        maxLines = 1,
                    )
                }

                // Button 3: 이동
                OutlinedButton(
                    shape = EditorButtonShape,
                    onClick = { onMove(effectiveSelectedKeys) },
                    enabled = hasSelection,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .heightIn(min = if (isTablet) 28.dp else 30.dp),
                ) {
                    Text(
                        "이동",
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                        maxLines = 1,
                    )
                }

                // Button 4: 삭제
                OutlinedButton(
                    shape = EditorButtonShape,
                    onClick = { onRemove(effectiveSelectedKeys) },
                    enabled = hasSelection,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                        disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    ),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .heightIn(min = if (isTablet) 28.dp else 30.dp),
                ) {
                    Text(
                        "삭제",
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                        color = if (hasSelection) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        maxLines = 1,
                    )
                }
            }

            // Per-clip editing controls: only shown when single selection is active (not multi selecting)
            if (!multiActive && selectedScene != null && selectedSource != null) {
                val isImage = selectedSource.isImage

                if (isImage) {
                    var length by remember(selectedScene.key, selectedScene.length) { mutableFloatStateOf(selectedScene.length.toFloat()) }
                    val photoBaseline = baselines[selectedScene.key]
                    val canResetPhoto = photoBaseline != null && Math.abs(photoBaseline.second - length.toDouble()) > 0.001

                    // Row: Photo duration concise label
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "재생 시간 ${seconds1(length.toDouble())}",
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // Photo duration Slider with circular arrow reset button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = {
                                photoBaseline?.let { baseline ->
                                    length = baseline.second.toFloat()
                                    model.editorTrimScene(selectedScene.key, baseline.first, baseline.second)
                                }
                            },
                            enabled = canResetPhoto,
                            modifier = Modifier.semantics { contentDescription = "재생 시간 초기화" },
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                            )
                        }
                        Slider(
                            value = length.coerceIn(MIN_EDITOR_PHOTO_SCENE_SECONDS.toFloat(), MAX_EDITOR_PHOTO_SCENE_SECONDS.toFloat()),
                            onValueChange = {
                                baselines.putIfAbsent(selectedScene.key, selectedScene.start to selectedScene.length)
                                length = (it * 10).roundToInt() / 10f
                            },
                            onValueChangeFinished = { model.editorTrimScene(selectedScene.key, 0.0, length.toDouble()) },
                            valueRange = MIN_EDITOR_PHOTO_SCENE_SECONDS.toFloat()..MAX_EDITOR_PHOTO_SCENE_SECONDS.toFloat(),
                            modifier = Modifier.weight(1f).semantics { contentDescription = "사진 클립 길이" },
                        )
                    }
                } else {
                    val duration = selectedSource.duration.toFloat().coerceAtLeast(0.1f)
                    var range by remember(selectedScene.key, selectedScene.start, selectedScene.length) {
                        mutableStateOf(selectedScene.start.toFloat()..(selectedScene.start + selectedScene.length).toFloat().coerceAtMost(duration))
                    }
                    val videoBaseline = baselines[selectedScene.key]
                    val currentStart = range.start.toDouble()
                    val currentLength = (range.endInclusive - range.start).toDouble()
                    val canResetVideo = videoBaseline != null && (Math.abs(videoBaseline.first - currentStart) > 0.001 || Math.abs(videoBaseline.second - currentLength) > 0.001)

                    // Video editing label: e.g. 0.0초–1.2초 · 선택1.2초 / 전체2.7초
                    val selectedLength = (range.endInclusive - range.start).coerceAtLeast(0f)
                    Text(
                        "${seconds1(range.start.toDouble())}–${seconds1(range.endInclusive.toDouble())} · 선택${seconds1(selectedLength.toDouble())} / 전체${seconds1(selectedSource.duration)}",
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // Video RangeSlider with circular arrow reset button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = {
                                videoBaseline?.let { baseline ->
                                    range = baseline.first.toFloat()..(baseline.first + baseline.second).toFloat().coerceAtMost(duration)
                                    model.editorTrimScene(selectedScene.key, baseline.first, baseline.second)
                                }
                            },
                            enabled = canResetVideo,
                            modifier = Modifier.semantics { contentDescription = "영상 구간 초기화" },
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                            )
                        }
                        RangeSlider(
                            value = range,
                            onValueChange = {
                                baselines.putIfAbsent(selectedScene.key, selectedScene.start to selectedScene.length)
                                range = it
                            },
                            onValueChangeFinished = { model.editorTrimScene(selectedScene.key, range.start.toDouble(), (range.endInclusive - range.start).toDouble()) },
                            valueRange = 0f..duration,
                            modifier = Modifier.weight(1f).semantics { contentDescription = "영상 클립 시작과 끝" },
                        )
                    }
                }
            } else if (!multiActive && selectedScene == null) {
                Text(
                    "선택한 클립이 없습니다. 아래 클립을 눌러 편집하세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun SceneBoard(
    session: TrailerSession,
    selectedKey: Long?,
    selectedKeys: Set<Long>,
    multiSelectMode: Boolean = false,
    onToggleMultiSelectMode: (() -> Unit)? = null,
    onSelectKey: (Long) -> Unit,
    onToggleSelectKey: (Long) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onMoveTo: (Set<Long>, Int) -> Unit,
    onMoveStep: (Long, Int) -> Unit,
    onDuplicate: (Set<Long>) -> Unit,
    onRemove: (Set<Long>) -> Unit,
    onMarkImportant: ((Set<Long>, Boolean) -> Unit)? = null,
    onAddMaterial: () -> Unit,
    gridState: LazyGridState,
    onRequestMoveDialog: (Set<Long>) -> Unit,
    thumbnailAspect: Float = 1.25f,
    // Narrow cells (5–6 per row on a phone) move ⋮/checkbox under the thumbnail and drop the small text tags.
    compactCells: Boolean = false,
    fixedColumns: Int? = null,
    maxColumns: Int = 2,
    onColumnsChange: ((Int) -> Unit)? = null,
    showClips: Boolean = true,
    showToolbar: Boolean = true,
    page: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    model: NasFinderViewModel? = null,
) {
    val project = session.editor ?: return
    val sourcesById = session.sources.associateBy { it.id }
    var internalMultiSelectMode by rememberSaveable { mutableStateOf(false) }
    val effectiveMultiSelectMode = if (onToggleMultiSelectMode != null) multiSelectMode else internalMultiSelectMode
    var menuSceneKey by remember { mutableStateOf<Long?>(null) }
    var columnsMenu by remember { mutableStateOf(false) }
    var activeTitleCardSheet by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val indexedScenes = remember(project.scenes) { project.scenes.mapIndexed { idx, s -> idx to s } }

    // Compact toolbar: one text toggle for multi-selection; count, 전체 선택 and the
    // move/clone/delete actions appear only while selecting several (a single scene uses its ⋮ menu).
    val effectiveSelectedKeys = if (selectedKeys.isNotEmpty()) selectedKeys else setOfNotNull(selectedKey)
    val multiActive = effectiveMultiSelectMode || effectiveSelectedKeys.size > 1
    val headerItems: @Composable RowScope.() -> Unit = {
        Row(Modifier.align(Alignment.CenterVertically), verticalAlignment = Alignment.CenterVertically) {
            // Clips per row (thumbnail size): UI only, so order, selection, scroll and the project stay as they are.
            if (fixedColumns != null && onColumnsChange != null) {
                Box {
                    TextButton(
                        shape = EditorButtonShape,
                        onClick = { columnsMenu = true },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.heightIn(min = IconHitTarget).semantics(mergeDescendants = true) { contentDescription = "한 줄에 클립 ${fixedColumns}개" },
                    ) {
                        Text("한 줄 ${fixedColumns}개", maxLines = 1)
                        Icon(Icons.Default.ArrowDropDown, null, Modifier.size(20.dp))
                    }
                    DropdownMenu(expanded = columnsMenu, onDismissRequest = { columnsMenu = false }) {
                        (2..maxColumns.coerceAtLeast(2)).forEach { count ->
                            DropdownMenuItem(
                                text = { Text("한 줄 ${count}개") },
                                trailingIcon = { if (count == fixedColumns) Icon(Icons.Default.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary) },
                                onClick = { columnsMenu = false; onColumnsChange(count) },
                            )
                        }
                    }
                }
            }
        }
        val toggleLabel = if (multiActive) "선택 완료" else "선택"
        val toggle: () -> Unit = {
            if (onToggleMultiSelectMode != null) {
                onToggleMultiSelectMode()
            } else {
                if (multiActive) {
                    internalMultiSelectMode = false
                    if (effectiveSelectedKeys.size > 1) onClearSelection()
                } else {
                    internalMultiSelectMode = true
                }
            }
        }
        val configuration = LocalConfiguration.current
        val isTablet = configuration.smallestScreenWidthDp >= 600
        val toggleHeightMod = Modifier
            .align(Alignment.CenterVertically)
            .minimumInteractiveComponentSize()
            .heightIn(min = if (isTablet) 34.dp else IconHitTarget)
        if (multiActive) {
            FilledTonalButton(shape = EditorButtonShape, onClick = toggle, modifier = toggleHeightMod) {
                Text(toggleLabel, maxLines = 1, style = if (isTablet) MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp) else MaterialTheme.typography.labelLarge)
            }
        } else {
            OutlinedButton(shape = EditorButtonShape, onClick = toggle, modifier = toggleHeightMod) {
                Text(toggleLabel, maxLines = 1, style = if (isTablet) MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp) else MaterialTheme.typography.labelLarge)
            }
        }
    }
    val selectionItems: @Composable RowScope.() -> Unit = {
        Text(
            "${effectiveSelectedKeys.size}개 선택",
            Modifier.align(Alignment.CenterVertically).padding(start = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
        TextButton(
            shape = EditorButtonShape,
            onClick = {
                if (effectiveSelectedKeys.size == project.scenes.size) onClearSelection()
                else onSelectAll()
            },
            contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = Modifier.align(Alignment.CenterVertically).heightIn(min = IconHitTarget),
        ) {
            Text(if (effectiveSelectedKeys.size == project.scenes.size) "선택 해제" else "전체 선택")
        }
        SceneSelectionAction(PackGlyph.MOVE, "위치 이동", onClick = { onRequestMoveDialog(effectiveSelectedKeys) })
        SceneSelectionAction(PackGlyph.DUPLICATE, "복제", description = "클립 복제", onClick = { onDuplicate(effectiveSelectedKeys) })
        SceneSelectionAction(
            PackGlyph.TRASH,
            "삭제",
            description = "클립 삭제 (원본 파일은 유지)",
            tint = MaterialTheme.colorScheme.error,
            onClick = { onRemove(effectiveSelectedKeys) },
        )
    }
    val showSelection = multiActive && effectiveSelectedKeys.isNotEmpty()

    // Multirow LazyVerticalGrid: the page item (when given), then (클립 only) the toolbar and the clips.
    val minCellSize = 136.dp
    LazyVerticalGrid(
        columns = if (fixedColumns != null) GridCells.Fixed(fixedColumns) else GridCells.Adaptive(minSize = minCellSize),
        state = gridState,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        if (page != null) item(key = "page", span = { GridItemSpan(maxLineSpan) }) { page() }
        if (!showClips) return@LazyVerticalGrid
        if (showToolbar) {
            item(key = "toolbar", span = { GridItemSpan(maxLineSpan) }) {
                // Wrapping rows keep every toolbar action visible, however narrow the page.
                Surface(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FlowRow(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) { headerItems() }
                        if (showSelection) {
                            // Selection count and the actions on it share one wrapping row instead of shrinking.
                            FlowRow(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) { selectionItems() }
                        }
                    }
                }
            }
        }
        item(key = "intro_card_item", span = { GridItemSpan(maxLineSpan) }) {
            TitleCardBanner(
                isIntro = true,
                card = project.introCard,
                onOpenSettings = { activeTitleCardSheet = true },
            )
        }
        itemsIndexed(indexedScenes, key = { _, item -> item.second.key }) { _, (originalIndex, scene) ->
            val source = sourcesById[scene.sourceId] ?: return@itemsIndexed
            val isSelected = scene.key in selectedKeys || scene.key == selectedKey
            val isImportantPhoto = source.isImage && source.isImportant
            val thumb by produceState<Bitmap?>(source.thumbnail, source.file, scene.start) {
                if (value == null || !source.isImage) {
                    value = withContext(Dispatchers.IO) { loadFrame(source.file, source.isImage, scene.start, 220) } ?: value
                }
            }
            // Multi-select Checkbox OR Dropdown Menu: on the thumbnail's top right, or under it in compact cells.
            val cellAction: @Composable (Modifier) -> Unit = { actionModifier ->
                if (effectiveMultiSelectMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelectKey(scene.key) },
                        modifier = actionModifier.size(IconHitTarget),
                    )
                } else {
                    Box(actionModifier) {
                        IconButton(
                            onClick = { menuSceneKey = scene.key },
                            modifier = Modifier.size(IconHitTarget),
                        ) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = "${originalIndex + 1}번 클립 메뉴",
                                tint = Color.White,
                                modifier = Modifier.background(Color.Black.copy(alpha = 0.45f), EditorButtonShape).padding(2.dp),
                            )
                        }
                        DropdownMenu(
                            expanded = menuSceneKey == scene.key,
                            onDismissRequest = { menuSceneKey = null },
                        ) {
                            DropdownMenuItem(
                                text = { Text("앞으로 이동") },
                                enabled = originalIndex > 0,
                                onClick = { menuSceneKey = null; onMoveStep(scene.key, -1) },
                            )
                            DropdownMenuItem(
                                text = { Text("뒤로 이동") },
                                enabled = originalIndex < project.scenes.lastIndex,
                                onClick = { menuSceneKey = null; onMoveStep(scene.key, 1) },
                            )
                            DropdownMenuItem(
                                text = { Text("위치 지정 이동…") },
                                onClick = { menuSceneKey = null; onRequestMoveDialog(setOf(scene.key)) },
                            )
                            DropdownMenuItem(
                                text = { Text("클립 복제") },
                                onClick = { menuSceneKey = null; onDuplicate(setOf(scene.key)) },
                            )
                            DropdownMenuItem(
                                text = { Text("클립 삭제 (원본 파일은 유지)") },
                                onClick = { menuSceneKey = null; onRemove(setOf(scene.key)) },
                            )
                            if (source.isImage && onMarkImportant != null) {
                                DropdownMenuItem(
                                    text = { Text(if (source.isImportant) "일반 사진으로 변경" else "중요 사진으로 설정") },
                                    leadingIcon = {
                                        Icon(
                                            if (source.isImportant) Icons.Default.StarBorder else Icons.Default.Star,
                                            null,
                                            Modifier.size(18.dp),
                                            tint = if (source.isImportant) Color.Unspecified else Color(0xFFFFB300),
                                        )
                                    },
                                    onClick = {
                                        menuSceneKey = null
                                        onMarkImportant(setOf(scene.key), !source.isImportant)
                                    },
                                )
                            }
                        }
                    }
                }
            }

            Surface(
                onClick = {
                    if (effectiveMultiSelectMode) onToggleSelectKey(scene.key) else onSelectKey(scene.key)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "${originalIndex + 1}번 클립, ${source.name}, ${if (isImportantPhoto) "중요 사진, " else ""}${seconds1(scene.length)}"
                        this.selected = isSelected
                        customActions = listOf(
                            CustomAccessibilityAction("앞으로 이동") { onMoveStep(scene.key, -1); true },
                            CustomAccessibilityAction("뒤로 이동") { onMoveStep(scene.key, 1); true },
                            CustomAccessibilityAction("위치 지정 이동") { onRequestMoveDialog(setOf(scene.key)); true },
                            CustomAccessibilityAction("클립 복제") { onDuplicate(setOf(scene.key)); true },
                            CustomAccessibilityAction("클립 삭제 (원본은 유지)") { onRemove(setOf(scene.key)); true },
                        )
                    },
                shape = EditorButtonShape,
                border = BorderStroke(
                    if (isSelected) 2.dp else 1.dp,
                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                ),
                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface,
                tonalElevation = if (isSelected) 3.dp else 0.dp,
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(thumbnailAspect)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        thumb?.let {
                            Image(
                                it.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        }
                        // 1-based Scene Number Badge with optional Important indication
                        Row(
                            Modifier
                                .align(Alignment.TopStart)
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${originalIndex + 1}",
                                Modifier
                                    .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 5.dp, vertical = 2.dp),
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp),
                            )
                            if (isImportantPhoto) {
                                Box(
                                    Modifier
                                        .background(Color(0xFFE65100).copy(alpha = 0.88f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Star,
                                        contentDescription = "중요 사진",
                                        tint = Color.White,
                                        modifier = Modifier.size(12.dp),
                                    )
                                }
                            }
                        }
                        if (!compactCells) {
                            cellAction(Modifier.align(Alignment.TopEnd))
                            // Media Kind tag (bottom-left)
                            val tagBg = if (isImportantPhoto) Color(0xFFE65100).copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.65f)
                            val tagText = if (source.isImage) (if (source.isImportant) "★ 중요" else "사진") else "영상"
                            Text(
                                tagText,
                                Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(4.dp)
                                    .background(tagBg, RoundedCornerShape(3.dp))
                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    fontWeight = if (isImportantPhoto) FontWeight.Bold else FontWeight.Normal,
                                ),
                            )
                        }
                        // Duration badge (bottom-right)
                        Text(
                            seconds1(scene.length),
                            Modifier
                                .align(Alignment.BottomEnd)
                                .padding(4.dp)
                                .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(3.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium, fontSize = 10.sp),
                        )
                    }
                    if (compactCells) {
                        // The thumbnail stays a clear tap target; the 48dp ⋮/checkbox gets its own row under it.
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { cellAction(Modifier) }
                    } else {
                        // Source Filename below thumbnail
                        Text(
                            source.name,
                            Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        item {
            Surface(
                onClick = onAddMaterial,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(thumbnailAspect)
                    .semantics { contentDescription = "재료 추가" },
                shape = EditorButtonShape,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                color = Color.Transparent,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    PackIcon(PackGlyph.ADD, null, Modifier.size(24.dp))
                    Spacer(Modifier.height(4.dp))
                    Text("재료 추가", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        item(key = "outro_card_item", span = { GridItemSpan(maxLineSpan) }) {
            TitleCardBanner(
                isIntro = false,
                card = project.outroCard,
                onOpenSettings = { activeTitleCardSheet = false },
            )
        }
    }

    activeTitleCardSheet?.let { isIntro ->
        TitleCardSettingsSheet(
            isIntro = isIntro,
            card = if (isIntro) project.introCard else project.outroCard,
            session = session,
            model = model ?: return@let,
            onDismiss = { activeTitleCardSheet = null },
        )
    }
}

@Composable
private fun TitleCardBanner(
    isIntro: Boolean,
    card: TrailerTitleCard,
    onOpenSettings: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val isTablet = configuration.smallestScreenWidthDp >= 600
    val label = if (isIntro) "시작 화면" else "마지막 화면"
    val settingButtonLabel = if (isIntro) "시작 화면 설정" else "마지막 화면 설정"

    Surface(
        onClick = onOpenSettings,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "$label, ${if (card.enabled) "사용 중, ${seconds1(card.duration)}" else "사용 안 함"}"
            },
        shape = EditorButtonShape,
        color = if (card.enabled) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(
            1.dp,
            if (card.enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = if (isTablet) 6.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f).padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = if (isTablet) 14.sp else 13.sp,
                        ),
                    )
                    Text(
                        if (card.enabled) "사용 중 · ${seconds1(card.duration)}" else "사용 안 함",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            fontWeight = if (card.enabled) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        color = if (card.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (card.enabled && (card.title.isNotBlank() || card.subtitle.isNotBlank())) {
                    val previewText = listOf(card.title.trim(), card.subtitle.trim()).filter { it.isNotBlank() }.joinToString(" · ")
                    Text(
                        previewText,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            OutlinedButton(
                shape = EditorButtonShape,
                onClick = onOpenSettings,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = if (isTablet) 4.dp else 6.dp),
                modifier = Modifier.heightIn(min = if (isTablet) 32.dp else 36.dp),
            ) {
                Text(
                    settingButtonLabel,
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = if (isTablet) 12.sp else 11.sp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TitleCardSettingsSheet(
    isIntro: Boolean,
    card: TrailerTitleCard,
    session: TrailerSession,
    model: NasFinderViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var draftCard by remember(card) { mutableStateOf(card) }
    val sheetName = if (isIntro) "시작 화면 설정" else "마지막 화면 설정"
    val toggleLabel = if (isIntro) "시작 화면 사용" else "마지막 화면 사용"
    val bgToggleLabel = if (isIntro) "첫 사진/영상 배경 사용" else "마지막 사진/영상 배경 사용"

    val scenes = session.scenes
    val targetScene = if (isIntro) scenes.firstOrNull() else scenes.lastOrNull()
    val backgroundBitmap by produceState<Bitmap?>(null, isIntro, draftCard.usesFirstOrLastImage, targetScene) {
        if (!draftCard.usesFirstOrLastImage || targetScene == null) {
            value = null
            return@produceState
        }
        val atSeconds = if (isIntro) targetScene.start else maxOf(targetScene.start, targetScene.start + targetScene.length - 0.5)
        value = withContext(Dispatchers.IO) {
            loadFrame(targetScene.source.file, targetScene.source.isImage, atSeconds, 480, exact = true)
        }
    }

    val (outputWidth, outputHeight) = session.format.resolveDimensions(session.scenes.map { it.source }.ifEmpty { session.sources })
    val scale = 360f / maxOf(outputWidth, outputHeight).coerceAtLeast(1)
    val previewW = (((outputWidth * scale).toInt() / 2) * 2).coerceAtLeast(2)
    val previewH = (((outputHeight * scale).toInt() / 2) * 2).coerceAtLeast(2)

    val previewBitmap by produceState<Bitmap?>(null, draftCard, backgroundBitmap, previewW, previewH) {
        value = withContext(Dispatchers.Default) {
            TrailerTitleCardRenderer.renderCardBitmap(context, draftCard, backgroundBitmap, previewW, previewH)
        }
    }

    fun commit(updated: TrailerTitleCard) {
        draftCard = updated
        if (isIntro) model.editorSetIntroCard(updated)
        else model.editorSetOutroCard(updated)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(sheetName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "닫기")
                }
            }

            // LIVE Preview box using the same renderer
            val aspect = if (outputHeight > 0) outputWidth.toFloat() / outputHeight.toFloat() else 16f / 9f
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .aspectRatio(aspect, matchHeightConstraintsFirst = true)
                    .align(Alignment.CenterHorizontally)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = previewBitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "$sheetName 실시간 미리보기",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }

            // Toggle: 시작 화면 사용 / 마지막 화면 사용
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(toggleLabel, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text("영상의 시작/끝에 타이틀 카드를 넣습니다", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = draftCard.enabled,
                    onCheckedChange = { commit(draftCard.copy(enabled = it)) },
                )
            }

            HorizontalDivider()

            // Title and Subtitle fields
            OutlinedTextField(
                value = draftCard.title,
                onValueChange = { commit(draftCard.copy(title = it)) },
                label = { Text("제목") },
                placeholder = { Text(if (isIntro) "예: 제주도 여행" else "예: The End") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = draftCard.subtitle,
                onValueChange = { commit(draftCard.copy(subtitle = it)) },
                label = { Text("부제목") },
                placeholder = { Text(if (isIntro) "예: 2026 여름" else "예: 시청해 주셔서 감사합니다") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // 1..10s slider
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "재생 시간: ${seconds1(draftCard.duration)}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                )
                Slider(
                    value = draftCard.duration.toFloat().coerceIn(1f, 10f),
                    onValueChange = { pos ->
                        val snapped = (pos * 10).roundToInt() / 10.0
                        commit(draftCard.copy(duration = snapped))
                    },
                    valueRange = 1f..10f,
                    steps = 89,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            HorizontalDivider()

            // Background toggle: 첫/마지막 사진/영상 배경 사용
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(bgToggleLabel, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text("끄면 깔끔한 어두운 단색 배경이 적용됩니다", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = draftCard.usesFirstOrLastImage,
                    onCheckedChange = { commit(draftCard.copy(usesFirstOrLastImage = it)) },
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun Modifier.graphicsOffset(x: Float): Modifier = this.then(Modifier.offset { androidx.compose.ui.unit.IntOffset(x.roundToInt(), 0) })

/** Labelled scene-selection action: themed pack glyph at toolbar size plus text in a 48 tall target. */
@Composable
private fun SceneSelectionAction(glyph: PackGlyph, label: String, description: String = label, tint: Color = Color.Unspecified, onClick: () -> Unit) {
    val configuration = LocalConfiguration.current
    val isTablet = configuration.smallestScreenWidthDp >= 600
    OutlinedButton(
        shape = EditorButtonShape,
        onClick = onClick,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = if (isTablet) 34.dp else IconHitTarget)
            .semantics(mergeDescendants = true) { contentDescription = description },
        contentPadding = PaddingValues(start = 10.dp, end = 14.dp, top = if (isTablet) 4.dp else 6.dp, bottom = if (isTablet) 4.dp else 6.dp),
    ) {
        PackIcon(glyph, null, Modifier.size(ToolbarActionIcon), tint = tint)
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = if (isTablet) MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp) else MaterialTheme.typography.labelLarge,
            color = if (tint == Color.Unspecified) Color.Unspecified else tint,
            maxLines = 1,
        )
    }
}

/**
 * The selected tab's body split at one section boundary: [left] (the selected clip's controls, under the preview on a
 * wide page) and [right] (the related whole-video/look group). One column shows them in this order, with a divider
 * when [dividerBetween], unless [single] gives that one-column order; the state stays in the section that built them,
 * so a relayout never duplicates controls.
 */
internal class InspectorColumns(
    val left: @Composable () -> Unit,
    val right: (@Composable () -> Unit)? = null,
    val dividerBetween: Boolean = false,
    val single: (@Composable () -> Unit)? = null,
)

@Composable
private fun editorInspectorColumns(
    session: TrailerSession,
    scene: EditorScene?,
    source: TrailerSource?,
    selectedKeys: Set<Long>,
    captionDraft: CaptionDraft?,
    onCaptionDraftChange: (CaptionDraft?) -> Unit,
    tab: InspectorTab,
    model: NasFinderViewModel,
    onSelectScene: (Long) -> Unit,
    onOpenMusicPicker: () -> Unit,
): InspectorColumns = when (if (tab == InspectorTab.PLAN) InspectorTab.SCENE else tab) {
    // 클립: only the selected clip's compact length/range, so the clip grid below stays in the first viewport.
    InspectorTab.SCENE, InspectorTab.PLAN -> InspectorColumns(left = { SceneSection(session, scene, source, model, onSelectScene) })
    InspectorTab.CAPTION -> sceneCaptionColumns(session, scene, selectedKeys, captionDraft, onCaptionDraftChange, model, onSelectScene)
    InspectorTab.SOUND -> InspectorColumns(
        left = { SoundClipSection(scene, source, model) },
        right = { SoundMusicSection(session, model, onOpenMusicPicker) },
        dividerBetween = true,
    )
    // 화면: wide pages put the plan lengths under the clip's framing and the other whole-video settings at the right;
    // one column keeps 맞추기 → 비율 → whole plan. Only one of the two arrangements is composed at a time.
    InspectorTab.SCREEN -> InspectorColumns(
        left = {
            ScreenFitSection(scene, source, model)
            HorizontalDivider()
            PlanLengthSection(session, model)
        },
        right = {
            ScreenFormatSection(session, model)
            HorizontalDivider()
            PlanOptionsSection(session, model, onOpenMusicPicker)
        },
        single = {
            ScreenFitSection(scene, source, model)
            HorizontalDivider()
            ScreenFormatSection(session, model)
            HorizontalDivider()
            PlanLengthSection(session, model)
            HorizontalDivider()
            PlanOptionsSection(session, model, onOpenMusicPicker)
        },
    )
}

/**
 * One inspector column. Body only: the four tabs live directly under the preview (InspectorTabs). No scroll of its
 * own; it is part of the editing page's one scroll (its 8dp edge padding comes from the page grid).
 */
@Composable
private fun InspectorBody(modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier.padding(top = 4.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            content()
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * The four inspector tabs, shown only directly under the preview. [modifier] carries the preview resize drag
 * (vertical, past touch slop) so a tap selects a tab and a drag only resizes; each tab keeps its own click and
 * also offers the TalkBack resize actions.
 */
@Composable
private fun InspectorTabs(tab: InspectorTab, onTab: (InspectorTab) -> Unit, resizeActions: List<CustomAccessibilityAction>, modifier: Modifier) {
    val shownTab = if (tab == InspectorTab.PLAN) InspectorTab.SCENE else tab
    val tabs = InspectorTab.entries - InspectorTab.PLAN
    val tabModifier = if (resizeActions.isEmpty()) Modifier else Modifier.semantics { customActions = resizeActions }
    val configuration = LocalConfiguration.current
    val isTablet = configuration.smallestScreenWidthDp >= 600
    val tabHeightMod = if (isTablet) Modifier.minimumInteractiveComponentSize().heightIn(min = 34.dp) else Modifier.heightIn(min = IconHitTarget)
    // Four tabs share one row; only a very narrow pane (large text) wraps them into two rows.
    BoxWithConstraints(modifier.fillMaxWidth()) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            maxItemsInEachRow = if (maxWidth < 280.dp) 2 else tabs.size,
        ) {
            tabs.forEach { entry ->
                TrailerChoice(entry.title, shownTab == entry, Modifier.weight(1f).then(tabHeightMod).then(tabModifier)) { onTab(entry) }
            }
        }
    }
}

@Composable
private fun SceneSection(session: TrailerSession, scene: EditorScene?, source: TrailerSource?, model: NasFinderViewModel, onSelectScene: (Long) -> Unit) {
    if (scene == null || source == null) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("선택한 클립이 없습니다. 클립 목록에서 길이나 구간을 조절할 클립을 선택해 주세요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val firstKey = session.editor?.scenes?.firstOrNull()?.key
            if (firstKey != null) {
                OutlinedButton(shape = EditorButtonShape, onClick = { onSelectScene(firstKey) }) { Text("첫 번째 클립 선택") }
            }
        }
        return
    }
    val project = session.editor ?: return
    val index = project.scenes.indexOfFirst { it.key == scene.key }
    TrailerSectionTitle("${index + 1}번 클립 / 전체 ${project.scenes.size}개 · ${if (source.isImage) "사진" else "영상"} ${seconds1(scene.length)}")
    Text(source.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    if (source.isImage) {
        var length by remember(scene.key, scene.length) { mutableFloatStateOf(scene.length.toFloat()) }
        Text("보여 줄 사진 재생 시간: ${seconds1(length.toDouble())}", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = length.coerceIn(MIN_EDITOR_PHOTO_SCENE_SECONDS.toFloat(), MAX_EDITOR_PHOTO_SCENE_SECONDS.toFloat()),
            onValueChange = { length = (it * 10).roundToInt() / 10f },
            onValueChangeFinished = { model.editorTrimScene(scene.key, 0.0, length.toDouble()) },
            valueRange = MIN_EDITOR_PHOTO_SCENE_SECONDS.toFloat()..MAX_EDITOR_PHOTO_SCENE_SECONDS.toFloat(),
            modifier = Modifier.semantics { contentDescription = "사진 클립 길이" },
        )
    } else {
        val duration = source.duration.toFloat().coerceAtLeast(0.1f)
        var range by remember(scene.key, scene.start, scene.length) {
            mutableStateOf(scene.start.toFloat()..(scene.start + scene.length).toFloat().coerceAtMost(duration))
        }
        Text("영상 구간: ${seconds1(range.start.toDouble())} ~ ${seconds1(range.endInclusive.toDouble())} (클립 길이 ${seconds1((range.endInclusive - range.start).toDouble())} / 원본 ${seconds1(source.duration)})", style = MaterialTheme.typography.labelMedium)
        RangeSlider(
            value = range,
            onValueChange = { range = it },
            onValueChangeFinished = { model.editorTrimScene(scene.key, range.start.toDouble(), (range.endInclusive - range.start).toDouble()) },
            valueRange = 0f..duration,
            modifier = Modifier.semantics { contentDescription = "영상 클립 시작과 끝" },
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val configuration = LocalConfiguration.current
            val isTablet = configuration.smallestScreenWidthDp >= 600
            // 폭이 좁은 인스펙터(300dp)에서는 2x2, 접힌 폭(~387dp) 등에서는 기존처럼 한 줄 4개.
            val trimItemsPerRow = if (maxWidth < 340.dp) 2 else 4
            val trimButtonModifier = Modifier
                .minimumInteractiveComponentSize()
                .heightIn(min = if (isTablet) 34.dp else 40.dp)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                maxItemsInEachRow = trimItemsPerRow,
            ) {
                listOf(-0.5 to "시작 −0.5초", 0.5 to "시작 +0.5초").forEach { (delta, label) ->
                    OutlinedButton(shape = EditorButtonShape, onClick = { model.editorTrimScene(scene.key, scene.start + delta, scene.length) }, modifier = trimButtonModifier, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                listOf(-0.5 to "끝 −0.5초", 0.5 to "끝 +0.5초").forEach { (delta, label) ->
                    OutlinedButton(shape = EditorButtonShape, onClick = { model.editorTrimScene(scene.key, scene.start, scene.length + delta) }, modifier = trimButtonModifier, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
    // 위치 이동·복제·삭제 live on the scene board (selection actions and each thumbnail's menu), not repeated here.
}

@Composable
private fun SoundClipSection(scene: EditorScene?, source: TrailerSource?, model: NasFinderViewModel) {
    TrailerSectionTitle("이 클립의 원본 소리")
    if (scene == null || source == null) {
        Text("클립을 선택하면 원본 소리 크기를 바꿀 수 있습니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else if (source.isImage) {
        Text("사진 클립에는 원본 소리가 없습니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        var volume by remember(scene.key, scene.sourceVolume) { mutableFloatStateOf(scene.sourceVolume) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (volume <= 0.001f) "음소거" else "${(volume * 100).roundToInt()}%", Modifier.width(64.dp), style = MaterialTheme.typography.labelMedium)
            Slider(
                value = volume, onValueChange = { volume = (it * 20).roundToInt() / 20f },
                onValueChangeFinished = { model.editorSetSceneVolume(scene.key, volume) },
                valueRange = 0f..2f, modifier = Modifier.weight(1f).semantics { contentDescription = "원본 소리 크기" },
            )
            TextButton(shape = EditorButtonShape, onClick = { model.editorSetSceneVolume(scene.key, if (scene.sourceVolume <= 0.001f) 1f else 0f) }) {
                Text(if (scene.sourceVolume <= 0.001f) "소리 켜기" else "음소거")
            }
        }
    }
}

@Composable
private fun SoundMusicSection(session: TrailerSession, model: NasFinderViewModel, onOpenMusicPicker: () -> Unit) {
    val project = session.editor ?: return
    TrailerSectionTitle("전체 영상 배경 음악")
    val unavailable = session.unavailableMusic.takeIf { session.musicTrack == null }
    if (unavailable != null) {
        UnavailableMusicCard(unavailable, session, model, onReselect = onOpenMusicPicker)
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(session.musicTrack?.name ?: "음악 없음", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text(
                    session.musicTrack?.let { "음악 길이 ${trailerLengthTitle(it.durationSeconds)}" } ?: "영상에 넣을 배경 음악을 골라보세요",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (session.musicTrack != null) TextButton(shape = EditorButtonShape, onClick = { model.editorSetMusic(null, TrailerMusicPolicy.LOOP_MUSIC, 1) }) { Text("제거") }
            Button(shape = EditorButtonShape, onClick = onOpenMusicPicker) { Text(if (session.musicTrack != null) "음악 변경" else "음악 선택") }
        }
    }
    if (session.musicTrack != null) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TrailerChoice(
                "음악길이",
                session.musicPolicy == TrailerMusicPolicy.FIT_MUSIC_LENGTH,
                Modifier.weight(1f),
                compact = true,
            ) {
                val count = if (session.musicPolicy == TrailerMusicPolicy.FIT_MUSIC_LENGTH) session.musicFitCount + 1 else 1
                model.editorSetMusic(session.musicTrack, TrailerMusicPolicy.FIT_MUSIC_LENGTH, count)
            }
            TrailerChoice(
                "음악 반복",
                session.musicPolicy == TrailerMusicPolicy.LOOP_MUSIC,
                Modifier.weight(1f),
                compact = true,
            ) {
                model.editorSetMusic(session.musicTrack, TrailerMusicPolicy.LOOP_MUSIC, 1)
            }
            TrailerChoice(
                "음악한번",
                session.musicPolicy == TrailerMusicPolicy.SINGLE,
                Modifier.weight(1f),
                compact = true,
            ) {
                model.editorSetMusic(session.musicTrack, TrailerMusicPolicy.SINGLE, 1)
            }
            TrailerChoice(
                "음악템포",
                false,
                Modifier.weight(1f),
                enabled = !session.busy && session.musicTrack != null,
                compact = true,
            ) {
                model.analyzeAndAlignMusicTempo()
            }
        }
        if (session.musicPolicy == TrailerMusicPolicy.FIT_MUSIC_LENGTH && session.musicFitCount > 1) {
            Text("음악 길이에 맞춰 ${session.musicFitCount}번 반복합니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (project.autoPlan.not() && session.musicPolicy == TrailerMusicPolicy.FIT_MUSIC_LENGTH) {
            Text("직접 편집한 클립은 그대로 두고, 음악을 영상 길이에 맞춰 자릅니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        var musicVolume by remember(project.musicVolume) { mutableFloatStateOf(project.musicVolume) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("음악 ${(musicVolume * 100).roundToInt()}%", Modifier.width(88.dp), style = MaterialTheme.typography.labelMedium)
            Slider(
                value = musicVolume, onValueChange = { musicVolume = (it * 20).roundToInt() / 20f },
                onValueChangeFinished = { model.editorSetMusicVolume(musicVolume) },
                valueRange = 0f..2f, modifier = Modifier.weight(1f).semantics { contentDescription = "전체 음악 크기" },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("자동 오디오 더킹", style = MaterialTheme.typography.bodyMedium)
                Text("원본 소리가 나올 때 음악을 자동으로 줄입니다 (클립 소리 크기 반영)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = session.autoDucking, onCheckedChange = model::editorSetAutoDucking)
        }
    }
}

/** A replacement for an unavailable stored choice keeps its length policy; a fresh choice starts with 음악 반복. */
private fun selectReplacementMusic(session: TrailerSession, selected: TrailerMusicTrack?, model: NasFinderViewModel) {
    if (selected != null && session.musicTrack == null && session.unavailableMusic != null) {
        model.editorSetMusic(selected, session.musicPolicy, session.musicFitCount)
    } else {
        model.editorSetMusic(selected, TrailerMusicPolicy.LOOP_MUSIC, 1)
    }
}

/** Stored music whose file cannot be read now: kept as chosen, with reconnect / reselect / explicit removal. */
@Composable
private fun UnavailableMusicCard(track: TrailerMusicTrack, session: TrailerSession, model: NasFinderViewModel, onReselect: () -> Unit) {
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.errorContainer.copy(alpha = .45f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(track.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(
                "음악 파일을 찾을 수 없음 · ${trailerLengthTitle(track.durationSeconds)} · ${session.musicPolicy.title} · " +
                    "음악 ${((session.editor?.musicVolume ?: 1f) * 100).roundToInt()}%${if (session.autoDucking) " · 자동 더킹" else ""}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text("선택과 설정은 그대로 보관됩니다. 해결하기 전에는 이 음악 없이 영상을 만들지 않습니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(shape = EditorButtonShape, onClick = { model.editorRecheckMusic() }) { Text("다시 확인") }
                Button(shape = EditorButtonShape, onClick = onReselect) { Text("다시 고르기") }
                Spacer(Modifier.weight(1f))
                TextButton(shape = EditorButtonShape, onClick = { model.editorSetMusic(null, TrailerMusicPolicy.LOOP_MUSIC, 1) }) { Text("선택 해제") }
            }
        }
    }
}

/** Export was requested with an unresolved stored music choice: resolve it, or explicitly export without it. */
@Composable
private fun MissingMusicExportDialog(session: TrailerSession, model: NasFinderViewModel, onStarted: () -> Unit) {
    val track = session.unavailableMusic ?: return
    var showPicker by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = model::dismissMusicExportPrompt,
        title = { Text("음악 파일을 찾을 수 없습니다") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("이 편집에 고른 음악 ‘${track.name}’을(를) 지금 읽을 수 없어, 그대로 만들면 의도와 다른 소리가 됩니다.")
                if (session.isMusicOverlayMode) {
                    Text("뮤직비디오는 음악이 필요합니다. 파일을 다시 확인하거나 다른 음악을 골라 주세요.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("‘음악 없이 만들기’는 이번 영상에만 적용되며, 음악 선택은 편집에 계속 보관됩니다.", style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(shape = EditorButtonShape, onClick = { model.editorRecheckMusic() }) { Text("다시 확인") }
                    OutlinedButton(shape = EditorButtonShape, onClick = { showPicker = true }) { Text("다른 음악 고르기") }
                }
            }
        },
        confirmButton = {
            if (!session.isMusicOverlayMode) {
                TextButton(shape = EditorButtonShape, onClick = { model.exportEditorProjectWithoutUnavailableMusic(onStarted) }) { Text("음악 없이 만들기") }
            }
        },
        dismissButton = { TextButton(shape = EditorButtonShape, onClick = model::dismissMusicExportPrompt) { Text("취소") } },
    )
    if (showPicker) {
        UnifiedMusicPickerDialog(
            model = model,
            currentTrack = null,
            onDismiss = { showPicker = false },
            onSelectTrack = { selected ->
                showPicker = false
                if (selected != null) selectReplacementMusic(session, selected, model)
            },
        )
    }
}

@Composable
private fun ScreenFitSection(scene: EditorScene?, source: TrailerSource?, model: NasFinderViewModel) {
    TrailerSectionTitle("이 클립의 화면 채우기")
    if (scene == null || source == null) {
        Text("클립을 선택해 주세요.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SceneFit.entries.forEach { fit ->
                TrailerChoice(fit.title, scene.fit == fit, Modifier.weight(1f)) { model.editorSetSceneFraming(scene.key, fit, scene.focusX, scene.focusY) }
            }
        }
        if (scene.fit == SceneFit.FILL) {
            var focusX by remember(scene.key, scene.focusX) { mutableFloatStateOf(scene.focusX) }
            var focusY by remember(scene.key, scene.focusY) { mutableFloatStateOf(scene.focusY) }
            Text("보이는 위치", style = MaterialTheme.typography.labelMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("좌우", Modifier.width(40.dp), style = MaterialTheme.typography.labelSmall)
                Slider(focusX, { focusX = (it * 20).roundToInt() / 20f }, Modifier.weight(1f).semantics { contentDescription = "좌우 위치" },
                    valueRange = -1f..1f, onValueChangeFinished = { model.editorSetSceneFraming(scene.key, SceneFit.FILL, focusX, focusY) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("상하", Modifier.width(40.dp), style = MaterialTheme.typography.labelSmall)
                Slider(focusY, { focusY = (it * 20).roundToInt() / 20f }, Modifier.weight(1f).semantics { contentDescription = "상하 위치" },
                    valueRange = -1f..1f, onValueChangeFinished = { model.editorSetSceneFraming(scene.key, SceneFit.FILL, focusX, focusY) })
            }
            TextButton(shape = EditorButtonShape, onClick = { model.editorSetSceneFraming(scene.key, SceneFit.FILL, 0f, 0f) }, enabled = scene.focusX != 0f || scene.focusY != 0f) { Text("가운데로") }
        } else {
            Text("클립 전체가 보이도록 빈 곳은 검은색으로 채웁니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ScreenFormatSection(session: TrailerSession, model: NasFinderViewModel) {
    TrailerSectionTitle("전체 영상 비율")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TrailerFormat.entries.forEach { candidate ->
            TrailerChoice(candidate.title, session.format == candidate, Modifier.width(64.dp)) { model.editorSetFormat(candidate) }
        }
    }
    val (w, h) = session.format.resolveDimensions(session.scenes.map { it.source }.ifEmpty { session.sources })
    Text("영상 크기 ${w}×${h}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (session.format == TrailerFormat.ORIGINAL) {
        Text("'원본'은 첫 번째 영상의 비율을 따릅니다. 첫 영상의 순서를 바꾸면 영상 방향이 바뀔 수 있습니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatClipDuration(seconds: Double): String = formatAdaptiveDurationTitle(seconds)

@Composable
private fun DurationControlRow(
    currentValue: Double,
    minValue: Double,
    maxValue: Double,
    recommendedValue: Double,
    onValueSelected: (Double) -> Unit,
) {
    val hasVariation = maxValue > minValue + 1e-4

    val stepRec = adaptiveStepSeconds(recommendedValue)
    val lowerCandidate = (recommendedValue - stepRec).coerceIn(minValue, maxValue)
    val higherCandidate = (recommendedValue + stepRec).coerceIn(minValue, maxValue)

    val stepDown = adaptiveStepSeconds(currentValue)
    val canStepDown = hasVariation && currentValue > minValue + 1e-4

    val stepUp = adaptiveStepSeconds(currentValue)
    val canStepUp = hasVariation && currentValue < maxValue - 1e-4

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DurationActionButton(
            label = "-${formatStepAmount(stepDown)}",
            accessibilityLabel = "${formatStepAmount(stepDown)} 줄이기",
            enabled = canStepDown,
            modifier = Modifier.weight(1f),
            onClick = {
                val next = max(minValue, (round((currentValue - stepDown) * 10.0) / 10.0))
                onValueSelected(next)
            },
        )

        val hasLower = hasVariation && lowerCandidate < recommendedValue - 1e-4
        DurationCandidateButton(
            title = formatCandidateDuration(lowerCandidate),
            accessibilityTitle = formatAdaptiveDurationTitle(lowerCandidate),
            subtitle = null,
            selected = hasLower && abs(currentValue - lowerCandidate) < 0.05,
            enabled = hasLower,
            modifier = Modifier.weight(1.1f),
            onClick = { onValueSelected(lowerCandidate) },
        )

        DurationCandidateButton(
            title = formatCandidateDuration(recommendedValue),
            accessibilityTitle = "${formatAdaptiveDurationTitle(recommendedValue)} 추천",
            subtitle = "추천",
            selected = hasVariation && abs(currentValue - recommendedValue) < 0.05,
            enabled = hasVariation,
            modifier = Modifier.weight(1.2f),
            onClick = { onValueSelected(recommendedValue) },
        )

        val hasHigher = hasVariation && higherCandidate > recommendedValue + 1e-4
        DurationCandidateButton(
            title = formatCandidateDuration(higherCandidate),
            accessibilityTitle = formatAdaptiveDurationTitle(higherCandidate),
            subtitle = null,
            selected = hasHigher && abs(currentValue - higherCandidate) < 0.05,
            enabled = hasHigher,
            modifier = Modifier.weight(1.1f),
            onClick = { onValueSelected(higherCandidate) },
        )

        DurationActionButton(
            label = "+${formatStepAmount(stepUp)}",
            accessibilityLabel = "${formatStepAmount(stepUp)} 늘리기",
            enabled = canStepUp,
            modifier = Modifier.weight(1f),
            onClick = {
                val next = min(maxValue, (round((currentValue + stepUp) * 10.0) / 10.0))
                onValueSelected(next)
            },
        )
    }
}

@Composable
private fun DurationCandidateButton(
    title: String,
    accessibilityTitle: String,
    subtitle: String?,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val content = (if (selected) accent else MaterialTheme.colorScheme.onSurface).let { if (enabled) it else it.copy(alpha = 0.38f) }
    val configuration = LocalConfiguration.current
    val isTablet = configuration.smallestScreenWidthDp >= 600
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = if (isTablet) 34.dp else 44.dp)
            .semantics {
                this.selected = selected
                contentDescription = accessibilityTitle
            },
        shape = EditorButtonShape,
        color = if (selected) accent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant,
        border = if (selected) BorderStroke(1.dp, accent) else null,
        contentColor = content,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 2.dp, vertical = if (isTablet) 2.dp else 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) accent else MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun DurationActionButton(
    label: String,
    accessibilityLabel: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val content = MaterialTheme.colorScheme.onSurface.let { if (enabled) it else it.copy(alpha = 0.38f) }
    val configuration = LocalConfiguration.current
    val isTablet = configuration.smallestScreenWidthDp >= 600
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = if (isTablet) 34.dp else 44.dp)
            .semantics { contentDescription = accessibilityLabel },
        shape = EditorButtonShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = content,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 2.dp, vertical = if (isTablet) 2.dp else 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 전체 길이 and 영상 클립 길이 of the plan; the remaining plan options are [PlanOptionsSection]. */
@Composable
private fun PlanLengthSection(session: TrailerSession, model: NasFinderViewModel) {
    val project = session.editor ?: return
    val totalTime = session.scenes.sumOf { it.length }
    val recDefaults = remember(session.sources) { recommendedTrailerDefaults(session.sources) }

    if (session.isMusicOverlayMode) {
        val fullTotal = session.totalDuration
        val hasCards = session.cardDuration > 0.0
        TrailerSectionTitle(if (!project.autoPlan) "본편 길이" else "전체 길이")
        val label = if (hasCards) {
            "현재 본편 길이: ${formatAdaptiveDurationTitle(totalTime)} (시작·마지막 포함 ${formatAdaptiveDurationTitle(fullTotal)}, 총 ${session.scenes.size}개 클립)"
        } else {
            "현재 전체 영상 길이: ${formatAdaptiveDurationTitle(totalTime)} (총 ${session.scenes.size}개 클립)"
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text("뮤직비디오 모드: 원본 영상을 자르지 않고 음악에 맞춰 이어 붙입니다. '예고편' 모드로 바꾸면 전체 길이를 직접 설정할 수 있습니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        HorizontalDivider()

        TrailerSectionTitle("영상 클립 길이")
        Text("뮤직비디오 모드에서는 원본 영상 클립 길이를 원본 그대로 유지합니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        // --- 전체 길이 ---
        val (minTotal, maxTotal) = if (project.autoPlan) {
            minOf(1.0, totalTime) to 7200.0
        } else {
            calculateManualTotalBounds(project, session.sources)
        }
        val hasTotalVariation = maxTotal > minTotal + 1e-4

        var localTotalPreview by remember(totalTime) { mutableStateOf<Double?>(null) }
        val displayedTotal = if (hasTotalVariation) {
            (localTotalPreview ?: totalTime).coerceIn(minTotal, maxTotal)
        } else {
            totalTime
        }

        TrailerSectionTitle(if (!project.autoPlan) "본편 길이" else "전체 길이")
        val fullTotal = session.totalDuration
        val hasCards = session.cardDuration > 0.0
        val durationLabel = if (hasCards) {
            "${formatAdaptiveDurationTitle(displayedTotal)} (시작·마지막 포함 ${formatAdaptiveDurationTitle(displayedTotal + session.cardDuration)}, 총 ${session.scenes.size}개 클립)"
        } else {
            "${formatAdaptiveDurationTitle(displayedTotal)} (총 ${session.scenes.size}개 클립)"
        }
        Text(
            text = durationLabel,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        val recommendedTotal = recDefaults.total.coerceIn(minTotal, maxTotal)
        DurationControlRow(
            currentValue = displayedTotal,
            minValue = minTotal,
            maxValue = maxTotal,
            recommendedValue = recommendedTotal,
            onValueSelected = { model.editorSetPlanTotal(it) },
        )

        Slider(
            value = if (hasTotalVariation) sliderValueToPosition(displayedTotal, minTotal, maxTotal) else 0f,
            onValueChange = { pos ->
                if (hasTotalVariation) {
                    localTotalPreview = sliderPositionToValue(pos, minTotal, maxTotal)
                }
            },
            onValueChangeFinished = {
                localTotalPreview?.let { model.editorSetPlanTotal(it) }
                localTotalPreview = null
            },
            enabled = hasTotalVariation,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "전체 길이 슬라이더" },
        )

        if (!project.autoPlan) {
            Text(
                "모든 클립의 길이를 비율대로 늘리거나 줄여 전체 길이를 조절합니다. 범위를 벗어난 목표는 조절 가능 한계로 안전하게 맞춰집니다.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider()

        // --- 영상 클립 길이 ---
        val validVideoDurations = session.sources.filter { !it.isImage && it.hasValidDuration }.map { it.duration }
        val manualSpan = if (!project.autoPlan) {
            val sourcesById = session.sources.associateBy { it.id }
            project.scenes.maxOfOrNull { scene ->
                val src = sourcesById[scene.sourceId]
                if (src != null && !src.isImage && src.hasValidDuration) {
                    val safeStart = (if (scene.start.isFinite()) scene.start else 0.0).coerceIn(0.0, src.duration)
                    src.duration - safeStart
                } else 0.0
            } ?: 0.0
        } else 0.0
        val maxVideoSpan = maxOf(validVideoDurations.maxOrNull() ?: 0.0, manualSpan)
        val baseCeiling = if (maxVideoSpan > 0.0) maxVideoSpan else 30.0
        val savedClip = session.clipLength.takeIf { it.isFinite() && it > 0.0 } ?: baseCeiling
        val maxClip = maxOf(baseCeiling, savedClip)
        val minClip = minOf(0.5, maxClip)
        val hasClipVariation = maxClip > minClip + 1e-4

        var localClipPreview by remember(session.clipLength) { mutableStateOf<Double?>(null) }
        val displayedClip = if (hasClipVariation) {
            (localClipPreview ?: savedClip).coerceIn(minClip, maxClip)
        } else {
            savedClip
        }

        TrailerSectionTitle("영상 클립 길이")
        Text(
            "사진은 영상 클립 길이를 기준으로 중요도에 따라 조절돼요.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = formatAdaptiveDurationTitle(displayedClip),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        val recommendedClip = recDefaults.clipLength.coerceIn(minClip, maxClip)
        DurationControlRow(
            currentValue = displayedClip,
            minValue = minClip,
            maxValue = maxClip,
            recommendedValue = recommendedClip,
            onValueSelected = { model.editorSetPlanClipLength(it) },
        )

        Slider(
            value = if (hasClipVariation) sliderValueToPosition(displayedClip, minClip, maxClip) else 0f,
            onValueChange = { pos ->
                if (hasClipVariation) {
                    localClipPreview = sliderPositionToValue(pos, minClip, maxClip)
                }
            },
            onValueChangeFinished = {
                localClipPreview?.let { model.editorSetPlanClipLength(it) }
                localClipPreview = null
            },
            enabled = hasClipVariation,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "영상 클립 길이 슬라이더" },
        )
    }
}

/** 제작 방식, 배경 음악, 순서 and the reset of the plan; owns the plan's only confirm dialogs. */
@Composable
private fun PlanOptionsSection(session: TrailerSession, model: NasFinderViewModel, onOpenMusicPicker: () -> Unit) {
    val project = session.editor ?: return
    var confirmReset by remember { mutableStateOf(false) }
    var confirmMode by remember { mutableStateOf<Boolean?>(null) }

    TrailerSectionTitle("제작 방식")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(false to "예고편", true to "뮤직비디오").forEach { (music, title) ->
            TrailerChoice(title, session.isMusicOverlayMode == music, Modifier.weight(1f), enabled = !session.busy) {
                if (session.isMusicOverlayMode != music) {
                    if (project.autoPlan) model.switchTrailerMode(music, session.sources.map { it.id }) else confirmMode = music
                }
            }
        }
    }
    HorizontalDivider()
    TrailerSectionTitle("배경 음악")
    val unavailable = session.unavailableMusic.takeIf { session.musicTrack == null }
    if (unavailable != null) {
        UnavailableMusicCard(unavailable, session, model, onReselect = onOpenMusicPicker)
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(session.musicTrack?.name ?: "음악 없음", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text(
                    session.musicTrack?.let { "음악 길이 ${trailerLengthTitle(it.durationSeconds)}" } ?: "영상에 넣을 배경 음악을 골라보세요",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (session.musicTrack != null) TextButton(shape = EditorButtonShape, onClick = { model.editorSetMusic(null, TrailerMusicPolicy.LOOP_MUSIC, 1) }) { Text("제거") }
            Button(shape = EditorButtonShape, onClick = onOpenMusicPicker) { Text(if (session.musicTrack != null) "음악 변경" else "음악 선택") }
        }
    }
    HorizontalDivider()
    TrailerSectionTitle("순서")
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        listOf("추가순", "시간순", "가나다순", "랜덤").forEach { mode ->
            TrailerChoice(mode, session.orderMode == mode, Modifier.weight(1f)) { model.editorApplyOrder(mode) }
        }
    }

    if (!project.autoPlan) {
        HorizontalDivider()
        OutlinedButton(shape = EditorButtonShape, onClick = { confirmReset = true }, modifier = Modifier.fillMaxWidth()) {
            Text("초기 자동 추천으로 다시 구성")
        }
    }

    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("자동 추천으로 다시 구성할까요?") },
        text = { Text("직접 고친 클립 구성이 새 추천 구성으로 바뀝니다. 실행 취소로 되돌릴 수 있고, 원본 파일은 바뀌지 않습니다.") },
        confirmButton = { TextButton(shape = EditorButtonShape, onClick = { confirmReset = false; model.editorResetToAutoPlan() }) { Text("다시 구성") } },
        dismissButton = { TextButton(shape = EditorButtonShape, onClick = { confirmReset = false }) { Text("취소") } },
    )
    confirmMode?.let { music ->
        AlertDialog(
            onDismissRequest = { confirmMode = null },
            title = { Text(if (music) "뮤직비디오로 바꿀까요?" else "예고편으로 바꿀까요?") },
            text = { Text("직접 편집한 클립 구성이 새 방식의 추천 구성으로 바뀝니다. 실행 취소로 되돌릴 수 있습니다.") },
            confirmButton = { TextButton(shape = EditorButtonShape, onClick = { confirmMode = null; model.switchTrailerMode(music, session.sources.map { it.id }) }) { Text("바꾸기") } },
            dismissButton = { TextButton(shape = EditorButtonShape, onClick = { confirmMode = null }) { Text("취소") } },
        )
    }
}

private enum class FinderSource(val title: String) { PHONEHARD("폰하드"), PHOTOS("내 미디어"), NETWORK("NAS·클라우드"), WEB("웹 다운로드") }

/**
 * Finder for the CURRENT project: real browsing/selection of PhoneHard, device photos (MediaStore list + system
 * picker), NAS/cloud folders and web downloads. Selections persist across sources; the primary action adds them
 * into this project through the existing preparation helper with per-item state.
 */
@Composable
private fun EditorFinderPanel(
    session: TrailerSession,
    appState: AppState,
    model: NasFinderViewModel,
    selection: MutableMap<String, EditorAddCandidate>,
    usedOrigins: Set<String>,
    originToSource: Map<String, TrailerSource>,
    selectedSceneKey: Long?,
    onUsedAgain: (Long) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
    onAdded: () -> Unit = {},
) {
    val palette = themePalette()
    var source by rememberSaveable { mutableStateOf(FinderSource.PHONEHARD) }
    val pendingByOrigin = session.pendingAdds.associateBy { it.originKey }

    fun toggle(candidate: EditorAddCandidate) {
        if (selection.containsKey(candidate.originKey)) selection.remove(candidate.originKey) else selection[candidate.originKey] = candidate
    }

    Column(modifier.background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("현재 편집에 사용할 재료 찾기", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose, modifier = Modifier.size(IconHitTarget)) { PackIcon(PackGlyph.CLOSE, "재료 찾기 닫기") }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FinderSource.entries.forEach { entry ->
                FilterChip(selected = source == entry, onClick = { source = entry }, label = { Text(entry.title) })
            }
        }
        if (session.pendingAdds.isNotEmpty()) PendingAddsList(session.pendingAdds, model)
        Box(Modifier.fillMaxWidth().weight(1f)) {
            val itemState: (String) -> FinderItemState = { origin ->
                FinderItemState(
                    selected = selection.containsKey(origin),
                    used = origin in usedOrigins,
                    pending = pendingByOrigin[origin],
                )
            }
            val onUsedItem: (String) -> Unit = { origin ->
                originToSource[origin]?.let { src -> model.editorUseSourceAgain(src.id, selectedSceneKey)?.let(onUsedAgain) }
            }
            when (source) {
                FinderSource.PHONEHARD -> PhoneHardFinder(appState, model, itemState, ::toggle, onUsedItem)
                FinderSource.PHOTOS -> PhotosFinder(itemState, ::toggle, onUsedItem, selection)
                FinderSource.NETWORK -> NetworkFinder(appState, model, itemState, ::toggle, onUsedItem)
                FinderSource.WEB -> WebFinder(appState, model, itemState, ::toggle, onUsedItem)
            }
        }
        HorizontalDivider()
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "선택 ${selection.size}개 · 이 프로젝트 재료 ${session.sources.size}개 · 클립 ${session.editor?.scenes?.size ?: 0}개",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (selection.isNotEmpty()) TextButton(shape = EditorButtonShape, onClick = { selection.clear() }) { Text("선택 해제") }
                // References only; the selection stays so it can still be added to this project.
                OutlinedButton(
                    shape = EditorButtonShape,
                    onClick = { model.requestWorkbenchAddEditorCandidates(selection.values.toList()) },
                    enabled = selection.isNotEmpty(),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("작업대에 담기") }
                Button(
                    shape = EditorButtonShape,
                    onClick = {
                        model.editorAddCandidates(selection.values.toList(), afterKey = selectedSceneKey)
                        selection.clear()
                        onAdded()
                    },
                    enabled = selection.isNotEmpty(),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.solidAction, contentColor = palette.onAccent),
                ) {
                    Text("${session.displayTitle}에 추가", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private data class FinderItemState(val selected: Boolean, val used: Boolean, val pending: EditorPendingAdd?)

@Composable
private fun PendingAddsList(pending: List<EditorPendingAdd>, model: NasFinderViewModel) {
    val palette = themePalette()
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        pending.forEach { item ->
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(item.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                        val progressText = item.progress?.let { " ${(it * 100).roundToInt()}%" } ?: ""
                        Text("${item.phase.title}$progressText", style = MaterialTheme.typography.labelSmall,
                            color = if (item.phase == EditorAddPhase.FAILED) palette.danger else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    when (item.phase) {
                        EditorAddPhase.FAILED -> {
                            Text(item.error ?: "준비하지 못했습니다.", style = MaterialTheme.typography.labelSmall, color = palette.danger)
                            Row {
                                TextButton(shape = EditorButtonShape, onClick = { model.retryEditorAdd(item.id) }) { Text("다시 시도") }
                                TextButton(shape = EditorButtonShape, onClick = { model.dismissEditorAdd(item.id) }) { Text("건너뛰기") }
                            }
                        }
                        EditorAddPhase.QUEUED -> TextButton(shape = EditorButtonShape, onClick = { model.dismissEditorAdd(item.id) }) { Text("건너뛰기") }
                        else -> {
                            val progress = item.progress
                            if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                            else LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

/** One selectable Finder cell: checkmark = selected to add, 사용 중 = already used by this project, plus prepare state. */
@Composable
private fun FinderCell(
    name: String,
    isVideo: Boolean,
    state: FinderItemState,
    onToggle: () -> Unit,
    onUseAgain: () -> Unit,
    thumbnail: @Composable BoxScope.() -> Unit,
) {
    var usedMenu by remember { mutableStateOf(false) }
    val palette = themePalette()
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(if (state.selected) 2.dp else 0.dp, if (state.selected) palette.accent else Color.Transparent, RoundedCornerShape(8.dp))
            .combinedClickable(onClick = { if (state.used && !state.selected) usedMenu = true else onToggle() }, onLongClick = onToggle)
            .semantics {
                contentDescription = buildString {
                    append(name); append(if (isVideo) ", 영상" else ", 사진")
                    if (state.selected) append(", 추가할 항목으로 선택됨")
                    if (state.used) append(", 사용 중")
                    state.pending?.let { append(", ${it.phase.title}") }
                }
                selected = state.selected
            },
    ) {
        thumbnail()
        if (isVideo) Icon(Icons.Default.PlayCircle, null, Modifier.align(Alignment.Center).size(24.dp), tint = Color.White)
        Text(name, Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(alpha = .5f)).padding(horizontal = 4.dp, vertical = 1.dp),
            color = Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(
            Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp)
                .background(if (state.selected) palette.accent else Color.Black.copy(alpha = .4f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text(if (state.selected) "✓" else "", color = Color.White, fontSize = 12.sp) }
        if (state.used || state.pending != null) {
            Text(
                state.pending?.phase?.title ?: "사용 중",
                Modifier.align(Alignment.TopStart).padding(4.dp).background(Color.Black.copy(alpha = .7f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
                color = Color.White, fontSize = 10.sp,
            )
        }
        DropdownMenu(expanded = usedMenu, onDismissRequest = { usedMenu = false }) {
            DropdownMenuItem(text = { Text("클립으로 한 번 더 사용") }, onClick = { usedMenu = false; onUseAgain() })
            DropdownMenuItem(text = { Text("다시 가져와 새 재료로 추가 선택") }, onClick = { usedMenu = false; onToggle() })
        }
    }
}

@Composable
private fun FileThumb(file: File, isVideo: Boolean) {
    val bitmap by produceState<Bitmap?>(null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (isVideo && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ThumbnailUtils.createVideoThumbnail(file, Size(240, 240), null)
                else loadFrame(file, !isVideo, 0.0, 240)
            }.getOrNull()
        }
    }
    bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
}

@Composable
private fun PhoneHardFinder(
    appState: AppState,
    model: NasFinderViewModel,
    itemState: (String) -> FinderItemState,
    toggle: (EditorAddCandidate) -> Unit,
    onUsed: (String) -> Unit,
) {
    // 다른 화면에서 옮기거나 가져온 파일이 오래된 목록 때문에 비어 보이지 않도록, 열 때마다 폰하드 목록을 다시 읽는다.
    LaunchedEffect(Unit) { model.refreshInbox() }
    val media = remember(appState.inboxFiles) {
        appState.inboxFiles.mapNotNull { item ->
            when (BuiltInPreviewPolicy.kind(item.originalFilename, item.mimeType)) {
                BuiltInPreviewKind.VIDEO -> item to true
                BuiltInPreviewKind.IMAGE -> item to false
                else -> null
            }?.takeIf { it.first.file.isFile }
        }
    }
    if (media.isEmpty()) {
        FinderEmpty("폰하드에 사진이나 영상이 없습니다. 폰하드 탭에서 파일을 가져오면 이곳에 나타납니다.")
        return
    }
    LazyVerticalGrid(GridCells.Adaptive(96.dp), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(media, key = { it.first.id.toString() }) { (item, isVideo) ->
            val candidate = EditorAddCandidate.PhoneHard(item, isVideo)
            FinderCell(item.originalFilename, isVideo, itemState(candidate.originKey), { toggle(candidate) }, { onUsed(candidate.originKey) }) {
                FileThumb(item.file, isVideo)
            }
        }
    }
}

@Composable
private fun PhotosFinder(
    itemState: (String) -> FinderItemState,
    toggle: (EditorAddCandidate) -> Unit,
    onUsed: (String) -> Unit,
    selection: MutableMap<String, EditorAddCandidate>,
) {
    val context = LocalContext.current
    val permission = remember { LocalPhotoLibraryCalendarRepository.checkPermissionState(context) }
    var recent by remember { mutableStateOf<List<CalendarDayMedia>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(maxItems = 100)) { uris ->
        // Cancel returns an empty list and keeps every existing selection.
        uris.forEach { uri ->
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull() ?: uri.lastPathSegment ?: "미디어"
            val isVideo = context.contentResolver.getType(uri)?.startsWith("video/") == true
            val candidate = EditorAddCandidate.LocalUri(uri, name, isVideo)
            selection[candidate.originKey] = candidate
        }
    }
    LaunchedEffect(permission) {
        if (permission == CalendarMediaPermissionState.DENIED) return@LaunchedEffect
        loading = true
        try {
            recent = LocalPhotoLibraryCalendarRepository.loadRecentMedia(context, 240, LocalLibraryQuery(ascending = false))
                .sortedByDescending { LocalPhotoLibraryCalendarRepository.resolveEffectiveTimeMs(it) ?: 0L }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = "미디어 목록을 불러오지 못했습니다."
        } finally {
            loading = false
        }
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedButton(
            shape = EditorButtonShape,
            onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            PackIcon(PackGlyph.PHOTOS, null, Modifier.size(24.dp))
            Spacer(Modifier.width(6.dp))
            Text("시스템 사진 선택기에서 고르기")
        }
        when {
            permission == CalendarMediaPermissionState.DENIED -> FinderEmpty("사진 접근 권한이 없어 목록을 표시할 수 없습니다. 시스템 사진 선택기를 사용해 주세요.")
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            error != null -> FinderEmpty(error!!)
            else -> {
                val pickedOnly = selection.values.filterIsInstance<EditorAddCandidate.LocalUri>().filter { picked -> recent.none { editorOriginLocal(it.uri.toString()) == picked.originKey } }
                LazyVerticalGrid(GridCells.Adaptive(96.dp), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(pickedOnly, key = { it.originKey }) { candidate ->
                        FinderCell(candidate.name, candidate.isVideo, itemState(candidate.originKey), { toggle(candidate) }, { onUsed(candidate.originKey) }) {
                            val thumb by produceState<Bitmap?>(null, candidate.uri) { value = loadLocalLibraryThumbnail(context, candidate.uri) }
                            thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        }
                    }
                    items(recent, key = { it.uri.toString() }) { media ->
                        val candidate = EditorAddCandidate.LocalUri(media.uri, media.displayName ?: "미디어", media.isVideo, LocalPhotoLibraryCalendarRepository.resolveEffectiveTimeMs(media) ?: 0L)
                        FinderCell(candidate.name, candidate.isVideo, itemState(candidate.originKey), { toggle(candidate) }, { onUsed(candidate.originKey) }) {
                            val thumb by produceState<Bitmap?>(null, media.uri) { value = loadLocalLibraryThumbnail(context, media.uri) }
                            thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NetworkFinder(
    appState: AppState,
    model: NasFinderViewModel,
    itemState: (String) -> FinderItemState,
    toggle: (EditorAddCandidate) -> Unit,
    onUsed: (String) -> Unit,
) {
    var connectionId by rememberSaveable { mutableStateOf(appState.preferredId ?: appState.connections.firstOrNull()?.id) }
    val connection = appState.connections.firstOrNull { it.id == connectionId }
    var path by rememberSaveable(connectionId) { mutableStateOf(connection?.normalizedRootPath) }
    var entries by remember { mutableStateOf<List<RemoteFileItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    var connectionMenu by remember { mutableStateOf(false) }

    if (appState.connections.isEmpty()) {
        FinderEmpty("저장된 NAS·클라우드 연결이 없습니다. 파인더에서 연결을 추가해 주세요.")
        return
    }
    LaunchedEffect(connectionId, path, reload) {
        val target = connection ?: return@LaunchedEffect
        val folder = path ?: target.normalizedRootPath
        loading = true
        error = null
        model.listEditorFinderFolder(target, folder)
            .onSuccess { entries = it }
            .onFailure { entries = emptyList(); error = it.message ?: "폴더를 불러오지 못했습니다." }
        loading = false
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                TextButton(shape = EditorButtonShape, onClick = { connectionMenu = true }) {
                    PackIcon(PackGlyph.NAS, null, Modifier.size(24.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(connection?.name ?: "연결 선택", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(expanded = connectionMenu, onDismissRequest = { connectionMenu = false }) {
                    appState.connections.forEach { candidate ->
                        DropdownMenuItem(text = { Text(candidate.name) }, onClick = {
                            connectionMenu = false
                            connectionId = candidate.id
                            path = candidate.normalizedRootPath
                        })
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            val root = connection?.normalizedRootPath
            if (path != null && root != null && path!!.trimEnd('/') != root.trimEnd('/')) {
                TextButton(shape = EditorButtonShape, onClick = {
                    val parent = path!!.trimEnd('/').substringBeforeLast('/', "")
                    path = if (parent.length < root.trimEnd('/').length) root else parent.ifBlank { root }
                }) { Text("상위 폴더") }
            }
        }
        Text(path ?: "", Modifier.padding(horizontal = 14.dp), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            error != null -> Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(error!!, color = MaterialTheme.colorScheme.error)
                Button(shape = EditorButtonShape, onClick = { reload++ }) { Text("다시 시도") }
            }
            entries.isEmpty() -> FinderEmpty("이 폴더에 미디어나 하위 폴더가 없습니다.")
            else -> LazyVerticalGrid(GridCells.Adaptive(96.dp), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(entries, key = { it.path }) { entry ->
                    if (entry.isDirectory) {
                        Surface(
                            onClick = { path = entry.path },
                            modifier = Modifier.aspectRatio(1f).semantics { contentDescription = "${entry.name} 폴더 열기" },
                            shape = EditorButtonShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.padding(6.dp)) {
                                Icon(Icons.Default.Folder, null, Modifier.size(32.dp))
                                Text(entry.name, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    } else {
                        val target = connection ?: return@items
                        val candidate = EditorAddCandidate.Remote(target, entry)
                        FinderCell(entry.name, entry.isVideo, itemState(candidate.originKey), { toggle(candidate) }, { onUsed(candidate.originKey) }) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                PackIcon(if (entry.isVideo) PackGlyph.VIDEO else PackGlyph.PHOTOS, null, Modifier.size(28.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WebFinder(
    appState: AppState,
    model: NasFinderViewModel,
    itemState: (String) -> FinderItemState,
    toggle: (EditorAddCandidate) -> Unit,
    onUsed: (String) -> Unit,
) {
    val pending = appState.pendingLocalUpload?.takeIf { it.file.isFile }
    val kind = pending?.let { BuiltInPreviewPolicy.kind(it.filename, it.mimeType) }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("브라우저에서 받은 파일입니다. 폰하드에 저장한 다운로드는 폰하드 탭에 표시됩니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (pending != null && (kind == BuiltInPreviewKind.VIDEO || kind == BuiltInPreviewKind.IMAGE)) {
            val candidate = EditorAddCandidate.WebFile(pending.file, pending.filename, kind == BuiltInPreviewKind.VIDEO)
            Box(Modifier.width(120.dp)) {
                FinderCell(candidate.name, candidate.isVideo, itemState(candidate.originKey), { toggle(candidate) }, { onUsed(candidate.originKey) }) {
                    FileThumb(pending.file, candidate.isVideo)
                }
            }
        } else {
            Text("지금 받은 미디어 다운로드가 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(shape = EditorButtonShape, onClick = { model.show(Screen.WebBrowser) }) {
            PackIcon(PackGlyph.BROWSER, null, Modifier.size(24.dp))
            Spacer(Modifier.width(6.dp))
            Text("브라우저 열기 (편집은 유지됨)")
        }
    }
}

@Composable
private fun FinderEmpty(text: String) {
    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Shared choice button (iOS TrailerChoiceButton look). */
@Composable
internal fun TrailerChoice(title: String, selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, compact: Boolean = false, click: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val content = (if (selected) accent else MaterialTheme.colorScheme.onSurface).let { if (enabled) it else it.copy(alpha = .38f) }
    val configuration = LocalConfiguration.current
    val isTablet = configuration.smallestScreenWidthDp >= 600
    val visualMinHeight = if (isTablet) 34.dp else 44.dp
    Surface(
        onClick = click,
        enabled = enabled,
        modifier = modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = visualMinHeight)
            .semantics { this.selected = selected },
        shape = EditorButtonShape,
        color = if (selected) accent.copy(alpha = .12f) else MaterialTheme.colorScheme.surfaceVariant,
        border = if (selected) BorderStroke(1.dp, accent) else null,
        contentColor = content,
    ) {
        Row(
            Modifier.padding(horizontal = if (compact) 4.dp else 6.dp, vertical = if (isTablet) 2.dp else 4.dp),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 4.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = if (isTablet) MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp) else if (compact) MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp) else MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (selected && !compact) Icon(Icons.Default.Check, null, Modifier.size(if (isTablet) 12.dp else 14.dp), tint = accent)
        }
    }
}

@Composable
internal fun TrailerSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold)
}
