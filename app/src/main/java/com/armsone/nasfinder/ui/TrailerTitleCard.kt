package com.armsone.nasfinder.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import org.json.JSONObject
import kotlin.math.roundToInt

data class TrailerTitleCard(
    val enabled: Boolean = false,
    val title: String = "",
    val subtitle: String = "",
    val duration: Double = 3.0,
    val usesFirstOrLastImage: Boolean = true,
) {
    fun clampedDuration(): Double {
        if (!duration.isFinite()) return 3.0
        return duration.coerceIn(1.0, 10.0)
    }

    fun clamped(): TrailerTitleCard = copy(
        title = title,
        subtitle = subtitle,
        duration = clampedDuration(),
        usesFirstOrLastImage = usesFirstOrLastImage,
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("title", title)
        put("subtitle", subtitle)
        put("duration", clampedDuration())
        put("usesFirstOrLastImage", usesFirstOrLastImage)
    }

    companion object {
        fun fromJson(obj: JSONObject?): TrailerTitleCard {
            if (obj == null) return TrailerTitleCard()
            val rawDuration = if (obj.has("duration")) obj.optDouble("duration", 3.0) else 3.0
            return TrailerTitleCard(
                enabled = obj.optBoolean("enabled", false),
                title = obj.optString("title", ""),
                subtitle = obj.optString("subtitle", ""),
                duration = if (rawDuration.isFinite()) rawDuration.coerceIn(1.0, 10.0) else 3.0,
                usesFirstOrLastImage = obj.optBoolean("usesFirstOrLastImage", true),
            )
        }
    }
}

object TrailerTitleCardRenderer {
    /**
     * Renders a title card to a bitmap of size [width] x [height].
     * Aspect-fills [sourceBitmap] if [card.usesFirstOrLastImage] and [sourceBitmap] is non-null.
     * Applies a 48% black overlay (NORMAL alpha), then draws white bold centered title/subtitle
     * using SceneCaptionRenderer style (XLARGE, Pretendard Bold, centered).
     * If sourceBitmap is null or usesFirstOrLastImage is false, fills with dark charcoal/black (#141417).
     */
    fun renderCardBitmap(
        context: Context,
        card: TrailerTitleCard,
        sourceBitmap: Bitmap?,
        width: Int,
        height: Int,
    ): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (card.usesFirstOrLastImage && sourceBitmap != null && !sourceBitmap.isRecycled && sourceBitmap.width > 0 && sourceBitmap.height > 0) {
            val imageAspect = sourceBitmap.width.toFloat() / sourceBitmap.height.toFloat()
            val renderAspect = width.toFloat() / height.toFloat()
            val drawRect = if (imageAspect >= renderAspect) {
                val w = height.toFloat() * imageAspect
                RectF((width.toFloat() - w) / 2f, 0f, (width.toFloat() + w) / 2f, height.toFloat())
            } else {
                val h = width.toFloat() / imageAspect
                RectF(0f, (height.toFloat() - h) / 2f, width.toFloat(), (height.toFloat() + h) / 2f)
            }
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(sourceBitmap, null, drawRect, paint)

            // Dim overlay: 48% black NORMAL alpha (Apple parity: UIColor.black.withAlphaComponent(0.48))
            val overlayPaint = Paint().apply {
                color = Color.argb((0.48f * 255f).roundToInt(), 0, 0, 0)
                style = Paint.Style.FILL
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlayPaint)
        } else {
            // Dark charcoal / black background: RGB(0.08, 0.08, 0.09) = #141417
            val darkBgPaint = Paint().apply {
                color = Color.rgb((0.08f * 255f).roundToInt(), (0.08f * 255f).roundToInt(), (0.09f * 255f).roundToInt())
                style = Paint.Style.FILL
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), darkBgPaint)
        }

        val titleText = card.title.trim()
        val subtitleText = card.subtitle.trim()
        if (titleText.isEmpty() && subtitleText.isEmpty()) {
            return bitmap
        }

        val combinedText = when {
            titleText.isNotEmpty() && subtitleText.isNotEmpty() -> "$titleText\n$subtitleText"
            titleText.isNotEmpty() -> titleText
            else -> subtitleText
        }

        val caption = SceneCaption(
            text = combinedText,
            font = CaptionFont.PRETENDARD_BOLD,
            fontName = "pretendard_bold",
            size = CaptionSize.XLARGE,
            textColor = 0xFFFFFFFF.toInt(),
            shadowEnabled = true,
            shadowColor = 0xFF000000.toInt(),
            shadowOpacity = 0.75f,
            lineSpacing = 1.25f,
            column = 2,
            row = 2,
        )

        val captionBitmap = SceneCaptionRenderer.render(context, caption, width, height)
        if (captionBitmap != null) {
            canvas.drawBitmap(captionBitmap, 0f, 0f, null)
            captionBitmap.recycle()
        }

        return bitmap
    }
}
