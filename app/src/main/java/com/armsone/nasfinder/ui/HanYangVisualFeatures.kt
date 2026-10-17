package com.armsone.nasfinder.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.hanai.onnx.DinoImageFeatures
import com.hanai.onnx.DinoImagePreprocessor
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * Computes a transient DINO visual feature vector for a single staged trailer photo file,
 * used only as an additional AND veto on top of the existing fingerprint similarity gate in
 * TrailerPhotoAnalysis. Any failure (decode, model, cancellation) yields null so the caller
 * falls back to the exact pre-existing fingerprint-only policy.
 */
internal object HanYangVisualFeatures {

    private const val MODEL_ASSET_PATH = "hanai/dinov2-small.onnx"
    private const val MODEL_FILE_NAME = "dinov2-small.onnx"
    private const val EXPECTED_MODEL_SIZE = 88400292L
    private const val EXPECTED_MODEL_SHA256 = "6b3e37fdea1393dda0ea0312f5f19b20829ca96447ab5b8b3c92a36777fc431d"

    private const val MAX_DIMENSION = 16384
    private const val MAX_PIXELS = 16_000_000L
    private const val MIN_DECODE_SHORT_EDGE = 256
    private const val MAX_DECODE_LONG_EDGE = 2048

    private val sessionLock = Any()
    @Volatile private var cachedSession: DinoImageFeatures? = null

    fun computeEmbedding(application: Application, file: File): FloatArray? {
        return try {
            val session = obtainSession(application) ?: return null
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Thread interrupted before visual feature decode")
            val decoded = decodeRgb8(file) ?: return null
            val (rgb, width, height) = decoded
            val input = DinoImagePreprocessor.normalizedInput(rgb, width, height)
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Thread interrupted before visual feature inference")
            synchronized(sessionLock) { session.infer(input) }
        } catch (interrupted: InterruptedException) {
            throw interrupted
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
    }

    private fun obtainSession(application: Application): DinoImageFeatures? {
        cachedSession?.let { return it }
        synchronized(sessionLock) {
            cachedSession?.let { return it }
            val modelFile = ensureModelFile(application) ?: return null
            val canonicalModelFile = canonicalWithinPrivateDir(application, modelFile) ?: return null
            val session = try {
                DinoImageFeatures.open(canonicalModelFile)
            } catch (interrupted: InterruptedException) {
                throw interrupted
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                return null
            }
            cachedSession = session
            return session
        }
    }

    private fun ensureModelFile(application: Application): File? {
        return try {
            val directory = File(application.noBackupFilesDir, "hanai").apply { mkdirs() }
            val target = File(directory, MODEL_FILE_NAME)
            if (verifyPublishedModel(application, target)) {
                return target
            }
            val staging = File(directory, "$MODEL_FILE_NAME.tmp-${UUID.randomUUID()}")
            try {
                application.assets.open(MODEL_ASSET_PATH).use { input ->
                    FileOutputStream(staging).use { output ->
                        val digest = MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(65536)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            total += read
                        }
                        output.fd.sync()
                        check(total == EXPECTED_MODEL_SIZE) { "Model asset size mismatch" }
                        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                        check(actualHash.equals(EXPECTED_MODEL_SHA256, ignoreCase = true)) {
                            "Model asset SHA-256 mismatch"
                        }
                    }
                }
                check(staging.renameTo(target)) { "Failed to publish model asset" }
            } finally {
                staging.delete()
            }
            if (!verifyPublishedModel(application, target)) return null
            target
        } catch (interrupted: InterruptedException) {
            throw interrupted
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
    }

    // Re-verifies the published model by streaming size+SHA-256 again (not just a size check),
    // so a corrupted or tampered on-disk file can never be silently reused for inference.
    private fun verifyPublishedModel(application: Application, target: File): Boolean {
        val canonical = canonicalWithinPrivateDir(application, target) ?: return false
        if (canonical.length() != EXPECTED_MODEL_SIZE) return false
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        try {
            canonical.inputStream().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    digest.update(buffer, 0, read)
                }
            }
        } catch (interrupted: InterruptedException) {
            throw interrupted
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return false
        }
        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
        return actualHash.equals(EXPECTED_MODEL_SHA256, ignoreCase = true)
    }

    // Canonicalizes both the app-private base directory and the candidate file, then requires
    // the canonical file to be a regular file contained within the canonical base directory, so
    // a symlink/alias (e.g. under Android's /data/user/0 ancestor) can never escape containment.
    private fun canonicalWithinPrivateDir(application: Application, file: File): File? {
        return try {
            val base = File(application.noBackupFilesDir, "hanai").canonicalFile
            val canonical = file.canonicalFile
            if (!canonical.isFile) return null
            val basePath = base.path + File.separator
            if (!(canonical.path + File.separator).startsWith(basePath)) return null
            canonical
        } catch (interrupted: InterruptedException) {
            throw interrupted
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
    }

    private data class DecodedRgb(val rgb: ByteArray, val width: Int, val height: Int)

    private fun decodeRgb8(file: File): DecodedRgb? {
        val bitmap = decodeBoundedBitmap(file) ?: return null
        return try {
            DecodedRgb(bitmapToRgb8(bitmap), bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeBoundedBitmap(file: File): Bitmap? {
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            decodeBoundedBitmapModern(file)
        } else {
            decodeBoundedBitmapLegacy(file)
        }
        return bitmap?.let(::validateDecodedBounds)
    }

    // Enforces the decoded-RGB-buffer contract (<=16MP, long edge<=2048, short edge>=256) on the
    // actual output bitmap, independent of the source's encoded pixel count.
    private fun validateDecodedBounds(bitmap: Bitmap): Bitmap? {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) {
            bitmap.recycle()
            return null
        }
        if (max(width, height) > MAX_DECODE_LONG_EDGE ||
            min(width, height) < MIN_DECODE_SHORT_EDGE ||
            width.toLong() * height.toLong() > MAX_PIXELS
        ) {
            bitmap.recycle()
            return null
        }
        return bitmap
    }

    private fun decodeBoundedBitmapModern(file: File): Bitmap? {
        return try {
            runCatching {
                val source = ImageDecoder.createSource(file)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = false
                    decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                    val srcWidth = info.size.width
                    val srcHeight = info.size.height
                    if (srcWidth <= 0 || srcHeight <= 0) {
                        throw IllegalArgumentException("Source image has invalid bounds")
                    }
                    if (max(srcWidth, srcHeight) > MAX_DIMENSION) {
                        throw IllegalArgumentException("Source image exceeds bounded decode limits")
                    }
                    val (targetWidth, targetHeight) = targetDecodeSize(srcWidth, srcHeight)
                        ?: throw IllegalArgumentException("Source image cannot satisfy decode bounds")
                    decoder.setTargetSize(targetWidth, targetHeight)
                }
            }.getOrNull()
        } catch (interrupted: InterruptedException) {
            throw interrupted
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    private fun decodeBoundedBitmapLegacy(file: File): Bitmap? {
        try {
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, boundsOptions)
            val srcWidth = boundsOptions.outWidth
            val srcHeight = boundsOptions.outHeight
            if (srcWidth <= 0 || srcHeight <= 0) return null
            if (max(srcWidth, srcHeight) > MAX_DIMENSION) {
                return null
            }
            val (targetWidth, targetHeight) = targetDecodeSize(srcWidth, srcHeight) ?: return null
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(srcWidth, srcHeight, targetWidth, targetHeight)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
                }
            }
            val rawBitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOptions) ?: return null
            return applyExifOrientation(rawBitmap, readExifOrientation(file))
        } catch (interrupted: InterruptedException) {
            throw interrupted
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return null
        }
    }

    private fun readExifOrientation(file: File): Int {
        return runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    }

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
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
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private fun calculateInSampleSize(srcWidth: Int, srcHeight: Int, targetWidth: Int, targetHeight: Int): Int {
        var inSampleSize = 1
        if (srcHeight > targetHeight || srcWidth > targetWidth) {
            val halfHeight = srcHeight / 2
            val halfWidth = srcWidth / 2
            while ((halfHeight / inSampleSize) >= targetHeight && (halfWidth / inSampleSize) >= targetWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    // Returns null to decline the whole batch when the long-edge cap and the min-short-edge
    // floor are mutually incompatible for this source's aspect ratio, rather than silently
    // violating one of the two bounds.
    private fun targetDecodeSize(width: Int, height: Int): Pair<Int, Int>? {
        val longEdge = max(width, height)
        if (longEdge <= MAX_DECODE_LONG_EDGE) return width to height
        var scale = MAX_DECODE_LONG_EDGE.toDouble() / longEdge.toDouble()
        val shortEdge = min(width, height)
        if (shortEdge >= MIN_DECODE_SHORT_EDGE && shortEdge * scale < MIN_DECODE_SHORT_EDGE) {
            scale = MIN_DECODE_SHORT_EDGE.toDouble() / shortEdge.toDouble()
            if (longEdge * scale > MAX_DECODE_LONG_EDGE) return null
        }
        val targetWidth = max(1, (width * scale).toInt())
        val targetHeight = max(1, (height * scale).toInt())
        return targetWidth to targetHeight
    }

    private fun bitmapToRgb8(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val out = ByteArray(width * height * 3)
        var offset = 0
        for (pixel in pixels) {
            out[offset] = ((pixel shr 16) and 0xFF).toByte()
            out[offset + 1] = ((pixel shr 8) and 0xFF).toByte()
            out[offset + 2] = (pixel and 0xFF).toByte()
            offset += 3
        }
        return out
    }
}
