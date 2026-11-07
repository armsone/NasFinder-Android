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
    // Optional existing project photo or video (TrailerSource.id) used as the background instead of the first/last frame;
    // null (old saves) = first/last frame. Ignored while usesFirstOrLastImage is off or the source is gone.
    val backgroundSourceId: Int? = null,
    // Optional still timestamp (seconds) within the chosen video source; null for photo sources or old saves.
    val backgroundTimestamp: Double? = null,
    val titleStyle: SceneCaption? = null,
    val subtitleStyle: SceneCaption? = null,
) {
    /** The chosen source among [candidates], or null (missing/not readable) so callers fall back to first/last frame. */
    fun resolveBackgroundSource(candidates: Iterable<TrailerSource>): TrailerSource? {
        val id = backgroundSourceId ?: return null
        if (!usesFirstOrLastImage) return null
        return candidates.firstOrNull { it.id == id && it.file.isFile }
    }

    /** Clamps [backgroundTimestamp] to valid source duration [0.0..duration], or 0.0 for photos / missing timestamp. */
    fun clampedBackgroundTimestamp(source: TrailerSource?): Double {
        if (source == null || source.isImage) return 0.0
        val ts = backgroundTimestamp ?: 0.0
        if (!ts.isFinite()) return 0.0
        val maxDur = maxOf(0.0, source.duration)
        return if (maxDur > 0.0) ts.coerceIn(0.0, maxDur) else ts.coerceAtLeast(0.0)
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
        backgroundTimestamp = backgroundTimestamp?.takeIf { it.isFinite() },
        titleStyle = titleStyle?.clamped()?.copy(text = ""),
        subtitleStyle = subtitleStyle?.clamped()?.copy(text = ""),
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("title", title)
        put("subtitle", subtitle)
        put("duration", clampedDuration())
        put("usesFirstOrLastImage", usesFirstOrLastImage)
        if (backgroundSourceId != null) put("backgroundSourceId", backgroundSourceId)
        if (backgroundTimestamp != null && backgroundTimestamp.isFinite()) put("backgroundTimestamp", backgroundTimestamp)
        titleStyle?.let { put("titleStyle", it.copy(text = "").toJson()) }
        subtitleStyle?.let { put("subtitleStyle", it.copy(text = "").toJson()) }
    }

    companion object {
        fun fromJson(obj: JSONObject?): TrailerTitleCard {
            if (obj == null) return TrailerTitleCard()
            val rawDuration = if (obj.has("duration")) obj.optDouble("duration", 3.0) else 3.0
            val titleStyle = sceneCaptionFromJson(obj.optJSONObject("titleStyle"), allowBlank = true)?.copy(text = "")
            val subtitleStyle = sceneCaptionFromJson(obj.optJSONObject("subtitleStyle"), allowBlank = true)?.copy(text = "")
            val bgTimestamp = if (obj.has("backgroundTimestamp") && !obj.isNull("backgroundTimestamp")) {
                val rawTs = obj.optDouble("backgroundTimestamp")
                if (rawTs.isFinite()) rawTs else null
            } else null
            return TrailerTitleCard(
                enabled = obj.optBoolean("enabled", false),
                title = obj.optString("title", ""),
                subtitle = obj.optString("subtitle", ""),
                duration = if (rawDuration.isFinite()) rawDuration.coerceIn(1.0, 10.0) else 3.0,
                usesFirstOrLastImage = obj.optBoolean("usesFirstOrLastImage", true),
                backgroundSourceId = if (obj.has("backgroundSourceId") && !obj.isNull("backgroundSourceId")) obj.optInt("backgroundSourceId") else null,
                backgroundTimestamp = bgTimestamp,
                titleStyle = titleStyle,
                subtitleStyle = subtitleStyle,
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

    fun defaultTitleStyle(bodyCaption: SceneCaption? = null): SceneCaption {
        val legacy = SceneCaption(
            text = "",
            font = CaptionFont.PRETENDARD_BOLD,
            fontName = "pretendard_bold",
            size = CaptionSize.XLARGE,
            textColor = 0xFFFFFFFF.toInt(),
            shadowEnabled = true,
            shadowColor = 0xFF000000.toInt(),
            shadowOpacity = 0.75f,
            lineSpacing = 1.25f,
            column = 2,
            row = 1,
        )
        return if (bodyCaption == null) legacy else legacy.copy(
            font = bodyCaption.font,
            fontName = bodyCaption.effectiveFontName,
            size = bodyCaption.size,
            textColor = bodyCaption.textColor,
            shadowEnabled = bodyCaption.shadowEnabled,
            shadowColor = bodyCaption.shadowColor,
            shadowOpacity = bodyCaption.shadowOpacity,
            lineSpacing = bodyCaption.lineSpacing,
        )
    }

    fun defaultSubtitleStyle(bodyCaption: SceneCaption? = null): SceneCaption {
        val legacy = SceneCaption(
            text = "",
            font = CaptionFont.PRETENDARD_BOLD,
            fontName = "pretendard_bold",
            size = CaptionSize.LARGE,
            textColor = 0xFFFFFFFF.toInt(),
            shadowEnabled = true,
            shadowColor = 0xFF000000.toInt(),
            shadowOpacity = 0.75f,
            lineSpacing = 1.25f,
            column = 2,
            row = 3,
        )
        return if (bodyCaption == null) legacy else legacy.copy(
            font = bodyCaption.font,
            fontName = bodyCaption.effectiveFontName,
            size = bodyCaption.size,
            textColor = bodyCaption.textColor,
            shadowEnabled = bodyCaption.shadowEnabled,
            shadowColor = bodyCaption.shadowColor,
            shadowOpacity = bodyCaption.shadowOpacity,
            lineSpacing = bodyCaption.lineSpacing,
        )
    }

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

        val hasCustomStyles = card.titleStyle != null || card.subtitleStyle != null
        if (!hasCustomStyles) {
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

        // When both title and subtitle resolve to the exact same row and column, draw subtitle beneath
        // title bounds with a small scaled gap, shifting the pair upward if the bottom would clip.
        if (titleText.isNotEmpty() && subtitleText.isNotEmpty()) {
            val tStyle = (card.titleStyle ?: defaultTitleStyle(bodyCaption)).copy(text = titleText)
            val sStyle = (card.subtitleStyle ?: defaultSubtitleStyle(bodyCaption)).copy(text = subtitleText)
            val tCap = tStyle.clamped()
            val sCap = sStyle.clamped()

            if (tCap.row == sCap.row && tCap.column == sCap.column) {
                val titleBitmap = SceneCaptionRenderer.render(context, tStyle, width, height)
                val subtitleBitmap = SceneCaptionRenderer.render(context, sStyle, width, height)

                if (titleBitmap != null && subtitleBitmap != null) {
                    val titleBounds = findVerticalAlphaBounds(titleBitmap)
                    val subBounds = findVerticalAlphaBounds(subtitleBitmap)

                    if (titleBounds != null && subBounds != null) {
                        val titleTop = titleBounds.first
                        val titleBottom = titleBounds.second
                        val subTop = subBounds.first
                        val subBottom = subBounds.second

                        val gap = maxOf(4f, minOf(width, height) * 0.018f)
                        val desiredSubTop = titleBottom + gap
                        val subOffsetY = desiredSubTop - subTop
                        val newSubBottom = subBottom + subOffsetY

                        val safeMargin = height * 0.08f
                        val safeBottom = height - safeMargin
                        val safeTop = safeMargin

                        var shiftUp = (newSubBottom - safeBottom).coerceAtLeast(0f)
                        if (titleTop - shiftUp < safeTop && (newSubBottom - shiftUp) <= height) {
                            val maxShift = (titleTop - safeTop).coerceAtLeast(0f)
                            shiftUp = minOf(shiftUp, maxShift)
                            if (newSubBottom - shiftUp > height) {
                                shiftUp = (newSubBottom - height.toFloat()).coerceAtLeast(0f)
                            }
                        }

                        val titleDrawY = -shiftUp
                        val subDrawY = subOffsetY - shiftUp

                        canvas.drawBitmap(titleBitmap, 0f, titleDrawY, null)
                        canvas.drawBitmap(subtitleBitmap, 0f, subDrawY, null)
                    } else {
                        canvas.drawBitmap(titleBitmap, 0f, 0f, null)
                        canvas.drawBitmap(subtitleBitmap, 0f, 0f, null)
                    }
                    titleBitmap.recycle()
                    subtitleBitmap.recycle()
                    return bitmap
                } else {
                    if (titleBitmap != null) {
                        canvas.drawBitmap(titleBitmap, 0f, 0f, null)
                        titleBitmap.recycle()
                    }
                    if (subtitleBitmap != null) {
                        canvas.drawBitmap(subtitleBitmap, 0f, 0f, null)
                        subtitleBitmap.recycle()
                    }
                    return bitmap
                }
            }
        }

        // Independent custom styles for title and subtitle (different row/column or single text)
        if (titleText.isNotEmpty()) {
            val tStyle = (card.titleStyle ?: defaultTitleStyle(bodyCaption)).copy(text = titleText)
            val titleBitmap = SceneCaptionRenderer.render(context, tStyle, width, height)
            if (titleBitmap != null) {
                canvas.drawBitmap(titleBitmap, 0f, 0f, null)
                titleBitmap.recycle()
            }
        }

        if (subtitleText.isNotEmpty()) {
            val sStyle = (card.subtitleStyle ?: defaultSubtitleStyle(bodyCaption)).copy(text = subtitleText)
            val subtitleBitmap = SceneCaptionRenderer.render(context, sStyle, width, height)
            if (subtitleBitmap != null) {
                canvas.drawBitmap(subtitleBitmap, 0f, 0f, null)
                subtitleBitmap.recycle()
            }
        }

        return bitmap
    }

    private fun findVerticalAlphaBounds(bitmap: Bitmap, alphaThreshold: Int = 10): Pair<Int, Int>? {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return null
        val row = IntArray(width)
        var top = -1
        for (y in 0 until height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                if ((row[x] ushr 24) >= alphaThreshold) {
                    top = y
                    break
                }
            }
            if (top != -1) break
        }
        if (top == -1) return null

        var bottom = top
        for (y in height - 1 downTo top) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            var found = false
            for (x in 0 until width) {
                if ((row[x] ushr 24) >= alphaThreshold) {
                    bottom = y
                    found = true
                    break
                }
            }
            if (found) break
        }
        return Pair(top, bottom)
    }
}
