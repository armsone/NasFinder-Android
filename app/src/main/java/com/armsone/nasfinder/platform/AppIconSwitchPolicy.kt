package com.armsone.nasfinder.platform

import com.armsone.nasfinder.model.AppTheme

// User-visible order: Blue, Purple, Vibe Coder, Cyber Vault, NAS Radar.
enum class LauncherIconVariant { DEFAULT, PURPLE_NAS, VIBE_CODER, CYBER_VAULT, NAS_RADAR }

internal object AppIconComponentContract {
    const val MAIN_ACTIVITY_CLASS = "com.armsone.nasfinder.MainActivity"
    const val DEFAULT_ALIAS_CLASS = "com.armsone.nasfinder.DefaultLauncherAlias"
    const val PURPLE_NAS_ALIAS_CLASS = "com.armsone.nasfinder.PurpleNasLauncherAlias"
    const val DIGITAL_RAIN_ALIAS_CLASS = "com.armsone.nasfinder.DigitalRainLauncherAlias"
    const val CYBER_VAULT_ALIAS_CLASS = "com.armsone.nasfinder.CyberVaultLauncherAlias"
    const val NAS_RADAR_ALIAS_CLASS = "com.armsone.nasfinder.NasRadarLauncherAlias"

    val managedAliasClassNames = setOf(
        DEFAULT_ALIAS_CLASS,
        CYBER_VAULT_ALIAS_CLASS,
        DIGITAL_RAIN_ALIAS_CLASS,
        PURPLE_NAS_ALIAS_CLASS,
        NAS_RADAR_ALIAS_CLASS,
    )

    fun launcherAliasClass(icon: LauncherIconVariant): String = when (icon) {
        LauncherIconVariant.DEFAULT -> DEFAULT_ALIAS_CLASS
        LauncherIconVariant.CYBER_VAULT -> CYBER_VAULT_ALIAS_CLASS
        LauncherIconVariant.VIBE_CODER -> DIGITAL_RAIN_ALIAS_CLASS
        LauncherIconVariant.PURPLE_NAS -> PURPLE_NAS_ALIAS_CLASS
        LauncherIconVariant.NAS_RADAR -> NAS_RADAR_ALIAS_CLASS
    }
}

internal enum class LauncherAlias { DEFAULT, CYBER_VAULT, DIGITAL_RAIN, PURPLE_NAS, NAS_RADAR }

internal enum class AliasOverride { MANIFEST_DEFAULT, ENABLED, DISABLED }

internal data class LauncherAliasState(
    val default: AliasOverride,
    val cyberVault: AliasOverride,
    val digitalRain: AliasOverride,
    val purpleNas: AliasOverride,
    val nasRadar: AliasOverride,
) {
    fun overrideFor(alias: LauncherAlias): AliasOverride = when (alias) {
        LauncherAlias.DEFAULT -> default
        LauncherAlias.CYBER_VAULT -> cyberVault
        LauncherAlias.DIGITAL_RAIN -> digitalRain
        LauncherAlias.PURPLE_NAS -> purpleNas
        LauncherAlias.NAS_RADAR -> nasRadar
    }
}

internal data class AliasMutation(
    val alias: LauncherAlias,
    val override: AliasOverride,
)

/** Pure launcher policy kept separate from PackageManager for deterministic regression tests. */
internal object AppIconSwitchPolicy {
    fun iconFor(theme: AppTheme): LauncherIconVariant = when (theme) {
        AppTheme.DIGITAL_RAIN -> LauncherIconVariant.VIBE_CODER
        AppTheme.SYSTEM,
        AppTheme.DAY,
        AppTheme.NIGHT,
        AppTheme.WINDY_MEADOW,
        AppTheme.WORKBENCH,
        -> LauncherIconVariant.DEFAULT
    }

    fun restoredIcon(storedName: String?, legacyTheme: AppTheme): LauncherIconVariant =
        storedName?.let { name -> LauncherIconVariant.entries.firstOrNull { it.name == name } }
            ?: iconFor(legacyTheme)

    fun isApplied(state: LauncherAliasState, icon: LauncherIconVariant): Boolean {
        val target = icon.alias
        return isEnabled(state, target) &&
            LauncherAlias.entries.filterNot { it == target }.none { isEnabled(state, it) }
    }

    fun previousStableIcon(
        state: LauncherAliasState,
        fallback: LauncherIconVariant,
    ): LauncherIconVariant {
        val enabled = LauncherIconVariant.entries.filter { icon -> isEnabled(state, icon.alias) }
        return enabled.singleOrNull() ?: fallback
    }

    /**
     * The non-target alias is disabled first on legacy Android to prevent a transient duplicate
     * launcher. MainActivity is deliberately not represented in this policy and remains enabled.
     */
    fun transition(state: LauncherAliasState, icon: LauncherIconVariant): List<AliasMutation> {
        if (isApplied(state, icon)) return emptyList()
        val target = icon.alias
        return buildList {
            LauncherAlias.entries.filterNot { it == target }.forEach { other ->
                if (isEnabled(state, other)) {
                    add(AliasMutation(other, AliasOverride.DISABLED))
                }
            }
            if (state.overrideFor(target) != AliasOverride.ENABLED) {
                add(AliasMutation(target, AliasOverride.ENABLED))
            }
        }
    }

    private fun isEnabled(state: LauncherAliasState, alias: LauncherAlias): Boolean =
        when (state.overrideFor(alias)) {
            AliasOverride.ENABLED -> true
            AliasOverride.DISABLED -> false
            AliasOverride.MANIFEST_DEFAULT -> alias == LauncherAlias.DEFAULT
        }

    private val LauncherIconVariant.alias: LauncherAlias
        get() = when (this) {
            LauncherIconVariant.DEFAULT -> LauncherAlias.DEFAULT
            LauncherIconVariant.CYBER_VAULT -> LauncherAlias.CYBER_VAULT
            LauncherIconVariant.VIBE_CODER -> LauncherAlias.DIGITAL_RAIN
            LauncherIconVariant.PURPLE_NAS -> LauncherAlias.PURPLE_NAS
            LauncherIconVariant.NAS_RADAR -> LauncherAlias.NAS_RADAR
        }

    fun launcherAlias(icon: LauncherIconVariant): LauncherAlias = icon.alias
}
