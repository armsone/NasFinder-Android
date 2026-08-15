package com.armsone.nasfinder.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class AppEntryRouteParserTest {
    private val id = UUID.fromString("78ef8aed-8ac9-4f0c-bad9-aea5f52a35e3")

    @Test fun validInboxDeepLinkReturnsRecordId() {
        assertEquals(
            AppEntryRoute.Inbox(id),
            AppEntryRouteParser.parse("android.intent.action.VIEW", "nasfinder://inbox?id=$id"),
        )
        assertEquals(
            AppEntryRoute.Inbox(id),
            AppEntryRouteParser.parse("android.intent.action.VIEW", "NASFINDER://INBOX/?id=$id"),
        )
    }

    @Test fun invalidOrMissingUuidIsRejected() {
        assertRejected("nasfinder://inbox")
        assertRejected("nasfinder://inbox?id=not-a-uuid")
        assertRejected("nasfinder://inbox?id=$id&id=${UUID.randomUUID()}")
        assertRejected("nasfinder://inbox?id=%ZZ")
    }

    @Test fun wrongDeepLinkShapeAndSchemeAreRejected() {
        assertRejected("https://inbox?id=$id")
        assertRejected("nasfinder://settings?id=$id")
        assertRejected("nasfinder://inbox/child?id=$id")
        assertRejected("nasfinder://user@inbox?id=$id")
        assertRejected("nasfinder://inbox?id=$id#fragment")
        assertRejected("nasfinder://inbox?id=$id&next=dashboard")
        assertRejected("javascript:alert(1)")
    }

    @Test fun onlyWellFormedContentAndFileUrisCanBeImported() {
        assertEquals(
            AppEntryRoute.ImportUri,
            AppEntryRouteParser.parse("android.intent.action.VIEW", "content://media/external/file/10"),
        )
        assertEquals(
            AppEntryRoute.ImportUri,
            AppEntryRouteParser.parse("android.intent.action.VIEW", "file:///storage/emulated/0/Download/a.pdf"),
        )
        assertRejected("content:///missing-authority")
        assertRejected("file:///")
        assertRejected("ftp://server/file")
    }

    @Test fun nonViewActionsAreIgnoredEvenWithMaliciousUri() {
        assertEquals(
            AppEntryRoute.Ignore,
            AppEntryRouteParser.parse("android.intent.action.SEND", "javascript:alert(1)"),
        )
        assertEquals(AppEntryRoute.Ignore, AppEntryRouteParser.parse(null, null))
    }

    private fun assertRejected(uri: String) {
        assertTrue(AppEntryRouteParser.parse("android.intent.action.VIEW", uri) is AppEntryRoute.Rejected)
    }
}
