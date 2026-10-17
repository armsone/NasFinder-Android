package com.armsone.nasfinder.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/** Chooses one representation only, during edit preparation. Original/library URIs are read-only. */
internal object TrailerMotionSelection {
    data class Selection(val file: File, val isImage: Boolean)
    private data class Sample(val timeUs: Long, val measurement: TrailerMotionMeasurement)

    suspend fun choose(context: Context, uri: Uri, primary: File): Selection = withContext(Dispatchers.IO) {
        val unchanged = Selection(primary, true)
        var video: File? = null
        var frameFile: File? = null
        try {
            val range = LocalMotionPhotoReader.inspect(context, uri) ?: return@withContext unchanged
            coroutineContext.ensureActive()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(primary.absolutePath, bounds)
            var primaryWidth = bounds.outWidth
            var primaryHeight = bounds.outHeight
            val still = if (Build.VERSION.SDK_INT >= 28) ImageDecoder.decodeBitmap(ImageDecoder.createSource(primary)) { decoder, info, _ ->
                primaryWidth = info.size.width; primaryHeight = info.size.height
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val scale = minOf(1f, 256f / max(info.size.width, info.size.height))
                decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
            } else BitmapFactory.decodeFile(primary.absolutePath, BitmapFactory.Options().apply { inSampleSize = (max(bounds.outWidth, bounds.outHeight) / 256).coerceAtLeast(1) })
            if (still == null) return@withContext unchanged
            val primaryMeasurement = try { measureTrailerMotionBitmap(still) }
            finally { still.recycle() }
            video = LocalMotionPhotoReader.extractPreview(context, uri, range) { }
            val reader = MediaMetadataRetriever()
            try {
                reader.setDataSource(video!!.absolutePath)
                val duration = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return@withContext unchanged
                val width = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toLongOrNull() ?: 0L
                val height = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toLongOrNull() ?: 0L
                if (duration !in 800..8_000 || width <= 0 || height <= 0) return@withContext unchanged
                val samples = mutableListOf<Sample>()
                for (fraction in listOf(.1, .3, .5, .7, .9)) {
                    coroutineContext.ensureActive()
                    val time = (duration * 1000 * fraction).toLong()
                    val bitmap = if (Build.VERSION.SDK_INT >= 27) reader.getScaledFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST, 256, 256) else null
                    if (bitmap != null) try { samples += Sample(time, measureTrailerMotionBitmap(bitmap)) } finally { bitmap.recycle() }
                }
                if (samples.size < 4) return@withContext unchanged
                val best = samples.maxBy { it.measurement.quality }
                val qualities = samples.map { it.measurement.quality }.sorted()
                val median = qualities[qualities.size / 2]
                val residuals = samples.zipWithNext { a, b -> trailerMotionResidual(a.measurement.fingerprint, b.measurement.fingerprint) }
                val rawChanges = samples.zipWithNext { a, b -> a.measurement.fingerprint.indices.sumOf { abs(a.measurement.fingerprint[it] - b.measurement.fingerprint[it]).toDouble() } / a.measurement.fingerprint.size / 255.0 }
                val residualMedian = residuals.sorted()[residuals.size / 2]
                val rawMedian = rawChanges.sorted()[rawChanges.size / 2]
                val relativeResolution = width * height / (primaryWidth.toDouble().coerceAtLeast(width.toDouble()) * primaryHeight.toDouble().coerceAtLeast(height.toDouble()))
                val average = qualities.average()
                val stableQuality = samples.count { it.measurement.quality >= primaryMeasurement.quality - .10 } >= 4 &&
                    average >= primaryMeasurement.quality - .035 && samples.map { it.measurement.exposure }.average() >= .35 &&
                    samples.map { it.measurement.sharpness }.average() >= max(.008, primaryMeasurement.sharpness * .60)
                val usefulMotion = residualMedian in .008..0.10 && rawMedian <= .16 && residualMedian >= rawMedian * .25 && rawChanges.max() <= .25
                val consistentMotion = stableQuality && usefulMotion && average >= primaryMeasurement.quality + .02 && qualities.last() - qualities.first() < .06
                val frameWins = !consistentMotion && best.measurement.quality >= primaryMeasurement.quality + .08 && relativeResolution >= .20 && best.measurement.exposure >= .35 && best.measurement.sharpness >= primaryMeasurement.sharpness * .80
                val videoWins = !frameWins && stableQuality && usefulMotion && hasVideoDecoder(video!!)

                val mode: String
                val selected = when {
                    frameWins -> {
                        coroutineContext.ensureActive()
                        val full = reader.getFrameAtTime(best.timeUs, MediaMetadataRetriever.OPTION_CLOSEST) ?: return@withContext unchanged
                        frameFile = File.createTempFile("motion-frame-", ".jpg", primary.parentFile)
                        try { frameFile!!.outputStream().use { check(full.compress(Bitmap.CompressFormat.JPEG, 95, it)) } } finally { full.recycle() }
                        mode = "peak_frame"
                        Selection(frameFile!!, true)
                    }
                    videoWins -> {
                        val stagedVideo = File.createTempFile("motion-video-", ".mp4", primary.parentFile)
                        try {
                            if (!video!!.renameTo(stagedVideo)) { video!!.copyTo(stagedVideo, overwrite = true); video!!.delete() }
                        } catch (failure: Throwable) { stagedVideo.delete(); throw failure }
                        video = null
                        frameFile = stagedVideo
                        mode = if (consistentMotion) "consistent_motion" else "usable_motion"
                        Selection(stagedVideo, false)
                    }
                    else -> { mode = "representative_fallback"; unchanged }
                }
                coroutineContext.ensureActive()
                // Counts/scores only: never URI, filename, account, image content or face data.
                Log.d("TrailerMotionSelection", "mode=$mode samples=${samples.size} primary=${"%.3f".format(primaryMeasurement.quality)} median=${"%.3f".format(median)} residual=${"%.3f".format(residualMedian)} raw=${"%.3f".format(rawMedian)}")
                frameFile = null
                selected
            } finally { reader.release() }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { Log.d("TrailerMotionSelection", "mode=representative_fallback error=true"); unchanged }
        finally { video?.delete(); frameFile?.delete() }
    }
    private fun hasVideoDecoder(file: File): Boolean {
        val extractor = android.media.MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).any { index ->
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME)
                mime?.startsWith("video/") == true && android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format) != null
            }
        } catch (_: Exception) { false } finally { extractor.release() }
    }

}
