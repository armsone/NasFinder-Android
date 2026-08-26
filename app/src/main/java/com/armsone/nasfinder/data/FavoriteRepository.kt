package com.armsone.nasfinder.data

import android.content.Context
import com.armsone.nasfinder.model.BrowserFavorite
import com.armsone.nasfinder.model.RemoteFavorite
import com.armsone.nasfinder.model.RemoteFileItem
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

class FavoriteRepository(context: Context) {
    private val preferences = context.getSharedPreferences("favorites.v1", Context.MODE_PRIVATE)

    fun remoteFavorites(): List<RemoteFavorite> = decode(KEY_REMOTE) { value ->
        RemoteFavoriteJsonCodec.decode(value)
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

    fun updateRemoteMetadata(favoriteId: String, item: RemoteFileItem): List<RemoteFavorite> {
        val current = remoteFavorites().toMutableList()
        val index = current.indexOfFirst { it.id == favoriteId }
        if (index < 0) return current
        val existing = current[index]
        val updated = existing.copy(
            path = item.path,
            name = item.name,
            isDirectory = item.isDirectory,
            remoteItemId = item.id,
            size = item.size,
            modifiedAt = item.modifiedAt,
            mimeType = item.mimeType,
            thumbnailUrl = item.thumbnailUrl,
        )
        if (existing == updated) return current
        current[index] = updated
        saveRemote(current)
        return current
    }

    fun saveRemote(values: List<RemoteFavorite>) = save(KEY_REMOTE, values.map(RemoteFavoriteJsonCodec::encode))

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

internal object RemoteFavoriteJsonCodec {
    fun decode(value: JSONObject) = RemoteFavorite(
        id = value.getString("id"),
        connectionId = value.getString("connectionId"),
        path = value.getString("path"),
        name = value.getString("name"),
        isDirectory = value.getBoolean("isDirectory"),
        createdAt = value.getLong("createdAt"),
        remoteItemId = value.optString("remoteItemId").takeIf(String::isNotBlank),
        size = value.optLong("size").takeIf { value.has("size") },
        modifiedAt = value.optString("modifiedAt").takeIf(String::isNotBlank)?.let {
            runCatching { Instant.parse(it) }.getOrNull()
        },
        mimeType = value.optString("mimeType").takeIf(String::isNotBlank),
        thumbnailUrl = value.optString("thumbnailUrl").takeIf(String::isNotBlank),
    )

    fun encode(value: RemoteFavorite) = JSONObject().apply {
        put("id", value.id)
        put("connectionId", value.connectionId)
        put("path", value.path)
        put("name", value.name)
        put("isDirectory", value.isDirectory)
        put("createdAt", value.createdAt)
        value.remoteItemId?.let { put("remoteItemId", it) }
        value.size?.let { put("size", it) }
        value.modifiedAt?.let { put("modifiedAt", it.toString()) }
        value.mimeType?.let { put("mimeType", it) }
        value.thumbnailUrl?.let { put("thumbnailUrl", it) }
    }
}
