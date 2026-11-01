package com.armsone.nasfinder.ui

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.text.font.FontFamily
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class CaptionFontCategory(val title: String) {
    BASIC("기본"),
    GOLF("골프"),
    TITLE("제목"),
    VLOG("브이로그"),
    EMOTIONAL("감성"),
    HANDWRITING("손글씨"),
    USER("사용자");
}

data class CaptionFontItem(
    val id: String,
    val displayName: String,
    val category: CaptionFontCategory,
    val assetPath: String? = null,
    val isSystem: Boolean = false,
)

data class ImportedFontSummary(
    val id: String,
    val displayName: String,
)

object CaptionFontStore {
    const val DIRECTORY_NAME = "imported-fonts"
    const val ID_PREFIX = "imported_font:"
    const val MAXIMUM_FONT_COUNT = 30

    private val typefaceCache = ConcurrentHashMap<String, Typeface>()

    val BUNDLED_FONTS: List<CaptionFontItem> = listOf(
        // 기본 (Basic)
        CaptionFontItem("nanum_gothic", "나눔고딕", CaptionFontCategory.BASIC, "fonts/nanum_gothic_regular.ttf"),
        CaptionFontItem("kakao_big_sans", "카카오", CaptionFontCategory.BASIC, "fonts/kakao_big_sans_regular.ttf"),
        CaptionFontItem("pretendard", "프리텐다드", CaptionFontCategory.BASIC, "fonts/pretendard_regular.otf"),
        CaptionFontItem("pretendard_bold", "프리텐다드 굵게", CaptionFontCategory.BASIC, "fonts/pretendard_bold.ttf"),
        CaptionFontItem("nexon_lv1_gothic", "넥슨Lv1", CaptionFontCategory.BASIC, "fonts/nexon_lv1_gothic.ttf"),
        CaptionFontItem("serif", "명조(시스템)", CaptionFontCategory.BASIC, isSystem = true),
        CaptionFontItem("monospace", "고정폭(시스템)", CaptionFontCategory.BASIC, isSystem = true),

        // 골프 (Golf)
        CaptionFontItem("puradak_gentle_gothic", "젠틀고딕", CaptionFontCategory.GOLF, "fonts/puradak_gentle_gothic.ttf"),

        // 제목 (Title)
        CaptionFontItem("tenada", "태나다", CaptionFontCategory.TITLE, "fonts/tenada.ttf"),
        CaptionFontItem("black_han_sans", "검은고딕", CaptionFontCategory.TITLE, "fonts/black_han_sans_regular.ttf"),
        CaptionFontItem("paperlogy_bold", "페이퍼로지B", CaptionFontCategory.TITLE, "fonts/paperlogy_bold.ttf"),

        // 브이로그 (Vlog)
        CaptionFontItem("poppins", "Poppins", CaptionFontCategory.VLOG, "fonts/poppins_regular.ttf"),
        CaptionFontItem("cafe24_ssurround", "써라운드", CaptionFontCategory.VLOG, "fonts/cafe24_ssurround.ttf"),
        CaptionFontItem("do_hyeon", "도현", CaptionFontCategory.VLOG, "fonts/do_hyeon_regular.ttf"),

        // 감성 (Emotional)
        CaptionFontItem("maruburi", "마루부리", CaptionFontCategory.EMOTIONAL, "fonts/maru_buri_regular.ttf"),
        CaptionFontItem("gowun_dodum", "고운돋움", CaptionFontCategory.EMOTIONAL, "fonts/gowun_dodum_regular.ttf"),
        CaptionFontItem("gowun_batang", "고운바탕", CaptionFontCategory.EMOTIONAL, "fonts/gowun_batang_regular.ttf"),

        // 손글씨 (Handwriting)
        CaptionFontItem("ddulgi_mayo", "둘기마요", CaptionFontCategory.HANDWRITING, "fonts/ddulgi_mayo.otf"),
    )

    fun list(context: Context): List<ImportedFontSummary> {
        return directory(context).listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in setOf("ttf", "otf") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { file ->
                ImportedFontSummary(
                    id = ID_PREFIX + file.name,
                    displayName = displayNameFromFilename(file.name),
                )
            }
            .orEmpty()
    }

    fun canImportMoreFonts(currentCount: Int): Boolean {
        return currentCount.coerceAtLeast(0) < MAXIMUM_FONT_COUNT
    }

    fun isImported(id: String): Boolean = id.startsWith(ID_PREFIX)

    fun hasImportedFont(context: Context, id: String): Boolean {
        if (!isImported(id)) return false
        val filename = id.removePrefix(ID_PREFIX)
        if (filename != File(filename).name) return false
        val file = File(directory(context), filename)
        return file.isFile && file.length() > 0L
    }

    fun displayName(id: String): String {
        if (isImported(id)) {
            val filename = id.removePrefix(ID_PREFIX)
            return displayNameFromFilename(filename)
        }
        return BUNDLED_FONTS.firstOrNull { it.id == id }?.displayName
            ?: when (id) {
                "pretendard" -> "프리텐다드"
                "pretendard_bold" -> "프리텐다드 굵게"
                "kakao_big_sans", "kakao" -> "카카오"
                "gowun_batang" -> "고운바탕"
                "gowun_dodum" -> "고운돋움"
                "nanum_gothic" -> "나눔고딕"
                "cafe24_ssurround" -> "써라운드"
                "puradak_gentle_gothic", "puradak_gentle" -> "젠틀고딕"
                "tenada" -> "태나다"
                "do_hyeon" -> "도현"
                "black_han_sans" -> "검은고딕"
                "maruburi" -> "마루부리"
                "ddulgi_mayo" -> "둘기마요"
                "paperlogy_bold" -> "페이퍼로지B"
                "nexon_lv1_gothic" -> "넥슨Lv1"
                "poppins" -> "Poppins"
                "serif" -> "명조(시스템)"
                "monospace" -> "고정폭(시스템)"
                else -> id
            }
    }

    fun import(context: Context, uri: Uri): ImportedFontSummary {
        val currentFonts = list(context)
        check(canImportMoreFonts(currentFonts.size)) {
            "사용자 글꼴은 최대 ${MAXIMUM_FONT_COUNT}개까지 보관할 수 있습니다. 기존 프로젝트의 글꼴을 보호하기 위해 새 파일을 추가하지 않았습니다."
        }
        val sourceName = queryDisplayName(context, uri)
            ?: uri.lastPathSegment
            ?: "font.ttf"
        val extension = sourceName.substringAfterLast('.', "ttf").lowercase()
            .takeIf { it in setOf("ttf", "otf") }
            ?: "ttf"
        val baseName = sourceName.substringBeforeLast('.', sourceName)
            .replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")
            .trim()
            .take(48)
            .ifBlank { "사용자 글꼴" }
        val target = File(
            directory(context),
            "$baseName--${UUID.randomUUID()}.$extension"
        )
        val staging = File(directory(context), ".font-staging-${UUID.randomUUID()}.$extension")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(staging).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            } ?: error("글꼴 파일을 열 수 없습니다.")
            require(staging.length() > 0L) { "비어 있는 글꼴 파일은 가져올 수 없습니다." }
            require(hasSupportedFontStructure(staging)) { "TTF 또는 OTF 글꼴 파일이 아닙니다." }
            runCatching { Typeface.createFromFile(staging) }
                .getOrElse { error ->
                    throw IllegalArgumentException("TTF 또는 OTF 글꼴 파일이 아닙니다.", error)
                }
            check(staging.renameTo(target)) { "검증한 글꼴 파일을 저장하지 못했습니다." }
        } catch (error: Throwable) {
            staging.delete()
            target.delete()
            throw error
        } finally {
            staging.delete()
        }
        val summary = ImportedFontSummary(
            id = ID_PREFIX + target.name,
            displayName = displayNameFromFilename(target.name),
        )
        // Warm typeface cache immediately
        runCatching { Typeface.createFromFile(target) }.getOrNull()?.let {
            typefaceCache[summary.id] = it
        }
        return summary
    }

    internal fun hasSupportedFontStructure(file: File): Boolean = runCatching {
        if (!file.isFile || file.length() < 12L) return@runCatching false
        RandomAccessFile(file, "r").use { input ->
            val signature = input.readInt()
            if (signature == TRUE_TYPE_COLLECTION_SIGNATURE) {
                input.readInt()
                val fontCount = input.readInt()
                fontCount in 1..MAXIMUM_COLLECTION_FONT_COUNT &&
                    12L + fontCount * 4L <= input.length()
            } else {
                if (signature !in SUPPORTED_SFNT_SIGNATURES) return@use false
                val tableCount = input.readUnsignedShort()
                tableCount in 1..MAXIMUM_TABLE_COUNT &&
                    12L + tableCount * 16L <= input.length()
            }
        }
    }.getOrDefault(false)

    fun typeface(context: Context, fontId: String): Typeface {
        typefaceCache[fontId]?.let { return it }

        if (isImported(fontId)) {
            val loaded = loadImportedTypeface(context, fontId)
            if (loaded != null) {
                typefaceCache[fontId] = loaded
                return loaded
            }
            // If missing import, do not cache bogus typeface; fall back to safe default
            return Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }

        val loaded = when (fontId) {
            "serif" -> Typeface.SERIF
            "monospace" -> Typeface.MONOSPACE
            else -> {
                val assetPath = when (fontId) {
                    "pretendard_bold" -> "fonts/pretendard_bold.ttf"
                    "kakao_big_sans", "kakao" -> "fonts/kakao_big_sans_regular.ttf"
                    "gowun_batang" -> "fonts/gowun_batang_regular.ttf"
                    "gowun_dodum" -> "fonts/gowun_dodum_regular.ttf"
                    "nanum_gothic" -> "fonts/nanum_gothic_regular.ttf"
                    "cafe24_ssurround" -> "fonts/cafe24_ssurround.ttf"
                    "puradak_gentle_gothic", "puradak_gentle" -> "fonts/puradak_gentle_gothic.ttf"
                    "tenada" -> "fonts/tenada.ttf"
                    "do_hyeon" -> "fonts/do_hyeon_regular.ttf"
                    "black_han_sans" -> "fonts/black_han_sans_regular.ttf"
                    "maruburi" -> "fonts/maru_buri_regular.ttf"
                    "ddulgi_mayo" -> "fonts/ddulgi_mayo.otf"
                    "paperlogy_bold" -> "fonts/paperlogy_bold.ttf"
                    "nexon_lv1_gothic" -> "fonts/nexon_lv1_gothic.ttf"
                    "poppins" -> "fonts/poppins_regular.ttf"
                    "pretendard" -> "fonts/pretendard_regular.otf"
                    else -> "fonts/pretendard_bold.ttf"
                }
                runCatching { Typeface.createFromAsset(context.assets, assetPath) }.getOrNull()
                    ?: Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            }
        }
        typefaceCache[fontId] = loaded
        return loaded
    }

    private fun loadImportedTypeface(context: Context, id: String): Typeface? {
        val filename = id.removePrefix(ID_PREFIX)
        if (filename != File(filename).name) return null
        val file = File(directory(context), filename)
        if (!file.isFile || file.length() <= 0L) return null
        return runCatching { Typeface.createFromFile(file) }.getOrNull()
    }

    fun fontFamilyForName(context: Context, fontId: String): FontFamily {
        val tf = typeface(context, fontId)
        return FontFamily(tf)
    }

    private fun directory(context: Context): File {
        return File(context.filesDir, DIRECTORY_NAME).apply { mkdirs() }
    }

    private fun displayNameFromFilename(filename: String): String {
        return filename.substringBeforeLast('.')
            .substringBeforeLast("--")
            .ifBlank { "사용자 글꼴" }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }

    private const val TRUE_TYPE_COLLECTION_SIGNATURE = 0x74746366
    private const val MAXIMUM_COLLECTION_FONT_COUNT = 256
    private const val MAXIMUM_TABLE_COUNT = 4_096
    private val SUPPORTED_SFNT_SIGNATURES = setOf(
        0x00010000,
        0x4F54544F,
        0x74727565,
        0x74797031,
    )
}
