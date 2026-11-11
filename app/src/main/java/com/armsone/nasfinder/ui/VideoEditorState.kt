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
    // Excluded from rendering/output but kept in project (distinct from deletion).
    val excluded: Boolean = false,
    // Excluded strictly due to budget fitting (restored first on budget change).
    val budgetExcluded: Boolean = false,
    // Explicit whole-manual protection lock.
    val automationProtected: Boolean = false,
    // Individual field categories modified by user.
    val manual: ManualFields = ManualFields.NONE,
    // Clip adjustments (speed rate, color adjustments, keyframes, transitions, timed caption cues, suppressions).
    val adjustments: TrailerClipAdjustments? = null,
) {
    val hasCustomFraming: Boolean
        get() = fit == SceneFit.FIT || focusX != 0f || focusY != 0f

    val timingProtected: Boolean
        get() = automationProtected || manual.contains(ManualField.TIMING)

    val exclusionProtected: Boolean
        get() = automationProtected || manual.contains(ManualField.EXCLUSION)

    val orderProtected: Boolean
        get() = automationProtected || manual.contains(ManualField.ORDER)

    val audioProtected: Boolean
        get() = automationProtected || manual.contains(ManualField.AUDIO)

    val framingProtected: Boolean
        get() = automationProtected || manual.contains(ManualField.FRAMING)

    val captionProtected: Boolean
        get() = automationProtected || manual.contains(ManualField.CAPTION)

    val hasManualContent: Boolean
        get() = automationProtected || manual.hasManualContent()

    val isPureAutomatic: Boolean
        get() = !automationProtected && manual.isEmpty()

    fun hardAnchorSpan(): Pair<Double, Double>? {
        if (timingProtected) {
            if (start.isFinite() && length.isFinite() && length > 0.0) {
                return start to (start + length)
            }
        }
        if (length.isFinite() && start.isFinite() && length > 0.0) {
            return adjustments?.anchorSpan(start, start + length)
        }
        return null
    }

    fun softActionSpan(): Pair<Double, Double>? {
        if (actionStart != null && actionEnd != null && actionStart.isFinite() && actionEnd.isFinite() && actionEnd > actionStart) {
            return actionStart to actionEnd
        }
        return null
    }

    fun protectedSpan(): Pair<Double, Double>? {
        val hard = hardAnchorSpan()
        val soft = softActionSpan()
        return when {
            hard != null && soft != null -> min(hard.first, soft.first) to max(hard.second, soft.second)
            hard != null -> hard
            else -> soft
        }
    }
}

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
    // Voiceover recording takes for this project.
    val voiceovers: List<TrailerVoiceoverTake> = emptyList(),
    // Default/batch caption style for the project.
    val batchCaption: SceneCaption? = null,
) {
    fun scene(key: Long): EditorScene? = scenes.firstOrNull { it.key == key }

    val totalDuration: Double
        get() {
            val fullMappings = canonicalClipMappings(includeCards = true)
            val canonicalVisualEnd = fullMappings.maxOfOrNull { it.outputStart + it.outputDuration } ?: 0.0
            val voiceFloor = voiceovers.mapNotNull { it.requiredOutputEnd() }.maxOrNull() ?: 0.0
            return max(canonicalVisualEnd, voiceFloor)
        }

    fun toTrailerScenes(sources: List<TrailerSource>): List<TrailerScene> {
        val byId = sources.associateBy { it.id }
        return scenes.filter { !it.excluded }.map { scene ->
            val source = byId[scene.sourceId] ?: TrailerSource(
                id = scene.sourceId,
                name = "",
                file = File(""),
                duration = if (scene.length.isFinite() && scene.length > 0.0) scene.length else MIN_EDITOR_VIDEO_SCENE_SECONDS,
                width = 1920,
                height = 1080,
                analysis = AudioAnalysisResult(emptyList(), 0.0, listOf(0.0), ClipAudioAvailability.NoTrack, ClipHighlightSource.Fallback),
                thumbnail = null,
                isImage = false,
            )
            // Same exact-window contract as fit/redistribute: floor 1/30*rate, real source limit, manual anchors; no policy-minimum re-inflation.
            val bounds = resolveSceneDurationBounds(scene, source)
            val safeStart = if (bounds.safeStart.isFinite()) bounds.safeStart else scene.start
            val safeLength = if (bounds.currentLen.isFinite() && bounds.currentLen > 0.0) bounds.currentLen else scene.length
            TrailerScene(
                source = source,
                start = safeStart,
                length = safeLength,
                fit = scene.fit,
                focusX = scene.focusX.coerceIn(-1f, 1f),
                focusY = scene.focusY.coerceIn(-1f, 1f),
                sourceVolume = if (source.isImage) 1f else scene.sourceVolume.coerceIn(0f, 2f),
                caption = scene.caption?.normalizedOrNull(),
                actionStart = scene.actionStart,
                actionEnd = scene.actionEnd,
                adjustments = scene.adjustments,
                sceneKey = scene.key,
                manual = scene.manual,
                automationProtected = scene.automationProtected,
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
                    excluded = false,
                    budgetExcluded = false,
                    automationProtected = false,
                    manual = ManualFields.NONE,
                    adjustments = null,
                )
            }
            return EditorProject(scenes, autoPlan, musicVolume, firstKey + scenes.size, introCard, outroCard)
        }
    }
}

/**
 * Canonical timeline mappings for active (non-excluded) scenes.
 * Includes the outro card when enabled so the last body scene's dissolve into outro is clamped identically to export/preview.
 * When [includeCards] is true, synthetic intro (-999L) and outro (-998L) card mappings are also returned.
 */
fun EditorProject.canonicalClipMappings(includeCards: Boolean = false): List<TrailerClipTimeMapping> {
    val active = scenes.filter { !it.excluded }
    if (active.isEmpty() && !includeCards) return emptyList()
    val introDur = if (includeCards && introCard.enabled) introCard.clampedDuration() else 0.0
    val outroDur = if (outroCard.enabled) outroCard.clampedDuration() else 0.0
    val inputs = buildList {
        if (introDur > 0.0) {
            add(
                TrailerClipInput(
                    sceneKey = -999L,
                    sourceId = -999,
                    sourceStart = 0.0,
                    sourceLength = introDur,
                    rate = 1.0,
                    requestedOutgoingOverlap = 0.0,
                )
            )
        }
        for (scene in active) {
            val safeStart = if (scene.start.isFinite() && scene.start >= 0.0) scene.start else 0.0
            val safeLen = if (scene.length.isFinite() && scene.length > 0.0001) scene.length else 0.0001
            val rate = scene.adjustments?.rate?.takeIf { it.isFinite() && it in 0.25..4.0 } ?: 1.0
            val overlap = scene.adjustments?.transition?.dissolveDuration?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
            add(
                TrailerClipInput(
                    sceneKey = scene.key,
                    sourceId = scene.sourceId,
                    sourceStart = safeStart,
                    sourceLength = safeLen,
                    rate = rate,
                    requestedOutgoingOverlap = overlap,
                )
            )
        }
        if (outroDur > 0.0 && (active.isNotEmpty() || includeCards)) {
            add(
                TrailerClipInput(
                    sceneKey = -998L,
                    sourceId = -998,
                    sourceStart = 0.0,
                    sourceLength = outroDur,
                    rate = 1.0,
                    requestedOutgoingOverlap = 0.0,
                )
            )
        }
    }
    if (inputs.isEmpty()) return emptyList()
    val mappings = try {
        TrailerClipTimeMappingFactory.buildTimelineMappings(inputs)
    } catch (_: Exception) {
        var cur = 0.0
        inputs.map { inp ->
            val outDur = inp.sourceLength / inp.rate
            val m = TrailerClipTimeMapping(
                sceneKey = inp.sceneKey,
                sourceId = inp.sourceId,
                sourceStart = inp.sourceStart,
                sourceLength = inp.sourceLength,
                rate = inp.rate,
                outputStart = cur,
                outputDuration = outDur,
            )
            cur += outDur
            m
        }
    }
    return if (includeCards) mappings else mappings.filter { it.sceneKey >= 0L }
}

/** Total body duration of all active scenes in output seconds (full canonical visual duration minus card durations). */
fun EditorProject.bodyDuration(): Double {
    val active = scenes.filter { !it.excluded }
    if (active.isEmpty()) return 0.0
    val fullMappings = canonicalClipMappings(includeCards = true)
    val fullVisual = fullMappings.maxOfOrNull { it.outputStart + it.outputDuration } ?: 0.0
    val intro = if (introCard.enabled) introCard.clampedDuration() else 0.0
    val outro = if (outroCard.enabled) outroCard.clampedDuration() else 0.0
    return max(0.0, fullVisual - intro - outro)
}

/** Output start time for each active scene key, offset by intro card duration when [includeIntro] is true. */
fun EditorProject.outputStarts(includeIntro: Boolean = true): Map<Long, Double> {
    val introOffset = if (includeIntro && introCard.enabled) introCard.clampedDuration() else 0.0
    return canonicalClipMappings(includeCards = false).associate { it.sceneKey to (it.outputStart + introOffset) }
}

/** Converts an output duration change to a source duration delta for the given scene. */
fun sourceDeltaForOutput(scene: EditorScene, outputDelta: Double): Double {
    if (!outputDelta.isFinite()) return 0.0
    val rate = scene.adjustments?.rate?.takeIf { it.isFinite() && it in 0.25..4.0 } ?: 1.0
    return outputDelta * rate
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

/** Length of the hard anchor interval of a scene (manual cues/keyframes or locked timing), bounded by source. */
internal fun EditorScene.hardAnchorLength(sourceDuration: Double): Double {
    val rate = adjustments?.rate?.takeIf { it.isFinite() && it in 0.25..4.0 } ?: 1.0
    val frameFloor = (1.0 / 30.0) * rate
    if (timingProtected) {
        return if (length.isFinite() && length > 0.0) min(length, sourceDuration) else frameFloor
    }
    val span = adjustments?.anchorSpan(start, start + length) ?: return min(frameFloor, sourceDuration)
    val a = span.first
    val b = min(span.second, sourceDuration)
    val len = if (a.isFinite() && b.isFinite() && b > a) b - a else 0.0
    return max(frameFloor, min(len, sourceDuration))
}

/** Length of the protected action interval of a video scene, bounded by the source; 0 when nothing is protected. */
internal fun EditorScene.protectedLength(sourceDuration: Double): Double {
    val span = protectedSpan() ?: return 0.0
    val a = span.first
    val b = span.second
    if (!a.isFinite() || !b.isFinite() || b <= a) return 0.0
    return max(0.0, min(b, sourceDuration) - max(a, 0.0))
}

/** Moves [start] the least needed so a window of [length] contains the scene's hard anchors and/or peak/action. */
internal fun EditorScene.startKeepingAction(start: Double, length: Double, sourceDuration: Double): Double {
    // 1. Hard anchor must be contained if window is large enough
    val hard = hardAnchorSpan()
    if (hard != null) {
        val a = hard.first
        val b = min(hard.second, sourceDuration)
        if (length >= b - a - 1e-6) {
            val lo = max(0.0, b - length)
            val hi = min(a, max(0.0, sourceDuration - length))
            if (lo <= hi + 1e-6) return start.coerceIn(lo, max(lo, hi))
        }
    }
    // 2. Soft action: peak-centered narrowing if window is smaller than action, or contained if window is larger
    val soft = softActionSpan()
    if (soft != null) {
        val a = soft.first
        val b = min(soft.second, sourceDuration)
        if (length < b - a - 1e-6) {
            val peak = (a + b) / 2.0
            val wanted = (peak - 0.5 * length).coerceIn(0.0, max(0.0, sourceDuration - length))
            return wanted
        } else {
            val lo = max(0.0, b - length)
            val hi = min(a, max(0.0, sourceDuration - length))
            if (lo <= hi + 1e-6) return start.coerceIn(lo, max(lo, hi))
        }
    }
    return start.coerceIn(0.0, max(0.0, sourceDuration - length))
}

internal data class SceneDurationBounds(
    val safeStart: Double,
    val minLen: Double,
    val maxLen: Double,
    val currentLen: Double,
    val isFixed: Boolean,
)

internal fun resolveSceneDurationBounds(scene: EditorScene, source: TrailerSource?): SceneDurationBounds {
    val rate = scene.adjustments?.rate?.takeIf { it.isFinite() && it in 0.25..4.0 } ?: 1.0
    val frameFloor = (1.0 / 30.0) * rate
    if (source == null || (!source.isImage && (!source.duration.isFinite() || source.duration <= 0.0))) {
        val len = if (scene.length.isFinite() && scene.length > 0.0) scene.length else frameFloor
        return SceneDurationBounds(
            safeStart = if (scene.start.isFinite()) scene.start else 0.0,
            minLen = len,
            maxLen = len,
            currentLen = len,
            isFixed = true,
        )
    }
    if (scene.timingProtected) {
        val len = if (scene.length.isFinite() && scene.length > 0.0) scene.length else frameFloor
        return SceneDurationBounds(
            safeStart = scene.start,
            minLen = len,
            maxLen = len,
            currentLen = len,
            isFixed = true,
        )
    }
    if (source.isImage) {
        val hardLen = scene.hardAnchorLength(MAX_EDITOR_PHOTO_SCENE_SECONDS)
        val minLen = max(frameFloor, hardLen)
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
    val hardLen = scene.hardAnchorLength(duration)
    val minLen = max(frameFloor, hardLen)
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
    val all = project.copy(budgetReserve = project.budgetReserve.filter { it.sourceId in byId }).withBudgetReserveRestored().scenes.filter { !it.excluded }
    if (all.isEmpty()) return 0.0 to 0.0
    val bounds = all.map { resolveSceneDurationBounds(it, byId[it.sourceId]) }
    val rates = all.map { it.adjustments?.rate?.takeIf { r -> r.isFinite() && r in 0.25..4.0 } ?: 1.0 }
    val minOutput = bounds.indices.minOfOrNull { bounds[it].minLen / rates[it] } ?: 0.0
    val maxOutput = bounds.indices.sumOf { bounds[it].maxLen / rates[it] }
    return minOutput to maxOutput
}

/** Computes achievable total duration range [minTotal, maxTotal] for manual editing in output seconds. */
internal fun calculateManualTotalBounds(project: EditorProject, sources: List<TrailerSource>): Pair<Double, Double> {
    val active = project.scenes.filter { !it.excluded }
    if (active.isEmpty()) return 0.0 to 0.0
    val sourcesById = sources.associateBy { it.id }
    var minSum = 0.0
    var maxSum = 0.0
    for (scene in active) {
        val bounds = resolveSceneDurationBounds(scene, sourcesById[scene.sourceId])
        val rate = scene.adjustments?.rate?.takeIf { it.isFinite() && it in 0.25..4.0 } ?: 1.0
        minSum += bounds.minLen / rate
        maxSum += bounds.maxLen / rate
    }
    return minSum to maxSum
}

/**
 * Proportionally redistributes durations across existing scenes in manual mode.
 * Target is in OUTPUT seconds. Uses monotone bisection over scaling factor on current lengths,
 * capping each scene to its [minLen, maxLen], and reclamps overlaps via canonical timeline mapping
 * so the final map actually meets target within 1 output frame.
 */
internal fun redistributeManualTotal(
    project: EditorProject,
    sources: List<TrailerSource>,
    targetTotal: Double,
): Pair<List<EditorScene>, Double> {
    if (project.scenes.isEmpty() || !targetTotal.isFinite() || targetTotal <= 0.0) {
        return project.scenes to project.bodyDuration()
    }
    val activeIndices = project.scenes.indices.filter { !project.scenes[it].excluded }
    if (activeIndices.isEmpty()) {
        return project.scenes to 0.0
    }
    val sourcesById = sources.associateBy { it.id }
    val sceneBounds = project.scenes.map { resolveSceneDurationBounds(it, sourcesById[it.sourceId]) }
    val rates = project.scenes.map { it.adjustments?.rate?.takeIf { r -> r.isFinite() && r in 0.25..4.0 } ?: 1.0 }
    val transitions = project.scenes.map { it.adjustments?.transition?.dissolveDuration?.takeIf { t -> t.isFinite() && t > 0.0 } ?: 0.0 }
    val outroDur = if (project.outroCard.enabled) project.outroCard.clampedDuration() else 0.0

    fun evalOutputDuration(lengths: DoubleArray): Double {
        val inputs = buildList {
            for (i in activeIndices) {
                val scene = project.scenes[i]
                add(
                    TrailerClipInput(
                        sceneKey = scene.key,
                        sourceId = scene.sourceId,
                        sourceStart = sceneBounds[i].safeStart,
                        sourceLength = max(0.0001, lengths[i]),
                        rate = rates[i],
                        requestedOutgoingOverlap = transitions[i],
                    )
                )
            }
            if (outroDur > 0.0 && activeIndices.isNotEmpty()) {
                add(
                    TrailerClipInput(
                        sceneKey = -998L,
                        sourceId = -998,
                        sourceStart = 0.0,
                        sourceLength = outroDur,
                        rate = 1.0,
                        requestedOutgoingOverlap = 0.0,
                    )
                )
            }
        }
        val mappings = try {
            TrailerClipTimeMappingFactory.buildTimelineMappings(inputs)
        } catch (_: Exception) {
            var cur = 0.0
            inputs.map { inp ->
                val d = inp.sourceLength / inp.rate
                val m = TrailerClipTimeMapping(
                    sceneKey = inp.sceneKey,
                    sourceId = inp.sourceId,
                    sourceStart = inp.sourceStart,
                    sourceLength = inp.sourceLength,
                    rate = inp.rate,
                    outputStart = cur,
                    outputDuration = d,
                )
                cur += d
                m
            }
        }
        val totalEnd = mappings.maxOfOrNull { it.outputStart + it.outputDuration } ?: 0.0
        return max(0.0, totalEnd - outroDur)
    }

    val minLengths = DoubleArray(sceneBounds.size) { sceneBounds[it].minLen }
    val maxLengths = DoubleArray(sceneBounds.size) { sceneBounds[it].maxLen }
    val outMin = evalOutputDuration(minLengths)
    val outMax = evalOutputDuration(maxLengths)
    val clampedTarget = targetTotal.coerceIn(outMin, outMax)

    val candidateLengths = DoubleArray(sceneBounds.size)
    if (outMax <= outMin + 1e-6) {
        for (i in sceneBounds.indices) candidateLengths[i] = sceneBounds[i].minLen
    } else {
        val adjustable = activeIndices.filterNot { sceneBounds[it].isFixed }
        if (adjustable.isEmpty()) {
            for (i in sceneBounds.indices) candidateLengths[i] = sceneBounds[i].currentLen
        } else {
            var low = 0.0
            var high = max(1.0, adjustable.maxOfOrNull { sceneBounds[it].maxLen / max(sceneBounds[it].currentLen, 1e-4) } ?: 1.0) * 2.0 + 1.0

            for (iter in 0 until 60) {
                val mid = (low + high) / 2.0
                for (i in sceneBounds.indices) {
                    val b = sceneBounds[i]
                    candidateLengths[i] = if (b.isFixed) b.currentLen else (b.currentLen * mid).coerceIn(b.minLen, b.maxLen)
                }
                val curDur = evalOutputDuration(candidateLengths)
                if (curDur < clampedTarget) {
                    low = mid
                } else {
                    high = mid
                }
            }

            val finalFactor = (low + high) / 2.0
            for (i in sceneBounds.indices) {
                val b = sceneBounds[i]
                candidateLengths[i] = if (b.isFixed) b.currentLen else (b.currentLen * finalFactor).coerceIn(b.minLen, b.maxLen)
            }

            // Fine tuning residual to meet target within 1 output frame (1/30s)
            val currentDur = evalOutputDuration(candidateLengths)
            val frameTime = 1.0 / 30.0
            if (currentDur < clampedTarget - frameTime) {
                var remainder = clampedTarget - currentDur
                for (i in adjustable) {
                    val b = sceneBounds[i]
                    val capOutput = (b.maxLen - candidateLengths[i]) / rates[i]
                    if (capOutput > 1e-6) {
                        val deltaOut = min(remainder, capOutput)
                        candidateLengths[i] += deltaOut * rates[i]
                        remainder -= deltaOut
                        if (remainder <= 1e-6) break
                    }
                }
            } else if (currentDur > clampedTarget + frameTime) {
                var deficit = currentDur - clampedTarget
                for (i in adjustable) {
                    val b = sceneBounds[i]
                    val reducibleOutput = (candidateLengths[i] - b.minLen) / rates[i]
                    if (reducibleOutput > 1e-6) {
                        val deltaOut = min(deficit, reducibleOutput)
                        candidateLengths[i] -= deltaOut * rates[i]
                        deficit -= deltaOut
                        if (deficit <= 1e-6) break
                    }
                }
            }
        }
    }

    val finalScenes = project.scenes.mapIndexed { i, scene ->
        if (scene.excluded) {
            scene
        } else {
            val b = sceneBounds[i]
            val source = sourcesById[scene.sourceId]
            val (safeStart, safeLen) = if (source != null && !b.isFixed) {
                if (source.isImage) {
                    // Exact output-frame floor (scaled by rate) and the manual anchor end survive; no policy-minimum re-expansion.
                    0.0 to candidateLengths[i].coerceIn(b.minLen, max(b.minLen, MAX_EDITOR_PHOTO_SCENE_SECONDS))
                }
                else {
                    // One output frame (scaled to source by the rate) is the floor; never re-expand to MIN_EDITOR_VIDEO_SCENE_SECONDS.
                    val duration = source.duration
                    val floor = min(b.minLen, duration)
                    val len = candidateLengths[i].coerceIn(floor, max(floor, duration))
                    val ks = scene.startKeepingAction(b.safeStart.coerceIn(0.0, max(0.0, duration - len)), len, duration)
                    ks.coerceIn(0.0, max(0.0, duration - len)) to len
                }
            } else {
                b.safeStart to candidateLengths[i]
            }
            scene.copy(start = safeStart, length = safeLen)
        }
    }
    val finalBodyDur = project.copy(scenes = finalScenes).bodyDuration()
    return finalScenes to finalBodyDur
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
