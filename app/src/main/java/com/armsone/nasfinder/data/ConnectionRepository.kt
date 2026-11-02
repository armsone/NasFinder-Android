package com.armsone.nasfinder.data

import android.content.Context
import com.armsone.nasfinder.model.BrowserPreferences
import com.armsone.nasfinder.model.BrowserPreferencesStorage
import com.armsone.nasfinder.model.ConnectionKind
import com.armsone.nasfinder.model.RemoteConnection
import com.armsone.nasfinder.model.StoredBrowserPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** 저장된 연결 목록을 읽지 못해 변경을 막았다. 원본 데이터와 연결된 로그인 정보는 그대로 남아 있다. */
class ConnectionStoreReadException :
    IOException("저장된 연결 목록을 읽지 못해 변경 사항을 저장하지 않았습니다. 기존 데이터는 그대로 보존했습니다.")

class ConnectionRepository(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = context.getSharedPreferences("connections.v1", Context.MODE_PRIVATE)
    private val browserPreferences = context.getSharedPreferences("connection.browser.v1", Context.MODE_PRIVATE)
    val credentials = CredentialVault(context)
    val oauthClients = OAuthClientConfigurationStore(context)
    val oauthTokens = OAuthTokenStore(context)

    /** 비공개를 포함한 전체 저장 연결. 정리·id 조회·저장은 항상 이 전체를 기준으로 한다. */
    fun loadAll(): List<RemoteConnection> = runCatching { readStored() }.getOrElse { emptyList() }.also { all ->
        PrivacyMode.setPrivateConnectionIds(all.filter(RemoteConnection::isPrivate).mapTo(hashSetOf(), RemoteConnection::id))
    }

    /** 키가 없는 새 설치는 빈 목록이며 정상이다. 저장된 값이 있는데 해석할 수 없을 때만 true다. */
    fun isStoreUnreadable(): Boolean = runCatching { readStored() }.isFailure

    private fun readStored(): List<RemoteConnection> {
        val array = JSONArray(preferences.getString(KEY_CONNECTIONS, "[]"))
        return buildList {
            for (index in 0 until array.length()) add(array.getJSONObject(index).toConnection())
        }
    }

    /** 쓰기 전용 조회. 읽기 실패를 빈 목록으로 보면 원본을 덮어쓰게 되므로 변경 전체를 막는다. */
    private fun readForWrite(): List<RemoteConnection> =
        try {
            readStored()
        } catch (_: Exception) {
            throw ConnectionStoreReadException()
        }

    /** 지금 모드에서 보이는 연결: 일반 모드는 공개만, 프라이버시 모드는 공개 + 비공개. */
    fun load(): List<RemoteConnection> = loadAll().filter { PrivacyMode.isVisible(it.isPrivate) }

    /**
     * 호출자가 준 목록(보통 보이는 연결만)을 저장한다. 숨겨진 연결은 그대로 이어 붙이고, 수정 화면이 모르는
     * 비공개 플래그는 저장된 값을 이어받으며, 프라이버시 모드에서 새로 만든 연결만 비공개로 시작한다.
     */
    fun save(incoming: List<RemoteConnection>) {
        val raw = readForWrite()
        val rawById = raw.associateBy(RemoteConnection::id)
        val visibleNew = incoming.map { connection ->
            val stored = rawById[connection.id]
            connection.copy(isPrivate = stored?.isPrivate ?: (connection.isPrivate || PrivacyMode.isActive))
        }
        persist(raw, PrivacyMode.preservingHidden(raw, visibleNew, { !PrivacyMode.isVisible(it.isPrivate) }, RemoteConnection::id))
    }

    /** 명시적인 공개/비공개 전환. 플래그만 바꾸고 id·로그인 정보·순서는 그대로 둔다. */
    fun setPrivate(id: String, isPrivate: Boolean) {
        val raw = readForWrite()
        if (raw.none { it.id == id && it.isPrivate != isPrivate }) return
        persist(raw, raw.map { if (it.id == id) it.copy(isPrivate = isPrivate) else it })
    }

    private fun persist(previous: List<RemoteConnection>, connections: List<RemoteConnection>) {
        val retainedIds = connections.mapTo(mutableSetOf(), RemoteConnection::id)
        previous.asSequence().map(RemoteConnection::id).filterNot(retainedIds::contains).forEach { removedId ->
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
        PrivacyMode.setPrivateConnectionIds(connections.filter(RemoteConnection::isPrivate).mapTo(hashSetOf(), RemoteConnection::id))
    }

    fun googlePhotosVisible(): Boolean = preferences.getBoolean("google_photos.visible", true)
    fun googlePhotosName(): String = preferences.getString("google_photos.name", null)?.takeIf(String::isNotBlank) ?: "Google 포토"
    fun saveGooglePhotosLocation(name: String, visible: Boolean) {
        preferences.edit().putString("google_photos.name", name.trim().ifBlank { "Google 포토" })
            .putBoolean("google_photos.visible", visible).apply()
    }

    fun preferredId(): String? = preferences.getString(KEY_PREFERRED, null)?.takeUnless { id ->
        loadAll().any { it.id == id && !PrivacyMode.isVisible(it.isPrivate) }
    }

    fun setPreferred(id: String?) {
        preferences.edit().apply {
            if (id == null) remove(KEY_PREFERRED) else putString(KEY_PREFERRED, id)
        }.apply()
    }

    private fun isHiddenConnection(connectionId: String): Boolean =
        loadAll().any { it.id == connectionId && !PrivacyMode.isVisible(it.isPrivate) }

    fun lastPath(connectionId: String): String? = browserPreferences
        .getString("last_path.$connectionId", null)
        ?.takeIf(String::isNotBlank)
        ?.takeUnless { isHiddenConnection(connectionId) }

    fun lastConnectionId(): String? = browserPreferences
        .getString(KEY_LAST_CONNECTION, null)
        ?.takeIf(String::isNotBlank)
        ?.takeUnless { isHiddenConnection(it) }

    fun setLastPath(connectionId: String, path: String) {
        if (connectionId.isBlank() || path.isBlank()) return
        // 프라이버시 모드이거나 비공개 연결이면 마지막 위치를 새로 기록하지 않는다(기존 값은 그대로 둔다).
        if (PrivacyMode.isActive || loadAll().any { it.id == connectionId && it.isPrivate }) return
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
        if (isPrivate) put("isPrivate", true)
    }

    private fun JSONObject.toConnection() = RemoteConnection(
        id = getString("id"), name = getString("name"), kind = ConnectionKind.valueOf(getString("kind")),
        host = getString("host"), port = getInt("port"), username = getString("username"),
        rootPath = optString("rootPath", "/"), usesTls = optBoolean("usesTls", true),
        trustedHostKey = optString("trustedHostKey").takeIf { it.isNotBlank() && it != "null" },
        createdAt = optLong("createdAt", System.currentTimeMillis()),
        isPrivate = optBoolean("isPrivate", false),
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
