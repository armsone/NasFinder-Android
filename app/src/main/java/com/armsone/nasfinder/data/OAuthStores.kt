package com.armsone.nasfinder.data

import android.content.Context
import com.armsone.nasfinder.model.CloudOAuthProvider
import com.armsone.nasfinder.model.OAuthPendingSession
import com.armsone.nasfinder.model.OAuthSecurityPolicy
import com.armsone.nasfinder.model.OAuthTokenSet
import com.armsone.nasfinder.model.SingleUseOAuthSessionGate
import org.json.JSONObject
import java.io.IOException

class OAuthClientConfigurationStore(context: Context) {
    private val preferences = context.getSharedPreferences("oauth.clients.v1", Context.MODE_PRIVATE)

    fun clientId(provider: CloudOAuthProvider): String? = runCatching {
        OAuthSecurityPolicy.optionalClientId(preferences.getString(provider.name, null))
    }.getOrNull()

    fun setClientId(provider: CloudOAuthProvider, clientId: String?) {
        val normalized = OAuthSecurityPolicy.optionalClientId(clientId)
        preferences.edit().apply {
            if (normalized == null) remove(provider.name) else putString(provider.name, normalized)
        }.apply()
    }

    fun isAutomaticOAuthEnabled(provider: CloudOAuthProvider): Boolean = clientId(provider) != null
}

class OAuthSessionStore(context: Context) {
    private val vault = CredentialVault(context)

    fun create(
        provider: CloudOAuthProvider,
        connectionId: String,
        clientId: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): Pair<String, OAuthPendingSession> {
        require(connectionId.isNotBlank()) { "연결 ID가 필요합니다." }
        val normalizedClientId = OAuthSecurityPolicy.requireValidClientId(clientId)
        val state = OAuthSecurityPolicy.randomState()
        val digest = OAuthSecurityPolicy.stateDigest(state)
        val session = OAuthPendingSession(
            provider = provider,
            connectionId = connectionId,
            clientId = normalizedClientId,
            stateDigest = digest,
            codeVerifier = OAuthSecurityPolicy.randomCodeVerifier(),
            createdAtMillis = nowMillis,
            expiresAtMillis = nowMillis + OAuthSecurityPolicy.SESSION_TTL_MILLIS,
        )
        vault.save(sessionKey(digest), session.toJson().toString())
        return state to session
    }

    fun consume(state: String, nowMillis: Long = System.currentTimeMillis()): OAuthPendingSession = synchronized(lock) {
        SingleUseOAuthSessionGate { digest ->
            val key = sessionKey(digest)
            val stored = vault.read(key)
            vault.delete(key)
            stored?.let { runCatching { JSONObject(it).toPendingSession() }.getOrNull() }
        }.consume(state, nowMillis)
    }

    private fun OAuthPendingSession.toJson() = JSONObject()
        .put("provider", provider.name)
        .put("connectionId", connectionId)
        .put("clientId", clientId)
        .put("stateDigest", stateDigest)
        .put("codeVerifier", codeVerifier)
        .put("createdAtMillis", createdAtMillis)
        .put("expiresAtMillis", expiresAtMillis)

    private fun JSONObject.toPendingSession() = OAuthPendingSession(
        provider = CloudOAuthProvider.valueOf(getString("provider")),
        connectionId = getString("connectionId"),
        clientId = getString("clientId"),
        stateDigest = getString("stateDigest"),
        codeVerifier = getString("codeVerifier"),
        createdAtMillis = getLong("createdAtMillis"),
        expiresAtMillis = getLong("expiresAtMillis"),
    )

    private fun sessionKey(digest: String) = "oauth.session.$digest"

    private companion object { val lock = Any() }
}

/** 일반 클라우드 전송이 지금 따라야 할 자격 증명의 종류. 토큰이 들어 있으므로 문자열 표현을 만들지 않는다. */
sealed interface CloudTransportCredential {
    /** OAuth 기록이 없거나, 그 뒤 사용자가 수동 토큰을 저장한 연결. */
    class Manual(val token: String?) : CloudTransportCredential

    /** 저장된 OAuth 토큰 세트를 따르는 연결. */
    class Automatic(val tokens: OAuthTokenSet) : CloudTransportCredential

    /** 저장된 자격 증명을 읽거나 해석할 수 없는 연결. 다른 자격 증명으로 넘겨짚지 않는다. */
    object Unreadable : CloudTransportCredential
}

class OAuthTokenStore(context: Context) {
    private val vault = CredentialVault(context)

    /** OAuth 로그인·갱신 결과를 이 연결의 자격 증명으로 고른다. 토큰, 수동 키 사본, 출처 표지는 한 묶음으로 기록된다. */
    fun save(connectionId: String, tokens: OAuthTokenSet) = synchronized(lock) {
        require(connectionId.isNotBlank() && tokens.accessToken.isNotBlank())
        commit(
            mapOf(
                tokenKey(connectionId) to tokens.toJson().toString(),
                // 수동 키를 직접 읽는 호출부와 이전 앱 버전을 위해 같은 값을 둔다. 자격 증명 선택의 기준은 출처 표지다.
                connectionId to tokens.accessToken,
                sourceKey(connectionId) to SOURCE_AUTOMATIC,
            ),
        )
    }

    /**
     * 사용자가 입력한 수동 토큰이나 NAS 비밀번호를 저장한다. OAuth 기록이 있으면 원본은 읽지도 바꾸지도 않고
     * 출처 표지만 수동으로 바꾸므로, 그 기록을 읽을 수 없는 상태에서도 원본이 그대로 남는다.
     */
    fun saveManualCredential(connectionId: String, credential: String) = synchronized(lock) {
        require(connectionId.isNotBlank() && credential.isNotEmpty())
        commit(
            buildMap {
                put(connectionId, credential)
                if (vault.contains(tokenKey(connectionId))) put(sourceKey(connectionId), SOURCE_MANUAL)
            },
        )
    }

    /**
     * 출처 표지가 없으면 이전 버전이 저장한 기록이다. 그때의 토큰/수동 키 불일치는 사용자가 저장한 수동 토큰과
     * 두 번 쓰기 도중 중단된 흔적이 같은 모습이라 구분할 수 없다. 수동 값을 잃지 않도록 수동으로 두며,
     * 중단된 흔적이었다면 브라우저로 다시 로그인해야 자동 갱신으로 돌아온다.
     */
    fun transportCredential(connectionId: String): CloudTransportCredential = synchronized(lock) {
        val source = vault.read(sourceKey(connectionId))
        if (source == null && vault.contains(sourceKey(connectionId))) {
            return@synchronized CloudTransportCredential.Unreadable
        }
        val mirrored = vault.read(connectionId)
        val manual = if (mirrored == null && vault.contains(connectionId)) {
            CloudTransportCredential.Unreadable
        } else {
            CloudTransportCredential.Manual(mirrored)
        }
        if (source == SOURCE_MANUAL) return@synchronized manual
        val stored = vault.read(tokenKey(connectionId))
        if (stored == null && !vault.contains(tokenKey(connectionId))) return@synchronized manual
        val current = stored?.let { runCatching { JSONObject(it).toTokenSet() }.getOrNull() }
            ?: return@synchronized CloudTransportCredential.Unreadable
        when {
            source == SOURCE_AUTOMATIC -> CloudTransportCredential.Automatic(current)
            manual is CloudTransportCredential.Unreadable -> manual
            mirrored != null && mirrored != current.accessToken -> manual
            else -> CloudTransportCredential.Automatic(current)
        }
    }

    fun saveIfUnchanged(connectionId: String, expected: OAuthTokenSet, tokens: OAuthTokenSet): Boolean = synchronized(lock) {
        // 갱신하는 사이 저장된 수동 토큰, 이전 기록의 불일치, 읽기 실패는 모두 Automatic이 아니므로 덮어쓰지 않는다.
        val current = transportCredential(connectionId) as? CloudTransportCredential.Automatic
        if (current?.tokens != expected) return@synchronized false
        save(connectionId, tokens)
        true
    }

    fun read(connectionId: String): OAuthTokenSet? = synchronized(lock) {
        vault.read(tokenKey(connectionId))?.let { runCatching { JSONObject(it).toTokenSet() }.getOrNull() }
    }

    /** 로그아웃·연결 삭제. 이 연결의 자격 증명 선택에 쓰는 키를 함께 지우며, 디스크에 기록하지 못하면 false다. */
    fun delete(connectionId: String): Boolean = synchronized(lock) {
        vault.commitBatch(deletes = setOf(tokenKey(connectionId), sourceKey(connectionId), connectionId))
    }

    private fun commit(values: Map<String, String>) {
        if (!vault.commitBatch(saves = values)) throw IOException("로그인 정보를 저장하지 못했습니다.")
    }

    private fun OAuthTokenSet.toJson() = JSONObject()
        .put("provider", provider.name)
        .put("accessToken", accessToken)
        .put("refreshToken", refreshToken)
        .put("expiresAtMillis", expiresAtMillis)
        .put("scope", scope)

    private fun JSONObject.toTokenSet() = OAuthTokenSet(
        provider = CloudOAuthProvider.valueOf(getString("provider")),
        accessToken = getString("accessToken"),
        refreshToken = optString("refreshToken").takeIf { it.isNotBlank() && it != "null" },
        expiresAtMillis = getLong("expiresAtMillis"),
        scope = optString("scope").takeIf { it.isNotBlank() && it != "null" },
    )

    private fun tokenKey(connectionId: String) = "oauth.tokens.$connectionId"

    private fun sourceKey(connectionId: String) = "oauth.source.$connectionId"

    private companion object {
        val lock = Any()
        const val SOURCE_AUTOMATIC = "automatic"
        const val SOURCE_MANUAL = "manual"
    }
}
