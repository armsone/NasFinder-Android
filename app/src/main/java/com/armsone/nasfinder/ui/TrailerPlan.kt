package com.armsone.nasfinder.ui

import android.graphics.Bitmap
import java.io.File
import kotlin.math.*

// Default .55 is the neutral HanYang quality/similarity ratio used until analyzeTrailerPhotos()
// (TrailerPhotoAnalysis.kt) fills in a real per-photo measurement; kept stable across preview
// recomposition, export and saved-draft re-open because it lives on this immutable source.
const val DEFAULT_PHOTO_DURATION_RATIO: Double = .55
const val MIN_PHOTO_DURATION_RATIO: Double = .05
internal const val MIN_PHOTO_SCENE_DURATION: Double = 1.0 / 30.0

data class TrailerSource(
    val id: Int, val name: String, val file: File, val duration: Double,
    val width: Int, val height: Int, val analysis: AudioAnalysisResult, val thumbnail: Bitmap?,
    val isImage: Boolean = false,
    val captureTimeMillis: Long = 0L,
    // HanYang quality/similarity ratio of the configured video clip length this photo's scene
    // should occupy: .35..75 ordinary (best photo highest, similar duplicates lower), down to
    // MIN_PHOTO_DURATION_RATIO for extreme near-duplicates. Set by analyzeTrailerPhotos().
    val photoDurationRatio: Double = DEFAULT_PHOTO_DURATION_RATIO,
) {
    // Actual scene duration for this photo given the configured video-clip ("base clip") length.
    fun weightedPhotoLength(clipLength: Double): Double =
        max(MIN_PHOTO_SCENE_DURATION, clipLength * photoDurationRatio)
    val recommendedLength: Double get() {
        if (isImage) return 4.0
        val wave = analysis.waveform
        val baseline = wave.average().takeIf { it.isFinite() } ?: 0.0
        val lengths = analysis.peakTimesSeconds.map { peak ->
            if (wave.isEmpty()) min(4.0, duration) else {
                val index = (peak / duration * wave.size).toInt().coerceIn(wave.indices)
                val threshold = baseline + max(0.0, wave[index] - baseline) * .4
                var left = index; var right = index
                while (left > 0 && wave[left - 1] > threshold && index - left < 12) left--
                while (right + 1 < wave.size && wave[right + 1] > threshold && right - index < 12) right++
                min(duration, max(1.0, min(10.0, (right - left + 1) * duration / wave.size + 1)))
            }
        }.sorted()
        return max(min(1.0, duration), min(duration, floor((lengths.getOrNull(lengths.size / 2) ?: 4.0) + .5)))
    }
    val recommendedTotal: Double get() {
        if (isImage) return recommendedLength
        val wave = analysis.waveform
        val baseline = wave.average().takeIf { it.isFinite() } ?: 0.0
        val strong = analysis.peakTimesSeconds.count { peak ->
            wave.isNotEmpty() && wave[(peak / duration * wave.size).toInt().coerceIn(wave.indices)] >= max(baseline, (wave.maxOrNull() ?: 0.0) * .55)
        }
        return min(max(1, (duration / recommendedLength).toInt()), max(1, min(8, strong))) * recommendedLength
    }
}

data class TrailerScene(val source: TrailerSource, val start: Double, val length: Double)
enum class TrailerFormat(val title: String, val width: Int, val height: Int) {
    SQUARE("1:1", 1080, 1080), PORTRAIT("3:4", 1080, 1440), LANDSCAPE("4:3", 1440, 1080),
    STORY("9:16", 1080, 1920), WIDE("16:9", 1920, 1080);
    companion object {
        fun closest(width: Int, height: Int) = entries.minBy { abs(it.width.toDouble() / it.height - width.toDouble() / height.coerceAtLeast(1)) }
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
    val format: TrailerFormat = TrailerFormat.WIDE,
    val output: File? = null, val busy: Boolean = true, val status: String = "영상을 준비하는 중…",
    val progress: Float? = null, val error: String? = null, val saved: Boolean = false,
    val isMusicOverlayMode: Boolean = false,
    val musicTrack: TrailerMusicTrack? = null,
    val musicPolicy: TrailerMusicPolicy = TrailerMusicPolicy.LOOP_MUSIC,
    val musicFitCount: Int = 1,
    val autoDucking: Boolean = true,
    val awaitingInitialSettings: Boolean = false,
    // True only once every source is staged and analysed and the first plan exists.
    val prepared: Boolean = false,
    val draftId: String? = null,
) {
    val hasVideos: Boolean get() = sources.any { !it.isImage }
    // Music-overlay mode keeps every photo at a fixed 1s Ken Burns scene; trailer mode now weighs
    // each photo's own HanYang quality/similarity ratio against the configured video clip length
    // instead of forcing a uniform value, so this is only an estimate used for legacy callers.
    val effectivePhotoClipLength: Double get() = if (isMusicOverlayMode) 1.0 else photoClipLength
    val originalTotal get() = sources.sumOf {
        if (!it.isImage) it.duration else if (isMusicOverlayMode) 1.0 else it.weightedPhotoLength(clipLength)
    }
    val scenes get() = if (isMusicOverlayMode) {
        musicOverlayScenes(sources, 1.0, musicTrack, musicPolicy, musicFitCount)
    } else {
        trailerScenes(sources, total, clipLength, photoClipLength, musicTrack, musicPolicy, musicFitCount)
    }
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

    val targetTotal = if (musicTrack != null && musicPolicy == TrailerMusicPolicy.FIT_MUSIC_LENGTH && musicTrack.durationSeconds > 0.0) {
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
            result.add(TrailerScene(next.source, next.start, remaining))
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

internal val InboxDisplayItem.isTrailerVideo: Boolean get() = isTrailerMedia
internal val InboxDisplayItem.isTrailerMedia: Boolean get() {
    val item = com.armsone.nasfinder.model.RemoteFileItem(id.toString(), originalFilename, id.toString(), false, mimeType = mimeType)
    return item.isVideo || item.isImage
}
