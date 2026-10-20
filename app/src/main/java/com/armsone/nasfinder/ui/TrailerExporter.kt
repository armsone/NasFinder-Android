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
        progress: (status: String, progress: Float?) -> Unit,
    ): File = withContext(Dispatchers.Main.immediate) {
        require(scenes.isNotEmpty()) { "이 장면 길이로 만들 수 있는 영상이 없습니다. 장면 길이를 줄여 주세요." }
        val totalClips = scenes.size
        val random = Random()
        val (exportWidth, exportHeight) = format.resolveDimensions(aspectSources)
        val presentation = Presentation.createForWidthAndHeight(exportWidth, exportHeight, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
        val musicGain = musicVolume.coerceIn(0f, 2f)

        fun videoEffects(scene: TrailerScene): List<androidx.media3.common.Effect> =
            sceneFramingVideoEffects(scene.fit, scene.focusX, scene.focusY, scene.source.width, scene.source.height, exportWidth, exportHeight)

        // Preprocess images at 60% resolution of output dimensions (even dimensions >= 2)
        val targetWidth = ((kotlin.math.round(exportWidth * 0.60) / 2.0).toInt() * 2).coerceAtLeast(2)
        val targetHeight = ((kotlin.math.round(exportHeight * 0.60) / 2.0).toInt() * 2).coerceAtLeast(2)
        val tempImages = mutableListOf<File>()

        val edited = try {
            withContext(Dispatchers.IO) {
                val preprocessedBySource = mutableMapOf<String, File>()
                scenes.mapIndexed { index, scene ->
                    val clipIndex = index + 1
                    withContext(Dispatchers.Main) {
                        progress("${clipIndex}/${totalClips}개 클립 연결 준비 중…", clipIndex.toFloat() / totalClips)
                    }
                    if (scene.source.isImage) {
                        val framingKey = "${scene.source.id}:${scene.fit}:${scene.focusX}:${scene.focusY}"
                        val preprocessedFile = preprocessedBySource.getOrPut(framingKey) {
                            val tempFile = File(destination.parentFile ?: context.cacheDir, "preprocessed-${scene.source.id}-${UUID.randomUUID()}.jpg")
                            tempImages.add(tempFile)
                            preprocessImage(scene.source.file, targetWidth, targetHeight, tempFile, scene.fit, scene.focusX, scene.focusY)
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
                            .setEffects(Effects(audioEffects, videoEffects(scene)))
                            .build()
                    }
                }
            }
        } catch (e: Exception) {
            tempImages.forEach { it.delete() }
            throw e
        }
        val videoSequence = EditedMediaItemSequence.withAudioAndVideoFrom(edited)

        val totalVideoDurationSeconds = scenes.sumOf { it.length }
        val sequences = mutableListOf<EditedMediaItemSequence>()
        sequences.add(videoSequence)

        // Copy the selected music into this export's own temp file so a later move/delete of the
        // original (e.g. on PhoneHard) can't affect an export already in progress.
        val copiedMusicFile: File? = if (musicTrack != null) {
            try {
                require(musicTrack.file.isFile) { "선택한 음악 파일을 찾을 수 없습니다. 음악을 다시 선택해 주세요." }
                require(musicTrack.durationSeconds > 0.0) { "선택한 음악의 길이를 확인할 수 없습니다. 음악을 다시 선택해 주세요." }
                withContext(Dispatchers.IO) {
                    val sourceExt = musicTrack.file.extension.takeIf { it.isNotBlank() && it.all(Char::isLetterOrDigit) } ?: "tmp"
                    val copy = File(destination.parentFile ?: context.cacheDir, "music-${UUID.randomUUID()}.$sourceExt")
                    tempImages.add(copy)
                    musicTrack.file.copyTo(copy, overwrite = true)
                    copy
                }
            } catch (e: Exception) {
                tempImages.forEach { it.delete() }
                throw e
            }
        } else null

        val scope = CoroutineScope(currentCoroutineContext())
        var poll: Job? = null
        var transformer: Transformer? = null
        try {
        if (copiedMusicFile != null) {
            val musicDurationSeconds = musicTrack!!.durationSeconds
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
                // Loop music until matching total video duration
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
                // FIT_MUSIC_LENGTH: repeat music musicFitCount times, trimming tail to total video duration
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
                // SINGLE / MANUAL: play music once, trimmed at video end; after music ends, original sound only
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
            if (musicItems.isNotEmpty()) {
                sequences.add(EditedMediaItemSequence.withAudioFrom(musicItems))
            }
        }

        val composition = Composition.Builder(sequences)
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL).build()
            suspendCancellableCoroutine<Unit> { continuation ->
                val instance = Transformer.Builder(context).setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
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
                transformer = instance
                continuation.invokeOnCancellation {
                    poll?.cancel()
                    if (Looper.myLooper() == Looper.getMainLooper()) instance.cancel()
                    else Handler(Looper.getMainLooper()).post { instance.cancel() }
                }
                progress("영상으로 저장 중 (${totalClips}/${totalClips}개 연결 완료)", 0f)
                instance.start(composition, destination.absolutePath)
                poll = scope.launch {
                    val holder = ProgressHolder()
                    while (isActive) {
                        if (instance.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                            val p = holder.progress / 100f
                            progress("영상으로 저장 중 (${totalClips}/${totalClips}개 연결 완료)", p)
                        }
                        delay(250)
                    }
                }
            }
            withContext(Dispatchers.IO) {
                check(destination.isFile && destination.length() > 0) { "예고편 파일을 만들지 못했습니다." }
                val reader = MediaMetadataRetriever()
                try {
                    reader.setDataSource(destination.absolutePath)
                    val duration = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull()?.div(1000) ?: 0.0
                    check(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes" && abs(duration - scenes.sumOf { it.length }) <= maxOf(.5, scenes.size * .05)) { "완성 영상의 길이를 확인하지 못했습니다. 다시 만들어 주세요." }
                } finally { reader.release() }
            }
            destination
        } finally {
            poll?.cancel()
            transformer?.cancel()
            tempImages.forEach { it.delete() }
        }
    }

    private fun preprocessImage(source: File, targetW: Int, targetH: Int, output: File, fit: SceneFit = SceneFit.FILL, focusX: Float = 0f, focusY: Float = 0f) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "이미지를 읽을 수 없습니다: ${source.name}" }

        val exif = ExifInterface(source.absolutePath)
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val isRotated90 = orientation == ExifInterface.ORIENTATION_ROTATE_90 || orientation == ExifInterface.ORIENTATION_ROTATE_270

        val (rawW, rawH) = if (isRotated90) bounds.outHeight to bounds.outWidth else bounds.outWidth to bounds.outHeight
        val sample = maxOf(1, minOf(rawW / targetW, rawH / targetH))
        val samplePowerOfTwo = Integer.highestOneBit(sample).coerceAtLeast(1)

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = samplePowerOfTwo
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = checkNotNull(BitmapFactory.decodeFile(source.absolutePath, decodeOptions)) {
            "이미지 디코딩에 실패했습니다: ${source.name}"
        }

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
            throw e
        }

        val resultBitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(resultBitmap)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

            val srcW = orientedBitmap.width.toFloat()
            val srcH = orientedBitmap.height.toFloat()
            if (fit == SceneFit.FIT) {
                // Letterbox: whole photo visible on black.
                canvas.drawColor(android.graphics.Color.BLACK)
                val scale = minOf(targetW / srcW, targetH / srcH)
                val drawW = srcW * scale
                val drawH = srcH * scale
                val left = (targetW - drawW) / 2f
                val top = (targetH - drawH) / 2f
                canvas.drawBitmap(orientedBitmap, null, RectF(left, top, left + drawW, top + drawH), paint)
            } else {
                val scale = maxOf(targetW / srcW, targetH / srcH)
                val cropW = targetW / scale
                val cropH = targetH / scale
                // focus -1..1 moves the crop window from the left/top edge to the right/bottom edge.
                val cropX = (srcW - cropW) / 2f * (1f + focusX.coerceIn(-1f, 1f))
                val cropY = (srcH - cropH) / 2f * (1f + focusY.coerceIn(-1f, 1f))

                val srcRect = Rect(cropX.toInt(), cropY.toInt(), (cropX + cropW).toInt(), (cropY + cropH).toInt())
                val dstRect = Rect(0, 0, targetW, targetH)
                canvas.drawBitmap(orientedBitmap, srcRect, dstRect, paint)
            }

            FileOutputStream(output).use { fos ->
                resultBitmap.compress(Bitmap.CompressFormat.JPEG, 92, fos)
            }
        } finally {
            orientedBitmap.recycle()
            resultBitmap.recycle()
        }
    }
}
