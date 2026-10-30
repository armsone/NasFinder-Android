package com.armsone.nasfinder.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix as AndroidMatrix
import android.graphics.RectF
import android.opengl.Matrix
import android.util.LruCache
import androidx.annotation.OptIn
import androidx.exifinterface.media.ExifInterface
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlMatrixTransformation
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

enum class PhotoMotionMode {
    FIT_STATIC,
    MANUAL_FILL,
    PAN_HORIZONTAL,
    PAN_VERTICAL,
    STATIC_FILL,
}

data class PhotoMotionPlan(
    val mode: PhotoMotionMode,
    val isFit: Boolean,
    val cropWidthNorm: Float = 1f,
    val cropHeightNorm: Float = 1f,
    val startXNorm: Float = 0f,
    val startYNorm: Float = 0f,
    val endXNorm: Float = 0f,
    val endYNorm: Float = 0f,
    val startTranslateX: Float = 0f,
    val startTranslateY: Float = 0f,
    val endTranslateX: Float = 0f,
    val endTranslateY: Float = 0f,
) {
    fun progress(fraction: Float): Pair<Float, Float> {
        val p = fraction.coerceIn(0f, 1f)
        val eased = p * p * (3f - 2f * p)
        val x = startXNorm + (endXNorm - startXNorm) * eased
        val y = startYNorm + (endYNorm - startYNorm) * eased
        return x to y
    }

    fun glTranslation(fraction: Float): Pair<Float, Float> {
        val p = fraction.coerceIn(0f, 1f)
        val eased = p * p * (3f - 2f * p)
        val tx = startTranslateX + (endTranslateX - startTranslateX) * eased
        val ty = startTranslateY + (endTranslateY - startTranslateY) * eased
        return tx to ty
    }

    fun computeBiasAlignment(fraction: Float): Pair<Float, Float> {
        if (isFit) return 0f to 0f
        val (x, y) = progress(fraction)
        val biasX = if (cropWidthNorm < 0.9999f) {
            val xc = x + cropWidthNorm / 2f
            ((2f * xc - 1f) / (1f - cropWidthNorm)).coerceIn(-1f, 1f)
        } else {
            0f
        }
        val biasY = if (cropHeightNorm < 0.9999f) {
            val yc = y + cropHeightNorm / 2f
            ((2f * yc - 1f) / (1f - cropHeightNorm)).coerceIn(-1f, 1f)
        } else {
            0f
        }
        return biasX to biasY
    }
}

@OptIn(UnstableApi::class)
class PhotoMotionGlTransformation(
    private val plan: PhotoMotionPlan,
    private val durationUs: Long,
) : GlMatrixTransformation {
    private var baseTimeUs: Long = -1L

    override fun getGlMatrixArray(presentationTimeUs: Long): FloatArray {
        if (baseTimeUs < 0L) {
            baseTimeUs = presentationTimeUs
        }
        val elapsedUs = (presentationTimeUs - baseTimeUs).coerceAtLeast(0L)
        val progress = if (durationUs > 0) (elapsedUs.toFloat() / durationUs).coerceIn(0f, 1f) else 0f
        val (tx, ty) = plan.glTranslation(progress)

        val glMatrix = FloatArray(16)
        Matrix.setIdentityM(glMatrix, 0)
        Matrix.translateM(glMatrix, 0, tx, ty, 0f)
        return glMatrix
    }
}

internal data class PhotoAnalysisData(
    val protectedBounds: RectF?,
    val treatAsFitSafe: Boolean,
)

object TrailerPhotoMotionPlanner {
    private val analysisCache = LruCache<String, PhotoAnalysisData>(128)
    private val cleanupExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TrailerPhotoMotion-Cleanup").apply {
            isDaemon = true
        }
    }

    private class AnalysisResources(
        val bitmap: Bitmap,
        val faceDetector: com.google.mlkit.vision.face.FaceDetector?,
        val objectDetector: com.google.mlkit.vision.objects.ObjectDetector?,
    ) {
        private val pendingTasks = AtomicInteger(0)
        private val isDisposed = AtomicBoolean(false)
        private val needsCleanup = AtomicBoolean(false)

        fun registerTask(task: com.google.android.gms.tasks.Task<*>) {
            pendingTasks.incrementAndGet()
            task.addOnCompleteListener(cleanupExecutor) {
                if (pendingTasks.decrementAndGet() == 0 && needsCleanup.get()) {
                    disposeInternal()
                }
            }
        }

        fun release() {
            needsCleanup.set(true)
            if (pendingTasks.get() == 0) {
                disposeInternal()
            }
        }

        private fun disposeInternal() {
            if (isDisposed.compareAndSet(false, true)) {
                cleanupExecutor.execute {
                    try { bitmap.recycle() } catch (_: Throwable) {}
                    try { faceDetector?.close() } catch (_: Throwable) {}
                    try { objectDetector?.close() } catch (_: Throwable) {}
                }
            }
        }
    }

    private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitCancellable(): T =
        suspendCancellableCoroutine { continuation ->
            addOnSuccessListener(cleanupExecutor) { result ->
                if (continuation.isActive) continuation.resume(result)
            }
            addOnFailureListener(cleanupExecutor) { exception ->
                if (continuation.isActive) continuation.resumeWithException(exception)
            }
            addOnCanceledListener(cleanupExecutor) {
                if (continuation.isActive) continuation.cancel()
            }
        }

    private fun decodeOrientedBitmapForAnalysis(file: File, maxDimension: Int = 1280): Bitmap? {
        if (!file.exists() || !file.isFile || !file.canRead()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val exif = try {
            ExifInterface(file.absolutePath)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
        val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            ?: ExifInterface.ORIENTATION_NORMAL
        val isRotated90 = orientation == ExifInterface.ORIENTATION_ROTATE_90 || orientation == ExifInterface.ORIENTATION_ROTATE_270
        val (rawW, rawH) = if (isRotated90) bounds.outHeight to bounds.outWidth else bounds.outWidth to bounds.outHeight

        var sample = 1
        var w = rawW
        var h = rawH
        while (maxOf(w, h) > maxDimension) {
            sample *= 2
            w = rawW / sample
            h = rawH / sample
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sample.coerceAtLeast(1)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = try {
            BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        } ?: return null

        return try {
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
            if (e is CancellationException) throw e
            null
        }
    }

    private suspend fun analyzePhoto(file: File): PhotoAnalysisData = withContext(Dispatchers.Default) {
        val bitmap = decodeOrientedBitmapForAnalysis(file)
            ?: return@withContext PhotoAnalysisData(protectedBounds = null, treatAsFitSafe = true)

        var faceDetector: com.google.mlkit.vision.face.FaceDetector? = null
        var objectDetector: com.google.mlkit.vision.objects.ObjectDetector? = null
        var resources: AnalysisResources? = null

        try {
            faceDetector = FaceDetection.getClient(
                FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                    .build()
            )
            objectDetector = ObjectDetection.getClient(
                ObjectDetectorOptions.Builder()
                    .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
                    .enableMultipleObjects()
                    .build()
            )
            val res = AnalysisResources(bitmap, faceDetector, objectDetector)
            resources = res

            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val subjectRects = mutableListOf<RectF>()
            var detectionFailed = false

            try {
                val faceTask = faceDetector.process(inputImage)
                res.registerTask(faceTask)
                val faces = faceTask.awaitCancellable()
                for (face in faces) {
                    val box = face.boundingBox
                    val left = box.left.toFloat() / bitmap.width
                    val top = box.top.toFloat() / bitmap.height
                    val right = box.right.toFloat() / bitmap.width
                    val bottom = box.bottom.toFloat() / bitmap.height
                    if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite() ||
                        box.width() <= 0 || box.height() <= 0 || right <= left || bottom <= top
                    ) {
                        detectionFailed = true
                        break
                    }
                    subjectRects.add(
                        RectF(
                            left.coerceIn(0f, 1f),
                            top.coerceIn(0f, 1f),
                            right.coerceIn(0f, 1f),
                            bottom.coerceIn(0f, 1f),
                        )
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                detectionFailed = true
            }

            if (!detectionFailed) {
                try {
                    val objectTask = objectDetector.process(inputImage)
                    res.registerTask(objectTask)
                    val objects = objectTask.awaitCancellable()
                    for (obj in objects) {
                        val box = obj.boundingBox
                        val left = box.left.toFloat() / bitmap.width
                        val top = box.top.toFloat() / bitmap.height
                        val right = box.right.toFloat() / bitmap.width
                        val bottom = box.bottom.toFloat() / bitmap.height
                        if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite() ||
                            box.width() <= 0 || box.height() <= 0 || right <= left || bottom <= top
                        ) {
                            detectionFailed = true
                            break
                        }
                        subjectRects.add(
                            RectF(
                                left.coerceIn(0f, 1f),
                                top.coerceIn(0f, 1f),
                                right.coerceIn(0f, 1f),
                                bottom.coerceIn(0f, 1f),
                            )
                        )
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    detectionFailed = true
                }
            }

            if (detectionFailed) {
                return@withContext PhotoAnalysisData(protectedBounds = null, treatAsFitSafe = true)
            }

            if (subjectRects.isNotEmpty()) {
                var minL = 1f
                var minT = 1f
                var maxR = 0f
                var maxB = 0f
                for (rect in subjectRects) {
                    minL = minOf(minL, rect.left)
                    minT = minOf(minT, rect.top)
                    maxR = maxOf(maxR, rect.right)
                    maxB = maxOf(maxB, rect.bottom)
                }
                val padX = maxOf(0.04f, (maxR - minL) * 0.10f)
                val padY = maxOf(0.04f, (maxB - minT) * 0.10f)
                val protectedLeft = (minL - padX).coerceIn(0f, 1f)
                val protectedTop = (minT - padY).coerceIn(0f, 1f)
                val protectedRight = (maxR + padX).coerceIn(0f, 1f)
                val protectedBottom = (maxB + padY).coerceIn(0f, 1f)
                val bounds = RectF(protectedLeft, protectedTop, protectedRight, protectedBottom)
                PhotoAnalysisData(protectedBounds = bounds, treatAsFitSafe = false)
            } else {
                PhotoAnalysisData(protectedBounds = null, treatAsFitSafe = false)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            PhotoAnalysisData(protectedBounds = null, treatAsFitSafe = true)
        } finally {
            if (resources != null) {
                resources.release()
            } else {
                try { bitmap.recycle() } catch (_: Throwable) {}
                try { faceDetector?.close() } catch (_: Throwable) {}
                try { objectDetector?.close() } catch (_: Throwable) {}
            }
        }
    }

    private suspend fun getOrAnalyzePhoto(file: File): PhotoAnalysisData {
        val cacheKey = "${file.absolutePath}:${file.lastModified()}:${file.length()}"
        val cached = synchronized(analysisCache) { analysisCache.get(cacheKey) }
        if (cached != null) return cached

        val fresh = analyzePhoto(file)
        if (!fresh.treatAsFitSafe) {
            synchronized(analysisCache) { analysisCache.put(cacheKey, fresh) }
        }
        return fresh
    }

    suspend fun planMotion(
        source: TrailerSource,
        fit: SceneFit,
        focusX: Float,
        focusY: Float,
        outputWidth: Int,
        outputHeight: Int,
        durationSeconds: Double,
    ): PhotoMotionPlan = withContext(Dispatchers.Default) {
        if (fit == SceneFit.FIT) {
            return@withContext PhotoMotionPlan(mode = PhotoMotionMode.FIT_STATIC, isFit = true)
        }
        if (focusX != 0f || focusY != 0f) {
            return@withContext PhotoMotionPlan(mode = PhotoMotionMode.MANUAL_FILL, isFit = false)
        }
        if (source.width <= 0 || source.height <= 0 || outputWidth <= 0 || outputHeight <= 0) {
            return@withContext PhotoMotionPlan(mode = PhotoMotionMode.FIT_STATIC, isFit = true)
        }

        val photoAspect = source.width.toDouble() / source.height
        val outputAspect = outputWidth.toDouble() / outputHeight
        if (!photoAspect.isFinite() || !outputAspect.isFinite() || photoAspect <= 0.0 || outputAspect <= 0.0) {
            return@withContext PhotoMotionPlan(mode = PhotoMotionMode.FIT_STATIC, isFit = true)
        }

        val analysis = try {
            getOrAnalyzePhoto(source.file)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            PhotoAnalysisData(protectedBounds = null, treatAsFitSafe = true)
        }

        if (analysis.treatAsFitSafe) {
            return@withContext PhotoMotionPlan(mode = PhotoMotionMode.FIT_STATIC, isFit = true)
        }

        val protected = analysis.protectedBounds
        if (protected != null) {
            if (!protected.left.isFinite() || !protected.right.isFinite() ||
                !protected.top.isFinite() || !protected.bottom.isFinite() ||
                protected.left >= protected.right || protected.top >= protected.bottom ||
                protected.left < 0f || protected.right > 1f ||
                protected.top < 0f || protected.bottom > 1f
            ) {
                return@withContext PhotoMotionPlan(mode = PhotoMotionMode.FIT_STATIC, isFit = true)
            }
        }

        if (photoAspect >= outputAspect) {
            val cropW = (outputAspect / photoAspect).toFloat()
            if (!cropW.isFinite() || cropW <= 0f || cropW > 1f) {
                return@withContext PhotoMotionPlan(mode = PhotoMotionMode.FIT_STATIC, isFit = true)
            }
            if (protected != null) {
                val subjectW = protected.right - protected.left
                if (subjectW > cropW) {
                    return@withContext PhotoMotionPlan(mode = PhotoMotionMode.FIT_STATIC, isFit = true)
                }
            }

            val xMin = if (protected != null) maxOf(0f, protected.right - cropW) else 0f
            val xMax = if (protected != null) minOf(1f - cropW, protected.left) else 1f - cropW
            if (xMin > xMax) {
                return@withContext PhotoMotionPlan(mode = PhotoMotionMode.FIT_STATIC, isFit = true)
            }
            val delta = xMax - xMin

            val maxViewportRate = 0.15f
            val maxTravel = cropW * maxViewportRate * durationSeconds.toFloat()
            val travel = minOf(delta, maxTravel)

            if (durationSeconds <= 1.0 || delta < 0.005f || travel < 0.005f) {
                val x = (xMin + xMax) / 2f
                val xc = x + cropW / 2f
                val tx = 1f - 2f * xc
                return@withContext PhotoMotionPlan(
                    mode = PhotoMotionMode.STATIC_FILL,
                    isFit = false,
                    cropWidthNorm = cropW,
                    cropHeightNorm = 1f,
                    startXNorm = x,
                    startYNorm = 0f,
                    endXNorm = x,
                    endYNorm = 0f,
                    startTranslateX = tx,
                    startTranslateY = 0f,
                    endTranslateX = tx,
                    endTranslateY = 0f,
                )
            }

            val center = (xMin + xMax) / 2f
            val x0 = (center - travel / 2f).coerceIn(xMin, xMax)
            val x1 = (center + travel / 2f).coerceIn(xMin, xMax)

            val seed = source.id * 31 + source.name.hashCode()
            val forward = (seed and 1) == 0
            val (startX, endX) = if (forward) x0 to x1 else x1 to x0
            val startXc = startX + cropW / 2f
            val endXc = endX + cropW / 2f
            val startTx = 1f - 2f * startXc
            val endTx = 1f - 2f * endXc

            PhotoMotionPlan(
                mode = PhotoMotionMode.PAN_HORIZONTAL,
                isFit = false,
                cropWidthNorm = cropW,
                cropHeightNorm = 1f,
                startXNorm = startX,
                startYNorm = 0f,
                endXNorm = endX,
                endYNorm = 0f,
                startTranslateX = startTx,
                startTranslateY = 0f,
                endTranslateX = endTx,
                endTranslateY = 0f,
            )
        } else {
            val cropH = (photoAspect / outputAspect).toFloat()
            if (!cropH.isFinite() || cropH <= 0f || cropH > 1f) {
                return@withContext PhotoMotionPlan(mode = PhotoMotionMode.FIT_STATIC, isFit = true)
            }
            if (protected != null) {
                val subjectH = protected.bottom - protected.top
                if (subjectH > cropH) {
                    return@withContext PhotoMotionPlan(mode = PhotoMotionMode.FIT_STATIC, isFit = true)
                }
            }

            val yMin = if (protected != null) maxOf(0f, protected.bottom - cropH) else 0f
            val yMax = if (protected != null) minOf(1f - cropH, protected.top) else 1f - cropH
            if (yMin > yMax) {
                return@withContext PhotoMotionPlan(mode = PhotoMotionMode.FIT_STATIC, isFit = true)
            }
            val delta = yMax - yMin

            val maxViewportRate = 0.15f
            val maxTravel = cropH * maxViewportRate * durationSeconds.toFloat()
            val travel = minOf(delta, maxTravel)

            if (durationSeconds <= 1.0 || delta < 0.005f || travel < 0.005f) {
                val y = (yMin + yMax) / 2f
                val yc = y + cropH / 2f
                val ty = 2f * yc - 1f
                return@withContext PhotoMotionPlan(
                    mode = PhotoMotionMode.STATIC_FILL,
                    isFit = false,
                    cropWidthNorm = 1f,
                    cropHeightNorm = cropH,
                    startXNorm = 0f,
                    startYNorm = y,
                    endXNorm = 0f,
                    endYNorm = y,
                    startTranslateX = 0f,
                    startTranslateY = ty,
                    endTranslateX = 0f,
                    endTranslateY = ty,
                )
            }

            val center = (yMin + yMax) / 2f
            val y0 = (center - travel / 2f).coerceIn(yMin, yMax)
            val y1 = (center + travel / 2f).coerceIn(yMin, yMax)

            val seed = source.id * 31 + source.name.hashCode()
            val forward = (seed and 1) == 0
            val (startY, endY) = if (forward) y0 to y1 else y1 to y0
            val startYc = startY + cropH / 2f
            val endYc = endY + cropH / 2f
            val startTy = 2f * startYc - 1f
            val endTy = 2f * endYc - 1f

            PhotoMotionPlan(
                mode = PhotoMotionMode.PAN_VERTICAL,
                isFit = false,
                cropWidthNorm = 1f,
                cropHeightNorm = cropH,
                startXNorm = 0f,
                startYNorm = startY,
                endXNorm = 0f,
                endYNorm = endY,
                startTranslateX = 0f,
                startTranslateY = startTy,
                endTranslateX = 0f,
                endTranslateY = endTy,
            )
        }
    }
}
