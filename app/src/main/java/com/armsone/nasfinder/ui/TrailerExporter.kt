package com.armsone.nasfinder.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix as AndroidMatrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.exifinterface.media.ExifInterface
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.*
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

import android.opengl.Matrix
import androidx.media3.effect.GlMatrixTransformation
import java.util.Random

// Ken Burns transformation for image clips: random zoom 1.0->1.1 or 1.1->1.0 with a fixed focal point sampled once per clip.
@androidx.annotation.OptIn(UnstableApi::class)
private class ImageKenBurnsTransformation(
    private val zoomIn: Boolean,
    private val focalX: Float,
    private val focalY: Float,
    private val durationUs: Long,
) : GlMatrixTransformation {
    private var baseTimeUs: Long = -1L

    override fun getGlMatrixArray(presentationTimeUs: Long): FloatArray {
        if (baseTimeUs < 0L) {
            baseTimeUs = presentationTimeUs
        }
        val elapsedUs = (presentationTimeUs - baseTimeUs).coerceAtLeast(0L)
        val progress = if (durationUs > 0) (elapsedUs.toFloat() / durationUs).coerceIn(0f, 1f) else 0f
        val currentZoom = if (zoomIn) 1.0f + 0.10f * progress else 1.10f - 0.10f * progress
        val maxShiftX = (currentZoom - 1f) / currentZoom
        val maxShiftY = (currentZoom - 1f) / currentZoom
        val translateX = focalX * maxShiftX
        val translateY = focalY * maxShiftY

        val glMatrix = FloatArray(16)
        Matrix.setIdentityM(glMatrix, 0)
        Matrix.translateM(glMatrix, 0, translateX, translateY, 0f)
        Matrix.scaleM(glMatrix, 0, currentZoom, currentZoom, 1f)
        return glMatrix
    }
}

/** Constant per-scene original-audio gain (0 mutes); applied only to that scene's own clip audio. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class SceneGainAudioProcessor(private val gain: Float) : androidx.media3.common.audio.BaseAudioProcessor() {
    override fun onConfigure(inputAudioFormat: androidx.media3.common.audio.AudioProcessor.AudioFormat): androidx.media3.common.audio.AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != androidx.media3.common.C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != androidx.media3.common.C.ENCODING_PCM_FLOAT) {
            throw androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: java.nio.ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val output = replaceOutputBuffer(remaining)
        val input = inputBuffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
        output.order(java.nio.ByteOrder.LITTLE_ENDIAN)
        if (inputAudioFormat.encoding == androidx.media3.common.C.ENCODING_PCM_16BIT) {
            while (input.remaining() >= 2) {
                val scaled = (input.short * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                output.putShort(scaled.toShort())
            }
        } else {
            while (input.remaining() >= 4) output.putFloat((input.float * gain).coerceIn(-1f, 1f))
        }
        output.flip()
    }
}

/**
 * Per-scene framing effects shared by export and the editor's playback preview (ExoPlayer.setVideoEffects), so both
 * render the same fit/focus in the same output size. FIT letterboxes; FILL crops to cover. A non-centered FILL focus
 * first scales the clip to its cover size, crops the output-shaped window at the focus, then normalizes to the exact
 * output size (all supported Media3 effects).
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun sceneFramingVideoEffects(
    fit: SceneFit,
    focusX: Float,
    focusY: Float,
    sourceWidth: Int,
    sourceHeight: Int,
    outputWidth: Int,
    outputHeight: Int,
): List<androidx.media3.common.Effect> {
    val fitPresentation = Presentation.createForWidthAndHeight(outputWidth, outputHeight, Presentation.LAYOUT_SCALE_TO_FIT)
    if (fit == SceneFit.FIT) return listOf(fitPresentation)
    if ((focusX == 0f && focusY == 0f) || sourceWidth <= 0 || sourceHeight <= 0) {
        return listOf(Presentation.createForWidthAndHeight(outputWidth, outputHeight, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
    }
    val sourceAspect = sourceWidth.toDouble() / sourceHeight
    val outputAspect = outputWidth.toDouble() / outputHeight
    val (coverWidth, coverHeight) = if (sourceAspect > outputAspect) {
        (outputHeight * sourceAspect) to outputHeight.toDouble()
    } else {
        outputWidth.toDouble() to (outputWidth / sourceAspect)
    }
    val evenCoverWidth = (((coverWidth / 2.0).toInt()) * 2).coerceAtLeast(outputWidth)
    val evenCoverHeight = (((coverHeight / 2.0).toInt()) * 2).coerceAtLeast(outputHeight)
    val halfWidth = (outputWidth.toFloat() / evenCoverWidth).coerceIn(0.01f, 1f)
    val halfHeight = (outputHeight.toFloat() / evenCoverHeight).coerceIn(0.01f, 1f)
    val centerX = focusX.coerceIn(-1f, 1f) * (1f - halfWidth)
    // focusY -1 = top of the picture; NDC +1 is the top edge.
    val centerY = -focusY.coerceIn(-1f, 1f) * (1f - halfHeight)
    return listOf(
        Presentation.createForWidthAndHeight(evenCoverWidth, evenCoverHeight, Presentation.LAYOUT_SCALE_TO_FIT),
        androidx.media3.effect.Crop(centerX - halfWidth, centerX + halfWidth, centerY - halfHeight, centerY + halfHeight),
        fitPresentation,
    )
}

// HanClip's composition, crop presentation and cancellation/export validation path.
@androidx.annotation.OptIn(UnstableApi::class)
internal object TrailerExporter {
    private const val MAX_GROUP_SIZE = 8

    private class ImageDecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private data class GroupExportResult(
        val files: List<File>,
        val effectiveScenes: List<TrailerScene>,
        val recoveredCount: Int = 0,
        val excludedCount: Int = 0,
    )

    private fun isMediaDecodingFailure(e: Throwable): Boolean {
        if (e is CancellationException) return false
        if (e is java.io.FileNotFoundException || e is SecurityException) return false

        val msg = e.message?.lowercase() ?: ""
        if (msg.contains("enospc") || msg.contains("no space") || msg.contains("permission denied") || msg.contains("read-only")) {
            return false
        }

        var current: Throwable? = e
        var isDecoding = false
        var isEncoderOrMuxerOrIo = false

        while (current != null) {
            if (current is CancellationException) return false
            if (current is java.io.FileNotFoundException || current is SecurityException) return false

            val curMsg = current.message?.lowercase() ?: ""
            if (curMsg.contains("enospc") || curMsg.contains("no space") || curMsg.contains("permission denied")) {
                return false
            }

            if (current is ImageDecodeException) {
                isDecoding = true
            }

            if (current is ExportException) {
                when (current.errorCode) {
                    ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
                    ExportException.ERROR_CODE_ENCODING_FAILED,
                    ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED,
                    ExportException.ERROR_CODE_MUXING_FAILED,
                    ExportException.ERROR_CODE_MUXING_TIMEOUT -> {
                        isEncoderOrMuxerOrIo = true
                    }
                    ExportException.ERROR_CODE_IO_FILE_NOT_FOUND,
                    ExportException.ERROR_CODE_IO_NO_PERMISSION,
                    ExportException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    ExportException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> {
                        return false
                    }
                    ExportException.ERROR_CODE_DECODER_INIT_FAILED,
                    ExportException.ERROR_CODE_DECODING_FAILED,
                    ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> {
                        isDecoding = true
                    }
                }
            }

            if (current is android.media.MediaCodec.CodecException) {
                if (!isEncoderOrMuxerOrIo) {
                    isDecoding = true
                }
            }

            current = current.cause
        }

        if (isEncoderOrMuxerOrIo) return false
        return isDecoding || msg.contains("decode") || msg.contains("extractor") || msg.contains("demux")
    }

    private fun renderBitmapToJpeg(
        sourceBitmap: Bitmap,
        targetW: Int,
        targetH: Int,
        output: File,
        fit: SceneFit = SceneFit.FILL,
        focusX: Float = 0f,
        focusY: Float = 0f,
    ) {
        val resultBitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(resultBitmap)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

            val srcW = sourceBitmap.width.toFloat()
            val srcH = sourceBitmap.height.toFloat()
            if (fit == SceneFit.FIT) {
                canvas.drawColor(android.graphics.Color.BLACK)
                val scale = minOf(targetW / srcW, targetH / srcH)
                val drawW = srcW * scale
                val drawH = srcH * scale
                val left = (targetW - drawW) / 2f
                val top = (targetH - drawH) / 2f
                canvas.drawBitmap(sourceBitmap, null, RectF(left, top, left + drawW, top + drawH), paint)
            } else {
                val scale = maxOf(targetW / srcW, targetH / srcH)
                val cropW = targetW / scale
                val cropH = targetH / scale
                val cropX = ((srcW - cropW) / 2f * (1f + focusX.coerceIn(-1f, 1f))).coerceIn(0f, (srcW - cropW).coerceAtLeast(0f))
                val cropY = ((srcH - cropH) / 2f * (1f + focusY.coerceIn(-1f, 1f))).coerceIn(0f, (srcH - cropH).coerceAtLeast(0f))

                val srcRect = Rect(
                    cropX.toInt().coerceIn(0, sourceBitmap.width),
                    cropY.toInt().coerceIn(0, sourceBitmap.height),
                    (cropX + cropW).toInt().coerceIn(0, sourceBitmap.width),
                    (cropY + cropH).toInt().coerceIn(0, sourceBitmap.height),
                )
                val dstRect = Rect(0, 0, targetW, targetH)
                canvas.drawBitmap(sourceBitmap, srcRect, dstRect, paint)
            }

            FileOutputStream(output).use { fos ->
                resultBitmap.compress(Bitmap.CompressFormat.JPEG, 92, fos)
            }
        } finally {
            resultBitmap.recycle()
        }
    }

    private fun preprocessImage(
        source: File,
        targetW: Int,
        targetH: Int,
        output: File,
        fit: SceneFit = SceneFit.FILL,
        focusX: Float = 0f,
        focusY: Float = 0f,
    ) {
        if (!source.exists() || !source.isFile) {
            throw java.io.FileNotFoundException("Source image not found: ${source.absolutePath}")
        }
        if (!source.canRead()) {
            throw SecurityException("Source image not readable: ${source.absolutePath}")
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw ImageDecodeException("이미지를 읽을 수 없습니다: ${source.name}")
        }

        val exif = try {
            ExifInterface(source.absolutePath)
        } catch (e: Exception) {
            if (e is CancellationException || e is SecurityException || e is java.io.IOException) throw e
            throw ImageDecodeException("이미지 메타데이터를 읽을 수 없습니다: ${source.name}", e)
        }
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val isRotated90 = orientation == ExifInterface.ORIENTATION_ROTATE_90 || orientation == ExifInterface.ORIENTATION_ROTATE_270

        val (rawW, rawH) = if (isRotated90) bounds.outHeight to bounds.outWidth else bounds.outWidth to bounds.outHeight
        val sample = maxOf(1, minOf(rawW / targetW, rawH / targetH))
        val samplePowerOfTwo = Integer.highestOneBit(sample).coerceAtLeast(1)

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = samplePowerOfTwo
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = try {
            BitmapFactory.decodeFile(source.absolutePath, decodeOptions)
        } catch (e: Exception) {
            if (e is CancellationException || e is SecurityException || e is java.io.IOException) throw e
            throw ImageDecodeException("이미지 디코딩에 실패했습니다: ${source.name}", e)
        } ?: throw ImageDecodeException("이미지 디코딩에 실패했습니다: ${source.name}")

        val orientedBitmap = try {
            val matrix = AndroidMatrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    matrix.postRotate(90f)
                    matrix.postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    matrix.postRotate(270f)
                    matrix.postScale(-1f, 1f)
                }
            }
            if (!matrix.isIdentity) {
                val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                if (rotated !== decoded) decoded.recycle()
                rotated
            } else {
                decoded
            }
        } catch (e: Exception) {
            decoded.recycle()
            if (e is CancellationException || e is SecurityException || e is java.io.IOException) throw e
            throw ImageDecodeException("이미지 변환 처리에 실패했습니다: ${source.name}", e)
        }

        try {
            renderBitmapToJpeg(orientedBitmap, targetW, targetH, output, fit, focusX, focusY)
        } finally {
            orientedBitmap.recycle()
        }
    }

    private fun preprocessImageOrFallback(
        source: TrailerSource,
        targetW: Int,
        targetH: Int,
        output: File,
        fit: SceneFit,
        focusX: Float,
        focusY: Float,
    ): Boolean {
        try {
            preprocessImage(source.file, targetW, targetH, output, fit, focusX, focusY)
            return false
        } catch (e: ImageDecodeException) {
            var cause: Throwable? = e.cause
            while (cause != null) {
                if (cause is CancellationException || cause is SecurityException || cause is java.io.IOException || cause is Error) {
                    throw cause
                }
                cause = cause.cause
            }
            val thumb = source.thumbnail
            if (thumb != null && !thumb.isRecycled) {
                renderBitmapToJpeg(thumb, targetW, targetH, output, fit, focusX, focusY)
                return true
            } else {
                throw e
            }
        }
    }

    private suspend fun createRecoveredStillScene(
        scene: TrailerScene,
        targetW: Int,
        targetH: Int,
        destinationParent: File,
        tempFiles: MutableList<File>,
        preprocessedBySource: MutableMap<String, File>,
    ): TrailerScene? = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()

        var retrievedBitmap: Bitmap? = null
        var isRetrievedOwned = false

        val sourceThumb = scene.source.thumbnail?.takeIf { !it.isRecycled }
        try {
            val bitmap: Bitmap? = if (sourceThumb != null) {
                sourceThumb
            } else {
                if (!scene.source.file.exists() || !scene.source.file.isFile) {
                    throw java.io.FileNotFoundException("Source video not found: ${scene.source.file.absolutePath}")
                }
                if (!scene.source.file.canRead()) {
                    throw SecurityException("Source video not readable: ${scene.source.file.absolutePath}")
                }
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(scene.source.file.absolutePath)
                    currentCoroutineContext().ensureActive()
                    val targetUs = (scene.start * 1_000_000).toLong()
                    retrievedBitmap = retriever.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: retriever.getFrameAtTime(0L)
                    if (retrievedBitmap != null) {
                        isRetrievedOwned = true
                    }
                    retrievedBitmap
                } catch (e: Exception) {
                    if (e is CancellationException || e is SecurityException || e is java.io.IOException) {
                        throw e
                    }
                    null
                } finally {
                    try {
                        retriever.release()
                    } catch (_: Exception) {
                    }
                }
            }

            currentCoroutineContext().ensureActive()
            if (bitmap == null) return@withContext null

            val stillFile = File(destinationParent, "recovered-still-${scene.source.id}-${UUID.randomUUID()}.jpg")
            tempFiles.add(stillFile)
            try {
                renderBitmapToJpeg(bitmap, targetW, targetH, stillFile, scene.fit, scene.focusX, scene.focusY)
            } catch (t: Throwable) {
                stillFile.delete()
                tempFiles.remove(stillFile)
                throw t
            }

            currentCoroutineContext().ensureActive()

            val framingKey = "${scene.source.id}:${scene.fit}:${scene.focusX}:${scene.focusY}"
            preprocessedBySource[framingKey] = stillFile

            val recoveredSource = scene.source.copy(
                file = stillFile,
                isImage = true,
                width = targetW,
                height = targetH,
                thumbnail = sourceThumb,
            )
            scene.copy(
                source = recoveredSource,
                start = scene.start,
                length = scene.length,
                fit = scene.fit,
                focusX = scene.focusX,
                focusY = scene.focusY,
                sourceVolume = 0f,
            )
        } finally {
            if (isRetrievedOwned && retrievedBitmap != null) {
                retrievedBitmap.recycle()
            }
        }
    }

    private fun buildVideoEditedMediaItem(
        scene: TrailerScene,
        exportWidth: Int,
        exportHeight: Int,
        presentation: Presentation,
        random: Random,
        targetWidth: Int,
        targetHeight: Int,
        preprocessedBySource: MutableMap<String, File>,
        thumbnailFallbackKeys: MutableSet<String>,
        tempFiles: MutableList<File>,
        destinationParent: File,
    ): EditedMediaItem {
        return if (scene.source.isImage) {
            val framingKey = "${scene.source.id}:${scene.fit}:${scene.focusX}:${scene.focusY}"
            val preprocessedFile = preprocessedBySource.getOrPut(framingKey) {
                val tempFile = File(destinationParent, "preprocessed-${scene.source.id}-${UUID.randomUUID()}.jpg")
                tempFiles.add(tempFile)
                val usedFallback = try {
                    preprocessImageOrFallback(scene.source, targetWidth, targetHeight, tempFile, scene.fit, scene.focusX, scene.focusY)
                } catch (t: Throwable) {
                    tempFile.delete()
                    tempFiles.remove(tempFile)
                    throw t
                }
                if (usedFallback) {
                    thumbnailFallbackKeys.add(framingKey)
                }
                tempFile
            }
            val inputUri = Uri.fromFile(preprocessedFile)
            val durationUs = (scene.length * 1_000_000).toLong()
            val zoomIn = random.nextBoolean()
            val focalX = (random.nextFloat() * 2f - 1f)
            val focalY = (random.nextFloat() * 2f - 1f)
            val kenBurns = ImageKenBurnsTransformation(zoomIn, focalX, focalY, durationUs)
            val input = MediaItem.Builder()
                .setUri(inputUri)
                .setImageDurationMs((scene.length * 1000).toLong())
                .build()
            EditedMediaItem.Builder(input)
                .setDurationUs(durationUs)
                .setFrameRate(30)
                .setEffects(Effects(emptyList(), listOf(presentation, kenBurns)))
                .build()
        } else {
            val inputUri = Uri.fromFile(scene.source.file)
            val input = MediaItem.Builder().setUri(inputUri)
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs((scene.start * 1000).toLong())
                    .setEndPositionMs(((scene.start + scene.length) * 1000).toLong()).build()).build()
            val gain = scene.sourceVolume.coerceIn(0f, 2f)
            val audioEffects = if (abs(gain - 1f) < 0.005f) emptyList() else listOf<androidx.media3.common.audio.AudioProcessor>(SceneGainAudioProcessor(gain))
            EditedMediaItem.Builder(input).setFrameRate(30)
                .setEffects(Effects(audioEffects, sceneFramingVideoEffects(scene.fit, scene.focusX, scene.focusY, scene.source.width, scene.source.height, exportWidth, exportHeight)))
                .build()
        }
    }

    private suspend fun buildMusicSequence(
        context: Context,
        scenes: List<TrailerScene>,
        copiedMusicFile: File,
        musicTrack: TrailerMusicTrack,
        musicPolicy: TrailerMusicPolicy,
        musicFitCount: Int,
        autoDucking: Boolean,
        musicGain: Float,
        totalVideoDurationSeconds: Double,
    ): EditedMediaItemSequence? {
        val musicDurationSeconds = musicTrack.durationSeconds
        val duckingTimeline = if (autoDucking) {
            val uniqueVideoSources = scenes.map { it.source }
                .filter { !it.isImage }
                .distinctBy { it.id }

            val envelopes = mutableMapOf<Int, FloatArray>()
            for (src in uniqueVideoSources) {
                val env = AudioAnalysisService.extractLoudnessEnvelope(
                    context = context,
                    uri = Uri.fromFile(src.file),
                    sourceDurationSeconds = src.duration,
                    binSeconds = 0.05,
                )
                envelopes[src.id] = env
            }
            AudioDuckingTimeline.buildWithLoudnessEnvelopes(
                scenes = scenes,
                envelopes = envelopes,
                binSeconds = 0.05,
                originalClipGain = 1.0f,
            )
        } else {
            AudioDuckingTimeline.constant()
        }

        fun makeMusicEffects(startOffsetSeconds: Double): Effects {
            val audioProcessors = listOf<androidx.media3.common.audio.AudioProcessor>(
                AutoDuckingAudioProcessor(
                    timeline = duckingTimeline,
                    outputStartOffsetSeconds = startOffsetSeconds,
                    autoDucking = autoDucking,
                    duckedGain = 0.18f * musicGain,
                    fullGain = 0.85f * musicGain,
                    constantGain = 0.85f * musicGain,
                )
            )
            return Effects(audioProcessors, emptyList())
        }

        val musicUri = Uri.fromFile(copiedMusicFile)
        val musicItems = mutableListOf<EditedMediaItem>()

        if (musicPolicy == TrailerMusicPolicy.LOOP_MUSIC) {
            var accumulated = 0.0
            while (accumulated < totalVideoDurationSeconds - 0.05) {
                val remaining = totalVideoDurationSeconds - accumulated
                val clipDuration = min(musicDurationSeconds, remaining)
                val input = MediaItem.Builder()
                    .setUri(musicUri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(0L)
                            .setEndPositionMs((clipDuration * 1000).toLong())
                            .build()
                    ).build()
                val musicEffects = makeMusicEffects(startOffsetSeconds = accumulated)
                musicItems.add(
                    EditedMediaItem.Builder(input)
                        .setEffects(musicEffects)
                        .build()
                )
                accumulated += clipDuration
            }
        } else if (musicPolicy == TrailerMusicPolicy.FIT_MUSIC_LENGTH) {
            val count = musicFitCount.coerceAtLeast(1)
            var accumulated = 0.0
            for (rep in 0 until count) {
                if (accumulated >= totalVideoDurationSeconds - 0.05) break
                val remaining = totalVideoDurationSeconds - accumulated
                val clipDuration = min(musicDurationSeconds, remaining)
                val input = MediaItem.Builder()
                    .setUri(musicUri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(0L)
                            .setEndPositionMs((clipDuration * 1000).toLong())
                            .build()
                    ).build()
                val musicEffects = makeMusicEffects(startOffsetSeconds = accumulated)
                musicItems.add(
                    EditedMediaItem.Builder(input)
                        .setEffects(musicEffects)
                        .build()
                )
                accumulated += clipDuration
            }
        } else {
            val clipDuration = min(musicDurationSeconds, totalVideoDurationSeconds)
            val input = MediaItem.Builder()
                .setUri(musicUri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(0L)
                        .setEndPositionMs((clipDuration * 1000).toLong())
                        .build()
                ).build()
            val musicEffects = makeMusicEffects(startOffsetSeconds = 0.0)
            musicItems.add(
                EditedMediaItem.Builder(input)
                    .setEffects(musicEffects)
                    .build()
            )
        }

        return if (musicItems.isNotEmpty()) {
            EditedMediaItemSequence.withAudioFrom(musicItems)
        } else {
            null
        }
    }

    private suspend fun runTransformerExport(
        context: Context,
        composition: Composition,
        outputFile: File,
        statusText: String,
        safeProgress: (status: String, progress: Float?) -> Unit,
        progressStart: Float,
        progressEnd: Float,
        onTransformerCreated: (Transformer) -> Unit = {},
    ) {
        val scope = CoroutineScope(currentCoroutineContext())
        var poll: Job? = null
        suspendCancellableCoroutine<Unit> { continuation ->
            val instance = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
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

            onTransformerCreated(instance)

            continuation.invokeOnCancellation {
                poll?.cancel()
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    instance.cancel()
                } else {
                    Handler(Looper.getMainLooper()).post { instance.cancel() }
                }
            }

            if (!continuation.isActive) return@suspendCancellableCoroutine

            safeProgress(statusText, progressStart)

            try {
                instance.start(composition, outputFile.absolutePath)
            } catch (e: Throwable) {
                poll?.cancel()
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    instance.cancel()
                } else {
                    Handler(Looper.getMainLooper()).post { instance.cancel() }
                }
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                } else {
                    throw e
                }
                return@suspendCancellableCoroutine
            }

            poll = scope.launch {
                val holder = ProgressHolder()
                while (isActive) {
                    if (instance.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        val fraction = (holder.progress / 100f).coerceIn(0f, 1f)
                        val current = progressStart + fraction * (progressEnd - progressStart)
                        safeProgress(statusText, current)
                    }
                    delay(250)
                }
            }
        }
    }

    private suspend fun exportChunk(
        context: Context,
        scenes: List<TrailerScene>,
        exportWidth: Int,
        exportHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        outputFile: File,
        statusText: String,
        safeProgress: (status: String, progress: Float?) -> Unit,
        progressStart: Float,
        progressEnd: Float,
        random: Random,
        preprocessedBySource: MutableMap<String, File>,
        thumbnailFallbackKeys: MutableSet<String>,
        tempFiles: MutableList<File>,
        destinationParent: File,
        onTransformerCreated: (Transformer) -> Unit,
    ) {
        val presentation = Presentation.createForWidthAndHeight(exportWidth, exportHeight, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
        val edited = withContext(Dispatchers.IO) {
            scenes.map { scene ->
                buildVideoEditedMediaItem(
                    scene = scene,
                    exportWidth = exportWidth,
                    exportHeight = exportHeight,
                    presentation = presentation,
                    random = random,
                    targetWidth = targetWidth,
                    targetHeight = targetHeight,
                    preprocessedBySource = preprocessedBySource,
                    thumbnailFallbackKeys = thumbnailFallbackKeys,
                    tempFiles = tempFiles,
                    destinationParent = destinationParent,
                )
            }
        }
        currentCoroutineContext().ensureActive()
        val videoSequence = EditedMediaItemSequence.withAudioAndVideoFrom(edited)
        val composition = Composition.Builder(listOf(videoSequence))
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()

        currentCoroutineContext().ensureActive()
        runTransformerExport(
            context = context,
            composition = composition,
            outputFile = outputFile,
            statusText = statusText,
            safeProgress = safeProgress,
            progressStart = progressStart,
            progressEnd = progressEnd,
            onTransformerCreated = onTransformerCreated,
        )
    }

    private suspend fun exportGroupWithRecovery(
        context: Context,
        scenes: List<TrailerScene>,
        exportWidth: Int,
        exportHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        statusPrefix: String,
        safeProgress: (status: String, progress: Float?) -> Unit,
        progressStart: Float,
        progressEnd: Float,
        random: Random,
        preprocessedBySource: MutableMap<String, File>,
        thumbnailFallbackKeys: MutableSet<String>,
        tempFiles: MutableList<File>,
        destinationParent: File,
        depth: Int = 0,
        maxDepth: Int = 8,
        clipOffset: Int,
        totalClips: Int,
        onTransformerCreated: (Transformer) -> Unit,
    ): GroupExportResult {
        currentCoroutineContext().ensureActive()

        val chunkFile = File(destinationParent, "chunk-${UUID.randomUUID()}.mp4")
        tempFiles.add(chunkFile)

        val currentClipEnd = clipOffset + scenes.size
        val statusText = "$statusPrefix (${clipOffset + 1}~$currentClipEnd/$totalClips 클립 준비 중)"

        try {
            exportChunk(
                context = context,
                scenes = scenes,
                exportWidth = exportWidth,
                exportHeight = exportHeight,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                outputFile = chunkFile,
                statusText = statusText,
                safeProgress = safeProgress,
                progressStart = progressStart,
                progressEnd = progressEnd,
                random = random,
                preprocessedBySource = preprocessedBySource,
                thumbnailFallbackKeys = thumbnailFallbackKeys,
                tempFiles = tempFiles,
                destinationParent = destinationParent,
                onTransformerCreated = onTransformerCreated,
            )
            val fallbackRecoveries = scenes.count { scene ->
                scene.source.isImage && thumbnailFallbackKeys.contains("${scene.source.id}:${scene.fit}:${scene.focusX}:${scene.focusY}")
            }
            return GroupExportResult(
                files = listOf(chunkFile),
                effectiveScenes = scenes,
                recoveredCount = fallbackRecoveries,
                excludedCount = 0,
            )
        } catch (e: Throwable) {
            chunkFile.delete()
            tempFiles.remove(chunkFile)
            if (e is CancellationException) {
                throw e
            }
            if (e is SecurityException || e is java.io.IOException || !isMediaDecodingFailure(e)) {
                throw e
            }
            if (depth >= maxDepth) {
                throw e
            }

            if (scenes.size > 1) {
                val mid = scenes.size / 2
                val left = scenes.subList(0, mid)
                val right = scenes.subList(mid, scenes.size)
                val midProgress = progressStart + (progressEnd - progressStart) * (mid.toFloat() / scenes.size)

                val leftResult = exportGroupWithRecovery(
                    context = context,
                    scenes = left,
                    exportWidth = exportWidth,
                    exportHeight = exportHeight,
                    targetWidth = targetWidth,
                    targetHeight = targetHeight,
                    statusPrefix = statusPrefix,
                    safeProgress = safeProgress,
                    progressStart = progressStart,
                    progressEnd = midProgress,
                    random = random,
                    preprocessedBySource = preprocessedBySource,
                    thumbnailFallbackKeys = thumbnailFallbackKeys,
                    tempFiles = tempFiles,
                    destinationParent = destinationParent,
                    depth = depth + 1,
                    maxDepth = maxDepth,
                    clipOffset = clipOffset,
                    totalClips = totalClips,
                    onTransformerCreated = onTransformerCreated,
                )

                val rightResult = exportGroupWithRecovery(
                    context = context,
                    scenes = right,
                    exportWidth = exportWidth,
                    exportHeight = exportHeight,
                    targetWidth = targetWidth,
                    targetHeight = targetHeight,
                    statusPrefix = statusPrefix,
                    safeProgress = safeProgress,
                    progressStart = midProgress,
                    progressEnd = progressEnd,
                    random = random,
                    preprocessedBySource = preprocessedBySource,
                    thumbnailFallbackKeys = thumbnailFallbackKeys,
                    tempFiles = tempFiles,
                    destinationParent = destinationParent,
                    depth = depth + 1,
                    maxDepth = maxDepth,
                    clipOffset = clipOffset + mid,
                    totalClips = totalClips,
                    onTransformerCreated = onTransformerCreated,
                )

                return GroupExportResult(
                    files = leftResult.files + rightResult.files,
                    effectiveScenes = leftResult.effectiveScenes + rightResult.effectiveScenes,
                    recoveredCount = leftResult.recoveredCount + rightResult.recoveredCount,
                    excludedCount = leftResult.excludedCount + rightResult.excludedCount,
                )
            } else {
                val badScene = scenes[0]
                val clipNum = clipOffset + 1
                safeProgress("손상된 미디어 복구 중 ($clipNum/$totalClips 클립)…", progressStart)

                val recoveredScene = try {
                    createRecoveredStillScene(
                        scene = badScene,
                        targetW = targetWidth,
                        targetH = targetHeight,
                        destinationParent = destinationParent,
                        tempFiles = tempFiles,
                        preprocessedBySource = preprocessedBySource,
                    )
                } catch (stillErr: Throwable) {
                    if (stillErr is CancellationException) throw stillErr
                    if (stillErr is SecurityException || stillErr is java.io.IOException || !isMediaDecodingFailure(stillErr)) {
                        throw stillErr
                    }
                    null
                }

                if (recoveredScene == null) {
                    return GroupExportResult(
                        files = emptyList(),
                        effectiveScenes = emptyList(),
                        recoveredCount = 0,
                        excludedCount = 1,
                    )
                }

                val recoveredChunkFile = File(destinationParent, "chunk-recovered-${UUID.randomUUID()}.mp4")
                tempFiles.add(recoveredChunkFile)

                try {
                    exportChunk(
                        context = context,
                        scenes = listOf(recoveredScene),
                        exportWidth = exportWidth,
                        exportHeight = exportHeight,
                        targetWidth = targetWidth,
                        targetHeight = targetHeight,
                        outputFile = recoveredChunkFile,
                        statusText = "복구된 스틸로 저장 중 ($clipNum/$totalClips 클립)",
                        safeProgress = safeProgress,
                        progressStart = progressStart,
                        progressEnd = progressEnd,
                        random = random,
                        preprocessedBySource = preprocessedBySource,
                        thumbnailFallbackKeys = thumbnailFallbackKeys,
                        tempFiles = tempFiles,
                        destinationParent = destinationParent,
                        onTransformerCreated = onTransformerCreated,
                    )
                    return GroupExportResult(
                        files = listOf(recoveredChunkFile),
                        effectiveScenes = listOf(recoveredScene),
                        recoveredCount = 1,
                        excludedCount = 0,
                    )
                } catch (recoveryError: Throwable) {
                    recoveredChunkFile.delete()
                    tempFiles.remove(recoveredChunkFile)
                    recoveredScene.source.file.delete()
                    tempFiles.remove(recoveredScene.source.file)
                    preprocessedBySource.remove("${badScene.source.id}:${badScene.fit}:${badScene.focusX}:${badScene.focusY}")
                    if (recoveryError is CancellationException) throw recoveryError
                    if (recoveryError is SecurityException || recoveryError is java.io.IOException || !isMediaDecodingFailure(recoveryError)) {
                        throw recoveryError
                    }
                    return GroupExportResult(
                        files = emptyList(),
                        effectiveScenes = emptyList(),
                        recoveredCount = 0,
                        excludedCount = 1,
                    )
                }
            }
        }
    }

    suspend fun export(
        context: Context,
        scenes: List<TrailerScene>,
        format: TrailerFormat,
        destination: File,
        musicTrack: TrailerMusicTrack? = null,
        musicPolicy: TrailerMusicPolicy = TrailerMusicPolicy.LOOP_MUSIC,
        musicFitCount: Int = 1,
        autoDucking: Boolean = true,
        aspectSources: List<TrailerSource> = scenes.map { it.source },
        musicVolume: Float = 1f,
        onSuccess: ((recoveredScenes: Int, excludedScenes: Int) -> Unit)? = null,
        progress: (status: String, progress: Float?) -> Unit,
    ): File = withContext(Dispatchers.Main.immediate) {
        require(scenes.isNotEmpty()) { "이 장면 길이로 만들 수 있는 영상이 없습니다. 장면 길이를 줄여 주세요." }
        val totalClips = scenes.size
        val random = Random()
        val (exportWidth, exportHeight) = format.resolveDimensions(aspectSources)
        val musicGain = musicVolume.coerceIn(0f, 2f)

        val targetWidth = ((kotlin.math.round(exportWidth * 0.60) / 2.0).toInt() * 2).coerceAtLeast(2)
        val targetHeight = ((kotlin.math.round(exportHeight * 0.60) / 2.0).toInt() * 2).coerceAtLeast(2)

        val taskOwnedTempFiles = mutableListOf<File>()
        val destinationParent = destination.parentFile ?: context.cacheDir
        val preprocessedBySource = mutableMapOf<String, File>()
        val thumbnailFallbackKeys = mutableSetOf<String>()

        var lastReportedProgress = 0f
        fun safeProgress(status: String, p: Float?) {
            if (p == null) {
                progress(status, null)
            } else {
                val monotonic = maxOf(lastReportedProgress, p.coerceIn(0f, 1f))
                lastReportedProgress = monotonic
                progress(status, monotonic)
            }
        }

        val copiedMusicFile: File? = if (musicTrack != null) {
            try {
                require(musicTrack.file.isFile) { "선택한 음악 파일을 찾을 수 없습니다. 음악을 다시 선택해 주세요." }
                require(musicTrack.durationSeconds > 0.0) { "선택한 음악의 길이를 확인할 수 없습니다. 음악을 다시 선택해 주세요." }
                withContext(Dispatchers.IO) {
                    val sourceExt = musicTrack.file.extension.takeIf { it.isNotBlank() && it.all(Char::isLetterOrDigit) } ?: "tmp"
                    val copy = File(destinationParent, "music-${UUID.randomUUID()}.$sourceExt")
                    taskOwnedTempFiles.add(copy)
                    musicTrack.file.copyTo(copy, overwrite = true)
                    copy
                }
            } catch (e: Exception) {
                taskOwnedTempFiles.forEach { it.delete() }
                throw e
            }
        } else null

        var currentTransformer: Transformer? = null
        var exportSuccess = false

        try {
            val maxGroupSize = MAX_GROUP_SIZE
            val groups = scenes.chunked(maxGroupSize)
            val totalGroups = groups.size
            val chunkPhaseEnd = if (totalGroups == 1 && copiedMusicFile == null) 1.0f else 0.85f

            val intermediateFiles = mutableListOf<File>()
            val effectiveScenes = mutableListOf<TrailerScene>()
            var clipOffset = 0

            var totalRecoveredScenes = 0
            var totalExcludedScenes = 0

            for ((groupIndex, groupScenes) in groups.withIndex()) {
                currentCoroutineContext().ensureActive()
                val groupStartProgress = (groupIndex.toFloat() / totalGroups) * chunkPhaseEnd
                val groupEndProgress = ((groupIndex + 1).toFloat() / totalGroups) * chunkPhaseEnd

                val groupResult = exportGroupWithRecovery(
                    context = context,
                    scenes = groupScenes,
                    exportWidth = exportWidth,
                    exportHeight = exportHeight,
                    targetWidth = targetWidth,
                    targetHeight = targetHeight,
                    statusPrefix = "클립 준비 중",
                    safeProgress = ::safeProgress,
                    progressStart = groupStartProgress,
                    progressEnd = groupEndProgress,
                    random = random,
                    preprocessedBySource = preprocessedBySource,
                    thumbnailFallbackKeys = thumbnailFallbackKeys,
                    tempFiles = taskOwnedTempFiles,
                    destinationParent = destinationParent,
                    depth = 0,
                    maxDepth = 8,
                    clipOffset = clipOffset,
                    totalClips = totalClips,
                    onTransformerCreated = { currentTransformer = it },
                )
                intermediateFiles.addAll(groupResult.files)
                effectiveScenes.addAll(groupResult.effectiveScenes)
                totalRecoveredScenes += groupResult.recoveredCount
                totalExcludedScenes += groupResult.excludedCount
                clipOffset += groupScenes.size
            }

            currentCoroutineContext().ensureActive()

            if (effectiveScenes.isEmpty() || intermediateFiles.isEmpty()) {
                throw IllegalStateException("내보낼 수 있는 유효한 장면이 없습니다.")
            }

            val effectiveTotalDuration = effectiveScenes.sumOf { it.length }

            if (intermediateFiles.size == 1 && copiedMusicFile == null) {
                val singleChunk = intermediateFiles[0]
                withContext(Dispatchers.IO) {
                    singleChunk.copyTo(destination, overwrite = true)
                }
                safeProgress("영상으로 저장 중 (${effectiveScenes.size}/${effectiveScenes.size}개 연결 완료)", 1.0f)
            } else {
                val concatEditedItems = intermediateFiles.map { chunkFile ->
                    val inputUri = Uri.fromFile(chunkFile)
                    val input = MediaItem.Builder().setUri(inputUri).build()
                    EditedMediaItem.Builder(input)
                        .setFrameRate(30)
                        .build()
                }
                val videoSequence = EditedMediaItemSequence.withAudioAndVideoFrom(concatEditedItems)
                val sequences = mutableListOf<EditedMediaItemSequence>()
                sequences.add(videoSequence)

                if (copiedMusicFile != null && musicTrack != null) {
                    val musicSequence = buildMusicSequence(
                        context = context,
                        scenes = effectiveScenes,
                        copiedMusicFile = copiedMusicFile,
                        musicTrack = musicTrack,
                        musicPolicy = musicPolicy,
                        musicFitCount = musicFitCount,
                        autoDucking = autoDucking,
                        musicGain = musicGain,
                        totalVideoDurationSeconds = effectiveTotalDuration,
                    )
                    if (musicSequence != null) {
                        sequences.add(musicSequence)
                    }
                }

                val finalComposition = Composition.Builder(sequences)
                    .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
                    .build()

                currentCoroutineContext().ensureActive()

                runTransformerExport(
                    context = context,
                    composition = finalComposition,
                    outputFile = destination,
                    statusText = "영상으로 저장 중 (${effectiveScenes.size}/${effectiveScenes.size}개 연결 완료)",
                    safeProgress = ::safeProgress,
                    progressStart = 0.85f,
                    progressEnd = 1.0f,
                    onTransformerCreated = { currentTransformer = it },
                )
            }

            withContext(Dispatchers.IO) {
                check(destination.isFile && destination.length() > 0) { "예고편 파일을 만들지 못했습니다." }
                val reader = MediaMetadataRetriever()
                try {
                    reader.setDataSource(destination.absolutePath)
                    val duration = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull()?.div(1000) ?: 0.0
                    check(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes" && abs(duration - effectiveTotalDuration) <= maxOf(.5, effectiveScenes.size * .05)) { "완성 영상의 길이를 확인하지 못했습니다. 다시 만들어 주세요." }
                } finally { reader.release() }
            }

            exportSuccess = true
            onSuccess?.invoke(totalRecoveredScenes, totalExcludedScenes)
            destination
        } finally {
            currentTransformer?.cancel()
            if (!exportSuccess && destination.exists()) {
                destination.delete()
            }
            taskOwnedTempFiles.forEach { it.delete() }
        }
    }
}
