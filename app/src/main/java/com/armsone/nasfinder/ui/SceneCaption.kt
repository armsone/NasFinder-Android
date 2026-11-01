package com.armsone.nasfinder.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/** Longest caption text the editor accepts per scene (input limit only; stored text is never cut on load). */
internal const val MAX_SCENE_CAPTION_CHARS = 2000

/**
 * Bundled Pretendard faces plus platform serif/monospace and all HanClip bundled fonts;
 * retains all original enum IDs for backward compatibility.
 */
enum class CaptionFont(val title: String, val fontId: String) {
    PRETENDARD("프리텐다드", "pretendard"),
    PRETENDARD_BOLD("프리텐다드 굵게", "pretendard_bold"),
    SERIF("명조(시스템)", "serif"),
    MONOSPACE("고정폭(시스템)", "monospace"),
    KAKAO_BIG_SANS("카카오", "kakao_big_sans"),
    GOWUN_BATANG("고운바탕", "gowun_batang"),
    GOWUN_DODUM("고운돋움", "gowun_dodum"),
    NANUM_GOTHIC("나눔고딕", "nanum_gothic"),
    CAFE24_SSURROUND("써라운드", "cafe24_ssurround"),
    PURADAK_GENTLE_GOTHIC("젠틀고딕", "puradak_gentle_gothic"),
    TENADA("태나다", "tenada"),
    DO_HYEON("도현", "do_hyeon"),
    BLACK_HAN_SANS("검은고딕", "black_han_sans"),
    MARUBURI("마루부리", "maruburi"),
    DDULGI_MAYO("둘기마요", "ddulgi_mayo"),
    PAPERLOGY_BOLD("페이퍼로지B", "paperlogy_bold"),
    NEXON_LV1_GOTHIC("넥슨Lv1", "nexon_lv1_gothic"),
    POPPINS("Poppins", "poppins"),
    IMPORTED("사용자 글꼴", "imported");

    companion object {
        fun fromFontId(id: String): CaptionFont {
            if (id.startsWith(CaptionFontStore.ID_PREFIX)) return IMPORTED
            return entries.firstOrNull { it.fontId == id }
                ?: when (id.lowercase()) {
                    "pretendard" -> PRETENDARD
                    "pretendard_bold", "pretendard-bold" -> PRETENDARD_BOLD
                    "serif" -> SERIF
                    "monospace" -> MONOSPACE
                    "kakao", "kakao_big_sans" -> KAKAO_BIG_SANS
                    "gowun_batang" -> GOWUN_BATANG
                    "gowun_dodum" -> GOWUN_DODUM
                    "nanum_gothic" -> NANUM_GOTHIC
                    "cafe24_ssurround", "cafe24ssurround" -> CAFE24_SSURROUND
                    "puradak_gentle_gothic", "puradak_gentle" -> PURADAK_GENTLE_GOTHIC
                    "tenada" -> TENADA
                    "do_hyeon", "dohyeon" -> DO_HYEON
                    "black_han_sans", "blackhansans" -> BLACK_HAN_SANS
                    "maruburi", "maru_buri" -> MARUBURI
                    "ddulgi_mayo", "dulgimayo" -> DDULGI_MAYO
                    "paperlogy_bold", "paperlogy" -> PAPERLOGY_BOLD
                    "nexon_lv1_gothic", "nexon" -> NEXON_LV1_GOTHIC
                    "poppins" -> POPPINS
                    else -> PRETENDARD_BOLD
                }
        }
    }
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
 * [fontName] stores the stable font ID (either built-in id or imported_font:filename).
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
    val fontName: String = "",
) {
    val effectiveFontName: String
        get() = fontName.ifBlank { font.fontId }

    fun withFont(newFontId: String): SceneCaption {
        val enumFont = CaptionFont.fromFontId(newFontId)
        return copy(font = enumFont, fontName = newFontId)
    }

    /** Clamped settings; blank text means "no caption" (null). Text itself is kept as typed. */
    fun normalizedOrNull(): SceneCaption? = if (text.isBlank()) null else clamped()

    /** Settings clamped to their valid ranges (text untouched, may be blank while editing). */
    fun clamped(): SceneCaption {
        val effectiveName = if (fontName.isNotBlank()) fontName else font.fontId
        return copy(
            textColor = textColor or 0xFF000000.toInt(),
            shadowColor = shadowColor or 0xFF000000.toInt(),
            shadowOpacity = if (shadowOpacity.isFinite()) shadowOpacity.coerceIn(0f, 1f) else 0.7f,
            lineSpacing = if (lineSpacing.isFinite()) lineSpacing.coerceIn(0.5f, 2.0f) else 1.0f,
            column = column.coerceIn(0, 4),
            row = row.coerceIn(0, 4),
            fontName = effectiveName,
        )
    }

    /** Same text, style taken from [preset]. Row and column are preserved. */
    fun withStyleOf(preset: SceneCaption) = preset.copy(
        text = text,
        column = column,
        row = row,
    )

    companion object {
        val Readable = SceneCaption(text = "", font = CaptionFont.PRETENDARD_BOLD, fontName = "pretendard_bold", size = CaptionSize.LARGE, textColor = 0xFFFFFFFF.toInt(), shadowEnabled = true, shadowColor = 0xFF000000.toInt(), shadowOpacity = 0.85f, lineSpacing = 1.0f, column = 2, row = 4)
        val Cinema = SceneCaption(text = "", font = CaptionFont.SERIF, fontName = "serif", size = CaptionSize.NORMAL, textColor = 0xFFF4EEDF.toInt(), shadowEnabled = true, shadowColor = 0xFF000000.toInt(), shadowOpacity = 0.55f, lineSpacing = 1.2f, column = 2, row = 3)
        val Travel = SceneCaption(text = "", font = CaptionFont.PRETENDARD_BOLD, fontName = "pretendard_bold", size = CaptionSize.LARGE, textColor = 0xFFFFE45C.toInt(), shadowEnabled = true, shadowColor = 0xFF1A1A1A.toInt(), shadowOpacity = 0.7f, lineSpacing = 1.0f, column = 0, row = 0)
        val PRESETS = listOf("가독성" to Readable, "시네마" to Cinema, "여행" to Travel)
    }
}

internal fun SceneCaption.toJson(): JSONObject = JSONObject().apply {
    put("text", text)
    put("font", font.name)
    put("fontName", effectiveFontName)
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
    val rawFontName = obj.optString("fontName", "").ifBlank {
        obj.optString("font", "")
    }
    val resolvedFont = runCatching {
        CaptionFont.valueOf(obj.optString("font"))
    }.getOrElse {
        CaptionFont.fromFontId(rawFontName)
    }
    val effectiveFontName = if (rawFontName.isNotBlank()) {
        if (CaptionFontStore.isImported(rawFontName)) rawFontName
        else CaptionFont.fromFontId(rawFontName).fontId
    } else resolvedFont.fontId

    return SceneCaption(
        text = obj.optString("text", ""),
        font = resolvedFont,
        size = runCatching { CaptionSize.valueOf(obj.optString("size")) }.getOrDefault(defaults.size),
        textColor = obj.optInt("textColor", defaults.textColor),
        shadowEnabled = obj.optBoolean("shadowEnabled", defaults.shadowEnabled),
        shadowColor = obj.optInt("shadowColor", defaults.shadowColor),
        shadowOpacity = obj.optDouble("shadowOpacity", defaults.shadowOpacity.toDouble()).toFloat(),
        lineSpacing = obj.optDouble("lineSpacing", defaults.lineSpacing.toDouble()).toFloat(),
        column = obj.optInt("column", defaults.column),
        row = obj.optInt("row", defaults.row),
        fontName = effectiveFontName,
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

    fun typeface(context: Context, font: CaptionFont): Typeface = CaptionFontStore.typeface(context, font.fontId)

    fun typeface(context: Context, fontName: String): Typeface = CaptionFontStore.typeface(context, fontName)

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
            typeface = CaptionFontStore.typeface(context, safeCaption.effectiveFontName)
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
internal fun captionDateText(millis: Long): String = CaptionDateFormatter.single(millis)

/** Capture date or range of the given sources; null when no capture time is known (never invented). */
internal fun captionCaptureRangeText(sources: List<TrailerSource>): String? {
    val times = sources.map { it.captureTimeMillis }.filter { it > 0L }
    if (times.isEmpty()) return null
    return CaptionDateFormatter.range(times)
}

/** Inserts [insertion] at the selection of [text], returning the new text and cursor. */
internal fun insertCaptionText(text: String, selectionStart: Int, selectionEnd: Int, insertion: String): Pair<String, Int> {
    val start = min(selectionStart, selectionEnd).coerceIn(0, text.length)
    val end = max(selectionStart, selectionEnd).coerceIn(0, text.length)
    val merged = text.substring(0, start) + insertion + text.substring(end)
    return merged to (start + insertion.length)
}
