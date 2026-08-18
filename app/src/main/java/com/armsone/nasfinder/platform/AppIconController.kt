package com.armsone.nasfinder.platform

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.armsone.nasfinder.model.AppTheme

sealed interface AppIconChangeResult {
    val succeeded: Boolean

    data class AlreadyApplied(val icon: LauncherIconVariant) : AppIconChangeResult {
        override val succeeded = true
    }

    data class Applied(val icon: LauncherIconVariant) : AppIconChangeResult {
        override val succeeded = true
    }

    data class RolledBack(
        val requested: LauncherIconVariant,
        val restored: LauncherIconVariant,
    ) : AppIconChangeResult {
        override val succeeded = false
    }

    data class RollbackFailed(
        val requested: LauncherIconVariant,
    ) : AppIconChangeResult {
        override val succeeded = false
    }

    data class Unavailable(
        val requested: LauncherIconVariant,
    ) : AppIconChangeResult {
        override val succeeded = false
    }

    data class PreferenceWriteFailed(
        val previousIcon: LauncherIconVariant,
        val iconRollbackSucceeded: Boolean,
    ) : AppIconChangeResult {
        override val succeeded = false
    }
}

class AppIconController(context: Context) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager
    private val components = mapOf(
        LauncherAlias.DEFAULT to ComponentName(appContext, AppIconComponentContract.DEFAULT_ALIAS_CLASS),
        LauncherAlias.PURPLE_NAS to ComponentName(appContext, AppIconComponentContract.PURPLE_NAS_ALIAS_CLASS),
        LauncherAlias.DIGITAL_RAIN to ComponentName(appContext, AppIconComponentContract.DIGITAL_RAIN_ALIAS_CLASS),
        LauncherAlias.CYBER_VAULT to ComponentName(appContext, AppIconComponentContract.CYBER_VAULT_ALIAS_CLASS),
        LauncherAlias.NAS_RADAR to ComponentName(appContext, AppIconComponentContract.NAS_RADAR_ALIAS_CLASS),
    )

    /** Reconciles first-install/update state without touching MainActivity. */
    fun restore(theme: AppTheme): AppIconChangeResult = applyTheme(theme)

    fun applyTheme(theme: AppTheme): AppIconChangeResult {
        return applyIcon(AppIconSwitchPolicy.iconFor(theme))
    }

    fun applyIcon(requested: LauncherIconVariant): AppIconChangeResult {
        val result = synchronized(APP_ICON_LOCK) { applyIconLocked(requested) }
        val stableIcon = when (result) {
            is AppIconChangeResult.AlreadyApplied -> result.icon
            is AppIconChangeResult.Applied -> result.icon
            is AppIconChangeResult.RolledBack -> result.restored
            else -> null
        }
        stableIcon?.let { icon ->
            // Dynamic shortcuts stay owned by the active launcher alias, while their intents still
            // target the always-enabled MainActivity.
            runCatching { NasFinderShortcuts.installDynamic(appContext, componentFor(icon)) }
        }
        return result
    }

    private fun applyIconLocked(requested: LauncherIconVariant): AppIconChangeResult {
        val before = try {
            readState()
        } catch (_: RuntimeException) {
            return AppIconChangeResult.Unavailable(requested)
        }
        if (AppIconSwitchPolicy.isApplied(before, requested)) {
            return AppIconChangeResult.AlreadyApplied(requested)
        }
        val rollbackIcon = AppIconSwitchPolicy.previousStableIcon(before, LauncherIconVariant.DEFAULT)

        return try {
            applyTransition(before, requested)
            check(AppIconSwitchPolicy.isApplied(readState(), requested))
            AppIconChangeResult.Applied(requested)
        } catch (_: RuntimeException) {
            rollback(requested, rollbackIcon)
        }
    }

    private fun componentFor(icon: LauncherIconVariant): ComponentName =
        ComponentName(appContext, AppIconComponentContract.launcherAliasClass(icon))

    private fun rollback(
        requested: LauncherIconVariant,
        rollbackIcon: LauncherIconVariant,
    ): AppIconChangeResult = try {
        applyTransition(readState(), rollbackIcon)
        if (AppIconSwitchPolicy.isApplied(readState(), rollbackIcon)) {
            AppIconChangeResult.RolledBack(requested, rollbackIcon)
        } else {
            AppIconChangeResult.RollbackFailed(requested)
        }
    } catch (_: RuntimeException) {
        AppIconChangeResult.RollbackFailed(requested)
    }

    private fun applyTransition(state: LauncherAliasState, icon: LauncherIconVariant) {
        val changes = AppIconSwitchPolicy.transition(state, icon)
        if (changes.isEmpty()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API 33+ applies both final states as one PackageManager transaction.
            val target = AppIconSwitchPolicy.launcherAlias(icon)
            packageManager.setComponentEnabledSettings(
                LauncherAlias.entries.map { alias ->
                    PackageManager.ComponentEnabledSetting(
                        requireNotNull(components[alias]),
                        if (alias == target) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP,
                    )
                }
            )
        } else {
            // Policy order is disable-old then enable-new, preventing duplicate launcher entries.
            changes.forEach { mutation ->
                packageManager.setComponentEnabledSetting(
                    requireNotNull(components[mutation.alias]),
                    mutation.override.toPackageManagerState(),
                    PackageManager.DONT_KILL_APP,
                )
            }
        }
    }

    private fun readState(): LauncherAliasState = LauncherAliasState(
        default = packageManager.getComponentEnabledSetting(requireNotNull(components[LauncherAlias.DEFAULT])).toAliasOverride(),
        purpleNas = packageManager.getComponentEnabledSetting(requireNotNull(components[LauncherAlias.PURPLE_NAS])).toAliasOverride(),
        digitalRain = packageManager.getComponentEnabledSetting(requireNotNull(components[LauncherAlias.DIGITAL_RAIN])).toAliasOverride(),
        cyberVault = packageManager.getComponentEnabledSetting(requireNotNull(components[LauncherAlias.CYBER_VAULT])).toAliasOverride(),
        nasRadar = packageManager.getComponentEnabledSetting(requireNotNull(components[LauncherAlias.NAS_RADAR])).toAliasOverride(),
    )

    private fun Int.toAliasOverride(): AliasOverride = when (this) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> AliasOverride.ENABLED
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> AliasOverride.DISABLED
        else -> AliasOverride.MANIFEST_DEFAULT
    }

    private fun AliasOverride.toPackageManagerState(): Int = when (this) {
        AliasOverride.MANIFEST_DEFAULT -> PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        AliasOverride.ENABLED -> PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        AliasOverride.DISABLED -> PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }
}

private val APP_ICON_LOCK = Any()
