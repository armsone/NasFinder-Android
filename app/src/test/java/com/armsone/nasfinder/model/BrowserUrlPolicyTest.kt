package com.armsone.nasfinder.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserUrlPolicyTest {
    @Test fun missingSchemeDefaultsToHttps() {
        assertEquals("https://example.com/path", BrowserUrlPolicy.normalize("example.com/path"))
    }

    @Test fun onlyHttpAndHttpsOpenInsideApp() {
        assertTrue(BrowserUrlPolicy.canOpenInsideApp("https://example.com"))
        assertTrue(BrowserUrlPolicy.canOpenInsideApp("http://192.168.0.2"))
        assertFalse(BrowserUrlPolicy.canOpenInsideApp("file:///etc/passwd"))
        assertFalse(BrowserUrlPolicy.canOpenInsideApp("javascript:alert(1)"))
    }

    @Test fun hostIsRequired() {
        assertNull(BrowserUrlPolicy.normalize("https:///missing-host"))
        assertNull(BrowserUrlPolicy.normalize("  "))
    }
}
