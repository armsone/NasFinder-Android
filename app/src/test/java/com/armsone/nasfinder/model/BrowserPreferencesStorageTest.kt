package com.armsone.nasfinder.model

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserPreferencesStorageTest {
    @Test
    fun restoresEveryPersistedBrowserPreference() {
        val stored = StoredBrowserPreferences(
            layout = BrowserLayout.LARGE_GRID.name,
            sortField = SortField.MODIFIED.name,
            sortDirection = SortDirection.DESCENDING.name,
            namePriority = NamePriority.KOREAN_FIRST.name,
            foldersFirst = false,
        )

        assertEquals(
            BrowserPreferences(
                layout = BrowserLayout.LARGE_GRID,
                sortField = SortField.MODIFIED,
                sortDirection = SortDirection.DESCENDING,
                namePriority = NamePriority.KOREAN_FIRST,
                foldersFirst = false,
            ),
            BrowserPreferencesStorage.restore(stored),
        )
    }

    @Test
    fun missingOrInvalidValuesUseIosAppStorageDefaults() {
        val stored = StoredBrowserPreferences(
            layout = null,
            sortField = "unknown",
            sortDirection = null,
            namePriority = "",
            foldersFirst = true,
        )

        assertEquals(
            BrowserPreferences(layout = BrowserLayout.SMALL_GRID),
            BrowserPreferencesStorage.restore(stored),
        )
    }

    @Test
    fun storedValuesRoundTripWithoutPersistingUnrelatedPreferences() {
        val preferences = BrowserPreferences(
            layout = BrowserLayout.LIST,
            sortField = SortField.SIZE,
            sortDirection = SortDirection.DESCENDING,
            namePriority = NamePriority.LATIN_FIRST,
            foldersFirst = false,
            showHiddenFiles = true,
        )

        assertEquals(
            preferences.copy(showHiddenFiles = false),
            BrowserPreferencesStorage.restore(BrowserPreferencesStorage.store(preferences)),
        )
    }
}
