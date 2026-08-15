package com.armsone.nasfinder.data

import android.content.Context
import com.armsone.nasfinder.model.BrowserFavorite
import com.armsone.nasfinder.model.RemoteFavorite
import org.json.JSONArray
import org.json.JSONObject

class FavoriteRepository(context: Context) {
    private val preferences = context.getSharedPreferences("favorites.v1", Context.MODE_PRIVATE)

    fun remoteFavorites(): List<RemoteFavorite> = decode(KEY_REMOTE) { value ->
        RemoteFavorite(
            id = value.getString("id"), connectionId = value.getString("connectionId"),
            path = value.getString("path"), name = value.getString("name"),
            isDirectory = value.getBoolean("isDirectory"), createdAt = value.getLong("createdAt"),
        )
    }

    fun browserFavorites(): List<BrowserFavorite> = decode(KEY_BROWSER) { value ->
        BrowserFavorite(
            id = value.getString("id"), title = value.getString("title"), url = value.getString("url"),
            isHomepage = value.optBoolean("isHomepage"), createdAt = value.getLong("createdAt"),
        )
    }

    fun toggleRemote(favorite: RemoteFavorite): List<RemoteFavorite> {
        val values = remoteFavorites().toMutableList()
        val existing = values.indexOfFirst { it.connectionId == favorite.connectionId && it.path == favorite.path }
        if (existing >= 0) values.removeAt(existing) else values += favorite
        saveRemote(values)
        return values
    }

    fun saveRemote(values: List<RemoteFavorite>) = save(KEY_REMOTE, values.map { value ->
        JSONObject().apply {
            put("id", value.id); put("connectionId", value.connectionId); put("path", value.path)
            put("name", value.name); put("isDirectory", value.isDirectory); put("createdAt", value.createdAt)
        }
    })

    fun saveBrowser(values: List<BrowserFavorite>) {
        val homepage = values.firstOrNull { it.isHomepage }?.id
        save(KEY_BROWSER, values.distinctBy { it.url }.map { value ->
            JSONObject().apply {
                put("id", value.id); put("title", value.title); put("url", value.url)
                put("isHomepage", value.id == homepage); put("createdAt", value.createdAt)
            }
        })
    }

    private fun <T> decode(key: String, transform: (JSONObject) -> T): List<T> = runCatching {
        val array = JSONArray(preferences.getString(key, "[]"))
        buildList { for (index in 0 until array.length()) add(transform(array.getJSONObject(index))) }
    }.getOrElse { emptyList() }

    private fun save(key: String, values: List<JSONObject>) {
        preferences.edit().putString(key, JSONArray(values).toString()).apply()
    }

    private companion object {
        const val KEY_REMOTE = "remote"
        const val KEY_BROWSER = "browser"
    }
}
