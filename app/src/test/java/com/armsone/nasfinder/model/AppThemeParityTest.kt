package com.armsone.nasfinder.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AppThemeParityTest {
    @Test
    fun `manual six themes keep order and next wraps (header quick4 is not exercised here)`() {
        assertEquals(
            listOf(
                AppTheme.LIGHT,
                AppTheme.BKSTYLE,
                AppTheme.NIGHT,
                AppTheme.JARVIS,
                AppTheme.FRIDAY,
                AppTheme.ULTRON,
            ),
            AppTheme.entries,
        )
        AppTheme.entries.forEachIndexed { index, theme ->
            assertEquals(AppTheme.entries[(index + 1) % AppTheme.entries.size], theme.next)
        }
    }
}
