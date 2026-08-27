package com.armsone.nasfinder.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import com.armsone.nasfinder.model.RemoteFileItem
import kotlin.math.ceil
import kotlin.math.exp

/** Pure Android SDK composer for 384x384 3x3 Folder Super Thumbnail sheets. */
object FolderSuperThumbnailComposer {

    fun composeRawSheet(
        children: List<RemoteFileItem>,
        resolveTileBitmap: (RemoteFileItem) -> Bitmap?,
    ): Bitmap {
        val selected = FolderSuperThumbnailPolicy.selectChildren(children)
        val result = Bitmap.createBitmap(
            FolderSuperThumbnailPolicy.SHEET_PIXEL_SIZE,
            FolderSuperThumbnailPolicy.SHEET_PIXEL_SIZE,
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(result)
        // Keep generated Vault artwork byte-semantically aligned with the Mac producer.
        canvas.drawColor(Color.rgb(247, 247, 250))

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        for (index in 0 until FolderSuperThumbnailPolicy.MAX_CHILDREN) {
            val row = index / FolderSuperThumbnailPolicy.GRID_DIMENSION
            val col = index % FolderSuperThumbnailPolicy.GRID_DIMENSION
            val left = col * FolderSuperThumbnailPolicy.TILE_PIXEL_SIZE
            val top = row * FolderSuperThumbnailPolicy.TILE_PIXEL_SIZE
            val right = left + FolderSuperThumbnailPolicy.TILE_PIXEL_SIZE
            val bottom = top + FolderSuperThumbnailPolicy.TILE_PIXEL_SIZE
            val dstRect = Rect(left, top, right, bottom)

            if (index < selected.size) {
                val child = selected[index]
                val tileBitmap = resolveTileBitmap(child)
                if (tileBitmap != null && !tileBitmap.isRecycled) {
                    val srcRect = calculateCenterCropRect(
                        tileBitmap.width,
                        tileBitmap.height,
                        FolderSuperThumbnailPolicy.TILE_PIXEL_SIZE,
                        FolderSuperThumbnailPolicy.TILE_PIXEL_SIZE,
                    )
                    canvas.drawBitmap(tileBitmap, srcRect, dstRect, paint)
                } else {
                    when {
                        child.isDirectory -> drawFolderPlaceholder(canvas, dstRect)
                        child.isImage || child.isVideo -> drawMediaPlaceholder(canvas, dstRect)
                        else -> drawDocumentPlaceholder(canvas, dstRect)
                    }
                }
            } else {
                drawEmptyTile(canvas, dstRect)
            }
        }

        return result
    }

    fun applySkinToneBlurIfNeeded(bitmap: Bitmap): Bitmap {
        // Deterministic 12x12 sample for skin tone detection
        var skinCount = 0
        val samples = FolderSuperThumbnailPolicy.sampleCoordinates()
        for ((x, y) in samples) {
            val pixel = bitmap.getPixel(x, y)
            if (FolderSuperThumbnailPolicy.isSkinTone(pixel)) {
                skinCount++
            }
        }

        val radiusPx = FolderSuperThumbnailPolicy.blurRadiusPx(skinCount, samples.size)
        if (radiusPx > 0f) {
            applyGaussianBlur(bitmap, radiusPx)
        }

        return bitmap
    }

    fun composeSheet(
        children: List<RemoteFileItem>,
        resolveTileBitmap: (RemoteFileItem) -> Bitmap?,
    ): Bitmap {
        val rawSheet = composeRawSheet(children, resolveTileBitmap)
        return applySkinToneBlurIfNeeded(rawSheet)
    }

    fun applyGaussianBlur(bitmap: Bitmap, radius: Float = FolderSuperThumbnailPolicy.MAX_BLUR_RADIUS_PX): Bitmap {
        if (radius <= 0f || !radius.isFinite()) return bitmap
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val sigma = radius / 2.0
        val kernelRadius = ceil(radius.toDouble()).toInt().coerceAtLeast(1)
        val kernelSize = kernelRadius * 2 + 1
        val kernel = DoubleArray(kernelSize)
        var kernelSum = 0.0
        for (i in -kernelRadius..kernelRadius) {
            val weight = exp(-(i * i) / (2.0 * sigma * sigma))
            kernel[i + kernelRadius] = weight
            kernelSum += weight
        }
        for (i in 0 until kernelSize) {
            kernel[i] /= kernelSum
        }

        val tempPixels = IntArray(width * height)

        // Horizontal pass
        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                var r = 0.0
                var g = 0.0
                var b = 0.0
                var a = 0.0
                for (k in -kernelRadius..kernelRadius) {
                    val px = (x + k).coerceIn(0, width - 1)
                    val pixel = pixels[rowOffset + px]
                    val weight = kernel[k + kernelRadius]
                    a += ((pixel ushr 24) and 0xff) * weight
                    r += ((pixel ushr 16) and 0xff) * weight
                    g += ((pixel ushr 8) and 0xff) * weight
                    b += (pixel and 0xff) * weight
                }
                val ia = a.toInt().coerceIn(0, 255)
                val ir = r.toInt().coerceIn(0, 255)
                val ig = g.toInt().coerceIn(0, 255)
                val ib = b.toInt().coerceIn(0, 255)
                tempPixels[rowOffset + x] = (ia shl 24) or (ir shl 16) or (ig shl 8) or ib
            }
        }

        // Vertical pass
        for (x in 0 until width) {
            for (y in 0 until height) {
                var r = 0.0
                var g = 0.0
                var b = 0.0
                var a = 0.0
                for (k in -kernelRadius..kernelRadius) {
                    val py = (y + k).coerceIn(0, height - 1)
                    val pixel = tempPixels[py * width + x]
                    val weight = kernel[k + kernelRadius]
                    a += ((pixel ushr 24) and 0xff) * weight
                    r += ((pixel ushr 16) and 0xff) * weight
                    g += ((pixel ushr 8) and 0xff) * weight
                    b += (pixel and 0xff) * weight
                }
                val ia = a.toInt().coerceIn(0, 255)
                val ir = r.toInt().coerceIn(0, 255)
                val ig = g.toInt().coerceIn(0, 255)
                val ib = b.toInt().coerceIn(0, 255)
                pixels[y * width + x] = (ia shl 24) or (ir shl 16) or (ig shl 8) or ib
            }
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun calculateCenterCropRect(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Rect {
        val srcRatio = srcW.toFloat() / srcH.toFloat()
        val dstRatio = dstW.toFloat() / dstH.toFloat()
        return if (srcRatio > dstRatio) {
            val targetSrcW = (srcH * dstRatio).toInt()
            val left = (srcW - targetSrcW) / 2
            Rect(left, 0, left + targetSrcW, srcH)
        } else {
            val targetSrcH = (srcW / dstRatio).toInt()
            val top = (srcH - targetSrcH) / 2
            Rect(0, top, srcW, top + targetSrcH)
        }
    }

    private fun drawFolderPlaceholder(canvas: Canvas, rect: Rect) {
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(224, 237, 252) }
        canvas.drawRect(rect, bgPaint)

        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(71, 143, 235)
            style = Paint.Style.FILL
        }
        val cx = rect.exactCenterX()
        val cy = rect.exactCenterY()
        val w = rect.width() * 0.44f
        val h = rect.height() * 0.34f

        val folderPath = Path().apply {
            val l = cx - w / 2
            val t = cy - h / 2
            val r = cx + w / 2
            val b = cy + h / 2
            val tabW = w * 0.40f
            val tabH = h * 0.25f

            moveTo(l + 4f, t)
            lineTo(l + tabW, t)
            lineTo(l + tabW + 6f, t + tabH)
            lineTo(r - 4f, t + tabH)
            quadTo(r, t + tabH, r, t + tabH + 4f)
            lineTo(r, b - 4f)
            quadTo(r, b, r - 4f, b)
            lineTo(l + 4f, b)
            quadTo(l, b, l, b - 4f)
            lineTo(l, t + 4f)
            quadTo(l, t, l + 4f, t)
            close()
        }
        canvas.drawPath(folderPath, iconPaint)
    }

    private fun drawMediaPlaceholder(canvas: Canvas, rect: Rect) {
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(240, 240, 242) }
        canvas.drawRect(rect, bgPaint)

        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(158, 158, 161)
            style = Paint.Style.FILL
        }
        val cx = rect.exactCenterX()
        val cy = rect.exactCenterY()
        val size = rect.width() * 0.38f
        val rRect = RectF(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
        canvas.drawRoundRect(rRect, 8f, 8f, iconPaint)
    }

    private fun drawDocumentPlaceholder(canvas: Canvas, rect: Rect) {
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(240, 240, 242) }
        canvas.drawRect(rect, bgPaint)

        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(158, 158, 161)
            style = Paint.Style.FILL
        }
        val cx = rect.exactCenterX()
        val cy = rect.exactCenterY()
        val w = rect.width() * 0.32f
        val h = rect.height() * 0.42f
        val rRect = RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        canvas.drawRoundRect(rRect, 6f, 6f, iconPaint)
    }

    private fun drawEmptyTile(canvas: Canvas, rect: Rect) {
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(247, 247, 250) }
        canvas.drawRect(rect, bgPaint)
    }
}
