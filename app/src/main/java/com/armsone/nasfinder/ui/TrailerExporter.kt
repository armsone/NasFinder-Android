package com.armsone.nasfinder.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix as AndroidMatrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.MediaCodecInfo
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
    private const val OUTPUT_FPS = 30.0
    // H.264 레벨 4.1이 허용하는 최대 프레임 크기(8192 매크로블록 = 2,097,152 화소). 1920x1080/1080x1920 모두 이 안에 든다.
    private const val H264_LEVEL_41_MAX_PIXELS = 2_097_152L

    private class ImageDecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private data class GroupExportResult(
        val files: List<File>,
        // files와 같은 순서로, 각 파일이 담아야 할 계획 길이(초). 이어 붙일 때 파일 끝의 인코더/AAC 꼬리를 잘라내는 기준이다.
        val fileDurations: List<Double>,
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

    private fun renderFullOrientedBitmapToJpeg(
        orientedBitmap: Bitmap,
        output: File,
        maxLongEdge: Int = 2560,
        maxPixels: Long = 4_194_304L,
    ) {
        val srcW = orientedBitmap.width
        val srcH = orientedBitmap.height
        val longEdge = maxOf(srcW, srcH)
        val totalPixels = srcW.toLong() * srcH.toLong()

        val scale = if (longEdge > maxLongEdge || totalPixels > maxPixels) {
            val scaleEdge = maxLongEdge.toFloat() / longEdge
            val scalePixels = kotlin.math.sqrt(maxPixels.toDouble() / totalPixels).toFloat()
            minOf(scaleEdge, scalePixels)
        } else {
            1f
        }

        val finalW = (((srcW * scale).toInt() / 2) * 2).coerceAtLeast(2)
        val finalH = (((srcH * scale).toInt() / 2) * 2).coerceAtLeast(2)

        val workingBitmap = if (finalW != srcW || finalH != srcH) {
            Bitmap.createScaledBitmap(orientedBitmap, finalW, finalH, true)
        } else {
            orientedBitmap
        }

        try {
            FileOutputStream(output).use { fos ->
                workingBitmap.compress(Bitmap.CompressFormat.JPEG, 92, fos)
            }
        } finally {
            if (workingBitmap !== orientedBitmap) {
                workingBitmap.recycle()
            }
        }
    }

    private fun preprocessFullPhoto(source: File, output: File) {
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
        val maxLongEdge = 2560
        val maxPixels = 4_194_304L

        var sample = 1
        var w = rawW
        var h = rawH
        while (maxOf(w, h) > maxLongEdge * 2 || (w.toLong() * h.toLong()) > maxPixels * 4L) {
            sample *= 2
            w = rawW / sample
            h = rawH / sample
        }
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
            renderFullOrientedBitmapToJpeg(orientedBitmap, output, maxLongEdge, maxPixels)
        } finally {
            orientedBitmap.recycle()
        }
    }

    private fun preprocessFullPhotoOrFallback(source: TrailerSource, output: File): Boolean {
        try {
            preprocessFullPhoto(source.file, output)
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
                renderFullOrientedBitmapToJpeg(thumb, output)
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

    /**
     * The scene's caption as a full-frame transparent bitmap overlay, appended AFTER framing/photo motion so it stays
     * fixed to the output frame. Bitmaps are shared per identical caption within a chunk and never recycled here
     * (the GL pipeline may still sample them until the export ends).
     */
    private fun captionOverlayEffects(
        context: Context,
        caption: SceneCaption?,
        exportWidth: Int,
        exportHeight: Int,
        captionBitmaps: MutableMap<SceneCaption, Bitmap>,
    ): List<androidx.media3.common.Effect> {
        val normalized = caption?.normalizedOrNull() ?: return emptyList()
        val bitmap = captionBitmaps.getOrPut(normalized) {
            SceneCaptionRenderer.render(context, normalized, exportWidth, exportHeight) ?: return emptyList()
        }
        val overlay: androidx.media3.effect.TextureOverlay = androidx.media3.effect.BitmapOverlay.createStaticBitmapOverlay(bitmap)
        return listOf(androidx.media3.effect.OverlayEffect(com.google.common.collect.ImmutableList.of(overlay)))
    }

    private suspend fun buildVideoEditedMediaItem(
        context: Context,
        captionBitmaps: MutableMap<SceneCaption, Bitmap>,
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
            val plan = TrailerPhotoMotionPlanner.planMotion(
                source = scene.source,
                fit = scene.fit,
                focusX = scene.focusX,
                focusY = scene.focusY,
                outputWidth = exportWidth,
                outputHeight = exportHeight,
                durationSeconds = scene.length,
            )
            // Every photo (FIT, FILL and manual focus) renders the full photo through the planned camera path, the same
            // path the editor preview draws; FILL frames are clamped to the photo so no frame can letterbox.
            val fullPhotoKey = "full:${scene.source.id}"
            val preprocessedFile = preprocessedBySource.getOrPut(fullPhotoKey) {
                val tempFile = File(destinationParent, "preprocessed-full-${scene.source.id}-${UUID.randomUUID()}.jpg")
                tempFiles.add(tempFile)
                val usedFallback = try {
                    preprocessFullPhotoOrFallback(scene.source, tempFile)
                } catch (t: Throwable) {
                    tempFile.delete()
                    tempFiles.remove(tempFile)
                    throw t
                }
                if (usedFallback) {
                    thumbnailFallbackKeys.add(fullPhotoKey)
                }
                tempFile
            }
            val inputUri = Uri.fromFile(preprocessedFile)
            val durationUs = (scene.length * 1_000_000).toLong()
            val plannedAspect = if (scene.source.width > 0 && scene.source.height > 0) {
                scene.source.width.toFloat() / scene.source.height
            } else {
                exportWidth.toFloat() / exportHeight
            }
            // The camera path renders straight into the export size (zoom/pan in photo coordinates, no later crop).
            val effectsList = listOf<androidx.media3.common.Effect>(
                PhotoMotionGlTransformation(plan, durationUs, exportWidth, exportHeight, plannedAspect),
            ) + captionOverlayEffects(context, scene.caption, exportWidth, exportHeight, captionBitmaps)
            val input = MediaItem.Builder()
                .setUri(inputUri)
                .setImageDurationMs((scene.length * 1000).toLong())
                .build()
            EditedMediaItem.Builder(input)
                .setDurationUs(durationUs)
                .setFrameRate(30)
                .setEffects(Effects(emptyList(), effectsList))
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
                .setEffects(Effects(
                    audioEffects,
                    sceneFramingVideoEffects(scene.fit, scene.focusX, scene.focusY, scene.source.width, scene.source.height, exportWidth, exportHeight) +
                        captionOverlayEffects(context, scene.caption, exportWidth, exportHeight, captionBitmaps),
                ))
                .build()
        }
    }

    private suspend fun buildMusicSequence(
        context: Context,
        scenes: List<TrailerScene>,
        copiedMusicFile: File,
        musicTrack: TrailerMusicTrack,
        musicPolicy: TrailerMusicPolicy,
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

        // 음악 길이 맞춤도 반복 재생이다: 영상이 음악×반복 횟수와 같으면 정확히 끝나고, 직접 편집으로 더 길어져도 음악이 끝까지 이어진다.
        if (musicPolicy != TrailerMusicPolicy.SINGLE) {
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
        outputPixels: Long = 0L,
    ) {
        val scope = CoroutineScope(currentCoroutineContext())
        var poll: Job? = null
        suspendCancellableCoroutine<Unit> { continuation ->
            val transformerBuilder = Transformer.Builder(context)
            // 일부 기기(예: Fold6)의 H.264 인코더는 1080p 출력에 레벨 6.0을 기록하고, 같은 기기의 내장 플레이어가 그 영상을
            // 디코드하지 못해 외부 플레이어로 넘어간다. 1080p 이하는 레벨 4.1을 요청하고, 인코더가 지원하지 않으면 기본 폴백을 따른다.
            if (outputPixels in 1..H264_LEVEL_41_MAX_PIXELS) {
                transformerBuilder.setEncoderFactory(
                    DefaultEncoderFactory.Builder(context)
                        .setRequestedVideoEncoderSettings(
                            VideoEncoderSettings.Builder()
                                .setEncodingProfileLevel(
                                    MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                                    MediaCodecInfo.CodecProfileLevel.AVCLevel41,
                                )
                                .build(),
                        )
                        .build(),
                )
            }
            val instance = transformerBuilder
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
        val captionBitmaps = mutableMapOf<SceneCaption, Bitmap>()
        val edited = withContext(Dispatchers.IO) {
            scenes.map { scene ->
                buildVideoEditedMediaItem(
                    context = context,
                    captionBitmaps = captionBitmaps,
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
            outputPixels = exportWidth.toLong() * exportHeight,
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
                scene.source.isImage && (
                    thumbnailFallbackKeys.contains("${scene.source.id}:${scene.fit}:${scene.focusX}:${scene.focusY}") ||
                    thumbnailFallbackKeys.contains("full:${scene.source.id}")
                )
            }
            return GroupExportResult(
                files = listOf(chunkFile),
                fileDurations = listOf(scenes.sumOf { it.length }),
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
                    fileDurations = leftResult.fileDurations + rightResult.fileDurations,
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
                        fileDurations = emptyList(),
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
                        fileDurations = listOf(recoveredScene.length),
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
                        fileDurations = emptyList(),
                        effectiveScenes = emptyList(),
                        recoveredCount = 0,
                        excludedCount = 1,
                    )
                }
            }
        }
    }

    private suspend fun exportTitleCardChunk(
        context: Context,
        cardBitmap: Bitmap,
        durationSeconds: Double,
        exportWidth: Int,
        exportHeight: Int,
        outputFile: File,
        destinationParent: File,
        tempFiles: MutableList<File>,
        statusText: String,
        safeProgress: (status: String, progress: Float?) -> Unit,
        progressStart: Float,
        progressEnd: Float,
        onTransformerCreated: (Transformer) -> Unit,
    ) {
        val tempJpeg = File(destinationParent, "titlecard-frame-${UUID.randomUUID()}.jpg")
        tempFiles.add(tempJpeg)
        withContext(Dispatchers.IO) {
            FileOutputStream(tempJpeg).use { fos ->
                cardBitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos)
            }
        }
        val durationUs = (durationSeconds * 1_000_000.0).toLong()
        val presentation = Presentation.createForWidthAndHeight(exportWidth, exportHeight, Presentation.LAYOUT_SCALE_TO_FIT)
        val input = MediaItem.Builder()
            .setUri(Uri.fromFile(tempJpeg))
            .setImageDurationMs((durationSeconds * 1000.0).toLong())
            .build()
        val editedItem = EditedMediaItem.Builder(input)
            .setDurationUs(durationUs)
            .setFrameRate(30)
            .setEffects(Effects(emptyList(), listOf(presentation)))
            .build()
        val sequence = EditedMediaItemSequence.withAudioAndVideoFrom(listOf(editedItem))
        val composition = Composition.Builder(listOf(sequence))
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()
        runTransformerExport(
            context = context,
            composition = composition,
            outputFile = outputFile,
            statusText = statusText,
            safeProgress = safeProgress,
            progressStart = progressStart,
            progressEnd = progressEnd,
            onTransformerCreated = onTransformerCreated,
            outputPixels = exportWidth.toLong() * exportHeight,
        )
    }

    /**
     * 장면 끝 시각을 30fps 프레임 격자에 누적 반올림한다. 인코더는 장면마다 프레임 단위로 길이를 맞추므로, 계획 길이를 그대로 쓰면
     * 장면 수만큼 오차가 쌓여 영상 끝·비트 전환·음악 길이에서 벗어난다(Media3 이미지 프레임 수는 장면마다 Math.round(fps×길이)).
     * 누적 반올림은 모든 전환을 계획 시각의 반 프레임 안에 둔다. 영상 장면은 시작 위치부터 원본 끝을 넘지 않도록 내림해 남은 오차를 다음 장면이 흡수한다.
     */
    private fun snapSceneLengthsToFrames(scenes: List<TrailerScene>): List<TrailerScene> {
        var plannedEnd = 0.0
        var snappedFrames = 0L
        return scenes.map { scene ->
            plannedEnd += scene.length
            var endFrame = Math.round(plannedEnd * OUTPUT_FPS).coerceAtLeast(snappedFrames + 1)
            val source = scene.source
            if (!source.isImage && source.duration.isFinite() && source.duration > 0.0) {
                val sourceFrames = Math.floor((source.duration - scene.start) * OUTPUT_FPS + 1e-6).toLong()
                if (endFrame - snappedFrames > sourceFrames) endFrame = snappedFrames + sourceFrames.coerceAtLeast(1)
            }
            val snappedLength = (endFrame - snappedFrames) / OUTPUT_FPS
            snappedFrames = endFrame
            if (abs(snappedLength - scene.length) < 1e-9) scene else scene.copy(length = snappedLength)
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
        introCard: TrailerTitleCard? = null,
        outroCard: TrailerTitleCard? = null,
        // Photos a card's chosen background may come from (same candidates the editor offers); missing = first/last frame.
        cardBackgroundSources: List<TrailerSource> = scenes.map { it.source },
        onSuccess: ((recoveredScenes: Int, excludedScenes: Int) -> Unit)? = null,
        progress: (status: String, progress: Float?) -> Unit,
    ): File = withContext(Dispatchers.Main.immediate) {
        require(scenes.isNotEmpty()) { "이 장면 길이로 만들 수 있는 영상이 없습니다. 장면 길이를 줄여 주세요." }
        val framedScenes = snapSceneLengthsToFrames(scenes)
        val totalClips = framedScenes.size
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
            val groups = framedScenes.chunked(maxGroupSize)
            val totalGroups = groups.size
            val chunkPhaseEnd = if (totalGroups == 1 && copiedMusicFile == null) 1.0f else 0.85f

            val intermediateFiles = mutableListOf<File>()
            val intermediateDurations = mutableListOf<Double>()
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
                intermediateDurations.addAll(groupResult.fileDurations)
                effectiveScenes.addAll(groupResult.effectiveScenes)
                totalRecoveredScenes += groupResult.recoveredCount
                totalExcludedScenes += groupResult.excludedCount
                clipOffset += groupScenes.size
            }

            currentCoroutineContext().ensureActive()

            if (effectiveScenes.isEmpty() || intermediateFiles.isEmpty()) {
                throw IllegalStateException("내보낼 수 있는 유효한 장면이 없습니다.")
            }

            val introCardEnabled = introCard?.enabled == true
            val outroCardEnabled = outroCard?.enabled == true

            var introChunkFile: File? = null
            var outroChunkFile: File? = null

            val firstIncludedScene = effectiveScenes.firstOrNull()
            val lastIncludedScene = effectiveScenes.lastOrNull()
            val bodyCaptionStyle = TrailerTitleCardRenderer.bodyCaptionOf(effectiveScenes.map { it.caption })

            if (introCardEnabled && introCard != null) {
                val introDur = introCard.clampedDuration()
                val introChosen = introCard.resolveBackgroundSource(cardBackgroundSources)
                val introBgBitmap = if (introChosen != null) {
                    withContext(Dispatchers.IO) {
                        loadFrame(
                            file = introChosen.file,
                            isImage = introChosen.isImage,
                            atSeconds = introCard.clampedBackgroundTimestamp(introChosen),
                            maxDimension = maxOf(exportWidth, exportHeight),
                            exact = true,
                        )
                    }
                } else if (introCard.usesFirstOrLastImage && firstIncludedScene != null) {
                    withContext(Dispatchers.IO) {
                        loadFrame(
                            file = firstIncludedScene.source.file,
                            isImage = firstIncludedScene.source.isImage,
                            atSeconds = firstIncludedScene.start,
                            maxDimension = maxOf(exportWidth, exportHeight),
                            exact = true,
                        )
                    }
                } else null

                val introBitmap = withContext(Dispatchers.Default) {
                    TrailerTitleCardRenderer.renderCardBitmap(
                        context = context,
                        card = introCard,
                        sourceBitmap = introBgBitmap,
                        width = exportWidth,
                        height = exportHeight,
                        bodyCaption = bodyCaptionStyle,
                    )
                }
                introBgBitmap?.recycle()

                if (introBitmap != null) {
                    val introFile = File(destinationParent, "chunk-intro-${UUID.randomUUID()}.mp4")
                    taskOwnedTempFiles.add(introFile)
                    try {
                        exportTitleCardChunk(
                            context = context,
                            cardBitmap = introBitmap,
                            durationSeconds = introDur,
                            exportWidth = exportWidth,
                            exportHeight = exportHeight,
                            outputFile = introFile,
                            destinationParent = destinationParent,
                            tempFiles = taskOwnedTempFiles,
                            statusText = "시작 화면 생성 중…",
                            safeProgress = ::safeProgress,
                            progressStart = chunkPhaseEnd,
                            progressEnd = chunkPhaseEnd + (0.85f - chunkPhaseEnd) * 0.5f,
                            onTransformerCreated = { currentTransformer = it },
                        )
                        introChunkFile = introFile
                    } finally {
                        introBitmap.recycle()
                    }
                }
            }

            if (outroCardEnabled && outroCard != null) {
                val outroDur = outroCard.clampedDuration()
                val outroChosen = outroCard.resolveBackgroundSource(cardBackgroundSources)
                val outroBgBitmap = if (outroChosen != null) {
                    withContext(Dispatchers.IO) {
                        loadFrame(
                            file = outroChosen.file,
                            isImage = outroChosen.isImage,
                            atSeconds = outroCard.clampedBackgroundTimestamp(outroChosen),
                            maxDimension = maxOf(exportWidth, exportHeight),
                            exact = true,
                        )
                    }
                } else if (outroCard.usesFirstOrLastImage && lastIncludedScene != null) {
                    withContext(Dispatchers.IO) {
                        val atTime = maxOf(lastIncludedScene.start, lastIncludedScene.start + lastIncludedScene.length - 0.5)
                        loadFrame(
                            file = lastIncludedScene.source.file,
                            isImage = lastIncludedScene.source.isImage,
                            atSeconds = atTime,
                            maxDimension = maxOf(exportWidth, exportHeight),
                            exact = true,
                        )
                    }
                } else null

                val outroBitmap = withContext(Dispatchers.Default) {
                    TrailerTitleCardRenderer.renderCardBitmap(
                        context = context,
                        card = outroCard,
                        sourceBitmap = outroBgBitmap,
                        width = exportWidth,
                        height = exportHeight,
                        bodyCaption = bodyCaptionStyle,
                    )
                }
                outroBgBitmap?.recycle()

                if (outroBitmap != null) {
                    val outroFile = File(destinationParent, "chunk-outro-${UUID.randomUUID()}.mp4")
                    taskOwnedTempFiles.add(outroFile)
                    try {
                        exportTitleCardChunk(
                            context = context,
                            cardBitmap = outroBitmap,
                            durationSeconds = outroDur,
                            exportWidth = exportWidth,
                            exportHeight = exportHeight,
                            outputFile = outroFile,
                            destinationParent = destinationParent,
                            tempFiles = taskOwnedTempFiles,
                            statusText = "마지막 화면 생성 중…",
                            safeProgress = ::safeProgress,
                            progressStart = chunkPhaseEnd + (0.85f - chunkPhaseEnd) * 0.5f,
                            progressEnd = 0.85f,
                            onTransformerCreated = { currentTransformer = it },
                        )
                        outroChunkFile = outroFile
                    } finally {
                        outroBitmap.recycle()
                    }
                }
            }

            val allIntermediateFiles = listOfNotNull(introChunkFile) + intermediateFiles + listOfNotNull(outroChunkFile)
            val introDuration = if (introChunkFile != null && introCard != null) introCard.clampedDuration() else 0.0
            val outroDuration = if (outroChunkFile != null && outroCard != null) outroCard.clampedDuration() else 0.0
            val effectiveTotalDuration = introDuration + effectiveScenes.sumOf { it.length } + outroDuration

            if (allIntermediateFiles.size == 1 && copiedMusicFile == null) {
                val singleChunk = allIntermediateFiles[0]
                withContext(Dispatchers.IO) {
                    singleChunk.copyTo(destination, overwrite = true)
                }
                safeProgress("영상으로 저장 중 (${effectiveScenes.size}/${effectiveScenes.size}개 연결 완료)", 1.0f)
            } else {
                // 청크 파일은 인코더/AAC 꼬리 때문에 계획보다 조금 길다(청크당 약 0.07초). Media3는 이어 붙일 때 파일 길이만큼 다음 항목을 밀기 때문에
                // 그대로 두면 청크 수만큼 쌓여 영상·음악이 계획 길이를 넘는다. 각 청크를 계획 길이로 잘라 연결 시각을 계획과 일치시킨다.
                val allIntermediateDurations = listOfNotNull(introChunkFile?.let { introDuration }) + intermediateDurations + listOfNotNull(outroChunkFile?.let { outroDuration })
                check(allIntermediateDurations.size == allIntermediateFiles.size) { "예고편 청크 길이 정보가 맞지 않습니다." }
                val concatEditedItems = allIntermediateFiles.mapIndexed { index, chunkFile ->
                    val inputUri = Uri.fromFile(chunkFile)
                    val input = MediaItem.Builder().setUri(inputUri)
                        .setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(0L)
                                .setEndPositionMs(Math.round(allIntermediateDurations[index] * 1000.0))
                                .build()
                        ).build()
                    EditedMediaItem.Builder(input)
                        .setFrameRate(30)
                        .build()
                }
                val videoSequence = EditedMediaItemSequence.withAudioAndVideoFrom(concatEditedItems)
                val sequences = mutableListOf<EditedMediaItemSequence>()
                sequences.add(videoSequence)

                if (copiedMusicFile != null && musicTrack != null) {
                    val duckingScenes = (if (introDuration > 0.0) listOf(TrailerScene(source = TrailerSource(id = -999, name = "intro", file = File(""), duration = introDuration, width = 0, height = 0, analysis = AudioAnalysisResult(emptyList(), 0.0), thumbnail = null, isImage = true), start = 0.0, length = introDuration)) else emptyList()) +
                        effectiveScenes +
                        (if (outroDuration > 0.0) listOf(TrailerScene(source = TrailerSource(id = -998, name = "outro", file = File(""), duration = outroDuration, width = 0, height = 0, analysis = AudioAnalysisResult(emptyList(), 0.0), thumbnail = null, isImage = true), start = 0.0, length = outroDuration)) else emptyList())

                    val musicSequence = buildMusicSequence(
                        context = context,
                        scenes = duckingScenes,
                        copiedMusicFile = copiedMusicFile,
                        musicTrack = musicTrack,
                        musicPolicy = musicPolicy,
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
                    outputPixels = exportWidth.toLong() * exportHeight,
                )
            }

            withContext(Dispatchers.IO) {
                check(destination.isFile && destination.length() > 0) { "예고편 파일을 만들지 못했습니다." }
                val reader = MediaMetadataRetriever()
                try {
                    reader.setDataSource(destination.absolutePath)
                    val containerDuration = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull()?.div(1000) ?: 0.0
                    val hasVideo = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
                    var videoTrackDuration = -1.0
                    var audioTrackDuration = -1.0
                    runCatching {
                        val extractor = android.media.MediaExtractor()
                        try {
                            extractor.setDataSource(destination.absolutePath)
                            for (index in 0 until extractor.trackCount) {
                                val format = extractor.getTrackFormat(index)
                                val mime = format.getString(android.media.MediaFormat.KEY_MIME).orEmpty()
                                if (!format.containsKey(android.media.MediaFormat.KEY_DURATION)) continue
                                val seconds = format.getLong(android.media.MediaFormat.KEY_DURATION) / 1_000_000.0
                                if (mime.startsWith("video/") && videoTrackDuration < 0) videoTrackDuration = seconds
                                else if (mime.startsWith("audio/") && audioTrackDuration < 0) audioTrackDuration = seconds
                            }
                        } finally { extractor.release() }
                    }
                    val videoDuration = if (videoTrackDuration > 0) videoTrackDuration else containerDuration
                    val videoTolerance = maxOf(.5, allIntermediateFiles.size * .05)
                    // AAC 프레임 패딩이 청크마다 누적되므로 오디오/컨테이너는 비디오보다 넓은 한계를 허용한다.
                    val audioTolerance = maxOf(1.0, allIntermediateFiles.size * .1)
                    // 음악 한 번 재생은 음악이 영상보다 짧으면 거기서 끝나는 것이 정상이다. 반복·길이 맞춤은 영상 끝까지 이어져야 한다.
                    val expectedAudioMin = if (musicTrack != null && musicPolicy == TrailerMusicPolicy.SINGLE) {
                        min(musicTrack.durationSeconds, effectiveTotalDuration)
                    } else {
                        effectiveTotalDuration
                    }
                    val videoOk = hasVideo && abs(videoDuration - effectiveTotalDuration) <= videoTolerance
                    val containerOk = abs(containerDuration - effectiveTotalDuration) <= audioTolerance
                    val audioOk = audioTrackDuration < 0 ||
                        (audioTrackDuration >= expectedAudioMin - audioTolerance && audioTrackDuration <= effectiveTotalDuration + audioTolerance)
                    if (!videoOk || !containerOk || !audioOk) {
                        android.util.Log.w(
                            "TrailerExporter",
                            "duration check failed videoOk=$videoOk containerOk=$containerOk audioOk=$audioOk " +
                                "expected=${"%.2f".format(effectiveTotalDuration)} expectedAudioMin=${"%.2f".format(expectedAudioMin)} " +
                                "video=${"%.2f".format(videoDuration)}(tol ${"%.2f".format(videoTolerance)}) " +
                                "audio=${"%.2f".format(audioTrackDuration)} container=${"%.2f".format(containerDuration)}(tol ${"%.2f".format(audioTolerance)}) " +
                                "chunks=${allIntermediateFiles.size} scenes=${effectiveScenes.size} policy=$musicPolicy",
                        )
                    }
                    check(videoOk && containerOk && audioOk) { "완성 영상의 길이를 확인하지 못했습니다. 다시 만들어 주세요." }
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
