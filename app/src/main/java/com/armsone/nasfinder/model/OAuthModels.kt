package com.armsone.nasfinder.model

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

enum class CloudOAuthProvider(
    val connectionKind: ConnectionKind,
    val authorizeEndpoint: String,
    val tokenEndpoint: String,
    val scopes: List<String>,
) {
    DROPBOX(
        ConnectionKind.DROPBOX,
        "https://www.dropbox.com/oauth2/authorize",
        "https://api.dropboxapi.com/oauth2/token",
        listOf("files.metadata.read", "files.metadata.write", "files.content.read", "files.content.write"),
    ),
    ONEDRIVE(
        ConnectionKind.ONEDRIVE,
        "https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
        "https://login.microsoftonline.com/common/oauth2/v2.0/token",
        listOf("offline_access", "Files.ReadWrite"),
    ),
    GOOGLE_DRIVE(
        ConnectionKind.GOOGLE_DRIVE,
        "https://accounts.google.com/o/oauth2/v2/auth",
        "https://oauth2.googleapis.com/token",
        listOf("https://www.googleapis.com/auth/drive"),
    );

    val authorizeHost: String get() = URI(authorizeEndpoint).host
    val tokenHost: String get() = URI(tokenEndpoint).host

    companion object {
        fun from(kind: ConnectionKind): CloudOAuthProvider? = entries.firstOrNull { it.connectionKind == kind }
    }
}

data class OAuthPendingSession(
    val provider: CloudOAuthProvider,
    val connectionId: String,
    val clientId: String,
    val stateDigest: String,
    val codeVerifier: String,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
)

data class OAuthTokenSet(
    val provider: CloudOAuthProvider,
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtMillis: Long,
    val scope: String? = null,
) {
    fun usableAccessToken(nowMillis: Long, refreshSkewMillis: Long = 60_000L): String? =
        accessToken.takeIf { it.isNotBlank() && nowMillis < expiresAtMillis - refreshSkewMillis }
}

data class OAuthCallback(val state: String, val code: String? = null, val error: String? = null)

object OAuthSecurityPolicy {
    const val REDIRECT_URI = "nasfinder-oauth://callback/v1"
    const val SESSION_TTL_MILLIS = 10L * 60L * 1_000L
    private val safeBase64Url = Regex("^[A-Za-z0-9_-]+$")

    fun randomState(random: SecureRandom = SecureRandom()): String = randomUrlSafe(32, random)
    fun randomCodeVerifier(random: SecureRandom = SecureRandom()): String = randomUrlSafe(64, random)

    fun codeChallenge(verifier: String): String {
        require(verifier.length in 43..128 && safeBase64Url.matches(verifier)) { "PKCE verifier가 올바르지 않습니다." }
        return base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)))
    }

    fun stateDigest(state: String): String {
        require(state.length in 32..128 && safeBase64Url.matches(state)) { "OAuth state가 올바르지 않습니다." }
        return base64Url(MessageDigest.getInstance("SHA-256").digest(state.toByteArray(StandardCharsets.US_ASCII)))
    }

    fun constantTimeEquals(left: String, right: String): Boolean = MessageDigest.isEqual(
        left.toByteArray(StandardCharsets.US_ASCII),
        right.toByteArray(StandardCharsets.US_ASCII),
    )

    fun authorizationUrl(provider: CloudOAuthProvider, clientId: String, state: String, verifier: String): String {
        requireValidClientId(clientId)
        stateDigest(state)
        val parameters = linkedMapOf(
            "client_id" to clientId,
            "redirect_uri" to REDIRECT_URI,
            "response_type" to "code",
            "state" to state,
            "code_challenge" to codeChallenge(verifier),
            "code_challenge_method" to "S256",
        )
        if (provider.scopes.isNotEmpty()) parameters["scope"] = provider.scopes.joinToString(" ")
        when (provider) {
            CloudOAuthProvider.DROPBOX -> parameters["token_access_type"] = "offline"
            CloudOAuthProvider.ONEDRIVE -> parameters["response_mode"] = "query"
            CloudOAuthProvider.GOOGLE_DRIVE -> {
                parameters["access_type"] = "offline"
                parameters["prompt"] = "consent"
            }
        }
        return provider.authorizeEndpoint + "?" + parameters.entries.joinToString("&") {
            "${encode(it.key)}=${encode(it.value)}"
        }
    }

    fun parseCallback(rawUri: String): OAuthCallback {
        require(rawUri.isNotBlank() && rawUri.length <= 32_768 && rawUri.none { it == '\u0000' || it == '\r' || it == '\n' }) {
            "OAuth callback이 올바르지 않습니다."
        }
        val uri = runCatching { URI(rawUri) }.getOrElse { throw IllegalArgumentException("OAuth callback이 올바르지 않습니다.") }
        require(uri.scheme == "nasfinder-oauth" && uri.host == "callback" && uri.path == "/v1") {
            "허용되지 않은 OAuth callback입니다."
        }
        require(uri.userInfo == null && uri.port == -1 && uri.rawFragment == null) { "허용되지 않은 OAuth callback입니다." }
        val allowed = setOf(
            "state", "code", "error", "error_description", "error_uri", "error_subcode",
            "scope", "authuser", "prompt", "hd", "session_state", "iss", "correlation_id", "trace_id",
        )
        val query = linkedMapOf<String, String>()
        for (part in uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty)) {
            val key = decode(part.substringBefore('='))
            val value = decode(part.substringAfter('=', ""))
            require(key in allowed && query.putIfAbsent(key, value) == null) { "허용되지 않은 OAuth callback 항목입니다." }
        }
        val state = query["state"].orEmpty().also(::stateDigest)
        val code = query["code"]?.also(::requireSafeCallbackValue)
        val error = query["error"]?.also(::requireSafeCallbackValue)
        require((code == null) != (error == null)) { "OAuth callback 결과가 올바르지 않습니다." }
        return OAuthCallback(state, code, error)
    }

    fun requireValidClientId(value: String): String = value.trim().also {
        require(it.isNotEmpty() && it.length <= 512 && it.none { char -> char == '\u0000' || char == '\r' || char == '\n' }) {
            "OAuth client ID가 올바르지 않습니다."
        }
    }

    fun optionalClientId(value: String?): String? = value?.takeIf(String::isNotBlank)?.let(::requireValidClientId)

    private fun requireSafeCallbackValue(value: String) {
        require(value.isNotBlank() && value.length <= 8_192 && value.none { it == '\u0000' || it == '\r' || it == '\n' }) {
            "OAuth callback 값이 올바르지 않습니다."
        }
    }

    private fun randomUrlSafe(byteCount: Int, random: SecureRandom): String = ByteArray(byteCount).also(random::nextBytes).let(::base64Url)
    private fun base64Url(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
    private fun decode(value: String): String = runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }
        .getOrElse { throw IllegalArgumentException("OAuth callback 인코딩이 올바르지 않습니다.") }
}

class SingleUseOAuthSessionGate(
    private val loadAndDelete: (stateDigest: String) -> OAuthPendingSession?,
) {
    fun consume(state: String, nowMillis: Long): OAuthPendingSession {
        val digest = OAuthSecurityPolicy.stateDigest(state)
        val session = loadAndDelete(digest) ?: throw IllegalArgumentException("만료되었거나 이미 사용한 OAuth 요청입니다.")
        require(OAuthSecurityPolicy.constantTimeEquals(session.stateDigest, digest)) { "OAuth state가 일치하지 않습니다." }
        require(nowMillis in session.createdAtMillis..session.expiresAtMillis) { "OAuth 요청이 만료되었습니다." }
        return session
    }
}
