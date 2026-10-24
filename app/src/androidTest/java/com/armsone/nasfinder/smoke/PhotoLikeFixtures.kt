package com.armsone.nasfinder.smoke

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.sin
import kotlin.random.Random

/**
 * Procedural, photo-like test inputs (sky/ground/objects with texture, lighting gradients and sensor grain).
 * They are generated here, so no user photo, attachment or external image is involved. They exercise the real
 * measurement/grouping path only; they do not prove quality ranking on real photographs.
 */
internal object PhotoLikeFixtures {
    const val W = 1024
    const val H = 768

    val kindNames = listOf("forest-dusk", "city-day", "beach", "interior", "mountain-lake", "autumn-field")

    /** [kind] fixes the layout; [dx]/[dy]/[gain]/[grainSeed] only vary the capture (used for the near-duplicate). */
    fun scene(kind: Int, dx: Float = 0f, dy: Float = 0f, gain: Float = 1f, grainSeed: Int = 1): Bitmap {
        val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val rnd = Random(7000 + kind)
        canvas.save()
        canvas.translate(dx, dy)
        when (kind) {
            0 -> forest(canvas, rnd)
            1 -> city(canvas, rnd)
            2 -> beach(canvas, rnd)
            3 -> interior(canvas, rnd)
            4 -> mountain(canvas, rnd)
            else -> field(canvas, rnd)
        }
        canvas.restore()
        texture(bitmap, kind, gain, grainSeed)
        return bitmap
    }

    fun blurred(source: Bitmap): Bitmap {
        val small = Bitmap.createScaledBitmap(source, W / 24, H / 24, true)
        return Bitmap.createScaledBitmap(small, W, H, true).also { small.recycle() }
    }

    fun darkened(source: Bitmap, factor: Float): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(factor, factor, factor, 1f) })
        }
        Canvas(out).drawBitmap(source, 0f, 0f, paint)
        return out
    }

    private fun texture(bitmap: Bitmap, kind: Int, gain: Float, grainSeed: Int) {
        val w = bitmap.width
        val h = bitmap.height
        val grid = Random(900 + kind)
        val gw = 33
        val gh = 25
        val low = FloatArray(gw * gh) { (grid.nextFloat() - 0.5f) * 22f }
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        var state = 0x9E3779B1.toInt() xor (grainSeed * 7919)
        for (y in 0 until h) {
            val gy = y * (gh - 1f) / h
            val y0 = gy.toInt()
            val fy = gy - y0
            for (x in 0 until w) {
                val gx = x * (gw - 1f) / w
                val x0 = gx.toInt()
                val fx = gx - x0
                val a = low[y0 * gw + x0]
                val b = low[y0 * gw + x0 + 1]
                val c = low[(y0 + 1) * gw + x0]
                val d = low[(y0 + 1) * gw + x0 + 1]
                val lowNoise = (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
                state = state xor (state shl 13)
                state = state xor (state ushr 17)
                state = state xor (state shl 5)
                val grain = ((state and 0xFF) - 128) / 128f * 7f
                val p = pixels[y * w + x]
                val vignette = 1f - 0.22f * (((x - w / 2f) / w) * ((x - w / 2f) / w) + ((y - h / 2f) / h) * ((y - h / 2f) / h)) * 4f
                val r = (((p shr 16) and 0xFF) * gain * vignette + lowNoise + grain).toInt().coerceIn(0, 255)
                val g = (((p shr 8) and 0xFF) * gain * vignette + lowNoise + grain).toInt().coerceIn(0, 255)
                val bl = ((p and 0xFF) * gain * vignette + lowNoise + grain).toInt().coerceIn(0, 255)
                pixels[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
            }
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    private fun paint(color: Int, alpha: Int = 255) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; this.alpha = alpha }

    private fun sky(c: Canvas, top: Int, bottom: Int, horizon: Float) {
        val p = Paint().apply { shader = LinearGradient(0f, -40f, 0f, horizon, top, bottom, Shader.TileMode.CLAMP) }
        c.drawRect(-60f, -60f, W + 60f, horizon, p)
    }

    private fun glow(c: Canvas, x: Float, y: Float, r: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(x, y, r, color, color and 0x00FFFFFF, Shader.TileMode.CLAMP)
        }
        c.drawCircle(x, y, r, p)
    }

    private fun ridge(c: Canvas, rnd: Random, baseY: Float, amp: Float, color: Int, phase: Float, snow: Boolean = false) {
        val path = Path().apply { moveTo(-60f, H + 60f) }
        val steps = 80
        val points = ArrayList<Pair<Float, Float>>()
        for (i in 0..steps) {
            val x = -60f + i * (W + 120f) / steps
            val y = baseY - amp * (0.55f + 0.30f * sin(i * 0.19f + phase) + 0.15f * sin(i * 0.61f + phase * 2)) + rnd.nextFloat() * amp * 0.08f
            points += x to y
            path.lineTo(x, y)
        }
        path.lineTo(W + 60f, H + 60f)
        path.close()
        c.drawPath(path, paint(color))
        if (snow) {
            val cap = paint(0xFFF4F6FA.toInt(), 235)
            for (i in 1 until points.size - 1) {
                val (x, y) = points[i]
                if (y < baseY - amp * 0.7f) c.drawRect(x - 7f, y, x + 7f, y + amp * 0.12f + rnd.nextFloat() * 10f, cap)
            }
        }
    }

    private fun strokes(c: Canvas, rnd: Random, count: Int, y0: Float, y1: Float, colors: IntArray, maxLen: Float, width: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = width; strokeCap = Paint.Cap.ROUND }
        repeat(count) {
            val x = rnd.nextFloat() * (W + 80) - 40
            val y = y0 + rnd.nextFloat() * (y1 - y0)
            p.color = colors[rnd.nextInt(colors.size)]
            p.alpha = 150 + rnd.nextInt(100)
            val len = 3f + rnd.nextFloat() * maxLen
            c.drawLine(x, y, x + (rnd.nextFloat() - 0.5f) * len, y - len, p)
        }
    }

    private fun forest(c: Canvas, rnd: Random) {
        sky(c, 0xFF1B2A4E.toInt(), 0xFFE8894A.toInt(), H * 0.58f)
        glow(c, W * 0.7f, H * 0.52f, 260f, 0xFFFFD9A0.toInt())
        ridge(c, rnd, H * 0.58f, 90f, 0xFF3B2F55.toInt(), 0.4f)
        c.drawRect(-60f, H * 0.58f, W + 60f, H + 60f, Paint().apply {
            shader = LinearGradient(0f, H * 0.58f, 0f, H.toFloat(), 0xFF2E4A2A.toInt(), 0xFF0F1D10.toInt(), Shader.TileMode.CLAMP)
        })
        strokes(c, rnd, 2600, H * 0.6f, H * 1f, intArrayOf(0xFF4F7A3A.toInt(), 0xFF2A4A22.toInt(), 0xFF6E8F3F.toInt(), 0xFF1D3318.toInt()), 26f, 2f)
        repeat(34) {
            val x = rnd.nextFloat() * W
            val base = H * 0.62f + rnd.nextFloat() * H * 0.34f
            val scale = 0.5f + (base / H)
            c.drawRect(x - 5 * scale, base - 90 * scale, x + 5 * scale, base, paint(0xFF3A2A1C.toInt()))
            repeat(22) {
                val fx = x + (rnd.nextFloat() - 0.5f) * 70f * scale
                val fy = base - 90 * scale - rnd.nextFloat() * 80f * scale
                val shade = 30 + rnd.nextInt(70)
                c.drawCircle(fx, fy, (12 + rnd.nextInt(18)) * scale, paint((0xFF shl 24) or (shade / 2 shl 16) or ((70 + shade) shl 8) or (shade / 3)))
            }
        }
    }

    private fun city(c: Canvas, rnd: Random) {
        sky(c, 0xFF8FB8E6.toInt(), 0xFFE9F1F8.toInt(), H * 0.62f)
        repeat(7) { glow(c, rnd.nextFloat() * W, rnd.nextFloat() * H * 0.35f, 90f + rnd.nextFloat() * 80f, 0xCCFFFFFF.toInt()) }
        var x = -30f
        while (x < W + 30) {
            val bw = 50f + rnd.nextFloat() * 90f
            val bh = 140f + rnd.nextFloat() * 330f
            val tone = 70 + rnd.nextInt(110)
            val body = (0xFF shl 24) or (tone shl 16) or ((tone - 8).coerceAtLeast(0) shl 8) or (tone - 22).coerceAtLeast(0)
            val top = H * 0.66f - bh
            c.drawRect(x, top, x + bw, H * 0.66f, paint(body))
            c.drawRect(x + bw - 8f, top, x + bw, H * 0.66f, paint(0xFF000000.toInt(), 55))
            var wy = top + 12f
            while (wy < H * 0.66f - 18f) {
                var wx = x + 8f
                while (wx < x + bw - 18f) {
                    val lit = rnd.nextInt(4) == 0
                    c.drawRect(wx, wy, wx + 9f, wy + 12f, paint(if (lit) 0xFFFFE9A8.toInt() else 0xFF2B3A4C.toInt(), if (lit) 255 else 200))
                    wx += 17f
                }
                wy += 24f
            }
            x += bw + 4f
        }
        c.drawRect(-60f, H * 0.66f, W + 60f, H + 60f, Paint().apply {
            shader = LinearGradient(0f, H * 0.66f, 0f, H.toFloat(), 0xFF45474C.toInt(), 0xFF202226.toInt(), Shader.TileMode.CLAMP)
        })
        repeat(9) { i -> c.drawRect(i * 130f - 20f, H * 0.86f, i * 130f + 50f, H * 0.875f, paint(0xFFE8E2B8.toInt(), 220)) }
        repeat(5) {
            val cx = rnd.nextFloat() * W
            c.drawRoundRect(cx, H * 0.76f, cx + 90f, H * 0.82f, 12f, 12f, paint((0xFF shl 24) or rnd.nextInt(0xFFFFFF)))
        }
    }

    private fun beach(c: Canvas, rnd: Random) {
        sky(c, 0xFF2F86D6.toInt(), 0xFFBFE3F7.toInt(), H * 0.40f)
        glow(c, W * 0.2f, H * 0.14f, 150f, 0xFFFFF6C8.toInt())
        c.drawRect(-60f, H * 0.40f, W + 60f, H * 0.62f, Paint().apply {
            shader = LinearGradient(0f, H * 0.40f, 0f, H * 0.62f, 0xFF1C7C8C.toInt(), 0xFF6CCFD0.toInt(), Shader.TileMode.CLAMP)
        })
        val wave = paint(0xFFFFFFFF.toInt(), 120).apply { strokeWidth = 2f }
        repeat(220) {
            val y = H * 0.41f + rnd.nextFloat() * H * 0.20f
            val x = rnd.nextFloat() * W
            c.drawLine(x, y, x + 12f + rnd.nextFloat() * 40f, y, wave)
        }
        c.drawRect(-60f, H * 0.62f, W + 60f, H + 60f, Paint().apply {
            shader = LinearGradient(0f, H * 0.62f, 0f, H.toFloat(), 0xFFF0DCA8.toInt(), 0xFFC9A66A.toInt(), Shader.TileMode.CLAMP)
        })
        repeat(4200) {
            val shade = 150 + rnd.nextInt(90)
            c.drawCircle(rnd.nextFloat() * W, H * 0.62f + rnd.nextFloat() * H * 0.4f, 0.8f + rnd.nextFloat() * 1.6f,
                paint((0xFF shl 24) or (shade shl 16) or ((shade - 20) shl 8) or (shade - 60).coerceAtLeast(0)))
        }
        c.drawRect(W * 0.58f, H * 0.42f, W * 0.58f + 7f, H * 0.86f, paint(0xFF5A4630.toInt()))
        val canopy = Path().apply {
            moveTo(W * 0.58f - 190f, H * 0.52f); lineTo(W * 0.58f + 3f, H * 0.34f); lineTo(W * 0.58f + 196f, H * 0.52f); close()
        }
        c.drawPath(canopy, paint(0xFFD8392F.toInt()))
        for (i in 0..5) {
            val p = Path().apply {
                moveTo(W * 0.58f + 3f, H * 0.34f)
                lineTo(W * 0.58f - 190f + i * 64f, H * 0.52f)
                lineTo(W * 0.58f - 190f + (i + 1) * 64f, H * 0.52f)
                close()
            }
            c.drawPath(p, paint(if (i % 2 == 0) 0xFFD8392F.toInt() else 0xFFFFF4E8.toInt()))
        }
        repeat(9) {
            val rx = W * 0.05f + rnd.nextFloat() * W * 0.28f
            val ry = H * 0.8f + rnd.nextFloat() * H * 0.14f
            c.drawOval(rx, ry, rx + 30f + rnd.nextFloat() * 46f, ry + 18f + rnd.nextFloat() * 20f, paint(0xFF6D675F.toInt()))
        }
    }

    private fun interior(c: Canvas, rnd: Random) {
        c.drawRect(-60f, -60f, W + 60f, H * 0.62f, Paint().apply {
            shader = LinearGradient(0f, 0f, W.toFloat(), 0f, 0xFFD8C3A0.toInt(), 0xFFB79C78.toInt(), Shader.TileMode.CLAMP)
        })
        c.drawRect(-60f, H * 0.62f, W + 60f, H + 60f, paint(0xFF7A5030.toInt()))
        var y = H * 0.62f
        var step = 14f
        while (y < H + 20) {
            c.drawLine(-60f, y, W + 60f, y, paint(0xFF4B2E19.toInt()).apply { strokeWidth = 2f })
            y += step; step *= 1.16f
        }
        repeat(2200) {
            val x = rnd.nextFloat() * W
            val yy = H * 0.63f + rnd.nextFloat() * H * 0.4f
            c.drawLine(x, yy, x + 20f + rnd.nextFloat() * 60f, yy, paint(0xFF8E6240.toInt(), 120).apply { strokeWidth = 1f })
        }
        c.drawRect(W * 0.14f, H * 0.12f, W * 0.46f, H * 0.52f, Paint().apply {
            shader = LinearGradient(0f, H * 0.12f, 0f, H * 0.52f, 0xFF9CD0F5.toInt(), 0xFFEAF6FF.toInt(), Shader.TileMode.CLAMP)
        })
        c.drawRect(W * 0.14f, H * 0.12f, W * 0.46f, H * 0.52f, paint(0xFF3D2B1B.toInt()).apply { style = Paint.Style.STROKE; strokeWidth = 12f })
        c.drawLine(W * 0.30f, H * 0.12f, W * 0.30f, H * 0.52f, paint(0xFF3D2B1B.toInt()).apply { strokeWidth = 8f })
        c.drawLine(W * 0.14f, H * 0.32f, W * 0.46f, H * 0.32f, paint(0xFF3D2B1B.toInt()).apply { strokeWidth = 8f })
        val cone = Path().apply {
            moveTo(W * 0.14f, H * 0.52f); lineTo(W * 0.46f, H * 0.52f); lineTo(W * 0.78f, H * 0.95f); lineTo(W * 0.25f, H * 0.95f); close()
        }
        c.drawPath(cone, paint(0xFFFFF2C4.toInt(), 70))
        c.drawRoundRect(W * 0.52f, H * 0.50f, W * 0.95f, H * 0.78f, 40f, 40f, paint(0xFF3C4A66.toInt()))
        c.drawRoundRect(W * 0.52f, H * 0.42f, W * 0.95f, H * 0.58f, 30f, 30f, paint(0xFF4B5C7E.toInt()))
        repeat(3) { i -> c.drawRoundRect(W * (0.56f + i * 0.11f), H * 0.52f, W * (0.65f + i * 0.11f), H * 0.62f, 18f, 18f, paint(0xFF5E6F93.toInt())) }
        glow(c, W * 0.88f, H * 0.28f, 150f, 0xFFFFD27A.toInt())
        c.drawRect(W * 0.875f, H * 0.28f, W * 0.885f, H * 0.5f, paint(0xFF222222.toInt()))
        repeat(3) { i ->
            val fx = W * (0.52f + i * 0.14f)
            c.drawRect(fx, H * 0.14f, fx + 90f, H * 0.30f, paint(0xFFF2E8D4.toInt()))
            c.drawRect(fx + 8f, H * 0.14f + 8f, fx + 82f, H * 0.30f - 8f, paint((0xFF shl 24) or ((rnd.nextInt(0xFFFFFF) and 0x7F7F7F) or 0x404040)))
        }
    }

    private fun mountain(c: Canvas, rnd: Random) {
        sky(c, 0xFF35567E.toInt(), 0xFFC7D8EA.toInt(), H * 0.5f)
        glow(c, W * 0.25f, H * 0.2f, 200f, 0xFFFFF2D8.toInt())
        ridge(c, rnd, H * 0.50f, 250f, 0xFF7C8CA6.toInt(), 0.3f, snow = true)
        ridge(c, rnd, H * 0.55f, 180f, 0xFF4F5F7C.toInt(), 1.7f, snow = true)
        ridge(c, rnd, H * 0.60f, 90f, 0xFF2B3A40.toInt(), 3.1f)
        c.drawRect(-60f, H * 0.6f, W + 60f, H + 60f, Paint().apply {
            shader = LinearGradient(0f, H * 0.6f, 0f, H.toFloat(), 0xFF3F7F9C.toInt(), 0xFF173A4E.toInt(), Shader.TileMode.CLAMP)
        })
        repeat(260) {
            val y = H * 0.62f + rnd.nextFloat() * H * 0.36f
            val x = rnd.nextFloat() * W
            c.drawLine(x, y, x + 10f + rnd.nextFloat() * 60f, y, paint(0xFFD6ECF5.toInt(), 70).apply { strokeWidth = 1.5f })
        }
        strokes(c, rnd, 1500, H * 0.54f, H * 0.64f, intArrayOf(0xFF1D3328.toInt(), 0xFF264236.toInt(), 0xFF142419.toInt()), 34f, 2.5f)
    }

    private fun field(c: Canvas, rnd: Random) {
        sky(c, 0xFF8F9DAE.toInt(), 0xFFE2DCCB.toInt(), H * 0.42f)
        repeat(6) { glow(c, rnd.nextFloat() * W, 20f + rnd.nextFloat() * H * 0.25f, 110f, 0x99FFFFFF.toInt()) }
        c.drawRect(-60f, H * 0.42f, W + 60f, H + 60f, Paint().apply {
            shader = LinearGradient(0f, H * 0.42f, 0f, H.toFloat(), 0xFFB89A3C.toInt(), 0xFF7A4A1C.toInt(), Shader.TileMode.CLAMP)
        })
        strokes(c, rnd, 5200, H * 0.43f, H.toFloat(), intArrayOf(0xFFE2B84A.toInt(), 0xFFB87A2A.toInt(), 0xFFD9661F.toInt(), 0xFF8A5A22.toInt(), 0xFFF0D68A.toInt()), 40f, 2.4f)
        val barn = Path().apply {
            moveTo(W * 0.12f, H * 0.62f); lineTo(W * 0.12f, H * 0.46f); lineTo(W * 0.2f, H * 0.38f); lineTo(W * 0.28f, H * 0.46f); lineTo(W * 0.28f, H * 0.62f); close()
        }
        c.drawPath(barn, paint(0xFF9C2A22.toInt()))
        c.drawRect(W * 0.17f, H * 0.5f, W * 0.23f, H * 0.62f, paint(0xFFF2E6D2.toInt()))
        c.drawLine(W * 0.12f, H * 0.46f, W * 0.2f, H * 0.38f, paint(0xFF3A1410.toInt()).apply { strokeWidth = 6f })
        c.drawRect(W * 0.7f, H * 0.34f, W * 0.715f, H * 0.62f, paint(0xFF3A2A1C.toInt()))
        repeat(60) {
            val fx = W * 0.71f + (rnd.nextFloat() - 0.5f) * 230f
            val fy = H * 0.3f + (rnd.nextFloat() - 0.5f) * 130f
            val tone = rnd.nextInt(80)
            c.drawCircle(fx, fy, 16f + rnd.nextInt(22), paint((0xFF shl 24) or ((190 + tone / 2) shl 16) or ((70 + tone) shl 8) or 28))
        }
    }
}
