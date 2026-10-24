package com.armsone.nasfinder.googlephotos

import android.content.Context
import com.armsone.nasfinder.data.CredentialVault
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import org.json.JSONObject

data class GooglePhotosOAuthConfiguration(
    val clientId: String,
    val redirectUriOverride: String? = null,
    val authorizationEndpoint: String = "https://accounts.google.com/o/oauth2/v2/auth",
    val tokenEndpoint: String = "https://oauth2.googleapis.com/token",
    val revocationEndpoint: String = "https://oauth2.googleapis.com/revoke",
) {
    val callbackScheme: String get() = clientId.split(".").reversed().joinToString(".")
    val redirectUri: String get() = redirectUriOverride ?: "$callbackScheme:/oauthredirect"
    val scope: String get() = "https://www.googleapis.com/auth/photospicker.mediaitems.readonly"

    fun requireUsable() {
        require(clientId.isNotBlank() && !clientId.startsWith("REPLACE_"))
        listOf(authorizationEndpoint, tokenEndpoint, revocationEndpoint).forEach {
            require(URI(it).scheme == "https")
        }
    }
}

data class GooglePhotosAuthorizationAttempt(val state: String, val codeVerifier: String, val authorizationUrl: String)

object GooglePhotosOAuthPolicy {
    private val random = SecureRandom()
    fun createAttempt(configuration: GooglePhotosOAuthConfiguration): GooglePhotosAuthorizationAttempt {
        configuration.requireUsable()
        val state = randomUrlToken(24)
        val verifier = randomUrlToken(48)
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
        )
        val query = linkedMapOf(
            "client_id" to configuration.clientId, "redirect_uri" to configuration.redirectUri,
            "response_type" to "code", "scope" to configuration.scope,
            "code_challenge" to challenge, "code_challenge_method" to "S256",
            "state" to state, "access_type" to "offline", "prompt" to "consent",
        ).entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        return GooglePhotosAuthorizationAttempt(state, verifier, "${configuration.authorizationEndpoint}?$query")
    }

    fun authorizationCode(callbackUri: String, expectedState: String, configuration: GooglePhotosOAuthConfiguration): String {
        val uri = runCatching { URI(callbackUri) }.getOrElse { throw GooglePhotosOAuthException.InvalidCallback }
        val expected = URI(configuration.redirectUri)
        if (uri.scheme != expected.scheme || uri.host != expected.host || uri.path != expected.path) throw GooglePhotosOAuthException.InvalidCallback
        val values = linkedMapOf<String, String>()
        (uri.rawQuery ?: "").split('&').filter(String::isNotEmpty).forEach {
            val parts = it.split('=', limit = 2)
            if (parts.size != 2) throw GooglePhotosOAuthException.InvalidCallback
            val key = java.net.URLDecoder.decode(parts[0], "UTF-8")
            val value = java.net.URLDecoder.decode(parts[1], "UTF-8")
            if (key !in setOf("state", "code", "error", "scope", "authuser", "prompt") || values.putIfAbsent(key, value) != null) {
                throw GooglePhotosOAuthException.InvalidCallback
            }
        }
        if (!MessageDigest.isEqual(values["state"].orEmpty().toByteArray(), expectedState.toByteArray())) throw GooglePhotosOAuthException.StateMismatch
        if (!values["error"].isNullOrBlank()) throw GooglePhotosOAuthException.AuthorizationDenied
        return values["code"]?.takeIf(String::isNotBlank) ?: throw GooglePhotosOAuthException.AuthorizationDenied
    }

    private fun randomUrlToken(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    internal fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

data class GooglePhotosCredential(
    val accessToken: String, val refreshToken: String?, val expiresAtEpochSeconds: Long?, val grantedScopes: Set<String>,
) {
    fun isValid(now: Instant = Instant.now(), leewaySeconds: Long = 60): Boolean =
        accessToken.isNotBlank() && (expiresAtEpochSeconds == null || expiresAtEpochSeconds > now.epochSecond + leewaySeconds)
    override fun toString(): String = "GooglePhotosCredential(accessToken=<redacted>, refreshToken=${if (refreshToken == null) "null" else "<redacted>"}, expiresAtEpochSeconds=$expiresAtEpochSeconds, grantedScopes=$grantedScopes)"
}

sealed class GooglePhotosOAuthException(message: String) : Exception(message) {
    data object InvalidCallback : GooglePhotosOAuthException("Google 포토 인증 응답이 올바르지 않습니다.")
    data object StateMismatch : GooglePhotosOAuthException("Google 포토 인증 상태가 일치하지 않습니다.")
    data object AuthorizationDenied : GooglePhotosOAuthException("Google 포토 인증이 취소되었습니다.")
    data object ReauthorizationRequired : GooglePhotosOAuthException("Google 포토에 다시 로그인해 주세요.")
    data class RequestFailed(val statusCode: Int?) : GooglePhotosOAuthException("Google 포토 인증 요청이 실패했습니다.")
}

class GooglePhotosCredentialStore(context: Context) {
    private val vault = CredentialVault(context)
    fun save(value: GooglePhotosCredential) = vault.save(KEY, JSONObject().put("access", value.accessToken).put("refresh", value.refreshToken)
        .put("expires", value.expiresAtEpochSeconds).put("scopes", value.grantedScopes.joinToString(" ")).toString())
    fun read(): GooglePhotosCredential? = runCatching {
        val json = JSONObject(vault.read(KEY) ?: return null)
        GooglePhotosCredential(json.getString("access"), json.optString("refresh").takeIf { it.isNotBlank() && it != "null" },
            if (json.isNull("expires")) null else json.getLong("expires"), json.optString("scopes").split(' ').filter(String::isNotBlank).toSet())
    }.getOrNull()
    fun delete() = vault.delete(KEY)
    private companion object { const val KEY = "google.photos.oauth.credential.v1" }
}

class GooglePhotosAuthorizationSessionStore(context: Context) {
    private val vault = CredentialVault(context)
    fun save(attempt: GooglePhotosAuthorizationAttempt) = vault.save(key(attempt.state), JSONObject()
        .put("state", attempt.state).put("verifier", attempt.codeVerifier).toString())
    fun consume(state: String): GooglePhotosAuthorizationAttempt? = synchronized(lock) {
        val key = key(state); val raw = vault.read(key) ?: return@synchronized null; vault.delete(key)
        runCatching { JSONObject(raw) }.getOrNull()?.let {
            GooglePhotosAuthorizationAttempt(it.getString("state"), it.getString("verifier"), "")
        }
    }
    private fun key(state: String): String = "google.photos.oauth.session." + Base64.getUrlEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(state.toByteArray())
    )
    private companion object { val lock = Any() }
}
