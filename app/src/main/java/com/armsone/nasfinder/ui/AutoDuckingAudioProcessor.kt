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
 * Uses original video/scene speech/audio presence timeline (energy envelope).
 * Where original audio is audible/active, music gain is ducked smoothly to [duckedGain].
 * Where original audio is silent or photo clips, music gain returns to [fullGain].
 *
 * Attack (~0.1s) and release (~0.3s) exponential smoothing avoids clicks and sudden volume jumps.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class AutoDuckingAudioProcessor(
    private val timeline: AudioDuckingTimeline,
    private val outputStartOffsetSeconds: Double = 0.0,
    private val duckedGain: Float = 0.22f,
    private val fullGain: Float = 0.85f,
) : BaseAudioProcessor() {

    private var currentGain: Float = fullGain
    private var processedFrames: Long = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun onFlush() {
        super.onFlush()
        currentGain = fullGain
        processedFrames = 0L
    }

    override fun onReset() {
        super.onReset()
        currentGain = fullGain
        processedFrames = 0L
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

        // Smoothing coefficients: attack ~0.1s, release ~0.3s
        // alpha = 1.0 - exp(-1.0 / (sampleRate * tau))
        val attackAlpha = (1.0 - exp(-1.0 / (sampleRate * 0.10))).toFloat().coerceIn(0.0001f, 1f)
        val releaseAlpha = (1.0 - exp(-1.0 / (sampleRate * 0.30))).toFloat().coerceIn(0.0001f, 1f)

        if (format.encoding == C.ENCODING_PCM_16BIT) {
            val inputShorts = inputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val outputShorts = outputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()

            for (i in 0 until frameCount) {
                val currentSeconds = outputStartOffsetSeconds + ((processedFrames + i).toDouble() / sampleRate.toDouble())
                val isSpeechActive = timeline.isAudioActiveAt(currentSeconds)
                val targetGain = if (isSpeechActive) duckedGain else fullGain

                val alpha = if (targetGain < currentGain) attackAlpha else releaseAlpha
                currentGain += alpha * (targetGain - currentGain)

                val gain = currentGain
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
                val currentSeconds = outputStartOffsetSeconds + ((processedFrames + i).toDouble() / sampleRate.toDouble())
                val isSpeechActive = timeline.isAudioActiveAt(currentSeconds)
                val targetGain = if (isSpeechActive) duckedGain else fullGain

                val alpha = if (targetGain < currentGain) attackAlpha else releaseAlpha
                currentGain += alpha * (targetGain - currentGain)

                val gain = currentGain
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
 * Maps output presentation timestamp (in seconds) to whether the original audio is audible/active.
 */
internal class AudioDuckingTimeline(
    private val intervals: List<AudioActiveInterval>,
) {
    data class AudioActiveInterval(val startSeconds: Double, val endSeconds: Double)

    fun isAudioActiveAt(seconds: Double): Boolean {
        // Binary search or linear scan over disjoint sorted intervals
        for (interval in intervals) {
            if (seconds in interval.startSeconds..interval.endSeconds) {
                return true
            }
            if (interval.startSeconds > seconds) break
        }
        return false
    }

    companion object {
        fun buildWithEnvelopes(
            scenes: List<TrailerScene>,
            envelopes: Map<Int, BooleanArray>,
            binSeconds: Double = 0.1,
        ): AudioDuckingTimeline {
            val intervals = mutableListOf<AudioActiveInterval>()
            var timelineOffset = 0.0

            for (scene in scenes) {
                val sceneStart = timelineOffset
                val sceneEnd = timelineOffset + scene.length

                if (!scene.source.isImage) {
                    val env = envelopes[scene.source.id]
                    if (env != null && env.isNotEmpty() && scene.source.duration > 0.0) {
                        val bucketDuration = (scene.source.duration / env.size.toDouble()).coerceAtLeast(0.001)
                        var activeStart: Double? = null

                        val startBucket = (scene.start / bucketDuration).toInt().coerceIn(env.indices)
                        val endBucket = ((scene.start + scene.length) / bucketDuration).toInt().coerceIn(env.indices)

                        for (b in startBucket..endBucket) {
                            val isActive = env[b]
                            val bTime = (b * bucketDuration - scene.start).coerceIn(0.0, scene.length)

                            if (isActive) {
                                if (activeStart == null) activeStart = sceneStart + bTime
                            } else {
                                if (activeStart != null) {
                                    intervals.add(AudioActiveInterval(activeStart, sceneStart + bTime))
                                    activeStart = null
                                }
                            }
                        }
                        if (activeStart != null) {
                            intervals.add(AudioActiveInterval(activeStart, sceneEnd))
                        }
                    } else if (env != null && env.isEmpty()) {
                        // Decoder failure: conservative fallback treats entire video scene as audible
                        intervals.add(AudioActiveInterval(sceneStart, sceneEnd))
                    } else if (env == null && scene.source.duration > 0.0) {
                        // Fallback when not sampled: conservative whole video scene audible
                        intervals.add(AudioActiveInterval(sceneStart, sceneEnd))
                    }
                }
                timelineOffset += scene.length
            }

            return mergeIntervals(intervals)
        }

        private fun mergeIntervals(intervals: List<AudioActiveInterval>): AudioDuckingTimeline {
            val merged = mutableListOf<AudioActiveInterval>()
            for (interval in intervals.sortedBy { it.startSeconds }) {
                if (merged.isEmpty()) {
                    merged.add(interval)
                } else {
                    val last = merged.last()
                    if (interval.startSeconds <= last.endSeconds + 0.1) {
                        merged[merged.lastIndex] = AudioActiveInterval(last.startSeconds, max(last.endSeconds, interval.endSeconds))
                    } else {
                        merged.add(interval)
                    }
                }
            }
            return AudioDuckingTimeline(merged)
        }
    }
}
