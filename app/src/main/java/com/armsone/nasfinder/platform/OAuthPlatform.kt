package com.armsone.nasfinder.platform

import android.content.Context
import com.armsone.nasfinder.data.ConnectionRepository
import com.armsone.nasfinder.data.OAuthClientConfigurationStore
import com.armsone.nasfinder.data.OAuthSessionStore
import com.armsone.nasfinder.data.OAuthTokenStore
import com.armsone.nasfinder.model.CloudOAuthProvider
import com.armsone.nasfinder.model.OAuthSecurityPolicy
import com.armsone.nasfinder.model.OAuthTokenSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class OAuthTokenEndpointClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun exchangeAuthorizationCode(
        provider: CloudOAuthProvider,
        clientId: String,
        code: String,
        codeVerifier: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): OAuthTokenSet = requestTokens(
        provider = provider,
        clientId = clientId,
        nowMillis = nowMillis,
        form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", OAuthSecurityPolicy.requireValidClientId(clientId))
            .add("code", requireSafeSecret(code, "authorization code"))
            .add("redirect_uri", OAuthSecurityPolicy.REDIRECT_URI)
            .add("code_verifier", codeVerifier.also(OAuthSecurityPolicy::codeChallenge))
            .build(),
    )

    suspend fun refresh(
        provider: CloudOAuthProvider,
        clientId: String,
        refreshToken: String,
        previousRefreshToken: String = refreshToken,
        nowMillis: Long = System.currentTimeMillis(),
    ): OAuthTokenSet = requestTokens(
        provider = provider,
        clientId = clientId,
        nowMillis = nowMillis,
        previousRefreshToken = previousRefreshToken,
        form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", OAuthSecurityPolicy.requireValidClientId(clientId))
            .add("refresh_token", requireSafeSecret(refreshToken, "refresh token"))
            .build(),
    )

    private suspend fun requestTokens(
        provider: CloudOAuthProvider,
        clientId: String,
        form: FormBody,
        nowMillis: Long,
        previousRefreshToken: String? = null,
    ): OAuthTokenSet = withContext(Dispatchers.IO) {
        OAuthSecurityPolicy.requireValidClientId(clientId)
        val endpoint = provider.tokenEndpoint.toHttpUrl()
        check(endpoint.isHttps && endpoint.host == provider.tokenHost) { "OAuth token endpoint가 허용되지 않았습니다." }
        val request = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .post(form)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("OAuth token 요청을 완료하지 못했습니다.")
            val body = response.body ?: throw IOException("OAuth token 응답이 비어 있습니다.")
            val length = body.contentLength()
            if (length > MAX_RESPONSE_BYTES) throw IOException("OAuth token 응답이 너무 큽니다.")
            val bytes = body.byteStream().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                while (output.size() <= MAX_RESPONSE_BYTES) {
                    val count = input.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_BYTES + 1 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            if (bytes.size > MAX_RESPONSE_BYTES) throw IOException("OAuth token 응답이 너무 큽니다.")
            val json = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }
                .getOrElse { throw IOException("OAuth token 응답이 올바르지 않습니다.") }
            val accessToken = json.optString("access_token").let { requireSafeSecret(it, "access token") }
            val tokenType = json.optString("token_type", "Bearer")
            if (!tokenType.equals("Bearer", ignoreCase = true)) throw IOException("지원하지 않는 OAuth token 형식입니다.")
            val expiresIn = json.optLong("expires_in", 0L)
            if (expiresIn !in 1L..MAX_EXPIRES_SECONDS) throw IOException("OAuth token 만료 시간이 올바르지 않습니다.")
            val refreshToken = json.optString("refresh_token").takeIf(String::isNotBlank)
                ?.let { requireSafeSecret(it, "refresh token") }
                ?: previousRefreshToken
            OAuthTokenSet(
                provider = provider,
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresAtMillis = nowMillis + expiresIn * 1_000L,
                scope = json.optString("scope").takeIf(String::isNotBlank),
            )
        }
    }

    private fun requireSafeSecret(value: String, label: String): String = value.also {
        require(it.isNotBlank() && it.length <= 16_384 && it.none { char -> char == '\u0000' || char == '\r' || char == '\n' }) {
            "$label 값이 올바르지 않습니다."
        }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 1024 * 1024
        const val MAX_EXPIRES_SECONDS = 365L * 24L * 60L * 60L
    }
}

class OAuthCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val connections = ConnectionRepository(appContext)
    private val clients = OAuthClientConfigurationStore(appContext)
    private val sessions = OAuthSessionStore(appContext)
    private val tokens = OAuthTokenStore(appContext)
    private val endpointClient = OAuthTokenEndpointClient()

    /** Returns null when the user has not configured a public client ID. */
    fun begin(provider: CloudOAuthProvider, connectionId: String, nowMillis: Long = System.currentTimeMillis()): String? {
        if (connections.load().none { it.id == connectionId && it.kind == provider.connectionKind }) return null
        val clientId = clients.clientId(provider) ?: return null
        val (state, session) = sessions.create(provider, connectionId, clientId, nowMillis)
        return OAuthSecurityPolicy.authorizationUrl(provider, clientId, state, session.codeVerifier)
    }

    suspend fun completeCallback(rawUri: String, nowMillis: Long = System.currentTimeMillis()): OAuthTokenSet {
        val callback = OAuthSecurityPolicy.parseCallback(rawUri)
        val session = sessions.consume(callback.state, nowMillis)
        if (callback.error != null) throw IOException("OAuth 로그인이 취소되었거나 거부되었습니다.")
        if (connections.load().none { it.id == session.connectionId && it.kind == session.provider.connectionKind }) {
            throw IOException("OAuth 연결을 찾을 수 없습니다.")
        }
        val configuredClientId = clients.clientId(session.provider)
        if (configuredClientId == null || configuredClientId != session.clientId) {
            throw IOException("OAuth client 설정이 변경되었습니다.")
        }
        val tokenSet = endpointClient.exchangeAuthorizationCode(
            provider = session.provider,
            clientId = session.clientId,
            code = callback.code ?: throw IOException("OAuth authorization code가 없습니다."),
            codeVerifier = session.codeVerifier,
            nowMillis = nowMillis,
        )
        tokens.save(session.connectionId, tokenSet)
        return tokenSet
    }
}

class OAuthAccessTokenProvider(context: Context) {
    private val clients = OAuthClientConfigurationStore(context.applicationContext)
    private val tokens = OAuthTokenStore(context.applicationContext)
    private val endpointClient = OAuthTokenEndpointClient()

    /** Null means no automatic credential is available; callers keep using the existing manual-token path. */
    suspend fun accessToken(
        connectionId: String,
        provider: CloudOAuthProvider,
        nowMillis: Long = System.currentTimeMillis(),
    ): String? = refreshLock.withLock {
        val current = tokens.read(connectionId)?.takeIf { it.provider == provider } ?: return@withLock null
        current.usableAccessToken(nowMillis)?.let { return@withLock it }
        val refreshToken = current.refreshToken ?: return@withLock null
        val clientId = clients.clientId(provider) ?: return@withLock null
        val refreshed = endpointClient.refresh(provider, clientId, refreshToken, refreshToken, nowMillis).let {
            if (it.scope == null && current.scope != null) it.copy(scope = current.scope) else it
        }
        tokens.save(connectionId, refreshed)
        refreshed.accessToken
    }

    private companion object { val refreshLock = Mutex() }
}
