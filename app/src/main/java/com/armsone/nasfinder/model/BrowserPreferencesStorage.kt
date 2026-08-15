package com.armsone.nasfinder.model

data class StoredBrowserPreferences(
    val layout: String?,
    val sortField: String?,
    val sortDirection: String?,
    val namePriority: String?,
    val foldersFirst: Boolean,
)

object BrowserPreferencesStorage {
    private val defaults = BrowserPreferences(layout = BrowserLayout.SMALL_GRID)

    fun restore(stored: StoredBrowserPreferences): BrowserPreferences = BrowserPreferences(
        layout = stored.layout.enumValueOr(defaults.layout),
        sortField = stored.sortField.enumValueOr(defaults.sortField),
        sortDirection = stored.sortDirection.enumValueOr(defaults.sortDirection),
        namePriority = stored.namePriority.enumValueOr(defaults.namePriority),
        foldersFirst = stored.foldersFirst,
    )

    fun store(preferences: BrowserPreferences): StoredBrowserPreferences = StoredBrowserPreferences(
        layout = preferences.layout.name,
        sortField = preferences.sortField.name,
        sortDirection = preferences.sortDirection.name,
        namePriority = preferences.namePriority.name,
        foldersFirst = preferences.foldersFirst,
    )
}

private inline fun <reified T : Enum<T>> String?.enumValueOr(default: T): T =
    runCatching { enumValueOf<T>(orEmpty()) }.getOrDefault(default)
