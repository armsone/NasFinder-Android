package com.armsone.nasfinder.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix as AndroidMatrix
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.*
import com.google.common.collect.ImmutableList
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Constant speed provider for Media3 EditedMediaItem.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class ConstantSpeedProvider(private val speed: Float) : SpeedProvider {
    override fun getSpeed(timeUs: Long): Float = speed
    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = androidx.media3.common.C.TIME_UNSET
}

/**
 * Public Media3 RgbMatrix implementation for RGB saturation.
 * Standard Rec.709 luminance weights: R=0.2126, G=0.7152, B=0.0722.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class SaturationRgbMatrix(private val saturation: Float) : RgbMatrix {
    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
        val s = saturation.coerceIn(0f, 3f)
        val rW = 0.2126f * (1f - s)
        val gW = 0.7152f * (1f - s)
        val bW = 0.0722f * (1f - s)
        // Column-major 4x4 matrix for OpenGL shader
        return floatArrayOf(
            rW + s, rW,     rW,     0f,
            gW,     gW + s, gW,     0f,
            bW,     bW,     bW + s, 0f,
            0f,     0f,     0f,     1f
        )
    }
}

/**
 * Keyframe matrix transformation evaluating interpolated position and scale in source time.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class KeyframeMatrixTransformation(
    private val keyframes: List<TrailerTransformKeyframe>,
    private val sourceStartSeconds: Double,
    private val rate: Double,
) : MatrixTransformation {
    override fun getMatrix(presentationTimeUs: Long): AndroidMatrix {
        val matrix = AndroidMatrix()
        if (keyframes.isEmpty()) return matrix
        val sourceTime = sourceStartSeconds + (presentationTimeUs / 1_000_000.0) * rate
        val transform = TrailerTransformKeyframe.interpolate(keyframes, sourceTime)
        matrix.postScale(transform.scale.toFloat(), transform.scale.toFloat())
        matrix.postTranslate(transform.offsetX.toFloat(), -transform.offsetY.toFloat())
        return matrix
    }
}

/**
 * Keyframe opacity for a lone clip: Media3 only allows VideoCompositorSettings on a multi-input graph, and a single clip is a
 * single-input graph. Over the black canvas the compositor would use, alpha a is exactly an RGB scale by a.
 */
internal class KeyframeOpacityRgbMatrix(
    private val keyframes: List<TrailerTransformKeyframe>,
    private val sourceStartSeconds: Double,
    private val rate: Double,
) : RgbMatrix {
    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
        val sourceTime = sourceStartSeconds + (presentationTimeUs / 1_000_000.0) * rate
        val a = TrailerTransformKeyframe.interpolate(keyframes, sourceTime).opacity.toFloat().coerceIn(0f, 1f)
        return floatArrayOf(a, 0f, 0f, 0f, 0f, a, 0f, 0f, 0f, 0f, a, 0f, 0f, 0f, 0f, 1f)
    }
}

/**
 * One timed caption cue as its own overlay: its bitmap is rendered lazily on first use inside the cue's source window and
 * released after it, so simultaneous cues (each with its own position) all show and memory stays bounded to the active cues.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class TimedCueBitmapOverlay(
    private val cue: TrailerTimedCaptionCue,
    private val sourceStartSeconds: Double,
    private val rate: Double,
    private val emptyBitmap: Bitmap,
    private val render: () -> Bitmap?,
) : BitmapOverlay() {
    private var bitmap: Bitmap? = null

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val sourceTime = sourceStartSeconds + (presentationTimeUs / 1_000_000.0) * rate
        if (sourceTime < cue.sourceStart || sourceTime > cue.sourceEnd) {
            bitmap = null
            return emptyBitmap
        }
        return bitmap ?: (render() ?: emptyBitmap).also { bitmap = it }
    }
}

/**
 * A clip's static caption as an overlay whose full-canvas bitmap is rendered on first use (the clip's first frame) and dropped when the
 * overlay is released or the clip's frames are past, so a long project never holds one full bitmap per clip.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class LazyCaptionBitmapOverlay(
    private val clipDurationUs: Long,
    private val emptyBitmap: Bitmap,
    private val render: () -> Bitmap?,
) : BitmapOverlay() {
    private var bitmap: Bitmap? = null

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        if (presentationTimeUs > clipDurationUs + LATE_SLACK_US) {
            bitmap = null
            return emptyBitmap
        }
        return bitmap ?: (render() ?: emptyBitmap).also { bitmap = it }
    }

    override fun release() {
        bitmap = null
        super.release()
    }

    private companion object {
        const val LATE_SLACK_US = 500_000L
    }
}

/**
 * Equal-gain fade of one scene's ORIGINAL audio over its dissolve overlaps: 0→1 across the first [fadeInUs] and 1→0 across the last
 * [fadeOutUs] of the clip's output duration [clipDurationUs], so the overlapping outgoing/incoming scene sounds do not add up.
 * It runs after Media3's speed processing, so its frames are already in output time (never divided by the rate again). The
 * clip-local clock starts at the flush's positionOffsetUs and counts output frames, so a seek resumes mid-ramp instead of replaying it.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class SceneTransitionAudioProcessor(
    private val clipDurationUs: Long,
    private val fadeInUs: Long,
    private val fadeOutUs: Long,
) : androidx.media3.common.audio.BaseAudioProcessor() {
    private var offsetUs = 0L
    private var framesSinceFlush = 0L

    override fun onConfigure(inputAudioFormat: androidx.media3.common.audio.AudioProcessor.AudioFormat): androidx.media3.common.audio.AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != androidx.media3.common.C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != androidx.media3.common.C.ENCODING_PCM_FLOAT) {
            throw androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    // The buffer last handed to the consumer. getOutput() returns the processor's own storage by reference, so while the consumer has not
    // read it all, replaceOutputBuffer() must not reuse (clear/re-limit) that same storage.
    private var handedToConsumer: java.nio.ByteBuffer? = null

    override fun getOutput(): java.nio.ByteBuffer {
        val delivered = super.getOutput()
        if (delivered.hasRemaining()) handedToConsumer = delivered
        return delivered
    }

    override fun onFlush(streamMetadata: androidx.media3.common.audio.AudioProcessor.StreamMetadata) {
        offsetUs = streamMetadata.positionOffsetUs
        framesSinceFlush = 0L
        handedToConsumer = null
    }

    override fun onReset() {
        handedToConsumer = null
        offsetUs = 0L
        framesSinceFlush = 0L
    }

    /** Like replaceOutputBuffer, but never reuses storage the consumer still holds unread: then a fresh, larger buffer is allocated. */
    private fun replaceOutputBufferKeepingHeld(count: Int): java.nio.ByteBuffer {
        val held = handedToConsumer
        if (held != null && !held.hasRemaining()) handedToConsumer = null
        val heldCapacity = if (held != null && held.hasRemaining()) held.capacity() else 0
        return replaceOutputBuffer(maxOf(count, heldCapacity + 1))
    }

    private fun gainAt(clipTimeUs: Double): Float {
        var gain = 1.0
        if (fadeInUs > 0L) gain *= (clipTimeUs / fadeInUs).coerceIn(0.0, 1.0)
        if (fadeOutUs > 0L) gain *= ((clipDurationUs - clipTimeUs) / fadeOutUs).coerceIn(0.0, 1.0)
        return gain.toFloat()
    }

    override fun queueInput(inputBuffer: java.nio.ByteBuffer) {
        val frameBytes = inputAudioFormat.bytesPerFrame
        val frames = inputBuffer.remaining() / frameBytes
        if (frames == 0) return
        val channels = inputAudioFormat.channelCount
        val sampleRate = inputAudioFormat.sampleRate.toDouble()
        // Never reuse storage the consumer still holds unread; end of stream queues nothing here.
        val output = replaceOutputBufferKeepingHeld(frames * frameBytes)
        val input = inputBuffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
        output.order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val is16 = inputAudioFormat.encoding == androidx.media3.common.C.ENCODING_PCM_16BIT
        repeat(frames) {
            val gain = gainAt(offsetUs + framesSinceFlush * 1_000_000.0 / sampleRate)
            repeat(channels) {
                if (is16) {
                    output.putShort((input.short * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
                } else {
                    output.putFloat((input.float * gain).coerceIn(-1f, 1f))
                }
            }
            framesSinceFlush++
        }
        output.flip()
    }
}

/**
 * Dissolve window specification for 2-track overlapping compositor.
 */
internal data class DissolveWindow(
    val incomingInputId: Int,
    val startUs: Long,
    val endUs: Long,
)

/**
 * Active clip window on a video compositor input track, retaining original rate/sourceStart/keyframes
 * for opacity evaluation while the prepared clip media itself plays at baked rate 1.0.
 */
internal data class CompositorClipWindow(
    val inputId: Int,
    val startUs: Long,
    val endUs: Long,
    val outputStartSeconds: Double,
    val sourceStartSeconds: Double,
    val rate: Double,
    val keyframes: List<TrailerTransformKeyframe> = emptyList(),
)

/**
 * VideoCompositorSettings implementing real dissolve alpha crossfade between adjacent overlapping sequences,
 * hiding inactive gap tracks (alpha 0), and combining user keyframe opacity in source time.
 *
 * Media3 DefaultVideoCompositor composites primary input 0 on top of base input 1:
 * - When incoming is track 0 (top), track 0 alpha = t and base track 1 alpha = 1.0.
 * - When incoming is track 1 (base), outgoing top track 0 alpha = 1.0 - t and base track 1 alpha = 1.0.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class DissolveVideoCompositorSettings(
    private val exportWidth: Int,
    private val exportHeight: Int,
    private val dissolveWindows: List<DissolveWindow>,
    private val clipWindows: List<CompositorClipWindow> = emptyList(),
) : VideoCompositorSettings {
    override fun getOutputSize(inputSizes: List<Size>): Size = Size(exportWidth, exportHeight)

    override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
        val activeClip = if (clipWindows.isNotEmpty()) {
            clipWindows.firstOrNull {
                it.inputId == inputId && presentationTimeUs >= it.startUs && presentationTimeUs < it.endUs
            } ?: return StaticOverlaySettings.Builder().setAlphaScale(0f).build()
        } else {
            null
        }

        var alpha = 1.0f
        val window = dissolveWindows.firstOrNull { presentationTimeUs >= it.startUs && presentationTimeUs < it.endUs }
        if (window != null) {
            val durationUs = (window.endUs - window.startUs).coerceAtLeast(1L)
            val t = ((presentationTimeUs - window.startUs).toFloat() / durationUs).coerceIn(0f, 1f)
            alpha = when {
                window.incomingInputId == 0 && inputId == 0 -> t
                window.incomingInputId == 0 && inputId == 1 -> 1.0f
                window.incomingInputId == 1 && inputId == 0 -> (1.0f - t).coerceIn(0f, 1f)
                window.incomingInputId == 1 && inputId == 1 -> 1.0f
                else -> 1.0f
            }
        }

        if (activeClip != null && activeClip.keyframes.isNotEmpty()) {
            val globalOutputSeconds = presentationTimeUs / 1_000_000.0
            val sourceTime = activeClip.sourceStartSeconds +
                (globalOutputSeconds - activeClip.outputStartSeconds) * activeClip.rate
            val keyframeOpacity = TrailerTransformKeyframe.interpolate(activeClip.keyframes, sourceTime)
                .opacity
                .toFloat()
                .coerceIn(0f, 1f)
            alpha *= keyframeOpacity
        }

        return StaticOverlaySettings.Builder()
            .setAlphaScale(alpha.coerceIn(0f, 1f))
            .build()
    }
}

/**
 * Advanced Composer helper connecting scene effects, speed, color, keyframes, cues, transitions, and voiceovers.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal object TrailerAdvancedComposer {

    val EMPTY_CUE_BITMAP: Bitmap by lazy {
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply {
            setPixel(0, 0, 0)
        }
    }

    /**
     * Builds the complete list of video/image effects for a scene, including framing, color, keyframes, static caption, and timed cues.
     */
    fun buildSceneEffects(
        context: Context,
        scene: TrailerScene,
        exportWidth: Int,
        exportHeight: Int,
        captionBitmaps: MutableMap<SceneCaption, Bitmap>,
        emptyBitmap: Bitmap = EMPTY_CUE_BITMAP,
    ): List<Effect> {
        val effects = mutableListOf<Effect>()
        val rate = scene.adjustments?.rate?.takeIf { it.isFinite() && it in 0.25..4.0 } ?: 1.0

        // 1. Color adjustments (brightness, contrast, saturation)
        val color = scene.adjustments?.color
        if (color != null && !color.isNeutral) {
            if (abs(color.brightness) >= 0.005) {
                effects.add(Brightness(color.brightness.toFloat().coerceIn(-1f, 1f)))
            }
            if (abs(color.contrast - 1.0) >= 0.005) {
                // Media3 Contrast(-1f..1f); 0 is neutral, positive increases contrast
                val c = (color.contrast - 1.0).toFloat().coerceIn(-1f, 1f)
                effects.add(Contrast(c))
            }
            if (abs(color.saturation - 1.0) >= 0.005) {
                effects.add(SaturationRgbMatrix(color.saturation.toFloat()))
            }
        }

        // 2. Keyframes transform (scale, position in source time)
        val keyframes = scene.adjustments?.keyframes.orEmpty()
        if (keyframes.isNotEmpty()) {
            effects.add(KeyframeMatrixTransformation(keyframes, scene.start, rate))
        }

        // 3. Static caption overlay (independent existing user styles preserved)
        val staticCaption = scene.caption?.normalizedOrNull()
        if (staticCaption != null) {
            val outputDurationUs = Math.round(scene.length / rate * 1_000_000.0)
            val overlay = LazyCaptionBitmapOverlay(outputDurationUs, emptyBitmap) { SceneCaptionRenderer.render(context, staticCaption, exportWidth, exportHeight) }
            effects.add(OverlayEffect(ImmutableList.of<androidx.media3.effect.TextureOverlay>(overlay)))
        }

        // 4. Timed caption cues (source-range mapped to output): every cue is its own overlay, text from the cue itself.
        val cues = scene.adjustments?.cues.orEmpty()
        val overlays = cues.mapNotNull { cue ->
            val norm = cue.style.copy(text = cue.text).normalizedOrNull() ?: return@mapNotNull null
            TimedCueBitmapOverlay(cue, scene.start, rate, emptyBitmap) { SceneCaptionRenderer.render(context, norm, exportWidth, exportHeight) }
        }
        if (overlays.isNotEmpty()) {
            effects.add(OverlayEffect(ImmutableList.copyOf<androidx.media3.effect.TextureOverlay>(overlays)))
        }

        return effects
    }

    /**
     * Preflights all voiceover takes.
     * Ensures all files exist and have valid readable duration ranges.
     * Throws explicit error on invalid/unreadable/partial manual take, preserving project/media.
     */
    fun preflightVoiceovers(
        voiceovers: List<TrailerVoiceoverTake>,
        projectDir: File?,
    ): List<Pair<TrailerVoiceoverTake, File>> {
        if (voiceovers.isEmpty()) return emptyList()
        val result = mutableListOf<Pair<TrailerVoiceoverTake, File>>()
        for (take in voiceovers) {
            if (!TrailerVoiceoverTake.isValidRelativeFilePath(take.relativeFile)) {
                throw IllegalStateException("녹음된 음성 파일 경로가 올바르지 않습니다: ${take.relativeFile}")
            }
            val file = if (projectDir != null) File(projectDir, take.relativeFile) else File(take.relativeFile)
            if (!file.isFile || file.length() <= 0L) {
                throw IllegalStateException("녹음된 음성 파일을 찾을 수 없습니다: ${file.name}")
            }
            if (!take.outputStart.isFinite() || take.outputStart < 0.0) {
                throw IllegalStateException("녹음 파일의 시작 시각이 올바르지 않습니다: ${take.outputStart}")
            }
            if (!take.sourceIn.isFinite() || !take.sourceOut.isFinite() || take.sourceIn < 0.0 || take.sourceOut <= take.sourceIn) {
                throw IllegalStateException("녹음 파일의 구간 정보가 올바르지 않습니다: [${take.sourceIn} ~ ${take.sourceOut}]")
            }
            if (!take.volume.isFinite() || take.volume < 0.0) {
                throw IllegalStateException("녹음 파일의 음량 정보가 올바르지 않습니다: ${take.volume}")
            }
            val reqEnd = take.requiredOutputEnd()
            if (reqEnd == null || !reqEnd.isFinite() || reqEnd <= take.outputStart) {
                throw IllegalStateException("녹음 파일의 배치 길이가 올바르지 않습니다: ${file.name}")
            }

            // Verify readability via MediaMetadataRetriever
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val fileDurSec = durMs / 1000.0
                if (fileDurSec <= 0.0 || fileDurSec < take.sourceIn) {
                    throw IllegalStateException("녹음 파일이 비어 있거나 손상되었습니다: ${file.name}")
                }
                // The whole authored range must exist in the file; only integer-millisecond metadata rounding (2ms) is tolerated.
                if (fileDurSec + 0.002 < take.sourceOut) {
                    throw IllegalStateException("녹음 파일이 지정한 구간보다 짧습니다: ${file.name}")
                }
            } catch (e: Exception) {
                if (e is IllegalStateException) throw e
                throw IllegalStateException("녹음 파일을 열 수 없습니다: ${file.name}", e)
            } finally {
                retriever.release()
            }

            result.add(take to file)
        }
        return result
    }

    /**
     * Prepared intermediate clip item and its original canonical input/keyframe metadata.
     * The prepared file has speed/framing/color/captions baked once (rate=1 for the temporary media),
     * while clipInput and keyframes retain the authored scene metadata for canonical timeline mapping and opacity.
     */
    internal data class PreparedClipSpec(
        val file: File,
        val clipInput: TrailerClipInput,
        val keyframes: List<TrailerTransformKeyframe> = emptyList(),
        // Preview direct-input route: the healthy original (clipping/rate/effects already set) instead of a prepared [file].
        val editedItem: androidx.media3.transformer.EditedMediaItem? = null,
        // A clip shorter than one AAC frame: its audio is this exact PCM window, not the prepared file's re-decoded AAC.
        val exactAudio: ExactClipAudio? = null,
    )

    /**
     * Exact PCM16 WAV window of a very short clip. The WAV already carries the clip's rate (speed) and source window; only [gain] is still
     * to be applied, once, in the final composition. The prepared file's own audio is dropped so the audio is encoded to AAC exactly once.
     */
    internal data class ExactClipAudio(val file: File, val durationUs: Long, val gain: Float)

    /**
     * Canonical timeline plan including visual mappings, visual end, voice floor, and effective global duration.
     */
    internal data class TimelineCompositionPlan(
        val clipInputs: List<TrailerClipInput>,
        val mappings: List<TrailerClipTimeMapping>,
        val canonicalVisualDurationSeconds: Double,
        val voiceFloorSeconds: Double,
        val effectiveTotalDurationSeconds: Double,
    )

    /**
     * Built overlapping Media3 Composition shared between export and active preview CompositionPlayer.
     */
    internal data class BuiltTimelineComposition(
        val composition: androidx.media3.transformer.Composition,
        val videoSequences: List<androidx.media3.transformer.EditedMediaItemSequence>,
        val voiceSequences: List<androidx.media3.transformer.EditedMediaItemSequence>,
        val musicSequence: androidx.media3.transformer.EditedMediaItemSequence?,
        val compositorSettings: DissolveVideoCompositorSettings,
        val mappings: List<TrailerClipTimeMapping>,
        val clipWindows: List<CompositorClipWindow>,
        val dissolveWindows: List<DissolveWindow>,
        val canonicalVisualDurationSeconds: Double,
        val voiceFloorSeconds: Double,
        val effectiveTotalDurationSeconds: Double,
        val ownedTempFiles: List<File> = emptyList(),
        val recoveredSceneCount: Int = 0,
        val excludedSceneCount: Int = 0,
        // Shared preview-cache clips this composition plays: never deleted here, only un-leased so the cache may trim them later.
        val leasedCacheFiles: List<File> = emptyList(),
    ) {
        private val released = java.util.concurrent.atomic.AtomicBoolean(false)

        fun releaseOwnedTempFiles() {
            if (released.compareAndSet(false, true)) PreviewClipCache.release(leasedCacheFiles)
            ownedTempFiles.forEach { file ->
                runCatching {
                    if (file.exists()) {
                        file.delete()
                    }
                }
            }
        }
    }

    /**
     * Builds canonical TrailerClipInput list including optional synthetic intro/outro cards (rate=1.0).
     * When outro is enabled, the last body scene's requestedOutgoingOverlap sees the outro card as the next clip input.
     */
    fun buildClipInputs(
        scenes: List<TrailerScene>,
        introDurationSeconds: Double = 0.0,
        outroDurationSeconds: Double = 0.0,
    ): List<TrailerClipInput> {
        val inputs = mutableListOf<TrailerClipInput>()
        if (introDurationSeconds > 0.0) {
            inputs.add(
                TrailerClipInput(
                    sceneKey = -999L,
                    sourceId = -999,
                    sourceStart = 0.0,
                    sourceLength = introDurationSeconds,
                    rate = 1.0,
                    requestedOutgoingOverlap = 0.0,
                )
            )
        }
        for ((index, scene) in scenes.withIndex()) {
            val rate = scene.adjustments?.rate?.takeIf { it.isFinite() && it in 0.25..4.0 } ?: 1.0
            val dissolve = (scene.adjustments?.transition?.dissolveDuration ?: 0.0)
                .takeIf { it.isFinite() && it > 0.0 } ?: 0.0
            inputs.add(
                TrailerClipInput(
                    sceneKey = scene.sceneKey ?: (index + 1).toLong(),
                    sourceId = scene.source.id,
                    sourceStart = scene.start,
                    sourceLength = scene.length,
                    rate = rate,
                    requestedOutgoingOverlap = dissolve,
                )
            )
        }
        if (outroDurationSeconds > 0.0) {
            inputs.add(
                TrailerClipInput(
                    sceneKey = -998L,
                    sourceId = -998,
                    sourceStart = 0.0,
                    sourceLength = outroDurationSeconds,
                    rate = 1.0,
                    requestedOutgoingOverlap = 0.0,
                )
            )
        }
        return inputs
    }

    /**
     * Computes the canonical timeline mappings and effective total duration (max of canonical visual end and voice floor).
     */
    fun planTimeline(
        clipInputs: List<TrailerClipInput>,
        voiceovers: List<TrailerVoiceoverTake> = emptyList(),
    ): TimelineCompositionPlan {
        val mappings = TrailerClipTimeMappingFactory.buildTimelineMappings(clipInputs)
        val canonicalVisualDuration = mappings.maxOfOrNull { it.outputStart + it.outputDuration } ?: 0.0
        val voiceFloor = voiceovers.mapNotNull { it.requiredOutputEnd() }.maxOrNull() ?: 0.0
        val effectiveTotalDuration = maxOf(canonicalVisualDuration, voiceFloor)
        return TimelineCompositionPlan(
            clipInputs = clipInputs,
            mappings = mappings,
            canonicalVisualDurationSeconds = canonicalVisualDuration,
            voiceFloorSeconds = voiceFloor,
            effectiveTotalDurationSeconds = effectiveTotalDuration,
        )
    }

    /**
     * Builds the overlapping 2-track video + multi-track audio Media3 Composition with DissolveVideoCompositorSettings.
     * - Places even prepared clips on video sequence 0 (primary top inputId 0) and odd clips on video sequence 1 (base inputId 1).
     * - Inserts real Builder.addGap(...) between planned clip windows on each video sequence and pads through effectiveTotalDuration (including voiceFloor).
     * - Prepares each clip item at baked rate 1.0 over its canonical outputDuration while keeping original rate/keyframes in CompositorClipWindow.
     */
    fun buildTimelineComposition(
        preparedClips: List<PreparedClipSpec>,
        exportWidth: Int,
        exportHeight: Int,
        preflightedVoiceovers: List<Pair<TrailerVoiceoverTake, File>> = emptyList(),
        musicSequence: androidx.media3.transformer.EditedMediaItemSequence? = null,
        ownedTempFiles: List<File> = emptyList(),
        recoveredSceneCount: Int = 0,
        excludedSceneCount: Int = 0,
        leasedCacheFiles: List<File> = emptyList(),
    ): BuiltTimelineComposition {
        require(preparedClips.isNotEmpty()) { "내보낼 수 있는 유효한 클립이 없습니다." }

        val plan = planTimeline(
            clipInputs = preparedClips.map { it.clipInput },
            voiceovers = preflightedVoiceovers.map { it.first },
        )
        val mappings = plan.mappings
        check(mappings.size == preparedClips.size) { "클립 타임라인 매핑 수가 일치하지 않습니다." }

        val hasSecondTrack = preparedClips.size > 1
        // Always audio+video: the second sequence starts with a Gap (Media3 requires forced tracks then), and silence fills in
        // for direct originals without audio like it does for prepared clips.
        val trackTypes = setOf(androidx.media3.common.C.TRACK_TYPE_AUDIO, androidx.media3.common.C.TRACK_TYPE_VIDEO)
        fun newTrackBuilder() = androidx.media3.transformer.EditedMediaItemSequence.Builder(trackTypes)
        val trackBuilders = arrayOf(newTrackBuilder(), newTrackBuilder())
        val trackCursorsUs = longArrayOf(0L, 0L)
        val trackItemCounts = intArrayOf(0, 0)

        val clipWindows = mutableListOf<CompositorClipWindow>()
        val dissolveWindows = mutableListOf<DissolveWindow>()
        val exactAudioSequences = mutableListOf<androidx.media3.transformer.EditedMediaItemSequence>()
        var previousClipEndUs = 0L

        for (index in preparedClips.indices) {
            val spec = preparedClips[index]
            val mapping = mappings[index]
            val inputId = if (hasSecondTrack) (index % 2) else 0

            val plannedStartUs = Math.round(mapping.outputStart * 1_000_000.0).coerceAtLeast(0L)
            val clipDurationMs = Math.round(mapping.outputDuration * 1000.0).coerceAtLeast(1L)
            val clipDurationUs = clipDurationMs * 1000L

            val gapBeforeUs = (plannedStartUs - trackCursorsUs[inputId]).coerceAtLeast(0L)
            if (gapBeforeUs > 0L) {
                trackBuilders[inputId].addGap(gapBeforeUs)
                trackCursorsUs[inputId] += gapBeforeUs
            }

            val actualStartUs = trackCursorsUs[inputId]
            val actualEndUs = actualStartUs + clipDurationUs

            // A direct original with an exact audio window keeps its framing/color/caption/rate/video; only its own audio is dropped.
            // Dissolve overlaps: only the scene's own original audio fades (in over its incoming overlap, out over the next
            // clip's incoming overlap); the already-applied sourceVolume gain is kept and voiceover/music are untouched.
            val fadeInUs = if (index > 0) Math.round(mapping.incomingOverlap * 1_000_000.0).coerceIn(0L, clipDurationUs) else 0L
            val fadeOutUs = Math.round((mappings.getOrNull(index + 1)?.incomingOverlap ?: 0.0) * 1_000_000.0).coerceIn(0L, clipDurationUs)
            val sceneFade = if (fadeInUs > 0L || fadeOutUs > 0L) {
                listOf<androidx.media3.common.audio.AudioProcessor>(SceneTransitionAudioProcessor(clipDurationUs, fadeInUs, fadeOutUs))
            } else emptyList()
            val baseClipItem = spec.editedItem?.let { if (spec.exactAudio != null) it.buildUpon().setRemoveAudio(true).build() else it } ?: run {
                val mediaItem = MediaItem.Builder()
                    .setUri(Uri.fromFile(spec.file))
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(0L)
                            .setEndPositionMs(clipDurationMs)
                            .build()
                    )
                    .build()
                androidx.media3.transformer.EditedMediaItem.Builder(mediaItem)
                    .setDurationUs(clipDurationUs)
                    .setFrameRate(30)
                    .setRemoveAudio(spec.exactAudio != null)
                    .build()
            }
            // The exact-PCM route carries its fade on its own audio item below; its video item has no audio.
            val editedClipItem = if (spec.exactAudio == null && sceneFade.isNotEmpty()) {
                baseClipItem.buildUpon()
                    .setEffects(androidx.media3.transformer.Effects(baseClipItem.effects.audioProcessors + sceneFade, baseClipItem.effects.videoEffects))
                    .build()
            } else baseClipItem
            spec.exactAudio?.let { exact ->
                val gainEffects = (if (abs(exact.gain - 1f) < 0.005f) emptyList() else listOf<androidx.media3.common.audio.AudioProcessor>(SceneGainAudioProcessor(exact.gain))) + sceneFade
                val exactItem = androidx.media3.transformer.EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(exact.file)))
                    .setRemoveVideo(true)
                    .setDurationUs(exact.durationUs)
                    .setEffects(androidx.media3.transformer.Effects(gainEffects, emptyList()))
                    .build()
                exactAudioSequences.add(
                    androidx.media3.transformer.EditedMediaItemSequence.Builder(setOf(androidx.media3.common.C.TRACK_TYPE_AUDIO))
                        .apply { if (actualStartUs > 0L) addGap(actualStartUs) }
                        .addItem(exactItem)
                        .build()
                )
            }

            val itemToAdd = if (!hasSecondTrack && spec.keyframes.isNotEmpty()) {
                val effects = editedClipItem.effects
                editedClipItem.buildUpon()
                    .setEffects(androidx.media3.transformer.Effects(effects.audioProcessors, effects.videoEffects + KeyframeOpacityRgbMatrix(spec.keyframes, mapping.sourceStart, mapping.rate)))
                    .build()
            } else editedClipItem
            trackBuilders[inputId].addItem(itemToAdd)
            trackCursorsUs[inputId] = actualEndUs
            trackItemCounts[inputId] += 1

            clipWindows.add(
                CompositorClipWindow(
                    inputId = inputId,
                    startUs = actualStartUs,
                    endUs = actualEndUs,
                    outputStartSeconds = actualStartUs / 1_000_000.0,
                    sourceStartSeconds = mapping.sourceStart,
                    rate = mapping.rate,
                    keyframes = spec.keyframes,
                )
            )

            if (index > 0 && mapping.incomingOverlap > 0.0) {
                val overlapUs = Math.round(mapping.incomingOverlap * 1_000_000.0).coerceAtLeast(1L)
                val dissolveStartUs = actualStartUs
                val dissolveEndUs = min(previousClipEndUs, dissolveStartUs + overlapUs)
                if (dissolveEndUs > dissolveStartUs) {
                    dissolveWindows.add(
                        DissolveWindow(
                            incomingInputId = inputId,
                            startUs = dissolveStartUs,
                            endUs = dissolveEndUs,
                        )
                    )
                }
            }

            previousClipEndUs = max(previousClipEndUs, actualEndUs)
        }

        val effectiveTotalDurationSeconds = maxOf(
            plan.effectiveTotalDurationSeconds,
            previousClipEndUs / 1_000_000.0,
        )
        val targetTotalDurationUs = maxOf(
            Math.round(effectiveTotalDurationSeconds * 1_000_000.0),
            trackCursorsUs[0],
            trackCursorsUs[1],
        )

        // Pad active video sequence(s) through effectiveTotalDuration (including voiceFloor or opposite-track tail)
        for (trackIdx in 0..1) {
            if (trackItemCounts[trackIdx] > 0) {
                val trailingGapUs = (targetTotalDurationUs - trackCursorsUs[trackIdx]).coerceAtLeast(0L)
                if (trailingGapUs > 0L) {
                    trackBuilders[trackIdx].addGap(trailingGapUs)
                    trackCursorsUs[trackIdx] += trailingGapUs
                }
            }
        }

        val videoSequences = buildList {
            if (trackItemCounts[0] > 0) add(trackBuilders[0].build())
            if (trackItemCounts[1] > 0) add(trackBuilders[1].build())
        }

        val voiceSequences = buildVoiceSequences(
            preflightedTakes = preflightedVoiceovers,
            totalDurationSeconds = effectiveTotalDurationSeconds,
        )

        val allSequences = mutableListOf<androidx.media3.transformer.EditedMediaItemSequence>()
        allSequences.addAll(videoSequences)
        allSequences.addAll(voiceSequences)
        allSequences.addAll(exactAudioSequences)
        if (musicSequence != null) {
            allSequences.add(musicSequence)
        }

        val compositorSettings = DissolveVideoCompositorSettings(
            exportWidth = exportWidth,
            exportHeight = exportHeight,
            dissolveWindows = dissolveWindows,
            clipWindows = clipWindows,
        )

        val composition = androidx.media3.transformer.Composition.Builder(allSequences)
            .apply { if (hasSecondTrack) setVideoCompositorSettings(compositorSettings) }
            .setHdrMode(androidx.media3.transformer.Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()

        return BuiltTimelineComposition(
            composition = composition,
            videoSequences = videoSequences,
            voiceSequences = voiceSequences,
            musicSequence = musicSequence,
            compositorSettings = compositorSettings,
            mappings = mappings,
            clipWindows = clipWindows,
            dissolveWindows = dissolveWindows,
            canonicalVisualDurationSeconds = plan.canonicalVisualDurationSeconds,
            voiceFloorSeconds = plan.voiceFloorSeconds,
            effectiveTotalDurationSeconds = effectiveTotalDurationSeconds,
            ownedTempFiles = ownedTempFiles,
            recoveredSceneCount = recoveredSceneCount,
            excludedSceneCount = excludedSceneCount,
            leasedCacheFiles = leasedCacheFiles,
        )
    }

    /**
     * Builds one EditedMediaItemSequence per voiceover take using real Builder.addGap(outputStartUs)
     * so overlapping takes mix naturally and each take starts at its exact authored outputStart.
     */
    fun buildVoiceSequences(
        preflightedTakes: List<Pair<TrailerVoiceoverTake, File>>,
        totalDurationSeconds: Double,
    ): List<androidx.media3.transformer.EditedMediaItemSequence> {
        if (preflightedTakes.isEmpty()) return emptyList()
        val sorted = preflightedTakes.sortedBy { it.first.outputStart }
        val sequences = mutableListOf<androidx.media3.transformer.EditedMediaItemSequence>()

        for ((take, file) in sorted) {
            val eff = take.effectivePlacement(totalDurationSeconds)
                ?: throw IllegalStateException("녹음 파일의 배치 구간이 올바르지 않습니다: ${file.name}")
            if (!eff.outputStart.isFinite() || eff.outputStart < 0.0 || eff.outputDuration <= 0.0 || eff.sourceOut <= eff.sourceIn) {
                throw IllegalStateException("녹음 파일의 배치 시각이 올바르지 않습니다: ${file.name}")
            }
            val startMs = Math.round(eff.sourceIn * 1000.0).coerceAtLeast(0L)
            val endMs = Math.round(eff.sourceOut * 1000.0).coerceAtLeast(startMs + 1L)
            val input = MediaItem.Builder()
                .setUri(Uri.fromFile(file))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(startMs)
                        .setEndPositionMs(endMs)
                        .build()
                )
                .build()
            val gain = eff.volume.toFloat().coerceIn(0f, 2f)
            val audioEffects = if (abs(gain - 1f) < 0.005f) emptyList() else listOf(SceneGainAudioProcessor(gain))
            val editedItem = androidx.media3.transformer.EditedMediaItem.Builder(input)
                .setRemoveVideo(true)
                .setDurationUs(endMs * 1000L) // original-media duration (>= clip end, verified by preflight), not the clipped length
                .setEffects(androidx.media3.transformer.Effects(audioEffects, emptyList()))
                .build()

            val seqBuilder = androidx.media3.transformer.EditedMediaItemSequence.Builder(
                setOf(androidx.media3.common.C.TRACK_TYPE_AUDIO)
            )
            val outputStartUs = Math.round(eff.outputStart * 1_000_000.0)
            if (outputStartUs > 0L) {
                seqBuilder.addGap(outputStartUs)
            }
            seqBuilder.addItem(editedItem)
            sequences.add(seqBuilder.build())
        }

        return sequences
    }
}
