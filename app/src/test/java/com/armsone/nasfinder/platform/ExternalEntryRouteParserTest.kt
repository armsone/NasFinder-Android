package com.armsone.nasfinder.platform

import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalEntryRouteParserTest {
    @Test fun `fixed shortcut routes open only their intended screens`() {
        assertEquals(ExternalEntryRoute.Inbox, parse(ExternalEntryRouteParser.INBOX_URI))
        assertEquals(ExternalEntryRoute.WebHard, parse(ExternalEntryRouteParser.WEB_HARD_URI))
        assertEquals(ExternalEntryRoute.WebBrowser, parse(ExternalEntryRouteParser.WEB_BROWSER_URI))
    }

    @Test fun `existing inbox item deep link remains delegated`() {
        assertEquals(
            ExternalEntryRoute.PassThrough,
            parse("nasfinder://inbox?id=123e4567-e89b-12d3-a456-426614174000"),
        )
    }

    @Test fun `non view intents and content files remain delegated`() {
        assertEquals(
            ExternalEntryRoute.PassThrough,
            ExternalEntryRouteParser.parse("android.intent.action.SEND", ExternalEntryRouteParser.INBOX_URI),
        )
        assertEquals(ExternalEntryRoute.PassThrough, parse("content://files/item"))
    }

    @Test fun `query path authority and fragment cannot alter a shortcut route`() {
        assertEquals(ExternalEntryRoute.Rejected, parse("nasfinder://webhard?start=true"))
        assertEquals(ExternalEntryRoute.Rejected, parse("nasfinder://webhard/other"))
        assertEquals(ExternalEntryRoute.Rejected, parse("nasfinder://attacker@webhard"))
        assertEquals(ExternalEntryRoute.Rejected, parse("nasfinder://webhard#fragment"))
        assertEquals(ExternalEntryRoute.Rejected, parse("nasfinder://unknown"))
    }

    private fun parse(uri: String) = ExternalEntryRouteParser.parse("android.intent.action.VIEW", uri)
}
