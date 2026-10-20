package com.armsone.nasfinder.ui

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Audio ducking processor for background music.
 * Applied to the background music track in Transformer.
 *
 * When [autoDucking] is enabled:
 * - Samples the continuous original-audio loudness envelope on the rendered output timeline
 *   (accounting for scene start/length offsets, photo/silent clips, and repeated/looped music via [outputStartOffsetSeconds]).
 * - Loud original audio smoothly ducks background music down toward [duckedGain];
 *   soft/silent original audio or still photos smoothly raise background music toward [fullGain].
 * - Attack (~0.08s) and release (~0.28s) exponential smoothing prevents clicks and sudden volume jumps.
 *
 * When [autoDucking] is disabled:
 * - Applies a constant background music gain ([constantGain]) while leaving original clip audio untouched.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class AutoDuckingAudioProcessor(
    private val timeline: AudioDuckingTimeline,
    private val outputStartOffsetSeconds: Double = 0.0,
    private val autoDucking: Boolean = true,
    private val duckedGain: Float = 0.18f,
    private val fullGain: Float = 0.85f,
    private val constantGain: Float = 0.85f,
) : BaseAudioProcessor() {

    private var currentGain: Float = initialGain()
    private var processedFrames: Long = 0L

    private fun initialGain(): Float {
        return if (autoDucking) {
            timeline.targetMusicGainAt(outputStartOffsetSeconds, duckedGain, fullGain)
        } else {
            constantGain
        }
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun onFlush() {
        super.onFlush()
        if (processedFrames == 0L) {
            currentGain = initialGain()
        }
    }

    override fun onReset() {
        super.onReset()
        processedFrames = 0L
        currentGain = initialGain()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val format = inputAudioFormat
        val sampleRate = format.sampleRate
        val channelCount = format.channelCount
        val bytesPerFrame = format.bytesPerFrame.coerceAtLeast(channelCount * 2)

        val frameCount = remaining / bytesPerFrame
        val outputBuffer = replaceOutputBuffer(remaining)

        // Smoothing coefficients: fast attack (~0.08s) when original audio gets louder,
        // gentle release (~0.28s) when original audio softens/ends.
        val attackAlpha = (1.0 - exp(-1.0 / (sampleRate * 0.08))).toFloat().coerceIn(0.0001f, 1f)
        val releaseAlpha = (1.0 - exp(-1.0 / (sampleRate * 0.28))).toFloat().coerceIn(0.0001f, 1f)

        if (format.encoding == C.ENCODING_PCM_16BIT) {
            val inputShorts = inputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val outputShorts = outputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()

            for (i in 0 until frameCount) {
                val gain = if (autoDucking) {
                    val currentSeconds = outputStartOffsetSeconds + ((processedFrames + i).toDouble() / sampleRate.toDouble())
                    val targetGain = timeline.targetMusicGainAt(currentSeconds, duckedGain, fullGain)
                    val alpha = if (targetGain < currentGain) attackAlpha else releaseAlpha
                    currentGain += alpha * (targetGain - currentGain)
                    currentGain
                } else {
                    constantGain
                }
                for (ch in 0 until channelCount) {
                    val sample = inputShorts.get()
                    val scaled = (sample * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    outputShorts.put(scaled.toShort())
                }
            }
            outputBuffer.position(0)
            outputBuffer.limit(frameCount * bytesPerFrame)
            inputBuffer.position(inputBuffer.position() + frameCount * bytesPerFrame)
        } else if (format.encoding == C.ENCODING_PCM_FLOAT) {
            val inputFloats = inputBuffer.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val outputFloats = outputBuffer.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()

            for (i in 0 until frameCount) {
                val gain = if (autoDucking) {
                    val currentSeconds = outputStartOffsetSeconds + ((processedFrames + i).toDouble() / sampleRate.toDouble())
                    val targetGain = timeline.targetMusicGainAt(currentSeconds, duckedGain, fullGain)
                    val alpha = if (targetGain < currentGain) attackAlpha else releaseAlpha
                    currentGain += alpha * (targetGain - currentGain)
                    currentGain
                } else {
                    constantGain
                }
                for (ch in 0 until channelCount) {
                    val sample = inputFloats.get()
                    val scaled = (sample * gain).coerceIn(-1.0f, 1.0f)
                    outputFloats.put(scaled)
                }
            }
            outputBuffer.position(0)
            outputBuffer.limit(frameCount * bytesPerFrame)
            inputBuffer.position(inputBuffer.position() + frameCount * bytesPerFrame)
        } else {
            outputBuffer.put(inputBuffer)
        }

        processedFrames += frameCount
    }
}

/**
 * Maps output presentation timestamp (in seconds) to continuous original-audio loudness (`0.0f..1.0f`)
 * and target background music gain across the rendered scene timeline.
 */
internal class AudioDuckingTimeline(
    private val intervals: List<AudioActiveInterval> = emptyList(),
    private val loudnessBins: FloatArray = FloatArray(0),
    private val binSeconds: Double = 0.05,
) {
    data class AudioActiveInterval(val startSeconds: Double, val endSeconds: Double)

    fun isAudioActiveAt(seconds: Double): Boolean {
        if (loudnessBins.isNotEmpty()) {
            return loudnessAt(seconds) >= 0.15f
        }
        for (interval in intervals) {
            if (seconds in interval.startSeconds..interval.endSeconds) {
                return true
            }
            if (interval.startSeconds > seconds) break
        }
        return false
    }

    /**
     * Returns continuous original-audio loudness (`0.0f..1.0f`) at [seconds] on the rendered timeline,
     * linearly interpolated between adjacent bins.
     */
    fun loudnessAt(seconds: Double): Float {
        if (seconds < 0.0) return 0f
        if (loudnessBins.isNotEmpty() && binSeconds > 0.0) {
            val exactIndex = seconds / binSeconds
            val idx0 = exactIndex.toInt()
            if (idx0 >= loudnessBins.size) return 0f
            val idx1 = min(loudnessBins.lastIndex, idx0 + 1)
            val frac = (exactIndex - idx0).toFloat().coerceIn(0f, 1f)
            val v0 = loudnessBins[idx0]
            val v1 = loudnessBins[idx1]
            return (v0 + (v1 - v0) * frac).coerceIn(0f, 1f)
        }
        return if (isAudioActiveAt(seconds)) 1f else 0f
    }

    /**
     * Computes the continuous target background music gain at [seconds]:
     * - `loudness == 0f` (silent clip / photo / pause) -> [fullGain]
     * - `loudness == 1f` (loud speech / loud original audio) -> [duckedGain]
     * - intermediate original loudness -> smooth continuous gain between [fullGain] and [duckedGain]
     */
    fun targetMusicGainAt(
        seconds: Double,
        duckedGain: Float = 0.18f,
        fullGain: Float = 0.85f,
    ): Float {
        val loudness = loudnessAt(seconds).coerceIn(0f, 1f)
        // Slight perceptual emphasis (loudness^0.85) so moderate speech already ducks clearly
        // while soft ambient sounds duck gently and loud original audio ducks fully to duckedGain.
        val shaped = Math.pow(loudness.toDouble(), 0.85).toFloat().coerceIn(0f, 1f)
        return (fullGain - shaped * (fullGain - duckedGain)).coerceIn(min(duckedGain, fullGain), max(duckedGain, fullGain))
    }

    companion object {
        fun constant(): AudioDuckingTimeline = AudioDuckingTimeline()

        /**
         * Builds a continuous rendered-timeline loudness map from each scene's `[scene.start, scene.start + scene.length]`
         * slice of its source clip's loudness envelope (`0.0f..1.0f`), preserving exact scene offsets,
         * photo/silent clips (`0.0f`), and original clip audio level.
         */
        fun buildWithLoudnessEnvelopes(
            scenes: List<TrailerScene>,
            envelopes: Map<Int, FloatArray>,
            binSeconds: Double = 0.05,
            originalClipGain: Float = 1.0f,
        ): AudioDuckingTimeline {
            val safeBinSeconds = binSeconds.coerceAtLeast(0.01)
            val totalSeconds = scenes.sumOf { it.length.coerceAtLeast(0.0) }
            if (totalSeconds <= 0.0) return AudioDuckingTimeline()

            val totalBins = max(1, kotlin.math.ceil(totalSeconds / safeBinSeconds).toInt())
            val timelineBins = FloatArray(totalBins)
            val intervals = mutableListOf<AudioActiveInterval>()
            var timelineOffset = 0.0
            val safeOriginalGain = originalClipGain.coerceAtLeast(0f)

            for (scene in scenes) {
                val sceneLen = scene.length.coerceAtLeast(0.0)
                val sceneStart = timelineOffset
                val sceneEnd = timelineOffset + sceneLen

                if (!scene.source.isImage && sceneLen > 0.0) {
                    val env = envelopes[scene.source.id]
                    val startBin = (sceneStart / safeBinSeconds).toInt().coerceIn(0, totalBins - 1)
                    val endBin = kotlin.math.ceil(sceneEnd / safeBinSeconds).toInt().coerceIn(startBin, totalBins)

                    for (b in startBin until endBin) {
                        val timelineTime = (b + 0.5) * safeBinSeconds
                        val localSceneTime = (timelineTime - sceneStart).coerceIn(0.0, sceneLen)
                        val sourceTime = scene.start + localSceneTime

                        val rawLoudness = when {
                            env != null && env.isNotEmpty() && scene.source.duration > 0.0 -> {
                                val srcBinDuration = (scene.source.duration / env.size.toDouble()).coerceAtLeast(0.001)
                                val exactSrcIdx = (sourceTime / srcBinDuration).coerceIn(0.0, (env.size - 1).toDouble())
                                val s0 = exactSrcIdx.toInt().coerceIn(env.indices)
                                val s1 = min(env.lastIndex, s0 + 1)
                                val frac = (exactSrcIdx - s0).toFloat().coerceIn(0f, 1f)
                                env[s0] + (env[s1] - env[s0]) * frac
                            }
                            env != null && env.isEmpty() -> {
                                // Decoder error fallback: moderate ducking (0.65f) so original audio stays intelligible
                                0.65f
                            }
                            scene.source.duration > 0.0 -> 0.65f
                            else -> 0f
                        }
                        // A muted/quieter scene ducks the music proportionally less.
                        val effectiveLoudness = (rawLoudness * safeOriginalGain * scene.sourceVolume.coerceIn(0f, 2f)).coerceIn(0f, 1f)
                        timelineBins[b] = max(timelineBins[b], effectiveLoudness)
                    }
                }
                timelineOffset += sceneLen
            }

            // Populate active intervals for any callers checking isAudioActiveAt
            var activeStart: Double? = null
            for (b in 0 until totalBins) {
                val bTime = b * safeBinSeconds
                if (timelineBins[b] >= 0.15f) {
                    if (activeStart == null) activeStart = bTime
                } else if (activeStart != null) {
                    intervals.add(AudioActiveInterval(activeStart, bTime))
                    activeStart = null
                }
            }
            if (activeStart != null) {
                intervals.add(AudioActiveInterval(activeStart, totalSeconds))
            }

            return AudioDuckingTimeline(
                intervals = intervals,
                loudnessBins = timelineBins,
                binSeconds = safeBinSeconds,
            )
        }

        fun buildWithEnvelopes(
            scenes: List<TrailerScene>,
            envelopes: Map<Int, BooleanArray>,
            binSeconds: Double = 0.1,
        ): AudioDuckingTimeline {
            val floatEnvelopes = envelopes.mapValues { (_, boolArr) ->
                FloatArray(boolArr.size) { idx -> if (boolArr[idx]) 1f else 0f }
            }
            return buildWithLoudnessEnvelopes(
                scenes = scenes,
                envelopes = floatEnvelopes,
                binSeconds = binSeconds,
            )
        }
    }
}
