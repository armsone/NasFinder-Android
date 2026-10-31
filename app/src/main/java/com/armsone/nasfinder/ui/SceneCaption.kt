package com.armsone.nasfinder.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import com.armsone.nasfinder.R
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/** Longest caption text the editor accepts per scene (input limit only; stored text is never cut on load). */
internal const val MAX_SCENE_CAPTION_CHARS = 2000

/** Bundled Pretendard faces plus platform serif/monospace; no imported or downloaded fonts. */
enum class CaptionFont(val title: String) {
    PRETENDARD("프리텐다드"),
    PRETENDARD_BOLD("프리텐다드 굵게"),
    SERIF("명조(시스템)"),
    MONOSPACE("고정폭(시스템)"),
}

/** Text height as a fraction of the output frame's short edge, so size never depends on device density. */
enum class CaptionSize(val title: String, val shortEdgeFraction: Float) {
    SMALL("작게", 0.036f),
    NORMAL("기본", 0.048f),
    LARGE("크게", 0.064f),
    XLARGE("더크게", 0.084f),
}

internal val CAPTION_LINE_SPACINGS = listOf("좁게" to 0.8f, "보통" to 1.0f, "넓게" to 1.2f, "더 넓게" to 1.5f)

/**
 * Manual caption of one scene, shown for that scene's whole duration and fixed to the output frame.
 * [column]/[row] pick one of 5×5 positions (0..4) inside the 8% safe margin. Colors are ARGB ints.
 */
data class SceneCaption(
    val text: String,
    val font: CaptionFont = CaptionFont.PRETENDARD_BOLD,
    val size: CaptionSize = CaptionSize.NORMAL,
    val textColor: Int = 0xFFFFFFFF.toInt(),
    val shadowEnabled: Boolean = true,
    val shadowColor: Int = 0xFF000000.toInt(),
    val shadowOpacity: Float = 0.7f,
    val lineSpacing: Float = 1.0f,
    val column: Int = 2,
    val row: Int = 4,
) {
    /** Clamped settings; blank text means "no caption" (null). Text itself is kept as typed. */
    fun normalizedOrNull(): SceneCaption? = if (text.isBlank()) null else clamped()

    /** Settings clamped to their valid ranges (text untouched, may be blank while editing). */
    fun clamped(): SceneCaption {
        return copy(
            textColor = textColor or 0xFF000000.toInt(),
            shadowColor = shadowColor or 0xFF000000.toInt(),
            shadowOpacity = if (shadowOpacity.isFinite()) shadowOpacity.coerceIn(0f, 1f) else 0.7f,
            lineSpacing = if (lineSpacing.isFinite()) lineSpacing.coerceIn(0.5f, 2.0f) else 1.0f,
            column = column.coerceIn(0, 4),
            row = row.coerceIn(0, 4),
        )
    }

    /** Same text, style taken from [preset]. */
    fun withStyleOf(preset: SceneCaption) = preset.copy(text = text)

    companion object {
        val Readable = SceneCaption(text = "", font = CaptionFont.PRETENDARD_BOLD, size = CaptionSize.LARGE, textColor = 0xFFFFFFFF.toInt(), shadowEnabled = true, shadowColor = 0xFF000000.toInt(), shadowOpacity = 0.85f, lineSpacing = 1.0f, column = 2, row = 4)
        val Cinema = SceneCaption(text = "", font = CaptionFont.SERIF, size = CaptionSize.NORMAL, textColor = 0xFFF4EEDF.toInt(), shadowEnabled = true, shadowColor = 0xFF000000.toInt(), shadowOpacity = 0.55f, lineSpacing = 1.2f, column = 2, row = 3)
        val Travel = SceneCaption(text = "", font = CaptionFont.PRETENDARD_BOLD, size = CaptionSize.LARGE, textColor = 0xFFFFE45C.toInt(), shadowEnabled = true, shadowColor = 0xFF1A1A1A.toInt(), shadowOpacity = 0.7f, lineSpacing = 1.0f, column = 0, row = 0)
        val PRESETS = listOf("가독성" to Readable, "시네마" to Cinema, "여행" to Travel)
    }
}

internal fun SceneCaption.toJson(): JSONObject = JSONObject().apply {
    put("text", text)
    put("font", font.name)
    put("size", size.name)
    put("textColor", textColor)
    put("shadowEnabled", shadowEnabled)
    put("shadowColor", shadowColor)
    put("shadowOpacity", shadowOpacity.toDouble())
    put("lineSpacing", lineSpacing.toDouble())
    put("column", column)
    put("row", row)
}

/** Reads an optional stored caption; unknown/missing settings fall back to defaults and are clamped. */
internal fun sceneCaptionFromJson(obj: JSONObject?, allowBlank: Boolean = false): SceneCaption? {
    if (obj == null) return null
    val defaults = SceneCaption(text = "")
    return SceneCaption(
        text = obj.optString("text", ""),
        font = runCatching { CaptionFont.valueOf(obj.optString("font")) }.getOrDefault(defaults.font),
        size = runCatching { CaptionSize.valueOf(obj.optString("size")) }.getOrDefault(defaults.size),
        textColor = obj.optInt("textColor", defaults.textColor),
        shadowEnabled = obj.optBoolean("shadowEnabled", defaults.shadowEnabled),
        shadowColor = obj.optInt("shadowColor", defaults.shadowColor),
        shadowOpacity = obj.optDouble("shadowOpacity", defaults.shadowOpacity.toDouble()).toFloat(),
        lineSpacing = obj.optDouble("lineSpacing", defaults.lineSpacing.toDouble()).toFloat(),
        column = obj.optInt("column", defaults.column),
        row = obj.optInt("row", defaults.row),
    ).let { if (allowBlank) it.clamped() else it.normalizedOrNull() }
}

/** "#RRGGBB" for a caption color. */
internal fun captionHex(color: Int): String = "#%06X".format(color and 0xFFFFFF)

/** Parses "#RRGGBB"/"RRGGBB" into an opaque color, or null. */
internal fun parseCaptionHex(input: String): Int? {
    val hex = input.trim().removePrefix("#")
    if (hex.length != 6 || hex.any { it.digitToIntOrNull(16) == null }) return null
    return (hex.toLong(16).toInt()) or 0xFF000000.toInt()
}

/**
 * The single caption renderer shared by the editor preview and the exporter's Media3 BitmapOverlay: a transparent
 * bitmap of exactly the output size with the caption laid out inside the 8% safe rectangle. Call off the main thread.
 */
internal object SceneCaptionRenderer {
    private const val SAFE_MARGIN = 0.08f
    private val typefaces = mutableMapOf<CaptionFont, Typeface>()

    fun typeface(context: Context, font: CaptionFont): Typeface = synchronized(typefaces) {
        typefaces.getOrPut(font) {
            when (font) {
                CaptionFont.PRETENDARD -> runCatching { ResourcesCompat.getFont(context, R.font.pretendard_regular) }.getOrNull() ?: Typeface.SANS_SERIF
                CaptionFont.PRETENDARD_BOLD -> runCatching { ResourcesCompat.getFont(context, R.font.pretendard_bold) }.getOrNull() ?: Typeface.DEFAULT_BOLD
                CaptionFont.SERIF -> Typeface.SERIF
                CaptionFont.MONOSPACE -> Typeface.MONOSPACE
            }
        }
    }

    fun render(context: Context, caption: SceneCaption, width: Int, height: Int): Bitmap? {
        val safeCaption = caption.normalizedOrNull() ?: return null
        if (width <= 0 || height <= 0) return null
        val safeLeft = width * SAFE_MARGIN
        val safeTop = height * SAFE_MARGIN
        val safeWidth = (width * (1f - 2f * SAFE_MARGIN)).toInt().coerceAtLeast(1)
        val safeHeight = height * (1f - 2f * SAFE_MARGIN)
        val alignment = when (safeCaption.column) {
            0, 1 -> Layout.Alignment.ALIGN_NORMAL
            2 -> Layout.Alignment.ALIGN_CENTER
            else -> Layout.Alignment.ALIGN_OPPOSITE
        }
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            typeface = typeface(context, safeCaption.font)
            color = safeCaption.textColor
        }
        var textSize = min(width, height) * safeCaption.size.shortEdgeFraction
        val minTextSize = max(6f, min(width, height) * 0.012f)
        var layout: StaticLayout
        var shadowPad: Float
        // Extreme text shrinks until the whole block fits the safe rectangle instead of running off-frame.
        do {
            paint.textSize = textSize
            shadowPad = if (safeCaption.shadowEnabled) textSize * 0.12f else 0f
            if (safeCaption.shadowEnabled) {
                val alpha = (safeCaption.shadowOpacity * 255).toInt().coerceIn(0, 255)
                paint.setShadowLayer(textSize * 0.08f, textSize * 0.035f, textSize * 0.04f, (safeCaption.shadowColor and 0x00FFFFFF) or (alpha shl 24))
            } else {
                paint.clearShadowLayer()
            }
            layout = StaticLayout.Builder.obtain(safeCaption.text, 0, safeCaption.text.length, paint, safeWidth)
                .setAlignment(alignment)
                .setLineSpacing(0f, safeCaption.lineSpacing)
                .setIncludePad(true)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .build()
            val fits = layout.height + shadowPad <= safeHeight || textSize <= minTextSize
            if (!fits) textSize = max(minTextSize, textSize * 0.88f)
        } while (!fits)
        var blockWidth = 0f
        for (line in 0 until layout.lineCount) blockWidth = max(blockWidth, layout.getLineWidth(line))
        blockWidth = min(blockWidth, safeWidth.toFloat())
        val free = safeWidth - blockWidth
        val currentLeft = when (alignment) {
            Layout.Alignment.ALIGN_CENTER -> free / 2f
            Layout.Alignment.ALIGN_OPPOSITE -> free
            else -> 0f
        }
        // Still too tall at minTextSize (e.g. many newlines): draw the whole layout uniformly scaled down so it fits.
        val fullHeight = layout.height + shadowPad
        val drawScale = if (fullHeight > safeHeight) safeHeight / fullHeight else 1f
        val scaledFree = safeWidth - blockWidth * drawScale
        val dx = safeCaption.column / 4f * scaledFree - currentLeft * drawScale
        val blockHeight = fullHeight * drawScale
        val dy = safeCaption.row / 4f * (safeHeight - blockHeight)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(AndroidColor.TRANSPARENT)
        val canvas = Canvas(bitmap)
        canvas.save()
        canvas.translate(safeLeft + dx, safeTop + dy)
        if (drawScale < 1f) canvas.scale(drawScale, drawScale)
        layout.draw(canvas)
        canvas.restore()
        return bitmap
    }
}

/** Calendar day text used by "오늘 날짜" and capture-date insertion. */
internal fun captionDateText(millis: Long): String =
    java.text.SimpleDateFormat("yyyy.MM.dd", java.util.Locale.KOREA).format(java.util.Date(millis))

/** Capture date or range of the given sources; null when no capture time is known (never invented). */
internal fun captionCaptureRangeText(sources: List<TrailerSource>): String? {
    val times = sources.map { it.captureTimeMillis }.filter { it > 0L }
    if (times.isEmpty()) return null
    val first = captionDateText(times.min())
    val last = captionDateText(times.max())
    return if (first == last) first else "$first ~ $last"
}

/** Inserts [insertion] at the selection of [text], returning the new text and cursor. */
internal fun insertCaptionText(text: String, selectionStart: Int, selectionEnd: Int, insertion: String): Pair<String, Int> {
    val start = min(selectionStart, selectionEnd).coerceIn(0, text.length)
    val end = max(selectionStart, selectionEnd).coerceIn(0, text.length)
    val merged = text.substring(0, start) + insertion + text.substring(end)
    return merged to (start + insertion.length)
}
