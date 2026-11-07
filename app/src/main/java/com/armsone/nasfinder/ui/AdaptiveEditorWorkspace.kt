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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.graphics.luminance
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
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
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
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
private enum class InspectorTab(val title: String) { SCENE("클립"), CAPTION("자막·구간"), SOUND("음악"), SCREEN("화면"), PLAN("클립") }

/**
 * Two screens of one project, without visible navigation tabs: editing (the draft is saved from the header) and the
 * made video (rendering progress → whole-video player → 저장·공유). EXPORT opens only from the edit footer's
 * 내보내기 (after the draft save succeeds, or straight to the current video); back always returns to the same editing view.
 */
private enum class EditorPhase { EDIT, EXPORT }

/** Portrait tablet / TriFold: the one switch under the fixed preview (클립 board or 설정 tabs). UI-only, survives rotation. */
private enum class PortraitPane(val title: String) { CLIPS("클립"), SETTINGS("설정") }

/** Tablet / board display mode: 자세히 (detail), 썸네일 (thumbnail), 포스터 (poster). UI-only, survives rotation. */
internal enum class BoardDisplayMode(val title: String) {
    DETAIL("자세히"),
    THUMBNAIL("썸네일"),
    POSTER("포스터"),
}

/** Tablet landscape placement: arrangement of preview, settings and clips board. UI-only, survives rotation. */
private enum class LandscapePlacement(val title: String) {
    PREVIEW_LEFT("미리보기 좌측"),
    CLIPS_LEFT("클립 좌측"),
    SETTINGS_LEFT("설정 좌측"),
}

// Very short scenes (e.g. near-duplicate photos at 1% weight) would otherwise all read "0.0초".
private fun seconds1(value: Double): String = if (value > 0.0 && value < 0.095) "%.2f초".format(value) else "%.1f초".format(value)

// Timeline transport clock in the iPhone style (m:ss).
private fun timelineClock(seconds: Double): String {
    val whole = seconds.coerceAtLeast(0.0).toInt()
    return "${whole / 60}:${(whole % 60).toString().padStart(2, '0')}"
}

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
    // UI-only return point of 설정으로 ↓ → 클립으로 ↑ (0 until the first jump); never part of the project, revision or undo.
    var settingsReturnIndex by rememberSaveable(directoryKey) { mutableIntStateOf(0) }
    var settingsReturnOffset by rememberSaveable(directoryKey) { mutableIntStateOf(0) }
    var moveTargetDialogKeys by remember { mutableStateOf<Set<Long>?>(null) }
    // Unapplied 자막 form of one scene (preview only until 적용); survives rotation, never moves to another scene.
    var captionDraft by rememberSaveable(directoryKey, stateSaver = CaptionDraftSaver) { mutableStateOf<CaptionDraft?>(null) }
    val coroutineScope = rememberCoroutineScope()
    var finderOpen by rememberSaveable(directoryKey) { mutableStateOf(false) }
    var inspectorTab by rememberSaveable(directoryKey) { mutableStateOf(InspectorTab.SCENE) }
    var portraitPane by rememberSaveable(directoryKey) { mutableStateOf(PortraitPane.CLIPS) }
    val portraitSettingsScroll = rememberScrollState()
    var landscapePlacement by rememberSaveable(directoryKey) { mutableStateOf(LandscapePlacement.CLIPS_LEFT) }
    var boardDisplayMode by rememberSaveable(directoryKey) { mutableStateOf(BoardDisplayMode.DETAIL) }
    // UI-only left/right column width ratio of the landscape workspace (as on Mac); kept across rotation and placement changes.
    var landscapeSplitRatio by rememberSaveable { mutableFloatStateOf(0.5f) }
    val landscapeLeftScroll = rememberScrollState()
    val landscapeCenterScroll = rememberScrollState()
    val landscapeRightScroll = rememberScrollState()
    // Entering a project always starts at 편집; a render finishing never moves the user to another view.
    var phase by rememberSaveable(directoryKey) { mutableStateOf(EditorPhase.EDIT) }
    var showMusicPicker by rememberSaveable(directoryKey) { mutableStateOf(false) }
    // UI-only preview size (1 = default); never part of the project, revision or undo.
    var previewScale by rememberSaveable { mutableFloatStateOf(1f) }
    // Clip play/pause and position live here so moving the preview between layouts never restarts the clip.
    val clipPlayback = remember(directoryKey) { ClipPlaybackState() }
    // UI-only clips per row (phone / two-pane board); never part of the project, revision or undo.
    var phoneColumns by rememberSaveable { mutableIntStateOf(3) }
    var tabletColumns by rememberSaveable { mutableIntStateOf(4) }
    var renameOpen by remember { mutableStateOf(false) }
    var exportSheet by remember { mutableStateOf(false) }
    var editingFullscreen by rememberSaveable(directoryKey) { mutableStateOf(false) }
    val previewPrepState = remember(directoryKey) { TrailerPreviewPreparationState() }
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
    val editingFullscreenAllowed = phase == EditorPhase.EDIT && project.scenes.isNotEmpty() && !rendering
    LaunchedEffect(editingFullscreen, editingFullscreenAllowed) { if (editingFullscreen && !editingFullscreenAllowed) editingFullscreen = false }
    // A cancelled render (or one outdated by edits) leaves nothing to show: the made-video screen returns to 편집.
    val resultEmpty = !rendering && session.export?.error == null && !outputCurrent
    LaunchedEffect(phase, resultEmpty) { if (phase == EditorPhase.EXPORT && resultEmpty) phase = EditorPhase.EDIT }
    LaunchedEffect(saveReady) { if (!saveReady) exportSheet = false }
    // An unapplied 자막 form (its scene changed, or deleted) is never carried silently into the whole video.
    val captionPending = captionDraft?.let { draft ->
        val owner = project.scene(draft.sceneKey)
        owner == null || draft.caption.normalizedOrNull() != owner.caption?.normalizedOrNull()
    } == true
    // In-progress 자막 autosave (as on Mac): a short pause after typing writes the draft through commitSceneCaption, but only
    // for a draft whose scope is exactly its own scene. Bulk scopes keep the explicit 적용. The effect restarts on every
    // keystroke and re-runs only when the draft differs from the project, so a committed draft never loops. A refused
    // commit keeps the form (same as 편집본 저장); the draft's own scene key is used, never the current selection.
    LaunchedEffect(captionDraft) {
        val draft = captionDraft ?: return@LaunchedEffect
        if (!captionPending) return@LaunchedEffect
        delay(800)
        val live = model.state.value.trailer?.editor ?: return@LaunchedEffect
        if (live.scene(draft.sceneKey) == null) return@LaunchedEffect
        val keys = draft.commitKeys(live)
        if (keys.size != 1 || keys.first() != draft.sceneKey) return@LaunchedEffect
        // Keep typing intact: if the form changed meanwhile this effect was already cancelled; the draft stays for the next pass.
        model.commitSceneCaption(keys, draft.caption)
    }
    val usedSourceIds = project.scenes.map { it.sourceId }.toSet()
    val usedOrigins =session.sources.filter { it.id in usedSourceIds }.mapNotNull { it.originKey }.toSet()
    val originToSource = session.sources.filter { it.originKey != null }.associateBy { it.originKey!! }

    // Project timeline play: one clock over [intro card] + scenes + [outro card] (the enabled cards are timed segments,
    // as in the export). It selects each scene as it is reached (same selection a board tap makes) and starts it
    // through the shared ClipPlaybackState, so the real video/photo previews run; a card segment holds the first/last
    // scene as its selection and shows the title-card render instead. It stops on any other selection, edit-phase exit or end.
    fun selectOnly(key: Long) {
        selectedSceneKey = key
        selectedSceneKeys = setOf(key)
    }
    val timelineSegs = remember(project.scenes, project.introCard, project.outroCard) {
        timelineSegments(project.scenes, project.introCard, project.outroCard)
    }

    val (outputWidth, outputHeight) = session.format.resolveDimensions(session.scenes.map { it.source }.ifEmpty { session.sources })
    val scenesPrepSignature = remember(
        directoryKey,
        project.scenes,
        session.sources,
        outputWidth,
        outputHeight,
        project.introCard,
        project.outroCard,
    ) {
        val sceneKeys = project.scenes.map { scene ->
            val src = session.sources.firstOrNull { it.id == scene.sourceId }
            ScenePrepItemKey(
                sceneKey = scene.key,
                sourcePath = src?.file?.absolutePath ?: "",
                sourceLength = src?.file?.length() ?: 0L,
                sourceMtime = src?.file?.lastModified() ?: 0L,
                isImage = src?.isImage ?: false,
                start = scene.start,
                length = scene.length,
                fit = scene.fit,
                focusX = scene.focusX,
                focusY = scene.focusY,
                captionSignature = scene.caption?.let { CaptionBitmapCache.key(it, outputWidth, outputHeight) },
            )
        }
        val introSig = if (project.introCard.enabled) project.introCard.toJson().toString() else null
        val outroSig = if (project.outroCard.enabled) project.outroCard.toJson().toString() else null
        ProjectPrepSignature(outputWidth, outputHeight, sceneKeys, introSig, outroSig)
    }

    val cacheDir = remember(context) { File(context.cacheDir, "preview-cache-v1").apply { if (!exists()) mkdirs() } }
    val projectDraftId = session.draftId ?: session.directory.name

    LaunchedEffect(directoryKey, scenesPrepSignature) {
        val currentDir = directoryKey
        val scenes = project.scenes
        if (scenes.isEmpty()) {
            previewPrepState.finish()
            return@LaunchedEffect
        }

        val token = previewPrepState.runId + 1

        // Enabled start/end cards, in the same keys/render size (720px) the editor and fullscreen preview use.
        val cardBody = TrailerTitleCardRenderer.bodyCaptionOf(scenes.map { it.caption })
        val cardCandidates = session.scenes.map { it.source }
        val (cardW, cardH) = titleCardRenderSize(outputWidth, outputHeight, 720)
        val cardJobs = withContext(Dispatchers.IO) {
            listOf(true to project.introCard, false to project.outroCard)
                .filter { (_, card) -> card.enabled }
                .map { (isIntro, card) ->
                    val holder = if (isIntro) scenes.first() else scenes.last()
                    val holderSource = session.sources.firstOrNull { it.id == holder.sourceId }
                    val bg = TitleCardBitmapCache.background(card, isIntro, holder, holderSource, card.resolveBackgroundSource(cardCandidates))
                    val key = TitleCardBitmapCache.key(projectDraftId, card, isIntro, bg, cardW, cardH, 720, cardBody)
                    TitleCardPrepJob(card, bg, key)
                }
                .also { jobs -> jobs.forEach { TitleCardBitmapCache.lookup(cacheDir, it.key) } }
        }

        // Phase 1: Hydrate cache misses from persistent disk cache off-main without showing progress
        withContext(Dispatchers.IO) {
            for (scene in scenes) {
                if (!coroutineContext.isActive) break
                val src = session.sources.firstOrNull { it.id == scene.sourceId } ?: continue
                if (!src.file.exists()) continue

                val frameKey = PreviewFrameCache.initialFrameKey(src, scene)
                if (PreviewFrameCache.get(frameKey) == null) {
                    val pKey = PreviewFrameCache.persistentKey(projectDraftId, src, scene.start)
                    val diskFrame = PreviewFrameCache.loadFromDisk(cacheDir, pKey)
                    if (diskFrame != null) {
                        PreviewFrameCache.put(frameKey, diskFrame)
                    }
                }

                if (src.isImage) {
                    val planKey = PhotoPlanKey(
                        filePath = src.file.absolutePath,
                        lastModified = src.file.lastModified(),
                        length = src.file.length(),
                        sourceId = src.id,
                        fit = scene.fit,
                        focusX = scene.focusX,
                        focusY = scene.focusY,
                        outputWidth = outputWidth,
                        outputHeight = outputHeight,
                        duration = scene.length,
                    )
                    if (PhotoMotionPlanCache.get(planKey) == null) {
                        val pKey = planKey.persistentKey(projectDraftId, src.name)
                        val diskPlan = PhotoMotionPlanCache.loadFromDisk(cacheDir, pKey)
                        if (diskPlan != null) {
                            PhotoMotionPlanCache.put(planKey, diskPlan)
                        }
                    }
                }

                scene.caption?.let { cap ->
                    val capKey = CaptionBitmapCache.key(cap, outputWidth, outputHeight)
                    if (CaptionBitmapCache.get(capKey) == null) {
                        val diskCap = CaptionBitmapCache.loadFromDisk(cacheDir, capKey)
                        if (diskCap != null) {
                            CaptionBitmapCache.put(capKey, diskCap)
                        }
                    }
                }
            }
        }

        if (!coroutineContext.isActive || token != previewPrepState.runId + 1) return@LaunchedEffect

        val uncachedScenes = scenes.filter { scene ->
            val src = session.sources.firstOrNull { it.id == scene.sourceId } ?: return@filter true
            if (!src.file.exists()) return@filter true
            val frameCached = PreviewFrameCache.get(PreviewFrameCache.initialFrameKey(src, scene)) != null
            val planCached = if (src.isImage) {
                val key = PhotoPlanKey(
                    filePath = src.file.absolutePath,
                    lastModified = src.file.lastModified(),
                    length = src.file.length(),
                    sourceId = src.id,
                    fit = scene.fit,
                    focusX = scene.focusX,
                    focusY = scene.focusY,
                    outputWidth = outputWidth,
                    outputHeight = outputHeight,
                    duration = scene.length,
                )
                PhotoMotionPlanCache.get(key) != null
            } else true
            val captionCached = scene.caption?.let { cap ->
                CaptionBitmapCache.get(CaptionBitmapCache.key(cap, outputWidth, outputHeight)) != null
            } ?: true
            !frameCached || !planCached || !captionCached
        }

        val missingCards = cardJobs.filter { TitleCardBitmapCache.get(it.key) == null }

        if (uncachedScenes.isEmpty() && missingCards.isEmpty()) {
            previewPrepState.preparedDirectoryKey = currentDir
            previewPrepState.finish(token)
            return@LaunchedEffect
        }

        previewPrepState.reset(uncachedScenes.size + missingCards.size, currentDir, token)

        for (scene in uncachedScenes) {
            if (!coroutineContext.isActive || session.directory.absolutePath != currentDir || token != previewPrepState.runId) break
            var sceneSuccess = true
            var failureReason: String? = null
            try {
                val src = session.sources.firstOrNull { it.id == scene.sourceId }
                if (src == null || !src.file.exists()) {
                    sceneSuccess = false
                    failureReason = "재료 파일 없음"
                } else {
                    val frameKey = PreviewFrameCache.initialFrameKey(src, scene)
                    val framePersistentKey = PreviewFrameCache.persistentKey(projectDraftId, src, scene.start)
                    if (PreviewFrameCache.get(frameKey) == null) {
                        val frame = withContext(Dispatchers.IO) {
                            PreviewFrameCache.loadFromDisk(cacheDir, framePersistentKey)
                                ?: loadFrame(src.file, src.isImage, scene.start, 960, exact = true)
                        }
                        if (frame != null) {
                            PreviewFrameCache.put(frameKey, frame)
                            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                                PreviewFrameCache.saveToDisk(cacheDir, framePersistentKey, frame, src.isImage)
                            }
                        } else {
                            sceneSuccess = false
                            failureReason = "프레임 로드 실패"
                        }
                    }

                    if (src.isImage && sceneSuccess) {
                        val planKey = PhotoPlanKey(
                            filePath = src.file.absolutePath,
                            lastModified = src.file.lastModified(),
                            length = src.file.length(),
                            sourceId = src.id,
                            fit = scene.fit,
                            focusX = scene.focusX,
                            focusY = scene.focusY,
                            outputWidth = outputWidth,
                            outputHeight = outputHeight,
                            duration = scene.length,
                        )
                        val planPersistentKey = planKey.persistentKey(projectDraftId, src.name)
                        if (PhotoMotionPlanCache.get(planKey) == null) {
                            val diskPlan = withContext(Dispatchers.IO) {
                                PhotoMotionPlanCache.loadFromDisk(cacheDir, planPersistentKey)
                            }
                            val plan = diskPlan ?: withContext(Dispatchers.Default) {
                                TrailerPhotoMotionPlanner.planMotion(
                                    source = src,
                                    fit = scene.fit,
                                    focusX = scene.focusX,
                                    focusY = scene.focusY,
                                    outputWidth = outputWidth,
                                    outputHeight = outputHeight,
                                    durationSeconds = scene.length,
                                )
                            }
                            PhotoMotionPlanCache.put(planKey, plan)
                            if (diskPlan == null) {
                                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                                    PhotoMotionPlanCache.saveToDisk(cacheDir, planPersistentKey, plan)
                                }
                            }
                        }
                    }

                    if (sceneSuccess) {
                        scene.caption?.let { cap ->
                            val capKey = CaptionBitmapCache.key(cap, outputWidth, outputHeight)
                            if (CaptionBitmapCache.get(capKey) == null) {
                                val diskCap = withContext(Dispatchers.IO) {
                                    CaptionBitmapCache.loadFromDisk(cacheDir, capKey)
                                }
                                val capBitmap = diskCap ?: withContext(Dispatchers.Default) {
                                    SceneCaptionRenderer.render(context, cap, outputWidth, outputHeight)
                                }
                                if (capBitmap != null) {
                                    CaptionBitmapCache.put(capKey, capBitmap)
                                    if (diskCap == null) {
                                        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                                            CaptionBitmapCache.saveToDisk(cacheDir, capKey, capBitmap)
                                        }
                                    }
                                } else {
                                    sceneSuccess = false
                                    failureReason = "자막 렌더링 실패"
                                }
                            }
                        }
                    }
                }
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                sceneSuccess = false
                failureReason = t.message ?: "미리보기 처리 실패"
            }

            if (!coroutineContext.isActive || token != previewPrepState.runId) break

            if (sceneSuccess) {
                previewPrepState.recordSuccess(token)
            } else {
                previewPrepState.recordFailure(token, failureReason)
            }
        }

        for (job in missingCards) {
            if (!coroutineContext.isActive || token != previewPrepState.runId) break
            val rendered = try {
                TitleCardBitmapCache.renderAndStore(context, cacheDir, job.key, job.card, job.bg, cardW, cardH, 720, cardBody)
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (_: Throwable) {
                null
            }
            if (!coroutineContext.isActive || token != previewPrepState.runId) break
            if (rendered != null) previewPrepState.recordSuccess(token) else previewPrepState.recordFailure(token, "시작/마지막 화면 렌더링 실패")
        }

        // Start/end card asset prewarming if practical
        if (coroutineContext.isActive && token == previewPrepState.runId && project.outroCard.enabled && project.outroCard.usesFirstOrLastImage) {
            val lastScene = scenes.lastOrNull()
            val lastSource = lastScene?.let { ls -> session.sources.firstOrNull { it.id == ls.sourceId } }
            if (lastSource != null && lastSource.file.exists()) {
                val (start, length) = clampSceneWindow(lastSource, lastScene.start, lastScene.length)
                val at = maxOf(start, start + length - 0.5)
                val outroFrameKey = PreviewFrameCache.seekFrameKey(lastSource, at)
                val outroPersistentKey = PreviewFrameCache.persistentKey(projectDraftId, lastSource, at)
                if (PreviewFrameCache.get(outroFrameKey) == null) {
                    runCatching {
                        val frame = withContext(Dispatchers.IO) {
                            PreviewFrameCache.loadFromDisk(cacheDir, outroPersistentKey)
                                ?: loadFrame(lastSource.file, lastSource.isImage, at, 960, exact = true)
                        }
                        if (frame != null) {
                            PreviewFrameCache.put(outroFrameKey, frame)
                            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                                PreviewFrameCache.saveToDisk(cacheDir, outroPersistentKey, frame, lastSource.isImage)
                            }
                        }
                    }
                }
            }
        }

        if (coroutineContext.isActive && token == previewPrepState.runId) {
            previewPrepState.preparedDirectoryKey = currentDir
            previewPrepState.finish(token)
        }
    }
    fun toggleProjectPlay() {
        val scenes = project.scenes
        if (scenes.isEmpty() || timelineSegs.isEmpty()) return
        if (clipPlayback.playing && clipPlayback.continuous) {
            clipPlayback.playing = false
            return
        }
        val current = scenes.firstOrNull { it.key == selectedSceneKey } ?: scenes.first()
        val total = timelineSegs.last().let { it.startSec + it.lengthSec }
        var at = timelineSecondsAt(timelineSegs, current.key, clipPlayback)
        if (at >= total - 0.05) at = 0.0
        seekTimeline(timelineSegs, at, clipPlayback, ::selectOnly, selectedSceneKey)
        clipPlayback.continuous = true
        clipPlayback.playing = true
    }
    val timelineSignature = timelineSegs.map { Triple(it.slot, it.scene?.key, it.lengthSec) }
    LaunchedEffect(clipPlayback.playing, clipPlayback.continuous, clipPlayback.epoch, timelineSignature) {
        if (!clipPlayback.playing || !clipPlayback.continuous) return@LaunchedEffect
        val scenes = project.scenes
        val key = clipPlayback.sceneKey
        val index = scenes.indexOfFirst { it.key == key }
        if (key == null || index < 0 || selectedSceneKey != key) {
            clipPlayback.playing = false
            clipPlayback.continuous = false
            clipPlayback.card = null
            return@LaunchedEffect
        }
        val slot = clipPlayback.card
        val lengthMs = when (slot) {
            TitleSlot.INTRO -> (project.introCard.clampedDuration() * 1000).toLong()
            TitleSlot.OUTRO -> (project.outroCard.clampedDuration() * 1000).toLong()
            null -> (scenes[index].length * 1000).toLong()
        }
        val startMs = (if (slot != null) clipPlayback.cardPositionMs else clipPlayback.positionFor(key)) ?: 0L
        val startedAt = android.os.SystemClock.uptimeMillis()
        while (isActive) {
            delay(30)
            // The user picked another clip on the board (or the preview moved on): timeline play ends there.
            if (selectedSceneKey != key) {
                clipPlayback.playing = false
                clipPlayback.continuous = false
                clipPlayback.card = null
                return@LaunchedEffect
            }
            val elapsed = startMs + (android.os.SystemClock.uptimeMillis() - startedAt)
            // A card has no player: this clock is its position.
            if (slot != null) clipPlayback.cardPositionMs = elapsed.coerceAtMost(lengthMs)
            if (elapsed >= lengthMs) break
        }
        when (slot) {
            TitleSlot.INTRO -> {
                // The start card holds the first scene; its own clip starts from 0.
                selectOnly(scenes.first().key)
                clipPlayback.seekWithin(scenes.first().key, 0L)
            }
            TitleSlot.OUTRO -> {
                clipPlayback.playing = false
                clipPlayback.continuous = false
            }
            null -> {
                val next = scenes.getOrNull(index + 1)
                if (next != null) {
                    selectOnly(next.key)
                    clipPlayback.seekWithin(next.key, 0L)
                } else if (project.outroCard.enabled) {
                    clipPlayback.positionMs = lengthMs
                    clipPlayback.seekCard(TitleSlot.OUTRO, key, 0L, lengthMs)
                } else {
                    clipPlayback.positionMs = lengthMs
                    clipPlayback.playing = false
                    clipPlayback.continuous = false
                }
            }
        }
    }
    // A card view never outlives its owner: another clip selected, or the card switched off, returns to the scene view.
    LaunchedEffect(selectedSceneKey, project.introCard.enabled, project.outroCard.enabled) {
        val slot = clipPlayback.card ?: return@LaunchedEffect
        val enabled = if (slot == TitleSlot.INTRO) project.introCard.enabled else project.outroCard.enabled
        if (!enabled || selectedSceneKey != clipPlayback.sceneKey) clipPlayback.card = null
    }
    // Fullscreen result / export screens never leave the editing timeline playing underneath.
    LaunchedEffect(phase) {
        if (phase != EditorPhase.EDIT) {
            clipPlayback.playing = false
            clipPlayback.continuous = false
        }
    }
    TimelineMusic(
        active = clipPlayback.playing && clipPlayback.continuous,
        track = session.musicTrack,
        policy = session.musicPolicy,
        volume = project.musicVolume,
        autoDucking = session.autoDucking,
        sources = session.sources,
        segments = timelineSegs,
        clipPlayback = clipPlayback,
    )

    fun shareOutput() {
        // A render of an older edit is never shared as if it were the current project.
        if (output == null || !outputCurrent) return
        // The model stages a copy in the share-only cache root off the main thread and reports failures.
        model.shareTrailerOutput()
    }

    BackHandler(enabled = !fullscreenPlayback) {
        when {
            editingFullscreen -> editingFullscreen = false
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
                    (event.key == Key.F11 || (event.isCtrlPressed && event.isShiftPressed && event.key == Key.F)) -> {
                        when {
                            editingFullscreen -> { editingFullscreen = false; true }
                            fullscreenAllowed -> { onFullscreenChange(!fullscreenPlayback); true }
                            editingFullscreenAllowed -> { editingFullscreen = true; true }
                            else -> false
                        }
                    }
                    editingFullscreen && event.key == Key.Escape -> { editingFullscreen = false; true }
                    else -> false
                }
            },
    ) {
        val workspaceWidth = maxWidth
        val workspaceIsWiderThanTall = maxWidth > maxHeight
        val tier = editorTier(maxWidth)
        // Two panes only when both stay readable (board ≥ ~500dp, preview+inspector ≥ ~250dp, height ≥ 480dp);
        // a phone in landscape or any short/narrow window uses the one-screen phone layout instead.
        val isLandscape = maxWidth > maxHeight && maxWidth >= 840.dp && maxHeight >= 480.dp
        // Classified from the full window (not the content box): a TriFold unfolded in portrait with the app sidebar open
        // leaves ~560dp of content but the window itself is a large portrait screen. Pane sizes still follow the content width.
        val windowConfiguration = LocalConfiguration.current
        val largePortrait = windowConfiguration.screenHeightDp > windowConfiguration.screenWidthDp &&
            windowConfiguration.screenWidthDp >= 600 && maxWidth >= 480.dp && maxHeight >= 560.dp
        val isPhone = !isLandscape && !largePortrait && (tier == EditorTier.FOCUSED || maxHeight < 560.dp)
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
        fun Board(fixedColumns: Int, maxColumns: Int, onColumnsChange: (Int) -> Unit, thumbnailAspect: Float, compactCells: Boolean, modifier: Modifier, page: (@Composable () -> Unit)?, forceClips: Boolean = false, footer: (@Composable () -> Unit)? = null, titleInGrid: Boolean = true) {
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
                showClips = forceClips || inspectorTab == InspectorTab.SCENE || inspectorTab == InspectorTab.PLAN,
                // Every clip board keeps its own selection/move/clone/remove/columns toolbar (as on Mac); on the phone it
                // follows the preview page inside the same lazy scroll.
                showToolbar = forceClips,
                // Phone only (page given): the clip menu opens 자막·구간 for that clip without scrolling.
                onOpenCaption = if (isPhone && page != null) {
                    { key: Long ->
                        selectedSceneKey = key
                        selectedSceneKeys = setOf(key)
                        inspectorTab = InspectorTab.CAPTION
                    }
                } else null,
                page = page,
                footer = footer,
                // 미리보기 좌측 shows its own heading (with 설정으로 ↓) above the grid, so it passes titleInGrid = false.
                showTitle = !isPhone && titleInGrid,
                macStyle = !isPhone,
                modifier = modifier,
                model = model,
                boardDisplayMode = boardDisplayMode,
                onBoardDisplayModeChange = { boardDisplayMode = it },
            )
        }

        // Most clips per row that still keep every cell at least a 48dp touch target (8dp gaps and edges).
        fun widthSafeColumns(boardWidth: Dp, cap: Int): Int = (((boardWidth - 8.dp) / 56.dp).toInt()).coerceIn(2, cap)

        @Composable
        fun Stage(paneHeight: Dp, baseShare: Float, baseMax: Dp, maxShare: Float, horizontalPadding: Dp, portraitSwitch: Boolean = false, tabsBelowPicture: Boolean = true, reservedBelow: Dp = 0.dp) {
            // paneHeight is the editing area left above the keyboard (IME insets are in the Row's padding).
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val paneWidth = (maxWidth - horizontalPadding * 2).coerceAtLeast(1.dp)
                val safePaneHeight = paneHeight.coerceAtLeast(72.dp)
                // Tablet/landscape/portrait panes are bounded: the controls under the picture (6dp gaps, 4dp stage padding, tab row,
                // transport + film timeline = 110dp, 24dp handle) are reserved first, so the picture takes only what is left and
                // the stage never overflows its pane. The phone page scrolls and keeps its unbounded sizing.
                val controlsHeight = if (isPhone) 0.dp else {
                    8.dp + (if (tabsBelowPicture) 58.dp else 0.dp) +
                        (if (imeVisible) 0.dp else 6.dp + 110.dp + 6.dp + 24.dp)
                }
                val pictureRoom = if (isPhone) safePaneHeight else (paneHeight - controlsHeight - reservedBelow).coerceAtLeast(48.dp)
                val cap = minOf((safePaneHeight * baseShare).coerceIn(72.dp, baseMax), pictureRoom)
                val tabsContent: @Composable (Modifier, List<CustomAccessibilityAction>) -> Unit = { dragModifier, resizeActions ->
                    if (portraitSwitch) {
                        PortraitPaneSwitch(portraitPane, onPane = { portraitPane = it }, resizeActions = resizeActions, modifier = dragModifier)
                    } else {
                        // Phone: the tab row ignores the resize drag so vertical swipes on it reach the page's lazy scroll;
                        // the dedicated handle under the picture still resizes.
                        InspectorTabs(inspectorTab, onTab = { inspectorTab = it }, resizeActions = resizeActions, modifier = if (isPhone) Modifier else dragModifier, connected = isPhone)
                    }
                }
                // Without tabs under the picture the resize handle stays (empty row, same drag state).
                val noTabsContent: @Composable (Modifier, List<CustomAccessibilityAction>) -> Unit = { _, _ -> }
                val tabsRow = if (tabsBelowPicture) tabsContent else noTabsContent
                EditorStage(
                    session = session,
                    scene = selectedScene,
                    source = selectedSource,
                    caption = previewCaption,
                    // The persisted previewScale is clamped here: a scale saved in a roomier layout never exceeds pictureRoom.
                    visualHeight = scaledStageHeight(session, paneWidth, cap, minOf(safePaneHeight * maxShare, pictureRoom), previewScale, imeVisible),
                    baseVisualHeight = stageVisualHeight(session, paneWidth, cap),
                    maxVisualHeight = scaledStageHeight(session, paneWidth, cap, minOf(safePaneHeight * maxShare, pictureRoom), 2f, imeVisible),
                    previewScale = previewScale,
                    onPreviewScaleChange = { previewScale = it },
                    showControls = !imeVisible,
                    inlinePlaybackAllowed = !fullscreenPlayback && !editingFullscreen,
                    pauseOnDisallow = !editingFullscreen,
                    clipPlayback = clipPlayback,
                    titleScenes = project.scenes,
                    introCard = project.introCard,
                    outroCard = project.outroCard,
                    onTogglePlay = ::toggleProjectPlay,
                    timeline = { sizeRatio ->
                        ProjectTimeline(
                            sizeRatio = sizeRatio,
                            compactPhone = isPhone,
                            session = session,
                            segments = timelineSegs,
                            selectedKey = selectedSceneKey,
                            clipPlayback = clipPlayback,
                            onSelectScene = ::selectOnly,
                            onTogglePlay = ::toggleProjectPlay,
                            onToggleFullscreen = { editingFullscreen = true },
                            fullscreenEnabled = editingFullscreenAllowed,
                        )
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding, vertical = 4.dp),
                    // 설정 좌측 places the tabs above the settings instead, so the preview alone stays on the right.
                    belowPicture = tabsRow,
                    preparationState = previewPrepState,
                )
            }
        }

        @Composable
        fun inspectorColumns(shownTab: InspectorTab): InspectorColumns =
            editorInspectorColumns(
                session = session,
                scene = selectedScene,
                source = selectedSource,
                selectedKeys = selectedSceneKeys,
                captionDraft = captionDraft,
                onCaptionDraftChange = { captionDraft = it },
                tab = shownTab,
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
                        // Edits autosave; the pending 자막 form is committed by 내보내기. Only a failed autosave offers a retry.
                        if (session.saveState == EditorSaveState.FAILED) {
                            OutlinedButton(
                                shape = EditorButtonShape,
                                onClick = ::requestDraftSave,
                                enabled = !session.busy,
                                modifier = buttonModifier.heightIn(min = 48.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                Text(saveButtonLabel, style = MaterialTheme.typography.labelLarge, color = palette.danger, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
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
                                "내보내기",
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
                    // Both orientations: the header itself shows the three roles once its width (= the pane area's) fits two panes.
                    landscapePlacement = if (!isPhone) landscapePlacement else null,
                    onLandscapePlacementChange = { landscapePlacement = it },
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
                            // Two panes (the same three roles and chooser as Mac) whenever both stay ≥ 240dp: 240 + 12 divider + 240
                            // = 492dp, in either orientation (a TriFold unfolded in portrait with the sidebar open included).
                            val twoColumns = !isPhone && maxWidth >= 492.dp
                            // Other narrow large portrait windows: fixed preview on top, 클립/설정 switch below, one column.
                            val portraitSplit = !twoColumns && largePortrait && maxHeight > maxWidth
                            // Ratio bounds follow the actual shared width: each pane ≥ 240dp, never outside the 0.3..0.7 of wide screens.
                            val paneMinShare = 240.dp / (maxWidth - 12.dp).coerceAtLeast(1.dp)
                            val minSplitRatio = max(0.3f, paneMinShare).coerceAtMost(0.5f)
                            val maxSplitRatio = min(0.7f, 1f - paneMinShare).coerceAtLeast(0.5f)
                            // On a wide page the board fills the right column only.
                            val splitRatio = landscapeSplitRatio.coerceIn(minSplitRatio, maxSplitRatio)
                            val boardShare = if (landscapePlacement == LandscapePlacement.CLIPS_LEFT) splitRatio else 1f - splitRatio
                            val boardWidth = if (twoColumns) (maxWidth - 12.dp) * boardShare else maxWidth
                            val maxColumns = widthSafeColumns(boardWidth, 6)
                            val columns = (if (isPhone) phoneColumns else tabletColumns).coerceIn(2, maxColumns)
                            // Phone: wider thumbnails at 2 columns, square once cells get narrow. Tablet: shorter/narrower aspect ~1.55.
                            val thumbAspect = if (!isPhone) 1.55f else when (columns) { 2 -> 2.2f; 3 -> 1.9f; 4 -> 1.5f; else -> 1f }
                            val compactCells = (boardWidth - 16.dp - 8.dp * (columns - 1)) / columns < 80.dp
                            // The tab body is built once, whatever the arrangement; its groups are only placed, never composed twice.
                            // The tablet/portrait settings panes have no 클립 tab: their first frame already builds 자막·구간
                            // (the LaunchedEffect in those panes then stores it), so no clip toolbar flashes.
                            val settingsPaneLayout = portraitSplit || twoColumns
                            val bodyTab = if (settingsPaneLayout && (inspectorTab == InspectorTab.SCENE || inspectorTab == InspectorTab.PLAN)) InspectorTab.CAPTION else inspectorTab
                            val body = inspectorColumns(bodyTab)
                            @Composable
                            fun PreviewAndTabs(tabsBelowPicture: Boolean = true, stageHeight: Dp = paneHeight) {
                                when {
                                    isPhone -> Stage(paneHeight = paneHeight, baseShare = 0.15f, baseMax = 96.dp, maxShare = if (imeVisible) 0.18f else 0.30f, horizontalPadding = 4.dp, tabsBelowPicture = tabsBelowPicture)
                                    // Two-pane portrait (TriFold) sizes the picture like landscape, not with the 140dp phone-ish cap.
                                    isLandscape || twoColumns -> Stage(paneHeight = stageHeight, baseShare = 0.55f, baseMax = 420.dp, maxShare = if (imeVisible) 0.42f else 0.75f, horizontalPadding = 0.dp, tabsBelowPicture = tabsBelowPicture)
                                    else -> Stage(paneHeight = paneHeight, baseShare = 0.165f, baseMax = 140.dp, maxShare = 0.30f, horizontalPadding = 4.dp, tabsBelowPicture = tabsBelowPicture)
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
                            }
                            if (portraitSplit) {
                                Column(Modifier.fillMaxSize()) {
                                    Stage(paneHeight = paneHeight, baseShare = 0.30f, baseMax = 360.dp, maxShare = if (imeVisible) 0.2f else 0.55f, horizontalPadding = 8.dp, portraitSwitch = true, reservedBelow = paneHeight * 0.52f)
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
                                    if (portraitPane == PortraitPane.CLIPS) {
                                        Board(
                                            fixedColumns = columns,
                                            maxColumns = maxColumns,
                                            onColumnsChange = { tabletColumns = it },
                                            thumbnailAspect = thumbAspect,
                                            compactCells = compactCells,
                                            modifier = Modifier.weight(1f).fillMaxWidth(),
                                            page = null,
                                            forceClips = true,
                                        )
                                    } else {
                                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(portraitSettingsScroll).padding(horizontal = 8.dp, vertical = 6.dp)) {
                                            // A legacy 클립/PLAN tab is moved to 자막·구간 here, as an effect (never during composition).
                                            LaunchedEffect(inspectorTab) {
                                                if (inspectorTab == InspectorTab.SCENE || inspectorTab == InspectorTab.PLAN) inspectorTab = InspectorTab.CAPTION
                                            }
                                            InspectorTabs(inspectorTab, onTab = { inspectorTab = it }, resizeActions = emptyList(), modifier = Modifier, settingsPane = true)
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
                            } else if (twoColumns) {
                                @Composable
                                fun SettingsColumnContent() {
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

                                // The three panes are always all present, whatever the settings tab; only their place changes.
                                // Scroll states are per role (preview / inspector; the board keeps the shared grid state).
                                @Composable
                                fun PreviewPane(paneModifier: Modifier, stageHeight: Dp) {
                                    Column(paneModifier.verticalScroll(landscapeLeftScroll).padding(horizontal = 8.dp, vertical = 6.dp)) {
                                        // The pane's own 6dp vertical padding and the 1dp divider under the stage are not available to it.
                                        PreviewAndTabs(tabsBelowPicture = false, stageHeight = stageHeight - 13.dp)
                                    }
                                }

                                // Natural-height preview (no scroll of its own) for a column that scrolls as one page; the
                                // picture is sized from the whole column, so shrinking it pulls the following content up.
                                @Composable
                                fun PreviewHeader() {
                                    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                                        PreviewAndTabs(tabsBelowPicture = false, stageHeight = paneHeight - 13.dp)
                                    }
                                }

                                @Composable
                                fun BoardPane(paneModifier: Modifier, page: (@Composable () -> Unit)? = null, footer: (@Composable () -> Unit)? = null, titleInGrid: Boolean = true) {
                                    Board(
                                        fixedColumns = columns,
                                        maxColumns = maxColumns,
                                        onColumnsChange = { tabletColumns = it },
                                        thumbnailAspect = thumbAspect,
                                        compactCells = compactCells,
                                        modifier = paneModifier,
                                        page = page,
                                        footer = footer,
                                        titleInGrid = titleInGrid,
                                        forceClips = true,
                                    )
                                }

                                // Tabs + body at natural height, no scroll of its own (the caller's single scroll carries it).
                                @Composable
                                fun InspectorContent() {
                                    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                                        // A legacy 클립/PLAN tab is moved to 자막·구간 here, as an effect (never during composition).
                                        LaunchedEffect(inspectorTab) {
                                            if (inspectorTab == InspectorTab.SCENE || inspectorTab == InspectorTab.PLAN) inspectorTab = InspectorTab.CAPTION
                                        }
                                        InspectorTabs(inspectorTab, onTab = { inspectorTab = it }, resizeActions = emptyList(), modifier = Modifier, settingsPane = true)
                                        SettingsColumnContent()
                                    }
                                }

                                @Composable
                                fun InspectorPane(paneModifier: Modifier) {
                                    Column(paneModifier.verticalScroll(landscapeRightScroll)) { InspectorContent() }
                                }

                                // Shared 12dp divider between the two columns: drag changes the UI-only ratio (never the project).
                                @Composable
                                fun SplitDivider() {
                                    val density = LocalDensity.current
                                    val totalPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
                                    val dragState = rememberDraggableState { deltaPx ->
                                        landscapeSplitRatio = (landscapeSplitRatio.coerceIn(minSplitRatio, maxSplitRatio) + deltaPx / totalPx).coerceIn(minSplitRatio, maxSplitRatio)
                                    }
                                    Box(
                                        Modifier
                                            .width(12.dp)
                                            .fillMaxHeight()
                                            .draggable(state = dragState, orientation = Orientation.Horizontal)
                                            .semantics {
                                                contentDescription = "좌우 폭 조절"
                                                stateDescription = "왼쪽 ${(splitRatio * 100).roundToInt()}%"
                                                customActions = listOf(
                                                    CustomAccessibilityAction("왼쪽 넓게") {
                                                        landscapeSplitRatio = (landscapeSplitRatio.coerceIn(minSplitRatio, maxSplitRatio) + 0.05f).coerceIn(minSplitRatio, maxSplitRatio); true
                                                    },
                                                    CustomAccessibilityAction("오른쪽 넓게") {
                                                        landscapeSplitRatio = (landscapeSplitRatio.coerceIn(minSplitRatio, maxSplitRatio) - 0.05f).coerceIn(minSplitRatio, maxSplitRatio); true
                                                    },
                                                )
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Box(Modifier.width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
                                    }
                                }

                                when (landscapePlacement) {
                                    LandscapePlacement.PREVIEW_LEFT -> {
                                        Row(Modifier.fillMaxSize()) {
                                            PreviewPane(Modifier.weight(splitRatio).fillMaxHeight(), paneHeight)
                                            SplitDivider()
                                            Column(Modifier.weight(1f - splitRatio).fillMaxHeight()) {
                                                // The settings sit after every clip in the ONE grid, so a compact heading outside the grid
                                                // (not a second scroll) jumps straight to that footer; 클립 N is not repeated inside the grid.
                                                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        "클립 ${project.scenes.size}",
                                                        Modifier.weight(1f),
                                                        style = MaterialTheme.typography.titleMedium,
                                                        fontWeight = FontWeight.SemiBold,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                    TextButton(
                                                        shape = EditorButtonShape,
                                                        onClick = {
                                                            val footerIndex = (gridScrollState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                                                            // Remember the clip list position, but never overwrite it while already at the footer.
                                                            if (gridScrollState.firstVisibleItemIndex < footerIndex) {
                                                                settingsReturnIndex = gridScrollState.firstVisibleItemIndex
                                                                settingsReturnOffset = gridScrollState.firstVisibleItemScrollOffset
                                                            }
                                                            coroutineScope.launch { gridScrollState.animateScrollToItem(footerIndex) }
                                                        },
                                                        modifier = Modifier.heightIn(min = IconHitTarget),
                                                    ) { Text("설정으로 ↓", maxLines = 1) }
                                                }
                                                BoardPane(
                                                    Modifier.weight(1f).fillMaxWidth(),
                                                    titleInGrid = false,
                                                    footer = {
                                                        Column(Modifier.fillMaxWidth()) {
                                                            TextButton(
                                                                shape = EditorButtonShape,
                                                                onClick = {
                                                                    coroutineScope.launch {
                                                                        val lastIndex = (gridScrollState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                                                                        gridScrollState.scrollToItem(settingsReturnIndex.coerceIn(0, lastIndex), settingsReturnOffset.coerceAtLeast(0))
                                                                    }
                                                                },
                                                                modifier = Modifier.heightIn(min = IconHitTarget),
                                                            ) { Text("클립으로 ↑", maxLines = 1) }
                                                            InspectorContent()
                                                        }
                                                    },
                                                )
                                            }
                                        }
                                    }
                                    LandscapePlacement.CLIPS_LEFT -> {
                                        Row(Modifier.fillMaxSize()) {
                                            BoardPane(Modifier.weight(splitRatio).fillMaxHeight())
                                            SplitDivider()
                                            Column(Modifier.weight(1f - splitRatio).fillMaxHeight().verticalScroll(landscapeRightScroll)) {
                                                PreviewHeader()
                                                InspectorContent()
                                            }
                                        }
                                    }
                                    LandscapePlacement.SETTINGS_LEFT -> {
                                        Row(Modifier.fillMaxSize()) {
                                            InspectorPane(Modifier.weight(splitRatio).fillMaxHeight())
                                            SplitDivider()
                                            BoardPane(Modifier.weight(1f - splitRatio).fillMaxHeight(), page = { PreviewHeader() })
                                        }
                                    }
                                }
                            } else {
                                if (inspectorTab == InspectorTab.SCENE || inspectorTab == InspectorTab.PLAN) {
                                    // One lazy scroll: the preview/transport/tabs scroll off the top, the clips take the page.
                                    Board(
                                        fixedColumns = columns,
                                        maxColumns = maxColumns,
                                        onColumnsChange = { phoneColumns = it },
                                        thumbnailAspect = thumbAspect,
                                        compactCells = compactCells,
                                        modifier = Modifier.fillMaxSize(),
                                        page = { PreviewAndTabs() },
                                        forceClips = true,
                                    )
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

        if (editingFullscreen && editingFullscreenAllowed) {
            val handleToggle = {
                if (clipPlayback.playing) clipPlayback.playing = false else toggleProjectPlay()
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            ) {
                BoxWithConstraints(
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing),
                ) {
                    val aspect = outputAspect(session)
                    val timelineHeight = if (isPhone) 88.dp else 108.dp
                    val availableVideoHeight = (maxHeight - timelineHeight - 24.dp).coerceAtLeast(120.dp)
                    val boxWidth = minOf(maxWidth, availableVideoHeight * aspect)
                    val boxHeight = if (aspect > 0f) boxWidth / aspect else availableVideoHeight

                    // 1. Centered preview fitting remaining height above timeline
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(maxHeight - timelineHeight)
                            .padding(top = 8.dp, bottom = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .width(boxWidth)
                                .height(boxHeight),
                            contentAlignment = Alignment.Center,
                        ) {
                            EditingPreviewBox(
                                session = session,
                                scene = selectedScene,
                                source = selectedSource,
                                caption = previewCaption,
                                playbackAllowed = true,
                                pauseOnDisallow = false,
                                clipPlayback = clipPlayback,
                                titleScenes = project.scenes,
                                introCard = project.introCard,
                                outroCard = project.outroCard,
                                modifier = Modifier.fillMaxSize(),
                                timelineShown = true,
                                preparationState = previewPrepState,
                            )
                        }
                    }

                    // 2. Gesture Box covering preview area (tap toggles play, downward drag >= 120dp dismisses)
                    val dismissThresholdPx = with(LocalDensity.current) { 120.dp.toPx() }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(maxHeight - timelineHeight)
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = true)
                                    var totalDragY = 0f
                                    var isDownward = false
                                    var isDrag = false
                                    var directionLocked = false

                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) {
                                            if (!isDrag && !change.isConsumed) {
                                                handleToggle()
                                            } else if (isDownward && totalDragY >= dismissThresholdPx) {
                                                editingFullscreen = false
                                            }
                                            break
                                        }
                                        val dx = change.position.x - down.position.x
                                        val dy = change.position.y - down.position.y
                                        totalDragY = dy

                                        val dist = kotlin.math.hypot(dx, dy)
                                        if (!directionLocked && dist > viewConfiguration.touchSlop) {
                                            directionLocked = true
                                            isDrag = true
                                            if (dy > 0f && dy > kotlin.math.abs(dx)) {
                                                isDownward = true
                                            }
                                        } else if (dist > viewConfiguration.touchSlop) {
                                            isDrag = true
                                        }

                                        if (isDownward && totalDragY > 0f) {
                                            change.consume()
                                        }
                                    }
                                }
                            }
                    )

                    // 3. TopEnd exit button (accessible above gestures)
                    IconButton(
                        onClick = { editingFullscreen = false },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .size(IconHitTarget)
                            .clip(EditorButtonShape)
                            .background(Color.Black.copy(alpha = 0.55f)),
                    ) {
                        Icon(
                            Icons.Default.FullscreenExit,
                            contentDescription = "전체화면 종료",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp),
                        )
                    }

                    // 4. Bottom ProjectTimeline (scrubbing/timeline across full project, receives & consumes gestures)
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        ProjectTimeline(
                            session = session,
                            segments = timelineSegs,
                            selectedKey = selectedSceneKey,
                            clipPlayback = clipPlayback,
                            onSelectScene = ::selectOnly,
                            onTogglePlay = ::toggleProjectPlay,
                            sizeRatio = if (isPhone) 0.8f else 1f,
                            compactPhone = isPhone,
                            onToggleFullscreen = { editingFullscreen = false },
                            fullscreenEnabled = true,
                            isFullscreen = true,
                        )
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
    landscapePlacement: LandscapePlacement? = null,
    onLandscapePlacementChange: ((LandscapePlacement) -> Unit)? = null,
) {
    val palette = themePalette()
    val project = session.editor
    val saveLabel = when (session.saveState) {
        EditorSaveState.FAILED -> "편집본 저장 실패"
        else -> ""
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
            // Mac's three roles on any pane area that can hold two ≥ 240dp panes (492dp+), in both orientations. Wide headers
            // keep them inline and flexible; narrower tablets give them a full-width row under the title and undo/redo.
            val showRoles = editing && landscapePlacement != null && onLandscapePlacementChange != null && maxWidth >= 492.dp
            val rolesInline = showRoles && maxWidth >= 720.dp
            Column(Modifier.fillMaxWidth()) {
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
                        if (rolesInline && landscapePlacement != null && onLandscapePlacementChange != null) {
                            LandscapePlacementChooser(landscapePlacement, onLandscapePlacementChange, Modifier.widthIn(min = 240.dp, max = 360.dp))
                            Spacer(Modifier.width(4.dp))
                        }
                        IconButton(onClick = onUndo, enabled = session.canUndo && !session.busy, modifier = Modifier.size(IconHitTarget)) {
                            Icon(Icons.AutoMirrored.Filled.Undo, "실행 취소", Modifier.size(ToolbarActionIcon))
                        }
                        IconButton(onClick = onRedo, enabled = session.canRedo && !session.busy, modifier = Modifier.size(IconHitTarget)) {
                            Icon(Icons.AutoMirrored.Filled.Redo, "다시 실행", Modifier.size(ToolbarActionIcon))
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                }
                if (showRoles && !rolesInline && landscapePlacement != null && onLandscapePlacementChange != null) {
                    LandscapePlacementChooser(landscapePlacement, onLandscapePlacementChange, Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 6.dp))
                }
            }
        }
    }
}

/** 클립 / 미리보기 / 설정 roles of the two-pane workspace as one connected segmented control (Mac order). */
@Composable
private fun LandscapePlacementChooser(current: LandscapePlacement, onChange: (LandscapePlacement) -> Unit, modifier: Modifier) {
    MacSegmentedControl(
        options = listOf(
            LandscapePlacement.CLIPS_LEFT to "클립",
            LandscapePlacement.PREVIEW_LEFT to "미리보기",
            LandscapePlacement.SETTINGS_LEFT to "설정",
        ),
        selected = current,
        onSelect = onChange,
        modifier = modifier,
    )
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

/**
 * Immutable key parameters identifying preview preparation requirements for one scene.
 */
internal data class ScenePrepItemKey(
    val sceneKey: Long,
    val sourcePath: String,
    val sourceLength: Long,
    val sourceMtime: Long,
    val isImage: Boolean,
    val start: Double,
    val length: Double,
    val fit: SceneFit,
    val focusX: Float,
    val focusY: Float,
    val captionSignature: String?,
)

/**
 * Immutable project-wide preview preparation signature.
 */
internal data class ProjectPrepSignature(
    val outputWidth: Int,
    val outputHeight: Int,
    val scenes: List<ScenePrepItemKey>,
    val introSignature: String?,
    val outroSignature: String?,
)

/**
 * Key parameters for caching planned photo camera motions.
 */
internal data class PhotoPlanKey(
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
) {
    fun persistentKey(draftId: String, sourceName: String = File(filePath).name): String =
        "plan|d=$draftId|s=${sourceId}_$sourceName|len=$length|mtime=$lastModified|fit=${fit.name}|fx=$focusX|fy=$focusY|w=$outputWidth|h=$outputHeight|dur=${(duration * 100).toLong()}"
}

internal object PreviewDiskCache {
    private const val MAX_BYTES = 120L * 1024L * 1024L
    private const val MAX_FILES = 400

    fun sha256Hex(input: String): String = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        bytes.joinToString("") { "%02x".format(it) }
    }.getOrDefault(input.hashCode().toString())

    fun writeBitmapAtomically(dir: File, fileName: String, bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int) {
        if (!dir.exists()) dir.mkdirs()
        val target = File(dir, fileName)
        val temp = File(dir, "$fileName.tmp_${System.nanoTime()}")
        try {
            FileOutputStream(temp).use { fos ->
                bitmap.compress(format, quality, fos)
                fos.flush()
            }
            if (!temp.renameTo(target)) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
            trim(dir)
        } catch (_: Throwable) {
            temp.delete()
        }
    }

    fun readBitmapSafely(dir: File, fileName: String): Bitmap? {
        val file = File(dir, fileName)
        if (!file.exists() || file.length() == 0L) return null
        return try {
            BitmapFactory.decodeFile(file.absolutePath)?.also {
                file.setLastModified(System.currentTimeMillis())
            } ?: run { file.delete(); null }
        } catch (_: Throwable) {
            file.delete()
            null
        }
    }

    fun writeTextAtomically(dir: File, fileName: String, text: String) {
        if (!dir.exists()) dir.mkdirs()
        val target = File(dir, fileName)
        val temp = File(dir, "$fileName.tmp_${System.nanoTime()}")
        try {
            FileOutputStream(temp).use { fos ->
                fos.write(text.toByteArray(Charsets.UTF_8))
                fos.flush()
            }
            if (!temp.renameTo(target)) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
            trim(dir)
        } catch (_: Throwable) {
            temp.delete()
        }
    }

    fun readTextSafely(dir: File, fileName: String): String? {
        val file = File(dir, fileName)
        if (!file.exists() || file.length() == 0L) return null
        return try {
            file.setLastModified(System.currentTimeMillis())
            file.readText(Charsets.UTF_8)
        } catch (_: Throwable) {
            file.delete()
            null
        }
    }

    fun trim(dir: File, maxBytes: Long = MAX_BYTES, maxFiles: Int = MAX_FILES) {
        val files = dir.listFiles() ?: return
        if (files.size <= maxFiles && files.sumOf { it.length() } <= maxBytes) return
        val sorted = files.sortedBy { it.lastModified() }
        var totalSize = files.sumOf { it.length() }
        var count = files.size
        for (f in sorted) {
            if (count <= (maxFiles * 0.8).toInt() && totalSize <= (maxBytes * 0.8).toLong()) break
            val len = f.length()
            if (f.delete()) {
                totalSize -= len
                count--
            }
        }
    }
}

private fun photoMotionPlanToJson(plan: PhotoMotionPlan): JSONObject = JSONObject().apply {
    put("mode", plan.mode.name)
    put("cover", plan.cover)
    put("reason", plan.reason)
    val arr = JSONArray()
    for (kf in plan.keyframes) {
        val o = JSONObject().apply {
            put("time", kf.time.toDouble())
            put("centerX", kf.centerX.toDouble())
            put("centerY", kf.centerY.toDouble())
            put("width", kf.width.toDouble())
            put("pull", kf.pull.toDouble())
        }
        arr.put(o)
    }
    put("keyframes", arr)
}

private fun photoMotionPlanFromJson(obj: JSONObject): PhotoMotionPlan? = runCatching {
    val mode = PhotoMotionMode.valueOf(obj.getString("mode"))
    val cover = obj.getBoolean("cover")
    val reason = obj.optString("reason", "")
    val arr = obj.getJSONArray("keyframes")
    val keyframes = ArrayList<PhotoKeyframe>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.getJSONObject(i)
        keyframes.add(
            PhotoKeyframe(
                time = o.getDouble("time").toFloat(),
                centerX = o.getDouble("centerX").toFloat(),
                centerY = o.getDouble("centerY").toFloat(),
                width = o.getDouble("width").toFloat(),
                pull = o.optDouble("pull", 0.0).toFloat(),
            )
        )
    }
    val finite = keyframes.all {
        it.time.isFinite() && it.centerX.isFinite() && it.centerY.isFinite() && it.width.isFinite() && it.pull.isFinite()
    }
    if (keyframes.isEmpty() || !finite) null else PhotoMotionPlan(mode, keyframes, cover, reason)
}.getOrNull()

/** Bounded LRU cache of 960px preview frames for scenes and seeks (max 24MB in-memory tier + bounded disk tier). */
internal object PreviewFrameCache {
    private val cache = object : android.util.LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun initialFrameKey(source: TrailerSource, scene: EditorScene): String {
        val file = source.file
        return "${file.absolutePath}|${file.lastModified()}|${file.length()}|${source.isImage}|${(scene.start * 10).toLong()}"
    }

    fun seekFrameKey(source: TrailerSource, atSeconds: Double): String {
        val file = source.file
        return "${file.absolutePath}|${file.lastModified()}|${file.length()}|${source.isImage}|${(atSeconds * 10).toLong()}"
    }

    fun persistentKey(draftId: String, source: TrailerSource, atSeconds: Double): String {
        val file = source.file
        return "frame|d=$draftId|s=${source.id}_${source.name}|len=${file.length()}|mtime=${file.lastModified()}|img=${source.isImage}|t=${(atSeconds * 10).toLong()}|dim=960"
    }

    @Synchronized
    fun get(key: String): Bitmap? = cache.get(key)?.takeIf { !it.isRecycled }

    @Synchronized
    fun put(key: String, bitmap: Bitmap) {
        if (!bitmap.isRecycled) {
            cache.put(key, bitmap)
        }
    }

    fun loadFromDisk(dir: File, persistentKey: String): Bitmap? {
        val fileName = "frame_" + PreviewDiskCache.sha256Hex(persistentKey) + ".dat"
        return PreviewDiskCache.readBitmapSafely(dir, fileName)
    }

    fun saveToDisk(dir: File, persistentKey: String, bitmap: Bitmap, isImage: Boolean) {
        val fileName = "frame_" + PreviewDiskCache.sha256Hex(persistentKey) + ".dat"
        val format = if (isImage && bitmap.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        PreviewDiskCache.writeBitmapAtomically(dir, fileName, bitmap, format, 90)
    }
}

/** Cache of planned photo motion keyframes per photo parameter set (in-memory LRU tier + JSON disk tier). */
internal object PhotoMotionPlanCache {
    private val cache = android.util.LruCache<PhotoPlanKey, PhotoMotionPlan>(120)

    @Synchronized
    fun get(key: PhotoPlanKey): PhotoMotionPlan? = cache.get(key)

    @Synchronized
    fun put(key: PhotoPlanKey, plan: PhotoMotionPlan) {
        cache.put(key, plan)
    }

    fun loadFromDisk(dir: File, persistentKey: String): PhotoMotionPlan? {
        val fileName = "plan_" + PreviewDiskCache.sha256Hex(persistentKey) + ".json"
        val text = PreviewDiskCache.readTextSafely(dir, fileName) ?: return null
        return try {
            photoMotionPlanFromJson(JSONObject(text))
        } catch (_: Throwable) {
            null
        }
    }

    fun saveToDisk(dir: File, persistentKey: String, plan: PhotoMotionPlan) {
        val fileName = "plan_" + PreviewDiskCache.sha256Hex(persistentKey) + ".json"
        if (photoMotionPlanFromJson(runCatching { photoMotionPlanToJson(plan) }.getOrNull() ?: return) == null) return
        val json = photoMotionPlanToJson(plan).toString()
        PreviewDiskCache.writeTextAtomically(dir, fileName, json)
    }
}

/** Bounded cache of rendered caption overlays (max 8MB in-memory tier + PNG disk tier). */
internal object CaptionBitmapCache {
    private val cache = object : android.util.LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun key(caption: SceneCaption, w: Int, h: Int): String =
        "caption|${caption.toJson()}|w=$w|h=$h"

    @Synchronized
    fun get(key: String): Bitmap? = cache.get(key)?.takeIf { !it.isRecycled }

    @Synchronized
    fun put(key: String, bitmap: Bitmap) {
        if (!bitmap.isRecycled) {
            cache.put(key, bitmap)
        }
    }

    fun loadFromDisk(dir: File, key: String): Bitmap? {
        val fileName = "caption_" + PreviewDiskCache.sha256Hex(key) + ".png"
        return PreviewDiskCache.readBitmapSafely(dir, fileName)
    }

    fun saveToDisk(dir: File, key: String, bitmap: Bitmap) {
        val fileName = "caption_" + PreviewDiskCache.sha256Hex(key) + ".png"
        PreviewDiskCache.writeBitmapAtomically(dir, fileName, bitmap, Bitmap.CompressFormat.PNG, 100)
    }
}

/** Output size a title card is rendered at for [maxSide] (even dimensions, same math for editor, fullscreen and prewarm). */
private class TitleCardPrepJob(val card: TrailerTitleCard, val bg: Pair<TrailerSource, Double>?, val key: String)

private fun titleCardRenderSize(outputWidth: Int, outputHeight: Int, maxSide: Int): Pair<Int, Int> {
    val scale = maxSide.toFloat() / maxOf(outputWidth, outputHeight).coerceAtLeast(1)
    return (((outputWidth * scale).toInt() / 2) * 2).coerceAtLeast(2) to (((outputHeight * scale).toInt() / 2) * 2).coerceAtLeast(2)
}

/** Bounded cache of rendered start/end cards (16MB in-memory tier + PNG disk tier), keyed by every visual input. */
internal object TitleCardBitmapCache {
    private val cache = object : android.util.LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Background photo and frame position the card is drawn over, or null for a plain card. */
    fun background(
        card: TrailerTitleCard,
        isIntro: Boolean,
        holder: EditorScene?,
        holderSource: TrailerSource?,
        chosen: TrailerSource?,
    ): Pair<TrailerSource, Double>? {
        if (chosen != null) return chosen to 0.0
        if (!card.usesFirstOrLastImage || holder == null || holderSource == null) return null
        val (start, length) = clampSceneWindow(holderSource, holder.start, holder.length)
        return holderSource to (if (isIntro) start else maxOf(start, start + length - 0.5))
    }

    fun key(
        draftId: String,
        card: TrailerTitleCard,
        isIntro: Boolean,
        bg: Pair<TrailerSource, Double>?,
        w: Int,
        h: Int,
        maxSide: Int,
        bodyCaption: SceneCaption?,
    ): String {
        val bgPart = bg?.let { (src, at) ->
            "${src.id}_${src.name}|len=${src.file.length()}|mtime=${src.file.lastModified()}|img=${src.isImage}|t=${(at * 10).toLong()}"
        } ?: "none"
        return "card|d=$draftId|intro=$isIntro|card=${card.toJson()}|body=${bodyCaption?.toJson()}|bg=$bgPart|w=$w|h=$h|max=$maxSide"
    }

    @Synchronized
    fun get(key: String): Bitmap? = cache.get(key)?.takeIf { !it.isRecycled }

    @Synchronized
    fun put(key: String, bitmap: Bitmap) {
        if (!bitmap.isRecycled) cache.put(key, bitmap)
    }

    /** Memory, then bounded disk read (call off the main thread). */
    fun lookup(dir: File, key: String): Bitmap? {
        get(key)?.let { return it }
        val fileName = "card_" + PreviewDiskCache.sha256Hex(key) + ".png"
        return PreviewDiskCache.readBitmapSafely(dir, fileName)?.also { put(key, it) }
    }

    /** Renders a miss, then stores it in memory and on disk. */
    suspend fun renderAndStore(
        context: android.content.Context,
        dir: File,
        key: String,
        card: TrailerTitleCard,
        bg: Pair<TrailerSource, Double>?,
        w: Int,
        h: Int,
        maxSide: Int,
        bodyCaption: SceneCaption?,
    ): Bitmap? {
        val rendered = withContext(Dispatchers.Default) {
            val background = bg?.let { (src, at) -> loadFrame(src.file, src.isImage, at, maxSide, exact = true) }
            TrailerTitleCardRenderer.renderCardBitmap(context, card, background, w, h, bodyCaption)
        } ?: return null
        put(key, rendered)
        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
            PreviewDiskCache.writeBitmapAtomically(dir, "card_" + PreviewDiskCache.sha256Hex(key) + ".png", rendered, Bitmap.CompressFormat.PNG, 100)
        }
        return rendered
    }
}

/**
 * Project-scoped preview asset preparation state with truthful horizontal progress tracking.
 */
@Stable
internal class TrailerPreviewPreparationState {
    var runId by mutableIntStateOf(0)
    var isPreparing by mutableStateOf(false)
    var completedCount by mutableIntStateOf(0)
    var failedCount by mutableIntStateOf(0)
    var totalCount by mutableIntStateOf(0)
    var failureStatus by mutableStateOf<String?>(null)
    var preparedDirectoryKey by mutableStateOf<String?>(null)

    val progress: Float?
        get() = if (totalCount > 0) ((completedCount + failedCount).toFloat() / totalCount).coerceIn(0f, 1f) else null

    fun reset(total: Int, dirKey: String, token: Int = runId + 1) {
        runId = token
        totalCount = total
        completedCount = 0
        failedCount = 0
        failureStatus = null
        isPreparing = total > 0
        preparedDirectoryKey = dirKey
    }

    fun recordSuccess(token: Int) {
        if (token != runId) return
        completedCount = minOf(completedCount + 1, totalCount)
        if (completedCount + failedCount >= totalCount) {
            isPreparing = false
        }
    }

    fun recordFailure(token: Int, status: String? = null) {
        if (token != runId) return
        failedCount++
        if (status != null && failureStatus == null) {
            failureStatus = status
        }
        if (completedCount + failedCount >= totalCount) {
            isPreparing = false
        }
    }

    fun step() {
        completedCount = minOf(completedCount + 1, totalCount)
        if (completedCount + failedCount >= totalCount) {
            isPreparing = false
        }
    }

    fun finish(token: Int = runId) {
        if (token != runId) return
        isPreparing = false
    }
}

@Composable
private fun PreviewProgressOverlay(
    loading: Boolean,
    progress: Float? = null,
    statusText: String = "미리보기 준비 중…",
    modifier: Modifier = Modifier,
) {
    if (!loading) return
    Box(
        modifier = modifier
            .padding(8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.75f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
            )
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .width(120.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(1.5.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.White.copy(alpha = 0.25f),
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .width(96.dp)
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.White.copy(alpha = 0.25f),
                )
            }
        }
    }
}

@Composable
private fun EditingPreviewBox(
    session: TrailerSession,
    scene: EditorScene?,
    source: TrailerSource?,
    caption: SceneCaption?,
    playbackAllowed: Boolean,
    pauseOnDisallow: Boolean,
    clipPlayback: ClipPlaybackState,
    titleScenes: List<EditorScene>,
    introCard: TrailerTitleCard,
    outroCard: TrailerTitleCard,
    modifier: Modifier = Modifier,
    timelineShown: Boolean = true,
    preparationState: TrailerPreviewPreparationState? = null,
) {
    val cardSlot = clipPlayback.card?.takeIf { scene != null && clipPlayback.sceneKey == scene.key }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (cardSlot != null) {
            val isIntro = cardSlot == TitleSlot.INTRO
            val card = if (isIntro) introCard else outroCard
            val holder = if (isIntro) titleScenes.firstOrNull() else titleScenes.lastOrNull()
            val dims = rememberOutputDims(session)
            val cardState = rememberTitleCardBitmap(
                card, isIntro, holder, holder?.let { h -> session.sources.firstOrNull { it.id == h.sourceId } }, dims.first, dims.second, 720,
                bodyCaption = TrailerTitleCardRenderer.bodyCaptionOf(titleScenes.map { it.caption }),
                backgroundCandidates = session.scenes.map { it.source },
                draftId = session.draftId ?: session.directory.name,
            )
            TitleCardPreview(
                bitmap = cardState.value,
                loading = cardState.isLoading,
                label = (if (isIntro) "시작 화면" else "마지막 화면") + " · ${seconds1(card.clampedDuration())}",
                aspect = outputAspect(session),
                modifier = Modifier.fillMaxSize(),
            )
        } else if (scene != null && source != null) {
            ScenePreview(
                session = session,
                scene = scene,
                source = source,
                caption = caption,
                playbackAllowed = playbackAllowed,
                clipPlayback = clipPlayback,
                modifier = Modifier.fillMaxSize(),
                timelineShown = timelineShown,
                pauseOnDisallow = pauseOnDisallow,
                preparationState = preparationState,
            )
        } else {
            Text("클립을 선택하거나 재료를 추가해 주세요.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (preparationState != null && preparationState.isPreparing) {
            val status = if (preparationState.failedCount > 0) {
                "미리보기 준비 중 (${preparationState.completedCount}/${preparationState.totalCount}, 실패 ${preparationState.failedCount})"
            } else {
                "미리보기 준비 중 (${preparationState.completedCount}/${preparationState.totalCount})"
            }
            PreviewProgressOverlay(
                loading = true,
                progress = preparationState.progress,
                statusText = status,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
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
    pauseOnDisallow: Boolean = true,
    clipPlayback: ClipPlaybackState,
    // Scenes and cards for the timeline's start/end card segments (shown in place of the scene while one is active).
    titleScenes: List<EditorScene> = emptyList(),
    introCard: TrailerTitleCard = TrailerTitleCard(),
    outroCard: TrailerTitleCard = TrailerTitleCard(),
    onTogglePlay: (() -> Unit)? = null,
    // Connected project timeline row between the picture and the tabs; hidden while typing.
    // It receives the shown picture height relative to its default (1 = default), so the whole preview group shrinks together.
    timeline: (@Composable (sizeRatio: Float) -> Unit)? = null,
    // Row directly under the picture (the four inspector tabs); it receives the resize drag modifier and the
    // TalkBack resize actions, both empty while typing.
    belowPicture: (@Composable (dragModifier: Modifier, resizeActions: List<CustomAccessibilityAction>) -> Unit)? = null,
    preparationState: TrailerPreviewPreparationState? = null,
) {
    // 편집 stage: the selected scene only (the whole video lives in 영상 만들기). The picture has its own bounded
    // height and the tab row under it doubles as its resize handle. While typing (showControls=false) only the
    // picture and the tabs stay, without resizing.
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(visualHeight), contentAlignment = Alignment.Center) {
            if (inlinePlaybackAllowed) {
                EditingPreviewBox(
                    session = session,
                    scene = scene,
                    source = source,
                    caption = caption,
                    playbackAllowed = inlinePlaybackAllowed,
                    pauseOnDisallow = pauseOnDisallow,
                    clipPlayback = clipPlayback,
                    titleScenes = titleScenes,
                    introCard = introCard,
                    outroCard = outroCard,
                    modifier = Modifier.fillMaxSize(),
                    timelineShown = timeline != null && showControls,
                    preparationState = preparationState,
                )
                if (onTogglePlay != null && scene != null && showControls) {
                    val handleToggle = {
                        if (clipPlayback.playing) clipPlayback.playing = false else onTogglePlay()
                    }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures { handleToggle() }
                            }
                    )
                    val isPlaying = clipPlayback.playing
                    IconButton(
                        onClick = handleToggle,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp)
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f)),
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "일시정지" else "재생",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
        // Ratio of the height actually shown (already clamped by the pane), not the persisted scale.
        if (timeline != null && showControls) timeline(if (baseVisualHeight > 0.dp) visualHeight / baseVisualHeight else 1f)
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
                    Modifier.draggable(dragState, Orientation.Vertical, onDragStarted = { dragHeight = currentVisual })
                        .pointerInput(minHeight, maxHeight) {
                            // Double tap toggles min/max without consuming anything, so tab taps and drags still work.
                            var lastTapUptime = 0L
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
                                val up = waitForUpOrCancellation(PointerEventPass.Final)
                                if (up != null && (up.position - down.position).getDistance() < viewConfiguration.touchSlop) {
                                    if (up.uptimeMillis - lastTapUptime <= 300L) {
                                        lastTapUptime = 0L
                                        dragHeight = currentVisual
                                        applyHeight(if (currentVisual - minHeight < maxHeight - currentVisual) maxHeight else minHeight)
                                    } else lastTapUptime = up.uptimeMillis
                                } else lastTapUptime = 0L
                            }
                        },
                    listOf(
                        CustomAccessibilityAction("미리보기 크게") { dragHeight = currentVisual; applyHeight(currentVisual + step); true },
                        CustomAccessibilityAction("미리보기 작게") { dragHeight = currentVisual; applyHeight(currentVisual - step); true },
                    ),
                )
                // Dedicated handle: drag to resize, double tap toggles min/max (the tab-row gesture is not discoverable).
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 24.dp)
                        .draggable(dragState, Orientation.Vertical, onDragStarted = { dragHeight = currentVisual })
                        .pointerInput(minHeight, maxHeight) {
                            detectTapGestures(onDoubleTap = {
                                dragHeight = currentVisual
                                applyHeight(if (currentVisual - minHeight < maxHeight - currentVisual) maxHeight else minHeight)
                            })
                        }
                        .semantics { contentDescription = "미리보기 크기 조절 핸들. 두 번 탭하면 크기를 바꿉니다" },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.width(40.dp).height(4.dp).background(MaterialTheme.colorScheme.outline, CircleShape))
                }
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
 * Clip playback owned by the workspace, not by whichever layout branch shows the preview, so play/pause and the
 * elapsed position (relative to the clipped range) survive portrait/landscape/resize relocation of the preview.
 */
@Stable
internal class ClipPlaybackState {
    var sceneKey by mutableStateOf<Long?>(null)
    var playing by mutableStateOf(false)
    var positionMs by mutableLongStateOf(0L)
    var seekRequestMs by mutableStateOf<Long?>(null)
    // True while the project timeline plays through the scenes in order; any single-clip control turns it off
    // (those loop the one clip as before).
    var continuous by mutableStateOf(false)
    // Set while the timeline is on its start/end card: the card (not [sceneKey]'s clip) is shown, [cardPositionMs] is
    // its elapsed time, and [sceneKey] holds the first/last scene so the clip selection never changes.
    var card by mutableStateOf<TitleSlot?>(null)
    var cardPositionMs by mutableLongStateOf(0L)

    fun isPlaying(key: Long) = playing && sceneKey == key && card == null
    fun positionFor(key: Long) = if (sceneKey == key) positionMs else 0L
    fun toggle(key: Long) {
        continuous = false
        card = null
        if (sceneKey != key) { sceneKey = key; positionMs = 0L; playing = true } else playing = !playing
    }
    fun seek(key: Long, posMs: Long) {
        continuous = false
        seekWithin(key, posMs)
    }

    /** Timeline move onto a card: [key] is the scene held for selection, [scenePosMs] the position that scene keeps. */
    fun seekCard(slot: TitleSlot, key: Long, posMs: Long, scenePosMs: Long, byUser: Boolean = false) {
        sceneKey = key
        positionMs = scenePosMs
        seekRequestMs = null
        card = slot
        cardPositionMs = posMs
        epoch++
        if (byUser) userEpoch++
    }
    fun restart(key: Long) {
        seek(key, 0L)
        playing = true
    }

    /** Timeline seek/advance: moves to [posMs] of scene [key] without leaving continuous mode. */
    fun seekWithin(key: Long, posMs: Long, byUser: Boolean = false) {
        card = null
        sceneKey = key
        positionMs = posMs
        seekRequestMs = posMs
        epoch++
        if (byUser) userEpoch++
    }

    // Bumped by every timeline move (re-anchors the advance clock) and, separately, by touch seeks only (re-syncs music).
    var epoch by mutableIntStateOf(0)
    var userEpoch by mutableIntStateOf(0)
}

/** Which title card a timeline segment / the card view stands for. */
internal enum class TitleSlot { INTRO, OUTRO }

/** One timed piece of the project timeline: an enabled start/end card ([slot]) or a scene, in export order. */
private class TimelineSegment(
    val slot: TitleSlot?,
    val scene: EditorScene?,
    val startSec: Double,
    val lengthSec: Double,
    val card: TrailerTitleCard? = null,
)

/** Enabled intro card, every scene, enabled outro card, back to back (the export's order and durations). */
private fun timelineSegments(scenes: List<EditorScene>, intro: TrailerTitleCard, outro: TrailerTitleCard): List<TimelineSegment> {
    if (scenes.isEmpty()) return emptyList()
    val out = ArrayList<TimelineSegment>(scenes.size + 2)
    var at = 0.0
    if (intro.enabled) { val d = intro.clampedDuration(); out += TimelineSegment(TitleSlot.INTRO, null, at, d, intro); at += d }
    for (scene in scenes) { out += TimelineSegment(null, scene, at, scene.length); at += scene.length }
    if (outro.enabled) out += TimelineSegment(TitleSlot.OUTRO, null, at, outro.clampedDuration(), outro)
    return out
}

/** The clip a card segment holds for selection: the first scene for the start card, the last for the end card. */
private fun heldSceneKey(segments: List<TimelineSegment>, slot: TitleSlot): Long? =
    (if (slot == TitleSlot.INTRO) segments.firstOrNull { it.scene != null } else segments.lastOrNull { it.scene != null })?.scene?.key

/** Shown position (seconds from the video start) of the selected clip, or of the card while one is active. */
private fun timelineSecondsAt(segments: List<TimelineSegment>, selectedKey: Long?, clipPlayback: ClipPlaybackState): Double {
    val slot = clipPlayback.card?.takeIf { clipPlayback.sceneKey == selectedKey }
    // A null selectedKey must not match card segments (their scene is null too), and a stale card/scene falls back to 0.
    val seg = if (slot != null) segments.firstOrNull { it.slot == slot }
    else if (selectedKey == null) null
    else segments.firstOrNull { it.scene?.key == selectedKey }
    seg ?: return 0.0
    val posMs = if (slot != null) clipPlayback.cardPositionMs else clipPlayback.positionFor(seg.scene?.key ?: return 0.0)
    return seg.startSec + (posMs / 1000.0).coerceIn(0.0, seg.lengthSec)
}

/** Moves the timeline to [targetSec]: selects the owning clip (the held one for a card) and seeks scene or card. */
private fun seekTimeline(
    segments: List<TimelineSegment>,
    targetSec: Double,
    clipPlayback: ClipPlaybackState,
    onSelectScene: (Long) -> Unit,
    selectedKey: Long?,
) {
    if (segments.isEmpty()) return
    val total = segments.last().let { it.startSec + it.lengthSec }
    val t = targetSec.coerceIn(0.0, total)
    val seg = segments.lastOrNull { it.startSec <= t } ?: segments.first()
    val offsetMs = ((t - seg.startSec).coerceIn(0.0, seg.lengthSec) * 1000).toLong()
    val slot = seg.slot
    val key = (if (slot != null) heldSceneKey(segments, slot) else seg.scene?.key) ?: return
    if (key != selectedKey) onSelectScene(key)
    if (slot == null) clipPlayback.seekWithin(key, offsetMs, byUser = true)
    else clipPlayback.seekCard(slot, key, offsetMs, if (slot == TitleSlot.INTRO) 0L else (segments.last { it.scene?.key == key }.lengthSec * 1000).toLong(), byUser = true)
}

/** Output size of the whole video (what the cards are rendered at), recomputed only when the session's inputs change. */
@Composable
private fun rememberOutputDims(session: TrailerSession): Pair<Int, Int> =
    remember(session.revision, session.format, session.sources) {
        session.format.resolveDimensions(session.scenes.map { it.source }.ifEmpty { session.sources })
    }

/**
 * The exporter's start/end card render at [maxSide] px: the same source selection as the inline card editor and the
 * export (first scene's first frame / last scene's frame 0.5s before its end, only while 첫/마지막 사진 배경 is on),
 * drawn by [TrailerTitleCardRenderer].
 */
@Stable
private class TitleCardBitmapState(
    initialBitmap: Bitmap? = null,
    initialLoading: Boolean = false,
) : State<Bitmap?> {
    var bitmap by mutableStateOf(initialBitmap)
    var isLoading by mutableStateOf(initialLoading)
    override val value: Bitmap? get() = bitmap
}

@Composable
private fun rememberTitleCardBitmap(
    card: TrailerTitleCard,
    isIntro: Boolean,
    holder: EditorScene?,
    holderSource: TrailerSource?,
    outputWidth: Int,
    outputHeight: Int,
    maxSide: Int,
    active: Boolean = true,
    bodyCaption: SceneCaption? = null,
    backgroundCandidates: List<TrailerSource> = emptyList(),
    draftId: String = "",
): TitleCardBitmapState {
    val context = LocalContext.current
    val cacheDir = remember(context) { File(context.cacheDir, "preview-cache-v1").apply { if (!exists()) mkdirs() } }
    val (w, h) = titleCardRenderSize(outputWidth, outputHeight, maxSide)
    val chosen = card.resolveBackgroundSource(backgroundCandidates)
    val bg = remember(card, isIntro, holder, holderSource, chosen) {
        TitleCardBitmapCache.background(card, isIntro, holder, holderSource, chosen)
    }
    val cacheKey = remember(draftId, card, isIntro, bg, w, h, maxSide, bodyCaption, active) {
        if (active) TitleCardBitmapCache.key(draftId, card, isIntro, bg, w, h, maxSide, bodyCaption) else null
    }
    val state = remember(isIntro) {
        val hit = cacheKey?.let { TitleCardBitmapCache.get(it) }
        TitleCardBitmapState(initialBitmap = hit, initialLoading = active && hit == null)
    }

    LaunchedEffect(cacheKey) {
        if (cacheKey == null) {
            state.bitmap = null
            state.isLoading = false
            return@LaunchedEffect
        }
        try {
            val cached = TitleCardBitmapCache.get(cacheKey)
                ?: withContext(Dispatchers.IO) { TitleCardBitmapCache.lookup(cacheDir, cacheKey) }
            if (cached != null) {
                state.bitmap = cached
                return@LaunchedEffect
            }
            state.isLoading = true
            val rendered = TitleCardBitmapCache.renderAndStore(context, cacheDir, cacheKey, card, bg, w, h, maxSide, bodyCaption)
            if (coroutineContext.isActive) state.bitmap = rendered
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // failed render
        } finally {
            if (coroutineContext.isActive) {
                state.isLoading = false
            }
        }
    }
    return state
}

@Composable
private fun TitleCardPreview(
    bitmap: Bitmap?,
    label: String,
    aspect: Float,
    modifier: Modifier,
    loading: Boolean = false,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val boxWidth = minOf(maxWidth, maxHeight * aspect)
            Box(
                Modifier.width(boxWidth).aspectRatio(aspect).clip(RoundedCornerShape(10.dp)).background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                if (bitmap != null) {
                    val image = remember(bitmap) { bitmap.asImageBitmap() }
                    Image(image, contentDescription = label, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                } else if (!loading) {
                    Text(
                        "미리보기를 불러올 수 없습니다",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    label,
                    Modifier.align(Alignment.TopStart).padding(8.dp).background(Color.Black.copy(alpha = .65f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/** Small bounded cache of timeline frames (the same low-res [loadFrame] the board thumbnails use). */
private object TimelineFrameCache {
    private val cache = object : android.util.LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    fun get(key: String): Bitmap? = cache.get(key)?.takeIf { !it.isRecycled }
    fun put(key: String, bitmap: Bitmap) { cache.put(key, bitmap) }
}

/**
 * One connected row under the preview: a segment per scene (width ∝ length, photo/video tinted, selected outlined),
 * a playhead at the shown position, and touch/drag seeking. Seeking selects the scene under the finger exactly like
 * tapping it on the board. It always shows the editing state of the project, never an exported file.
 */
@Composable
private fun ProjectTimeline(
    session: TrailerSession,
    segments: List<TimelineSegment>,
    selectedKey: Long?,
    clipPlayback: ClipPlaybackState,
    onSelectScene: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    sizeRatio: Float,
    compactPhone: Boolean = false,
    onToggleFullscreen: (() -> Unit)? = null,
    fullscreenEnabled: Boolean = true,
    isFullscreen: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (segments.isEmpty()) return
    // Below the default picture size the film thins from 56dp (ratio 1) to 24dp (ratio 0.5); at compact size the clock hides.
    // Phone only: 40dp default thinning to the same 24dp minimum.
    val filmRatio = ((sizeRatio - 0.5f) / 0.5f).coerceIn(0f, 1f)
    val filmHeight = (if (compactPhone) 24f + 16f * filmRatio else 24f + 32f * filmRatio).dp
    val compact = sizeRatio <= 0.6f
    val scenes = remember(segments) { segments.mapNotNull { it.scene } }
    val totalSeconds = segments.last().let { it.startSec + it.lengthSec }.coerceAtLeast(0.1)
    val selectedIndex = scenes.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0)
    val activeSlot = clipPlayback.card?.takeIf { clipPlayback.sceneKey == selectedKey }
    val activeSegmentIndex = if (activeSlot != null) segments.indexOfFirst { it.slot == activeSlot }
    else segments.indexOfFirst { it.scene?.key == scenes[selectedIndex].key }
    val shownSeconds = timelineSecondsAt(segments, scenes[selectedIndex].key, clipPlayback)
    val playingAll = clipPlayback.playing && clipPlayback.continuous
    val sourcesById = remember(session.sources) { session.sources.associateBy { it.id } }
    var widthPx by remember { mutableIntStateOf(0) }
    val currentSegments by rememberUpdatedState(segments)
    val currentSelected by rememberUpdatedState(selectedKey)

    fun seekToFraction(fraction: Float) {
        val list = currentSegments
        val total = list.last().let { it.startSec + it.lengthSec }
        seekTimeline(list, fraction.coerceIn(0f, 1f) * total, clipPlayback, onSelectScene, currentSelected)
    }
    fun seekToScene(index: Int) {
        val scene = scenes.getOrNull(index) ?: return
        val seg = currentSegments.firstOrNull { it.scene?.key == scene.key } ?: return
        seekTimeline(currentSegments, seg.startSec, clipPlayback, onSelectScene, currentSelected)
    }

    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.Black).padding(if (compact) 3.dp else 6.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 6.dp),
    ) {
      CompositionLocalProvider(LocalContentColor provides Color.White) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Start) {
            IconButton(onClick = onTogglePlay, modifier = Modifier.size(if (compactPhone) 32.dp else if (compact) 30.dp else 36.dp)) {
                PackIcon(if (playingAll) PackGlyph.PAUSE else PackGlyph.PLAY, if (playingAll) "전체 타임라인 재생 멈춤" else "타임라인 ${seconds1(if (shownSeconds >= totalSeconds - 0.05) 0.0 else shownSeconds)}부터 이어서 재생", Modifier.size(if (compact) 18.dp else 20.dp))
            }
            if (!compact) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "${timelineClock(shownSeconds)} / ${timelineClock(totalSeconds)}",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = Color.White,
                    maxLines = 1,
                )
            }
            if (onToggleFullscreen != null) {
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = onToggleFullscreen,
                    enabled = fullscreenEnabled,
                    modifier = Modifier.size(if (compactPhone) 32.dp else if (compact) 30.dp else 36.dp),
                ) {
                    Icon(
                        if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                        contentDescription = if (isFullscreen) "전체화면 종료" else "편집 전체화면",
                        modifier = Modifier.size(if (compact) 18.dp else 20.dp),
                        tint = if (fullscreenEnabled) Color.White else Color.White.copy(alpha = 0.38f),
                    )
                }
            }
        }
      }
        Box(
            Modifier
                .fillMaxWidth()
                .height(filmHeight)
                .onSizeChangedPx { widthPx = it }
                .clip(RoundedCornerShape(6.dp))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val w = size.width.coerceAtLeast(1)
                        seekToFraction(down.position.x / w)
                        down.consume()
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            seekToFraction(change.position.x / w)
                            change.consume()
                        }
                    }
                }
                .semantics {
                    contentDescription = "프로젝트 타임라인, 클립 ${scenes.size}개, 편집 내용 기준 미리보기"
                    stateDescription = when (activeSlot) {
                        TitleSlot.INTRO -> "${seconds1(shownSeconds)} / ${seconds1(totalSeconds)}, 시작 화면"
                        TitleSlot.OUTRO -> "${seconds1(shownSeconds)} / ${seconds1(totalSeconds)}, 마지막 화면"
                        null -> "${seconds1(shownSeconds)} / ${seconds1(totalSeconds)}, ${selectedIndex + 1}번째 클립"
                    }
                    customActions = listOf(
                        CustomAccessibilityAction("이전 클립") { seekToScene(selectedIndex - 1); true },
                        CustomAccessibilityAction("다음 클립") { seekToScene(selectedIndex + 1); true },
                    )
                },
        ) {
            TimelineFilmstrip(session, segments, sourcesById, widthPx, activeSegmentIndex)
            if (widthPx > 0) {
                val playheadDp = with(LocalDensity.current) { ((shownSeconds / totalSeconds).toFloat().coerceIn(0f, 1f) * widthPx).toDp() }
                Box(
                    Modifier.offset(x = (playheadDp - 1.dp).coerceAtLeast(0.dp)).width(2.dp).fillMaxHeight().background(Color.White)
                        .border(0.5.dp, Color.Black.copy(alpha = .6f)),
                )
            }
        }
    }
}

private fun segmentIndexAt(segments: List<TimelineSegment>, t: Double): Int {
    var lo = 0
    var hi = segments.lastIndex
    while (lo < hi) {
        val mid = (lo + hi + 1) / 2
        if (segments[mid].startSec <= t) lo = mid else hi = mid - 1
    }
    return lo
}

/**
 * The timeline as a filmstrip. Segment tint and boundaries are drawn for every segment (cheap, even for hundreds of
 * scenes), but frames are sampled: a bounded number of ~64dp cells across the strip (at most 48, whatever the scene
 * count), each showing the frame of whatever segment is under its center, loaded lazily through the board's low-res
 * [loadFrame] with a small cache. The start/end cards show their own [TrailerTitleCardRenderer] render.
 */
@Composable
private fun TimelineFilmstrip(
    session: TrailerSession,
    segments: List<TimelineSegment>,
    sourcesById: Map<Int, TrailerSource>,
    widthPx: Int,
    activeIndex: Int,
) {
    val scheme = MaterialTheme.colorScheme
    val photoTint = scheme.tertiaryContainer
    val videoTint = scheme.primaryContainer
    val cardTint = scheme.secondaryContainer
    val outline = scheme.primary
    val total = segments.last().let { it.startSec + it.lengthSec }.coerceAtLeast(0.1)
    val dims = rememberOutputDims(session)
    val firstScene = segments.firstOrNull { it.scene != null }?.scene
    val lastScene = segments.lastOrNull { it.scene != null }?.scene
    val introSeg = segments.firstOrNull { it.slot == TitleSlot.INTRO }
    val outroSeg = segments.firstOrNull { it.slot == TitleSlot.OUTRO }
    val bodyCaption = TrailerTitleCardRenderer.bodyCaptionOf(segments.mapNotNull { it.scene?.caption })
    val bgCandidates = remember(session.revision, session.scenes) { session.scenes.map { it.source } }
    val introThumb by rememberTitleCardBitmap(
        introSeg?.card ?: TrailerTitleCard(), true, firstScene, firstScene?.let { sourcesById[it.sourceId] }, dims.first, dims.second, 160, active = introSeg != null,
        bodyCaption = bodyCaption, backgroundCandidates = bgCandidates, draftId = session.draftId ?: session.directory.name,
    )
    val outroThumb by rememberTitleCardBitmap(
        outroSeg?.card ?: TrailerTitleCard(), false, lastScene, lastScene?.let { sourcesById[it.sourceId] }, dims.first, dims.second, 160, active = outroSeg != null,
        bodyCaption = bodyCaption, backgroundCandidates = bgCandidates, draftId = session.draftId ?: session.directory.name,
    )
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            segments.forEach { seg ->
                val x0 = (seg.startSec / total * size.width).toFloat()
                val x1 = ((seg.startSec + seg.lengthSec) / total * size.width).toFloat()
                val color = when {
                    seg.slot != null -> cardTint
                    sourcesById[seg.scene?.sourceId]?.isImage == true -> photoTint
                    else -> videoTint
                }
                drawRect(color, Offset(x0, 0f), androidx.compose.ui.geometry.Size((x1 - x0).coerceAtLeast(0f), size.height))
            }
        }
        if (widthPx > 0) {
            val cellPx = with(LocalDensity.current) { 64.dp.toPx() }
            val cellCount = kotlin.math.ceil(widthPx / cellPx).toInt().coerceIn(1, 48)
            Row(Modifier.fillMaxSize()) {
                for (i in 0 until cellCount) {
                    key(i) {
                        val t = (i + 0.5) / cellCount * total
                        val index = segmentIndexAt(segments, t)
                        val seg = segments[index]
                        val alpha = if (index == activeIndex) 1f else .7f
                        Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                            when (seg.slot) {
                                TitleSlot.INTRO -> TimelineBitmap(introThumb, alpha)
                                TitleSlot.OUTRO -> TimelineBitmap(outroThumb, alpha)
                                null -> seg.scene?.let { scene ->
                                    FilmstripFrame(scene, sourcesById[scene.sourceId], (t - seg.startSec).coerceIn(0.0, seg.lengthSec), alpha)
                                }
                            }
                        }
                    }
                }
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            val gap = 3.dp.toPx()
            segments.forEachIndexed { i, seg ->
                val x0 = (seg.startSec / total * size.width).toFloat()
                val x1 = ((seg.startSec + seg.lengthSec) / total * size.width).toFloat()
                // Boundaries only where segments are wide enough to tell apart; hundreds of scenes stay a clean strip.
                if (i > 0 && x1 - x0 >= gap) drawLine(Color.Black.copy(alpha = .55f), Offset(x0, 0f), Offset(x0, size.height), 1.dp.toPx())
                if (i == activeIndex) {
                    val width = maxOf(x1 - x0, gap)
                    val left = (x0 - (width - (x1 - x0)) / 2f).coerceIn(0f, (size.width - width).coerceAtLeast(0f))
                    drawRect(
                        outline, Offset(left, 0f), androidx.compose.ui.geometry.Size(width, size.height),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()),
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineBitmap(bitmap: Bitmap?, alpha: Float) {
    if (bitmap == null || bitmap.isRecycled) return
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Image(image, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = alpha)
}

@Composable
private fun FilmstripFrame(scene: EditorScene, source: TrailerSource?, offsetSec: Double, alpha: Float) {
    if (source == null) return
    // Photos use one frame; videos are sampled in 0.5s steps inside the clipped range.
    val at = if (source.isImage) 0.0 else scene.start + kotlin.math.floor(offsetSec * 2) / 2.0
    val cacheKey = remember(source.file, source.isImage, at) { "${source.file.absolutePath}|${source.file.lastModified()}|${(at * 10).toLong()}" }
    val frame by produceState<Bitmap?>(TimelineFrameCache.get(cacheKey) ?: source.thumbnail, cacheKey) {
        TimelineFrameCache.get(cacheKey)?.let { value = it; return@produceState }
        if (source.isImage && value != null) return@produceState
        val loaded = withContext(Dispatchers.IO) { loadFrame(source.file, source.isImage, at, 160) }
        if (loaded != null) {
            TimelineFrameCache.put(cacheKey, loaded)
            value = loaded
        }
    }
    TimelineBitmap(frame, alpha)
}

private fun Modifier.onSizeChangedPx(onWidth: (Int) -> Unit): Modifier =
    this.then(Modifier.onGloballyPositioned { onWidth(it.size.width) })

/** Loudness envelopes per source file, bounded by total bins (LRU) so a 400-clip project stays small. */
private object TimelineLoudnessCache {
    private const val MAX_BINS = 2_000_000
    private val map = LinkedHashMap<String, FloatArray>(16, 0.75f, true)
    private var bins = 0
    private fun key(s: TrailerSource) = "${s.file.absolutePath}|${s.file.lastModified()}|${s.file.length()}|${s.duration}"
    @Synchronized fun get(s: TrailerSource): FloatArray? = map[key(s)]
    @Synchronized fun put(s: TrailerSource, env: FloatArray) {
        map.put(key(s), env)?.let { bins -= it.size }
        bins += env.size
        val it = map.entries.iterator()
        while (bins > MAX_BINS && map.size > 1 && it.hasNext()) {
            val e = it.next()
            if (e.value === env) continue
            bins -= e.value.size
            it.remove()
        }
    }
}

/**
 * Background music for the project timeline play: it starts where the timeline is (video-wide, cards included, as the
 * export mixes it), loops unless the music plays once, and stops with the timeline. Volume follows the project
 * music volume up to 100% (a player cannot go louder); with auto-ducking the gain follows the export's ducking
 * timeline; tempo shifts are not previewed.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun TimelineMusic(
    active: Boolean,
    track: TrailerMusicTrack?,
    policy: TrailerMusicPolicy,
    volume: Float,
    autoDucking: Boolean,
    sources: List<TrailerSource>,
    segments: List<TimelineSegment>,
    clipPlayback: ClipPlaybackState,
) {
    val context = LocalContext.current
    val currentSegments by rememberUpdatedState(segments)
    val file = track?.file?.takeIf { it.isFile && it.length() > 0L }
    val player = remember(file?.absolutePath) {
        file?.let {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.fromFile(it)))
                playWhenReady = false
                prepare()
            }
        }
    }
    DisposableEffect(player) { onDispose { player?.release() } }
    LaunchedEffect(player, policy) { player?.repeatMode = if (policy == TrailerMusicPolicy.SINGLE) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ALL }
    val duck = autoDucking && player != null
    LaunchedEffect(player, volume, duck) { if (!duck) player?.volume = volume.coerceIn(0f, 1f) }
    // Auto-ducking: the exporter's own loudness envelopes (0.05s bins) and AudioDuckingTimeline over the same
    // card + scene layout, analysed lazily off the main thread (upcoming sources only) and cached per source file.
    val sourcesById = remember(sources) { sources.associateBy { it.id } }
    var envVersion by remember { mutableIntStateOf(0) }
    val duckingTimeline by produceState<AudioDuckingTimeline?>(null, duck, segments, sourcesById, envVersion) {
        if (!duck) { value = null; return@produceState }
        value = withContext(Dispatchers.Default) {
            var placeholderId = -1000
            fun silent(length: Double) = TrailerScene(
                source = TrailerSource(id = placeholderId--, name = "", file = File(""), duration = length, width = 0, height = 0, analysis = AudioAnalysisResult(emptyList(), 0.0), thumbnail = null, isImage = true),
                start = 0.0, length = length,
            )
            val envelopes = HashMap<Int, FloatArray>()
            val timelineScenes = segments.map { seg ->
                val scene = seg.scene
                val source = scene?.let { sourcesById[it.sourceId] }
                if (scene == null || source == null) silent(seg.lengthSec)
                else {
                    if (!source.isImage) TimelineLoudnessCache.get(source)?.let { envelopes[source.id] = it }
                    TrailerScene(source = source, start = scene.start, length = scene.length, sourceVolume = scene.sourceVolume)
                }
            }
            AudioDuckingTimeline.buildWithLoudnessEnvelopes(timelineScenes, envelopes, binSeconds = 0.05, originalClipGain = 1.0f)
        }
    }
    LaunchedEffect(duck, active, segments, sourcesById) {
        if (!duck) return@LaunchedEffect
        val appContext = context.applicationContext
        while (isActive) {
            val position = timelineSecondsAt(currentSegments, clipPlayback.sceneKey, clipPlayback)
            // Playing: a few sources ahead; otherwise only the current one, so opening never decodes the project.
            val pending = currentSegments.asSequence()
                .filter { it.startSec + it.lengthSec >= position }
                .mapNotNull { seg -> seg.scene?.let { sourcesById[it.sourceId] } }
                .filter { !it.isImage && it.duration > 0.0 && TimelineLoudnessCache.get(it) == null }
                .distinctBy { it.id }
                .take(if (active) 3 else 1)
                .toList()
            val next = pending.firstOrNull()
            if (next == null) { delay(500); continue }
            val envelope = runCatching {
                AudioAnalysisService.extractLoudnessEnvelope(appContext, Uri.fromFile(next.file), next.duration, 0.05)
            }.getOrDefault(FloatArray(0))
            TimelineLoudnessCache.put(next, envelope)
            envVersion++
        }
    }
    // Gain follows the export's processor: target from the timeline at the shown video position, ~0.08s attack / ~0.28s release.
    LaunchedEffect(player, duck, duckingTimeline, active, volume, clipPlayback.userEpoch) {
        val p = player ?: return@LaunchedEffect
        val timeline = duckingTimeline ?: return@LaunchedEffect
        val musicGain = volume.coerceIn(0f, 2f)
        val full = 0.85f * musicGain
        val ducked = 0.18f * musicGain
        fun target() = timeline.targetMusicGainAt(timelineSecondsAt(currentSegments, clipPlayback.sceneKey, clipPlayback), ducked, full)
        var gain = target()
        p.volume = gain.coerceIn(0f, 1f)
        if (!active) return@LaunchedEffect
        var last = android.os.SystemClock.uptimeMillis()
        while (isActive) {
            delay(40)
            val now = android.os.SystemClock.uptimeMillis()
            val dt = (now - last) / 1000.0
            last = now
            val goal = target()
            val tau = if (goal < gain) 0.08 else 0.28
            gain += ((1.0 - kotlin.math.exp(-dt / tau)).toFloat()) * (goal - gain)
            p.volume = gain.coerceIn(0f, 1f)
        }
    }
    // Re-synced only when the timeline starts or the user seeks, not on every automatic scene advance.
    LaunchedEffect(player, active, clipPlayback.userEpoch) {
        val p = player ?: return@LaunchedEffect
        if (!active) { p.pause(); return@LaunchedEffect }
        // The video-wide position: start card, scenes and end card all count, so music lines up with the export.
        val atSeconds = timelineSecondsAt(currentSegments, clipPlayback.sceneKey, clipPlayback)
        val durationMs = ((track?.durationSeconds ?: 0.0) * 1000).toLong()
        var targetMs = (atSeconds * 1000).toLong()
        if (durationMs > 0L) {
            if (policy == TrailerMusicPolicy.SINGLE && targetMs >= durationMs) { p.pause(); return@LaunchedEffect }
            targetMs %= durationMs
        }
        p.seekTo(targetMs)
        p.play()
    }
}

/**
 * Framed still of the selected scene at its trim start, drawn with the scene's fit/focus in the whole-video aspect,
 * plus an optional clip player for video scenes (trimmed range, scene volume).
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun ScenePreview(
    session: TrailerSession,
    scene: EditorScene,
    source: TrailerSource,
    caption: SceneCaption?,
    playbackAllowed: Boolean,
    clipPlayback: ClipPlaybackState,
    modifier: Modifier,
    timelineShown: Boolean = false,
    pauseOnDisallow: Boolean = true,
    preparationState: TrailerPreviewPreparationState? = null,
) {
    val context = LocalContext.current
    val aspect = outputAspect(session)
    val playing = clipPlayback.isPlaying(scene.key)
    val cacheDir = remember(context) { File(context.cacheDir, "preview-cache-v1").apply { if (!exists()) mkdirs() } }
    val projectDraftId = session.draftId ?: session.directory.name
    // Fullscreen opened: the clip player leaves composition this frame (released) and play is paused, so returning
    // shows the paused preview instead of auto-resuming. The clip position and photo progress are kept.
    if (!playbackAllowed && playing && pauseOnDisallow) SideEffect { clipPlayback.playing = false }
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
    var photoPlanEntry by remember(scene.key, source.file.absolutePath, planKey) {
        mutableStateOf<Pair<PhotoPlanKey, PhotoMotionPlan>?>(
            planKey?.let { k -> PhotoMotionPlanCache.get(k)?.let { k to it } }
        )
    }
    LaunchedEffect(planKey, preparationState?.completedCount) {
        if (planKey == null) {
            photoPlanEntry = null
            return@LaunchedEffect
        }
        val cached = PhotoMotionPlanCache.get(planKey)
        if (cached != null) {
            photoPlanEntry = planKey to cached
            return@LaunchedEffect
        }
        val planPersistentKey = planKey.persistentKey(projectDraftId, source.name)
        try {
            val diskPlan = withContext(Dispatchers.IO) {
                PhotoMotionPlanCache.loadFromDisk(cacheDir, planPersistentKey)
            }
            if (diskPlan != null) {
                PhotoMotionPlanCache.put(planKey, diskPlan)
                photoPlanEntry = planKey to diskPlan
                return@LaunchedEffect
            }
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
            if (coroutineContext.isActive) {
                PhotoMotionPlanCache.put(planKey, computed)
                photoPlanEntry = planKey to computed
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    PhotoMotionPlanCache.saveToDisk(cacheDir, planPersistentKey, computed)
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // failed
        }
    }
    val photoPlan = if (photoPlanEntry?.first == planKey) photoPlanEntry?.second else (planKey?.let { PhotoMotionPlanCache.get(it) } ?: pendingPlan)
    // A photo scene entered by the timeline (or resumed) starts at the position the workspace holds for it.
    var photoPlayProgress by remember(scene.key, planKey) {
        mutableFloatStateOf(
            if (source.isImage) (clipPlayback.positionFor(scene.key).toFloat() / (scene.length * 1000).toFloat().coerceAtLeast(100f)).coerceIn(0f, 1f) else 0f,
        )
    }
    LaunchedEffect(clipPlayback.seekRequestMs, scene.key, source.isImage) {
        val target = clipPlayback.seekRequestMs ?: return@LaunchedEffect
        if (source.isImage && clipPlayback.sceneKey == scene.key) {
            photoPlayProgress = (target.toFloat() / (scene.length * 1000).toFloat().coerceAtLeast(100f)).coerceIn(0f, 1f)
            clipPlayback.seekRequestMs = null
        }
    }
    LaunchedEffect(playing, source.isImage, scene.length, planKey, scene.key) {
        if (!playing || !source.isImage) {
            return@LaunchedEffect
        }
        val durationMs = (scene.length * 1000).toLong().coerceAtLeast(100L)
        val startOffsetMs = ((photoPlayProgress.coerceIn(0f, 1f) % 1f) * durationMs).toLong()
        val startTime = android.os.SystemClock.uptimeMillis() - startOffsetMs
        while (isActive && playing) {
            val elapsed = android.os.SystemClock.uptimeMillis() - startTime
            // On the project timeline a photo plays its camera move once; alone it loops.
            val shown = if (clipPlayback.continuous) elapsed.coerceAtMost(durationMs) else elapsed % durationMs
            photoPlayProgress = (shown.toFloat() / durationMs).coerceIn(0f, 1f)
            if (clipPlayback.sceneKey == scene.key) clipPlayback.positionMs = shown
            delay(16)
        }
    }
    // A paused video shows the frame at the held position (timeline/slider seek), in 0.1s steps; a playing one uses its player.
    val isPlayerActive = playing && playbackAllowed && !source.isImage
    val frameAt = if (source.isImage || playing) scene.start
    else scene.start + (clipPlayback.positionFor(scene.key).coerceIn(0L, (scene.length * 1000).toLong()) / 100) / 10.0

    val initialFrameKey = remember(source.file.absolutePath, source.file.lastModified(), source.file.length(), scene.start, source.isImage) {
        PreviewFrameCache.initialFrameKey(source, scene)
    }
    val currentFrameKey = remember(source.file.absolutePath, source.file.lastModified(), source.file.length(), frameAt, source.isImage) {
        PreviewFrameCache.seekFrameKey(source, frameAt)
    }

    val directCached = PreviewFrameCache.get(currentFrameKey)
    val fallbackCached = if (frameAt == scene.start) PreviewFrameCache.get(initialFrameKey) else null

    var frameBitmap by remember(scene.key, source.file.absolutePath) {
        mutableStateOf(directCached ?: fallbackCached)
    }
    var frameLoadedKey by remember(scene.key, source.file.absolutePath) {
        mutableStateOf(if (directCached != null) currentFrameKey else if (fallbackCached != null) initialFrameKey else null)
    }

    LaunchedEffect(currentFrameKey, isPlayerActive, preparationState?.completedCount) {
        if (isPlayerActive) return@LaunchedEffect
        val cached = PreviewFrameCache.get(currentFrameKey) ?: if (frameAt == scene.start) PreviewFrameCache.get(initialFrameKey) else null
        if (cached != null) {
            frameBitmap = cached
            frameLoadedKey = currentFrameKey
            return@LaunchedEffect
        }
        val framePersistentKey = PreviewFrameCache.persistentKey(projectDraftId, source, frameAt)
        val initialPersistentKey = PreviewFrameCache.persistentKey(projectDraftId, source, scene.start)
        try {
            val loaded = withContext(Dispatchers.IO) {
                val diskFrame = PreviewFrameCache.loadFromDisk(cacheDir, framePersistentKey)
                    ?: if (frameAt == scene.start) PreviewFrameCache.loadFromDisk(cacheDir, initialPersistentKey) else null
                diskFrame ?: loadFrame(source.file, source.isImage, frameAt, 960, exact = true)
            }
            if (coroutineContext.isActive && loaded != null) {
                PreviewFrameCache.put(currentFrameKey, loaded)
                frameBitmap = loaded
                frameLoadedKey = currentFrameKey
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    PreviewFrameCache.saveToDisk(cacheDir, framePersistentKey, loaded, source.isImage)
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // failed
        }
    }

    var captionBitmapState by remember(scene.key, caption) {
        mutableStateOf(caption?.let { CaptionBitmapCache.get(CaptionBitmapCache.key(it, outputWidth, outputHeight)) })
    }
    LaunchedEffect(caption, outputWidth, outputHeight, preparationState?.completedCount) {
        if (caption == null) {
            captionBitmapState = null
            return@LaunchedEffect
        }
        val key = CaptionBitmapCache.key(caption, outputWidth, outputHeight)
        val cached = CaptionBitmapCache.get(key)
        if (cached != null) {
            captionBitmapState = cached
            return@LaunchedEffect
        }
        try {
            val diskCap = withContext(Dispatchers.IO) {
                CaptionBitmapCache.loadFromDisk(cacheDir, key)
            }
            if (diskCap != null) {
                CaptionBitmapCache.put(key, diskCap)
                captionBitmapState = diskCap
                return@LaunchedEffect
            }
            delay(120)
            val rendered = withContext(Dispatchers.Default) {
                SceneCaptionRenderer.render(context, caption, outputWidth, outputHeight)
            }
            if (coroutineContext.isActive && rendered != null) {
                CaptionBitmapCache.put(key, rendered)
                captionBitmapState = rendered
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    CaptionBitmapCache.saveToDisk(cacheDir, key, rendered)
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // failed render
        }
    }

    var playerBuffering by remember(scene.key) { mutableStateOf(false) }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
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
                            override fun onPlaybackStateChanged(playbackState: Int) {
                                playerBuffering = (playbackState == Player.STATE_BUFFERING)
                            }
                            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                                playerBuffering = false
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
                            // Resumes where the previous player (another layout, or before pause) stopped, inside the clip.
                            clipPlayback.positionFor(scene.key).coerceIn(0L, (scene.length * 1000).toLong()),
                        )
                        repeatMode = Player.REPEAT_MODE_ONE
                        playWhenReady = true
                        prepare()
                        playerBuffering = (playbackState == Player.STATE_BUFFERING)
                    }
                }
                LaunchedEffect(player, scene.sourceVolume) { player.volume = scene.sourceVolume.coerceIn(0f, 1f) }
                LaunchedEffect(player, clipPlayback.seekRequestMs) {
                    val target = clipPlayback.seekRequestMs ?: return@LaunchedEffect
                    if (clipPlayback.sceneKey == scene.key) {
                        player.seekTo(target)
                        clipPlayback.seekRequestMs = null
                    }
                }
                LaunchedEffect(player) {
                    while (isActive) {
                        clipPlayback.positionMs = player.currentPosition.coerceAtLeast(0L)
                        delay(200)
                    }
                }
                DisposableEffect(player) {
                    onDispose {
                        playerBuffering = false
                        // The last known clip position; the next player (this or another layout) continues from it.
                        runCatching { if (clipPlayback.sceneKey == scene.key) clipPlayback.positionMs = player.currentPosition.coerceAtLeast(0L) }
                        player.release()
                    }
                }
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
                val bitmap = frameBitmap
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
                    Text(
                        "미리보기를 불러올 수 없습니다",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
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
                if (caption == null && !timelineShown) {
                    Text(
                        statusText,
                        Modifier.align(Alignment.TopStart).padding(8.dp).background(Color.Black.copy(alpha = .65f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            } else {
                val isFit = scene.fit == SceneFit.FIT
                val bitmap = frameBitmap
                if (bitmap != null) {
                    Image(
                        bitmap.asImageBitmap(),
                        contentDescription = "${source.name} 클립 화면",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = if (isFit) ContentScale.Fit else ContentScale.Crop,
                        alignment = if (isFit) Alignment.Center else BiasAlignment(scene.focusX.coerceIn(-1f, 1f), scene.focusY.coerceIn(-1f, 1f)),
                    )
                } else {
                    Text(
                        "미리보기를 불러올 수 없습니다",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            captionBitmapState?.let { bitmap ->
                val captionImage = remember(bitmap) { bitmap.asImageBitmap() }
                Image(captionImage, contentDescription = caption?.text, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            }
        }
    }
    // Enhanced preview controls: scrubber slider, play/pause, replay from start, and stepping.
    val elapsedSeconds = clipPlayback.positionFor(scene.key) / 1000.0
    // With the project timeline attached below the picture, its transport/scrub row is the only control set (no duplicate column).
    if (!timelineShown) Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (!source.isImage && scene.length > 0.0) {
            val totalMs = (scene.length * 1000).toLong().coerceAtLeast(1L)
            var scrubbingValue by remember(scene.key) { mutableStateOf<Float?>(null) }
            val currentScrubPos = scrubbingValue ?: (clipPlayback.positionFor(scene.key).toFloat() / totalMs)
            Row(
                modifier = Modifier.fillMaxWidth().height(26.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    seconds1(elapsedSeconds),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = currentScrubPos.coerceIn(0f, 1f),
                    onValueChange = { scrubbingValue = it },
                    onValueChangeFinished = {
                        scrubbingValue?.let { fraction ->
                            val targetMs = (fraction * totalMs).toLong()
                            clipPlayback.seek(scene.key, targetMs)
                        }
                        scrubbingValue = null
                    },
                    modifier = Modifier.weight(1f).heightIn(max = 26.dp),
                )
                Text(
                    seconds1(scene.length),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (source.isImage && playing) {
            LinearProgressIndicator(
                progress = { photoPlayProgress },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // With the project timeline above, its play/pause is the only visible one; restart and trim controls stay.
            if (!timelineShown) {
                FilledIconButton(
                    shape = EditorButtonShape,
                    onClick = { clipPlayback.toggle(scene.key) },
                    modifier = Modifier.size(36.dp).semantics {
                        if (!source.isImage) stateDescription = "경과 ${seconds1(elapsedSeconds)} / ${seconds1(scene.length)}"
                    },
                ) {
                    PackIcon(if (playing) PackGlyph.PAUSE else PackGlyph.PLAY, if (playing) "클립 재생 멈춤" else "클립 재생", Modifier.size(20.dp))
                }
            }
            IconButton(
                onClick = { clipPlayback.restart(scene.key) },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(Icons.Default.Replay, contentDescription = "처음부터 재생", Modifier.size(20.dp))
            }
            if (!source.isImage) {
                IconButton(
                    onClick = {
                        val prevMs = (clipPlayback.positionFor(scene.key) - 1000L).coerceAtLeast(0L)
                        clipPlayback.seek(scene.key, prevMs)
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Default.FastRewind, contentDescription = "1초 뒤로", Modifier.size(18.dp))
                }
                IconButton(
                    onClick = {
                        val nextMs = (clipPlayback.positionFor(scene.key) + 1000L).coerceAtMost((scene.length * 1000).toLong())
                        clipPlayback.seek(scene.key, nextMs)
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Default.FastForward, contentDescription = "1초 앞으로", Modifier.size(18.dp))
                }
            }
            Text(
                if (source.isImage) seconds1(scene.length)
                else "경과 ${seconds1(elapsedSeconds)} / ${seconds1(scene.length)} · ${seconds1(scene.start)}부터",
                Modifier.weight(1f).padding(start = 4.dp),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
    boardDisplayMode: BoardDisplayMode = BoardDisplayMode.DETAIL,
    onBoardDisplayModeChange: ((BoardDisplayMode) -> Unit)? = null,
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
            // Row 1 (Info row): columns dropdown + board display mode + compact count + photo importance toggle + 전체선택/선택해제 (when multiActive)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                var columnsMenu by remember { mutableStateOf(false) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
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

                    if (onBoardDisplayModeChange != null) {
                        var modeMenu by remember { mutableStateOf(false) }
                        Box {
                            TextButton(
                                shape = EditorButtonShape,
                                onClick = { modeMenu = true },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .heightIn(min = if (isTablet) 28.dp else 30.dp),
                            ) {
                                Text(boardDisplayMode.title, fontSize = 12.sp)
                                Icon(Icons.Default.ArrowDropDown, null, Modifier.size(18.dp))
                            }
                            DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                                BoardDisplayMode.entries.forEach { mode ->
                                    DropdownMenuItem(
                                        text = { Text(mode.title, fontSize = 13.sp) },
                                        trailingIcon = { if (mode == boardDisplayMode) Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary) },
                                        onClick = {
                                            modeMenu = false
                                            onBoardDisplayModeChange(mode)
                                        },
                                    )
                                }
                            }
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
    // Full-span item after the clips and 재료 추가 (미리보기 좌측: the settings group under the board).
    footer: (@Composable () -> Unit)? = null,
    // Tablet boards only: "클립 N" title above the 3-view selector; the phone page stays compact.
    showTitle: Boolean = false,
    // Tablet boards only: Mac clip management (info, 중요 사진, one row of 선택/복사/이동/삭제) and the connected 3-view control.
    macStyle: Boolean = false,
    onOpenCaption: ((Long) -> Unit)? = null,
    modifier: Modifier = Modifier,
    model: NasFinderViewModel? = null,
    boardDisplayMode: BoardDisplayMode = BoardDisplayMode.DETAIL,
    onBoardDisplayModeChange: ((BoardDisplayMode) -> Unit)? = null,
) {
    val project = session.editor ?: return
    val sourcesById = session.sources.associateBy { it.id }
    var internalMultiSelectMode by rememberSaveable { mutableStateOf(false) }
    val effectiveMultiSelectMode = if (onToggleMultiSelectMode != null) multiSelectMode else internalMultiSelectMode
    var menuSceneKey by remember { mutableStateOf<Long?>(null) }
    var columnsMenu by remember { mutableStateOf(false) }
    val indexedScenes = remember(project.scenes) { project.scenes.mapIndexed { idx, s -> idx to s } }

    val effectiveAspect = when (boardDisplayMode) {
        BoardDisplayMode.DETAIL -> thumbnailAspect
        BoardDisplayMode.THUMBNAIL -> 1.15f
        BoardDisplayMode.POSTER -> 0.72f
    }
    val effectiveCompact = compactCells || boardDisplayMode == BoardDisplayMode.THUMBNAIL

    // Compact toolbar: one text toggle for multi-selection; count, 전체 선택 and the
    // move/clone/delete actions appear only while selecting several (a single scene uses its ⋮ menu).
    // Tablet multi-select uses the selection exactly (even empty), so 전체 해제 never falls back to the focused clip.
    val effectiveSelectedKeys = if (macStyle && effectiveMultiSelectMode) selectedKeys
    else if (selectedKeys.isNotEmpty()) selectedKeys else setOfNotNull(selectedKey)
    val multiActive = effectiveMultiSelectMode || effectiveSelectedKeys.size > 1
    val toggleMultiSelect: () -> Unit = {
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
    // The quiet 한 줄 N개 control, shared by the phone toolbar and the tablet clip management.
    val columnsControl: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Clips per row (thumbnail size): UI only, so order, selection, scroll and the project stay as they are.
            if (fixedColumns != null && onColumnsChange != null) {
                Box {
                    TextButton(
                        shape = EditorButtonShape,
                        onClick = { columnsMenu = true },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.heightIn(min = IconHitTarget).semantics(mergeDescendants = true) { contentDescription = "한 줄에 클립 ${fixedColumns}개" },
                    ) {
                        Text(if (macStyle) "${fixedColumns}열" else "한 줄 ${fixedColumns}개", maxLines = 1)
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
    }
    val headerItems: @Composable RowScope.() -> Unit = {
        Row(Modifier.align(Alignment.CenterVertically), verticalAlignment = Alignment.CenterVertically) { columnsControl() }
        val toggleLabel = if (multiActive) "선택 완료" else "선택"
        val toggle = toggleMultiSelect
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

    // Tablet clip management (as on Mac): an info row (position or selection count, 중요 사진, the quiet 한 줄 N개) above ONE
    // always-visible row of four outlined actions on the current clip or the selected clips. Same callbacks as the phone toolbar.
    val macToolbar: @Composable () -> Unit = {
        val selectedScenes = project.scenes.filter { it.key in effectiveSelectedKeys }
        val photoKeys = selectedScenes.filter { sourcesById[it.sourceId]?.isImage == true }.map { it.key }.toSet()
        val allPhotosImportant = photoKeys.isNotEmpty() &&
            selectedScenes.filter { it.key in photoKeys }.all { sourcesById[it.sourceId]?.isImportant == true }
        val hasSelection = effectiveSelectedKeys.isNotEmpty()
        val currentIndex = project.scenes.indexOfFirst { it.key == selectedKey }
        val infoText = when {
            multiActive -> "${effectiveSelectedKeys.size}/${project.scenes.size}개 선택"
            currentIndex >= 0 -> "${currentIndex + 1}/${project.scenes.size}번"
            else -> "전체 ${project.scenes.size}개"
        }
        val important = Color(0xFFFFB300)
        val actionModifier = Modifier.minimumInteractiveComponentSize().heightIn(min = 34.dp)
        val actionPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
        val actionText = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp)
        Surface(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(10.dp),
        ) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        infoText,
                        Modifier.align(Alignment.CenterVertically),
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                        color = if (showSelection) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    Box(Modifier.align(Alignment.CenterVertically)) { columnsControl() }
                    // 중요 사진 applies to the photos among the current/selected clips; a video-only selection disables it.
                    if (onMarkImportant != null) {
                        OutlinedButton(
                            shape = EditorButtonShape,
                            onClick = { onMarkImportant(photoKeys, !allPhotosImportant) },
                            enabled = photoKeys.isNotEmpty(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = if (allPhotosImportant) important else MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                            border = BorderStroke(
                                1.dp,
                                (if (allPhotosImportant) important else MaterialTheme.colorScheme.outlineVariant).copy(alpha = if (photoKeys.isNotEmpty()) 1f else 0.4f),
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier
                                .align(Alignment.CenterVertically)
                                .then(actionModifier)
                                .semantics { contentDescription = if (allPhotosImportant) "중요 사진 해제" else "중요 사진 설정" },
                        ) {
                            Icon(if (allPhotosImportant) Icons.Default.Star else Icons.Default.StarBorder, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("중요 사진", style = actionText, fontWeight = if (allPhotosImportant) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                        }
                    }
                    if (multiActive) {
                        val allSelected = project.scenes.isNotEmpty() && effectiveSelectedKeys.size == project.scenes.size
                        TextButton(
                            shape = EditorButtonShape,
                            onClick = { if (allSelected) onClearSelection() else onSelectAll() },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.align(Alignment.CenterVertically).then(actionModifier),
                        ) { Text(if (allSelected) "선택 해제" else "전체 선택", style = actionText, maxLines = 1) }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        shape = EditorButtonShape,
                        onClick = toggleMultiSelect,
                        contentPadding = actionPadding,
                        modifier = Modifier.weight(1f).then(actionModifier).semantics { contentDescription = if (multiActive) "선택 완료" else "여러 클립 선택" },
                    ) { Text(if (multiActive) "완료" else "선택", style = actionText, fontWeight = if (multiActive) FontWeight.Bold else FontWeight.Normal, maxLines = 1) }
                    OutlinedButton(
                        shape = EditorButtonShape,
                        onClick = { onDuplicate(effectiveSelectedKeys) },
                        enabled = hasSelection,
                        contentPadding = actionPadding,
                        modifier = Modifier.weight(1f).then(actionModifier).semantics { contentDescription = "클립 복제" },
                    ) { Text("복사", style = actionText, maxLines = 1) }
                    OutlinedButton(
                        shape = EditorButtonShape,
                        onClick = { onRequestMoveDialog(effectiveSelectedKeys) },
                        enabled = hasSelection,
                        contentPadding = actionPadding,
                        modifier = Modifier.weight(1f).then(actionModifier).semantics { contentDescription = "위치 이동" },
                    ) { Text("이동", style = actionText, maxLines = 1) }
                    OutlinedButton(
                        shape = EditorButtonShape,
                        onClick = { onRemove(effectiveSelectedKeys) },
                        enabled = hasSelection,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        ),
                        contentPadding = actionPadding,
                        modifier = Modifier.weight(1f).then(actionModifier).semantics { contentDescription = "클립 삭제 (원본 파일은 유지)" },
                    ) { Text("삭제", style = actionText, maxLines = 1) }
                }
            }
        }
    }

    // Multirow LazyVerticalGrid: the page item (when given), then (클립 only) the toolbar and the clips.
    val minCellSize = 136.dp
    LazyVerticalGrid(
        // 자세히 is a vertical list (thumbnail left, text right) whatever the per-row setting; 썸네일/포스터 keep the grid.
        columns = if (boardDisplayMode == BoardDisplayMode.DETAIL) GridCells.Fixed(1)
        else if (fixedColumns != null) GridCells.Fixed(fixedColumns) else GridCells.Adaptive(minSize = minCellSize),
        state = gridState,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = if (macStyle) 6.dp else 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(if (macStyle) 8.dp else 6.dp),
        modifier = modifier,
    ) {
        if (page != null) item(key = "page", span = { GridItemSpan(maxLineSpan) }) { page() }
        if (!showClips) return@LazyVerticalGrid
        if (showTitle) {
            item(key = "clips_title", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "클립 ${project.scenes.size}",
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
        if (onBoardDisplayModeChange != null) {
            item(key = "board_display_mode_selector", span = { GridItemSpan(maxLineSpan) }) {
                // Phone and tablet share the same connected capsule (min 48dp touch targets).
                MacSegmentedControl(
                    options = BoardDisplayMode.entries.map { it to it.title },
                    selected = boardDisplayMode,
                    onSelect = onBoardDisplayModeChange,
                    modifier = Modifier.fillMaxWidth().padding(vertical = if (macStyle) 2.dp else 0.dp),
                )
            }
        }
        if (showToolbar && macStyle) item(key = "toolbar", span = { GridItemSpan(maxLineSpan) }) { macToolbar() }
        if (showToolbar && !macStyle) {
            item(key = "toolbar", span = { GridItemSpan(maxLineSpan) }) {
                // Wrapping rows keep every toolbar action visible, however narrow the page.
                Surface(
                    Modifier.fillMaxWidth().padding(vertical = 0.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Column(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        FlowRow(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalArrangement = Arrangement.spacedBy(2.dp),
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
                        checked = if (macStyle) scene.key in effectiveSelectedKeys else isSelected,
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
                            if (onOpenCaption != null) {
                                DropdownMenuItem(
                                    text = { Text("자막·구간") },
                                    onClick = { menuSceneKey = null; onOpenCaption(scene.key) },
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
                if (boardDisplayMode == BoardDisplayMode.DETAIL) {
                    val captionText = scene.caption?.text?.trim()?.takeIf { it.isNotEmpty() }
                    val kindText = if (source.isImage) (if (source.isImportant) "★ 중요 사진" else "사진") else "영상"
                    val lengthText = if (source.isImage) {
                        "재생 ${seconds1(scene.length)}"
                    } else {
                        "${seconds1(scene.start)}~${seconds1(scene.start + scene.length)} (${seconds1(scene.length)})"
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(6.dp))
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
                            if (isImportantPhoto) {
                                Icon(
                                    Icons.Default.Star,
                                    contentDescription = "중요 사진",
                                    tint = Color(0xFFFFB300),
                                    modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(14.dp),
                                )
                            }
                        }
                        Text(
                            "${originalIndex + 1}",
                            Modifier.widthIn(min = 24.dp),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(
                                source.name,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "$kindText · $lengthText",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (captionText != null) {
                                Text(
                                    captionText,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        cellAction(Modifier)
                    }
                } else Column(Modifier.fillMaxWidth()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(effectiveAspect)
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
                        if (boardDisplayMode == BoardDisplayMode.POSTER) {
                            cellAction(Modifier.align(Alignment.TopEnd))
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.BottomCenter)
                                    .background(
                                        androidx.compose.ui.graphics.Brush.verticalGradient(
                                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                                        )
                                    )
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                            ) {
                                Column {
                                    Text(
                                        source.name,
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 11.sp),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        seconds1(scene.length),
                                        color = Color.White.copy(alpha = 0.85f),
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    )
                                }
                            }
                        } else {
                            if (!effectiveCompact) {
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
                    }
                    if (boardDisplayMode == BoardDisplayMode.DETAIL) {
                        Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                            Text(
                                source.name,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val detailText = if (source.isImage) {
                                "사진 · 재생 ${seconds1(scene.length)}"
                            } else {
                                "영상 · 구간 ${seconds1(scene.start)}~${seconds1(scene.start + scene.length)} (선택 ${seconds1(scene.length)})"
                            }
                            Text(
                                detailText,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else if (boardDisplayMode == BoardDisplayMode.THUMBNAIL) {
                        if (effectiveCompact) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { cellAction(Modifier) }
                        } else {
                            Text(
                                source.name,
                                Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        item {
            Surface(
                onClick = onAddMaterial,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (boardDisplayMode == BoardDisplayMode.DETAIL) Modifier.height(72.dp) else Modifier.aspectRatio(effectiveAspect))
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
        if (footer != null) item(key = "footer", span = { GridItemSpan(maxLineSpan) }) { footer() }
    }
}

private enum class TitleCardStyleTarget(val title: String) {
    TITLE("제목"),
    SUBTITLE("부제목"),
}

private val TITLE_CARD_COLOR_SWATCHES = listOf(
    "흰색" to 0xFFFFFFFF.toInt(),
    "검정" to 0xFF111111.toInt(),
    "노랑" to 0xFFFFE45C.toInt(),
    "하늘" to 0xFF8FD3FF.toInt(),
    "분홍" to 0xFFFF9EC7.toInt(),
    "민트" to 0xFF8EF0C8.toInt(),
)

private val TITLE_CARD_ROW_NAMES = listOf("맨 위", "위", "가운데", "아래", "맨 아래")
private val TITLE_CARD_COLUMN_NAMES = listOf("왼쪽", "왼쪽 안쪽", "가운데", "오른쪽 안쪽", "오른쪽")

/**
 * 시작 화면/마지막 화면 editor shown inline in the 자막 inspector. It edits the same [TrailerTitleCard] through
 * editorSetIntroCard/editorSetOutroCard as the former sheet did; the time/background fields appear only while the
 * card is on. Title and subtitle have independently editable size, font, color, shadow and 5x5 position.
 */
@Composable
private fun TitleCardInlineEditor(
    isIntro: Boolean,
    card: TrailerTitleCard,
    session: TrailerSession,
    model: NasFinderViewModel,
) {
    val context = LocalContext.current
    var draftCard by remember(card) { mutableStateOf(card) }
    var selectedStyleTarget by rememberSaveable { mutableStateOf(TitleCardStyleTarget.TITLE) }
    var fontListExpanded by rememberSaveable { mutableStateOf(false) }
    val sheetName = if (isIntro) "시작 화면 설정" else "마지막 화면 설정"
    val toggleLabel = if (isIntro) "시작 화면 사용" else "마지막 화면 사용"
    val bgToggleLabel = if (isIntro) "첫 사진/영상 배경 사용" else "마지막 사진/영상 배경 사용"

    val scenes = session.scenes
    val targetScene = if (isIntro) scenes.firstOrNull() else scenes.lastOrNull()
    val photoChoices = remember(session.scenes) { session.scenes.map { it.source }.filter { it.isImage }.distinctBy { it.id } }
    val chosenPhoto = draftCard.resolveBackgroundSource(photoChoices)
    val backgroundBitmap by produceState<Bitmap?>(null, isIntro, draftCard.usesFirstOrLastImage, targetScene, chosenPhoto) {
        if (chosenPhoto != null) {
            value = withContext(Dispatchers.IO) { loadFrame(chosenPhoto.file, true, 0.0, 480, exact = true) }
            return@produceState
        }
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

    val bodyCaption = TrailerTitleCardRenderer.bodyCaptionOf(scenes.map { it.caption })
    val previewBitmap by produceState<Bitmap?>(null, draftCard, backgroundBitmap, previewW, previewH, bodyCaption) {
        value = withContext(Dispatchers.Default) {
            TrailerTitleCardRenderer.renderCardBitmap(context, draftCard, backgroundBitmap, previewW, previewH, bodyCaption)
        }
    }

    fun commit(updated: TrailerTitleCard) {
        draftCard = updated
        if (isIntro) model.editorSetIntroCard(updated)
        else model.editorSetOutroCard(updated)
    }

    val activeStyle = remember(draftCard.titleStyle, draftCard.subtitleStyle, selectedStyleTarget, bodyCaption) {
        if (selectedStyleTarget == TitleCardStyleTarget.TITLE) {
            draftCard.titleStyle ?: TrailerTitleCardRenderer.defaultTitleStyle(bodyCaption)
        } else {
            draftCard.subtitleStyle ?: TrailerTitleCardRenderer.defaultSubtitleStyle(bodyCaption)
        }
    }

    val hasCustomStyle = if (selectedStyleTarget == TitleCardStyleTarget.TITLE) {
        draftCard.titleStyle != null
    } else {
        draftCard.subtitleStyle != null
    }

    fun updateActiveStyle(newStyle: SceneCaption) {
        val clamped = newStyle.clamped().copy(text = "")
        val updated = if (selectedStyleTarget == TitleCardStyleTarget.TITLE) {
            draftCard.copy(
                titleStyle = clamped,
                subtitleStyle = draftCard.subtitleStyle ?: TrailerTitleCardRenderer.defaultSubtitleStyle(bodyCaption),
            )
        } else {
            draftCard.copy(
                titleStyle = draftCard.titleStyle ?: TrailerTitleCardRenderer.defaultTitleStyle(bodyCaption),
                subtitleStyle = clamped,
            )
        }
        commit(updated)
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                modifier = Modifier.semantics { contentDescription = toggleLabel },
            )
        }

        if (draftCard.enabled) {
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

            // Independent typography, colors and 5x5 position editor
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // Target selector: 제목 스타일 vs 부제목 스타일 + 초기화 버튼
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CompactChoice(
                            text = "제목 스타일",
                            selected = selectedStyleTarget == TitleCardStyleTarget.TITLE,
                            onClick = { selectedStyleTarget = TitleCardStyleTarget.TITLE },
                            modifier = Modifier.weight(1f),
                        )
                        CompactChoice(
                            text = "부제목 스타일",
                            selected = selectedStyleTarget == TitleCardStyleTarget.SUBTITLE,
                            onClick = { selectedStyleTarget = TitleCardStyleTarget.SUBTITLE },
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                val updated = if (selectedStyleTarget == TitleCardStyleTarget.TITLE) {
                                    draftCard.copy(titleStyle = null)
                                } else {
                                    draftCard.copy(subtitleStyle = null)
                                }
                                commit(updated)
                            },
                            enabled = hasCustomStyle,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(32.dp),
                        ) {
                            Text(
                                "초기화",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (hasCustomStyle) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                            )
                        }
                    }

                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                    val targetTitle = selectedStyleTarget.title

                    // Position (5x5 grid) & Size
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        val useRow = maxWidth >= 320.dp
                        val positionGrid: @Composable () -> Unit = {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "$targetTitle 위치: ${TITLE_CARD_ROW_NAMES[activeStyle.row.coerceIn(0, 4)]} ${TITLE_CARD_COLUMN_NAMES[activeStyle.column.coerceIn(0, 4)]}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Medium,
                                )
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
                                                    val isSelected = activeStyle.row == r && activeStyle.column == c
                                                    Box(
                                                        modifier = Modifier
                                                            .size(32.dp)
                                                            .semantics {
                                                                contentDescription = "$targetTitle 위치 ${TITLE_CARD_ROW_NAMES[r]} ${TITLE_CARD_COLUMN_NAMES[c]}"
                                                                this.selected = isSelected
                                                            }
                                                            .clickable { updateActiveStyle(activeStyle.copy(row = r, column = c)) },
                                                        contentAlignment = Alignment.Center,
                                                    ) {
                                                        if (isSelected) {
                                                            Box(
                                                                modifier = Modifier
                                                                    .size(24.dp)
                                                                    .clip(RoundedCornerShape(6.dp))
                                                                    .background(MaterialTheme.colorScheme.primary),
                                                                contentAlignment = Alignment.Center,
                                                            ) {
                                                                Text(
                                                                    if (selectedStyleTarget == TitleCardStyleTarget.TITLE) "제" else "부",
                                                                    color = MaterialTheme.colorScheme.onPrimary,
                                                                    fontWeight = FontWeight.Black,
                                                                    fontSize = 12.sp,
                                                                )
                                                            }
                                                        } else {
                                                            Box(
                                                                modifier = Modifier
                                                                    .size(4.dp)
                                                                    .clip(CircleShape)
                                                                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        val sizeControls: @Composable () -> Unit = {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("$targetTitle 크기", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CaptionSize.entries.forEach { size ->
                                        CompactChoice(
                                            text = size.title,
                                            selected = activeStyle.size == size,
                                            onClick = { updateActiveStyle(activeStyle.copy(size = size)) },
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }
                        }

                        if (useRow) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                positionGrid()
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    sizeControls()
                                }
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                positionGrid()
                                sizeControls()
                            }
                        }
                    }

                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                    // Font Selection with Sample
                    val currentFontFamily = remember(activeStyle.effectiveFontName) {
                        CaptionFontStore.fontFamilyForName(context, activeStyle.effectiveFontName)
                    }
                    val currentFontDisplayName = CaptionFontStore.displayName(activeStyle.effectiveFontName)

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { fontListExpanded = !fontListExpanded }
                                .semantics {
                                    role = Role.Button
                                    stateDescription = if (fontListExpanded) "펼쳐짐" else "접힘"
                                    contentDescription = "$targetTitle 글꼴 선택, 현재 $currentFontDisplayName"
                                },
                            shape = RoundedCornerShape(8.dp),
                            color = Color.Transparent,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text("글꼴", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                                    Text(
                                        currentFontDisplayName,
                                        fontFamily = currentFontFamily,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(30.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text("Aa", fontFamily = currentFontFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    }
                                    Icon(
                                        if (fontListExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }

                        if (fontListExpanded) {
                            val bundledFonts = remember { CaptionFontStore.BUNDLED_FONTS }
                            val importedFonts = remember(context) { CaptionFontStore.list(context) }
                            data class FontOption(val id: String, val name: String)
                            val allFonts = remember(bundledFonts, importedFonts) {
                                bundledFonts.map { FontOption(it.id, it.displayName) } +
                                    importedFonts.map { FontOption(it.id, it.displayName) }
                            }

                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 180.dp),
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .verticalScroll(rememberScrollState())
                                        .padding(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    allFonts.chunked(2).forEach { rowFonts ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            rowFonts.forEach { fontOpt ->
                                                val isSelected = activeStyle.effectiveFontName == fontOpt.id
                                                val optFamily = remember(fontOpt.id) {
                                                    CaptionFontStore.fontFamilyForName(context, fontOpt.id)
                                                }
                                                Surface(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .height(40.dp)
                                                        .clip(RoundedCornerShape(6.dp))
                                                        .clickable {
                                                            updateActiveStyle(activeStyle.withFont(fontOpt.id))
                                                            fontListExpanded = false
                                                        },
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                                    border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                                                ) {
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxSize()
                                                            .padding(horizontal = 8.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                    ) {
                                                        Text(
                                                            fontOpt.name,
                                                            fontFamily = optFamily,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                            modifier = Modifier.weight(1f),
                                                        )
                                                        if (isSelected) {
                                                            Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                        }
                                                    }
                                                }
                                            }
                                            if (rowFonts.size == 1) {
                                                Spacer(Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                    // Color Swatches & Custom Hex Input
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("$targetTitle 색상", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TITLE_CARD_COLOR_SWATCHES.forEach { (name, colorInt) ->
                                val isSelected = (activeStyle.textColor and 0xFFFFFF) == (colorInt and 0xFFFFFF)
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(Color(colorInt or 0xFF000000.toInt()))
                                        .border(
                                            width = if (isSelected) 2.5.dp else 1.dp,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                            shape = CircleShape,
                                        )
                                        .clickable {
                                            updateActiveStyle(activeStyle.copy(textColor = colorInt or 0xFF000000.toInt()))
                                        }
                                        .semantics {
                                            contentDescription = "$name 색상"
                                            this.selected = isSelected
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (isSelected) {
                                        val isBright = (Color(colorInt).luminance() > 0.5f)
                                        Text(
                                            "✓",
                                            color = if (isBright) Color.Black else Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                        )
                                    }
                                }
                            }
                        }

                        var hexText by remember(activeStyle.textColor) {
                            mutableStateOf(captionHex(activeStyle.textColor))
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = hexText,
                                onValueChange = { input ->
                                    val clean = input.trim().uppercase()
                                    if (clean.length <= 7) {
                                        hexText = clean
                                        val parsed = parseCaptionHex(clean)
                                        if (parsed != null) {
                                            updateActiveStyle(activeStyle.copy(textColor = parsed))
                                        }
                                    }
                                },
                                label = { Text("색상 코드", style = MaterialTheme.typography.labelSmall) },
                                singleLine = true,
                                modifier = Modifier.width(130.dp),
                            )
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(activeStyle.textColor or 0xFF000000.toInt()))
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
                            )
                        }
                    }

                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                    // Shadow Toggle & Opacity Slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("$targetTitle 그림자", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text("글자에 음영을 넣어 배경과 구분합니다", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = activeStyle.shadowEnabled,
                            onCheckedChange = { enabled ->
                                val nextOpacity = if (enabled && activeStyle.shadowOpacity <= 0f) 0.75f else activeStyle.shadowOpacity
                                updateActiveStyle(activeStyle.copy(shadowEnabled = enabled, shadowOpacity = nextOpacity))
                            },
                            modifier = Modifier.semantics { contentDescription = "$targetTitle 그림자 효과 사용" },
                        )
                    }

                    if (activeStyle.shadowEnabled) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("진하기", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Slider(
                                value = activeStyle.shadowOpacity.coerceIn(0f, 1f),
                                onValueChange = { opacity ->
                                    val snapped = (opacity * 20).roundToInt() / 20f
                                    updateActiveStyle(activeStyle.copy(shadowOpacity = snapped))
                                },
                                valueRange = 0f..1f,
                                steps = 19,
                                modifier = Modifier.weight(1f),
                            )
                            Text("${(activeStyle.shadowOpacity * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

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
                    modifier = Modifier.semantics { contentDescription = bgToggleLabel },
                )
            }

            // Optional project photo as the background; "기본" keeps the first/last frame. A missing photo falls back silently.
            if (draftCard.usesFirstOrLastImage && photoChoices.isNotEmpty()) {
                Text("배경 사진", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = chosenPhoto == null,
                        onClick = { commit(draftCard.copy(backgroundSourceId = null)) },
                        label = { Text(if (isIntro) "첫 장면" else "마지막 장면") },
                    )
                    photoChoices.forEachIndexed { index, photo ->
                        FilterChip(
                            selected = chosenPhoto?.id == photo.id,
                            onClick = { commit(draftCard.copy(backgroundSourceId = photo.id)) },
                            label = { Text("사진 ${index + 1}") },
                            modifier = Modifier.semantics { contentDescription = "배경 사진 ${index + 1}: ${photo.name}" },
                        )
                    }
                }
            }

            // LIVE preview of the card itself (the stage preview keeps showing the selected clip)
            val aspect = if (outputHeight > 0) outputWidth.toFloat() / outputHeight.toFloat() else 16f / 9f
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 160.dp)
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
        }
    }
}

/** Which part of the video the 자막 inspector edits: the clips' body captions, or the 시작/마지막 화면 title card. */
private enum class CaptionTarget(val title: String) {
    BODY("본문 자막"),
    INTRO("시작 화면"),
    OUTRO("마지막 화면"),
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
): InspectorColumns {
    // Saved here (not inside the caption section) so the choice survives tab switches and relayouts.
    var captionTarget by rememberSaveable { mutableStateOf(CaptionTarget.BODY) }
    var pendingScope by rememberSaveable { mutableStateOf<CaptionScope?>(null) }
    return when (if (tab == InspectorTab.PLAN) InspectorTab.SCENE else tab) {
    // 클립: only the selected clip's compact length/range, so the clip grid below stays in the first viewport.
    InspectorTab.SCENE, InspectorTab.PLAN -> InspectorColumns(left = { SceneSection(session, scene, source, model, onSelectScene) })
    InspectorTab.CAPTION -> {
        // 본문 keeps the unchanged caption inspector (its own 현재/선택/전체 scopes and pending draft); 시작 화면/마지막 화면
        // swap only the body below this selector for the title card controls, so no caption or card data is touched.
        // 시작/마지막 화면 선택지. 범위 3개와 같은 줄(CaptionScopeChoices)에 이어 붙여 한 번만 렌더된다.
        val cardChoices: @Composable () -> Unit = {
            val chip = Modifier.width(80.dp)
            CompactChoice("시작 화면", captionTarget == CaptionTarget.INTRO, { captionTarget = CaptionTarget.INTRO }, chip)
            CompactChoice("마지막 화면", captionTarget == CaptionTarget.OUTRO, { captionTarget = CaptionTarget.OUTRO }, chip)
        }
        val project = session.editor
        if (captionTarget == CaptionTarget.BODY || project == null) {
            val body = sceneCaptionColumns(
                session, scene, selectedKeys, captionDraft, onCaptionDraftChange, model, onSelectScene,
                scopeExtras = cardChoices,
                pendingScope = pendingScope,
                onPendingScopeHandled = { pendingScope = null },
            )
            val bodySingle = body.single
            val singleWithTiming: (@Composable () -> Unit)? = if (bodySingle == null) null else {
                {
                    SceneSection(session, scene, source, model, onSelectScene)
                    HorizontalDivider()
                    bodySingle()
                }
            }
            // As on Mac the scene timing leads the body-caption inspector; it joins only the route that is shown
            // (single, or left + right), so it is never composed twice. Title card targets below get none.
            InspectorColumns(
                left = {
                    SceneSection(session, scene, source, model, onSelectScene)
                    HorizontalDivider()
                    body.left()
                },
                right = body.right,
                dividerBetween = body.dividerBetween,
                single = singleWithTiming,
            )
        } else {
            val isIntro = captionTarget == CaptionTarget.INTRO
            val sceneKeys = project.scenes.map { it.key }.toSet()
            val selectedCount = selectedKeys.count { it in sceneKeys }
            InspectorColumns(left = {
                CaptionScopeChoices(
                    scope = null,
                    selectedCount = selectedCount,
                    onChoose = { pendingScope = it; captionTarget = CaptionTarget.BODY },
                    extra = cardChoices,
                )
                TitleCardInlineEditor(isIntro, if (isIntro) project.introCard else project.outroCard, session, model)
            })
        }
    }
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
private fun InspectorTabs(tab: InspectorTab, onTab: (InspectorTab) -> Unit, resizeActions: List<CustomAccessibilityAction>, modifier: Modifier, settingsPane: Boolean = false, connected: Boolean = false) {
    // settingsPane (tablet/portrait settings, as on Mac): 자막·구간/음악/화면 only; a legacy 클립/PLAN shows as 자막·구간.
    val legacy = tab == InspectorTab.SCENE || tab == InspectorTab.PLAN
    val shownTab = if (settingsPane && legacy) InspectorTab.CAPTION else if (tab == InspectorTab.PLAN) InspectorTab.SCENE else tab
    val tabs = if (settingsPane) InspectorTab.entries - InspectorTab.PLAN - InspectorTab.SCENE else InspectorTab.entries - InspectorTab.PLAN
    val tabModifier = if (resizeActions.isEmpty()) Modifier else Modifier.semantics { customActions = resizeActions }
    val configuration = LocalConfiguration.current
    val isTablet = configuration.smallestScreenWidthDp >= 600
    val tabHeightMod = if (isTablet) Modifier.minimumInteractiveComponentSize().heightIn(min = 34.dp) else Modifier.heightIn(min = IconHitTarget)
    if (settingsPane) {
        // Tablet inspector (as on Mac): the three tabs are connected segments that always share one row (panes are ≥ 240dp).
        MacSegmentedControl(tabs.map { it to it.title }, shownTab, onTab, modifier.fillMaxWidth(), itemModifier = tabModifier)
        return
    }
    if (connected) {
        // Phone: the four tabs share the Mac connected capsule instead of separate outlined blocks (min 48dp targets).
        MacSegmentedControl(tabs.map { it to it.title }, shownTab, onTab, modifier.fillMaxWidth(), itemModifier = tabModifier)
        return
    }
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

/** The 클립/설정 switch under the fixed portrait preview; its row also carries the vertical preview-resize drag. */
@Composable
private fun PortraitPaneSwitch(pane: PortraitPane, onPane: (PortraitPane) -> Unit, resizeActions: List<CustomAccessibilityAction>, modifier: Modifier) {
    val tabModifier = if (resizeActions.isEmpty()) Modifier else Modifier.semantics { customActions = resizeActions }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PortraitPane.entries.forEach { entry ->
            TrailerChoice(entry.title, pane == entry, Modifier.weight(1f).heightIn(min = IconHitTarget).then(tabModifier)) { onPane(entry) }
        }
    }
}

/**
 * Mac-style connected segmented control for the tablet choosers (pane roles, board view modes, inspector tabs): one
 * rounded neutral track, the selected segment a neutral light surface with dark text — no accent outline, check icon or
 * gaps between segments. Colors come from the theme (dark mode lifts the selected segment instead of whitening it).
 * Phone, export and music options keep TrailerChoice. [itemModifier] is applied to every segment (e.g. resize actions).
 */
@Composable
private fun <T> MacSegmentedControl(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    itemModifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    val trackColor = colors.onSurface.copy(alpha = if (dark) 0.14f else 0.08f)
    val selectedColor = if (dark) colors.onSurface.copy(alpha = 0.26f) else colors.surface
    Row(
        modifier.clip(RoundedCornerShape(50)).background(trackColor).padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            Box(
                Modifier
                    .weight(1f)
                    .then(itemModifier)
                    .minimumInteractiveComponentSize()
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (isSelected) selectedColor else Color.Transparent)
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(value) })
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (isSelected) colors.onSurface else colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
