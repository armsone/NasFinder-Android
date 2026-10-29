package com.armsone.nasfinder.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * HanYang photo quality ranking and similarity gates, ported from HanClip's
 * PhotoSortModel (quality score) and EditorViewModel (duplicate gates/translated
 * distance), applied here to allocate each photo's own trailer scene duration
 * instead of a uniform length. Runs fully on-device, no network.
 *
 * Call once after every TrailerSource is staged and sorted, before `prepared = true`;
 * the returned copies carry the stable `photoDurationRatio` used by TrailerPlan's
 * scene builder, the settings preview, export and saved drafts alike.
 */
suspend fun analyzeTrailerPhotos(application: Application, sources: List<TrailerSource>): List<TrailerSource> = withContext(Dispatchers.Default) {
    val photos = sources.filter { it.isImage }
    if (photos.isEmpty()) return@withContext sources

    val measurements = photos.associateBy({ it.id }) { source ->
        coroutineContext.ensureActive()
        measurePhoto(source)
    }

    val rankable = measurements.values.filter { it.quality != null && it.quality.isFinite() }
        .sortedWith(compareByDescending<PhotoMeasurement> { it.quality!! }.thenBy { it.sourceId })
    val qualities = rankable.map { it.quality!! }.sorted()
    val medianQuality = if (qualities.isEmpty()) 0.0 else qualities[qualities.size / 2]
    val baseRatioById = rankable.withIndex().associate { (rank, measurement) ->
        val normalizedRank = if (rankable.size <= 1) 1.0 else 1.0 - rank.toDouble() / (rankable.size - 1)
        val ordinary = ORDINARY_MIN_RATIO + (ORDINARY_MAX_RATIO - ORDINARY_MIN_RATIO) * normalizedRank
        val q = measurement.quality!!
        val bonus = if (normalizedRank >= 0.9 && q - medianQuality > 0.15) {
            0.30 * ((q - medianQuality - 0.15) / 0.35).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        measurement.sourceId to (ordinary + bonus)
    }

    // Serial transient DINO vectors for the entire photo batch. Only used as an additional
    // AND veto on top of the existing fingerprint similarity gate; an ordinary decode/model
    // failure anywhere in the batch yields a null map so every photo falls back to the exact
    // pre-existing fingerprint-only policy rather than mixing partial results, while cancellation
    // propagates/rethrows instead of being treated as a fallback.
    val visualEmbeddings: Map<Int, FloatArray>? = try {
        val result = LinkedHashMap<Int, FloatArray>(photos.size)
        for (source in photos) {
            coroutineContext.ensureActive()
            val embedding = HanYangVisualFeatures.computeEmbedding(application, source.file) ?: run {
                return@withContext finishPhotoRatios(sources, photos, measurements, baseRatioById, null)
            }
            result[source.id] = embedding
        }
        result
    } catch (interrupted: InterruptedException) {
        throw interrupted
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }

    finishPhotoRatios(sources, photos, measurements, baseRatioById, visualEmbeddings)
}

private suspend fun finishPhotoRatios(
    sources: List<TrailerSource>,
    photos: List<TrailerSource>,
    measurements: Map<Int, PhotoMeasurement>,
    baseRatioById: Map<Int, Double>,
    visualEmbeddings: Map<Int, FloatArray>?,
): List<TrailerSource> {
    val ratioById = photos.associate { source ->
        coroutineContext.ensureActive()
        source.id to photoDurationRatio(measurements[source.id], baseRatioById[source.id], measurements.values, visualEmbeddings)
    }
    return sources.map { source ->
        if (source.isImage) {
            source.copy(
                photoDurationRatio = ratioById[source.id] ?: DEFAULT_PHOTO_DURATION_RATIO,
                photoPolicyVersion = 2,
            )
        } else source
    }
}

private fun photoDurationRatio(
    measurement: PhotoMeasurement?,
    baseRatio: Double?,
    all: Collection<PhotoMeasurement>,
    visualEmbeddings: Map<Int, FloatArray>?,
): Double {
    val quality: Double = measurement?.quality?.takeIf { it.isFinite() } ?: return DEFAULT_PHOTO_DURATION_RATIO
    val fingerprint: IntArray = measurement.fingerprint ?: return DEFAULT_PHOTO_DURATION_RATIO
    val base: Double = baseRatio ?: return DEFAULT_PHOTO_DURATION_RATIO
    var strongest = base
    for (other in all) {
        if (other.sourceId == measurement.sourceId) continue
        val otherQuality = other.quality?.takeIf { it.isFinite() } ?: continue
        val otherFingerprint = other.fingerprint ?: continue
        val isOtherRepresentative = otherQuality > quality || (otherQuality == quality && other.sourceId < measurement.sourceId)
        if (!isOtherRepresentative) continue
        val closeness = similarityCloseness(fingerprint, measurement.aspectRatio, otherFingerprint, other.aspectRatio) ?: continue
        if (visualEmbeddings != null) {
            val lhs = visualEmbeddings[measurement.sourceId] ?: continue
            val rhs = visualEmbeddings[other.sourceId] ?: continue
            val distance = 1.0 - cosineDot(lhs, rhs)
            if (distance > DINO_DISTANCE_GATE) continue
        }
        strongest = min(strongest, max(MIN_PHOTO_DURATION_RATIO, base * (1 - closeness)))
    }
    return strongest
}

private fun cosineDot(lhs: FloatArray, rhs: FloatArray): Double {
    var sum = 0.0
    for (i in lhs.indices) sum += lhs[i].toDouble() * rhs[i].toDouble()
    return sum
}

private const val ORDINARY_MIN_RATIO = .10
private const val ORDINARY_MAX_RATIO = .95
private const val FINGERPRINT_DIMENSION = 16
private const val ASPECT_RATIO_GATE = .06
private const val LUMINANCE_DISTANCE_GATE = 28.0
private const val STRUCTURE_DISTANCE_GATE = 30.0
private const val MEAN_BRIGHTNESS_GATE = 28.0
private const val DOWNSAMPLE_LIMIT = 768
private const val DINO_DISTANCE_GATE = 0.06911703708590078

private class PhotoMeasurement(
    val sourceId: Int,
    val quality: Double?,
    val fingerprint: IntArray?,
    val aspectRatio: Double,
)

private fun measurePhoto(source: TrailerSource): PhotoMeasurement {
    val aspectRatio = source.width.toDouble() / source.height.coerceAtLeast(1)
    val bitmap = decodeDownsampled(source) ?: return PhotoMeasurement(source.id, null, null, aspectRatio)
    return try {
        val quality = qualityScore(bitmap, source.width.toLong() * source.height.toLong())
        val fingerprint = grayscaleFingerprint(bitmap)
        PhotoMeasurement(source.id, quality, fingerprint, aspectRatio)
    } catch (e: Exception) {
        PhotoMeasurement(source.id, null, null, aspectRatio)
    } finally {
        // source.thumbnail is shared with the UI; only recycle bitmaps decoded here.
        if (bitmap !== source.thumbnail) bitmap.recycle()
    }
}

private fun decodeDownsampled(source: TrailerSource): Bitmap? {
    // Reuse the already-downsampled UI thumbnail when available: it already satisfies the
    // "<=768px, no full-resolution decode" HanYang constraint and avoids a second decode pass.
    source.thumbnail?.let { return it }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching { BitmapFactory.decodeFile(source.file.absolutePath, bounds) }
    val longestSide = max(bounds.outWidth, bounds.outHeight)
    if (longestSide <= 0) return null
    var sampleSize = 1
    while (longestSide / (sampleSize * 2) >= DOWNSAMPLE_LIMIT) sampleSize *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize; inPreferredConfig = Bitmap.Config.ARGB_8888 }
    return runCatching { BitmapFactory.decodeFile(source.file.absolutePath, options) }.getOrNull()
}

// HanYang base quality: sharpness from Laplacian-of-luminance variance, exposure from mean
// brightness distance to mid-grey, plus a mild resolution bonus from the ORIGINAL pixel count.
private fun photoQualityComponents(bitmap: Bitmap): Triple<Double, Double, Double> {
    val w = bitmap.width; val h = bitmap.height
    val colors = IntArray(w * h)
    bitmap.getPixels(colors, 0, w, 0, 0, w, h)
    val gray = DoubleArray(colors.size) { i ->
        val c = colors[i]
        (0.2126 * ((c shr 16) and 255) + 0.7152 * ((c shr 8) and 255) + 0.0722 * (c and 255)) / 255.0
    }
    var sum = 0.0; var square = 0.0; var n = 0
    for (y in 1 until h - 1) for (x in 1 until w - 1) {
        val i = y * w + x
        val lap = gray[i - 1] + gray[i + 1] + gray[i - w] + gray[i + w] - 4 * gray[i]
        sum += lap; square += lap * lap; n++
    }
    val variance = if (n > 0) max(0.0, square / n - (sum / n) * (sum / n)) else 0.0
    val sharpness = min(1.0, variance * 20)
    val brightness = colors.sumOf { c -> (((c shr 16) and 255) + ((c shr 8) and 255) + (c and 255)) / 765.0 } / colors.size
    val exposure = max(0.0, 1.0 - abs(brightness - 0.5) * 2)
    return Triple(sharpness, exposure, brightness)
}

private fun qualityScore(bitmap: Bitmap, originalPixels: Long): Double {
    val (sharpness, exposure, _) = photoQualityComponents(bitmap)
    return sharpness * .4 + exposure * .2 + ln(max(1L, originalPixels).toDouble()) / 100
}

private const val BLACK_GATE_BRIGHTNESS = 0.03
private const val FRAME_PROXY_LONG_EDGE = 64

/**
 * Picks the sharpest/best-exposed non-near-black frame among candidate decoded frames
 * (deterministic anchor-first tie-break, i.e. index 0 wins ties). Returns null if every
 * candidate is unreadable or gated as near-black, so the caller keeps its existing thumbnail.
 */
internal fun selectBestVideoFrameIndex(bitmaps: List<Bitmap?>): Int? {
    var bestIndex: Int? = null
    var bestScore = Double.NEGATIVE_INFINITY
    for ((index, bitmap) in bitmaps.withIndex()) {
        if (bitmap == null) continue
        val (_, exposure, brightness) = try { photoQualityComponents(bitmap) } catch (e: Exception) { continue }
        if (brightness < BLACK_GATE_BRIGHTNESS) continue
        val sharpness = try { frameSharpness(bitmap) } catch (e: Exception) { continue }
        val score = sharpness * .7 + exposure * .3
        if (score > bestScore) {
            bestScore = score
            bestIndex = index
        }
    }
    return bestIndex
}

// Scales the candidate frame to a long-edge-64 gray proxy (never upscaling an already-tiny
// frame), then returns a bounded 4-neighbor Laplacian variance over its interior pixels.
private fun frameSharpness(bitmap: Bitmap): Double {
    val w = bitmap.width; val h = bitmap.height
    if (w <= 0 || h <= 0) return 0.0
    val longEdge = max(w, h)
    val scale = if (longEdge > FRAME_PROXY_LONG_EDGE) FRAME_PROXY_LONG_EDGE.toDouble() / longEdge else 1.0
    val targetW = max(1, (w * scale).toInt())
    val targetH = max(1, (h * scale).toInt())
    val scaled = if (targetW == w && targetH == h) bitmap else Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
    try {
        val sw = scaled.width; val sh = scaled.height
        val colors = IntArray(sw * sh)
        scaled.getPixels(colors, 0, sw, 0, 0, sw, sh)
        val gray = DoubleArray(colors.size) { i ->
            val c = colors[i]
            (0.2126 * ((c shr 16) and 255) + 0.7152 * ((c shr 8) and 255) + 0.0722 * (c and 255)) / 255.0
        }
        var sum = 0.0; var square = 0.0; var n = 0
        for (y in 1 until sh - 1) for (x in 1 until sw - 1) {
            val i = y * sw + x
            val lap = gray[i - 1] + gray[i + 1] + gray[i - sw] + gray[i + sw] - 4 * gray[i]
            sum += lap; square += lap * lap; n++
        }
        val variance = if (n > 0) max(0.0, square / n - (sum / n) * (sum / n)) else 0.0
        return min(1.0, variance * 20)
    } finally {
        if (scaled !== bitmap) scaled.recycle()
    }
}

private fun grayscaleFingerprint(bitmap: Bitmap): IntArray {
    val scaled = Bitmap.createScaledBitmap(bitmap, FINGERPRINT_DIMENSION, FINGERPRINT_DIMENSION, true)
    val fingerprint = IntArray(FINGERPRINT_DIMENSION * FINGERPRINT_DIMENSION)
    for (y in 0 until FINGERPRINT_DIMENSION) for (x in 0 until FINGERPRINT_DIMENSION) {
        val pixel = scaled.getPixel(x, y)
        val r = (pixel shr 16) and 255; val g = (pixel shr 8) and 255; val b = pixel and 255
        fingerprint[y * FINGERPRINT_DIMENSION + x] = (r * 0.299 + g * 0.587 + b * 0.114).toInt().coerceIn(0, 255)
    }
    if (scaled !== bitmap) scaled.recycle()
    return fingerprint
}

private fun fingerprintMean(values: IntArray): Double = if (values.isEmpty()) 0.0 else values.sum().toDouble() / values.size

// Best-of-25 translated distance (±2px in both axes), matching HanClip's duplicate gate.
private fun translatedDistance(lhs: IntArray, rhs: IntArray, subtractMean: Boolean, maxOffset: Int = 2): Double {
    if (lhs.size != rhs.size || lhs.isEmpty()) return Double.POSITIVE_INFINITY
    val dimension = FINGERPRINT_DIMENSION
    val lhsMean = if (subtractMean) fingerprintMean(lhs) else 0.0
    val rhsMean = if (subtractMean) fingerprintMean(rhs) else 0.0
    var best = lhs.indices.sumOf { i -> abs((lhs[i] - lhsMean) - (rhs[i] - rhsMean)) } / lhs.size
    for (yOffset in -maxOffset..maxOffset) for (xOffset in -maxOffset..maxOffset) {
        if (xOffset == 0 && yOffset == 0) continue
        var total = 0.0; var compared = 0
        for (y in 0 until dimension) for (x in 0 until dimension) {
            val sx = x + xOffset; val sy = y + yOffset
            if (sx !in 0 until dimension || sy !in 0 until dimension) continue
            total += abs((lhs[y * dimension + x] - lhsMean) - (rhs[sy * dimension + sx] - rhsMean))
            compared++
        }
        if (compared > 0) best = min(best, total / compared)
    }
    return best
}

// Returns closeness in 0..1 if the two fingerprints/aspect ratios pass every HanClip duplicate
// gate (aspect ratio, translated luminance distance, translated structure distance, mean
// brightness), or null if any gate fails (not treated as similar at all).
private fun similarityCloseness(fingerprint: IntArray, aspectRatio: Double, otherFingerprint: IntArray, otherAspectRatio: Double): Double? {
    if (abs(aspectRatio - otherAspectRatio) > ASPECT_RATIO_GATE) return null
    val luminanceDistance = translatedDistance(fingerprint, otherFingerprint, subtractMean = false)
    if (luminanceDistance > LUMINANCE_DISTANCE_GATE) return null
    val structureDistance = translatedDistance(fingerprint, otherFingerprint, subtractMean = true)
    if (structureDistance > STRUCTURE_DISTANCE_GATE) return null
    val meanDelta = abs(fingerprintMean(fingerprint) - fingerprintMean(otherFingerprint))
    if (meanDelta > MEAN_BRIGHTNESS_GATE) return null
    val closeness = 1 - (luminanceDistance / LUMINANCE_DISTANCE_GATE + structureDistance / STRUCTURE_DISTANCE_GATE + meanDelta / MEAN_BRIGHTNESS_GATE) / 3
    return closeness.coerceIn(0.0, 1.0)
}

/**
 * Photo selection reuses these exact HanYang measurements and duplicate gates (no change to trailer analysis):
 * sharpness/exposure in 0..1, a 16×16 luminance fingerprint, and the closeness test between two photos.
 */
internal class PhotoSelectionMetrics(val sharpness: Double, val exposure: Double, val fingerprint: IntArray)
internal fun measurePhotoSelectionBitmap(bitmap: Bitmap): PhotoSelectionMetrics {
    val (sharpness, exposure, _) = photoQualityComponents(bitmap)
    return PhotoSelectionMetrics(sharpness, exposure, grayscaleFingerprint(bitmap))
}
/** Closeness 0..1 when the pair passes every duplicate gate, otherwise null (not similar at all). */
internal fun photoSelectionCloseness(first: PhotoSelectionMetrics, firstAspect: Double, second: PhotoSelectionMetrics, secondAspect: Double): Double? =
    similarityCloseness(first.fingerprint, firstAspect, second.fingerprint, secondAspect)
internal const val PHOTO_SELECTION_DINO_DISTANCE_GATE = DINO_DISTANCE_GATE
internal fun photoSelectionCosineDistance(first: FloatArray, second: FloatArray): Double = 1.0 - cosineDot(first, second)

/** Same local HanYang quality/fingerprint used by motion representation selection. */
internal data class TrailerMotionMeasurement(val quality: Double, val sharpness: Double, val exposure: Double, val fingerprint: IntArray)
internal fun measureTrailerMotionBitmap(bitmap: Bitmap): TrailerMotionMeasurement {
    val (sharpness, exposure, _) = photoQualityComponents(bitmap)
    return TrailerMotionMeasurement(.65 * sharpness + .35 * exposure, sharpness, exposure, grayscaleFingerprint(bitmap))
}
internal fun trailerMotionResidual(first: IntArray, second: IntArray): Double =
    translatedDistance(first, second, subtractMean = true, maxOffset = 1) / 255.0
