package com.armsone.nasfinder.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AppThemeParityTest {
    @Test
    fun `themes match iPhone display order and wrap`() {
        assertEquals(
            listOf(
                AppTheme.SYSTEM,
                AppTheme.DAY,
                AppTheme.NIGHT,
                AppTheme.DIGITAL_RAIN,
                AppTheme.WINDY_MEADOW,
                AppTheme.WORKBENCH,
                AppTheme.SKEUOMORPHIC,
            ),
            AppTheme.entries,
        )
        AppTheme.entries.forEachIndexed { index, theme ->
            assertEquals(AppTheme.entries[(index + 1) % AppTheme.entries.size], theme.next)
        }
    }
}
