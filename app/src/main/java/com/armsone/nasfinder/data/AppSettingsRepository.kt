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

    init {
        restoreAppIcon()
    }

    fun theme(): AppTheme = preferences.getString(KEY_THEME, null)
        ?.let { stored -> AppTheme.entries.firstOrNull { it.name == stored } }
        ?: AppTheme.SYSTEM

    fun setTheme(theme: AppTheme): AppIconChangeResult {
        val previousTheme = this.theme()
        val previousIcon = icon()
        val hasExplicitIcon = preferences.contains(KEY_ICON)
        val requestedIcon = if (hasExplicitIcon) previousIcon else AppIconSwitchPolicy.iconFor(theme)
        // Persist the desired state before touching launcher aliases. Some OEM launchers kill the
        // app despite DONT_KILL_APP; the next process can then reconcile to the intended choice.
        if (!preferences.edit().putString(KEY_THEME, theme.name).commit()) {
            return AppIconChangeResult.PreferenceWriteFailed(
                previousIcon = previousIcon,
                iconRollbackSucceeded = true,
            )
        }
        val iconResult = appIconController.applyIcon(requestedIcon)
        if (iconResult.succeeded) return iconResult

        val preferenceRolledBack = preferences.edit().putString(KEY_THEME, previousTheme.name).commit()
        return if (preferenceRolledBack) iconResult else AppIconChangeResult.PreferenceWriteFailed(
            previousIcon = previousIcon,
            iconRollbackSucceeded = iconResult is AppIconChangeResult.RolledBack,
        )
    }

    fun icon(): LauncherIconVariant = AppIconSwitchPolicy.restoredIcon(
        preferences.getString(KEY_ICON, null),
        theme(),
    )

    fun setIcon(icon: LauncherIconVariant): AppIconChangeResult {
        val previousIcon = icon()
        val hadExplicitIcon = preferences.contains(KEY_ICON)
        if (!preferences.edit().putString(KEY_ICON, icon.name).commit()) {
            return AppIconChangeResult.PreferenceWriteFailed(
                previousIcon = previousIcon,
                iconRollbackSucceeded = true,
            )
        }
        val result = appIconController.applyIcon(icon)
        if (result.succeeded) return result

        val rollbackEditor = preferences.edit()
        if (hadExplicitIcon) rollbackEditor.putString(KEY_ICON, previousIcon.name)
        else rollbackEditor.remove(KEY_ICON)
        val preferenceRolledBack = rollbackEditor.commit()
        return if (preferenceRolledBack) result else AppIconChangeResult.PreferenceWriteFailed(
            previousIcon = previousIcon,
            iconRollbackSucceeded = result is AppIconChangeResult.RolledBack,
        )
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
