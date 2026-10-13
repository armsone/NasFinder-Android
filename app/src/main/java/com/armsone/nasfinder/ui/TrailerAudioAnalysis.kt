package com.armsone.nasfinder.ui

// Shared HanClip audio and visual-motion analysis, included for an independent build.

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.CancellationException
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class AudioAnalysisResult(
    val waveform: List<Double>,
    val peakTimeSeconds: Double,
    val peakTimesSeconds: List<Double> = listOf(peakTimeSeconds),
    val audioAvailability: ClipAudioAvailability = ClipAudioAvailability.Present,
    val highlightSource: ClipHighlightSource = ClipHighlightSource.Audio
)

private data class AudioImpactMetrics(
    val rms: Double,
    val peak: Double,
    val crossingRate: Double
) {
    val impactScore: Double
        get() {
            val highFrequencyWeight = min(1.0, crossingRate * 10.0)
            return min(1.0, rms * 0.55 + peak * 0.45 + highFrequencyWeight * rms * 0.35)
        }
}

private data class AudioImpactFrame(
    val timeSeconds: Double,
    val metrics: AudioImpactMetrics
)

internal object AudioAnalysisService {
    suspend fun analyze(
        context: Context,
        uri: Uri,
        sourceDurationSeconds: Double,
        bucketCount: Int = 192
    ): AudioAnalysisResult = runInterruptible(Dispatchers.Default) {
        val safeBucketCount = bucketCount.coerceAtLeast(1)
        runCatching {
            analyzePcm(
                context = context,
                uri = uri,
                sourceDurationSeconds = sourceDurationSeconds,
                bucketCount = safeBucketCount
            )
        }.getOrElse {
            if (it is CancellationException) throw it
            fallbackResult(sourceDurationSeconds, safeBucketCount)
        }
    }

    private fun analyzePcm(
        context: Context,
        uri: Uri,
        sourceDurationSeconds: Double,
        bucketCount: Int
    ): AudioAnalysisResult {
        val extractor = MediaExtractor()
        try {
            return analyzePcmWithExtractor(context, uri, sourceDurationSeconds, bucketCount, extractor)
        } finally { extractor.release() }
    }

    private fun analyzePcmWithExtractor(
        context: Context, uri: Uri, sourceDurationSeconds: Double, bucketCount: Int,
        extractor: MediaExtractor
    ): AudioAnalysisResult {
        extractor.setDataSource(context, uri, null)
        val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index)
                .getString(MediaFormat.KEY_MIME)
                ?.startsWith("audio/") == true
        } ?: run {
            return analyzeVisualMotionOrFallback(
                context = context,
                uri = uri,
                durationSeconds = sourceDurationSeconds,
                bucketCount = bucketCount,
                audioAvailability = ClipAudioAvailability.NoTrack
            )
        }

        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: run {
            return fallbackResult(sourceDurationSeconds, bucketCount)
        }
        val durationSeconds = if (format.containsKey(MediaFormat.KEY_DURATION)) {
            max(0.1, format.getLong(MediaFormat.KEY_DURATION) / 1_000_000.0)
        } else {
            max(0.1, sourceDurationSeconds)
        }
        val decoder = MediaCodec.createDecoderByType(mime)
        val info = MediaCodec.BufferInfo()
        val energy = DoubleArray(bucketCount)
        val peaks = DoubleArray(bucketCount)
        val crossings = IntArray(bucketCount)
        val sampleTotals = IntArray(bucketCount)
        val counts = IntArray(bucketCount)

        try {
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            decoder.configure(format, null, null, 0)
            decoder.start()

            var inputDone = false
            var outputDone = false
            var outputPcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            while (!outputDone) {
                if (Thread.currentThread().isInterrupted) throw CancellationException()
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)
                        val sampleSize = if (inputBuffer != null) {
                            inputBuffer.clear()
                            extractor.readSampleData(inputBuffer, 0)
                        } else {
                            -1
                        }

                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                sampleSize,
                                max(0L, extractor.sampleTime),
                                0
                            )
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = decoder.outputFormat
                        outputPcmEncoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }
                    }
                    else -> if (outputIndex >= 0) {
                        val buffer = decoder.getOutputBuffer(outputIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val pcmBuffer = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
                            val metrics = if (outputPcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                metricsFloatPcm(pcmBuffer)
                            } else {
                                metrics16BitPcm(pcmBuffer)
                            }
                            val timeSeconds = info.presentationTimeUs / 1_000_000.0
                            val bucket = ((timeSeconds / durationSeconds) * bucketCount)
                                .toInt()
                                .coerceIn(0, bucketCount - 1)
                            energy[bucket] += metrics.rms
                            peaks[bucket] = max(peaks[bucket], metrics.peak)
                            crossings[bucket] += metrics.crossings
                            sampleTotals[bucket] += metrics.sampleCount
                            counts[bucket] += 1
                        }
                        outputDone = outputDone ||
                            (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        } finally {
            runCatching { decoder.stop() }
            decoder.release()
        }

        val metrics = List(bucketCount) { index ->
            AudioImpactMetrics(
                rms = if (counts[index] > 0) energy[index] / counts[index] else 0.0,
                peak = peaks[index],
                crossingRate = if (sampleTotals[index] > 0) {
                    crossings[index].toDouble() / sampleTotals[index].toDouble()
                } else {
                    0.0
                }
            )
        }
        val maximumPeak = metrics.maxOfOrNull { it.peak } ?: 0.0
        val maximumRms = metrics.maxOfOrNull { it.rms } ?: 0.0
        if (maximumPeak < 0.0032 && maximumRms < 0.0016) {
            return analyzeVisualMotionOrFallback(
                context = context,
                uri = uri,
                durationSeconds = durationSeconds,
                bucketCount = bucketCount,
                audioAvailability = ClipAudioAvailability.Silent
            )
        }
        val waveform = normalizedWaveform(metrics)
        val frames = metrics.mapIndexed { index, metric ->
            AudioImpactFrame(
                timeSeconds = (index + 0.5) / bucketCount.toDouble() * durationSeconds,
                metrics = metric
            )
        }
        return pickPeaks(waveform, frames, durationSeconds)
    }

    private data class PcmMetrics(
        val rms: Double,
        val peak: Double,
        val crossings: Int,
        val sampleCount: Int
    )

    private fun metrics16BitPcm(buffer: java.nio.ByteBuffer): PcmMetrics {
        if (buffer.remaining() < 2) return PcmMetrics(0.0, 0.0, 0, 0)
        val samples = buffer.asShortBuffer()
        var sum = 0.0
        var peak = 0.0
        var crossings = 0
        var count = 0
        var previousSign = 0
        while (samples.hasRemaining()) {
            val value = samples.get().toDouble() / Short.MAX_VALUE.toDouble()
            val sign = if (value >= 0.0) 1 else -1
            sum += value * value
            peak = max(peak, abs(value))
            if (count > 0 && sign != previousSign) {
                crossings += 1
            }
            previousSign = sign
            count += 1
        }
        return PcmMetrics(
            rms = sqrt(sum / max(1, count)),
            peak = peak,
            crossings = crossings,
            sampleCount = count
        )
    }

    private fun metricsFloatPcm(buffer: java.nio.ByteBuffer): PcmMetrics {
        if (buffer.remaining() < Float.SIZE_BYTES) return PcmMetrics(0.0, 0.0, 0, 0)
        val samples = buffer.asFloatBuffer()
        var sum = 0.0
        var peak = 0.0
        var crossings = 0
        var count = 0
        var previousSign = 0
        while (samples.hasRemaining()) {
            val value = samples.get().toDouble().coerceIn(-1.0, 1.0)
            val sign = if (value >= 0.0) 1 else -1
            sum += value * value
            peak = max(peak, abs(value))
            if (count > 0 && sign != previousSign) crossings += 1
            previousSign = sign
            count += 1
        }
        return PcmMetrics(
            rms = sqrt(sum / max(1, count)),
            peak = peak,
            crossings = crossings,
            sampleCount = count
        )
    }

    private fun normalizedWaveform(metrics: List<AudioImpactMetrics>): List<Double> {
        val values = metrics.map { it.impactScore }
        val maximum = max(values.maxOrNull() ?: 0.0, 0.000_1)
        return values.map { value ->
            max(0.04, min(1.0, value / maximum))
        }
    }

    private fun pickPeaks(
        values: List<Double>,
        frames: List<AudioImpactFrame>,
        durationSeconds: Double
    ): AudioAnalysisResult {
        val bucketCount = values.size.coerceAtLeast(1)
        val candidates = mutableListOf<Pair<Int, Double>>()
        var bestIndex = bucketCount / 2
        var bestScore = Double.NEGATIVE_INFINITY

        for (index in 1 until bucketCount) {
            val historyStart = max(0, index - 5)
            val history = values.subList(historyStart, index)
            val baseline = history.sum() / max(1, history.size)
            val rise = values[index] - baseline
            val score = rise + values[index] * 0.25
            if (score > bestScore) {
                bestIndex = index
                bestScore = score
            }

            val previous = values[index - 1]
            val next = if (index + 1 < bucketCount) values[index + 1] else 0.0
            if (rise > 0.04) {
                candidates += index to score
            }
            if (values[index] >= previous && values[index] >= next) {
                candidates += index to score
            }
        }

        if (candidates.none { it.first == bestIndex }) {
            candidates += bestIndex to bestScore
        }
        values.forEachIndexed { index, value ->
            candidates += index to value * 0.55
        }
        AudioImpactClassifier.rankedImpactTimes(
            frames = frames,
            durationSeconds = durationSeconds,
            limit = 12
        ).forEach { peak ->
            val index = ((peak / durationSeconds) * bucketCount)
                .toInt()
                .coerceIn(0, bucketCount - 1)
            candidates += index to (2.0 + values[index])
        }

        val bucketsPerSecond = bucketCount / max(durationSeconds, 0.1)
        val minimumSeparation = max(1, (bucketsPerSecond * 0.45).roundToInt())
        val selected = mutableListOf<Pair<Int, Double>>()
        candidates
            .distinctBy { it.first }
            .sortedByDescending { it.second }
            .forEach { candidate ->
                if (selected.all { abs(it.first - candidate.first) >= minimumSeparation }) {
                    selected += candidate
                }
                if (selected.size >= 12) return@forEach
            }

        if (selected.isEmpty()) {
            selected += bestIndex to bestScore
        }

        val peakTimes = selected.map { (index, _) ->
            (index + 0.5) / bucketCount.toDouble() * durationSeconds
        }
        return AudioAnalysisResult(
            waveform = values,
            peakTimeSeconds = peakTimes.first(),
            peakTimesSeconds = peakTimes,
            audioAvailability = ClipAudioAvailability.Present,
            highlightSource = ClipHighlightSource.Audio
        )
    }

    private object AudioImpactClassifier {
        private data class Thresholds(
            val strongScoreFloor: Double,
            val strongBaselineMultiplier: Double,
            val strongPeakFloor: Double,
            val strongPeakBaselineMultiplier: Double,
            val strongRise: Double,
            val strongCrossingRate: Double,
            val strongCrestFactor: Double,
            val distantScoreFloor: Double,
            val distantBaselineMultiplier: Double,
            val distantPeakFloor: Double,
            val distantPeakBaselineMultiplier: Double,
            val distantRise: Double,
            val distantCrossingRate: Double,
            val distantCrestFactor: Double
        )

        private enum class Sensitivity {
            Noisy,
            Normal,
            Quiet,
            Automatic
        }

        private data class Decision(
            val isTriggered: Boolean,
            val confidence: Double
        )

        fun rankedImpactTimes(
            frames: List<AudioImpactFrame>,
            durationSeconds: Double,
            limit: Int
        ): List<Double> {
            if (frames.isEmpty()) return emptyList()

            var baseline = 0.008
            var recentLevel = 0.008
            val candidates = mutableListOf<Pair<Double, Double>>()
            val fallback = mutableListOf<Pair<Double, Double>>()

            frames.forEachIndexed { index, frame ->
                val metrics = frame.metrics
                val score = metrics.impactScore
                val previousRecentLevel = recentLevel
                val baselineSample = min(score, max(0.004, baseline * 1.35))
                baseline = baseline * 0.985 + max(0.002, baselineSample) * 0.015
                recentLevel = recentLevel * 0.72 + max(0.002, score) * 0.28

                val decision = detectImpact(
                    metrics = metrics,
                    baseline = baseline,
                    previousRecentLevel = previousRecentLevel,
                    sensitivity = Sensitivity.Automatic
                )
                val neighborhoodScore = localContrastScore(frames, index)
                val highlightScore = generalHighlightScore(frames, index)
                val memorableMomentScore = memorableMomentScore(frames, index)
                val combinedScore = decision.confidence +
                    neighborhoodScore * 0.45 +
                    highlightScore * 0.75 +
                    memorableMomentScore * 0.58 +
                    score * 0.12

                fallback += frame.timeSeconds to combinedScore
                if (
                    decision.isTriggered ||
                    highlightScore > 0.82 ||
                    memorableMomentScore > 0.78 ||
                    combinedScore > 1.02
                ) {
                    candidates += frame.timeSeconds to combinedScore
                }
            }

            val ranked = if (candidates.isEmpty()) fallback else candidates
            val minimumSeparation = max(0.45, durationSeconds / 160.0)
            val selected = mutableListOf<Pair<Double, Double>>()
            ranked.sortedByDescending { it.second }.forEach { candidate ->
                if (selected.all { abs(it.first - candidate.first) >= minimumSeparation }) {
                    selected += candidate
                }
                if (selected.size >= limit) return@forEach
            }
            return selected.map { it.first }
        }

        private fun detectImpact(
            metrics: AudioImpactMetrics,
            baseline: Double,
            previousRecentLevel: Double,
            sensitivity: Sensitivity
        ): Decision {
            val score = metrics.impactScore
            val referenceLevel = max(0.003, max(baseline, previousRecentLevel * 0.82))
            val suddenRise = score / referenceLevel
            val crestFactor = metrics.peak / max(0.001, metrics.rms)
            val thresholds = thresholdsFor(effectiveSensitivity(sensitivity, baseline))

            val strongScoreRequirement = max(
                thresholds.strongScoreFloor,
                baseline * thresholds.strongBaselineMultiplier
            )
            val strongPeakRequirement = max(
                thresholds.strongPeakFloor,
                baseline * thresholds.strongPeakBaselineMultiplier
            )
            val isStrongImpact = score >= strongScoreRequirement &&
                metrics.peak >= strongPeakRequirement &&
                suddenRise >= thresholds.strongRise &&
                metrics.crossingRate >= thresholds.strongCrossingRate &&
                crestFactor >= thresholds.strongCrestFactor

            val distantScoreRequirement = max(
                thresholds.distantScoreFloor,
                baseline * thresholds.distantBaselineMultiplier
            )
            val distantPeakRequirement = max(
                thresholds.distantPeakFloor,
                baseline * thresholds.distantPeakBaselineMultiplier
            )
            val isDistantSharpImpact = score >= distantScoreRequirement &&
                metrics.peak >= distantPeakRequirement &&
                suddenRise >= thresholds.distantRise &&
                metrics.crossingRate >= thresholds.distantCrossingRate &&
                crestFactor >= thresholds.distantCrestFactor

            val isSpeechLikePrompt = metrics.rms >= 0.025 &&
                crestFactor < 3.15 &&
                metrics.crossingRate < 0.16 &&
                suddenRise < 4.8 &&
                score < 0.18

            val confidence = impactConfidence(
                score = score,
                peak = metrics.peak,
                suddenRise = suddenRise,
                crossingRate = metrics.crossingRate,
                crestFactor = crestFactor,
                thresholds = thresholds
            )

            return Decision(
                isTriggered = !isSpeechLikePrompt && (isStrongImpact || isDistantSharpImpact),
                confidence = if (isSpeechLikePrompt) 0.0 else confidence
            )
        }

        private fun effectiveSensitivity(sensitivity: Sensitivity, baseline: Double): Sensitivity {
            if (sensitivity != Sensitivity.Automatic) return sensitivity
            return when {
                baseline >= 0.026 -> Sensitivity.Noisy
                baseline <= 0.009 -> Sensitivity.Quiet
                else -> Sensitivity.Normal
            }
        }

        private fun thresholdsFor(sensitivity: Sensitivity): Thresholds {
            return when (sensitivity) {
                Sensitivity.Noisy -> Thresholds(
                    strongScoreFloor = 0.10,
                    strongBaselineMultiplier = 2.7,
                    strongPeakFloor = 0.18,
                    strongPeakBaselineMultiplier = 4.2,
                    strongRise = 2.2,
                    strongCrossingRate = 0.07,
                    strongCrestFactor = 2.3,
                    distantScoreFloor = 0.065,
                    distantBaselineMultiplier = 3.4,
                    distantPeakFloor = 0.12,
                    distantPeakBaselineMultiplier = 5.0,
                    distantRise = 3.0,
                    distantCrossingRate = 0.10,
                    distantCrestFactor = 3.5
                )
                Sensitivity.Normal,
                Sensitivity.Automatic -> Thresholds(
                    strongScoreFloor = 0.075,
                    strongBaselineMultiplier = 2.3,
                    strongPeakFloor = 0.13,
                    strongPeakBaselineMultiplier = 3.5,
                    strongRise = 1.8,
                    strongCrossingRate = 0.05,
                    strongCrestFactor = 2.0,
                    distantScoreFloor = 0.045,
                    distantBaselineMultiplier = 2.8,
                    distantPeakFloor = 0.09,
                    distantPeakBaselineMultiplier = 4.2,
                    distantRise = 2.4,
                    distantCrossingRate = 0.08,
                    distantCrestFactor = 3.0
                )
                Sensitivity.Quiet -> Thresholds(
                    strongScoreFloor = 0.055,
                    strongBaselineMultiplier = 1.9,
                    strongPeakFloor = 0.095,
                    strongPeakBaselineMultiplier = 3.0,
                    strongRise = 1.55,
                    strongCrossingRate = 0.04,
                    strongCrestFactor = 1.7,
                    distantScoreFloor = 0.035,
                    distantBaselineMultiplier = 2.3,
                    distantPeakFloor = 0.07,
                    distantPeakBaselineMultiplier = 3.4,
                    distantRise = 2.0,
                    distantCrossingRate = 0.065,
                    distantCrestFactor = 2.5
                )
            }
        }

        private fun impactConfidence(
            score: Double,
            peak: Double,
            suddenRise: Double,
            crossingRate: Double,
            crestFactor: Double,
            thresholds: Thresholds
        ): Double {
            val scoreRatio = score / max(0.001, thresholds.distantScoreFloor)
            val peakRatio = peak / max(0.001, thresholds.distantPeakFloor)
            val riseRatio = suddenRise / max(0.001, thresholds.distantRise)
            val crossingRatio = crossingRate / max(0.001, thresholds.distantCrossingRate)
            val crestRatio = crestFactor / max(0.001, thresholds.distantCrestFactor)
            return scoreRatio * 0.28 +
                peakRatio * 0.22 +
                riseRatio * 0.24 +
                min(crossingRatio, 1.8) * 0.13 +
                min(crestRatio, 1.8) * 0.13
        }

        private fun localContrastScore(frames: List<AudioImpactFrame>, index: Int): Double {
            val start = max(0, index - 5)
            val history = frames.subList(start, index).map { it.metrics.impactScore }
            val baseline = history.sum() / max(1, history.size)
            val value = frames[index].metrics.impactScore
            val previous = if (index > 0) frames[index - 1].metrics.impactScore else 0.0
            val next = if (index + 1 < frames.size) frames[index + 1].metrics.impactScore else 0.0
            val rise = max(0.0, value - baseline)
            val isLocalPeak = value >= previous && value >= next
            return rise * 5.0 + if (isLocalPeak) value * 0.65 else 0.0
        }

        private fun generalHighlightScore(frames: List<AudioImpactFrame>, index: Int): Double {
            val metrics = frames[index].metrics
            val value = metrics.impactScore
            val history = frames.subList(max(0, index - 10), index)
            val historyCount = max(1, history.size)
            val baselineScore = history.sumOf { it.metrics.impactScore } / historyCount
            val baselineRms = history.sumOf { it.metrics.rms } / historyCount
            val baselinePeak = history.sumOf { it.metrics.peak } / historyCount
            val previous = if (index > 0) frames[index - 1].metrics.impactScore else 0.0
            val next = if (index + 1 < frames.size) frames[index + 1].metrics.impactScore else 0.0
            val isLocalPeak = value >= previous && value >= next
            val scoreRiseRatio = value / max(0.006, baselineScore)
            val rmsRiseRatio = metrics.rms / max(0.004, baselineRms)
            val peakRiseRatio = metrics.peak / max(0.012, baselinePeak)
            val directRise = max(0.0, value - previous)
            val sustainedEnergy = min(1.0, value * 1.25 + metrics.peak * 0.35)

            return min(
                1.6,
                max(0.0, scoreRiseRatio - 1.0) * 0.22 +
                    max(0.0, rmsRiseRatio - 1.0) * 0.16 +
                    max(0.0, peakRiseRatio - 1.0) * 0.14 +
                    directRise * 2.4 +
                    sustainedEnergy * 0.28 +
                    if (isLocalPeak) value * 0.45 else 0.0
            )
        }

        private fun memorableMomentScore(frames: List<AudioImpactFrame>, index: Int): Double {
            val frame = frames[index]
            val before = frames.subList(max(0, index - 8), index)
            val after = frames.subList(index + 1, min(frames.size, index + 9))
            if (before.size < 2 || after.size < 2) return 0.0

            val beforeEnergy = before.sumOf { it.metrics.impactScore } / before.size
            val afterEnergy = after.sumOf { it.metrics.impactScore } / after.size
            val afterPeak = after.maxOfOrNull { it.metrics.peak } ?: 0.0
            val currentEnergy = frame.metrics.impactScore
            val riseIntoMoment = max(0.0, currentEnergy - beforeEnergy)
            val heldExcitement = afterEnergy / max(0.008, beforeEnergy)
            val audibleResponseWeight = min(1.0, afterEnergy / 0.045)
            val heldExcitementBonus = min(2.4, max(0.0, heldExcitement - 1.0)) *
                audibleResponseWeight
            val responsePeak = max(0.0, afterPeak - frame.metrics.peak * 0.45)
            val crossingTexture = min(1.0, frame.metrics.crossingRate * 9.0)
            val distanceFromEdge = min(index, frames.size - 1 - index)
            val edgeConfidence = min(1.0, distanceFromEdge / 6.0)

            val score = min(
                1.5,
                riseIntoMoment * 2.2 +
                    heldExcitementBonus * 0.28 +
                    responsePeak * 0.45 +
                    currentEnergy * 0.34 +
                    crossingTexture * currentEnergy * 0.18
            )
            return score * edgeConfidence
        }
    }

    private data class VisualFrameSummary(
        val timeSeconds: Double,
        val cells: DoubleArray,
        val averageBrightness: Double
    )

    private data class VisualMotionSample(
        val timeSeconds: Double,
        val score: Double
    )

    private fun analyzeVisualMotionOrFallback(
        context: Context,
        uri: Uri,
        durationSeconds: Double,
        bucketCount: Int,
        audioAvailability: ClipAudioAvailability
    ): AudioAnalysisResult {
        return runCatching {
            analyzeVisualMotion(
                context = context,
                uri = uri,
                durationSeconds = durationSeconds,
                bucketCount = bucketCount,
                audioAvailability = audioAvailability
            )
        }.getOrElse {
            if (it is CancellationException) throw it
            fallbackResult(durationSeconds, bucketCount, audioAvailability)
        }
    }

    private fun analyzeVisualMotion(
        context: Context,
        uri: Uri,
        durationSeconds: Double,
        bucketCount: Int,
        audioAvailability: ClipAudioAvailability
    ): AudioAnalysisResult {
        val safeDuration = durationSeconds.coerceAtLeast(0.1)
        val retriever = MediaMetadataRetriever()
        val hasVideo = try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
                ?.equals("yes", ignoreCase = true) == true
        } catch (failure: Throwable) {
            retriever.release()
            throw failure
        }
        if (!hasVideo) {
            retriever.release()
            return fallbackResult(safeDuration, bucketCount, audioAvailability)
        }

        val maximumSamples = 1_200
        val sampleInterval = max(0.2, safeDuration / maximumSamples.toDouble())
        val samples = mutableListOf<VisualMotionSample>()
        var previousFrame: VisualFrameSummary? = null
        var motionBaseline = 0.008
        var timeSeconds = 0.0
        try {
            while (timeSeconds <= safeDuration && samples.size < maximumSamples) {
                if (Thread.currentThread().isInterrupted) throw CancellationException()
                val frame = scaledFrameAtTime(retriever, timeSeconds)
                if (frame == null) {
                    timeSeconds += sampleInterval
                    continue
                }
                val summary = try { visualFrameSummary(frame, timeSeconds) } finally { frame.recycle() }
                val previous = previousFrame
                previousFrame = summary
                if (previous != null && previous.cells.size == summary.cells.size) {
                    val differences = DoubleArray(summary.cells.size) { index ->
                        abs(summary.cells[index] - previous.cells[index])
                    }
                    val meanDifference = differences.average()
                    val sortedDifferences = differences.sortedArray()
                    val medianDifference = sortedDifferences[sortedDifferences.size / 2]
                    val residuals = differences
                        .map { max(0.0, it - medianDifference * 0.9) }
                        .sortedDescending()
                    val focusedCount = max(1, residuals.size / 4)
                    val localMotion = residuals.take(focusedCount).average()
                    val widespreadRatio = differences.count { it > 0.12 }.toDouble() /
                        max(1, differences.size).toDouble()
                    val brightnessChange = abs(
                        summary.averageBrightness - previous.averageBrightness
                    )
                    val isSceneBoundary = widespreadRatio > 0.72 && meanDifference > 0.14
                    val isFlash = brightnessChange > 0.22 && widespreadRatio > 0.55
                    val cappedBaselineSample = min(
                        localMotion,
                        max(0.006, motionBaseline * 1.5)
                    )
                    motionBaseline = motionBaseline * 0.94 + cappedBaselineSample * 0.06
                    val contrast = localMotion / max(0.004, motionBaseline)
                    val score = if (isSceneBoundary || isFlash || localMotion < 0.0035) {
                        0.0
                    } else {
                        min(
                            1.0,
                            max(0.0, contrast - 1.0) * 0.28 +
                                min(1.0, localMotion * 8.0) * 0.72
                        )
                    }
                    samples += VisualMotionSample(timeSeconds, score)
                }
                timeSeconds += sampleInterval
            }
        } finally {
            retriever.release()
        }

        if (samples.size < 3) {
            return fallbackResult(safeDuration, bucketCount, audioAvailability)
        }
        val edgeDuration = min(max(0.5, safeDuration * 0.05), max(0.5, safeDuration / 3.0))
        var smoothed = samples.indices.map { index ->
            val previous = if (index > 0) samples[index - 1].score else 0.0
            val current = samples[index].score
            val next = if (index + 1 < samples.size) samples[index + 1].score else 0.0
            val value = previous * 0.22 + current * 0.56 + next * 0.22
            val sampleTime = samples[index].timeSeconds
            if (sampleTime >= edgeDuration && sampleTime <= safeDuration - edgeDuration) value else 0.0
        }
        val maximumScore = smoothed.maxOrNull() ?: 0.0
        if (maximumScore < 0.12) {
            return fallbackResult(safeDuration, bucketCount, audioAvailability)
        }
        smoothed = smoothed.map { (it / maximumScore).coerceIn(0.0, 1.0) }
        val candidates = smoothed.indices.mapNotNull { index ->
            val previous = if (index > 0) smoothed[index - 1] else 0.0
            val next = if (index + 1 < smoothed.size) smoothed[index + 1] else 0.0
            smoothed[index].takeIf { it >= 0.28 && it >= previous && it >= next }
                ?.let { index to it }
        }
        val minimumSeparation = max(0.75, safeDuration / 20.0)
        val selected = mutableListOf<Pair<Int, Double>>()
        candidates
            .sortedWith(compareByDescending<Pair<Int, Double>> { it.second }.thenBy { samples[it.first].timeSeconds })
            .forEach { candidate ->
                if (selected.all {
                        abs(samples[it.first].timeSeconds - samples[candidate.first].timeSeconds) >=
                            minimumSeparation
                    }
                ) {
                    selected += candidate
                }
                if (selected.size >= 12) return@forEach
            }
        val strongest = selected.firstOrNull()
            ?: return fallbackResult(safeDuration, bucketCount, audioAvailability)
        val waveform = MutableList(bucketCount) { 0.0 }
        samples.forEachIndexed { index, sample ->
            val bucket = ((sample.timeSeconds / safeDuration) * bucketCount)
                .toInt()
                .coerceIn(0, bucketCount - 1)
            waveform[bucket] = max(waveform[bucket], smoothed[index])
        }
        return AudioAnalysisResult(
            waveform = waveform,
            peakTimeSeconds = samples[strongest.first].timeSeconds,
            peakTimesSeconds = selected.map { samples[it.first].timeSeconds },
            audioAvailability = audioAvailability,
            highlightSource = ClipHighlightSource.VisualMotion
        )
    }

    private fun scaledFrameAtTime(
        retriever: MediaMetadataRetriever,
        timeSeconds: Double
    ): Bitmap? {
        val timeUs = (timeSeconds * 1_000_000.0).toLong()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            return retriever.getScaledFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST,
                160,
                90
            )
        }
        val source = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
            ?: return null
        val scaled = Bitmap.createScaledBitmap(source, 160, 90, true)
        if (scaled !== source) source.recycle()
        return scaled
    }

    private fun visualFrameSummary(bitmap: Bitmap, timeSeconds: Double): VisualFrameSummary {
        val gridWidth = 8
        val gridHeight = 8
        val cells = DoubleArray(gridWidth * gridHeight)
        var brightnessTotal = 0.0
        var outputIndex = 0
        for (yCell in 0 until gridHeight) {
            for (xCell in 0 until gridWidth) {
                var cellBrightness = 0.0
                for (yQuarter in intArrayOf(1, 3)) {
                    val y = ((yCell * bitmap.height + yQuarter * bitmap.height / 4) / gridHeight)
                        .coerceIn(0, bitmap.height - 1)
                    for (xQuarter in intArrayOf(1, 3)) {
                        val x = ((xCell * bitmap.width + xQuarter * bitmap.width / 4) / gridWidth)
                            .coerceIn(0, bitmap.width - 1)
                        val pixel = bitmap.getPixel(x, y)
                        cellBrightness += (
                            android.graphics.Color.red(pixel) * 0.299 +
                                android.graphics.Color.green(pixel) * 0.587 +
                                android.graphics.Color.blue(pixel) * 0.114
                            ) / 255.0
                    }
                }
                val average = cellBrightness / 4.0
                cells[outputIndex++] = average
                brightnessTotal += average
            }
        }
        return VisualFrameSummary(
            timeSeconds = timeSeconds,
            cells = cells,
            averageBrightness = brightnessTotal / max(1, cells.size).toDouble()
        )
    }

    private fun fallbackResult(
        durationSeconds: Double,
        bucketCount: Int,
        audioAvailability: ClipAudioAvailability = ClipAudioAvailability.Unknown
    ): AudioAnalysisResult {
        val peak = max(0.0, durationSeconds / 2.0)
        return AudioAnalysisResult(
            waveform = List(bucketCount) { 0.0 },
            peakTimeSeconds = peak,
            peakTimesSeconds = listOf(peak),
            audioAvailability = audioAvailability,
            highlightSource = ClipHighlightSource.Fallback
        )
    }
}

enum class ClipAudioAvailability { Unknown, Present, NoTrack, Silent }
enum class ClipHighlightSource { Audio, VisualMotion, Fallback }
