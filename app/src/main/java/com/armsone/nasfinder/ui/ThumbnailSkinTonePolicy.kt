package com.armsone.nasfinder.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap
import kotlin.math.max
import kotlin.math.min

/** Local-only, bounded thumbnail analysis. No pixels or classification results leave the process. */
internal object ThumbnailSkinTonePolicy {
    const val BLUR_RADIUS_DP = 2f
    private const val SAMPLE_SIDE = 32
    private const val MIN_VISIBLE_ALPHA = 32

    fun shouldBlurArgb(pixels: IntArray): Boolean {
        var visible = 0
        var skin = 0
        pixels.forEach { pixel ->
            val alpha = pixel ushr 24 and 0xff
            if (alpha < MIN_VISIBLE_ALPHA) return@forEach
            visible++
            if (isSkinTone(pixel)) skin++
        }
        return visible > 0 && skin * 2L >= visible.toLong()
    }

    fun shouldBlur(bitmap: Bitmap): Boolean {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return false
        val columns = min(SAMPLE_SIDE, bitmap.width)
        val rows = min(SAMPLE_SIDE, bitmap.height)
        val sampled = IntArray(columns * rows)
        var index = 0
        for (row in 0 until rows) {
            val y = (((row + .5f) * bitmap.height) / rows).toInt().coerceIn(0, bitmap.height - 1)
            for (column in 0 until columns) {
                val x = (((column + .5f) * bitmap.width) / columns).toInt().coerceIn(0, bitmap.width - 1)
                sampled[index++] = bitmap.getPixel(x, y)
            }
        }
        return shouldBlurArgb(sampled)
    }

    /**
     * Combines YCbCr chroma limits with HSV hue, saturation and brightness limits. This keeps the
     * classifier deterministic while covering light and dark skin better than one RGB interval.
     */
    internal fun isSkinTone(argb: Int): Boolean {
        val red = (argb ushr 16 and 0xff).toFloat()
        val green = (argb ushr 8 and 0xff).toFloat()
        val blue = (argb and 0xff).toFloat()

        val cb = 128f - .168736f * red - .331264f * green + .5f * blue
        val cr = 128f + .5f * red - .418688f * green - .081312f * blue
        if (cb !in 77f..135f || cr !in 133f..180f) return false

        val highest = max(red, max(green, blue))
        val lowest = min(red, min(green, blue))
        val delta = highest - lowest
        val value = highest / 255f
        val saturation = if (highest == 0f) 0f else delta / highest
        val hue = when {
            delta == 0f -> 0f
            highest == red -> 60f * (((green - blue) / delta) % 6f)
            highest == green -> 60f * (((blue - red) / delta) + 2f)
            else -> 60f * (((red - green) / delta) + 4f)
        }.let { if (it < 0f) it + 360f else it }

        val skinHue = hue <= 55f || hue >= 345f
        return skinHue && saturation in 0.08f..0.80f && value >= 0.15f
    }
}

internal object ThumbnailBlurRenderingPolicy {
    fun radiusDp(shouldBlur: Boolean): Float = if (shouldBlur) ThumbnailSkinTonePolicy.BLUR_RADIUS_DP else 0f
}

internal object ThumbnailSkinToneCacheIdentity {
    fun effective(baseIdentity: String, width: Int, height: Int, generationId: Int): String =
        "$baseIdentity\u0000${width}x$height\u0000$generationId"
}

internal class ThumbnailSkinToneClassificationCache(private val maxEntries: Int = 768) {
    private val values = LinkedHashMap<String, Boolean>(32, .75f, true)

    @Synchronized
    fun cached(key: String): Boolean? = values[key]

    @Synchronized
    fun getOrClassify(key: String, classifier: () -> Boolean): Boolean {
        values[key]?.let { return it }
        val result = classifier()
        values[key] = result
        while (values.size > maxEntries) values.entries.iterator().run { next(); remove() }
        return result
    }
}

private val thumbnailSkinToneCache = ThumbnailSkinToneClassificationCache()

/**
 * The only bitmap renderer used for thumbnail artwork. Unknown images start blurred until their
 * off-main-thread classification finishes, preventing an unblurred sensitive frame from flashing.
 * Compose's blur is a safe no-op below Android 12; it does not mutate the cached bitmap.
 */
@Composable
internal fun SkinAwareThumbnailImage(
    bitmap: Bitmap,
    cacheIdentity: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    appliesDisplayBlur: Boolean = true,
) {
    val effectiveIdentity = ThumbnailSkinToneCacheIdentity.effective(
        cacheIdentity,
        bitmap.width,
        bitmap.height,
        bitmap.generationId,
    )
    val shouldBlur by produceState<Boolean?>(
        initialValue = if (appliesDisplayBlur) thumbnailSkinToneCache.cached(effectiveIdentity) else false,
        key1 = effectiveIdentity,
        key2 = bitmap,
        key3 = appliesDisplayBlur,
    ) {
        value = if (appliesDisplayBlur) {
            withContext(Dispatchers.Default) {
                thumbnailSkinToneCache.getOrClassify(effectiveIdentity) {
                    ThumbnailSkinTonePolicy.shouldBlur(bitmap)
                }
            }
        } else {
            false
        }
    }
    val radius = ThumbnailBlurRenderingPolicy.radiusDp(shouldBlur != false)
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = contentDescription,
        modifier = if (radius > 0f) modifier.blur(radius.dp) else modifier,
        contentScale = contentScale,
    )
}
