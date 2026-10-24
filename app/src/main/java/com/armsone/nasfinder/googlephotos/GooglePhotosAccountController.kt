package com.armsone.nasfinder.googlephotos

import android.content.Context
import com.armsone.nasfinder.model.CloudOAuthProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.net.URLDecoder
import java.time.Instant

class GooglePhotosAccountController(context: Context) {
    private val app = context.applicationContext
    private val credentials = GooglePhotosCredentialStore(app)
    private val sessions = GooglePhotosAuthorizationSessionStore(app)
    private val transport = OkHttpGooglePhotosTransport()

    fun isConnected(): Boolean = credentials.read() != null

    fun beginAuthorization(): String {
        val config = configuration()
        val attempt = GooglePhotosOAuthPolicy.createAttempt(config)
        sessions.save(attempt)
        return attempt.authorizationUrl
    }

    /** null means this callback belongs to another OAuth capability. */
    suspend fun completeCallbackIfPending(rawUri: String): Boolean? {
        val uri = runCatching { URI(rawUri) }.getOrNull() ?: return null
        val values = uri.rawQuery.orEmpty().split('&').mapNotNull {
            val p = it.split('=', limit = 2); if (p.size == 2) URLDecoder.decode(p[0], "UTF-8") to URLDecoder.decode(p[1], "UTF-8") else null
        }.toMap()
        val state = values["state"] ?: return null
        val attempt = sessions.consume(state) ?: return null
        return runCatching {
            val code = GooglePhotosOAuthPolicy.authorizationCode(rawUri, attempt.state, configuration())
            credentials.save(GooglePhotosTokenClient(configuration(), transport).exchange(code, attempt.codeVerifier))
        }.isSuccess
    }

    suspend fun disconnect() {
        val credential = credentials.read() ?: return
        runCatching { GooglePhotosTokenClient(configuration(), transport).revoke(credential) }
        credentials.delete()
    }

    suspend fun pickerClient(): GooglePhotosPickerClient = GooglePhotosPickerClient(transport, token = { accessToken() })

    private suspend fun accessToken(): String = refreshMutex.withLock {
        val current = credentials.read() ?: throw GooglePhotosOAuthException.ReauthorizationRequired
        if (current.isValid()) return@withLock current.accessToken
        val refreshed = GooglePhotosTokenClient(configuration(), transport).refresh(current)
        credentials.save(refreshed)
        refreshed.accessToken
    }

    private fun configuration(): GooglePhotosOAuthConfiguration {
        val dedicated = app.getSharedPreferences("google.photos.oauth.v1", Context.MODE_PRIVATE)
            .getString("clientId", null)?.trim()?.takeIf(String::isNotBlank)
        val drive = com.armsone.nasfinder.data.OAuthClientConfigurationStore(app).clientId(CloudOAuthProvider.GOOGLE_DRIVE)
        val clientId = dedicated ?: drive ?: throw IllegalStateException("Google 포토에 사용할 Google client ID가 없습니다.")
        return GooglePhotosOAuthConfiguration(clientId, redirectUriOverride = REDIRECT_URI)
    }

    companion object {
        const val REDIRECT_URI = "nasfinder-oauth://callback/v1"
        private val refreshMutex = Mutex()
    }
}
