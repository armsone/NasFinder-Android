package com.armsone.nasfinder.ui

import android.net.Uri
import com.armsone.nasfinder.model.RemoteConnection
import com.armsone.nasfinder.model.RemoteFileItem
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Shortest trim a video scene may have (or the whole clip when the source itself is shorter). */
internal const val MIN_EDITOR_VIDEO_SCENE_SECONDS = 0.2
internal const val MIN_EDITOR_PHOTO_SCENE_SECONDS = 0.2
internal const val MAX_EDITOR_PHOTO_SCENE_SECONDS = 30.0

/**
 * One scene of the current project. Scenes are separate from source files: the same source may appear in several
 * scenes (duplicate / "한 번 더 사용"), and removing a scene never touches the source file.
 */
data class EditorScene(
    val key: Long,
    val sourceId: Int,
    val start: Double,
    val length: Double,
    val fit: SceneFit = SceneFit.FILL,
    val focusX: Float = 0f,
    val focusY: Float = 0f,
    val sourceVolume: Float = 1f,
)

/**
 * Explicit editable state of the current project. While [autoPlan] is true the scenes mirror the recommended
 * plan and plan parameters (total/clip length/order/music fit) regenerate them; the first explicit scene edit
 * turns it false, after which only an explicit "자동 추천 다시 적용" replaces the scenes.
 */
data class EditorProject(
    val scenes: List<EditorScene>,
    val autoPlan: Boolean = true,
    // Whole-video background music volume (multiplies the music/ducking gains), independent of scene audio.
    val musicVolume: Float = 1f,
    val nextKey: Long = (scenes.maxOfOrNull { it.key } ?: 0L) + 1L,
) {
    fun scene(key: Long): EditorScene? = scenes.firstOrNull { it.key == key }

    fun toTrailerScenes(sources: List<TrailerSource>): List<TrailerScene> {
        val byId = sources.associateBy { it.id }
        return scenes.mapNotNull { scene ->
            val source = byId[scene.sourceId] ?: return@mapNotNull null
            val (start, length) = clampSceneWindow(source, scene.start, scene.length)
            TrailerScene(
                source = source,
                start = start,
                length = length,
                fit = scene.fit,
                focusX = scene.focusX.coerceIn(-1f, 1f),
                focusY = scene.focusY.coerceIn(-1f, 1f),
                sourceVolume = if (source.isImage) 1f else scene.sourceVolume.coerceIn(0f, 2f),
            )
        }
    }

    companion object {
        /** Materializes an automatic plan into explicit scenes with fresh stable keys. */
        fun fromPlan(plan: List<TrailerScene>, autoPlan: Boolean = true, musicVolume: Float = 1f, firstKey: Long = 1L): EditorProject {
            val scenes = plan.mapIndexed { index, scene ->
                EditorScene(
                    key = firstKey + index,
                    sourceId = scene.source.id,
                    start = scene.start,
                    length = scene.length,
                    fit = scene.fit,
                    focusX = scene.focusX,
                    focusY = scene.focusY,
                    sourceVolume = scene.sourceVolume,
                )
            }
            return EditorProject(scenes, autoPlan, musicVolume, firstKey + scenes.size)
        }
    }
}

/** Keeps a scene window inside the actual source: video trims within its duration, photos within 0.2…30s. */
internal fun clampSceneWindow(source: TrailerSource, start: Double, length: Double): Pair<Double, Double> {
    val safeLength = if (length.isFinite()) length else MIN_EDITOR_PHOTO_SCENE_SECONDS
    if (source.isImage) return 0.0 to safeLength.coerceIn(MIN_PHOTO_SCENE_DURATION, MAX_EDITOR_PHOTO_SCENE_SECONDS)
    val duration = source.duration.takeIf { it.isFinite() && it > 0.0 }
        ?: return start.coerceAtLeast(0.0) to safeLength.coerceAtLeast(MIN_PHOTO_SCENE_DURATION)
    val minLength = min(MIN_EDITOR_VIDEO_SCENE_SECONDS, duration)
    val safeStart = (if (start.isFinite()) start else 0.0).coerceIn(0.0, max(0.0, duration - minLength))
    return safeStart to safeLength.coerceIn(minLength, max(minLength, duration - safeStart))
}

enum class EditorSaveState { IDLE, SAVING, SAVED, FAILED }

/** A running or finished export of a fixed editor snapshot; editing continues independently. */
data class EditorExportState(
    val running: Boolean,
    val snapshotRevision: Long,
    val status: String,
    val progress: Float? = null,
    val error: String? = null,
)

enum class EditorAddPhase(val title: String) {
    QUEUED("대기 중"),
    PREPARING("준비 중"),
    DOWNLOADING("받는 중"),
    ANALYZING("분석 중"),
    FAILED("실패"),
}

data class EditorPendingAdd(
    val id: String,
    val originKey: String,
    val name: String,
    val isVideo: Boolean,
    val phase: EditorAddPhase = EditorAddPhase.QUEUED,
    val progress: Float? = null,
    val error: String? = null,
)

/** A Finder selection the editor adds to the CURRENT project. */
sealed interface EditorAddCandidate {
    val originKey: String
    val name: String
    val isVideo: Boolean

    data class Remote(val connection: RemoteConnection, val item: RemoteFileItem) : EditorAddCandidate {
        override val originKey get() = editorOriginRemote(connection.id, item.path)
        override val name get() = item.name
        override val isVideo get() = item.isVideo
    }

    data class PhoneHard(val item: InboxDisplayItem, override val isVideo: Boolean) : EditorAddCandidate {
        override val originKey get() = editorOriginPhoneHard(item.id.toString())
        override val name get() = item.originalFilename
    }

    data class LocalUri(val uri: Uri, override val name: String, override val isVideo: Boolean, val captureTimeMillis: Long = 0L) : EditorAddCandidate {
        override val originKey get() = editorOriginLocal(uri.toString())
    }

    data class WebFile(val file: File, override val name: String, override val isVideo: Boolean) : EditorAddCandidate {
        override val originKey get() = editorOriginWeb(file.absolutePath)
    }
}

// Same identity scheme as DurableMaterialStore so a Finder item maps to exactly one origin; no content hashing.
internal fun editorOriginRemote(connectionId: String, path: String) = "remote:$connectionId:$path"
internal fun editorOriginPhoneHard(id: String) = "phonehard:$id"
internal fun editorOriginLocal(uri: String) = "local:$uri"
internal fun editorOriginWeb(path: String) = "web:$path"

/** Immutable snapshot of everything an editor action can change; undo/redo swap whole snapshots. */
data class EditorSnapshot(
    val sources: List<TrailerSource>,
    val total: Double,
    val clipLength: Double,
    val photoClipLength: Double,
    val format: TrailerFormat,
    val musicTrack: TrailerMusicTrack?,
    val musicPolicy: TrailerMusicPolicy,
    val musicFitCount: Int,
    val autoDucking: Boolean,
    val orderMode: String?,
    val isMusicOverlayMode: Boolean,
    val highlightAnalyzed: Boolean,
    val title: String?,
    val editor: EditorProject?,
    val unavailableMusic: TrailerMusicTrack? = null,
)

internal fun TrailerSession.editorSnapshot() = EditorSnapshot(
    sources, total, clipLength, photoClipLength, format, musicTrack, musicPolicy, musicFitCount,
    autoDucking, orderMode, isMusicOverlayMode, highlightAnalyzed, title, editor, unavailableMusic,
)

internal fun TrailerSession.applyEditorSnapshot(snapshot: EditorSnapshot) = copy(
    sources = snapshot.sources,
    total = snapshot.total,
    clipLength = snapshot.clipLength,
    photoClipLength = snapshot.photoClipLength,
    format = snapshot.format,
    musicTrack = snapshot.musicTrack,
    unavailableMusic = snapshot.unavailableMusic,
    musicPolicy = snapshot.musicPolicy,
    musicFitCount = snapshot.musicFitCount,
    autoDucking = snapshot.autoDucking,
    orderMode = snapshot.orderMode,
    isMusicOverlayMode = snapshot.isMusicOverlayMode,
    highlightAnalyzed = snapshot.highlightAnalyzed,
    title = snapshot.title,
    editor = snapshot.editor,
)

/** Bounded undo/redo stacks of full immutable snapshots for one project directory. */
internal class EditorHistory(private val limit: Int = 60) {
    private val undoStack = ArrayDeque<EditorSnapshot>()
    private val redoStack = ArrayDeque<EditorSnapshot>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun record(before: EditorSnapshot) {
        if (undoStack.lastOrNull() == before) return
        undoStack.addLast(before)
        while (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
    }

    fun undo(current: EditorSnapshot): EditorSnapshot? {
        val previous = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(current)
        return previous
    }

    fun redo(current: EditorSnapshot): EditorSnapshot? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(current)
        return next
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}
