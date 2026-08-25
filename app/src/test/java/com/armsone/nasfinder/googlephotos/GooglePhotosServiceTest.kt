package com.armsone.nasfinder.googlephotos

import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GooglePhotosServiceTest {
    private val config = GooglePhotosOAuthConfiguration("123.apps.googleusercontent.com")

    @Test fun authorizationUsesPkceAndExactPickerScope() {
        val attempt = GooglePhotosOAuthPolicy.createAttempt(config)
        assertTrue(attempt.authorizationUrl.contains("code_challenge_method=S256"))
        assertTrue(attempt.authorizationUrl.contains("photospicker.mediaitems.readonly"))
        assertFalse(attempt.authorizationUrl.contains(attempt.codeVerifier))
        assertEquals("com.googleusercontent.apps.123:/oauthredirect", config.redirectUri)
    }

    @Test fun callbackRejectsWrongStateAndReturnsCode() {
        assertEquals("ok", GooglePhotosOAuthPolicy.authorizationCode("${config.redirectUri}?state=s&code=ok", "s", config))
        assertThrows(GooglePhotosOAuthException.StateMismatch::class.java) {
            GooglePhotosOAuthPolicy.authorizationCode("${config.redirectUri}?state=x&code=secret", "s", config)
        }
    }

    @Test fun refreshPreservesOldRefreshTokenAndDoesNotLeakIt() = runBlocking {
        val requests = mutableListOf<GooglePhotosHttpRequest>()
        val client = GooglePhotosTokenClient(config, GooglePhotosHttpTransport { request ->
            requests += request; GooglePhotosHttpResponse(200, """{"access_token":"new","expires_in":3600}""")
        }, { Instant.ofEpochSecond(100) })
        val result = client.refresh(GooglePhotosCredential("old", "refresh-secret", 0, emptySet()))
        assertEquals("refresh-secret", result.refreshToken)
        assertEquals(3700L, result.expiresAtEpochSeconds)
        assertFalse(result.toString().contains("refresh-secret"))
        assertTrue(requests.single().body!!.contains("grant_type=refresh_token"))
    }

    @Test fun pickerPaginatesAndStopsRepeatedToken() = runBlocking {
        var calls = 0
        val client = GooglePhotosPickerClient(GooglePhotosHttpTransport {
            calls++
            GooglePhotosHttpResponse(200, if (calls == 1) """{"mediaItems":[{"id":"1","type":"PHOTO"}],"nextPageToken":"same"}""" else """{"mediaItems":[{"id":"2","type":"VIDEO"}],"nextPageToken":"same"}""")
        }, { "token" })
        assertEquals(listOf("1", "2"), client.allMediaItems("session").map { it.id })
        assertEquals(2, calls)
    }

    @Test fun contentSuffixAndAuthorizationAreCorrect() = runBlocking {
        val item = GooglePhotosMediaItem("v", GooglePhotosMediaType.VIDEO, GooglePhotosMediaFile("https://lh3.googleusercontent.com/a=d", "video/mp4", "a.mp4", GooglePhotosVideoStatus.READY))
        val request = GooglePhotosPickerClient(GooglePhotosHttpTransport { GooglePhotosHttpResponse(200) }, { "bearer" }).contentRequest(item)
        assertEquals("https://lh3.googleusercontent.com/a=dv", request.url)
        assertEquals("Bearer bearer", request.headers["Authorization"])
    }

    @Test fun importerSkipsInvalidContinuesFailureAndImportsNext() = runBlocking {
        val invalid = GooglePhotosMediaItem("bad", GooglePhotosMediaType.VIDEO, GooglePhotosMediaFile("u", "video/mp4", "a", GooglePhotosVideoStatus.PROCESSING))
        val fail = GooglePhotosMediaItem("fail", GooglePhotosMediaType.PHOTO, GooglePhotosMediaFile("u", "image/jpeg", "a"))
        val good = fail.copy(id = "good")
        val summary = GooglePhotosImportCoordinator({ item -> if (item.id == "fail") error("download") else File("/tmp/a") }, { _, _ -> }).import(listOf(invalid, fail, good))
        assertEquals(1, summary.importedCount)
        assertTrue(summary.outcomes[0] is GooglePhotosImportOutcome.Skipped)
        assertTrue(summary.outcomes[1] is GooglePhotosImportOutcome.Failed)
    }

    @Test fun errorsNeverContainTokenOrResponseBody() = runBlocking {
        val token = "top-secret-token"
        val client = GooglePhotosPickerClient(GooglePhotosHttpTransport { GooglePhotosHttpResponse(403, "server leaked $token") }, { token })
        val error = runCatching { client.createSession() }.exceptionOrNull()!!
        assertFalse(error.toString().contains(token))
        assertFalse(error.toString().contains("server leaked"))
    }
}
