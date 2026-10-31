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
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import java.util.UUID
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

private enum class InspectorTab(val title: String) { SCENE("장면"), CAPTION("자막"), SOUND("소리·음악"), SCREEN("화면"), PLAN("구성") }

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
    var phoneDetailOpen by rememberSaveable(directoryKey) { mutableStateOf(false) }
    val gridScrollState = rememberLazyGridState()
    var compactThumbnails by rememberSaveable(directoryKey) { mutableStateOf(false) }
    var boardSearchQuery by rememberSaveable(directoryKey) { mutableStateOf("") }
    var moveTargetDialogKeys by remember { mutableStateOf<Set<Long>?>(null) }
    // Unapplied 자막 form of one scene (preview only until 적용); survives rotation, never moves to another scene.
    var captionDraft by rememberSaveable(directoryKey, stateSaver = CaptionDraftSaver) { mutableStateOf<CaptionDraft?>(null) }
    val coroutineScope = rememberCoroutineScope()
    var finderOpen by rememberSaveable(directoryKey) { mutableStateOf(false) }
    var railExpanded by rememberSaveable { mutableStateOf(false) }
    var inspectorTab by rememberSaveable(directoryKey) { mutableStateOf(InspectorTab.SCENE) }
    var lowerPanelOpen by rememberSaveable(directoryKey) { mutableStateOf(true) }
    var showOutput by rememberSaveable(directoryKey) { mutableStateOf(false) }
    var showMusicPicker by rememberSaveable(directoryKey) { mutableStateOf(false) }
    // UI-only preview size (1 = default); never part of the project, revision or undo.
    var previewScale by rememberSaveable { mutableFloatStateOf(1f) }
    var renameOpen by remember { mutableStateOf(false) }
    var collectionDialog by remember { mutableStateOf(false) }
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
    LaunchedEffect(session.output, session.outputRevision) {
        if (session.output != null && session.outputRevision == session.revision) showOutput = true
    }
    // An actual edit (revision change) that leaves the render stale returns to the live scene preview, so the
    // edit screen never keeps showing old pixels while trim/framing moves. Recomposition, rotation and play/pause
    // keep the last seen revision, so the user's explicit choice of the previous output stays until the next edit.
    var seenRevision by rememberSaveable(directoryKey) { mutableStateOf(session.revision) }
    LaunchedEffect(session.revision) {
        if (session.revision != seenRevision) {
            seenRevision = session.revision
            if (session.output != null && session.outputRevision != session.revision) showOutput = false
        }
    }
    val selectedScene = selectedSceneKey?.let(project::scene)
    val selectedSource = selectedScene?.let { scene -> session.sources.firstOrNull { it.id == scene.sourceId } }
    val previewCaption = captionDraft?.takeIf { it.sceneKey == selectedScene?.key }?.caption?.normalizedOrNull()
        ?: selectedScene?.caption?.normalizedOrNull().takeIf { captionDraft?.sceneKey != selectedScene?.key }
    val output = session.output?.takeIf { it.isFile && it.length() > 0L }
    val outputCurrent = output != null && session.outputRevision == session.revision
    val outputStatus = editorOutputStatus(session, output != null, outputCurrent)
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
            phoneDetailOpen -> phoneDetailOpen = false
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
                    event.isCtrlPressed && event.key == Key.Z && !event.isShiftPressed -> { model.editorUndo(); true }
                    event.isCtrlPressed && (event.key == Key.Y || (event.isShiftPressed && event.key == Key.Z)) -> { model.editorRedo(); true }
                    (event.key == Key.F11 || (event.isCtrlPressed && event.isShiftPressed && event.key == Key.F)) && output != null -> {
                        onFullscreenChange(!fullscreenPlayback); true
                    }
                    else -> false
                }
            },
    ) {
        val tier = editorTier(maxWidth)
        // Two panes only when both stay readable (board ≥ ~500dp, preview+inspector ≥ ~250dp, height ≥ 480dp);
        // a phone in landscape or any short/narrow window uses the phone board → detail flow instead.
        val isLandscape = maxWidth > maxHeight && maxWidth >= 840.dp && maxHeight >= 480.dp
        val isPhone = !isLandscape && (tier == EditorTier.FOCUSED || maxHeight < 560.dp)
        val panelFinderInline = finderOpen && !isPhone && (tier == EditorTier.WIDE || tier == EditorTier.MEDIUM)
        // While typing, stages keep only their (smaller) picture so the inspector field and 적용/취소 stay reachable.
        val imeVisible = WindowInsets.isImeVisible

        // Insets are consumed exactly once here: rail, header, stage controls and footer stay in the safe area.
        Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            if (tier != EditorTier.FOCUSED && !isPhone) {
                EditorRail(
                    expanded = railExpanded,
                    finderOpen = finderOpen,
                    onToggleExpanded = { railExpanded = !railExpanded },
                    onToggleFinder = { finderOpen = !finderOpen },
                    onTools = { model.minimizeEditor(MainNavigationDestination.TOOLS) },
                    onFinderHome = { model.minimizeEditor(MainNavigationDestination.FINDER) },
                )
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
            if (isLandscape) {
                // Expanded landscape: left scene board and right preview/inspector equal widths (1:1)
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    EditorHeader(
                        session = session,
                        compact = false,
                        outputCurrent = outputCurrent,
                        onClose = onRequestClose,
                        onRename = { renameOpen = true },
                        onUndo = model::editorUndo,
                        onRedo = model::editorRedo,
                        onExport = model::exportEditorProject,
                        onSaveToPhoneHard = model::saveTrailer,
                        onRetrySave = model::retryEditorAutosave,
                        showPanelToggle = false,
                        panelOpen = true,
                        onTogglePanel = {},
                    )
                    ExportStatusBar(session, outputStatus, onCancel = model::cancelEditorExport, onRetry = model::exportEditorProject, onDismiss = model::dismissEditorExportError)
                    if (session.busy || session.error != null) {
                        Text(
                            session.error ?: session.status,
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (session.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    SceneBoard(
                        session = session,
                        selectedKey = selectedSceneKey,
                        selectedKeys = selectedSceneKeys,
                        onSelectKey = { key ->
                            selectedSceneKey = key
                            selectedSceneKeys = setOf(key)
                            showOutput = false
                        },
                        onToggleSelectKey = { key ->
                            selectedSceneKeys = if (key in selectedSceneKeys) selectedSceneKeys - key else selectedSceneKeys + key
                            if (key in selectedSceneKeys) selectedSceneKey = key
                        },
                        onSelectAll = { selectedSceneKeys = project.scenes.map { it.key }.toSet() },
                        onClearSelection = { selectedSceneKeys = emptySet() },
                        onMoveTo = { keys, targetIdx -> model.editorMoveScenesTo(keys, targetIdx) },
                        onMoveStep = { key, delta -> model.editorMoveScene(key, delta) },
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
                        onAddMaterial = { finderOpen = true },
                        gridState = gridScrollState,
                        compact = compactThumbnails,
                        onToggleCompact = { compactThumbnails = !compactThumbnails },
                        searchQuery = boardSearchQuery,
                        onSearchQueryChange = { boardSearchQuery = it },
                        onRequestMoveDialog = { keys -> moveTargetDialogKeys = keys },
                        isPhone = false,
                        onOpenDetail = {},
                        fixedColumns = 4,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
                VerticalHairline()
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    // maxHeight is the pane height left above the keyboard (IME insets are in the Row's padding).
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        EditorStage(
                            session = session,
                            scene = selectedScene,
                            source = selectedSource,
                            caption = previewCaption,
                            output = output,
                            outputCurrent = outputCurrent,
                            showOutput = showOutput && output != null,
                            onShowOutput = { showOutput = it },
                            onFullscreen = { onFullscreenChange(true) },
                            onShare = ::shareOutput,
                            onSaveToPhoneHard = model::saveTrailer,
                            onCollection = { collectionDialog = true },
                            onExport = model::exportEditorProject,
                            visualHeight = scaledStageHeight(session, maxWidth - 16.dp, (maxHeight * 0.24f).coerceIn(72.dp, 200.dp), maxHeight * 0.5f, previewScale, imeVisible),
                            baseVisualHeight = stageVisualHeight(session, maxWidth - 16.dp, (maxHeight * 0.24f).coerceIn(72.dp, 200.dp)),
                            maxVisualHeight = scaledStageHeight(session, maxWidth - 16.dp, (maxHeight * 0.24f).coerceIn(72.dp, 200.dp), maxHeight * 0.5f, 2f, imeVisible),
                            previewScale = previewScale,
                            onPreviewScaleChange = { previewScale = it },
                            showControls = !imeVisible,
                            inlinePlaybackAllowed = !fullscreenPlayback,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
                    EditorInspector(
                        session = session,
                        scene = selectedScene,
                        source = selectedSource,
                        selectedKeys = selectedSceneKeys,
                        captionDraft = captionDraft,
                        onCaptionDraftChange = { captionDraft = it },
                        tab = inspectorTab,
                        onTab = { inspectorTab = it },
                        model = model,
                        onSelectScene = { key ->
                            selectedSceneKey = key
                            selectedSceneKeys = setOf(key)
                        },
                        onOpenMusicPicker = { showMusicPicker = true },
                        scrollable = true,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            } else if (isPhone) {
                // Folded phone: board fills content, selecting scene opens detail editor, back returns to board preserving scroll
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if (!phoneDetailOpen) {
                        EditorHeader(
                            session = session,
                            compact = true,
                            outputCurrent = outputCurrent,
                            onClose = onRequestClose,
                            onRename = { renameOpen = true },
                            onUndo = model::editorUndo,
                            onRedo = model::editorRedo,
                            onExport = model::exportEditorProject,
                            onSaveToPhoneHard = model::saveTrailer,
                            onRetrySave = model::retryEditorAutosave,
                            showPanelToggle = false,
                            panelOpen = true,
                            onTogglePanel = {},
                        )
                        ExportStatusBar(session, outputStatus, onCancel = model::cancelEditorExport, onRetry = model::exportEditorProject, onDismiss = model::dismissEditorExportError)
                        if (session.busy || session.error != null) {
                            Text(
                                session.error ?: session.status,
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (session.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // Selection label and move/duplicate/delete/상세 편집 live only in the board's own toolbar.
                        SceneBoard(
                            session = session,
                            selectedKey = selectedSceneKey,
                            selectedKeys = selectedSceneKeys,
                            onSelectKey = { key ->
                                selectedSceneKey = key
                                selectedSceneKeys = setOf(key)
                                showOutput = false
                            },
                            onToggleSelectKey = { key ->
                                selectedSceneKeys = if (key in selectedSceneKeys) selectedSceneKeys - key else selectedSceneKeys + key
                                if (key in selectedSceneKeys) selectedSceneKey = key
                            },
                            onSelectAll = { selectedSceneKeys = project.scenes.map { it.key }.toSet() },
                            onClearSelection = { selectedSceneKeys = emptySet() },
                            onMoveTo = { keys, targetIdx -> model.editorMoveScenesTo(keys, targetIdx) },
                            onMoveStep = { key, delta -> model.editorMoveScene(key, delta) },
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
                            onAddMaterial = { finderOpen = true },
                            gridState = gridScrollState,
                            compact = compactThumbnails,
                            onToggleCompact = { compactThumbnails = !compactThumbnails },
                            searchQuery = boardSearchQuery,
                            onSearchQueryChange = { boardSearchQuery = it },
                            onRequestMoveDialog = { keys -> moveTargetDialogKeys = keys },
                            isPhone = true,
                            onOpenDetail = { phoneDetailOpen = true },
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                        FocusedFooter(
                            onAddMaterial = { finderOpen = true },
                            primary = editorPrimary(session, outputCurrent),
                            onExport = model::exportEditorProject,
                            onSaveToPhoneHard = model::saveTrailer,
                        )
                    } else {
                        // Phone detail view: small preview + scrollable inspector; Back closes detail
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { phoneDetailOpen = false }) {
                                Icon(Icons.Default.ArrowBack, "장면 보드로 돌아가기")
                            }
                            val curIdx = project.scenes.indexOfFirst { it.key == selectedSceneKey }
                            Text(
                                if (curIdx >= 0) "${curIdx + 1}번 장면 상세 편집" else "장면 상세 편집",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            IconButton(onClick = model::editorUndo, enabled = session.canUndo && !session.busy) {
                                Icon(Icons.AutoMirrored.Filled.Undo, "실행 취소")
                            }
                            IconButton(onClick = model::editorRedo, enabled = session.canRedo && !session.busy) {
                                Icon(Icons.AutoMirrored.Filled.Redo, "다시 실행")
                            }
                            val primary = editorPrimary(session, outputCurrent)
                            Button(
                                onClick = { primary.dispatch(model::exportEditorProject, model::saveTrailer) },
                                enabled = primary.enabled,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.padding(end = 4.dp),
                            ) {
                                Text(primary.label, fontSize = 13.sp, maxLines = 1)
                            }
                        }
                        ExportStatusBar(session, outputStatus, onCancel = model::cancelEditorExport, onRetry = model::exportEditorProject, onDismiss = model::dismissEditorExportError)
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            EditorStage(
                                session = session,
                                scene = selectedScene,
                                source = selectedSource,
                                caption = previewCaption,
                                output = output,
                                outputCurrent = outputCurrent,
                                showOutput = showOutput && output != null,
                                onShowOutput = { showOutput = it },
                                onFullscreen = { onFullscreenChange(true) },
                                onShare = ::shareOutput,
                                onSaveToPhoneHard = model::saveTrailer,
                                onCollection = { collectionDialog = true },
                                onExport = model::exportEditorProject,
                                visualHeight = scaledStageHeight(session, maxWidth - 24.dp, (maxHeight * 0.30f).coerceIn(72.dp, 240.dp), maxHeight * 0.45f, previewScale, imeVisible),
                                baseVisualHeight = stageVisualHeight(session, maxWidth - 24.dp, (maxHeight * 0.30f).coerceIn(72.dp, 240.dp)),
                                maxVisualHeight = scaledStageHeight(session, maxWidth - 24.dp, (maxHeight * 0.30f).coerceIn(72.dp, 240.dp), maxHeight * 0.45f, 2f, imeVisible),
                                previewScale = previewScale,
                                onPreviewScaleChange = { previewScale = it },
                                showControls = !imeVisible,
                                inlinePlaybackAllowed = !fullscreenPlayback,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
                        EditorInspector(
                            session = session,
                            scene = selectedScene,
                            source = selectedSource,
                            selectedKeys = selectedSceneKeys,
                            captionDraft = captionDraft,
                            onCaptionDraftChange = { captionDraft = it },
                            tab = inspectorTab,
                            onTab = { inspectorTab = it },
                            model = model,
                            onSelectScene = { key ->
                                selectedSceneKey = key
                                selectedSceneKeys = setOf(key)
                            },
                            onOpenMusicPicker = { showMusicPicker = true },
                            scrollable = true,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                    }
                }
            } else {
                // Expanded portrait: practical detail area and wide multirow board using available height
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    EditorHeader(
                        session = session,
                        compact = false,
                        outputCurrent = outputCurrent,
                        onClose = onRequestClose,
                        onRename = { renameOpen = true },
                        onUndo = model::editorUndo,
                        onRedo = model::editorRedo,
                        onExport = model::exportEditorProject,
                        onSaveToPhoneHard = model::saveTrailer,
                        onRetrySave = model::retryEditorAutosave,
                        showPanelToggle = true,
                        panelOpen = lowerPanelOpen,
                        onTogglePanel = { lowerPanelOpen = !lowerPanelOpen },
                    )
                    ExportStatusBar(session, outputStatus, onCancel = model::cancelEditorExport, onRetry = model::exportEditorProject, onDismiss = model::dismissEditorExportError)
                    if (session.busy || session.error != null) {
                        Text(
                            session.error ?: session.status,
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (session.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (lowerPanelOpen) {
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            EditorStage(
                                session = session,
                                scene = selectedScene,
                                source = selectedSource,
                                caption = previewCaption,
                                output = output,
                                outputCurrent = outputCurrent,
                                showOutput = showOutput && output != null,
                                onShowOutput = { showOutput = it },
                                onFullscreen = { onFullscreenChange(true) },
                                onShare = ::shareOutput,
                                onSaveToPhoneHard = model::saveTrailer,
                                onCollection = { collectionDialog = true },
                                onExport = model::exportEditorProject,
                                visualHeight = scaledStageHeight(session, maxWidth - 24.dp, (maxHeight * 0.16f).coerceIn(72.dp, 160.dp), maxHeight * 0.34f, previewScale, imeVisible),
                                baseVisualHeight = stageVisualHeight(session, maxWidth - 24.dp, (maxHeight * 0.16f).coerceIn(72.dp, 160.dp)),
                                maxVisualHeight = scaledStageHeight(session, maxWidth - 24.dp, (maxHeight * 0.16f).coerceIn(72.dp, 160.dp), maxHeight * 0.34f, 2f, imeVisible),
                                previewScale = previewScale,
                                onPreviewScaleChange = { previewScale = it },
                                showControls = !imeVisible,
                                inlinePlaybackAllowed = !fullscreenPlayback,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            )
                        }
                        EditorInspector(
                            session = session,
                            scene = selectedScene,
                            source = selectedSource,
                            selectedKeys = selectedSceneKeys,
                            captionDraft = captionDraft,
                            onCaptionDraftChange = { captionDraft = it },
                            tab = inspectorTab,
                            onTab = { inspectorTab = it },
                            model = model,
                            onSelectScene = { key ->
                                selectedSceneKey = key
                                selectedSceneKeys = setOf(key)
                            },
                            onOpenMusicPicker = { showMusicPicker = true },
                            scrollable = true,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
                    }
                    SceneBoard(
                        session = session,
                        selectedKey = selectedSceneKey,
                        selectedKeys = selectedSceneKeys,
                        onSelectKey = { key ->
                            selectedSceneKey = key
                            selectedSceneKeys = setOf(key)
                            showOutput = false
                        },
                        onToggleSelectKey = { key ->
                            selectedSceneKeys = if (key in selectedSceneKeys) selectedSceneKeys - key else selectedSceneKeys + key
                            if (key in selectedSceneKeys) selectedSceneKey = key
                        },
                        onSelectAll = { selectedSceneKeys = project.scenes.map { it.key }.toSet() },
                        onClearSelection = { selectedSceneKeys = emptySet() },
                        onMoveTo = { keys, targetIdx -> model.editorMoveScenesTo(keys, targetIdx) },
                        onMoveStep = { key, delta -> model.editorMoveScene(key, delta) },
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
                        onAddMaterial = { finderOpen = true },
                        gridState = gridScrollState,
                        compact = compactThumbnails,
                        onToggleCompact = { compactThumbnails = !compactThumbnails },
                        searchQuery = boardSearchQuery,
                        onSearchQueryChange = { boardSearchQuery = it },
                        onRequestMoveDialog = { keys -> moveTargetDialogKeys = keys },
                        isPhone = false,
                        onOpenDetail = {},
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
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
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
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

    if (fullscreenPlayback && output != null) {
        // Only the explicit video pixels go full-bleed; the existing viewer keeps its own safe controls.
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
            confirmButton = { TextButton(onClick = { model.editorRename(title); renameOpen = false }) { Text("확인") } },
            dismissButton = { TextButton(onClick = { renameOpen = false }) { Text("취소") } },
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
                Text(if (selectedCount == 1) "장면 위치 이동" else "${selectedCount}개 장면 위치 이동")
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (selectedCount == 1) {
                            val currentPos = project.scenes.indexOfFirst { it.key in targetKeys } + 1
                            "현재 ${currentPos}번 장면입니다.\n이동할 새 위치 번호를 입력하세요 (1 ~ ${totalScenes}번)."
                        } else {
                            "선택한 ${selectedCount}개 장면의 원래 순서를 유지하며 연속 이동합니다.\n첫 번째 선택 장면이 위치할 새 번호를 입력하세요 (1 ~ ${maxAllowed}번)."
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
                    onClick = {
                        val dest1Based = parsed ?: return@Button
                        model.editorMoveScenesTo(targetKeys, dest1Based - 1)
                        moveTargetDialogKeys = null
                        if (boardSearchQuery.isBlank()) {
                            coroutineScope.launch {
                                gridScrollState.animateScrollToItem((dest1Based - 1).coerceAtLeast(0))
                            }
                        }
                    },
                    enabled = isValid,
                ) {
                    Text("이동")
                }
            },
            dismissButton = {
                TextButton(onClick = { moveTargetDialogKeys = null }) {
                    Text("취소")
                }
            },
        )
    }

    if (session.musicExportPrompt && session.musicTrack == null && session.unavailableMusic != null) {
        MissingMusicExportDialog(session, model)
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

    if (collectionDialog && output != null) {
        CompleteToCollectionDialog(
            session = session,
            output = output,
            onDismiss = { collectionDialog = false },
            onConfirm = { title, poster ->
                collectionDialog = false
                model.completeTrailerToCollection(title, poster)
            },
        )
    }
}

@Composable
private fun VerticalHairline() {
    Box(Modifier.fillMaxHeight().width(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f)))
}

/** 64dp rail (expandable with labels). Finder toggles the Finder panel; Video is the current location; Tools keeps the draft. */
@Composable
private fun EditorRail(
    expanded: Boolean,
    finderOpen: Boolean,
    onToggleExpanded: () -> Unit,
    onToggleFinder: () -> Unit,
    onTools: () -> Unit,
    onFinderHome: () -> Unit,
) {
    val width = if (expanded) 188.dp else 64.dp
    Column(
        Modifier.width(width).fillMaxHeight().background(MaterialTheme.colorScheme.surface).padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RailButton(expanded, label = if (expanded) "메뉴 접기" else "메뉴 펼치기", selected = false, onClick = onToggleExpanded) {
            Icon(Icons.Default.Menu, null, Modifier.size(24.dp))
        }
        Spacer(Modifier.height(4.dp))
        RailButton(expanded, label = if (finderOpen) "재료 찾기 닫기" else "재료 찾기 (파인더)", selected = finderOpen, onClick = onToggleFinder) {
            PackIcon(PackGlyph.HOME, null, Modifier.size(24.dp))
        }
        RailButton(expanded, label = "영상 (지금 편집 중)", selected = true, onClick = {}) {
            PackIcon(PackGlyph.VIDEO, null, Modifier.size(24.dp))
        }
        RailButton(expanded, label = "도구 (편집은 보관됨)", selected = false, onClick = onTools) {
            PackIcon(PackGlyph.SETTINGS, null, Modifier.size(24.dp))
        }
        Spacer(Modifier.weight(1f))
        RailButton(expanded, label = "파인더 (편집 유지)", selected = false, onClick = onFinderHome) {
            PackIcon(PackGlyph.NAS, null, Modifier.size(24.dp))
        }
    }
}

@Composable
private fun RailButton(expanded: Boolean, label: String, selected: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit) {
    val palette = themePalette()
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 6.dp)
            .semantics { contentDescription = label; this.selected = selected },
        shape = RoundedCornerShape(12.dp),
        color = if (selected) palette.selected else Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            icon()
            if (expanded) Text(label, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun EditorHeader(
    session: TrailerSession,
    compact: Boolean,
    outputCurrent: Boolean,
    onClose: () -> Unit,
    onRename: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onExport: () -> Unit,
    onSaveToPhoneHard: () -> Unit,
    onRetrySave: () -> Unit,
    showPanelToggle: Boolean,
    panelOpen: Boolean,
    onTogglePanel: () -> Unit,
) {
    val palette = themePalette()
    val project = session.editor
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose, modifier = Modifier.size(IconHitTarget)) { PackIcon(PackGlyph.BACK, "편집 닫기 (편집본 자동 저장)", Modifier.size(ToolbarActionIcon)) }
        Column(
            Modifier.weight(1f).clickable(onClickLabel = "프로젝트 이름 바꾸기", onClick = onRename).padding(vertical = 4.dp),
        ) {
            Text(session.displayTitle, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val saveLabel = when (session.saveState) {
                EditorSaveState.SAVING -> "편집본 저장 중…"
                EditorSaveState.SAVED -> "편집본 자동 저장됨"
                EditorSaveState.FAILED -> "편집본 저장 실패"
                EditorSaveState.IDLE -> ""
            }
            val counts = "재료 ${session.sources.size}개 · 장면 ${project?.scenes?.size ?: 0}개 · ${trailerLengthTitle(session.scenes.sumOf { it.length })}"
            Text(
                listOf(counts, saveLabel, if (project?.autoPlan == false) "직접 편집" else "자동 추천").filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = if (session.saveState == EditorSaveState.FAILED) palette.danger else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (session.saveState == EditorSaveState.FAILED) {
            TextButton(onClick = onRetrySave) { Text("다시 저장", color = palette.danger) }
        }
        IconButton(onClick = onUndo, enabled = session.canUndo && !session.busy, modifier = Modifier.size(IconHitTarget)) { Icon(Icons.AutoMirrored.Filled.Undo, "실행 취소", Modifier.size(ToolbarActionIcon)) }
        IconButton(onClick = onRedo, enabled = session.canRedo && !session.busy, modifier = Modifier.size(IconHitTarget)) { Icon(Icons.AutoMirrored.Filled.Redo, "다시 실행", Modifier.size(ToolbarActionIcon)) }
        if (showPanelToggle) {
            IconButton(onClick = onTogglePanel, modifier = Modifier.size(IconHitTarget)) { Icon(if (panelOpen) Icons.Default.ExpandMore else Icons.Default.Tune, if (panelOpen) "편집 패널 접기" else "편집 패널 열기", Modifier.size(ToolbarActionIcon)) }
        }
        val primary = editorPrimary(session, outputCurrent)
        Button(
            onClick = { primary.dispatch(onExport, onSaveToPhoneHard) },
            enabled = primary.enabled,
            colors = ButtonDefaults.buttonColors(containerColor = palette.solidAction, contentColor = palette.onAccent),
            contentPadding = if (compact) PaddingValues(horizontal = 10.dp, vertical = 6.dp) else ButtonDefaults.ContentPadding,
            modifier = Modifier.padding(end = if (compact) 4.dp else 8.dp),
        ) {
            Text(primary.label, fontSize = if (compact) 13.sp else 14.sp, maxLines = 1)
        }
    }
}

/** One primary action whose label/target follows the output: make → (edit) remake → save to 폰하드 → saved. */
private enum class EditorPrimaryStep { CREATE, RECREATE, SAVE, SAVED }

private data class EditorPrimary(val step: EditorPrimaryStep, val label: String, val enabled: Boolean) {
    fun dispatch(onExport: () -> Unit, onSaveToPhoneHard: () -> Unit) = when (step) {
        EditorPrimaryStep.CREATE, EditorPrimaryStep.RECREATE -> onExport()
        EditorPrimaryStep.SAVE -> onSaveToPhoneHard()
        EditorPrimaryStep.SAVED -> Unit
    }
}

private const val PHONEHARD_SAVING_STATUS = "폰하드에 저장하는 중…"

private fun editorPrimary(session: TrailerSession, outputCurrent: Boolean): EditorPrimary {
    val rendering = session.export?.running == true
    val step = when {
        outputCurrent && session.saved -> EditorPrimaryStep.SAVED
        outputCurrent -> EditorPrimaryStep.SAVE
        session.output != null -> EditorPrimaryStep.RECREATE
        else -> EditorPrimaryStep.CREATE
    }
    val label = when {
        rendering -> "영상 만드는 중…"
        step == EditorPrimaryStep.SAVE && session.busy && session.status == PHONEHARD_SAVING_STATUS -> "폰하드에 저장 중…"
        step == EditorPrimaryStep.CREATE -> "영상 만들기"
        step == EditorPrimaryStep.RECREATE -> "수정본 만들기"
        step == EditorPrimaryStep.SAVE -> "폰하드에 저장"
        else -> "저장됨"
    }
    val enabled = !rendering && !session.busy && when (step) {
        EditorPrimaryStep.CREATE, EditorPrimaryStep.RECREATE -> session.editor?.scenes?.isNotEmpty() == true
        EditorPrimaryStep.SAVE -> true
        EditorPrimaryStep.SAVED -> false
    }
    return EditorPrimary(step, label, enabled)
}

/** Short output state for the status line; null while rendering/busy (their own progress is shown) or before any output. */
private fun editorOutputStatus(session: TrailerSession, hasOutput: Boolean, outputCurrent: Boolean): String? = when {
    session.export?.running == true || session.busy -> null
    outputCurrent && session.saved -> "폰하드에 저장됨"
    outputCurrent -> "영상 준비됨 · 폰하드에 저장해 주세요"
    hasOutput -> "수정 내용으로 영상을 다시 만들어 주세요"
    else -> null
}

@Composable
private fun ExportStatusBar(session: TrailerSession, outputStatus: String?, onCancel: () -> Unit, onRetry: () -> Unit, onDismiss: () -> Unit) {
    val export = session.export
    if (export == null) {
        // Render done ≠ saved: one plain line says where the output stands and what to do next.
        if (outputStatus != null) {
            Text(
                outputStatus,
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        return
    }
    val palette = themePalette()
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (export.running) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val percent = export.progress?.let { " ${(it * 100).roundToInt().coerceIn(0, 100)}%" } ?: ""
                    Text("영상 만드는 중: ${export.status}$percent", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, maxLines = 2)
                    TextButton(onClick = onCancel) { Text("취소") }
                }
                val progress = export.progress
                if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                if (session.revision != export.snapshotRevision) {
                    Text("만들기 시작 후 바뀐 편집은 다음 수정본에 반영됩니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (export.error != null) {
                Text(export.error, color = palette.danger, style = MaterialTheme.typography.labelMedium)
                Text("편집본은 그대로 남아 있습니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row {
                    TextButton(onClick = onRetry) { Text("다시 시도") }
                    TextButton(onClick = onDismiss) { Text("닫기") }
                }
            } else if (export.status.isNotBlank()) {
                // Finished-with-notes summary: a render result, never a 폰하드 save confirmation.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        listOfNotNull(outputStatus, "영상 만들기 ${export.status}").joinToString("\n"),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 3,
                    )
                    TextButton(onClick = onDismiss) { Text("닫기") }
                }
            }
        }
    }
}

@Composable
private fun FocusedFooter(onAddMaterial: () -> Unit, primary: EditorPrimary, onExport: () -> Unit, onSaveToPhoneHard: () -> Unit) {
    val palette = themePalette()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = onAddMaterial, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
            PackIcon(PackGlyph.ADD, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("재료 추가")
        }
        Button(
            onClick = { primary.dispatch(onExport, onSaveToPhoneHard) },
            enabled = primary.enabled,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = palette.solidAction, contentColor = palette.onAccent),
        ) { Text(primary.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
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
 * [maxCap] (a share of the pane height, so the inspector keeps the rest). While typing the safe default is used.
 */
private fun scaledStageHeight(session: TrailerSession, paneWidth: Dp, cap: Dp, maxCap: Dp, scale: Float, imeVisible: Boolean): Dp {
    val base = stageVisualHeight(session, paneWidth, cap)
    if (imeVisible) return base
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
    output: File?,
    outputCurrent: Boolean,
    showOutput: Boolean,
    onShowOutput: (Boolean) -> Unit,
    onFullscreen: () -> Unit,
    onShare: () -> Unit,
    onSaveToPhoneHard: () -> Unit,
    onCollection: () -> Unit,
    onExport: () -> Unit,
    visualHeight: Dp,
    modifier: Modifier,
    showControls: Boolean = true,
    previewScale: Float = 1f,
    onPreviewScaleChange: ((Float) -> Unit)? = null,
    baseVisualHeight: Dp = visualHeight,
    maxVisualHeight: Dp = visualHeight,
    // False while the fullscreen viewer is open: inline players pause (and stay paused on return) so only one plays.
    inlinePlaybackAllowed: Boolean = true,
) {
    // The picture has its own bounded height; the selector and output actions add their own rows around it
    // instead of squeezing the video. While typing (showControls=false) only the picture stays.
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (output != null && showControls) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                TrailerChoice("장면 미리보기", !showOutput, Modifier.weight(1f)) { onShowOutput(false) }
                TrailerChoice(if (outputCurrent) "만든 영상" else "이전 영상 (수정 전)", showOutput, Modifier.weight(1f)) { onShowOutput(true) }
            }
        }
        Box(Modifier.fillMaxWidth().height(visualHeight), contentAlignment = Alignment.Center) {
            if (showOutput && output != null) {
                OutputPlayer(output, inlinePlaybackAllowed, Modifier.fillMaxSize())
            } else if (scene != null && source != null) {
                ScenePreview(session, scene, source, caption, inlinePlaybackAllowed, Modifier.fillMaxSize())
            } else {
                Text("장면을 선택하거나 재료를 추가해 주세요.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (onPreviewScaleChange != null && showControls) {
            // Preview size is a UI preference only: dragging the handle under the picture changes this picture's
            // height (down = larger), not framing or export. The scale is derived from the height actually shown so
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
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 24.dp)
                    .draggable(dragState, Orientation.Vertical, onDragStarted = { dragHeight = currentVisual }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    Modifier.weight(1f).heightIn(min = 24.dp).semantics {
                        contentDescription = "미리보기 크기 조절, 위아래로 드래그"
                        customActions = listOf(
                            CustomAccessibilityAction("미리보기 크게") { dragHeight = currentVisual; applyHeight(currentVisual + step); true },
                            CustomAccessibilityAction("미리보기 작게") { dragHeight = currentVisual; applyHeight(currentVisual - step); true },
                        )
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(Modifier.size(width = 36.dp, height = 3.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant))
                }
            }
        }
        if (showOutput && output != null && showControls) {
            // 폰 폭에서도 칩이 잘리지 않고 다음 줄로 이어진다(가로 스크롤로 숨기지 않음).
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (!outputCurrent) {
                    val palette = themePalette()
                    // Stale output: the same primary step as the header/footer (수정본 만들기), not a separate option.
                    val primary = editorPrimary(session, outputCurrent)
                    Button(
                        onClick = onExport,
                        enabled = primary.enabled,
                        colors = ButtonDefaults.buttonColors(containerColor = palette.solidAction, contentColor = palette.onAccent),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(primary.label, fontSize = 12.sp)
                    }
                }
                AssistChip(onClick = onFullscreen, label = { Text("전체화면") }, leadingIcon = { Icon(Icons.Default.Fullscreen, null, Modifier.size(18.dp)) })
                AssistChip(onClick = onShare, enabled = outputCurrent, label = { Text("공유") }, leadingIcon = { Icon(Icons.Default.Share, null, Modifier.size(18.dp)) })
                AssistChip(onClick = onSaveToPhoneHard, enabled = outputCurrent && !session.saved && !session.busy, label = { Text(if (session.saved) "폰하드에 저장됨" else "폰하드에 저장") }, leadingIcon = { Icon(Icons.Default.SaveAlt, null, Modifier.size(18.dp)) })
                AssistChip(onClick = onCollection, enabled = outputCurrent && !session.busy && session.export?.running != true, label = { Text("컬렉션에 보관") }, leadingIcon = { Icon(Icons.Default.VideoLibrary, null, Modifier.size(18.dp)) })
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
                        "이 기기 재생 미리보기는 가운데 기준입니다 · 만든 영상에는 위치가 반영됩니다",
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
                        contentDescription = "${source.name} 장면 화면",
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
                onClick = { playing = !playing },
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
            ) {
                PackIcon(if (playing) PackGlyph.PAUSE else PackGlyph.PLAY, if (playing) "장면 재생 멈춤" else "장면 재생", Modifier.size(20.dp))
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

/**
 * The explicit scene board (separate from source files): lazy multirow grid, visible numbered thumbnails,
 * duration and source filename, compact/normal size toggle, number/filename search & filter, direct jump to scene,
 * multi-select, and target position dialog.
 */
@Composable
private fun SceneBoard(
    session: TrailerSession,
    selectedKey: Long?,
    selectedKeys: Set<Long>,
    onSelectKey: (Long) -> Unit,
    onToggleSelectKey: (Long) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onMoveTo: (Set<Long>, Int) -> Unit,
    onMoveStep: (Long, Int) -> Unit,
    onDuplicate: (Set<Long>) -> Unit,
    onRemove: (Set<Long>) -> Unit,
    onAddMaterial: () -> Unit,
    gridState: LazyGridState,
    compact: Boolean,
    onToggleCompact: () -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onRequestMoveDialog: (Set<Long>) -> Unit,
    isPhone: Boolean = false,
    onOpenDetail: () -> Unit = {},
    fixedColumns: Int? = null,
    modifier: Modifier = Modifier,
) {
    val project = session.editor ?: return
    val sourcesById = session.sources.associateBy { it.id }
    val coroutineScope = rememberCoroutineScope()
    var multiSelectMode by rememberSaveable { mutableStateOf(false) }
    var menuSceneKey by remember { mutableStateOf<Long?>(null) }

    // Filter scenes by scene number (1-based index in project) or source filename
    val trimmedQuery = searchQuery.trim()
    val indexedScenes = remember(project.scenes) { project.scenes.mapIndexed { idx, s -> idx to s } }
    val filteredScenes = remember(indexedScenes, trimmedQuery, sourcesById) {
        if (trimmedQuery.isEmpty()) {
            indexedScenes
        } else {
            val queryNumber = trimmedQuery.toIntOrNull()
            indexedScenes.filter { (idx, scene) ->
                val sceneNum = idx + 1
                val source = sourcesById[scene.sourceId]
                (queryNumber != null && sceneNum == queryNumber) ||
                    sceneNum.toString().contains(trimmedQuery) ||
                    (source?.name?.contains(trimmedQuery, ignoreCase = true) == true)
            }
        }
    }

    Column(modifier) {
        // Toolbar: Search/Filter + View Size + Multi-select mode
        Surface(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(10.dp),
        ) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                        placeholder = { Text("장면 번호 또는 파일명", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { onSearchQueryChange("") }, modifier = Modifier.size(IconHitTarget)) {
                                    Icon(Icons.Default.Clear, "검색 지우기")
                                }
                            }
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    )
                    // If user typed a valid scene number, offer a 1-tap direct jump button
                    val jumpTarget = trimmedQuery.toIntOrNull()
                    if (jumpTarget != null && jumpTarget in 1..project.scenes.size) {
                        FilledTonalButton(
                            onClick = {
                                val targetScene = project.scenes[jumpTarget - 1]
                                onSelectKey(targetScene.key)
                                coroutineScope.launch {
                                    val gridIdx = filteredScenes.indexOfFirst { it.second.key == targetScene.key }
                                    if (gridIdx >= 0) gridState.animateScrollToItem(gridIdx)
                                }
                                if (isPhone) onOpenDetail()
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.heightIn(min = 40.dp),
                        ) {
                            Text("${jumpTarget}번 이동", fontSize = 12.sp)
                        }
                    }
                    IconButton(onClick = onToggleCompact, modifier = Modifier.size(IconHitTarget)) {
                        Icon(
                            if (compact) Icons.Default.ViewModule else Icons.Default.GridView,
                            contentDescription = if (compact) "보통 크기로 보기" else "작은 크기로 보기",
                        )
                    }
                    IconButton(
                        onClick = {
                            multiSelectMode = !multiSelectMode
                            if (!multiSelectMode && selectedKeys.size > 1) {
                                onClearSelection()
                            }
                        },
                        modifier = Modifier.size(IconHitTarget),
                    ) {
                        Icon(
                            Icons.Default.CheckBox,
                            contentDescription = "다중 선택 모드",
                            tint = if (multiSelectMode || selectedKeys.size > 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Active filter summary or selection bar
                val effectiveSelectedKeys = if (selectedKeys.isNotEmpty()) selectedKeys else setOfNotNull(selectedKey)
                // Summary + selection count own one row; the selection actions wrap below it instead of shrinking.
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (trimmedQuery.isNotEmpty()) {
                        Text(
                            "검색 결과 ${filteredScenes.size}개 / 전체 ${project.scenes.size}개",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Text(
                            "전체 장면 ${project.scenes.size}개 · ${trailerLengthTitle(project.scenes.sumOf { it.length })}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (effectiveSelectedKeys.isNotEmpty()) {
                        Text(
                            if (effectiveSelectedKeys.size == 1) {
                                val curIdx = project.scenes.indexOfFirst { it.key == effectiveSelectedKeys.first() }
                                "${curIdx + 1}번 선택됨"
                            } else {
                                "${effectiveSelectedKeys.size}개 선택됨"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                        )
                        if (multiSelectMode || effectiveSelectedKeys.size > 1) {
                            TextButton(
                                onClick = {
                                    if (effectiveSelectedKeys.size == project.scenes.size) onClearSelection()
                                    else onSelectAll()
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp),
                                modifier = Modifier.heightIn(min = IconHitTarget),
                            ) {
                                Text(if (effectiveSelectedKeys.size == project.scenes.size) "선택 해제" else "전체 선택", fontSize = 12.sp)
                            }
                        }
                    }
                }
                if (effectiveSelectedKeys.isNotEmpty()) {
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (isPhone) {
                            // Phone: card tap opens the detail editor; this keeps it reachable for multi-selection.
                            SceneSelectionAction(PackGlyph.SLIDERS, "상세 편집", onClick = onOpenDetail)
                        }
                        SceneSelectionAction(PackGlyph.MOVE, "위치 이동", onClick = { onRequestMoveDialog(effectiveSelectedKeys) })
                        SceneSelectionAction(PackGlyph.DUPLICATE, "복제", description = "장면 복제", onClick = { onDuplicate(effectiveSelectedKeys) })
                        SceneSelectionAction(
                            PackGlyph.TRASH,
                            "삭제",
                            description = "장면 삭제 (원본 파일은 유지)",
                            tint = MaterialTheme.colorScheme.error,
                            onClick = { onRemove(effectiveSelectedKeys) },
                        )
                    }
                }
            }
        }

        // Multirow LazyVerticalGrid
        val minCellSize = if (compact) 92.dp else 136.dp
        LazyVerticalGrid(
            columns = if (fixedColumns != null) GridCells.Fixed(fixedColumns) else GridCells.Adaptive(minSize = minCellSize),
            state = gridState,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            itemsIndexed(filteredScenes, key = { _, item -> item.second.key }) { _, (originalIndex, scene) ->
                val source = sourcesById[scene.sourceId] ?: return@itemsIndexed
                val isSelected = scene.key in selectedKeys || scene.key == selectedKey
                val thumb by produceState<Bitmap?>(source.thumbnail, source.file, scene.start) {
                    if (value == null || !source.isImage) {
                        value = withContext(Dispatchers.IO) { loadFrame(source.file, source.isImage, scene.start, 220) } ?: value
                    }
                }

                Surface(
                    onClick = {
                        if (multiSelectMode) {
                            onToggleSelectKey(scene.key)
                        } else {
                            onSelectKey(scene.key)
                            if (isPhone) onOpenDetail()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = "${originalIndex + 1}번 장면, ${source.name}, ${seconds1(scene.length)}"
                            this.selected = isSelected
                            customActions = listOf(
                                CustomAccessibilityAction("앞으로 이동") { onMoveStep(scene.key, -1); true },
                                CustomAccessibilityAction("뒤로 이동") { onMoveStep(scene.key, 1); true },
                                CustomAccessibilityAction("위치 지정 이동") { onRequestMoveDialog(setOf(scene.key)); true },
                                CustomAccessibilityAction("장면 복제") { onDuplicate(setOf(scene.key)); true },
                                CustomAccessibilityAction("장면 삭제 (원본은 유지)") { onRemove(setOf(scene.key)); true },
                            )
                        },
                    shape = RoundedCornerShape(8.dp),
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
                                .aspectRatio(1.25f)
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
                            // 1-based Scene Number Badge (clearly scene number)
                            Text(
                                "${originalIndex + 1}",
                                Modifier
                                    .align(Alignment.TopStart)
                                    .padding(4.dp)
                                    .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 5.dp, vertical = 2.dp),
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp),
                            )
                            // Multi-select Checkbox OR Dropdown Menu on top right
                            if (multiSelectMode) {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { onToggleSelectKey(scene.key) },
                                    modifier = Modifier.align(Alignment.TopEnd).size(IconHitTarget),
                                )
                            } else {
                                Box(Modifier.align(Alignment.TopEnd)) {
                                    IconButton(
                                        onClick = { menuSceneKey = scene.key },
                                        modifier = Modifier.size(IconHitTarget),
                                    ) {
                                        Icon(
                                            Icons.Default.MoreVert,
                                            contentDescription = "${originalIndex + 1}번 장면 메뉴",
                                            tint = Color.White,
                                            modifier = Modifier.background(Color.Black.copy(alpha = 0.45f), CircleShape).padding(2.dp),
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
                                            text = { Text("장면 복제") },
                                            onClick = { menuSceneKey = null; onDuplicate(setOf(scene.key)) },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("장면 삭제 (원본 파일은 유지)") },
                                            onClick = { menuSceneKey = null; onRemove(setOf(scene.key)) },
                                        )
                                    }
                                }
                            }
                            // Media Kind tag (bottom-left)
                            Text(
                                if (source.isImage) "사진" else "영상",
                                Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(4.dp)
                                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(3.dp))
                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            )
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
            item {
                Surface(
                    onClick = onAddMaterial,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.25f)
                        .semantics { contentDescription = "재료 추가" },
                    shape = RoundedCornerShape(8.dp),
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
        }
    }
}

private fun Modifier.graphicsOffset(x: Float): Modifier = this.then(Modifier.offset { androidx.compose.ui.unit.IntOffset(x.roundToInt(), 0) })

/** Labelled scene-selection action: themed pack glyph at toolbar size plus text in a 48 tall target. */
@Composable
private fun SceneSelectionAction(glyph: PackGlyph, label: String, description: String = label, tint: Color = Color.Unspecified, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = IconHitTarget).semantics(mergeDescendants = true) { contentDescription = description },
        contentPadding = PaddingValues(start = 10.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
    ) {
        PackIcon(glyph, null, Modifier.size(ToolbarActionIcon), tint = tint)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (tint == Color.Unspecified) Color.Unspecified else tint, maxLines = 1)
    }
}

@Composable
private fun EditorInspector(
    session: TrailerSession,
    scene: EditorScene?,
    source: TrailerSource?,
    selectedKeys: Set<Long>,
    captionDraft: CaptionDraft?,
    onCaptionDraftChange: (CaptionDraft?) -> Unit,
    tab: InspectorTab,
    onTab: (InspectorTab) -> Unit,
    model: NasFinderViewModel,
    onSelectScene: (Long) -> Unit,
    onOpenMusicPicker: () -> Unit,
    modifier: Modifier,
    scrollable: Boolean = true,
) {
    Column(modifier) {
        // Five tabs wrap into rows on narrow panes instead of squeezing into one cramped line.
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                maxItemsInEachRow = if (maxWidth < 420.dp) 3 else InspectorTab.entries.size,
            ) {
                InspectorTab.entries.forEach { entry -> TrailerChoice(entry.title, tab == entry, Modifier.weight(1f)) { onTab(entry) } }
            }
        }
        val contentModifier = if (scrollable) {
            Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 4.dp)
        } else {
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)
        }
        Column(
            modifier = contentModifier,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (tab) {
                InspectorTab.SCENE -> SceneSection(session, scene, source, model, onSelectScene)
                InspectorTab.CAPTION -> SceneCaptionSection(session, scene, selectedKeys, captionDraft, onCaptionDraftChange, model, onSelectScene)
                InspectorTab.SOUND -> SoundSection(session, scene, source, model, onOpenMusicPicker)
                InspectorTab.SCREEN -> ScreenSection(session, scene, source, model)
                InspectorTab.PLAN -> PlanSection(session, model, onOpenMusicPicker)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SceneSection(session: TrailerSession, scene: EditorScene?, source: TrailerSource?, model: NasFinderViewModel, onSelectScene: (Long) -> Unit) {
    if (scene == null || source == null) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("선택한 장면이 없습니다. 아래 타임라인에서 길이나 구간을 조절할 장면을 선택해 주세요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val firstKey = session.editor?.scenes?.firstOrNull()?.key
            if (firstKey != null) {
                OutlinedButton(onClick = { onSelectScene(firstKey) }) { Text("첫 번째 장면 선택") }
            }
        }
        return
    }
    val project = session.editor ?: return
    val index = project.scenes.indexOfFirst { it.key == scene.key }
    TrailerSectionTitle("${index + 1}번 장면 / 전체 ${project.scenes.size}개 · ${if (source.isImage) "사진" else "영상"} ${seconds1(scene.length)}")
    Text(source.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    if (source.isImage) {
        var length by remember(scene.key, scene.length) { mutableFloatStateOf(scene.length.toFloat()) }
        Text("보여 줄 사진 재생 시간: ${seconds1(length.toDouble())}", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = length.coerceIn(MIN_EDITOR_PHOTO_SCENE_SECONDS.toFloat(), MAX_EDITOR_PHOTO_SCENE_SECONDS.toFloat()),
            onValueChange = { length = (it * 10).roundToInt() / 10f },
            onValueChangeFinished = { model.editorTrimScene(scene.key, 0.0, length.toDouble()) },
            valueRange = MIN_EDITOR_PHOTO_SCENE_SECONDS.toFloat()..MAX_EDITOR_PHOTO_SCENE_SECONDS.toFloat(),
            modifier = Modifier.semantics { contentDescription = "사진 장면 길이" },
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
            modifier = Modifier.semantics { contentDescription = "영상 장면 시작과 끝" },
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // 폭이 좁은 인스펙터(300dp)에서는 2x2, 접힌 폭(~387dp) 등에서는 기존처럼 한 줄 4개.
            val trimItemsPerRow = if (maxWidth < 340.dp) 2 else 4
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                maxItemsInEachRow = trimItemsPerRow,
            ) {
                val trimButtonModifier = Modifier.weight(1f)
                listOf(-0.5 to "시작 −0.5초", 0.5 to "시작 +0.5초").forEach { (delta, label) ->
                    OutlinedButton(onClick = { model.editorTrimScene(scene.key, scene.start + delta, scene.length) }, modifier = trimButtonModifier, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                listOf(-0.5 to "끝 −0.5초", 0.5 to "끝 +0.5초").forEach { (delta, label) ->
                    OutlinedButton(onClick = { model.editorTrimScene(scene.key, scene.start, scene.length + delta) }, modifier = trimButtonModifier, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
    HorizontalDivider()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { model.editorMoveScene(scene.key, -1) }, enabled = index > 0, modifier = Modifier.weight(1f)) { Text("앞으로") }
        OutlinedButton(onClick = { model.editorMoveScene(scene.key, 1) }, enabled = index < project.scenes.lastIndex, modifier = Modifier.weight(1f)) { Text("뒤로") }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { model.editorDuplicateScene(scene.key)?.let(onSelectScene) }, modifier = Modifier.weight(1f)) { Text("복제") }
        OutlinedButton(onClick = { model.editorRemoveScene(scene.key) }, modifier = Modifier.weight(1f)) { Text("장면 삭제") }
    }
    Text("장면을 삭제해도 원본 파일과 재료는 지워지지 않습니다. 실행 취소로 되돌릴 수 있습니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SoundSection(session: TrailerSession, scene: EditorScene?, source: TrailerSource?, model: NasFinderViewModel, onOpenMusicPicker: () -> Unit) {
    val project = session.editor ?: return
    TrailerSectionTitle("이 장면의 원본 소리")
    if (scene == null || source == null) {
        Text("장면을 선택하면 원본 소리 크기를 바꿀 수 있습니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else if (source.isImage) {
        Text("사진 장면에는 원본 소리가 없습니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        var volume by remember(scene.key, scene.sourceVolume) { mutableFloatStateOf(scene.sourceVolume) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (volume <= 0.001f) "음소거" else "${(volume * 100).roundToInt()}%", Modifier.width(64.dp), style = MaterialTheme.typography.labelMedium)
            Slider(
                value = volume, onValueChange = { volume = (it * 20).roundToInt() / 20f },
                onValueChangeFinished = { model.editorSetSceneVolume(scene.key, volume) },
                valueRange = 0f..2f, modifier = Modifier.weight(1f).semantics { contentDescription = "원본 소리 크기" },
            )
            TextButton(onClick = { model.editorSetSceneVolume(scene.key, if (scene.sourceVolume <= 0.001f) 1f else 0f) }) {
                Text(if (scene.sourceVolume <= 0.001f) "소리 켜기" else "음소거")
            }
        }
    }
    HorizontalDivider()
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
            if (session.musicTrack != null) TextButton(onClick = { model.editorSetMusic(null, TrailerMusicPolicy.LOOP_MUSIC, 1) }) { Text("제거") }
            Button(onClick = onOpenMusicPicker) { Text(if (session.musicTrack != null) "음악 변경" else "음악 선택") }
        }
    }
    if (session.musicTrack != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TrailerChoice(
                if (session.musicPolicy == TrailerMusicPolicy.FIT_MUSIC_LENGTH && session.musicFitCount > 1) "${session.musicFitCount}번 맞추기" else "음악 길이에 맞추기",
                session.musicPolicy == TrailerMusicPolicy.FIT_MUSIC_LENGTH, Modifier.weight(1f),
            ) {
                val count = if (session.musicPolicy == TrailerMusicPolicy.FIT_MUSIC_LENGTH) session.musicFitCount + 1 else 1
                model.editorSetMusic(session.musicTrack, TrailerMusicPolicy.FIT_MUSIC_LENGTH, count)
            }
            TrailerChoice("음악 반복", session.musicPolicy == TrailerMusicPolicy.LOOP_MUSIC, Modifier.weight(1f)) {
                model.editorSetMusic(session.musicTrack, TrailerMusicPolicy.LOOP_MUSIC, 1)
            }
            TrailerChoice("한 번만", session.musicPolicy == TrailerMusicPolicy.SINGLE, Modifier.weight(1f)) {
                model.editorSetMusic(session.musicTrack, TrailerMusicPolicy.SINGLE, 1)
            }
        }
        if (project.autoPlan.not() && session.musicPolicy == TrailerMusicPolicy.FIT_MUSIC_LENGTH) {
            Text("직접 편집한 장면은 그대로 두고, 음악을 영상 길이에 맞춰 자릅니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Text("원본 소리가 나올 때 음악을 자동으로 줄입니다 (장면 소리 크기 반영)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                TextButton(onClick = { model.editorRecheckMusic() }) { Text("다시 확인") }
                Button(onClick = onReselect) { Text("다시 고르기") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { model.editorSetMusic(null, TrailerMusicPolicy.LOOP_MUSIC, 1) }) { Text("선택 해제") }
            }
        }
    }
}

/** Export was requested with an unresolved stored music choice: resolve it, or explicitly export without it. */
@Composable
private fun MissingMusicExportDialog(session: TrailerSession, model: NasFinderViewModel) {
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
                    OutlinedButton(onClick = { model.editorRecheckMusic() }) { Text("다시 확인") }
                    OutlinedButton(onClick = { showPicker = true }) { Text("다른 음악 고르기") }
                }
            }
        },
        confirmButton = {
            if (!session.isMusicOverlayMode) {
                TextButton(onClick = model::exportEditorProjectWithoutUnavailableMusic) { Text("음악 없이 만들기") }
            }
        },
        dismissButton = { TextButton(onClick = model::dismissMusicExportPrompt) { Text("취소") } },
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
private fun ScreenSection(session: TrailerSession, scene: EditorScene?, source: TrailerSource?, model: NasFinderViewModel) {
    TrailerSectionTitle("이 장면의 화면 채우기")
    if (scene == null || source == null) {
        Text("장면을 선택해 주세요.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            TextButton(onClick = { model.editorSetSceneFraming(scene.key, SceneFit.FILL, 0f, 0f) }, enabled = scene.focusX != 0f || scene.focusY != 0f) { Text("가운데로") }
        } else {
            Text("장면 전체가 보이도록 빈 곳은 검은색으로 채웁니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider()
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
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .heightIn(min = 44.dp)
            .semantics {
                this.selected = selected
                contentDescription = accessibilityTitle
            },
        shape = RoundedCornerShape(10.dp),
        color = if (selected) accent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant,
        border = if (selected) BorderStroke(1.dp, accent) else null,
        contentColor = content,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
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
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .heightIn(min = 44.dp)
            .semantics { contentDescription = accessibilityLabel },
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = content,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
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

@Composable
private fun PlanSection(session: TrailerSession, model: NasFinderViewModel, onOpenMusicPicker: () -> Unit) {
    val project = session.editor ?: return
    var confirmReset by remember { mutableStateOf(false) }
    var confirmMode by remember { mutableStateOf<Boolean?>(null) }

    val totalTime = session.scenes.sumOf { it.length }
    val recDefaults = remember(session.sources) { recommendedTrailerDefaults(session.sources) }

    if (session.isMusicOverlayMode) {
        TrailerSectionTitle("전체 길이")
        Text("현재 전체 영상 길이: ${formatAdaptiveDurationTitle(totalTime)} (총 ${session.scenes.size}개 장면)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
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

        TrailerSectionTitle("전체 길이")
        Text(
            text = "${formatAdaptiveDurationTitle(displayedTotal)} (총 ${session.scenes.size}개 장면)",
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
                "모든 장면의 길이를 비율대로 늘리거나 줄여 전체 길이를 조절합니다. 범위를 벗어난 목표는 조절 가능 한계로 안전하게 맞춰집니다.",
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

    HorizontalDivider()

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
            if (session.musicTrack != null) TextButton(onClick = { model.editorSetMusic(null, TrailerMusicPolicy.LOOP_MUSIC, 1) }) { Text("제거") }
            Button(onClick = onOpenMusicPicker) { Text(if (session.musicTrack != null) "음악 변경" else "음악 선택") }
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
        OutlinedButton(onClick = { confirmReset = true }, modifier = Modifier.fillMaxWidth()) {
            Text("초기 자동 추천으로 다시 구성")
        }
    }

    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("자동 추천으로 다시 구성할까요?") },
        text = { Text("직접 고친 장면 구성이 새 추천 구성으로 바뀝니다. 실행 취소로 되돌릴 수 있고, 원본 파일은 바뀌지 않습니다.") },
        confirmButton = { TextButton(onClick = { confirmReset = false; model.editorResetToAutoPlan() }) { Text("다시 구성") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("취소") } },
    )
    confirmMode?.let { music ->
        AlertDialog(
            onDismissRequest = { confirmMode = null },
            title = { Text(if (music) "뮤직비디오로 바꿀까요?" else "예고편으로 바꿀까요?") },
            text = { Text("직접 편집한 장면 구성이 새 방식의 추천 구성으로 바뀝니다. 실행 취소로 되돌릴 수 있습니다.") },
            confirmButton = { TextButton(onClick = { confirmMode = null; model.switchTrailerMode(music, session.sources.map { it.id }) }) { Text("바꾸기") } },
            dismissButton = { TextButton(onClick = { confirmMode = null }) { Text("취소") } },
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
                "선택 ${selection.size}개 · 이 프로젝트 재료 ${session.sources.size}개 · 장면 ${session.editor?.scenes?.size ?: 0}개",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (selection.isNotEmpty()) TextButton(onClick = { selection.clear() }) { Text("선택 해제") }
                // References only; the selection stays so it can still be added to this project.
                OutlinedButton(
                    onClick = { model.requestWorkbenchAddEditorCandidates(selection.values.toList()) },
                    enabled = selection.isNotEmpty(),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("작업대에 담기") }
                Button(
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
                                TextButton(onClick = { model.retryEditorAdd(item.id) }) { Text("다시 시도") }
                                TextButton(onClick = { model.dismissEditorAdd(item.id) }) { Text("건너뛰기") }
                            }
                        }
                        EditorAddPhase.QUEUED -> TextButton(onClick = { model.dismissEditorAdd(item.id) }) { Text("건너뛰기") }
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
            DropdownMenuItem(text = { Text("장면으로 한 번 더 사용") }, onClick = { usedMenu = false; onUseAgain() })
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
            onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            PackIcon(PackGlyph.PHOTOS, null, Modifier.size(18.dp))
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
                TextButton(onClick = { connectionMenu = true }) {
                    PackIcon(PackGlyph.NAS, null, Modifier.size(18.dp))
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
                TextButton(onClick = {
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
                Button(onClick = { reload++ }) { Text("다시 시도") }
            }
            entries.isEmpty() -> FinderEmpty("이 폴더에 미디어나 하위 폴더가 없습니다.")
            else -> LazyVerticalGrid(GridCells.Adaptive(96.dp), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(entries, key = { it.path }) { entry ->
                    if (entry.isDirectory) {
                        Surface(
                            onClick = { path = entry.path },
                            modifier = Modifier.aspectRatio(1f).semantics { contentDescription = "${entry.name} 폴더 열기" },
                            shape = RoundedCornerShape(8.dp),
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
        OutlinedButton(onClick = { model.show(Screen.WebBrowser) }) {
            PackIcon(PackGlyph.BROWSER, null, Modifier.size(18.dp))
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

@Composable
private fun CompleteToCollectionDialog(
    session: TrailerSession,
    output: File,
    onDismiss: () -> Unit,
    onConfirm: (String, File?) -> Unit,
) {
    var title by remember { mutableStateOf(session.title ?: trailerTimestampName(session.isMusicOverlayMode)) }
    var chosen by remember { mutableStateOf<Bitmap?>(null) }
    var writing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("컬렉션에 보관") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("완성된 영상의 사본을 컬렉션에 보관합니다. 편집 중인 프로젝트는 그대로 남습니다.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(title, { title = it }, label = { Text("영상 제목") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                PosterCandidatePicker(videoFile = output, selected = chosen, onSelect = { chosen = it })
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() && !writing,
                onClick = {
                    val frame = chosen
                    if (frame == null) { onConfirm(title, null); return@TextButton }
                    writing = true
                    scope.launch {
                        val file = withContext(Dispatchers.IO) {
                            runCatching {
                                File(session.directory, "poster-candidate-${UUID.randomUUID()}.jpg").also { f ->
                                    f.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                                }
                            }.getOrNull()
                        }
                        writing = false
                        onConfirm(title, file)
                    }
                },
            ) { Text("보관") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/** Shared choice button (iOS TrailerChoiceButton look). */
@Composable
internal fun TrailerChoice(title: String, selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, click: () -> Unit) {
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
        Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (selected) Icon(Icons.Default.Check, null, Modifier.size(11.dp))
        }
    }
}

@Composable
internal fun TrailerSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
}
