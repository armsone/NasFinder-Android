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
    // Optional manual caption for this scene's whole duration; null = no caption (old drafts).
    val caption: SceneCaption? = null,
    // Source-time interval of the detected action the automatic plan protects; null = none (manual trim or legacy draft).
    val actionStart: Double? = null,
    val actionEnd: Double? = null,
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
    val introCard: TrailerTitleCard = TrailerTitleCard(),
    val outroCard: TrailerTitleCard = TrailerTitleCard(),
    // Whole scenes left out only so an explicit total/music length fits (every edit kept). The next budget change puts them
    // back first; they are never rendered, counted or edited while reserved.
    val budgetReserve: List<EditorScene> = emptyList(),
    // Scene keys in their order when the budget was last fitted, so reserved scenes return to their own places.
    val budgetOrder: List<Long> = emptyList(),
    // Whole video length (title cards included) the user chose as a hard budget; null = none (music length fit has its own).
    val budgetTarget: Double? = null,
    // The clip row's last choice was a number (1, 2, 4, 6, −/+ or the slider), not 추천: video cuts keep exactly that length, so
    // every budget (whole length or music length) only caps them and never stretches them back.
    val clipChoiceExplicit: Boolean = false,
) {
    fun scene(key: Long): EditorScene? = scenes.firstOrNull { it.key == key }

    val totalDuration: Double
        get() = (if (introCard.enabled) introCard.clampedDuration() else 0.0) +
            scenes.sumOf { it.length } +
            (if (outroCard.enabled) outroCard.clampedDuration() else 0.0)

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
                caption = scene.caption?.normalizedOrNull(),
                actionStart = scene.actionStart,
                actionEnd = scene.actionEnd,
            )
        }
    }

    companion object {
        /** Materializes an automatic plan into explicit scenes with fresh stable keys. */
        fun fromPlan(
            plan: List<TrailerScene>,
            autoPlan: Boolean = true,
            musicVolume: Float = 1f,
            firstKey: Long = 1L,
            introCard: TrailerTitleCard = TrailerTitleCard(),
            outroCard: TrailerTitleCard = TrailerTitleCard(),
        ): EditorProject {
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
                    caption = scene.caption,
                    actionStart = scene.actionStart,
                    actionEnd = scene.actionEnd,
                )
            }
            return EditorProject(scenes, autoPlan, musicVolume, firstKey + scenes.size, introCard, outroCard)
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

/** Length of the protected action interval of a video scene, bounded by the source; 0 when nothing is protected. */
internal fun EditorScene.protectedLength(sourceDuration: Double): Double {
    val a = actionStart ?: return 0.0
    val b = actionEnd ?: return 0.0
    if (!a.isFinite() || !b.isFinite() || b <= a) return 0.0
    return min(b - a, sourceDuration)
}

/** Moves [start] the least needed so a window of [length] still contains the scene's protected action interval. */
internal fun EditorScene.startKeepingAction(start: Double, length: Double, sourceDuration: Double): Double {
    if (protectedLength(sourceDuration) <= 0.0 || length + 1e-9 < protectedLength(sourceDuration)) return start
    val a = actionStart!!
    val b = min(actionEnd!!, sourceDuration)
    val lo = max(0.0, b - length)
    val hi = min(a, max(0.0, sourceDuration - length))
    return if (lo <= hi + 1e-6) start.coerceIn(lo, max(lo, hi)) else start
}

internal data class SceneDurationBounds(
    val safeStart: Double,
    val minLen: Double,
    val maxLen: Double,
    val currentLen: Double,
    val isFixed: Boolean,
)

internal fun resolveSceneDurationBounds(scene: EditorScene, source: TrailerSource?): SceneDurationBounds {
    if (source == null || (!source.isImage && (!source.duration.isFinite() || source.duration <= 0.0))) {
        // Missing/invalid source duration: preserve that scene's duration without inventing capacity
        val len = if (scene.length.isFinite() && scene.length > 0.0) scene.length else MIN_EDITOR_VIDEO_SCENE_SECONDS
        return SceneDurationBounds(
            safeStart = if (scene.start.isFinite()) scene.start else 0.0,
            minLen = len,
            maxLen = len,
            currentLen = len,
            isFixed = true,
        )
    }
    if (source.isImage) {
        val minLen = MIN_PHOTO_SCENE_DURATION
        val maxLen = MAX_EDITOR_PHOTO_SCENE_SECONDS
        val curLen = (if (scene.length.isFinite()) scene.length else minLen).coerceIn(minLen, maxLen)
        return SceneDurationBounds(
            safeStart = 0.0,
            minLen = minLen,
            maxLen = maxLen,
            currentLen = curLen,
            isFixed = false,
        )
    }
    val duration = source.duration
    val minLen = max(min(MIN_EDITOR_VIDEO_SCENE_SECONDS, duration), scene.protectedLength(duration))
    val rawStart = (if (scene.start.isFinite()) scene.start else 0.0).coerceIn(0.0, max(0.0, duration - minLen))
    val curWanted = if (scene.length.isFinite()) max(scene.length, minLen) else minLen
    val safeStart = scene.startKeepingAction(rawStart, curWanted, duration).coerceIn(0.0, max(0.0, duration - minLen))
    val maxLen = max(minLen, duration - safeStart)
    val curLen = curWanted.coerceIn(minLen, maxLen)
    return SceneDurationBounds(
        safeStart = safeStart,
        minLen = minLen,
        maxLen = maxLen,
        currentLen = curLen,
        isFixed = false,
    )
}

/** Puts the scenes reserved by an earlier budget fit back, each after the nearest scene that preceded it then. */
internal fun EditorProject.withBudgetReserveRestored(): EditorProject {
    if (budgetReserve.isEmpty()) return this
    val result = scenes.toMutableList()
    val position = budgetOrder.withIndex().associate { it.value to it.index }
    for (reserved in budgetReserve.sortedBy { position[it.key] ?: Int.MAX_VALUE }) {
        val at = position[reserved.key]
        if (at == null) { result.add(reserved); continue }
        val anchor = (at - 1 downTo 0).firstNotNullOfOrNull { i -> result.indexOfFirst { it.key == budgetOrder[i] }.takeIf { it >= 0 } } ?: -1
        result.add(anchor + 1, reserved)
    }
    return copy(scenes = result, budgetReserve = emptyList(), budgetOrder = emptyList())
}

/** Reorders active and reserved scene pools preserving membership and fields, updating budgetOrder. */
fun EditorProject.withReorderedScenes(orderedSources: List<TrailerSource>): EditorProject {
    val rank = orderedSources.mapIndexed { index, source -> source.id to index }.toMap()
    val sceneComparator = compareBy<EditorScene> { rank[it.sourceId] ?: Int.MAX_VALUE }
        .thenBy { it.start }
        .thenBy { it.key }
    val newActiveScenes = scenes.sortedWith(sceneComparator)
    val newBudgetReserve = budgetReserve.sortedWith(sceneComparator)
    val allSorted = (scenes + budgetReserve).sortedWith(sceneComparator)
    val newBudgetOrder = if (budgetReserve.isEmpty() && budgetOrder.isEmpty()) emptyList() else allSorted.map { it.key }
    return copy(
        scenes = newActiveScenes,
        budgetReserve = newBudgetReserve,
        budgetOrder = newBudgetOrder,
    )
}

/** Range of the scene body an explicit total can ask for: the shortest single whole scene .. every scene at its longest. */
internal fun budgetBodyBounds(project: EditorProject, sources: List<TrailerSource>): Pair<Double, Double> {
    val byId = sources.associateBy { it.id }
    val all = project.copy(budgetReserve = project.budgetReserve.filter { it.sourceId in byId }).withBudgetReserveRestored().scenes
    if (all.isEmpty()) return 0.0 to 0.0
    val bounds = all.map { resolveSceneDurationBounds(it, byId[it.sourceId]) }
    return bounds.minOf { it.minLen } to bounds.sumOf { it.maxLen }
}

/** Computes achievable total duration range [minTotal, maxTotal] for manual editing. */
internal fun calculateManualTotalBounds(project: EditorProject, sources: List<TrailerSource>): Pair<Double, Double> {
    if (project.scenes.isEmpty()) return 0.0 to 0.0
    val sourcesById = sources.associateBy { it.id }
    var minSum = 0.0
    var maxSum = 0.0
    for (scene in project.scenes) {
        val bounds = resolveSceneDurationBounds(scene, sourcesById[scene.sourceId])
        minSum += bounds.minLen
        maxSum += bounds.maxLen
    }
    return minSum to maxSum
}

/**
 * Proportionally redistributes durations across existing scenes in manual mode.
 * Uses monotone bisection over scaling factor lambda on current lengths, capping each scene to its [minLen, maxLen],
 * and redistributes residual to scenes with remaining capacity so achievable target is met.
 */
internal fun redistributeManualTotal(
    project: EditorProject,
    sources: List<TrailerSource>,
    targetTotal: Double,
): Pair<List<EditorScene>, Double> {
    if (project.scenes.isEmpty() || !targetTotal.isFinite() || targetTotal <= 0.0) {
        return project.scenes to project.scenes.sumOf { it.length }
    }
    val sourcesById = sources.associateBy { it.id }
    val sceneBounds = project.scenes.map { resolveSceneDurationBounds(it, sourcesById[it.sourceId]) }
    val totalMin = sceneBounds.sumOf { it.minLen }
    val totalMax = sceneBounds.sumOf { it.maxLen }
    val clampedTarget = targetTotal.coerceIn(totalMin, totalMax)

    val candidateLengths = DoubleArray(sceneBounds.size)
    if (totalMax <= totalMin + 1e-6) {
        for (i in sceneBounds.indices) candidateLengths[i] = sceneBounds[i].minLen
    } else {
        val adjustable = sceneBounds.filterNot { it.isFixed }
        if (adjustable.isEmpty()) {
            for (i in sceneBounds.indices) candidateLengths[i] = sceneBounds[i].currentLen
        } else {
            val maxRatio = adjustable.maxOfOrNull { it.maxLen / max(it.currentLen, 1e-4) } ?: 1.0
            var low = 0.0
            var high = max(1.0, maxRatio) * 1.5 + 1.0

            for (iter in 0 until 60) {
                val mid = (low + high) / 2.0
                var sum = 0.0
                for (i in sceneBounds.indices) {
                    val b = sceneBounds[i]
                    sum += if (b.isFixed) b.currentLen else (b.currentLen * mid).coerceIn(b.minLen, b.maxLen)
                }
                if (sum < clampedTarget) {
                    low = mid
                } else {
                    high = mid
                }
            }

            val factor = (low + high) / 2.0
            for (i in sceneBounds.indices) {
                val b = sceneBounds[i]
                candidateLengths[i] = if (b.isFixed) b.currentLen else (b.currentLen * factor).coerceIn(b.minLen, b.maxLen)
            }

            var remainder = clampedTarget - candidateLengths.sum()
            if (remainder > 1e-6) {
                for (i in candidateLengths.indices) {
                    val b = sceneBounds[i]
                    if (b.isFixed) continue
                    val cap = b.maxLen - candidateLengths[i]
                    if (cap > 1e-6) {
                        val delta = min(remainder, cap)
                        candidateLengths[i] += delta
                        remainder -= delta
                        if (remainder <= 1e-6) break
                    }
                }
            } else if (remainder < -1e-6) {
                var deficit = -remainder
                for (i in candidateLengths.indices) {
                    val b = sceneBounds[i]
                    if (b.isFixed) continue
                    val reducible = candidateLengths[i] - b.minLen
                    if (reducible > 1e-6) {
                        val delta = min(deficit, reducible)
                        candidateLengths[i] -= delta
                        deficit -= delta
                        if (deficit <= 1e-6) break
                    }
                }
            }
        }
    }

    val finalScenes = project.scenes.mapIndexed { i, scene ->
        val b = sceneBounds[i]
        val source = sourcesById[scene.sourceId]
        val (safeStart, safeLen) = if (source != null && !b.isFixed) {
            val (cs, cl) = clampSceneWindow(source, b.safeStart, candidateLengths[i])
            if (source.isImage) cs to cl else scene.startKeepingAction(cs, cl, source.duration).let { ks -> clampSceneWindow(source, ks, cl) }
        } else {
            b.safeStart to candidateLengths[i]
        }
        scene.copy(start = safeStart, length = safeLen)
    }
    return finalScenes to finalScenes.sumOf { it.length }
}

/**
 * Applies a chosen clip length to every existing scene in manual mode (videos + weighted photos),
 * respecting per-source boundaries (video duration/start and photo max 30s) and preserving metadata.
 */
internal fun applyManualClipLength(
    project: EditorProject,
    sources: List<TrailerSource>,
    targetClipLength: Double,
): Pair<List<EditorScene>, Double> {
    if (project.scenes.isEmpty() || !targetClipLength.isFinite() || targetClipLength <= 0.0) {
        return project.scenes to project.scenes.sumOf { it.length }
    }
    val sourcesById = sources.associateBy { it.id }
    val newScenes = project.scenes.map { scene ->
        val source = sourcesById[scene.sourceId]
        if (source == null) {
            scene
        } else if (!source.isImage && (!source.duration.isFinite() || source.duration <= 0.0)) {
            // Missing/invalid source duration: preserve that scene's duration
            scene
        } else if (source.isImage) {
            val migratedSource = if (source.photoPolicyVersion < 2) {
                source.copy(photoDurationRatio = source.effectivePhotoDurationRatio(), photoPolicyVersion = 2)
            } else source
            val targetLen = migratedSource.weightedPhotoLength(targetClipLength)
            val (start, length) = clampSceneWindow(migratedSource, 0.0, targetLen)
            scene.copy(start = start, length = length)
        } else {
            val minLen = min(MIN_EDITOR_VIDEO_SCENE_SECONDS, source.duration)
            val safeStart = (if (scene.start.isFinite()) scene.start else 0.0).coerceIn(0.0, max(0.0, source.duration - minLen))
            val wanted = max(targetClipLength, scene.protectedLength(source.duration))
            val (start, length) = clampSceneWindow(source, scene.startKeepingAction(safeStart, wanted, source.duration), wanted)
            scene.copy(start = start, length = length)
        }
    }
    return newScenes to newScenes.sumOf { it.length }
}

enum class EditorSaveState { IDLE, SAVING, SAVED, FAILED }

/** A running or finished export of a fixed editor snapshot; editing continues independently. */
data class EditorExportState(
    val running: Boolean,
    val snapshotRevision: Long,
    val status: String,
    val progress: Float? = null,
    val error: String? = null,
    // The snapshot's scenes while running (render preview only; never persisted).
    val scenes: List<TrailerScene> = emptyList(),
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
