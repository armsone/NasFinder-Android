package com.armsone.nasfinder.ui

import android.graphics.Bitmap
import java.io.File
import kotlin.math.*

// Default .55 is the neutral HanYang quality/similarity ratio used until analyzeTrailerPhotos()
// (TrailerPhotoAnalysis.kt) fills in a real per-photo measurement; kept stable across preview
// recomposition, export and saved-draft re-open because it lives on this immutable source.
const val DEFAULT_PHOTO_DURATION_RATIO: Double = .55
const val MIN_PHOTO_DURATION_RATIO: Double = .01
internal const val MIN_PHOTO_SCENE_DURATION: Double = 1.0 / 30.0

fun migratePhotoDurationRatio(r: Double): Double {
    if (!r.isFinite() || r <= 0.0) return DEFAULT_PHOTO_DURATION_RATIO
    return if (r < 0.35) max(0.01, r * (0.10 / 0.35))
    else 0.10 + (r.coerceIn(0.35, 0.75) - 0.35) * (0.85 / 0.40)
}

data class TrailerSource(
    val id: Int, val name: String, val file: File, val duration: Double,
    val width: Int, val height: Int, val analysis: AudioAnalysisResult, val thumbnail: Bitmap?,
    val isImage: Boolean = false,
    val captureTimeMillis: Long = 0L,
    // HanYang quality/similarity ratio of the configured video clip length this photo's scene
    // should occupy: .10..95 ordinary (best photo highest, similar duplicates lower), down to
    // MIN_PHOTO_DURATION_RATIO (.01) for extreme near-duplicates. Set by analyzeTrailerPhotos().
    val photoDurationRatio: Double = DEFAULT_PHOTO_DURATION_RATIO,
    val photoPolicyVersion: Int = 2,
    // Stable identity of the Finder item this source was prepared from ("remote:<conn>:<path>",
    // "phonehard:<uuid>", "local:<uri>", "web:<path>"); null for legacy drafts. Only used to show 사용 중.
    val originKey: String? = null,
) {
    fun effectivePhotoDurationRatio(): Double {
        val raw = if (photoDurationRatio.isFinite() && photoDurationRatio > 0.0) photoDurationRatio else DEFAULT_PHOTO_DURATION_RATIO
        val ratio = if (photoPolicyVersion < 2) migratePhotoDurationRatio(raw) else raw
        return if (ratio.isFinite() && ratio > 0.0) ratio else DEFAULT_PHOTO_DURATION_RATIO
    }

    // Actual scene duration for this photo given the configured video-clip ("base clip") length.
    // Uses raw stored ratio (clamped frame...30s) so opening legacy drafts does not silently change durations.
    fun weightedPhotoLength(clipLength: Double): Double {
        val raw = if (photoDurationRatio.isFinite() && photoDurationRatio > 0.0) photoDurationRatio else DEFAULT_PHOTO_DURATION_RATIO
        return (clipLength * raw).coerceIn(MIN_PHOTO_SCENE_DURATION, MAX_EDITOR_PHOTO_SCENE_SECONDS)
    }
    // This source's own evidence-based clip length (see TrailerSourceEvidence), always within
    // 0 < clip <= duration for a valid video, so sub-second videos stay valid.
    val recommendedLength: Double get() =
        if (isImage) UNKNOWN_CLIP_SECONDS else if (hasValidDuration) recommendationEvidence().clipLength else UNKNOWN_CLIP_SECONDS
    // Total of the recommended plan when this source is edited alone (same counts as the batch plan).
    val recommendedTotal: Double get() = recommendTrailerPlan(listOf(this))?.total ?: 0.0
    internal val hasValidDuration: Boolean get() = duration.isFinite() && duration > 0.0
}

private const val UNKNOWN_CLIP_SECONDS = 4.0
private const val MAX_RECOMMENDED_SCENES_PER_SOURCE = 8
private const val MAX_TRAILER_TOTAL_SECONDS = 7200.0
private const val AUTOMATIC_TOTAL_TOLERANCE = 0.0005

// Per-source clip floor/ceiling: min(duration, clamp(duration/20, 2, 4)) .. min(duration, clamp(duration/8, 4, 10)).
private fun recommendedClipBounds(duration: Double): ClosedFloatingPointRange<Double> =
    min(duration, (duration / 20.0).coerceIn(2.0, 4.0))..min(duration, (duration / 8.0).coerceIn(4.0, 10.0))

private class TrailerPeakEvidence(val time: Double, val score: Double)

// clipLength: median continuous energetic span around the meaningful peaks (rounded to .5s, clamped to the
// source bounds), or the bounded 4s default when there is no valid evidence. peaks: meaningful peaks,
// strongest first; empty exactly when evidence is invalid (fallback source, silence, flat/NaN waveform, no peaks).
private class TrailerSourceEvidence(val clipLength: Double, val peaks: List<TrailerPeakEvidence>) {
    val hasEvidence: Boolean get() = peaks.isNotEmpty()
}

private fun TrailerSource.recommendationEvidence(): TrailerSourceEvidence {
    val bounds = recommendedClipBounds(duration)
    val unknown = TrailerSourceEvidence(UNKNOWN_CLIP_SECONDS.coerceIn(bounds), emptyList())
    val wave = analysis.waveform
    if (analysis.highlightSource == ClipHighlightSource.Fallback || wave.isEmpty()) return unknown
    val finite = wave.filter { it.isFinite() }
    if (finite.isEmpty()) return unknown
    val top = finite.max()
    val baseline = finite.average()
    if (!(top > 0.0) || top - baseline < top * 0.15) return unknown
    val strongCut = max(baseline, top * 0.55)
    val indexed = analysis.peakTimesSeconds
        .filter { it.isFinite() && it >= 0.0 && it <= duration }
        .distinct()
        .mapNotNull { time ->
            val index = (time / duration * wave.size).toInt().coerceIn(0, wave.lastIndex)
            val score = wave[index]
            if (score.isFinite() && score >= strongCut) Triple(time, index, score) else null
        }
        .sortedByDescending { it.third }
    if (indexed.isEmpty()) return unknown
    val spans = indexed.map { (_, index, score) ->
        val threshold = baseline + (score - baseline) * 0.4
        var left = index
        var right = index
        while (left > 0 && wave[left - 1].let { it.isFinite() && it > threshold }) left--
        while (right + 1 < wave.size && wave[right + 1].let { it.isFinite() && it > threshold }) right++
        (right - left + 1).toDouble() / wave.size * duration
    }.sorted()
    val clip = (round(spans[spans.size / 2] * 2.0) / 2.0).coerceIn(bounds)
    return TrailerSourceEvidence(clip, indexed.map { TrailerPeakEvidence(it.first, it.third) })
}

// Distinct, non-overlapping cut starts (chronological) a source contributes to the recommended plan.
// Evidence sources: strongest peaks whose clip windows do not overlap. Fallback sources: a limited,
// duration-based even spread. Both are capped by the per-source maximum, real capacity and half the source.
private fun recommendedSourceStarts(source: TrailerSource, evidence: TrailerSourceEvidence, clip: Double): List<Double> {
    val duration = source.duration
    val sceneLength = min(clip, duration)
    val capacity = floor(duration / sceneLength + 1e-6).toInt().coerceAtLeast(1)
    val halfBudget = floor(duration / 2.0 / sceneLength + 1e-6).toInt().coerceAtLeast(1)
    val limit = minOf(capacity, halfBudget, MAX_RECOMMENDED_SCENES_PER_SOURCE)
    val maxStart = max(0.0, duration - sceneLength)
    if (evidence.hasEvidence) {
        val starts = mutableListOf<Double>()
        for (peak in evidence.peaks) {
            if (starts.size >= limit) break
            val start = (peak.time - sceneLength / 2).coerceIn(0.0, maxStart)
            if (starts.none { abs(it - start) < sceneLength - 1e-6 }) starts += start
        }
        return starts.sorted()
    }
    val count = when {
        duration >= 60.0 -> 3
        duration >= 20.0 -> 2
        else -> 1
    }.coerceAtMost(limit)
    return (0 until count).map { index -> ((index + 0.5) * duration / count - sceneLength / 2).coerceIn(0.0, maxStart) }
}

internal class TrailerRecommendation(val clipLength: Double, val scenes: List<TrailerScene>) {
    val total: Double get() = scenes.sumOf { it.length }
}

internal class TrailerDefaults(val clipLength: Double, val total: Double)

// One shared policy for the common clip length: median of the video sources' own clips (valid evidence
// preferred), never clamped to the shortest source. 4s when there is no video.
private fun commonClipLength(evidences: List<TrailerSourceEvidence?>): Double {
    val videos = evidences.filterNotNull()
    if (videos.isEmpty()) return UNKNOWN_CLIP_SECONDS
    val pool = videos.filter { it.hasEvidence }.ifEmpty { videos }
    val sorted = pool.map { it.clipLength }.sorted()
    return sorted[sorted.size / 2]
}

// Recommended plan: every photo once at its weighted length in user order, every valid video its own
// distinct evidence-based cuts of min(commonClip, duration). The plan's total is exactly the scene sum,
// no repeats and no trimming. Videos with an invalid duration contribute nothing (as in trailerScenes).
internal fun recommendTrailerPlan(sources: List<TrailerSource>, explicitClip: Double? = null): TrailerRecommendation? {
    if (sources.isEmpty()) return null
    val effectiveSources = sources.map {
        if (it.isImage && it.photoPolicyVersion < 2) {
            it.copy(photoDurationRatio = it.effectivePhotoDurationRatio(), photoPolicyVersion = 2)
        } else it
    }
    val evidences = effectiveSources.map { if (it.isImage || !it.hasValidDuration) null else it.recommendationEvidence() }
    val clip = explicitClip?.takeIf { it.isFinite() && it > 0.0 } ?: commonClipLength(evidences)
    val scenes = effectiveSources.flatMapIndexed { index, source ->
        val evidence = evidences[index]
        when {
            source.isImage -> listOf(TrailerScene(source, 0.0, source.weightedPhotoLength(clip)))
            evidence == null -> emptyList()
            else -> {
                val sceneLength = min(clip, source.duration)
                recommendedSourceStarts(source, evidence, clip).map { TrailerScene(source, it, sceneLength) }
            }
        }
    }
    return if (scenes.isEmpty()) null else TrailerRecommendation(clip, scenes)
}

internal fun recommendedTrailerDefaults(sources: List<TrailerSource>): TrailerDefaults {
    val plan = recommendTrailerPlan(sources) ?: return TrailerDefaults(UNKNOWN_CLIP_SECONDS, 1.0)
    // Not floored at 1s: a lone 0.4s video recommends exactly 0.4s. Above the 2h cap it is manual by design.
    return TrailerDefaults(plan.clipLength, plan.total.coerceAtMost(MAX_TRAILER_TOTAL_SECONDS))
}

// The recommended plan is in effect exactly while the stored total and common clip still equal it (Apple
// parity: |total - automaticTotal| < .0005). Any manual clip/total edit, or a music-length fit, makes this
// null so trailerScenes keeps its explicit manual allocation/repetition semantics.
internal fun automaticTrailerPlan(sources: List<TrailerSource>, total: Double, clipLength: Double): TrailerRecommendation? {
    if (!total.isFinite() || !clipLength.isFinite()) return null
    val plan = recommendTrailerPlan(sources, explicitClip = clipLength) ?: return null
    val planTotal = plan.total
    return plan.takeIf {
        planTotal in 0.0..MAX_TRAILER_TOTAL_SECONDS &&
            abs(total - planTotal) < AUTOMATIC_TOTAL_TOLERANCE
    }
}

object NaturalOrderComparator : Comparator<String> {
    override fun compare(s1: String?, s2: String?): Int {
        if (s1 == null && s2 == null) return 0
        if (s1 == null) return -1
        if (s2 == null) return 1
        val collator = java.text.Collator.getInstance(java.util.Locale.KOREAN)
        var i1 = 0
        var i2 = 0
        while (i1 < s1.length && i2 < s2.length) {
            val c1 = s1[i1]
            val c2 = s2[i2]
            if (c1.isDigit() && c2.isDigit()) {
                var j1 = i1
                while (j1 < s1.length && s1[j1].isDigit()) j1++
                var j2 = i2
                while (j2 < s2.length && s2[j2].isDigit()) j2++

                val numStr1 = s1.substring(i1, j1)
                val numStr2 = s2.substring(i2, j2)

                // Compare numeric values using BigInteger or Long fallback to avoid overflow
                val diff = try {
                    java.math.BigInteger(numStr1).compareTo(java.math.BigInteger(numStr2))
                } catch (_: Exception) {
                    numStr1.compareTo(numStr2)
                }
                if (diff != 0) return diff

                // If numeric value is identical, compare leading zero count or representation length
                if (numStr1.length != numStr2.length) {
                    return numStr1.length.compareTo(numStr2.length)
                }

                i1 = j1
                i2 = j2
            } else {
                var j1 = i1
                while (j1 < s1.length && !s1[j1].isDigit()) j1++
                var j2 = i2
                while (j2 < s2.length && !s2[j2].isDigit()) j2++

                val chunk1 = s1.substring(i1, j1)
                val chunk2 = s2.substring(i2, j2)
                val diff = collator.compare(chunk1, chunk2)
                if (diff != 0) return diff

                i1 = j1
                i2 = j2
            }
        }
        return s1.length.compareTo(s2.length)
    }
}

/** How a scene fills the output frame: FILL crops to cover (focus picks the visible part), FIT letterboxes. */
enum class SceneFit(val title: String) { FILL("채우기"), FIT("맞추기") }

data class TrailerScene(
    val source: TrailerSource,
    val start: Double,
    val length: Double,
    val fit: SceneFit = SceneFit.FILL,
    // -1..1 horizontal/vertical focus of the visible crop window when [fit] is FILL; 0 is centered.
    val focusX: Float = 0f,
    val focusY: Float = 0f,
    // Original clip audio gain (0 = mute, 1 = as recorded); independent of the whole-video music volume.
    val sourceVolume: Float = 1f,
)
enum class TrailerFormat(val title: String, val width: Int, val height: Int) {
    ORIGINAL("원본", 0, 0),
    SQUARE("1:1", 1080, 1080),
    PORTRAIT("3:4", 1080, 1440),
    LANDSCAPE("4:3", 1440, 1080),
    STORY("9:16", 1080, 1920),
    WIDE("16:9", 1920, 1080);

    // ORIGINAL follows the first video in the current source order (leading photos ignored), else the first photo.
    // Source width/height are already display-oriented. Preview and export share this single resolution.
    fun resolveDimensions(orderedSources: List<TrailerSource>): Pair<Int, Int> {
        if (this != ORIGINAL) return width to height
        val reference = orderedSources.firstOrNull { !it.isImage } ?: orderedSources.firstOrNull()
        val srcW = reference?.width ?: 1920
        val srcH = reference?.height ?: 1080
        if (srcW <= 0 || srcH <= 0) return 1920 to 1080
        val (w, h) = if (srcW >= srcH) {
            val baseH = 1080
            val calcW = ((1080.0 * srcW / srcH).roundToInt() / 2) * 2
            calcW to baseH
        } else {
            val baseW = 1080
            val calcH = ((1080.0 * srcH / srcW).roundToInt() / 2) * 2
            baseW to calcH
        }
        return max(2, (w / 2) * 2) to max(2, (h / 2) * 2)
    }

    companion object {
        fun closest(width: Int, height: Int) = entries.filter { it != ORIGINAL }.minBy { abs(it.width.toDouble() / it.height - width.toDouble() / height.coerceAtLeast(1)) }
    }
}

data class TrailerMusicTrack(
    val file: File,
    val name: String,
    val durationSeconds: Double,
)

enum class TrailerMusicPolicy(val title: String) {
    FIT_MUSIC_LENGTH("음악 길이에 맞추기"),
    LOOP_MUSIC("음악 반복"),
    SINGLE("음악 한 번 재생"),
}

data class TrailerSession(
    val directory: File, val sources: List<TrailerSource> = emptyList(),
    val total: Double = 0.0, val clipLength: Double = 4.0,
    val photoClipLength: Double = defaultPhotoClipLength(clipLength, sources.any { !it.isImage }),
    val format: TrailerFormat = TrailerFormat.ORIGINAL,
    val output: File? = null, val busy: Boolean = true, val status: String = "영상을 준비하는 중…",
    val progress: Float? = null, val error: String? = null, val saved: Boolean = false,
    val isMusicOverlayMode: Boolean = false,
    val musicTrack: TrailerMusicTrack? = null,
    // The stored music choice whose file is unreadable right now. Kept (and saved) until the user explicitly
    // removes or replaces it; never used for playback/export while unavailable.
    val unavailableMusic: TrailerMusicTrack? = null,
    val musicPolicy: TrailerMusicPolicy = TrailerMusicPolicy.LOOP_MUSIC,
    val musicFitCount: Int = 1,
    val autoDucking: Boolean = true,
    // Export was requested while [unavailableMusic] is unresolved: the user must reconnect, reselect or skip it.
    val musicExportPrompt: Boolean = false,
    val awaitingInitialSettings: Boolean = false,
    // True only once every source is staged and analysed and the first plan exists.
    val prepared: Boolean = false,
    val draftId: String? = null,
    // False while sources only carry the placeholder analysis of music mode (no highlight/photo ratio data).
    val highlightAnalyzed: Boolean = true,
    val orderMode: String? = "추가순",
    // Explicit, editable scene list of the CURRENT project (null only while staging). Export and autosave use it.
    val editor: EditorProject? = null,
    // Incremented on every editor change; export snapshots and autosave compare against it.
    val revision: Long = 0L,
    val title: String? = null,
    val saveState: EditorSaveState = EditorSaveState.IDLE,
    val saveError: String? = null,
    val export: EditorExportState? = null,
    // Editor revision the current [output] was rendered from.
    val outputRevision: Long? = null,
    // Finder items being prepared for THIS project (download/copy/analysis), with real per-item state.
    val pendingAdds: List<EditorPendingAdd> = emptyList(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    // 비공개 오염 플래그: 프라이버시 모드에서 시작했거나 입력 중 하나라도 비공개 출처이면 켜지고 풀리지 않는다.
    // 편집본·출력(폰하드 파일)·컬렉션 영상으로 이어진다. 시작 시점에 한 번 정해지며 이후 모드 전환과 무관하다.
    val originPrivate: Boolean = false,
) {
    val hasVideos: Boolean get() = sources.any { !it.isImage }
    // Music-overlay mode keeps every photo at a fixed 1s Ken Burns scene; trailer mode now weighs
    // each photo's own HanYang quality/similarity ratio against the configured video clip length
    // instead of forcing a uniform value, so this is only an estimate used for legacy callers.
    val effectivePhotoClipLength: Double get() = if (isMusicOverlayMode) 1.0 else photoClipLength
    val originalTotal get() = sources.sumOf {
        if (!it.isImage) it.duration else if (isMusicOverlayMode) 1.0 else it.weightedPhotoLength(clipLength)
    }
    val scenes: List<TrailerScene> get() = editor?.toTrailerScenes(sources) ?: autoPlanScenes()

    /** The recommended/automatic plan for the current parameters (what [editor] mirrors while autoPlan is true). */
    fun autoPlanScenes(): List<TrailerScene> = if (isMusicOverlayMode) {
        musicOverlayScenes(sources, 1.0, plannedMusic, musicPolicy, musicFitCount)
    } else {
        trailerScenes(sources, total, clipLength, photoClipLength, plannedMusic, musicPolicy, musicFitCount)
    }

    /** Music the plan follows: the stored choice even while its file is unavailable (only its length is used). */
    val plannedMusic: TrailerMusicTrack? get() = musicTrack ?: unavailableMusic

    val displayTitle: String get() = title?.takeIf { it.isNotBlank() }
        ?: if (isMusicOverlayMode) "뮤직비디오 (${sources.size}개 미디어)" else "예고편 편집 (${sources.size}개 미디어)"
}

internal fun defaultPhotoClipLength(videoLength: Double, hasVideos: Boolean = true): Double = videoLength / 2.0

// Ported from VideoTrailerBatchPlan: distinct regions, equal lengths and proportional allocation.
// Each photo's own scene length is its HanYang-weighted share of `length` (TrailerSource.weightedPhotoLength);
// `photoLength` is kept only as a rough estimate for the scene-count budget below, never as the actual photo duration.
internal fun trailerScenes(
    sources: List<TrailerSource>,
    total: Double,
    length: Double,
    photoLength: Double = defaultPhotoClipLength(length, sources.any { !it.isImage }),
    musicTrack: TrailerMusicTrack? = null,
    musicPolicy: TrailerMusicPolicy = TrailerMusicPolicy.LOOP_MUSIC,
    musicFitCount: Int = 1,
): List<TrailerScene> {
    if (sources.isEmpty() || !length.isFinite() || length <= 0 || !photoLength.isFinite() || photoLength <= 0 || !total.isFinite()) return emptyList()

    val fitsMusicLength = musicTrack != null && musicPolicy == TrailerMusicPolicy.FIT_MUSIC_LENGTH && musicTrack.durationSeconds > 0.0
    // Unchanged recommended total + common clip: use the recommendation's own distinct cuts, no repeats.
    if (!fitsMusicLength) automaticTrailerPlan(sources, total, length)?.let { return it.scenes }

    val targetTotal = if (fitsMusicLength && musicTrack != null) {
        musicTrack.durationSeconds * musicFitCount.coerceAtLeast(1)
    } else {
        total.coerceIn(1.0, 7200.0)
    }

    val capacity = sources.map { source ->
        if (source.isImage) 1 else if (source.duration.isFinite() && source.duration > 0) floor(source.duration / length + .000001).toInt().coerceAtLeast(1) else 0
    }

    val lengthFor = sources.map { source ->
        if (source.isImage) source.weightedPhotoLength(length) else min(length, source.duration)
    }

    val counts = IntArray(sources.size)
    var remainingTime = targetTotal

    // First: allocate one to each source whose capacity > 0 and fits remaining time, preserving user order
    for (i in sources.indices) {
        if (capacity[i] > 0 && lengthFor[i] <= remainingTime + 1e-6) {
            counts[i]++
            remainingTime = (remainingTime - lengthFor[i]).coerceAtLeast(0.0)
        }
    }

    // Then: allocate additional video segments whose capacity remains, using existing duration / (counts + 1) priority and must fit remaining time
    while (true) {
        val candidates = sources.indices.filter {
            !sources[it].isImage && counts[it] < capacity[it] && lengthFor[it] <= remainingTime + 1e-6
        }
        if (candidates.isEmpty()) break
        val bestIndex = candidates.maxByOrNull {
            sources[it].duration / (counts[it] + 1)
        } ?: break
        counts[bestIndex]++
        remainingTime = (remainingTime - lengthFor[bestIndex]).coerceAtLeast(0.0)
    }

    // Fallback: if nothing fit but valid capacity exists, preserve previous at-least-one fallback deterministically
    if (counts.all { it == 0 }) {
        val firstEligible = sources.indices.firstOrNull { capacity[it] > 0 }
        if (firstEligible != null) {
            counts[firstEligible] = 1
        }
    }

    val rawScenes = sources.flatMapIndexed { index, source ->
        val count = counts[index]
        if (source.isImage) {
            (0 until count).map { TrailerScene(source, 0.0, source.weightedPhotoLength(length)) }
        } else {
            (0 until count).map { scene ->
                val lower = scene * source.duration / count
                val upper = min(source.duration, (scene + 1) * source.duration / count)
                val peaks = source.analysis.peakTimesSeconds.filter { it >= lower && it < upper }
                val wavePeak = source.analysis.waveform.mapIndexedNotNull { bucket, score ->
                    val time = (bucket + .5) / source.analysis.waveform.size * source.duration
                    if (time >= lower && time < upper) time to score else null
                }.maxByOrNull { it.second }?.first
                val peak = if (source.analysis.highlightSource == ClipHighlightSource.Fallback) (lower + upper) / 2 else peaks.firstOrNull() ?: wavePeak ?: (lower + upper) / 2
                val sceneLength = min(length, source.duration)
                TrailerScene(source, max(lower, min(upper - sceneLength, peak - sceneLength / 2)), sceneLength)
            }
        }
    }

    if (musicTrack != null && musicPolicy == TrailerMusicPolicy.FIT_MUSIC_LENGTH && musicTrack.durationSeconds > 0.0) {
        return fitScenesToTargetDuration(rawScenes, musicTrack.durationSeconds * musicFitCount.coerceAtLeast(1))
    }
    return fitScenesToTargetDuration(rawScenes, targetTotal)
}

/**
 * "영상에 뮤직비디오": Selected full original videos (in chosen order, NO HanYang cuts)
 * plus photos at default 1.0s (or given photoLength) with Ken Burns zoom.
 * If musicPolicy is FIT_MUSIC_LENGTH, repeat scenes or trim tail to match music.
 */
internal fun musicOverlayScenes(
    sources: List<TrailerSource>,
    photoLength: Double,
    musicTrack: TrailerMusicTrack?,
    musicPolicy: TrailerMusicPolicy,
    musicFitCount: Int = 1,
): List<TrailerScene> {
    if (sources.isEmpty()) return emptyList()
    // Photos always get a fixed 1s Ken Burns scene here regardless of the caller's photoLength;
    // only videos' own full duration and the music-fit policy affect this mode's total length.
    val baseScenes = sources.map { source ->
        if (source.isImage) {
            TrailerScene(source, 0.0, 1.0)
        } else {
            TrailerScene(source, 0.0, max(0.5, source.duration))
        }
    }
    if (musicTrack != null && musicPolicy == TrailerMusicPolicy.FIT_MUSIC_LENGTH && musicTrack.durationSeconds > 0.0) {
        return fitScenesToTargetDuration(baseScenes, musicTrack.durationSeconds * musicFitCount.coerceAtLeast(1))
    }
    return baseScenes
}

private fun fitScenesToTargetDuration(scenes: List<TrailerScene>, targetDuration: Double): List<TrailerScene> {
    if (scenes.isEmpty() || targetDuration <= 0.0) return scenes
    val baseSum = scenes.sumOf { it.length }
    if (baseSum <= 0.0) return scenes

    val result = mutableListOf<TrailerScene>()
    var accumulated = 0.0

    // Repeat base scenes sequence if needed
    var sceneIdx = 0
    while (accumulated < targetDuration - 1e-6) {
        val next = scenes[sceneIdx % scenes.size]
        val remaining = targetDuration - accumulated
        if (remaining <= 1e-6) break
        if (next.length <= remaining) {
            result.add(next)
            accumulated += next.length
        } else {
            // Trim this last clip to remaining exact duration
            result.add(next.copy(length = remaining))
            accumulated += remaining
            break
        }
        sceneIdx++
    }
    return result.ifEmpty { scenes }
}
internal fun trailerTime(seconds: Double): String {
    val n = seconds.roundToInt().coerceAtLeast(0)
    return if (n >= 3600) "%d:%02d:%02d".format(n / 3600, n / 60 % 60, n % 60) else "%d:%02d".format(n / 60, n % 60)
}
internal fun trailerLengthTitle(seconds: Double): String {
    val n = seconds.roundToInt().coerceAtLeast(0)
    return listOfNotNull(if (n >= 3600) "${n / 3600}시간" else null, if (n / 60 % 60 > 0) "${n / 60 % 60}분" else null, if (n % 60 > 0 || n == 0) "${n % 60}초" else null).joinToString(" ")
}

internal fun formatAdaptiveDurationTitle(seconds: Double): String {
    if (!seconds.isFinite() || seconds <= 0.0) return "0초"
    val totalTenths = (seconds * 10).roundToLong()
    val sTotal = totalTenths / 10.0
    val h = (sTotal / 3600).toInt()
    val rem = sTotal - h * 3600
    val m = (rem / 60).toInt()
    val s = rem - m * 60
    val parts = mutableListOf<String>()
    if (h > 0) parts.add("${h}시간")
    if (m > 0) parts.add("${m}분")
    val hasFraction = (s * 10).roundToLong() % 10L != 0L
    if (hasFraction) {
        parts.add("%.1f초".format(s))
    } else {
        val sInt = s.roundToInt()
        if (sInt > 0 || (h == 0 && m == 0)) {
            parts.add("${sInt}초")
        }
    }
    return parts.joinToString(" ")
}

internal fun formatCandidateDuration(seconds: Double): String {
    if (!seconds.isFinite() || seconds <= 0.0) return "0초"
    if (seconds < 60.0) {
        return if ((seconds * 10).roundToLong() % 10L != 0L) "%.1f초".format(seconds) else "${seconds.roundToInt()}초"
    }
    val totalTenths = (seconds * 10).roundToLong()
    val sTotal = totalTenths / 10.0
    val h = (sTotal / 3600).toInt()
    val rem = sTotal - h * 3600
    val m = (rem / 60).toInt()
    val s = rem - m * 60
    val hasFraction = (s * 10).roundToLong() % 10L != 0L
    return if (h > 0) {
        if (hasFraction) "%d:%02d:%04.1f".format(h, m, s) else "%d:%02d:%02d".format(h, m, s.roundToInt())
    } else {
        if (hasFraction) "%d:%04.1f".format(m, s) else "%d:%02d".format(m, s.roundToInt())
    }
}

internal fun adaptiveStepSeconds(value: Double): Double = when {
    value < 10.0 -> 0.5
    value < 30.0 -> 1.0
    value < 180.0 -> 5.0
    value < 600.0 -> 30.0
    else -> 60.0
}

internal fun snapToAdaptiveStep(value: Double, minVal: Double, maxVal: Double): Double {
    val clamped = value.coerceIn(minVal, maxVal)
    val step = adaptiveStepSeconds(clamped)
    return (round(clamped / step) * step).coerceIn(minVal, maxVal)
}

internal fun formatStepAmount(seconds: Double): String = when {
    seconds < 1.0 -> "${seconds}초"
    seconds < 60.0 -> if (seconds % 1.0 == 0.0) "${seconds.toInt()}초" else "${seconds}초"
    else -> {
        val mins = (seconds / 60.0).roundToInt()
        "${mins}분"
    }
}

internal fun sliderPositionToValue(position: Float, min: Double, max: Double): Double {
    if (max <= min) return min
    val p = position.toDouble().coerceIn(0.0, 1.0)
    val raw = min + (max - min) * (p * p)
    return snapToAdaptiveStep(raw, min, max)
}

internal fun sliderValueToPosition(value: Double, min: Double, max: Double): Float {
    if (max <= min) return 0f
    val fraction = ((value - min) / (max - min)).coerceIn(0.0, 1.0)
    return sqrt(fraction).toFloat()
}

internal fun totalSliderPositionToValue(position: Float, min: Double, max: Double): Double =
    sliderPositionToValue(position, min, max)

internal fun totalSliderValueToPosition(value: Double, min: Double, max: Double): Float =
    sliderValueToPosition(value, min, max)

internal val InboxDisplayItem.isTrailerVideo: Boolean get() = isTrailerMedia
internal val InboxDisplayItem.isTrailerMedia: Boolean get() {
    val item = com.armsone.nasfinder.model.RemoteFileItem(id.toString(), originalFilename, id.toString(), false, mimeType = mimeType)
    return item.isVideo || item.isImage
}
