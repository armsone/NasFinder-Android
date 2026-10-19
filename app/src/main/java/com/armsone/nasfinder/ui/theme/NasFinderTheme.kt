package com.armsone.nasfinder.ui.theme

import android.app.Activity
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.armsone.nasfinder.R
import com.armsone.nasfinder.model.AppTheme
import com.armsone.nasfinder.model.InterfaceIconPack
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp
import kotlin.math.pow

val BrowserOrange = Color(0xFFFF9500)

/**
 * One theme's semantic colours (HOME-VISUAL-EXECUTION-2232 §4, opaque sRGB, identical on Apple). [solidAction] is the
 * filled-CTA background carrying [onAccent]; it equals [accent] except BK Style, whose ornament accent #E44435 never
 * carries small white text (its CTA uses #C93629).
 */
@Immutable
data class ThemePalette(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val elevated: Color,
    val border: Color,
    val text: Color,
    val secondary: Color,
    val accent: Color,
    val selected: Color,
    val danger: Color,
    val pressed: Color,
    val onAccent: Color,
    val solidAction: Color = accent,
)

private fun palette(
    isDark: Boolean, background: Long, surface: Long, elevated: Long, border: Long, text: Long, secondary: Long,
    accent: Long, selected: Long, danger: Long, pressed: Long, onAccent: Long, solidAction: Long = accent,
) = ThemePalette(
    isDark, Color(background), Color(surface), Color(elevated), Color(border), Color(text), Color(secondary),
    Color(accent), Color(selected), Color(danger), Color(pressed), Color(onAccent), Color(solidAction),
)

private val LightPalette = palette(
    isDark = false,
    background = 0xFFF5F4F0,
    surface = 0xFFFFFFFF,
    elevated = 0xFFFBFAF7,
    border = 0xFFE2DFD5,
    text = 0xFF1F211C,
    secondary = 0xFF4A4C44,
    accent = 0xFF5C6B3C,
    selected = 0xFFE8EBDB,
    danger = 0xFF9C3B2A,
    pressed = 0xFFECEBE4,
    onAccent = 0xFFFFFFFF,
    solidAction = 0xFF5C6B3C,
)

private val BkStylePalette = palette(
    isDark = false,
    background = 0xFFD9D3C7,
    surface = 0xFFECE8DF,
    elevated = 0xFFE6E1D6,
    border = 0xFFBDB6A7,
    text = 0xFF1F211C,
    secondary = 0xFF3A3A34,
    accent = 0xFF46532C,
    selected = 0xFFCDD0B8,
    danger = 0xFF8A2F1F,
    pressed = 0xFFCFC8BA,
    onAccent = 0xFFFFFFFF,
    solidAction = 0xFF46532C,
)

private val NightPalette = palette(
    isDark = true,
    background = 0xFF1C1C1A,
    surface = 0xFF262622,
    elevated = 0xFF242422,
    border = 0xFF35342E,
    text = 0xFFE9E7DF,
    secondary = 0xFFC2BFB3,
    accent = 0xFF93B163,
    selected = 0xFF2A3022,
    danger = 0xFFE08674,
    pressed = 0xFF2E2E29,
    onAccent = 0xFF14180D,
    solidAction = 0xFF93B163,
)

private val JarvisPalette = palette(
    isDark = true,
    background = 0xFF0C1420,
    surface = 0xFF162337,
    elevated = 0xFF121C2B,
    border = 0xFF20324A,
    text = 0xFFE3EDF7,
    secondary = 0xFFB3C4D6,
    accent = 0xFF4CC2E6,
    selected = 0xFF12324A,
    danger = 0xFFF28B82,
    pressed = 0xFF1C2D44,
    onAccent = 0xFF04202B,
    solidAction = 0xFF4CC2E6,
)

private val FridayPalette = palette(
    isDark = false,
    background = 0xFFF7EFE3,
    surface = 0xFFFFFDF8,
    elevated = 0xFFFFFAF1,
    border = 0xFFE8D8C0,
    text = 0xFF2A1F16,
    secondary = 0xFF55422F,
    accent = 0xFF9A4A12,
    selected = 0xFFF6DFC0,
    danger = 0xFFA3204A,
    pressed = 0xFFF0E2CC,
    onAccent = 0xFFFFFFFF,
    solidAction = 0xFF9A4A12,
)

private val UltronPalette = palette(
    isDark = true,
    background = 0xFF121316,
    surface = 0xFF202328,
    elevated = 0xFF1A1C20,
    border = 0xFF2B2E34,
    text = 0xFFE6E7EA,
    secondary = 0xFFB8BBC2,
    accent = 0xFFF2566A,
    selected = 0xFF3A1A20,
    danger = 0xFFFF9A9A,
    pressed = 0xFF272A30,
    onAccent = 0xFF1A0508,
    solidAction = 0xFFF2566A,
)

fun paletteFor(theme: AppTheme): ThemePalette = when (theme) {
    AppTheme.LIGHT -> LightPalette
    AppTheme.BKSTYLE -> BkStylePalette
    AppTheme.NIGHT -> NightPalette
    AppTheme.JARVIS -> JarvisPalette
    AppTheme.FRIDAY -> FridayPalette
    AppTheme.ULTRON -> UltronPalette
}

object ThemeVectors {
    val Light: ImageVector by lazy {
        ImageVector.Builder(
            name = "ThemeLight",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            // <circle cx="12" cy="12" r="3.8"/>
            addPath(
                pathData = addPathNodes("M 12 8.2 a 3.8 3.8 0 1 0 0.001 0 Z"),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            // <path d="M12 3.5v2.2M12 18.3v2.2M3.5 12h2.2M18.3 12h2.2M6 6l1.6 1.6M16.4 16.4L18 18M6 18l1.6-1.6M16.4 7.6L18 6"/>
            addPath(
                pathData = addPathNodes("M 12 3.5 v 2.2 M 12 18.3 v 2.2 M 3.5 12 h 2.2 M 18.3 12 h 2.2 M 6 6 l 1.6 1.6 M 16.4 16.4 L 18 18 M 6 18 l 1.6 -1.6 M 16.4 7.6 L 18 6"),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    val Medium: ImageVector by lazy {
        ImageVector.Builder(
            name = "ThemeMedium",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            // <circle cx="12" cy="12" r="8"/>
            addPath(
                pathData = addPathNodes("M 12 4 a 8 8 0 1 0 0.001 0 Z"),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            // <path d="M12 4a8 8 0 0 0 0 16z" fill="currentColor"/>
            addPath(
                pathData = addPathNodes("M 12 4 a 8 8 0 0 0 0 16 Z"),
                fill = SolidColor(Color.White),
            )
        }.build()
    }

    val Dark: ImageVector by lazy {
        ImageVector.Builder(
            name = "ThemeDark",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            // <path d="M19.5 14.6A8 8 0 0 1 9.4 4.5a8 8 0 1 0 10.1 10.1z"/>
            addPath(
                pathData = addPathNodes("M 19.5 14.6 A 8 8 0 0 1 9.4 4.5 a 8 8 0 1 0 10.1 10.1 Z"),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    val Jarvis: ImageVector by lazy {
        ImageVector.Builder(
            name = "ThemeJarvis",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            // <circle cx="12" cy="12" r="9" stroke-dasharray="15.4 3.45" transform="rotate(-80 12 12)"/>
            // ImageVector paths have no dash support: circumference 2*pi*9 = 56.549 = 3 periods of 18.85,
            // so the dashes are three separate arcs (15.4 long, starting every 18.85 from 3 o'clock, clockwise),
            // endpoints = (12 + 9cos(s/9), 12 + 9sin(s/9)) for arc length s.
            group(
                rotate = -80f,
                pivotX = 12f,
                pivotY = 12f,
            ) {
                listOf(
                    "M 21 12 A 9 9 0 0 1 10.7413 20.9115",
                    "M 7.4996 19.794 A 9 9 0 0 1 4.912 6.4538",
                    "M 7.5008 4.2053 A 9 9 0 0 1 20.3473 8.6351",
                ).forEach { arc ->
                    addPath(
                        pathData = addPathNodes(arc),
                        stroke = SolidColor(Color.White),
                        strokeLineWidth = 1.7f,
                        strokeLineCap = StrokeCap.Round,
                        strokeLineJoin = StrokeJoin.Round,
                    )
                }
            }
            // <circle cx="12" cy="12" r="5" stroke-width="1.4"/>
            addPath(
                pathData = addPathNodes("M 12 7 a 5 5 0 1 0 0.001 0 Z"),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.4f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            // <circle cx="12" cy="12" r="1.7" fill="currentColor" stroke="none"/>
            addPath(
                pathData = addPathNodes("M 12 10.3 a 1.7 1.7 0 1 0 0.001 0 Z"),
                fill = SolidColor(Color.White),
            )
        }.build()
    }

    val Friday: ImageVector by lazy {
        ImageVector.Builder(
            name = "ThemeFriday",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            // <path d="M12 3l7.8 4.5v9L12 21l-7.8-4.5v-9z"/>
            addPath(
                pathData = addPathNodes("M 12 3 l 7.8 4.5 v 9 L 12 21 l -7.8 -4.5 v -9 Z"),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            // <circle cx="12" cy="12" r="3" fill="currentColor" stroke="none"/>
            addPath(
                pathData = addPathNodes("M 12 9 a 3 3 0 1 0 0.001 0 Z"),
                fill = SolidColor(Color.White),
            )
            // <path d="M12 9V6.5M12 17.5V15M9 12H6.5M17.5 12H15" stroke-width="1.4"/>
            addPath(
                pathData = addPathNodes("M 12 9 V 6.5 M 12 17.5 V 15 M 9 12 H 6.5 M 17.5 12 H 15"),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.4f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    val Ultron: ImageVector by lazy {
        ImageVector.Builder(
            name = "ThemeUltron",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            // <path d="M6 4h12l2.5 5v6l-4 5h-9l-4-5V9z"/>
            addPath(
                pathData = addPathNodes("M 6 4 h 12 l 2.5 5 v 6 l -4 5 h -9 l -4 -5 V 9 Z"),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            // <path d="M7.4 10.4l3.6 1.3-.3 1.4-3.3-.9zM16.6 10.4L13 11.7l.3 1.4 3.3-.9z" fill="currentColor" stroke="none"/>
            addPath(
                pathData = addPathNodes("M 7.4 10.4 l 3.6 1.3 l -0.3 1.4 l -3.3 -0.9 Z M 16.6 10.4 L 13 11.7 l 0.3 1.4 l 3.3 -0.9 Z"),
                fill = SolidColor(Color.White),
            )
            // <path d="M9.5 16.5h5" stroke-width="1.4"/>
            addPath(
                pathData = addPathNodes("M 9.5 16.5 h 5"),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.4f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    fun vectorFor(theme: AppTheme): ImageVector = when (theme) {
        AppTheme.LIGHT -> Light
        AppTheme.BKSTYLE -> Medium
        AppTheme.NIGHT -> Dark
        AppTheme.JARVIS -> Jarvis
        AppTheme.FRIDAY -> Friday
        AppTheme.ULTRON -> Ultron
    }
}

val LocalNasFinderTheme = staticCompositionLocalOf { AppTheme.LIGHT }
val LocalThemePalette = staticCompositionLocalOf { LightPalette }
val LocalInterfaceIconPack = staticCompositionLocalOf { InterfaceIconPack.REALISTIC }

/**
 * Bundled, unmodified Pretendard v1.3.9 static OTFs (SIL OFL 1.1, assets/licenses/Pretendard-OFL-1.1.txt). Each weight is
 * bound to its own file explicitly (the Medium/SemiBold name tables use separate legacy families, so no name lookup).
 */
val Pretendard = FontFamily(
    Font(R.font.pretendard_regular, FontWeight.Normal),
    Font(R.font.pretendard_medium, FontWeight.Medium),
    Font(R.font.pretendard_semibold, FontWeight.SemiBold),
    Font(R.font.pretendard_bold, FontWeight.Bold),
)

/** No synthetic weight, no legacy font padding; line height centred without trimming so glyph tops/bottoms never clip. */
private fun pretendard(size: Int, lineHeight: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = Pretendard,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    fontWeight = weight,
    fontSynthesis = FontSynthesis.None,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

/**
 * Approved-proposal role defaults. Only the home roles below come from HOME-VISUAL-EXECUTION-2232 §1; the rest keep the
 * earlier proposal sizes. Same family for all six themes; sp keeps the user's Android font scale.
 */
object PorcelainType {
    val Brand = pretendard(22, 28, FontWeight.Bold)
    val LargeHeading = pretendard(20, 25, FontWeight.SemiBold)
    val Section = pretendard(17, 22, FontWeight.SemiBold)
    val Row = pretendard(15, 20, FontWeight.Medium)
    val Body = pretendard(13, 18)
    val Caption = pretendard(12, 16)
    val Meta = pretendard(11, 15)
    val Badge = pretendard(10, 13, FontWeight.SemiBold)
    /** 07-states title: 20/600 (proposal). */
    val StateTitle = pretendard(20, 25, FontWeight.SemiBold)
}

/** Home direct roles (HOME-VISUAL-EXECUTION-2232 §1): size / line height / weight exactly as specified. */
object HomeType {
    val Brand = pretendard(22, 28, FontWeight.Bold)
    val Section = pretendard(20, 26, FontWeight.SemiBold)
    val Source = pretendard(14, 19, FontWeight.Medium)
    val Description = pretendard(12, 17)
    val Caption = pretendard(12, 16)
    /** Favorite shelf caption 11/400 (line 14), centered under the 52 cover in the 68 column. */
    val FavoriteCaption = pretendard(11, 14)
    val Tertiary = pretendard(12, 16)
    val Badge = pretendard(10, 13, FontWeight.SemiBold)
    val Cta = pretendard(16, 21, FontWeight.SemiBold)
    /** Browser / 연결 추가 compact action title 14/500. */
    val Compact = pretendard(14, 19, FontWeight.Medium)
}

/**
 * One Material typography for all six themes: SwiftUI semantic counterparts (body 17, subheadline 15, footnote 13,
 * caption 12, caption2 11) with the proposal title/label roles, every role on Pretendard.
 */
private val NasFinderTypography = Typography().let { base ->
    fun TextStyle.onPretendard() = copy(
        fontFamily = Pretendard,
        fontSynthesis = FontSynthesis.None,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )
    Typography(
        displayLarge = base.displayLarge.onPretendard(),
        displayMedium = base.displayMedium.onPretendard(),
        displaySmall = base.displaySmall.onPretendard(),
        headlineLarge = base.headlineLarge.onPretendard(),
        headlineMedium = base.headlineMedium.onPretendard(),
        headlineSmall = base.headlineSmall.onPretendard(),
        titleLarge = PorcelainType.LargeHeading,
        titleMedium = PorcelainType.Section,
        titleSmall = PorcelainType.Row,
        bodyLarge = pretendard(17, 22),
        bodyMedium = pretendard(15, 20),
        bodySmall = PorcelainType.Body,
        labelLarge = pretendard(14, 20),
        labelMedium = PorcelainType.Caption,
        labelSmall = PorcelainType.Meta,
    )
}

/**
 * Every selectable theme shares the one unified (former Porcelain) layout and typography; themes differ only in colour.
 * Layout branches ask this instead of comparing against a single theme id, so no theme falls back to a legacy layout.
 */
@Suppress("UNUSED_PARAMETER")
fun usesUnifiedLayout(theme: AppTheme): Boolean = true

/** Current theme's semantic palette (same values as the Material roles below, plus accent/selected/pressed/danger). */
@Composable
fun themePalette(): ThemePalette = LocalThemePalette.current

/** Folder glyph tint: the theme's primary text colour (Graphite in the light themes). */
fun folderColor(theme: AppTheme): Color = paletteFor(theme).text

@Composable
fun NasFinderTheme(theme: AppTheme, iconPack: InterfaceIconPack = InterfaceIconPack.REALISTIC, content: @Composable () -> Unit) {
    val p = paletteFor(theme)
    val dark = p.isDark
    // Every app-owned Material role is mapped so no Material baseline (purple) default leaks into any theme.
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val themedScheme = base.copy(
        primary = p.solidAction,
        onPrimary = p.onAccent,
        primaryContainer = p.selected,
        onPrimaryContainer = p.text,
        inversePrimary = p.accent,
        secondary = p.text,
        onSecondary = p.background,
        secondaryContainer = p.selected,
        onSecondaryContainer = p.text,
        tertiary = p.solidAction,
        onTertiary = p.onAccent,
        tertiaryContainer = p.selected,
        onTertiaryContainer = p.text,
        background = p.background,
        onBackground = p.text,
        surface = p.surface,
        onSurface = p.text,
        surfaceVariant = p.elevated,
        onSurfaceVariant = p.secondary,
        surfaceTint = Color.Transparent,
        inverseSurface = p.text,
        inverseOnSurface = p.surface,
        error = p.danger,
        onError = if (dark) p.background else Color.White,
        errorContainer = p.selected,
        onErrorContainer = p.text,
        outline = p.secondary,
        outlineVariant = p.border,
        surfaceBright = p.elevated,
        surfaceDim = p.background,
        surfaceContainerLowest = p.surface,
        surfaceContainerLow = p.surface,
        surfaceContainer = p.surface,
        surfaceContainerHigh = p.elevated,
        surfaceContainerHighest = p.elevated,
    )
    val activity = androidx.compose.ui.platform.LocalContext.current as? Activity
    SideEffect {
        activity?.window?.let {
            it.statusBarColor = Color.Transparent.toArgb()
            it.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(it, it.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalNasFinderTheme provides theme, LocalThemePalette provides p, LocalInterfaceIconPack provides iconPack) {
        MaterialTheme(
            colorScheme = themedScheme,
            typography = NasFinderTypography,
        ) {
            // Transparent Scaffolds resolve their content colour from here; without it uncoloured text falls back to black.
            CompositionLocalProvider(LocalContentColor provides p.text, content = content)
        }
    }
}

fun serviceColor(identifier: String, theme: AppTheme): Color {
    val base = when (identifier) {
        "SYNOLOGY" -> Color(0xFF0067E6); "SFTP" -> Color(0xFF218739)
        "SMB" -> Color(0xFF0F6CBD); "WEBDAV" -> Color(0xFF6554C0)
        "FTP" -> Color(0xFFE87500); "DROPBOX" -> Color(0xFF0061FF)
        "ONEDRIVE" -> Color(0xFF0078D4); "GOOGLE_DRIVE" -> Color(0xFF34A853)
        "WEBHARD" -> Color(0xFF5856D6)
        else -> Color(0xFF64748B)
    }
    // Light themes pull the brand colours slightly toward graphite text, dark themes slightly toward their light text.
    val p = paletteFor(theme)
    val target = p.text to if (p.isDark) .08f else .12f
    return Color(
        red = base.red + (target.first.red - base.red) * target.second,
        green = base.green + (target.first.green - base.green) * target.second,
        blue = base.blue + (target.first.blue - base.blue) * target.second,
        alpha = 1f,
    )
}

/** Mirrors ThemeServiceStyle's black/white contrast choice for filled service badges. */
fun serviceForegroundColor(identifier: String, theme: AppTheme): Color {
    val color = serviceColor(identifier, theme)
    fun linearized(component: Float): Double {
        val value = component.toDouble()
        return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    }
    val luminance = 0.2126 * linearized(color.red) +
        0.7152 * linearized(color.green) +
        0.0722 * linearized(color.blue)
    val whiteContrast = 1.05 / (luminance + 0.05)
    val blackContrast = (luminance + 0.05) / 0.05
    return if (blackContrast >= whiteContrast) Color.Black else Color.White
}

/** File metadata: the theme's secondary text. */
@Composable
fun fileMetaColor(): Color = themePalette().secondary

/** Thumbnail well: the theme's surface. */
@Composable
fun thumbnailSurfaceColor(): Color = themePalette().surface

/** Thumbnail hairline: the theme's border. */
@Composable
fun thumbnailBorderColor(): Color = themePalette().border
