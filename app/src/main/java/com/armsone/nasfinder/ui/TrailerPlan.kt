package com.armsone.nasfinder.ui

import android.graphics.Bitmap
import java.io.File
import kotlin.math.*

data class TrailerSource(
    val id: Int, val name: String, val file: File, val duration: Double,
    val width: Int, val height: Int, val analysis: AudioAnalysisResult, val thumbnail: Bitmap?,
) {
    val recommendedLength: Double get() {
        val wave = analysis.waveform
        val baseline = wave.average().takeIf { it.isFinite() } ?: 0.0
        val lengths = analysis.peakTimesSeconds.map { peak ->
            if (wave.isEmpty()) min(4.0, duration) else {
                val index = (peak / duration * wave.size).toInt().coerceIn(wave.indices)
                val threshold = baseline + max(0.0, wave[index] - baseline) * .4
                var left = index; var right = index
                while (left > 0 && wave[left - 1] > threshold && index - left < 12) left--
                while (right + 1 < wave.size && wave[right + 1] > threshold && right - index < 12) right++
                min(duration, max(2.0, min(10.0, (right - left + 1) * duration / wave.size + 1)))
            }
        }.sorted()
        return max(min(1.0, duration), min(duration, floor((lengths.getOrNull(lengths.size / 2) ?: 4.0) + .5)))
    }
    val recommendedTotal: Double get() {
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

data class TrailerSession(
    val directory: File, val sources: List<TrailerSource> = emptyList(),
    val total: Double = 0.0, val clipLength: Double = 4.0, val format: TrailerFormat = TrailerFormat.WIDE,
    val output: File? = null, val busy: Boolean = true, val status: String = "영상을 준비하는 중…",
    val progress: Float? = null, val error: String? = null, val saved: Boolean = false,
) {
    val originalTotal get() = sources.sumOf { it.duration }
    val scenes get() = trailerScenes(sources, total, clipLength)
}

// Ported from VideoTrailerBatchPlan: distinct regions, equal lengths and proportional allocation.
internal fun trailerScenes(sources: List<TrailerSource>, total: Double, length: Double): List<TrailerScene> {
    if (sources.isEmpty() || !length.isFinite() || length <= 0 || !total.isFinite()) return emptyList()
    val capacity = sources.map { floor(it.duration / length + .000001).toInt().coerceAtLeast(0) }
    val requested = min(capacity.sum(), max(1, floor(total / length + .5).toInt()))
    val counts = IntArray(sources.size)
    var remaining = requested
    sources.indices.filter { capacity[it] > 0 }.forEach { if (remaining > 0) { counts[it]++; remaining-- } }
    while (remaining > 0) {
        val index = sources.indices.filter { counts[it] < capacity[it] }.maxByOrNull { sources[it].duration / (counts[it] + 1) } ?: break
        counts[index]++; remaining--
    }
    return sources.flatMapIndexed { index, source ->
        val count = counts[index]
        (0 until count).map { scene ->
            val lower = scene * source.duration / count
            val upper = min(source.duration, (scene + 1) * source.duration / count)
            val peaks = source.analysis.peakTimesSeconds.filter { it >= lower && it < upper }
            val wavePeak = source.analysis.waveform.mapIndexedNotNull { bucket, score ->
                val time = (bucket + .5) / source.analysis.waveform.size * source.duration
                if (time >= lower && time < upper) time to score else null
            }.maxByOrNull { it.second }?.first
            val peak = if (source.analysis.highlightSource == ClipHighlightSource.Fallback) (lower + upper) / 2 else peaks.firstOrNull() ?: wavePeak ?: (lower + upper) / 2
            TrailerScene(source, max(lower, min(upper - length, peak - length / 2)), length)
        }
    }
}
internal fun trailerTime(seconds: Double): String {
    val n = seconds.roundToInt().coerceAtLeast(0)
    return if (n >= 3600) "%d:%02d:%02d".format(n / 3600, n / 60 % 60, n % 60) else "%d:%02d".format(n / 60, n % 60)
}
internal fun trailerLengthTitle(seconds: Double): String {
    val n = seconds.roundToInt().coerceAtLeast(0)
    return listOfNotNull(if (n >= 3600) "${n / 3600}시간" else null, if (n / 60 % 60 > 0) "${n / 60 % 60}분" else null, if (n % 60 > 0 || n == 0) "${n % 60}초" else null).joinToString(" ")
}

internal val InboxDisplayItem.isTrailerVideo: Boolean get() = com.armsone.nasfinder.model.RemoteFileItem(id.toString(), originalFilename, id.toString(), false, mimeType = mimeType).isVideo
