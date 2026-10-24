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

    /** 비공개를 포함한 전체 즐겨찾기. 저장·삭제·정리는 항상 이 전체 목록을 기준으로 한다. */
    fun allRemoteFavorites(): List<RemoteFavorite> = decode(KEY_REMOTE) { value ->
        RemoteFavoriteJsonCodec.decode(value)
    }

    private fun RemoteFavorite.isHidden(): Boolean =
        !PrivacyMode.isVisible(isPrivate) || PrivacyMode.hides(connectionId, path)

    /** 지금 모드에서 보이는 NAS 즐겨찾기. 비공개 연결·폰하드 경로를 가리키는 즐겨찾기도 일반 모드에서는 가려진다. */
    fun remoteFavorites(): List<RemoteFavorite> = allRemoteFavorites().filterNot { it.isHidden() }

    fun allBrowserFavorites(): List<BrowserFavorite> = decode(KEY_BROWSER) { value ->
        BrowserFavorite(
            id = value.getString("id"), title = value.getString("title"), url = value.getString("url"),
            isHomepage = value.optBoolean("isHomepage"), createdAt = value.getLong("createdAt"),
            isPrivate = value.optBoolean("isPrivate", false),
        )
    }

    fun browserFavorites(): List<BrowserFavorite> =
        allBrowserFavorites().filter { PrivacyMode.isVisible(it.isPrivate) }

    /**
     * 보이는 즐겨찾기면 해제, 아니면 추가한다. 같은 대상의 숨겨진 레코드가 있으면 아무것도 바꾸지 않는다
     * (조용히 공개로 바꾸거나 지우지 않는다). 새 레코드는 프라이버시 모드에서만 비공개로 시작한다.
     */
    fun toggleRemote(favorite: RemoteFavorite): List<RemoteFavorite> {
        val raw = allRemoteFavorites()
        val existing = raw.indexOfFirst { it.connectionId == favorite.connectionId && it.path == favorite.path }
        if (existing >= 0) {
            if (raw[existing].isHidden()) return remoteFavorites()
            persistRemote(raw.toMutableList().also { it.removeAt(existing) })
        } else {
            persistRemote(raw + favorite.copy(isPrivate = PrivacyMode.isActive))
        }
        return remoteFavorites()
    }

    /** 명시적인 공개/비공개 전환. id·순서·시각은 그대로 두고 플래그만 바꾼다. */
    /** 연결을 지울 때 그 연결을 가리키던 즐겨찾기를 숨겨진 것까지 함께 정리한다(사용자가 연결을 직접 삭제한 경우뿐). */
    fun removeFavoritesOf(connectionId: String) {
        val raw = allRemoteFavorites()
        if (raw.any { it.connectionId == connectionId }) persistRemote(raw.filterNot { it.connectionId == connectionId })
    }

    fun setRemotePrivate(favoriteId: String, isPrivate: Boolean): List<RemoteFavorite> {
        val raw = allRemoteFavorites()
        if (raw.any { it.id == favoriteId && it.isPrivate != isPrivate }) {
            persistRemote(raw.map { if (it.id == favoriteId) it.copy(isPrivate = isPrivate) else it })
        }
        return remoteFavorites()
    }

    fun setBrowserPrivate(favoriteId: String, isPrivate: Boolean): List<BrowserFavorite> {
        val raw = allBrowserFavorites()
        if (raw.any { it.id == favoriteId && it.isPrivate != isPrivate }) {
            persistBrowser(raw.map {
                if (it.id == favoriteId) it.copy(isPrivate = isPrivate, isHomepage = it.isHomepage && !isPrivate) else it
            })
        }
        return browserFavorites()
    }

    fun updateRemoteMetadata(favoriteId: String, item: RemoteFileItem): List<RemoteFavorite> {
        val current = allRemoteFavorites().toMutableList()
        val index = current.indexOfFirst { it.id == favoriteId }
        if (index < 0) return remoteFavorites()
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
        if (existing == updated) return remoteFavorites()
        current[index] = updated
        persistRemote(current)
        return remoteFavorites()
    }

    /**
     * 호출자가 준 목록(보통 보이는 즐겨찾기만)을 저장한다. 숨겨진 레코드는 원래 자리에 그대로 이어 붙이고,
     * 이미 있는 레코드의 비공개 플래그는 저장된 값을 이어받으며, 프라이버시 모드에서 새로 생긴 것만 비공개로 시작한다.
     */
    fun saveRemote(values: List<RemoteFavorite>) {
        val raw = allRemoteFavorites()
        val rawById = raw.associateBy(RemoteFavorite::id)
        val visibleNew = values.map { it.copy(isPrivate = rawById[it.id]?.isPrivate ?: (it.isPrivate || PrivacyMode.isActive)) }
        persistRemote(PrivacyMode.preservingHidden(raw, visibleNew, { it.isHidden() }, RemoteFavorite::id))
    }

    fun saveBrowser(values: List<BrowserFavorite>) {
        val raw = allBrowserFavorites()
        val rawById = raw.associateBy(BrowserFavorite::id)
        val visibleNew = values.map { it.copy(isPrivate = rawById[it.id]?.isPrivate ?: (it.isPrivate || PrivacyMode.isActive)) }
        persistBrowser(PrivacyMode.preservingHidden(raw, visibleNew, { !PrivacyMode.isVisible(it.isPrivate) }, BrowserFavorite::id))
    }

    private fun persistRemote(values: List<RemoteFavorite>) = save(KEY_REMOTE, values.map(RemoteFavoriteJsonCodec::encode))

    private fun persistBrowser(values: List<BrowserFavorite>) {
        // 홈페이지는 공개 즐겨찾기 하나뿐이다. 비공개 항목은 일반 모드 첫 화면이 될 수 없다.
        val homepage = values.firstOrNull { it.isHomepage && !it.isPrivate }?.id
        save(KEY_BROWSER, values.distinctBy { it.url to it.isPrivate }.map { value ->
            JSONObject().apply {
                put("id", value.id); put("title", value.title); put("url", value.url)
                put("isHomepage", value.id == homepage); put("createdAt", value.createdAt)
                if (value.isPrivate) put("isPrivate", true)
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
        isPrivate = value.optBoolean("isPrivate", false),
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
        if (value.isPrivate) put("isPrivate", true)
    }
}
