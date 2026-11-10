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
// Automatic photo scenes get at least this long so the 채우기 camera can start somewhere, visit the people and settle
// on the main person. Explicit per-scene lengths (editor slider, manual trims) are never raised by it.
internal const val MIN_AUTO_PHOTO_SCENE_SECONDS: Double = 3.0

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
    val isImportant: Boolean = false,
) {
    fun effectivePhotoDurationRatio(): Double {
        val raw = if (photoDurationRatio.isFinite() && photoDurationRatio > 0.0) photoDurationRatio else DEFAULT_PHOTO_DURATION_RATIO
        val ratio = if (photoPolicyVersion < 2) migratePhotoDurationRatio(raw) else raw
        return if (ratio.isFinite() && ratio > 0.0) ratio else DEFAULT_PHOTO_DURATION_RATIO
    }

    // Actual automatic scene duration for this photo given the configured video-clip ("base clip") length: the
    // weighted share, but never shorter than the camera-move minimum. Stored editor scenes keep their own lengths.
    // Important photos receive longer priority duration within configured maximum (not blindly 30s) while integrating
    // HanYang quality ranking.
    fun weightedPhotoLength(clipLength: Double): Double {
        val configuredClipMax = max(clipLength, MIN_AUTO_PHOTO_SCENE_SECONDS)
        return if (isImportant) {
            val hanYangRatio = effectivePhotoDurationRatio()
            val priorityRatio = 0.85 + 0.15 * (hanYangRatio / 0.95).coerceIn(0.0, 1.0)
            (configuredClipMax * priorityRatio).coerceIn(MIN_AUTO_PHOTO_SCENE_SECONDS, configuredClipMax)
        } else {
            unflooredPhotoLength(clipLength).coerceIn(MIN_AUTO_PHOTO_SCENE_SECONDS, configuredClipMax)
        }
    }

    // The pre-3s-floor weighted length (raw stored ratio, clamped frame...configuredClipMax); only used to recognize legacy totals.
    internal fun unflooredPhotoLength(clipLength: Double): Double {
        val raw = if (photoDurationRatio.isFinite() && photoDurationRatio > 0.0) photoDurationRatio else DEFAULT_PHOTO_DURATION_RATIO
        val ratio = if (isImportant) {
            0.85 + 0.15 * (raw / 0.95).coerceIn(0.0, 1.0)
        } else raw
        val configuredClipMax = max(clipLength, MIN_AUTO_PHOTO_SCENE_SECONDS)
        return (clipLength * ratio).coerceIn(MIN_PHOTO_SCENE_DURATION, configuredClipMax)
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

// onset: earliest isolated sharp hit shortly before the peak (the real impact when a louder sustained sound follows it), else the peak itself.
// verified: the classifier confirmed this peak as a real hit (an independent action, unlike a merely loud sound).
// sharp: an isolated sharp hit (a club/ball click that dies away at once) is this action or leads into it. Wind, carts, speech and
// handling are sustained and never sharp, so sharp actions are kept however many a long source has.
private class TrailerPeakEvidence(
    val time: Double,
    val score: Double,
    val spanStart: Double = time,
    val spanEnd: Double = time,
    val onset: Double = time,
    val verified: Boolean = false,
    val sharp: Boolean = false,
    val loud: Boolean = true,
) {
    // An independent hit: the classifier confirmed it, or an isolated sharp click (not a <0.3s split of the same hit) leads into it.
    val independent: Boolean get() = verified || sharp || time - onset >= INDEPENDENT_TRANSIENT_LEAD_SECONDS

    // Source-time interval to keep: lead-in before the (earliest) impact through the follow-through after the peak. Only a loud
    // sharp click is the impact itself; any weaker sound may be a practice swing or set-up before a silent stroke, so it keeps longer.
    fun protectedInterval(duration: Double): Pair<Double, Double> =
        max(0.0, onset - ACTION_LEAD_SECONDS) to min(duration, time + if (sharp && loud) ACTION_FOLLOW_SECONDS else WEAK_ACTION_FOLLOW_SECONDS)
}

// Source-time interval a cut must keep. Real swing footage (setup -> back-swing -> impact -> finish) runs about
// 1.8s before and 2.0s after the audio impact peak, so the cut's peak is kept with that lead-in/follow-through
// (clipped to the source); a cut with no peak keeps the whole candidate window.
internal const val ACTION_LEAD_SECONDS = 2.5
internal const val ACTION_FOLLOW_SECONDS = 3.5

// After a sound that is not a loud click: a silent stroke up to ~4s later (practice swing, quiet putt) plus its result (ball roll, camera follow).
internal const val WEAK_ACTION_FOLLOW_SECONDS = 6.0

// Transients closer than this belong to the same hit (a click split over neighbouring analysis frames).
private const val TRANSIENT_MERGE_SECONDS = 0.6

// A classifier-verified neighbour is merged into kept actions only while the result stays within three protected spans.
private const val MAX_MERGED_ACTION_SECONDS = 3 * (ACTION_LEAD_SECONDS + ACTION_FOLLOW_SECONDS)

// Largest gap between two kept actions of a source that a hit reaching into it closes.
private const val MAX_BRIDGED_GAP_SECONDS = 2.0

// An isolated click up to this long before a louder peak is that action's real impact (the louder sound is what followed it).
private const val ONSET_SEARCH_SECONDS = 3.8

private const val INDEPENDENT_TRANSIENT_LEAD_SECONDS = 0.3

// Lead-in/out kept around the measured energetic span of a peak so a cut never lands on the first/last frame of the action.
internal const val ACTION_SPAN_PAD_SECONDS = 0.25

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
    val verifiedTimes = analysis.verifiedPeakTimesSeconds
    // A classifier-verified hit is kept even when quieter than the strongCut loudness; unverified peaks still need the loud cut.
    val indexed = analysis.peakTimesSeconds
        .filter { it.isFinite() && it >= 0.0 && it <= duration }
        .distinct()
        .mapNotNull { time ->
            val index = (time / duration * wave.size).toInt().coerceIn(0, wave.lastIndex)
            val score = wave[index]
            val verified = verifiedTimes.any { abs(it - time) < 1e-6 }
            if (score.isFinite() && (score >= strongCut || verified)) Triple(time, index, score) to verified else null
        }
        .sortedWith(compareByDescending<Pair<Triple<Double, Int, Double>, Boolean>> { it.second }.thenByDescending { it.first.third })
    if (indexed.isEmpty()) return unknown
    val ranges = indexed.map { (peak, _) ->
        val (_, index, score) = peak
        val threshold = baseline + (max(score, baseline) - baseline) * 0.4
        var left = index
        var right = index
        while (left > 0 && wave[left - 1].let { it.isFinite() && it > threshold }) left--
        while (right + 1 < wave.size && wave[right + 1].let { it.isFinite() && it > threshold }) right++
        left.toDouble() / wave.size * duration to (right + 1).toDouble() / wave.size * duration
    }
    val spans = ranges.map { it.second - it.first }.sorted()
    val clip = (round(spans[spans.size / 2] * 2.0) / 2.0).coerceIn(bounds)
    val transients = analysis.transientTimesSeconds.filter { it.isFinite() && it >= 0.0 && it <= duration }.sorted()
        .fold(mutableListOf<Double>()) { kept, t -> if (kept.isEmpty() || t - kept.last() >= TRANSIENT_MERGE_SECONDS) kept += t; kept }
    val leadWindow = ONSET_SEARCH_SECONDS
    val peaks = indexed.mapIndexed { i, (p, verified) ->
        val onset = transients.filter { it < p.first && p.first - it <= leadWindow }.minOrNull() ?: p.first
        val sharp = transients.any { it <= p.first + TRANSIENT_MERGE_SECONDS && p.first - it <= leadWindow }
        TrailerPeakEvidence(p.first, p.third, ranges[i].first, ranges[i].second, onset, verified, sharp, loud = p.third >= strongCut)
    }
    // A sharp hit the loudness/classifier ranking left out (a quiet putt, a first-person shot in wind) is its own action candidate.
    val covered = { t: Double -> peaks.any { t >= it.onset - 1e-6 && t <= it.time + TRANSIENT_MERGE_SECONDS } }
    val extraHits = transients.filterNot(covered).map { t ->
        val score = wave[(t / duration * wave.size).toInt().coerceIn(0, wave.lastIndex)].takeIf { it.isFinite() } ?: 0.0
        TrailerPeakEvidence(t, score, t, t, t, verified = false, sharp = true, loud = score >= strongCut)
    }
    // Sharp actions first (each is kept), then classifier-verified, then merely loud; strongest first within each.
    val ranked = (peaks + extraHits).sortedWith(
        compareByDescending<TrailerPeakEvidence> { it.sharp }.thenByDescending { it.verified }.thenByDescending { it.score }
    )
    return TrailerSourceEvidence(clip, ranked)
}

// Distinct, non-overlapping cuts (chronological) a source contributes to the recommended plan.
// Evidence sources: strongest peaks whose clip windows do not overlap. Fallback sources: a limited,
// duration-based even spread. Both are capped by the per-source maximum, real capacity and half the source,
// except sharp hits: every one is kept (a long round can hold many shots), a hit next to a kept one widens that cut.
private fun recommendedSourcePicks(source: TrailerSource, evidence: TrailerSourceEvidence, clip: Double): List<SourcePick> =
    rankedSourcePicks(source, evidence, clip).let { if (evidence.hasEvidence) it.sortedBy { pick -> pick.start } else it }

private fun sourceSceneLimit(duration: Double, sceneLength: Double): Int {
    val capacity = floor(duration / sceneLength + 1e-6).toInt().coerceAtLeast(1)
    val halfBudget = floor(duration / 2.0 / sceneLength + 1e-6).toInt().coerceAtLeast(1)
    return minOf(capacity, halfBudget, MAX_RECOMMENDED_SCENES_PER_SOURCE)
}

// A cut start with the peak it was cut for (null for the evidence-free even spread) and the action interval it must keep
// (null = the whole window; wider than the peak's own interval when neighbouring sharp hits were merged into it).
private data class SourcePick(val start: Double, val peak: TrailerPeakEvidence?, val actionStart: Double? = null, val actionEnd: Double? = null)

// Strongest first (evidence sources).
private fun rankedSourcePicks(source: TrailerSource, evidence: TrailerSourceEvidence, clip: Double): List<SourcePick> {
    val duration = source.duration
    val sceneLength = min(clip, duration)
    val limit = sourceSceneLimit(duration, sceneLength)
    val maxStart = max(0.0, duration - sceneLength)
    if (evidence.hasEvidence) {
        val picks = mutableListOf<SourcePick>()
        fun overlaps(a: Double, b: Double, pick: SourcePick) = a < pick.actionEnd!! - 1e-6 && pick.actionStart!! < b - 1e-6
        // A verified hit first met with the source already full is retried once all cuts are known: it may still join one.
        val deferred = mutableListOf<TrailerPeakEvidence>()
        fun consider(peak: TrailerPeakEvidence, retry: Boolean) {
            val (a, b) = peak.protectedInterval(duration)
            val start = (peak.time - sceneLength / 2).coerceIn(0.0, maxStart)
            val clash = picks.indexOfFirst { abs(it.start - start) < sceneLength - 1e-6 || overlaps(a, b, it) }
            if (clash < 0) {
                if (!peak.sharp && picks.size >= limit) {
                    if (!retry && peak.verified) deferred += peak
                    return
                }
                picks += SourcePick(start, peak, a, b)
                return
            }
            // A separate hit next to kept actions is not dropped: they become one action holding all of them. A sharp hit always
            // merges; a classifier-verified one only while the merged action stays short, so runs of noise cannot swallow a source.
            val members = sortedSetOf(clash)
            var action = min(a, picks[clash].actionStart!!) to max(b, picks[clash].actionEnd!!)
            var changed = true
            while (changed) {
                changed = false
                picks.forEachIndexed { i, other ->
                    if (i !in members && overlaps(action.first, action.second, other)) {
                        members += i
                        action = min(action.first, other.actionStart!!) to max(action.second, other.actionEnd!!)
                        changed = true
                    }
                }
            }
            val grows = members.any { action.first < picks[it].actionStart!! - 1e-6 || action.second > picks[it].actionEnd!! + 1e-6 }
            if (!grows) return
            if (!(peak.sharp || (peak.verified && action.second - action.first <= MAX_MERGED_ACTION_SECONDS))) {
                // Too long to merge: a verified hit still outside every kept action gets the free gap between them, if the hit
                // itself (with a second either side) fits there. It only fills space next to kept actions, so the source limit does not apply.
                val lo = max(a, picks.filter { it.actionEnd!! <= peak.time + 1e-6 }.maxOfOrNull { it.actionEnd!! } ?: a)
                val hi = min(b, picks.filter { it.actionStart!! >= peak.time - 1e-6 }.minOfOrNull { it.actionStart!! } ?: b)
                if (peak.independent && peak.time - lo >= 1.0 - 1e-6 && hi - peak.time >= 1.0 - 1e-6 &&
                    picks.none { overlaps(lo, hi, it) }
                ) picks += SourcePick(start, peak, lo, hi)
                return
            }
            // The strongest of the merged picks keeps its place in the ranking.
            val strongest = picks[members.first()].copy(actionStart = action.first, actionEnd = action.second)
            members.descendingSet().forEach { picks.removeAt(it) }
            picks.add(members.first(), strongest)
        }
        evidence.peaks.forEach { consider(it, retry = false) }
        deferred.toList().forEach { consider(it, retry = true) }
        // Two kept actions less than MAX_BRIDGED_GAP_SECONDS apart whose gap an independent hit's context reaches into are joined, so
        // that hit is not cut between them.
        val order = picks.indices.sortedBy { picks[it].actionStart!! }
        for (k in 0 until order.size - 1) {
            val cur = picks[order[k]]
            val next = picks[order[k + 1]]
            val gap = next.actionStart!! - cur.actionEnd!!
            if (gap <= 1e-6 || gap > MAX_BRIDGED_GAP_SECONDS) continue
            val reaches = evidence.peaks.any { p -> p.independent && p.protectedInterval(duration).let { (a, b) -> a < next.actionStart - 1e-6 && b > cur.actionEnd + 1e-6 } }
            if (reaches) picks[order[k]] = cur.copy(actionEnd = next.actionStart)
        }
        return picks
    }
    val count = when {
        duration >= 60.0 -> 3
        duration >= 20.0 -> 2
        else -> 1
    }.coerceAtMost(limit)
    return (0 until count).map { index -> SourcePick(((index + 0.5) * duration / count - sceneLength / 2).coerceIn(0.0, maxStart), null) }
}


// The cut grows (centred on the interval) when the protected action is longer than the requested clip, so the window always holds it.
private fun actionScene(source: TrailerSource, pick: SourcePick, length: Double): TrailerScene {
    val a = pick.actionStart ?: return TrailerScene(source, pick.start, length, actionStart = pick.start, actionEnd = pick.start + length)
    val b = pick.actionEnd ?: (pick.start + length)
    if (b - a > length + 1e-9) return TrailerScene(source, a, b - a, actionStart = a, actionEnd = b)
    // Action span equal to the clip within float noise (b - length a hair above a) must not invert the range; start at a then.
    val start = pick.start.coerceIn(min(a, max(0.0, b - length)), a)
    return TrailerScene(source, start, length, actionStart = a, actionEnd = b)
}

// A source cut into fewer scenes than it has independent actions (e.g. one clip around the first of two putts) must not drop the
// rest: every further independent hit (sharp, classifier-verified, or led by an isolated click) gets its own scene. The picks are
// already separate actions within the per-source limit (sharp hits beyond it); plain loud sounds never qualify, so wind/noise
// cannot flood the montage.
private fun additionalActionScenes(source: TrailerSource, rest: List<SourcePick>, length: Double): List<TrailerScene> =
    rest.filter { it.peak?.independent == true }.map { actionScene(source, it, length) }.sortedBy { it.actionStart ?: it.start }

// Explicit "추천으로 다시 맞추기" for a project whose cuts were stored without verified anchors (e.g. saved before analysis
// was persisted): each source's scenes, in project order, take that source's freshly recommended windows. Order, captions,
// framing and volume stay; a source without verified evidence, or scenes beyond its recommended window count, are untouched.
// Independent verified actions beyond the source's scene count are added as new scenes (chronological, next to that source's
// own scenes; they inherit the neighbouring scene's framing/volume, no caption), so no known action is left out.
internal fun EditorProject.rebuiltFromRecommendation(sources: List<TrailerSource>): EditorProject {
    val byId = sources.associateBy { it.id }
    val usable = HashMap<Int, TrailerSource?>()
    fun usableSource(id: Int) = usable.getOrPut(id) {
        byId[id]?.takeIf { !it.isImage && it.hasValidDuration && it.analysis.highlightSource != ClipHighlightSource.Fallback && it.analysis.waveform.isNotEmpty() }
    }
    // A source with m scenes keeps its m strongest recommended windows (not the m earliest), handed out chronologically in project order.
    val windows = HashMap<Int, List<TrailerScene>>()
    val extraWindows = HashMap<Int, List<TrailerScene>>()
    for ((id, count) in scenes.groupingBy { it.sourceId }.eachCount()) {
        val source = usableSource(id) ?: continue
        val evidence = source.recommendationEvidence()
        val clip = commonClipLength(listOf(evidence))
        val length = min(clip, source.duration)
        val picks = rankedSourcePicks(source, evidence, clip)
        val core = picks.take(count).sortedBy { it.start }.map { actionScene(source, it, length) }
        windows[id] = core
        extraWindows[id] = additionalActionScenes(source, picks.drop(count), length)
    }
    val used = HashMap<Int, Int>()
    var nextKey = this.nextKey
    fun actionOf(scene: TrailerScene) = scene.actionStart ?: scene.start
    val rebuilt = scenes.flatMap { scene ->
        val source = usableSource(scene.sourceId) ?: return@flatMap listOf(scene)
        val index = used.getOrDefault(source.id, 0)
        val core = windows[source.id].orEmpty()
        val rec = core.getOrNull(index) ?: return@flatMap listOf(scene)
        used[source.id] = index + 1
        // The cut grows to hold the whole protected action and is centred on it, so the window always contains the interval.
        fun place(window: TrailerScene, base: EditorScene, length0: Double): EditorScene {
            val actionStart = window.actionStart ?: window.start
            val actionEnd = window.actionEnd ?: (window.start + window.length)
            val span = actionEnd - actionStart
            val length = min(max(length0, max(window.length, span)), source.duration)
            val start = (actionStart - (length - span) / 2).coerceIn(0.0, max(0.0, source.duration - length))
            return base.copy(start = start, length = length, actionStart = actionStart, actionEnd = actionEnd)
        }
        val extras = extraWindows[source.id].orEmpty()
        // Extras sit between the core scenes by source time: before the first, or after the core scene they follow.
        val before = if (index == 0) extras.filter { actionOf(it) < actionOf(rec) } else emptyList()
        val next = core.getOrNull(index + 1)
        val after = extras.filter { actionOf(it) > actionOf(rec) && (next == null || actionOf(it) < actionOf(next)) }
        fun extra(window: TrailerScene) = place(window, scene.copy(key = nextKey++, caption = null), scene.length)
        before.map(::extra) + place(rec, scene, scene.length) + after.map(::extra)
    }
    return copy(scenes = rebuilt, autoPlan = false, nextKey = nextKey)
}

// How much a whole scene matters when an explicit total cannot hold every protected scene: an important photo 2, another photo its
// quality ratio; a video cut holding an action 1 + sharp 1 + verified .5, a cut with evidence but no action .5, a video without
// evidence 1. (Loudness is relative to each source, so it does not rank scenes of different sources.)
private fun sceneImportance(scene: EditorScene, source: TrailerSource?, evidence: (TrailerSource) -> TrailerSourceEvidence): Double {
    if (source == null) return 0.0
    if (source.isImage) return if (source.isImportant) 2.0 else source.effectivePhotoDurationRatio().coerceAtMost(0.95)
    if (!source.hasValidDuration) return 0.0
    val peaks = evidence(source).peaks
    if (peaks.isEmpty()) return 1.0
    val a = scene.actionStart ?: scene.start
    val b = scene.actionEnd ?: (scene.start + scene.length)
    return peaks.filter { it.time >= a - 1e-6 && it.time <= b + 1e-6 }
        .maxOfOrNull { 1.0 + (if (it.sharp) 1.0 else 0.0) + (if (it.verified) 0.5 else 0.0) } ?: 0.5
}

internal class BudgetFit(val project: EditorProject, val total: Double, val included: Int, val excluded: Int)

// Share of an explicit budget left free when whole scenes have to leave, so beat fitting can still move cuts.
internal const val BUDGET_SLACK = 0.1

/**
 * Fits the scene body to an explicit [bodyTarget] (the total minus title cards) that is a hard budget. Scenes reserved by an earlier
 * fit come back first. When the protected minimum lengths do not fit, whole scenes are chosen most important first (each source's
 * strongest scene before any source's extra scenes; earlier first on a tie), each one that still fits whole; the rest are reserved.
 * Kept scenes keep their protected action and share the budget. null when not even one whole scene fits.
 */
internal fun fitEditorToBudget(project: EditorProject, sources: List<TrailerSource>, bodyTarget: Double, capOnly: Boolean = false): BudgetFit? {
    val byId = sources.associateBy { it.id }
    // A reserved scene whose source was removed meanwhile is gone with it.
    val all = project.copy(budgetReserve = project.budgetReserve.filter { it.sourceId in byId }).withBudgetReserveRestored()
    if (all.scenes.isEmpty() || !bodyTarget.isFinite() || bodyTarget <= 0.0) return null
    val minLen = all.scenes.map { resolveSceneDurationBounds(it, byId[it.sourceId]).minLen }
    val keep = BooleanArray(all.scenes.size) { true }
    var minSum = minLen.sum()
    if (minSum > bodyTarget + 1e-6) {
        val evidence = HashMap<Int, TrailerSourceEvidence>()
        val importance = all.scenes.map { scene ->
            sceneImportance(scene, byId[scene.sourceId]) { source -> evidence.getOrPut(source.id) { source.recommendationEvidence() } }
        }
        val representative = all.scenes.indices.groupBy { all.scenes[it].sourceId }.values
            .map { group -> group.maxWith(compareBy<Int> { importance[it] }.thenByDescending { minLen[it] }.thenByDescending { it }) }.toSet()
        // Least wanted first: extra scenes before each source's strongest, then less important, then (equally important) the longer
        // protected minimum, so the most whole shots fit; then later.
        val order = all.scenes.indices.sortedWith(
            compareBy<Int> { it in representative }.thenBy { importance[it] }.thenByDescending { minLen[it] }.thenByDescending { it }
        )
        // When scenes must leave, the kept minimum leaves BUDGET_SLACK of the budget free so cuts can still move onto beats (and get
        // context); a budget that holds a whole scene only without that room uses all of it.
        // Most important first (each source's strongest scene before any extra one; shorter first on equal importance), every whole
        // scene that still fits goes in.
        fun choose(limit: Double) {
            keep.fill(false)
            minSum = 0.0
            for (i in order.asReversed()) if (minSum + minLen[i] <= limit + 1e-6) { keep[i] = true; minSum += minLen[i] }
        }
        choose(bodyTarget * (1 - BUDGET_SLACK))
        if (keep.none { it }) choose(bodyTarget)
        if (keep.none { it }) return null
    }
    val kept = all.scenes.filterIndexed { i, _ -> keep[i] }
    val reserve = all.scenes.filterIndexed { i, _ -> !keep[i] }
    // capOnly (a clip length change under a chosen total) or a chosen number clip: the budget only caps, kept scenes are not stretched.
    val target = if (capOnly || project.clipChoiceExplicit) min(bodyTarget, kept.sumOf { it.length }) else bodyTarget
    val (fitted, total) = redistributeManualTotal(all.copy(scenes = kept), sources, target)
    val order = if (reserve.isEmpty()) emptyList() else all.scenes.map { it.key }
    return BudgetFit(all.copy(scenes = fitted, budgetReserve = reserve, budgetOrder = order), total, kept.size, reserve.size)
}

/**
 * The clip row (−, 1, 2, 추천, 4, 6, +). 추천 (the recommended clip, [recommended]) is protection-aware: each video cut protects its whole
 * detected action again (the source evidence inside the cut) and is never shorter than it. Any other length is the user's own choice:
 * each video cut becomes exactly that long (within its source) around its impact where known, and that window is what it protects
 * from then on (a 1s cut does not claim to hold the whole swing). Photos and scenes without a valid video source are left as they are.
 */
internal fun clipLengthScenes(scenes: List<EditorScene>, sources: List<TrailerSource>, length: Double, recommended: Boolean): List<EditorScene> {
    if (!length.isFinite() || length <= 0.0) return scenes
    val byId = sources.associateBy { it.id }
    val evidence = HashMap<Int, TrailerSourceEvidence>()
    return scenes.map { scene ->
        val source = byId[scene.sourceId]?.takeIf { !it.isImage && it.hasValidDuration } ?: return@map scene
        val duration = source.duration
        val a = (scene.actionStart ?: scene.start).coerceIn(0.0, duration)
        val b = min(scene.actionEnd ?: (scene.start + scene.length), duration).coerceAtLeast(a)
        val peak = evidence.getOrPut(source.id) { source.recommendationEvidence() }.peaks.firstOrNull { it.time >= a - 1e-6 && it.time <= b + 1e-6 }
        if (recommended) {
            val (pa, pb) = peak?.protectedInterval(duration) ?: return@map scene
            return@map scene.copy(actionStart = min(a, pa), actionEnd = max(b, pb))
        }
        val len = min(length, duration)
        val anchor = peak?.onset ?: ((a + b) / 2)
        val wanted = anchor - 0.4 * len
        val start = (if (len <= b - a) wanted.coerceIn(a, max(a, b - len)) else wanted.coerceIn(max(0.0, b - len), max(max(0.0, b - len), a)))
            .coerceIn(0.0, max(0.0, duration - len))
        val shorter = len < b - a - 1e-9
        scene.copy(
            start = start,
            length = len,
            actionStart = if (shorter && scene.actionStart != null) start else scene.actionStart,
            actionEnd = if (shorter && scene.actionEnd != null) start + len else scene.actionEnd,
        )
    }
}

/** What a budget fit traded off, for the status line; null when every scene fits. */
internal fun budgetFitStatus(fit: BudgetFit, cards: Double): String? =
    if (fit.excluded == 0) null
    else "전체 ${trailerTime(fit.total + cards)}에 맞추려고 장면 ${fit.included}개를 넣고 덜 중요한 ${fit.excluded}개는 통째로 뺐어요. 길이를 늘리면 다시 들어갑니다."

/** Refusal when even the shortest whole scene (plus title cards) is longer than the requested total. */
internal fun budgetTooShortMessage(project: EditorProject, sources: List<TrailerSource>, cards: Double): String =
    "가장 짧은 장면도 동작이 잘리지 않으려면 전체 ${trailerTime(budgetBodyBounds(project, sources).first + cards)} 이상이 필요해요. 이전 설정을 그대로 둡니다."

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
                recommendedSourcePicks(source, evidence, clip).map { actionScene(source, it, sceneLength) }
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
    // A draft saved before the photo camera-move floor stored the unfloored automatic total; it is still automatic.
    val legacyTotal = plan.scenes.sumOf { if (it.source.isImage) it.source.unflooredPhotoLength(plan.clipLength) else it.length }
    return plan.takeIf {
        planTotal in 0.0..MAX_TRAILER_TOTAL_SECONDS &&
            (abs(total - planTotal) < AUTOMATIC_TOTAL_TOLERANCE || abs(total - legacyTotal) < AUTOMATIC_TOTAL_TOLERANCE)
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

fun sortTrailerSources(
    sources: List<TrailerSource>,
    mode: String?,
    random: java.util.Random? = null,
): List<TrailerSource> = when (mode) {
    "촬영순", "시간순" -> sources.sortedWith(compareBy<TrailerSource> {
        if (it.captureTimeMillis > 0L) it.captureTimeMillis else it.file.lastModified().takeIf { t -> t > 0L } ?: Long.MAX_VALUE
    }.thenBy { it.id })
    "추가순" -> sources.sortedBy { it.id }
    "가나다순" -> sources.sortedWith(compareBy<TrailerSource, String>(NaturalOrderComparator) { it.name }.thenBy { it.id })
    "랜덤" -> if (random != null) sources.shuffled(random) else sources.shuffled()
    else -> sources
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
    // Manual caption burned in after framing/photo motion, fixed to the output frame; null = none.
    val caption: SceneCaption? = null,
    // Source-time interval of the detected action this scene must keep (null = nothing to protect).
    val actionStart: Double? = null,
    val actionEnd: Double? = null,
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
    val orderMode: String? = "촬영순",
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
    val cardDuration: Double get() = (if (editor?.introCard?.enabled == true) editor.introCard.clampedDuration() else 0.0) +
        (if (editor?.outroCard?.enabled == true) editor.outroCard.clampedDuration() else 0.0)
    val totalDuration: Double get() = cardDuration + scenes.sumOf { it.length }

    /** The recommended/automatic plan for the current parameters (what [editor] mirrors while autoPlan is true). */
    fun autoPlanScenes(): List<TrailerScene> = if (isMusicOverlayMode) {
        musicOverlayScenes(sources, 1.0, plannedMusic, musicPolicy, musicFitCount, reservedDuration = cardDuration)
    } else {
        trailerScenes(sources, total, clipLength, photoClipLength, plannedMusic, musicPolicy, musicFitCount, reservedDuration = cardDuration)
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
    reservedDuration: Double = 0.0,
): List<TrailerScene> {
    if (sources.isEmpty() || !length.isFinite() || length <= 0 || !photoLength.isFinite() || photoLength <= 0 || !total.isFinite()) return emptyList()

    val fitsMusicLength = musicTrack != null && musicPolicy == TrailerMusicPolicy.FIT_MUSIC_LENGTH && musicTrack.durationSeconds > 0.0
    // Unchanged recommended total + common clip: use the recommendation's own distinct cuts, no repeats.
    if (!fitsMusicLength) automaticTrailerPlan(sources, total, length)?.let { return it.scenes }

    val safeReserved = if (reservedDuration.isFinite()) reservedDuration.coerceAtLeast(0.0) else 0.0
    val targetTotal = if (fitsMusicLength && musicTrack != null) {
        max(0.1, musicTrack.durationSeconds * musicFitCount.coerceAtLeast(1) - safeReserved)
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
        return fitScenesToTargetDuration(rawScenes, targetTotal)
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
    reservedDuration: Double = 0.0,
): List<TrailerScene> {
    if (sources.isEmpty()) return emptyList()
    // Photos always get a fixed 1s Ken Burns scene here regardless of the caller's photoLength;
    // only videos' own full duration and the music-fit policy affect this mode's total length.
    val baseScenes = sources.map { source ->
        if (source.isImage) {
            val photoLen = if (source.isImportant) 2.0 else 1.0
            TrailerScene(source, 0.0, photoLen)
        } else {
            TrailerScene(source, 0.0, max(0.5, source.duration))
        }
    }
    if (musicTrack != null && musicPolicy == TrailerMusicPolicy.FIT_MUSIC_LENGTH && musicTrack.durationSeconds > 0.0) {
        val safeReserved = if (reservedDuration.isFinite()) reservedDuration.coerceAtLeast(0.0) else 0.0
        val targetTotal = max(0.1, musicTrack.durationSeconds * musicFitCount.coerceAtLeast(1) - safeReserved)
        return fitScenesToTargetDuration(baseScenes, targetTotal)
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

data class BeatSyncResult(
    val adjustedCount: Int,
    val averageShift: Double,
    val unalignedCount: Int,
    // 음악 길이 맞춤에서 비트 조절 뒤에도 흡수하지 못한 길이(초). 0이면 음악 길이에 정확히 맞는다.
    val fitResidual: Double = 0.0,
)

/**
 * 음악 비트 온셋에 맞춰 장면 길이(전환 시점)를 조절한다. 제한은 resolveSceneDurationBounds가 주는 실제 원본 범위
 * (영상은 시작 위치부터 남은 원본 길이, 사진은 사진 최소/최대 길이)뿐이며, 원래 컷 시각에 가장 가까운 비트를 우선하되
 * 무음 구간처럼 가까운 비트가 없으면 범위 안에서 더 먼 비트를 쓴다. 다음 장면이 어떤 비트에도 닿지 못하게 되는 후보는 피한다.
 * 영상은 시작 위치를 그대로 두고, 자막, 프레이밍, 순서는 보존된다.
 * 음악 길이 맞춤이면 남은 장면들의 최소/최대 길이 합으로 끝점을 미리 제한해 마지막 장면이 남은 시간을 받아 음악 끝에 정확히 닿게 하며,
 * 이미 맞춘 중간 컷은 다시 옮기지 않는다. 범위상 불가능한 장면은 그대로 두고 unalignedCount/fitResidual로 알린다.
 */
fun syncPhotoScenesToBeatOnsets(
    scenes: List<EditorScene>,
    sources: List<TrailerSource>,
    beatOnsets: List<Double>,
    musicDuration: Double,
    introOffset: Double = 0.0,
    musicPolicy: TrailerMusicPolicy = TrailerMusicPolicy.LOOP_MUSIC,
    maxBudget: Double? = null,
): Pair<List<EditorScene>, BeatSyncResult> {
    if (scenes.isEmpty() || beatOnsets.isEmpty() || !musicDuration.isFinite() || musicDuration <= 0.0) {
        return scenes to BeatSyncResult(0, 0.0, 0)
    }
    val single = musicPolicy == TrailerMusicPolicy.SINGLE
    // 음악 반복과 음악 길이 맞춤은 같은 반복 타임라인을 쓴다. 반복에서는 길이 끝 비트가 다음 반복 첫 비트와 겹치므로 뺀다.
    val beats = beatOnsets.filter { it.isFinite() && it >= 0.0 && (if (single) it <= musicDuration else it < musicDuration) }.distinct().sorted()
    if (beats.isEmpty()) return scenes to BeatSyncResult(0, 0.0, 0)

    val eps = 1e-6
    val byId = sources.associateBy { it.id }
    val n = scenes.size
    val bounds = scenes.map { resolveSceneDurationBounds(it, byId[it.sourceId]) }
    val minLen = DoubleArray(n) { bounds[it].minLen }
    val maxLen = DoubleArray(n) { bounds[it].maxLen }
    val origLen = DoubleArray(n) { bounds[it].currentLen }
    // 보호된 영상이 줄지 못하면 남는 박자 흡수를 사진이 떠맡아 0.2초 깜빡임이나 7초 정지가 생긴다. 사진은 원래 길이의 절반~2배 안에서만 움직인다.
    // 이 범위로 음악 길이를 못 채우게 되면(예산 불가능) 원래의 넓은 범위로 되돌린다.
    run {
        val narrowMin = minLen.copyOf()
        val narrowMax = maxLen.copyOf()
        for (i in 0 until n) {
            val src = byId[scenes[i].sourceId]
            if (src == null || !src.isImage || bounds[i].isFixed) continue
            narrowMin[i] = max(minLen[i], min(maxLen[i], origLen[i] * 0.5))
            narrowMax[i] = min(maxLen[i], max(narrowMin[i], origLen[i] * 2.0))
        }
        val begin = max(0.0, introOffset)
        val feasible = maxBudget == null ||
            (begin + narrowMin.sum() <= maxBudget + eps && begin + narrowMax.sum() >= maxBudget - eps)
        if (feasible) { narrowMin.copyInto(minLen); narrowMax.copyInto(maxLen) }
    }
    // i번째 이후 장면들이 낼 수 있는 최소/최대 길이 합. softMin은 "여유 있게" 남겨 둘 시간(원래 길이의 절반 이상)이다.
    val tailMin = DoubleArray(n + 1)
    val tailMax = DoubleArray(n + 1)
    val tailSoft = DoubleArray(n + 1)
    for (i in n - 1 downTo 0) {
        tailMin[i] = tailMin[i + 1] + minLen[i]
        tailMax[i] = tailMax[i + 1] + maxLen[i]
        tailSoft[i] = tailSoft[i + 1] + if (bounds[i].isFixed) minLen[i] else min(maxLen[i], max(minLen[i], origLen[i] * 0.5))
    }
    val start = max(0.0, introOffset)
    // 예산(음악 길이 맞춤의 음악 끝, 그 밖에는 지금 총길이)이 길이 범위상 가능할 때만 끝을 거기에 맞춘다. 불가능하면 비트만 맞추고 차이는 fitResidual로 알린다.
    val budget = maxBudget?.takeIf {
        it.isFinite() && start + tailMin[0] <= it + eps && start + tailMax[0] >= it - eps
    }
    val lastAdjustable = bounds.indexOfLast { !it.isFixed }

    fun beatsIn(lo: Double, hi: Double): List<Double> {
        if (hi < lo) return emptyList()
        if (single) return beats.filter { it >= lo && it <= hi }
        val out = mutableListOf<Double>()
        for (loop in floor(max(0.0, lo) / musicDuration).toLong()..floor(hi / musicDuration).toLong()) {
            val base = loop * musicDuration
            for (b in beats) if (base + b >= lo && base + b <= hi) out.add(base + b)
        }
        return out
    }

    fun firstBeatFrom(x: Double): Double? {
        if (single) return beats.firstOrNull { it >= x }
        val base = floor(max(0.0, x) / musicDuration) * musicDuration
        return beats.firstOrNull { base + it >= x }?.let { base + it } ?: (base + musicDuration + beats.first())
    }

    // 시작 s에서 i번째 장면의 끝점이 가질 수 있는 실제 범위(원본 길이 범위 + 남은 장면이 음악 끝을 채울 수 있는 범위)
    fun endWindow(i: Int, s: Double): Pair<Double, Double> {
        var lo = s + minLen[i]
        var hi = s + maxLen[i]
        if (budget != null) {
            lo = max(lo, budget - tailMax[i + 1])
            hi = min(hi, budget - tailMin[i + 1])
        } else if (maxBudget != null) {
            hi = min(hi, maxBudget)
        }
        return lo to hi
    }

    // i번째 장면이 e에서 끝날 때 다음 조절 대상 장면이 범위 안에서 닿을 비트가 하나라도 있는지(음악 끝을 채우는 마지막 장면은 예외)
    fun nextCanReachBeat(i: Int, e: Double): Boolean {
        var s = e
        var j = i + 1
        while (j < n && bounds[j].isFixed) { s += minLen[j]; j++ }
        if (j >= n || (budget != null && j == lastAdjustable)) return true
        val (lo, hi) = endWindow(j, s)
        val first = firstBeatFrom(lo - eps) ?: return false
        return first <= hi + eps
    }

    val resultScenes = scenes.toMutableList()
    var cursor = start
    var origCursor = start
    var adjustedCount = 0
    var unalignedCount = 0
    var totalShift = 0.0

    for (i in 0 until n) {
        val scene = resultScenes[i]
        val origEnd = origCursor + origLen[i]
        origCursor = origEnd
        if (bounds[i].isFixed) {
            cursor += minLen[i]
            continue
        }

        val (lo, hi) = endWindow(i, cursor)
        val currentEnd = cursor + origLen[i]
        var pick: Double? = null
        val end: Double
        if (lo > hi + eps) {
            unalignedCount++
            end = currentEnd
        } else if (budget != null && i == lastAdjustable) {
            // 마지막 장면은 비트가 아니라 음악 끝에 맞추는 장면: 남은 시간을 그대로 받는다.
            end = currentEnd.coerceIn(lo, max(lo, hi))
        } else {
            // 원래 컷 시각에 가까운 비트부터. 남은 장면이 너무 짧아지지 않고(소프트) 다음 장면이 닿을 수 있는 후보를 먼저 쓴다.
            val ordered = beatsIn(lo - eps, hi + eps).sortedBy { abs(it - origEnd) }
            pick = ordered.firstOrNull { (budget == null || it <= budget - tailSoft[i + 1] + eps) && nextCanReachBeat(i, it) }
                ?: ordered.firstOrNull { nextCanReachBeat(i, it) }
                ?: ordered.firstOrNull()
            if (pick == null) unalignedCount++
            end = pick ?: currentEnd.coerceIn(lo, max(lo, hi))
        }

        var newLen = (end - cursor).coerceIn(minLen[i], maxLen[i])
        // 이미 비트에서 20ms 이내이고 예산 범위 안이면 길이를 건드리지 않는다.
        if (pick != null && abs(newLen - origLen[i]) <= 0.02 && currentEnd >= lo - eps && currentEnd <= hi + eps) newLen = origLen[i]
        if (abs(newLen - scene.length) > 1e-9) {
            val src = byId[scene.sourceId]
            val keptStart = if (src != null && !src.isImage) scene.startKeepingAction(scene.start, newLen, src.duration) else scene.start
            resultScenes[i] = scene.copy(start = keptStart, length = newLen)
        }
        if (pick != null && abs(newLen - origLen[i]) > 0.02) {
            adjustedCount++
            totalShift += abs(newLen - origLen[i])
        }
        cursor += newLen
    }

    val fitResidual = if (maxBudget != null) maxBudget - cursor else 0.0
    val avgShift = if (adjustedCount > 0) totalShift / adjustedCount.toDouble() else 0.0
    return resultScenes to BeatSyncResult(adjustedCount, avgShift, unalignedCount, if (abs(fitResidual) <= 0.01) 0.0 else fitResidual)
}

internal class BeatFitResult(val scenes: List<EditorScene>, val sync: BeatSyncResult, val repeatCount: Int)

/**
 * 비트 맞춤. 음악 반복 횟수는 사용자가 고른 그대로이며(자동으로 늘리지 않는다) 예산을 넘기지 않는다: 음악 길이 맞춤은 음악 끝,
 * 다른 정책은 지금 총길이가 끝이다. 닿지 못한 컷은 unalignedCount로 알린다.
 */
internal fun syncToBeatsWithMinimumRepeats(
    project: EditorProject,
    sources: List<TrailerSource>,
    beats: List<Double>,
    musicDuration: Double,
    musicPolicy: TrailerMusicPolicy,
    fitCount: Int,
): BeatFitResult {
    val introOffset = if (project.introCard.enabled) project.introCard.clampedDuration() else 0.0
    val outroDuration = if (project.outroCard.enabled) project.outroCard.clampedDuration() else 0.0
    val fit = musicPolicy == TrailerMusicPolicy.FIT_MUSIC_LENGTH && musicDuration.isFinite() && musicDuration > 0.0
    val maxBudget = if (fit) musicDuration * fitCount - outroDuration else introOffset + project.scenes.sumOf { it.length }
    val (synced, sync) = syncPhotoScenesToBeatOnsets(project.scenes, sources, beats, musicDuration, introOffset, musicPolicy, maxBudget)
    return BeatFitResult(synced, sync, fitCount)
}

/** Regenerates the scenes from the recommended plan; only valid while the project is still automatic. */
internal fun TrailerSession.withRegeneratedPlan(): TrailerSession = regeneratedPlanOrNull() ?: this

/** [withRegeneratedPlan], or null when the explicit total/music length cannot hold even one whole scene (nothing changes then). */
internal fun TrailerSession.regeneratedPlanOrNull(): TrailerSession? {
    val project = editor ?: return this
    val music = plannedMusic
    if (!isMusicOverlayMode && musicPolicy == TrailerMusicPolicy.FIT_MUSIC_LENGTH && music != null &&
        music.durationSeconds.isFinite() && music.durationSeconds > 0.0
    ) {
        // 음악 예산으로 장면을 새로 고르면 뒤쪽(마지막 샷)이 잘린다. 앵커가 있는 전체 추천을 먼저 옮기고,
        // 보호 구간을 지키며 고른 반복 횟수의 길이에 맞춘다(안 들어가는 덜 중요한 장면은 통째로 뺀다).
        val full = recommendTrailerPlan(sources, explicitClip = clipLength)?.scenes
        if (!full.isNullOrEmpty()) {
            val plan = EditorProject.fromPlan(
                full,
                autoPlan = true,
                musicVolume = project.musicVolume,
                firstKey = project.nextKey,
                introCard = project.introCard,
                outroCard = project.outroCard,
            )
            val seeded = copy(editor = plan.copy(
                scenes = clipLengthScenes(plan.scenes, sources, clipLength, !project.clipChoiceExplicit),
                clipChoiceExplicit = project.clipChoiceExplicit,
            ))
            return seeded.withBudgetMusicFit()
        }
    }
    if (!isMusicOverlayMode) {
        // 정해 둔 길이로 장면을 새로 고르면 그 길이에 안 들어가는 샷이 조용히 빠진다. 전체 추천(모든 동작)에서 시작해 요청 길이에
        // 맞추되 보호된 동작은 자르지 않고, 다 안 들어가면 덜 중요한 장면을 통째로 빼고 그렇게 알린다.
        val full = recommendTrailerPlan(sources, explicitClip = clipLength)?.scenes
        if (!full.isNullOrEmpty()) {
            val plan = EditorProject.fromPlan(
                full,
                autoPlan = true,
                musicVolume = project.musicVolume,
                firstKey = project.nextKey,
                introCard = project.introCard,
                outroCard = project.outroCard,
            )
            val seeded = plan.copy(
                scenes = clipLengthScenes(plan.scenes, sources, clipLength, !project.clipChoiceExplicit),
                budgetTarget = project.budgetTarget,
                clipChoiceExplicit = project.clipChoiceExplicit,
            )
            // Only a whole length the user chose (cards included) is a budget, kept through every regeneration (order, photos, cards,
            // added sources). Without one the recommendation keeps every shot (an older automatic total is not a budget).
            val requested = project.budgetTarget?.let { it - cardDuration }
                ?: return copy(total = seeded.scenes.sumOf { it.length }, editor = seeded)
            val fit = fitEditorToBudget(seeded, sources, requested) ?: return null
            return copy(
                total = fit.total,
                editor = fit.project,
                status = budgetFitStatus(fit, cardDuration) ?: status,
            )
        }
    }
    val genScenes = autoPlanScenes()
    val actualSum = genScenes.sumOf { it.length }
    return copy(
        total = if (actualSum > 0.0) actualSum else total,
        editor = EditorProject.fromPlan(
            genScenes,
            autoPlan = true,
            musicVolume = project.musicVolume,
            firstKey = project.nextKey,
            introCard = project.introCard,
            outroCard = project.outroCard,
        ),
    )
}

/**
 * 직접 편집한 프로젝트에서 음악 길이 맞춤: 컷·순서·자막은 그대로 두고 장면 길이만 (음악 길이 × 반복 − 시작/마지막 카드)에
 * 맞춰 다시 나눈다. 장면 길이 한계로 못 맞추면 가능한 만큼만 바뀌고, 내보낼 때 음악이 영상 끝까지 이어진다.
 */
internal fun TrailerSession.withManualMusicFit(): TrailerSession {
    val project = editor ?: return this
    if (project.autoPlan) return this
    return withBudgetMusicFit() ?: copy(status = budgetTooShortMessage(project, sources, cardDuration))
}

/**
 * 자동/수동 공통 음악 길이 맞춤: 고른 반복 횟수 그대로(자동으로 늘리지 않는다) 음악 길이 × 횟수(카드 포함)가 예산이다.
 * 보호된 동작은 자르지 않고, 다 안 들어가면 덜 중요한 장면을 통째로 뺀다. 숫자로 고른 클립이면 음악 길이는 상한일 뿐 컷을 다시 늘리지
 * 않아 영상이 음악보다 짧을 수 있다(화면이 실제 길이를 보여 준다). 한 장면도 안 들어가면 null.
 */
internal fun TrailerSession.withBudgetMusicFit(): TrailerSession? {
    val project = editor ?: return this
    val music = plannedMusic ?: return this
    val musicLength = music.durationSeconds
    if (musicPolicy != TrailerMusicPolicy.FIT_MUSIC_LENGTH || !musicLength.isFinite() || musicLength <= 0.0 || project.scenes.isEmpty()) return this
    val cards = cardDuration.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
    val count = musicFitCount.coerceIn(1, 600)
    val fit = fitEditorToBudget(project, sources, (musicLength * count - cards).coerceAtLeast(0.1)) ?: return null
    return copy(
        total = fit.total,
        musicFitCount = count,
        editor = fit.project,
        status = budgetFitStatus(fit, cards) ?: status,
    )
}

/** A chosen whole length (not the music length fit) refits after title cards or clip lengths changed; [capOnly] for clip changes. */
internal fun TrailerSession.withBudgetTargetRefit(capOnly: Boolean = false): TrailerSession {
    val project = editor ?: return this
    val target = project.budgetTarget ?: return this
    if (musicPolicy == TrailerMusicPolicy.FIT_MUSIC_LENGTH && plannedMusic != null) return this
    val fit = fitEditorToBudget(project, sources, target - cardDuration, capOnly) ?: return copy(status = budgetTooShortMessage(project, sources, cardDuration))
    return copy(total = fit.total, editor = fit.project.copy(autoPlan = project.autoPlan), status = budgetFitStatus(fit, cardDuration) ?: status)
}

// editorSetMusic의 상태 변환(음악·정책·반복 횟수 선택 → 장면 재계획/길이 맞춤). 화면과 계측 테스트가 같은 모델을 쓴다.
internal fun TrailerSession.withMusicSelection(track: TrailerMusicTrack?, policy: TrailerMusicPolicy, count: Int): TrailerSession {
    var next = copy(
        musicTrack = track,
        unavailableMusic = null,
        musicExportPrompt = false,
        musicPolicy = policy,
        musicFitCount = count,
    )
    if (track != null && policy == TrailerMusicPolicy.FIT_MUSIC_LENGTH && track.durationSeconds > 0.0) {
        // The music length × count is the budget now; a whole length chosen earlier no longer applies.
        next = next.copy(total = (track.durationSeconds * count - cardDuration).coerceAtLeast(0.1), editor = editor?.copy(budgetTarget = null))
    }
    // 고른 반복 횟수의 길이에 한 장면도 통째로 안 들어가면 이전 상태 그대로(호출자가 최소 길이를 알린다).
    return if (editor?.autoPlan == true) next.regeneratedPlanOrNull() ?: this else next.withBudgetMusicFit() ?: this
}

