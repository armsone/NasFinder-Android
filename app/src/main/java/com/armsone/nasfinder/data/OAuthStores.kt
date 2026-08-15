package com.armsone.nasfinder.data

import android.content.Context
import com.armsone.nasfinder.model.CloudOAuthProvider
import com.armsone.nasfinder.model.OAuthPendingSession
import com.armsone.nasfinder.model.OAuthSecurityPolicy
import com.armsone.nasfinder.model.OAuthTokenSet
import com.armsone.nasfinder.model.SingleUseOAuthSessionGate
import org.json.JSONObject

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

class OAuthTokenStore(context: Context) {
    private val vault = CredentialVault(context)

    fun save(connectionId: String, tokens: OAuthTokenSet) = synchronized(lock) {
        require(connectionId.isNotBlank() && tokens.accessToken.isNotBlank())
        vault.save(tokenKey(connectionId), tokens.toJson().toString())
        // CloudDriveFileService continues to use the existing manual-token credential path.
        vault.save(connectionId, tokens.accessToken)
    }

    fun read(connectionId: String): OAuthTokenSet? = synchronized(lock) {
        vault.read(tokenKey(connectionId))?.let { runCatching { JSONObject(it).toTokenSet() }.getOrNull() }
    }

    fun delete(connectionId: String) = synchronized(lock) {
        vault.delete(tokenKey(connectionId))
        vault.delete(connectionId)
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

    private companion object { val lock = Any() }
}
