package com.armsone.nasfinder.data

import android.content.Context
import com.armsone.nasfinder.model.BrowserPreferences
import com.armsone.nasfinder.model.BrowserPreferencesStorage
import com.armsone.nasfinder.model.ConnectionKind
import com.armsone.nasfinder.model.RemoteConnection
import com.armsone.nasfinder.model.StoredBrowserPreferences
import org.json.JSONArray
import org.json.JSONObject

class ConnectionRepository(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = context.getSharedPreferences("connections.v1", Context.MODE_PRIVATE)
    private val browserPreferences = context.getSharedPreferences("connection.browser.v1", Context.MODE_PRIVATE)
    val credentials = CredentialVault(context)
    val oauthClients = OAuthClientConfigurationStore(context)
    val oauthTokens = OAuthTokenStore(context)

    fun load(): List<RemoteConnection> = runCatching {
        val array = JSONArray(preferences.getString(KEY_CONNECTIONS, "[]"))
        buildList {
            for (index in 0 until array.length()) add(array.getJSONObject(index).toConnection())
        }
    }.getOrElse { emptyList() }

    fun save(connections: List<RemoteConnection>) {
        val retainedIds = connections.mapTo(mutableSetOf(), RemoteConnection::id)
        load().asSequence().map(RemoteConnection::id).filterNot(retainedIds::contains).forEach { removedId ->
            oauthTokens.delete(removedId)
            SuperThumbnailSessionStore(appContext.filesDir.resolve("super-thumbnail/session-v2.json"))
                .clearConnection(removedId)
            browserPreferences.edit()
                .remove("last_path.$removedId")
                .remove("super_thumbnail_root.$removedId")
                .apply {
                    if (browserPreferences.getString(KEY_LAST_CONNECTION, null) == removedId) {
                        remove(KEY_LAST_CONNECTION)
                    }
                }
                .apply()
        }
        val array = JSONArray().apply { connections.forEach { put(it.toJson()) } }
        preferences.edit().putString(KEY_CONNECTIONS, array.toString()).apply()
    }

    fun preferredId(): String? = preferences.getString(KEY_PREFERRED, null)

    fun setPreferred(id: String?) {
        preferences.edit().apply {
            if (id == null) remove(KEY_PREFERRED) else putString(KEY_PREFERRED, id)
        }.apply()
    }

    fun lastPath(connectionId: String): String? = browserPreferences
        .getString("last_path.$connectionId", null)
        ?.takeIf(String::isNotBlank)

    fun lastConnectionId(): String? = browserPreferences
        .getString(KEY_LAST_CONNECTION, null)
        ?.takeIf(String::isNotBlank)

    fun setLastPath(connectionId: String, path: String) {
        if (connectionId.isBlank() || path.isBlank()) return
        browserPreferences.edit()
            .putString("last_path.$connectionId", path)
            .putString(KEY_LAST_CONNECTION, connectionId)
            .apply()
    }

    fun browserPreferences(): BrowserPreferences = BrowserPreferencesStorage.restore(
        StoredBrowserPreferences(
            layout = browserPreferences.getString(KEY_BROWSER_LAYOUT, null),
            sortField = browserPreferences.getString(KEY_BROWSER_SORT_FIELD, null),
            sortDirection = browserPreferences.getString(KEY_BROWSER_SORT_DIRECTION, null),
            namePriority = browserPreferences.getString(KEY_BROWSER_NAME_PRIORITY, null),
            foldersFirst = browserPreferences.getBoolean(KEY_BROWSER_FOLDERS_FIRST, true),
        )
    )

    fun setBrowserPreferences(value: BrowserPreferences) {
        val stored = BrowserPreferencesStorage.store(value)
        browserPreferences.edit()
            .putString(KEY_BROWSER_LAYOUT, stored.layout)
            .putString(KEY_BROWSER_SORT_FIELD, stored.sortField)
            .putString(KEY_BROWSER_SORT_DIRECTION, stored.sortDirection)
            .putString(KEY_BROWSER_NAME_PRIORITY, stored.namePriority)
            .putBoolean(KEY_BROWSER_FOLDERS_FIRST, stored.foldersFirst)
            .apply()
    }

    fun superThumbnailRootPath(connectionId: String): String? = browserPreferences
        .getString("super_thumbnail_root.$connectionId", null)
        ?.takeIf(String::isNotBlank)

    fun setSuperThumbnailRootPath(connectionId: String, path: String) {
        if (connectionId.isBlank() || path.isBlank()) return
        browserPreferences.edit().putString("super_thumbnail_root.$connectionId", path).apply()
    }

    private fun RemoteConnection.toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("kind", kind.name); put("host", host)
        put("port", port); put("username", username); put("rootPath", rootPath)
        put("usesTls", usesTls); put("trustedHostKey", trustedHostKey); put("createdAt", createdAt)
    }

    private fun JSONObject.toConnection() = RemoteConnection(
        id = getString("id"), name = getString("name"), kind = ConnectionKind.valueOf(getString("kind")),
        host = getString("host"), port = getInt("port"), username = getString("username"),
        rootPath = optString("rootPath", "/"), usesTls = optBoolean("usesTls", true),
        trustedHostKey = optString("trustedHostKey").takeIf { it.isNotBlank() && it != "null" },
        createdAt = optLong("createdAt", System.currentTimeMillis()),
    )

    private companion object {
        const val KEY_CONNECTIONS = "connections"
        const val KEY_PREFERRED = "preferred"
        const val KEY_LAST_CONNECTION = "last_connection"
        const val KEY_BROWSER_LAYOUT = "fileBrowserLayoutStyle"
        const val KEY_BROWSER_SORT_FIELD = "fileBrowserSortField"
        const val KEY_BROWSER_SORT_DIRECTION = "fileBrowserSortDirection"
        const val KEY_BROWSER_NAME_PRIORITY = "fileBrowserNamePriority"
        const val KEY_BROWSER_FOLDERS_FIRST = "fileBrowserFoldersFirst"
    }
}
