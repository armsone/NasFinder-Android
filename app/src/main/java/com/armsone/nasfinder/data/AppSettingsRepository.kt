package com.armsone.nasfinder.data

import android.content.Context
import com.armsone.nasfinder.model.AppTheme
import com.armsone.nasfinder.model.InterfaceIconPack
import com.armsone.nasfinder.platform.AppIconChangeResult
import com.armsone.nasfinder.platform.AppIconController
import com.armsone.nasfinder.platform.AppIconSwitchPolicy
import com.armsone.nasfinder.platform.LauncherIconVariant

enum class ScreenAwakeMode { AUTOMATIC, ALWAYS, OFF }

class AppSettingsRepository(context: Context) {
    private val preferences = context.getSharedPreferences("app-settings.v1", Context.MODE_PRIVATE)
    private val appIconController = AppIconController(context)

    init {
        activatePorcelainThemeOnce()
    }

    /**
     * 2.17.0 one-time activation: the first launch selects the realistic interface icon pack and pins the effective
     * launcher icon. The marker is written in the same commit, so any later manual pack choice is never overridden.
     * The theme is not written: the stored raw value (old or new) is only interpreted by [theme].
     */
    private fun activatePorcelainThemeOnce() {
        if (preferences.getBoolean(KEY_PORCELAIN_ACTIVATED, false)) return
        val editor = preferences.edit()
        editor.putString(KEY_ICON, icon().name)
        editor.putString(KEY_ICON_PACK, InterfaceIconPack.REALISTIC.name)
        editor.putBoolean(KEY_PORCELAIN_ACTIVATED, true)
        editor.commit()
    }

    /** Six stable ids are read as-is; any older raw value (or none) reads as Light without rewriting the preference. */
    fun theme(): AppTheme = AppTheme.fromStored(preferences.getString(KEY_THEME, null))

    fun iconPack(): InterfaceIconPack = preferences.getString(KEY_ICON_PACK, null)
        ?.let { stored -> InterfaceIconPack.entries.firstOrNull { it.name == stored } }
        ?: InterfaceIconPack.REALISTIC

    fun setIconPack(pack: InterfaceIconPack) {
        preferences.edit().putString(KEY_ICON_PACK, pack.name).apply()
    }

    /**
     * Colour only: the launcher alias is never derived from or changed by the theme. The current effective icon is
     * pinned in the same commit, so leaving a legacy raw theme (which may have implied an icon) cannot swap the OS icon.
     */
    fun setTheme(theme: AppTheme): AppIconChangeResult {
        val currentIcon = icon()
        if (!preferences.edit().putString(KEY_THEME, theme.id).putString(KEY_ICON, currentIcon.name).commit()) {
            return AppIconChangeResult.PreferenceWriteFailed(
                previousIcon = currentIcon,
                iconRollbackSucceeded = true,
            )
        }
        return AppIconChangeResult.AlreadyApplied(currentIcon)
    }

    /**
     * The effective launcher icon. A legacy BK Style raw theme always forced Enamel and still does until the user picks
     * a theme or icon; otherwise the explicit choice, else the icon an older version derived from its raw theme.
     */
    fun icon(): LauncherIconVariant {
        val rawTheme = preferences.getString(KEY_THEME, null)
        if (rawTheme == LEGACY_ENAMEL_THEME) return LauncherIconVariant.ENAMEL
        return preferences.getString(KEY_ICON, null)
            ?.let { name -> LauncherIconVariant.entries.firstOrNull { it.name == name } }
            ?: AppIconSwitchPolicy.legacyThemeIcon(rawTheme)
    }

    fun setIcon(icon: LauncherIconVariant): AppIconChangeResult {
        val previousIcon = icon()
        val editor = preferences.edit().putString(KEY_ICON, icon.name)
        // A legacy BK Style raw value (already shown as Light) would keep forcing Enamel; store the Light it reads as.
        if (preferences.getString(KEY_THEME, null) == LEGACY_ENAMEL_THEME) editor.putString(KEY_THEME, AppTheme.LIGHT.id)
        if (!editor.commit()) {
            return AppIconChangeResult.PreferenceWriteFailed(
                previousIcon = previousIcon,
                iconRollbackSucceeded = true,
            )
        }
        return AppIconChangeResult.Applied(icon)
    }

    fun restoreAppIcon(): AppIconChangeResult = appIconController.applyIcon(icon())

    fun screenAwakeMode(): ScreenAwakeMode = preferences.getString(KEY_SCREEN_AWAKE, null)
        ?.let { stored -> ScreenAwakeMode.entries.firstOrNull { it.name == stored } }
        ?: ScreenAwakeMode.AUTOMATIC

    fun setScreenAwakeMode(mode: ScreenAwakeMode) {
        preferences.edit().putString(KEY_SCREEN_AWAKE, mode.name).apply()
    }

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_ICON_PACK = "interface_icon_pack"
        const val KEY_PORCELAIN_ACTIVATED = "porcelain_theme_2_17_0_activated"
        const val KEY_ICON = "launcher_icon"
        const val KEY_SCREEN_AWAKE = "screen_awake_mode"
        /** Raw value of the former BK Style theme, which forced the Enamel launcher alias. */
        const val LEGACY_ENAMEL_THEME = "SKEUOMORPHIC"
    }
}
