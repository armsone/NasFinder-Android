package com.armsone.nasfinder.ui

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Target short side and H.264 video bitrate of each 용량 줄이기 choice. */
enum class CompressionPreset(val title: String, val shortSide: Int, val videoBitrate: Int) {
    P1080("1080p 고화질", 1080, 8_000_000),
    P720("720p 절약", 720, 4_000_000),
    P540("540p 최소", 540, 2_000_000),
}

/** Measured facts of a collection video (display-oriented size). */
data class VideoFileFacts(
    val width: Int,
    val height: Int,
    val durationSeconds: Double,
    val sizeBytes: Long,
    val hasAudio: Boolean,
    val bitrate: Long?,
)

enum class CompressionPhase(val title: String) {
    PREPARING("준비 중"),
    RUNNING("줄이는 중"),
    VALIDATING("결과 확인 중"),
    COMMITTING("저장 중"),
    DONE("완료"),
    NO_GAIN("원본 유지"),
    FAILED("실패"),
    CANCELLED("취소됨"),
}

data class CollectionCompressionState(
    val itemId: String,
    val operationId: String,
    val itemTitle: String,
    val preset: CompressionPreset,
    val phase: CompressionPhase,
    val progress: Float? = null,
    val message: String? = null,
    val originalBytes: Long = 0L,
    val resultBytes: Long? = null,
    // 시작할 때 고정한 출처. true이면 일반 모드 화면에는 제목을 내놓지 않는다(작업은 계속된다).
    val originPrivate: Boolean = false,
) {
    val running: Boolean get() = phase == CompressionPhase.PREPARING || phase == CompressionPhase.RUNNING ||
        phase == CompressionPhase.VALIDATING || phase == CompressionPhase.COMMITTING
}

internal fun readVideoFacts(file: File): VideoFileFacts? {
    if (!file.isFile || file.length() <= 0L) return null
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        if (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") return null
        var width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        var height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        if (rotation % 180 != 0) { val swap = width; width = height; height = swap }
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull()?.div(1000.0) ?: 0.0
        VideoFileFacts(
            width = width,
            height = height,
            durationSeconds = duration,
            sizeBytes = file.length(),
            hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes",
            bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull(),
        )
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

/** Output size keeping the source aspect; never upscales a source already at or below the preset. */
internal fun compressionTargetSize(facts: VideoFileFacts, preset: CompressionPreset): Pair<Int, Int> {
    val shortSide = min(facts.width, facts.height).coerceAtLeast(2)
    val scale = min(1.0, preset.shortSide.toDouble() / shortSide)
    fun even(value: Double) = max(2, (value.roundToInt() / 2) * 2)
    return even(facts.width * scale) to even(facts.height * scale)
}

/** Rough size estimate (shown as an estimate only): target bitrate scaled by pixels, never above the source bitrate. */
internal fun estimateCompressedBytes(facts: VideoFileFacts, preset: CompressionPreset): Long {
    val (w, h) = compressionTargetSize(facts, preset)
    val presetPixels = preset.shortSide.toDouble() * preset.shortSide * 16.0 / 9.0
    val videoBitrate = preset.videoBitrate * min(1.0, (w.toDouble() * h) / presetPixels)
    val sourceBitrate = facts.bitrate?.toDouble() ?: Double.MAX_VALUE
    val total = min(videoBitrate + if (facts.hasAudio) 128_000.0 else 0.0, sourceBitrate)
    return (total * facts.durationSeconds / 8.0).toLong()
}

/** Checks the compressed output is a playable smaller-resolution copy of the same video; returns a reason when not. */
internal fun validateCompressedOutput(output: File, source: VideoFileFacts, expectedWidth: Int, expectedHeight: Int): String? {
    val facts = readVideoFacts(output) ?: return "압축 결과에서 영상을 읽을 수 없습니다."
    if (facts.sizeBytes <= 0L) return "압축 결과가 비어 있습니다."
    val tolerance = max(0.5, source.durationSeconds * 0.02)
    if (abs(facts.durationSeconds - source.durationSeconds) > tolerance) return "압축 결과의 길이가 원본과 다릅니다."
    if (abs(facts.width - expectedWidth) > 2 || abs(facts.height - expectedHeight) > 2) return "압축 결과의 해상도·방향이 예상과 다릅니다."
    if (source.hasAudio && !facts.hasAudio) return "압축 결과에 소리가 빠졌습니다."
    return null
}

@androidx.annotation.OptIn(UnstableApi::class)
internal object CollectionCompressor {
    /** Re-encodes [input] into [output] (H.264/AAC) at [width]×[height]; cancelling the coroutine cancels Transformer. */
    suspend fun compress(
        context: Context,
        input: File,
        output: File,
        width: Int,
        height: Int,
        videoBitrate: Int,
        progress: (Float?) -> Unit,
    ) = withContext(Dispatchers.Main.immediate) {
        val presentation = Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT)
        val edited = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input)))
            .setEffects(Effects(emptyList(), listOf(presentation)))
            .build()
        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(videoBitrate).build())
            .setEnableFallback(true)
            .build()
        val scope = CoroutineScope(currentCoroutineContext())
        var poll: Job? = null
        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(encoderFactory)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            poll?.cancel()
                            if (continuation.isActive) continuation.resume(Unit)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            poll?.cancel()
                            if (continuation.isActive) continuation.resumeWithException(exportException)
                        }
                    }).build()
                continuation.invokeOnCancellation {
                    poll?.cancel()
                    if (Looper.myLooper() == Looper.getMainLooper()) transformer.cancel()
                    else Handler(Looper.getMainLooper()).post { transformer.cancel() }
                }
                transformer.start(edited, output.absolutePath)
                poll = scope.launch {
                    val holder = ProgressHolder()
                    while (isActive) {
                        if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) progress(holder.progress / 100f)
                        delay(300)
                    }
                }
            }
        } finally {
            poll?.cancel()
        }
    }
}
