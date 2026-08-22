package com.armsone.nasfinder.data

import android.content.Context
import com.armsone.nasfinder.model.AppTheme
import com.armsone.nasfinder.platform.AppIconChangeResult
import com.armsone.nasfinder.platform.AppIconController
import com.armsone.nasfinder.platform.AppIconSwitchPolicy
import com.armsone.nasfinder.platform.LauncherIconVariant

enum class ScreenAwakeMode { AUTOMATIC, ALWAYS, OFF }

class AppSettingsRepository(context: Context) {
    private val preferences = context.getSharedPreferences("app-settings.v1", Context.MODE_PRIVATE)
    private val appIconController = AppIconController(context)

    fun theme(): AppTheme = preferences.getString(KEY_THEME, null)
        ?.let { stored -> AppTheme.entries.firstOrNull { it.name == stored } }
        ?: AppTheme.SYSTEM

    fun setTheme(theme: AppTheme): AppIconChangeResult {
        val previousIcon = icon()
        val hasExplicitIcon = preferences.contains(KEY_ICON)
        val requestedIcon = if (theme == AppTheme.SKEUOMORPHIC) {
            LauncherIconVariant.ENAMEL
        } else if (hasExplicitIcon) {
            previousIcon
        } else {
            AppIconSwitchPolicy.iconFor(theme)
        }
        // Persist the desired state before touching launcher aliases. Some OEM launchers kill the
        // app despite DONT_KILL_APP; the next process can then reconcile to the intended choice.
        if (!preferences.edit().putString(KEY_THEME, theme.name).commit()) {
            return AppIconChangeResult.PreferenceWriteFailed(
                previousIcon = previousIcon,
                iconRollbackSucceeded = true,
            )
        }
        // Launcher aliases are reconciled before the next Activity is created. Applying them while
        // Settings is visible causes some launchers to terminate the current task.
        return AppIconChangeResult.AlreadyApplied(requestedIcon)
    }

    fun icon(): LauncherIconVariant {
        val theme = theme()
        if (theme == AppTheme.SKEUOMORPHIC) return LauncherIconVariant.ENAMEL
        return AppIconSwitchPolicy.restoredIcon(preferences.getString(KEY_ICON, null), theme)
    }

    fun setIcon(icon: LauncherIconVariant): AppIconChangeResult {
        val previousIcon = icon()
        if (!preferences.edit().putString(KEY_ICON, icon.name).commit()) {
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
        const val KEY_ICON = "launcher_icon"
        const val KEY_SCREEN_AWAKE = "screen_awake_mode"
    }
}
