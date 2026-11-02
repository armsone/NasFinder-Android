package com.armsone.nasfinder.ui

import android.content.Context
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

enum class CaptionStylePreset(
    val title: String,
    val fontName: String,
    val previewTextColorHex: String,
    val shadowColorHex: String,
    val fontSize: CaptionSize,
    val lineSpacing: Float = 1.0f,
    val shadowOpacity: Float = 0.5f,
) {
    Readable(
        title = "가독성",
        fontName = "pretendard",
        previewTextColorHex = "#FFFFFF",
        shadowColorHex = "#000000",
        fontSize = CaptionSize.LARGE,
        shadowOpacity = 0.5f,
    ),
    Lovely(
        title = "러블리",
        fontName = "ddulgi_mayo",
        previewTextColorHex = "#FF6FAE",
        shadowColorHex = "#7A3FFF",
        fontSize = CaptionSize.LARGE,
        shadowOpacity = 0.5f,
    ),
    Strong(
        title = "강력햐",
        fontName = "tenada",
        previewTextColorHex = "#FFE600",
        shadowColorHex = "#000000",
        fontSize = CaptionSize.XLARGE,
        shadowOpacity = 0.5f,
    ),
    Fresh(
        title = "청량",
        fontName = "gowun_dodum",
        previewTextColorHex = "#FFFFFF",
        shadowColorHex = "#18A8FF",
        fontSize = CaptionSize.LARGE,
        shadowOpacity = 0.5f,
    ),
    Travel(
        title = "여행",
        fontName = "gowun_batang",
        previewTextColorHex = "#FFF3D6",
        shadowColorHex = "#3F6F63",
        fontSize = CaptionSize.LARGE,
        shadowOpacity = 0.5f,
    ),
    Cinema(
        title = "시네마",
        fontName = "black_han_sans",
        previewTextColorHex = "#F8F3E7",
        shadowColorHex = "#141414",
        fontSize = CaptionSize.XLARGE,
        shadowOpacity = 0.5f,
    ),
    Daily(
        title = "데일리",
        fontName = "do_hyeon",
        previewTextColorHex = "#FFFFFF",
        shadowColorHex = "#FF7A3D",
        fontSize = CaptionSize.LARGE,
        shadowOpacity = 0.5f,
    ),
    Sentimental(
        title = "감성",
        fontName = "gowun_batang",
        previewTextColorHex = "#FFE9F0",
        shadowColorHex = "#6E5BFF",
        fontSize = CaptionSize.NORMAL,
        shadowOpacity = 0.5f,
    ),
    GreenGolf(
        title = "그린골프",
        fontName = "do_hyeon",
        previewTextColorHex = "#FFFFFF",
        shadowColorHex = "#10B85A",
        fontSize = CaptionSize.XLARGE,
        shadowOpacity = 0.5f,
    ),
    Magazine(
        title = "매거진",
        fontName = "paperlogy_bold",
        previewTextColorHex = "#FFF4D6",
        shadowColorHex = "#D94A32",
        fontSize = CaptionSize.XLARGE,
        shadowOpacity = 0.55f,
    ),
    Sports(
        title = "스포츠",
        fontName = "paperlogy_bold",
        previewTextColorHex = "#D8FF3E",
        shadowColorHex = "#10223A",
        fontSize = CaptionSize.XLARGE,
        shadowOpacity = 0.7f,
    ),
    Clean(
        title = "클린",
        fontName = "nexon_lv1_gothic",
        previewTextColorHex = "#FFFFFF",
        shadowColorHex = "#1B4D89",
        fontSize = CaptionSize.LARGE,
        shadowOpacity = 0.35f,
    ),
    Neon(
        title = "네온",
        fontName = "nexon_lv1_gothic",
        previewTextColorHex = "#7DF9FF",
        shadowColorHex = "#6C2BFF",
        fontSize = CaptionSize.LARGE,
        shadowOpacity = 0.8f,
    ),
    Vlog(
        title = "VLOG",
        fontName = "poppins",
        previewTextColorHex = "#FFFFFF",
        shadowColorHex = "#FF6B5E",
        fontSize = CaptionSize.LARGE,
        shadowOpacity = 0.55f,
    ),
    Pop(
        title = "POP",
        fontName = "poppins",
        previewTextColorHex = "#FFE45C",
        shadowColorHex = "#642BFF",
        fontSize = CaptionSize.XLARGE,
        shadowOpacity = 0.75f,
    );

    val defaultAppearance: CaptionPresetAppearance
        get() = CaptionPresetAppearance(
            textColorHex = previewTextColorHex,
            shadowColorHex = shadowColorHex,
            shadowOpacity = shadowOpacity,
            fontSize = fontSize,
            lineSpacing = lineSpacing,
        )

    fun applyTo(
        caption: SceneCaption,
        appearance: CaptionPresetAppearance = defaultAppearance,
    ): SceneCaption {
        val parsedTextColor = parseCaptionHex(appearance.textColorHex) ?: 0xFFFFFFFF.toInt()
        val parsedShadowColor = parseCaptionHex(appearance.shadowColorHex) ?: 0xFF000000.toInt()
        return caption.copy(
            font = CaptionFont.fromFontId(fontName),
            fontName = fontName,
            textColor = parsedTextColor,
            shadowEnabled = appearance.shadowOpacity > 0f,
            shadowColor = parsedShadowColor,
            shadowOpacity = appearance.shadowOpacity,
            size = appearance.fontSize,
            lineSpacing = appearance.lineSpacing.coerceIn(0.5f, 2.0f),
        )
    }

    fun matches(
        caption: SceneCaption,
        appearance: CaptionPresetAppearance = defaultAppearance,
    ): Boolean {
        val parsedTextColor = parseCaptionHex(appearance.textColorHex) ?: 0xFFFFFFFF.toInt()
        val parsedShadowColor = parseCaptionHex(appearance.shadowColorHex) ?: 0xFF000000.toInt()
        val appearanceShadowEnabled = appearance.shadowOpacity > 0f
        val shadowMatches = if (!appearanceShadowEnabled) {
            !caption.shadowEnabled || caption.shadowOpacity < 0.05f
        } else {
            caption.shadowEnabled &&
                (caption.shadowColor or 0xFF000000.toInt()) == parsedShadowColor &&
                abs(caption.shadowOpacity - appearance.shadowOpacity) < 0.05f
        }
        return caption.effectiveFontName == fontName &&
            (caption.textColor or 0xFF000000.toInt()) == parsedTextColor &&
            shadowMatches &&
            caption.size == appearance.fontSize &&
            abs(caption.lineSpacing - appearance.lineSpacing) < 0.05f
    }
}

data class CaptionPresetAppearance(
    val textColorHex: String,
    val shadowColorHex: String,
    val shadowOpacity: Float,
    val fontSize: CaptionSize,
    val lineSpacing: Float,
) {
    companion object {
        fun from(caption: SceneCaption): CaptionPresetAppearance {
            val effectiveOpacity = if (caption.shadowEnabled) caption.shadowOpacity.coerceIn(0f, 1f) else 0f
            return CaptionPresetAppearance(
                textColorHex = captionHex(caption.textColor),
                shadowColorHex = captionHex(caption.shadowColor),
                shadowOpacity = effectiveOpacity,
                fontSize = caption.size,
                lineSpacing = caption.lineSpacing.coerceIn(0.5f, 2.0f),
            )
        }
    }
}

object CaptionPresetStore {
    private const val PREFERENCES_NAME = "hanclip_caption"
    private const val KEY_PRESET_APPEARANCES = "caption_preset_appearances"

    fun load(context: Context): Map<CaptionStylePreset, CaptionPresetAppearance> {
        val raw = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PRESET_APPEARANCES, null) ?: return emptyMap()
        return runCatching {
            val root = JSONObject(raw)
            CaptionStylePreset.entries.mapNotNull { preset ->
                val value = root.optJSONObject(preset.name) ?: return@mapNotNull null
                preset to CaptionPresetAppearance(
                    textColorHex = value.optString("textColorHex", preset.previewTextColorHex),
                    shadowColorHex = value.optString("shadowColorHex", preset.defaultAppearance.shadowColorHex),
                    shadowOpacity = value.optDouble("shadowOpacity", preset.defaultAppearance.shadowOpacity.toDouble())
                        .toFloat().coerceIn(0f, 1f),
                    fontSize = runCatching {
                        CaptionSize.valueOf(value.getString("fontSize"))
                    }.getOrDefault(preset.defaultAppearance.fontSize),
                    lineSpacing = value.optDouble("lineSpacing", preset.defaultAppearance.lineSpacing.toDouble())
                        .toFloat().coerceIn(0.5f, 2.0f),
                )
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    fun save(context: Context, values: Map<CaptionStylePreset, CaptionPresetAppearance>) {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        if (values.isEmpty()) {
            preferences.edit().remove(KEY_PRESET_APPEARANCES).apply()
            return
        }
        val root = JSONObject()
        values.forEach { (preset, appearance) ->
            root.put(preset.name, JSONObject().apply {
                put("textColorHex", appearance.textColorHex)
                put("shadowColorHex", appearance.shadowColorHex)
                put("shadowOpacity", appearance.shadowOpacity.toDouble())
                put("fontSize", appearance.fontSize.name)
                put("lineSpacing", appearance.lineSpacing.toDouble())
            })
        }
        preferences.edit().putString(KEY_PRESET_APPEARANCES, root.toString()).apply()
    }

    fun reset(context: Context) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PRESET_APPEARANCES)
            .apply()
    }
}

enum class CaptionTextShortcut(val title: String) {
    Today("오늘 날짜"),
    Empty("비우기");

    fun text(now: LocalDate = LocalDate.now()): String {
        val dateText = CaptionDateFormatter.single(now)
        return when (this) {
            Today -> dateText
            Empty -> ""
        }
    }
}

object CaptionDateFormatter {
    private val singleDateFormatter = DateTimeFormatter.ofPattern("yy. M. d. (EEE)", Locale.KOREAN)

    fun single(date: LocalDate = LocalDate.now()): String = date.format(singleDateFormatter)

    fun single(millis: Long, zoneId: ZoneId = ZoneId.systemDefault()): String {
        val date = Instant.ofEpochMilli(millis).atZone(zoneId).toLocalDate()
        return single(date)
    }

    fun range(
        createdAtMillis: List<Long>,
        fallbackDate: LocalDate = LocalDate.now(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String {
        val validMillis = createdAtMillis.filter { it > 0L }
        val dates = validMillis
            .map { millis -> Instant.ofEpochMilli(millis).atZone(zoneId).toLocalDate() }
            .sorted()
        val first = dates.firstOrNull() ?: fallbackDate
        val last = dates.lastOrNull() ?: first
        val firstText = single(first).replace(". (", ".(")
        if (first == last) return firstText

        val nonBreakingRecentDate = single(last)
            .replace(". (", ".(")
            .replace(" ", "\u00A0")
        return "$firstText ~\u00A0$nonBreakingRecentDate"
    }

    fun insertDateText(
        currentText: String,
        selectionStart: Int,
        selectionEnd: Int,
        dateText: String,
        replaceBasicText: Boolean,
    ): Pair<String, Int> {
        if (replaceBasicText) {
            return dateText to dateText.length
        }
        val lower = minOf(selectionStart, selectionEnd).coerceIn(0, currentText.length)
        val upper = maxOf(selectionStart, selectionEnd).coerceIn(lower, currentText.length)
        val result = currentText.replaceRange(lower, upper, dateText)
        return result to (lower + dateText.length)
    }

    fun isBasicCaptionText(text: String, rangeText: String? = null): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true
        val today = CaptionDateFormatter.single()
        return trimmed == today ||
            (rangeText != null && trimmed == rangeText.trim()) ||
            trimmed == "여기에 글을 넣으세요." ||
            trimmed == "여기에 글을 넣으세요"
    }
}
