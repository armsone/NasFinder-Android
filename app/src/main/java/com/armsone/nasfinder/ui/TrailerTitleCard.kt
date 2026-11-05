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
    // Optional existing project photo (TrailerSource.id) used as the background instead of the first/last frame;
    // null (old saves) = first/last frame. Ignored while usesFirstOrLastImage is off or the source is gone.
    val backgroundSourceId: Int? = null,
) {
    /** The chosen photo among [candidates], or null (missing/not a readable photo) so callers fall back to first/last frame. */
    fun resolveBackgroundSource(candidates: Iterable<TrailerSource>): TrailerSource? {
        val id = backgroundSourceId ?: return null
        if (!usesFirstOrLastImage) return null
        return candidates.firstOrNull { it.id == id && it.isImage && it.file.isFile }
    }

    fun clampedDuration(): Double {
        if (!duration.isFinite()) return 3.0
        return duration.coerceIn(1.0, 10.0)
    }

    fun clamped(): TrailerTitleCard = copy(
        title = title,
        subtitle = subtitle,
        duration = clampedDuration(),
        usesFirstOrLastImage = usesFirstOrLastImage,
        backgroundSourceId = backgroundSourceId,
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("title", title)
        put("subtitle", subtitle)
        put("duration", clampedDuration())
        put("usesFirstOrLastImage", usesFirstOrLastImage)
        if (backgroundSourceId != null) put("backgroundSourceId", backgroundSourceId)
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
                backgroundSourceId = if (obj.has("backgroundSourceId") && !obj.isNull("backgroundSourceId")) obj.optInt("backgroundSourceId") else null,
            )
        }
    }
}

object TrailerTitleCardRenderer {
    /**
     * The project's shared body caption style: the first scene caption (in timeline order) that has text, or null
     * when no scene has one. Used by preview and export so both resolve the same style.
     */
    fun bodyCaptionOf(sceneCaptions: Iterable<SceneCaption?>): SceneCaption? =
        sceneCaptions.firstOrNull { it != null && it.text.isNotBlank() }?.clamped()

    /**
     * Renders a title card to a bitmap of size [width] x [height].
     * Aspect-fills [sourceBitmap] if [card.usesFirstOrLastImage] and [sourceBitmap] is non-null.
     * Applies a 48% black overlay (NORMAL alpha), then draws white bold centered title/subtitle
     * using SceneCaptionRenderer style (XLARGE, Pretendard Bold, centered), or the typography of [bodyCaption]
     * when given (card text, centered layout and background are unchanged).
     * If sourceBitmap is null or usesFirstOrLastImage is false, fills with dark charcoal/black (#141417).
     */
    fun renderCardBitmap(
        context: Context,
        card: TrailerTitleCard,
        sourceBitmap: Bitmap?,
        width: Int,
        height: Int,
        bodyCaption: SceneCaption? = null,
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

        val legacyCaption = SceneCaption(
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
        // Shared body caption typography: font/size/color/shadow/line spacing come from the body caption, while the
        // card keeps its own text, centered layout (column/row), time and background. No body caption = legacy look.
        val caption = if (bodyCaption == null) legacyCaption else legacyCaption.copy(
            font = bodyCaption.font,
            fontName = bodyCaption.effectiveFontName,
            size = bodyCaption.size,
            textColor = bodyCaption.textColor,
            shadowEnabled = bodyCaption.shadowEnabled,
            shadowColor = bodyCaption.shadowColor,
            shadowOpacity = bodyCaption.shadowOpacity,
            lineSpacing = bodyCaption.lineSpacing,
        )

        val captionBitmap = SceneCaptionRenderer.render(context, caption, width, height)
        if (captionBitmap != null) {
            canvas.drawBitmap(captionBitmap, 0f, 0f, null)
            captionBitmap.recycle()
        }

        return bitmap
    }
}
