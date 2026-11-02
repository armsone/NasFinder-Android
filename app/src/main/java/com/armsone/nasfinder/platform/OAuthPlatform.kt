package com.armsone.nasfinder.platform

import android.content.Context
import com.armsone.nasfinder.data.CloudTransportCredential
import com.armsone.nasfinder.data.ConnectionRepository
import com.armsone.nasfinder.data.OAuthClientConfigurationStore
import com.armsone.nasfinder.data.OAuthSessionStore
import com.armsone.nasfinder.data.OAuthTokenStore
import com.armsone.nasfinder.model.CloudOAuthProvider
import com.armsone.nasfinder.model.OAuthSecurityPolicy
import com.armsone.nasfinder.model.OAuthTokenSet
import com.armsone.nasfinder.model.RemoteConnection
import com.armsone.nasfinder.network.CloudAccessTokenSource
import com.armsone.nasfinder.network.RemoteServiceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

/** 토큰 서버가 요청 자체를 거부했다(만료·폐기된 갱신 토큰 등). 재시도가 아니라 다시 로그인이 필요하다. */
class OAuthTokenRejectedException : IOException("OAuth token 요청을 완료하지 못했습니다.")

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
            if (response.code == 400 || response.code == 401) throw OAuthTokenRejectedException()
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
        if (connections.loadAll().none { it.id == connectionId && it.kind == provider.connectionKind }) return null
        val clientId = clients.clientId(provider) ?: return null
        val (state, session) = sessions.create(provider, connectionId, clientId, nowMillis)
        return OAuthSecurityPolicy.authorizationUrl(provider, clientId, state, session.codeVerifier)
    }

    suspend fun completeCallback(rawUri: String, nowMillis: Long = System.currentTimeMillis()): OAuthTokenSet {
        val callback = OAuthSecurityPolicy.parseCallback(rawUri)
        val session = sessions.consume(callback.state, nowMillis)
        if (callback.error != null) throw IOException("OAuth 로그인이 취소되었거나 거부되었습니다.")
        // 1회용 state를 이미 소진했으므로 표시 모드가 아니라 저장된 전체 연결로 확인한다.
        if (connections.loadAll().none { it.id == session.connectionId && it.kind == session.provider.connectionKind }) {
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

class OAuthAccessTokenProvider(context: Context) : CloudAccessTokenSource {
    private val clients = OAuthClientConfigurationStore(context.applicationContext)
    private val tokens = OAuthTokenStore(context.applicationContext)
    private val endpointClient = OAuthTokenEndpointClient()

    /** Null means no automatic credential is available; callers keep using the existing manual-token path. */
    suspend fun accessToken(
        connectionId: String,
        provider: CloudOAuthProvider,
        nowMillis: Long = System.currentTimeMillis(),
        persistRefresh: Boolean = true,
    ): String? = refreshLock.withLock {
        val current = automaticTokens(connectionId)?.takeIf { it.provider == provider } ?: return@withLock null
        current.usableAccessToken(nowMillis)?.let { return@withLock it }
        val refreshToken = current.refreshToken ?: return@withLock null
        val clientId = clients.clientId(provider) ?: return@withLock null
        val refreshed = endpointClient.refresh(provider, clientId, refreshToken, refreshToken, nowMillis).let {
            if (it.scope == null && current.scope != null) it.copy(scope = current.scope) else it
        }
        if (clients.clientId(provider) != clientId) return@withLock null
        if (persistRefresh) {
            if (!tokens.saveIfUnchanged(connectionId, current, refreshed)) return@withLock null
        } else if (automaticTokens(connectionId) != current) {
            return@withLock null
        }
        refreshed.accessToken
    }

    private fun automaticTokens(connectionId: String): OAuthTokenSet? =
        (tokens.transportCredential(connectionId) as? CloudTransportCredential.Automatic)?.tokens

    /**
     * 일반 전송 경로: 수동 토큰 연결은 지금 저장된 수동 토큰을, 자동 OAuth 연결은 유효하거나 갱신한 토큰을 돌려준다.
     * 자동 연결을 갱신할 수 없으면 수동 키에 함께 저장된 만료 토큰으로 넘어가지 않고 실패시킨다.
     */
    override suspend fun currentAccessToken(connection: RemoteConnection): String? {
        currentCoroutineContext().ensureActive()
        val provider = CloudOAuthProvider.from(connection.kind) ?: return null
        when (val credential = tokens.transportCredential(connection.id)) {
            is CloudTransportCredential.Manual -> return credential.token
            CloudTransportCredential.Unreadable -> throw loginRequired()
            is CloudTransportCredential.Automatic -> {
                if (credential.tokens.provider != provider) throw loginRequired()
                credential.tokens.usableAccessToken(System.currentTimeMillis())?.let { return it }
            }
        }
        return refreshedTransportToken(connection.id, provider)
    }

    private suspend fun refreshedTransportToken(connectionId: String, provider: CloudOAuthProvider): String? =
        refreshLock.withLock {
            // 락을 기다리거나 조건부 저장에 실패한 사이 값이 바뀌었을 수 있으므로 매번 저장된 값을 다시 읽는다.
            repeat(MAX_TRANSPORT_REFRESH_ATTEMPTS) {
                val current = when (val credential = tokens.transportCredential(connectionId)) {
                    is CloudTransportCredential.Manual -> return@withLock credential.token
                    CloudTransportCredential.Unreadable -> throw loginRequired()
                    is CloudTransportCredential.Automatic -> credential.tokens
                }
                if (current.provider != provider) throw loginRequired()
                val nowMillis = System.currentTimeMillis()
                current.usableAccessToken(nowMillis)?.let { return@withLock it }
                val refreshToken = current.refreshToken ?: throw loginRequired()
                val clientId = clients.clientId(provider) ?: throw loginRequired()
                // 서버가 갱신 토큰을 바꿔 줬다면 호출자가 취소되어도 결과를 저장해야 다음 갱신이 실패하지 않는다.
                // 취소는 이 구간이 끝난 직후(토큰 요청 제한 시간 이내)에 반영된다.
                val saved = withContext(NonCancellable) {
                    val refreshed = try {
                        endpointClient.refresh(provider, clientId, refreshToken, refreshToken, nowMillis)
                    } catch (_: OAuthTokenRejectedException) {
                        throw loginRequired()
                    } catch (error: IOException) {
                        throw RemoteServiceException.Connection("클라우드 로그인을 갱신하지 못했습니다.", error)
                    } catch (_: IllegalArgumentException) {
                        throw loginRequired()
                    }
                    val merged = if (refreshed.scope == null && current.scope != null) {
                        refreshed.copy(scope = current.scope)
                    } else {
                        refreshed
                    }
                    if (clients.clientId(provider) != clientId) throw loginRequired()
                    merged.accessToken.takeIf { tokens.saveIfUnchanged(connectionId, current, merged) }
                }
                currentCoroutineContext().ensureActive()
                saved?.let { return@withLock it }
            }
            throw loginRequired()
        }

    private fun loginRequired() =
        RemoteServiceException.Authentication("클라우드 로그인이 만료되었습니다. 다시 로그인해 주세요.")

    private companion object {
        val refreshLock = Mutex()
        const val MAX_TRANSPORT_REFRESH_ATTEMPTS = 2
    }
}
