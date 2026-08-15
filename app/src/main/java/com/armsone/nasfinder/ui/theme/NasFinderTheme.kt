package com.armsone.nasfinder.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.armsone.nasfinder.model.AppTheme
import kotlin.math.pow

val NasBlue = Color(0xFF007AFF)
val SftpGreen = Color(0xFF34C759)
val BrowserOrange = Color(0xFFFF9500)
val FolderBlue = Color(0xFF1478B3)

private val NasFinderTypography = Typography(
    // SwiftUI semantic counterparts: body 17, subheadline 15, footnote 13,
    // caption 12 and caption2 11. sp preserves Android fontScale behavior.
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp),
)

/** Exact SkyBreeze folder tint, including the iOS adaptive dark variant. */
@Composable
fun folderColor(theme: AppTheme): Color = when (theme) {
    AppTheme.DIGITAL_RAIN -> Color(0xFF3BD6B0)
    AppTheme.WINDY_MEADOW -> Color(0xFF0A7AAD)
    AppTheme.NIGHT -> Color(0xFF5CBAF0)
    AppTheme.SYSTEM -> if (isSystemInDarkTheme()) Color(0xFF5CBAF0) else FolderBlue
    AppTheme.DAY -> FolderBlue
}

@Composable
fun NasFinderTheme(theme: AppTheme, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (theme) {
        AppTheme.DAY, AppTheme.WINDY_MEADOW -> false
        AppTheme.NIGHT, AppTheme.DIGITAL_RAIN -> true
        AppTheme.SYSTEM -> systemDark
    }
    val accent = when (theme) {
        AppTheme.DIGITAL_RAIN -> Color(0xFF2EE8B8)
        AppTheme.WINDY_MEADOW -> Color(0xFF0D8CC2)
        else -> NasBlue
    }
    val scheme = if (dark) darkColorScheme(
        primary = accent,
        background = if (theme == AppTheme.DIGITAL_RAIN) Color(0xFF030B0B) else Color(0xFF09131C),
        surface = if (theme == AppTheme.DIGITAL_RAIN) Color(0xFF091B18) else Color(0xFF131C24),
        surfaceVariant = if (theme == AppTheme.DIGITAL_RAIN) Color(0xFF0C2420) else Color(0xFF182631),
        outlineVariant = if (theme == AppTheme.DIGITAL_RAIN) Color(0xFF1A5245) else Color(0xFF2E4757),
        onBackground = if (theme == AppTheme.DIGITAL_RAIN) Color(0xFFE6FAF2) else Color(0xFFF2F5F7),
        onSurface = if (theme == AppTheme.DIGITAL_RAIN) Color(0xFFE6FAF2) else Color(0xFFF2F5F7),
        onSurfaceVariant = if (theme == AppTheme.DIGITAL_RAIN) Color(0xFF99BFB3) else Color(0xFFB5C1C9),
    ) else lightColorScheme(
        primary = accent,
        background = if (theme == AppTheme.WINDY_MEADOW) Color(0xFFEDF5D1) else Color(0xFFF4FBFE),
        surface = if (theme == AppTheme.WINDY_MEADOW) Color(0xFFFBF9E8) else Color.White,
        surfaceVariant = if (theme == AppTheme.WINDY_MEADOW) Color(0xFFF4F1D9) else Color(0xFFF4FAFD),
        outlineVariant = if (theme == AppTheme.WINDY_MEADOW) Color(0xFF9EBA66) else Color(0xFFBFE3F2),
        onBackground = Color(0xFF101417),
        onSurface = Color(0xFF101417),
        onSurfaceVariant = Color(0xFF59636B),
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
    MaterialTheme(colorScheme = scheme, typography = NasFinderTypography, content = content)
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
    val target = when (theme) {
        AppTheme.NIGHT -> Color.White to .08f
        AppTheme.DIGITAL_RAIN -> Color(0xFFBDFFE6) to .06f
        AppTheme.WINDY_MEADOW -> Color.Black to .04f
        else -> return base
    }
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
