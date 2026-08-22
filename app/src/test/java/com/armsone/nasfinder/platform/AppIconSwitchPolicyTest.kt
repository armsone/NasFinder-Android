package com.armsone.nasfinder.platform

import com.armsone.nasfinder.model.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppIconSwitchPolicyTest {
    @Test
    fun `themed launcher icons follow Digital Rain and BK Style`() {
        AppTheme.entries.forEach { theme ->
            val expected = when (theme) {
                AppTheme.DIGITAL_RAIN -> LauncherIconVariant.VIBE_CODER
                AppTheme.SKEUOMORPHIC -> LauncherIconVariant.ENAMEL
                else -> LauncherIconVariant.DEFAULT
            }
            assertEquals(expected, AppIconSwitchPolicy.iconFor(theme))
        }
        LauncherIconVariant.entries.forEach { icon ->
            assertEquals(icon, AppIconSwitchPolicy.restoredIcon(icon.name, AppTheme.SYSTEM))
        }
        assertEquals(
            LauncherIconVariant.VIBE_CODER,
            AppIconSwitchPolicy.restoredIcon(null, AppTheme.DIGITAL_RAIN),
        )
        assertEquals(
            LauncherIconVariant.DEFAULT,
            AppIconSwitchPolicy.restoredIcon("REMOVED_OLD_ICON", AppTheme.SYSTEM),
        )
    }

    @Test
    fun `first install manifest defaults need no component mutation`() {
        val freshInstall = state(AliasOverride.MANIFEST_DEFAULT, AliasOverride.MANIFEST_DEFAULT)

        assertTrue(AppIconSwitchPolicy.isApplied(freshInstall, LauncherIconVariant.DEFAULT))
        assertTrue(AppIconSwitchPolicy.transition(freshInstall, LauncherIconVariant.DEFAULT).isEmpty())
    }

    @Test
    fun `theme restore disables old launcher before enabling new launcher`() {
        val freshInstall = state(AliasOverride.MANIFEST_DEFAULT, AliasOverride.MANIFEST_DEFAULT)

        assertEquals(
            listOf(
                AliasMutation(LauncherAlias.DEFAULT, AliasOverride.DISABLED),
                AliasMutation(LauncherAlias.DIGITAL_RAIN, AliasOverride.ENABLED),
            ),
            AppIconSwitchPolicy.transition(freshInstall, LauncherIconVariant.VIBE_CODER),
        )
    }

    @Test
    fun `update repairs duplicate launcher without disabling MainActivity`() {
        val duplicated = state(AliasOverride.ENABLED, AliasOverride.ENABLED)
        val mutations = AppIconSwitchPolicy.transition(duplicated, LauncherIconVariant.VIBE_CODER)

        assertEquals(
            listOf(AliasMutation(LauncherAlias.DEFAULT, AliasOverride.DISABLED)),
            mutations,
        )
        assertFalse(
            AppIconComponentContract.MAIN_ACTIVITY_CLASS in
                AppIconComponentContract.managedAliasClassNames
        )
        assertEquals(
            setOf(
                "com.armsone.nasfinder.DefaultLauncherAlias",
                "com.armsone.nasfinder.PurpleNasLauncherAlias",
                "com.armsone.nasfinder.DigitalRainLauncherAlias",
                "com.armsone.nasfinder.CyberVaultLauncherAlias",
            "com.armsone.nasfinder.NasRadarLauncherAlias",
            "com.armsone.nasfinder.EnamelLauncherAlias",
            ),
            AppIconComponentContract.managedAliasClassNames,
        )
        assertEquals(
            "com.armsone.nasfinder.DigitalRainLauncherAlias",
            AppIconComponentContract.launcherAliasClass(LauncherIconVariant.VIBE_CODER),
        )
        assertEquals(
            "com.armsone.nasfinder.MainActivity",
            AppIconComponentContract.MAIN_ACTIVITY_CLASS,
        )
    }

    @Test
    fun `update repairs missing launcher to persisted theme`() {
        val missing = state(AliasOverride.DISABLED, AliasOverride.DISABLED)

        assertEquals(
            listOf(AliasMutation(LauncherAlias.DIGITAL_RAIN, AliasOverride.ENABLED)),
            AppIconSwitchPolicy.transition(missing, LauncherIconVariant.VIBE_CODER),
        )
    }

    @Test
    fun `all icon choices have distinct launcher aliases`() {
        assertEquals(
            listOf(
                LauncherIconVariant.DEFAULT,
                LauncherIconVariant.PURPLE_NAS,
                LauncherIconVariant.VIBE_CODER,
                LauncherIconVariant.CYBER_VAULT,
                LauncherIconVariant.NAS_RADAR,
                LauncherIconVariant.ENAMEL,
            ),
            LauncherIconVariant.entries,
        )
        LauncherIconVariant.entries.forEach { icon ->
            val applied = LauncherAliasState(
                default = overrideFor(LauncherIconVariant.DEFAULT, icon),
                cyberVault = overrideFor(LauncherIconVariant.CYBER_VAULT, icon),
                digitalRain = overrideFor(LauncherIconVariant.VIBE_CODER, icon),
                purpleNas = overrideFor(LauncherIconVariant.PURPLE_NAS, icon),
                nasRadar = overrideFor(LauncherIconVariant.NAS_RADAR, icon),
                enamel = overrideFor(LauncherIconVariant.ENAMEL, icon),
            )
            assertTrue(AppIconSwitchPolicy.isApplied(applied, icon))
            assertEquals(icon, AppIconSwitchPolicy.previousStableIcon(applied, LauncherIconVariant.DEFAULT))
            assertTrue(AppIconSwitchPolicy.transition(applied, icon).isEmpty())
        }
        assertEquals(
            setOf(
                "com.armsone.nasfinder.DefaultLauncherAlias",
                "com.armsone.nasfinder.PurpleNasLauncherAlias",
                "com.armsone.nasfinder.DigitalRainLauncherAlias",
                "com.armsone.nasfinder.CyberVaultLauncherAlias",
                "com.armsone.nasfinder.NasRadarLauncherAlias",
                "com.armsone.nasfinder.EnamelLauncherAlias",
            ),
            LauncherIconVariant.entries.mapTo(mutableSetOf()) {
                AppIconComponentContract.launcherAliasClass(it)
            },
        )
        assertEquals(
            listOf(
                AliasMutation(LauncherAlias.DEFAULT, AliasOverride.DISABLED),
                AliasMutation(LauncherAlias.PURPLE_NAS, AliasOverride.ENABLED),
            ),
            AppIconSwitchPolicy.transition(
                state(AliasOverride.MANIFEST_DEFAULT, AliasOverride.MANIFEST_DEFAULT),
                LauncherIconVariant.PURPLE_NAS,
            ),
        )
    }

    @Test
    fun `failed switch rollback target is previous stable launcher`() {
        val before = state(AliasOverride.DISABLED, AliasOverride.ENABLED)
        val partiallyFailed = state(AliasOverride.DISABLED, AliasOverride.DISABLED)
        val rollback = AppIconSwitchPolicy.previousStableIcon(before, LauncherIconVariant.DEFAULT)

        assertEquals(LauncherIconVariant.VIBE_CODER, rollback)
        assertEquals(
            listOf(AliasMutation(LauncherAlias.DIGITAL_RAIN, AliasOverride.ENABLED)),
            AppIconSwitchPolicy.transition(partiallyFailed, rollback),
        )
    }

    @Test
    fun `inconsistent prior state rolls back to safe default without duplicate launcher`() {
        val duplicated = state(AliasOverride.ENABLED, AliasOverride.ENABLED)

        assertEquals(
            LauncherIconVariant.DEFAULT,
            AppIconSwitchPolicy.previousStableIcon(duplicated, LauncherIconVariant.DEFAULT),
        )
        assertEquals(
            listOf(AliasMutation(LauncherAlias.DIGITAL_RAIN, AliasOverride.DISABLED)),
            AppIconSwitchPolicy.transition(duplicated, LauncherIconVariant.DEFAULT),
        )
    }

    private fun state(
        default: AliasOverride,
        digitalRain: AliasOverride,
        purpleNas: AliasOverride = AliasOverride.MANIFEST_DEFAULT,
        cyberVault: AliasOverride = AliasOverride.MANIFEST_DEFAULT,
        nasRadar: AliasOverride = AliasOverride.MANIFEST_DEFAULT,
        enamel: AliasOverride = AliasOverride.MANIFEST_DEFAULT,
    ) = LauncherAliasState(default, cyberVault, digitalRain, purpleNas, nasRadar, enamel)

    private fun overrideFor(candidate: LauncherIconVariant, selected: LauncherIconVariant) =
        if (candidate == selected) AliasOverride.ENABLED else AliasOverride.DISABLED
}
