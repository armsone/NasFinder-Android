package com.armsone.nasfinder.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class OAuthSecurityPolicyTest {
    @Test fun `automatic OAuth stays disabled without a user client ID`() {
        assertNull(OAuthSecurityPolicy.optionalClientId(null))
        assertNull(OAuthSecurityPolicy.optionalClientId("   "))
        assertEquals("configured-client", OAuthSecurityPolicy.optionalClientId(" configured-client "))
    }

    @Test fun `PKCE uses the RFC 7636 S256 contract`() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", OAuthSecurityPolicy.codeChallenge(verifier))
    }

    @Test fun `every provider pins HTTPS authorization and token hosts`() {
        val state = "a".repeat(43)
        val verifier = "b".repeat(64)
        val expected = mapOf(
            CloudOAuthProvider.DROPBOX to ("www.dropbox.com" to "api.dropboxapi.com"),
            CloudOAuthProvider.ONEDRIVE to ("login.microsoftonline.com" to "login.microsoftonline.com"),
            CloudOAuthProvider.GOOGLE_DRIVE to ("accounts.google.com" to "oauth2.googleapis.com"),
        )
        expected.forEach { (provider, hosts) ->
            val authorize = URI(OAuthSecurityPolicy.authorizationUrl(provider, "public-client", state, verifier))
            val token = URI(provider.tokenEndpoint)
            assertEquals("https", authorize.scheme)
            assertEquals(hosts.first, authorize.host)
            assertEquals("https", token.scheme)
            assertEquals(hosts.second, token.host)
            val query = query(authorize)
            assertEquals(OAuthSecurityPolicy.REDIRECT_URI, query["redirect_uri"])
            assertEquals("S256", query["code_challenge_method"])
            assertFalse(query.containsKey("client_secret"))
        }
    }

    @Test fun `callback accepts only the dedicated route and rejects replay-shaped input`() {
        val state = "s".repeat(43)
        val callback = OAuthSecurityPolicy.parseCallback("nasfinder-oauth://callback/v1?code=abc&state=$state")
        assertEquals("abc", callback.code)
        assertEquals(state, callback.state)
        assertFails { OAuthSecurityPolicy.parseCallback("https://callback/v1?code=abc&state=$state") }
        assertFails { OAuthSecurityPolicy.parseCallback("nasfinder-oauth://callback/v1?code=abc&state=$state&state=$state") }
        assertFails { OAuthSecurityPolicy.parseCallback("nasfinder-oauth://callback/v1?code=abc&state=$state#fragment") }
        assertFails { OAuthSecurityPolicy.parseCallback("nasfinder-oauth://callback/v1?code=abc%0D%0Aheader&state=$state") }
    }

    @Test fun `session state is consumed once and expiration is enforced`() {
        val state = "x".repeat(43)
        val digest = OAuthSecurityPolicy.stateDigest(state)
        val session = OAuthPendingSession(
            CloudOAuthProvider.DROPBOX,
            "connection",
            "client",
            digest,
            "v".repeat(64),
            createdAtMillis = 1_000,
            expiresAtMillis = 2_000,
        )
        val stored = mutableMapOf(digest to session)
        val gate = SingleUseOAuthSessionGate(stored::remove)
        assertEquals(session, gate.consume(state, 1_500))
        assertFails { gate.consume(state, 1_500) }
        assertTrue(stored.isEmpty())

        val expired = mutableMapOf(digest to session)
        assertFails { SingleUseOAuthSessionGate(expired::remove).consume(state, 2_001) }
        assertTrue(expired.isEmpty())
    }

    @Test fun `access token expires early enough to refresh`() {
        val tokens = OAuthTokenSet(CloudOAuthProvider.GOOGLE_DRIVE, "access", "refresh", 100_000)
        assertEquals("access", tokens.usableAccessToken(1_000))
        assertNull(tokens.usableAccessToken(40_000))
    }

    private fun query(uri: URI): Map<String, String> = uri.rawQuery.split('&').associate { part ->
        fun decode(value: String) = URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        decode(part.substringBefore('=')) to decode(part.substringAfter('='))
    }

    private fun assertFails(block: () -> Unit) {
        var failed = false
        try { block() } catch (_: IllegalArgumentException) { failed = true }
        assertTrue(failed)
    }
}
