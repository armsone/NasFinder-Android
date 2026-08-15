package com.armsone.nasfinder.platform

import java.net.URI

sealed interface ExternalEntryRoute {
    data object PassThrough : ExternalEntryRoute
    data object Inbox : ExternalEntryRoute
    data object WebHard : ExternalEntryRoute
    data object WebBrowser : ExternalEntryRoute
    data object Rejected : ExternalEntryRoute
}

/** Pure, strict parser shared by app shortcuts and the Quick Settings entry route. */
object ExternalEntryRouteParser {
    const val INBOX_URI = "nasfinder://inbox"
    const val WEB_HARD_URI = "nasfinder://webhard"
    const val WEB_BROWSER_URI = "nasfinder://browser"
    private const val ACTION_VIEW = "android.intent.action.VIEW"

    fun parse(action: String?, rawUri: String?): ExternalEntryRoute {
        if (action != ACTION_VIEW || rawUri.isNullOrBlank()) return ExternalEntryRoute.PassThrough
        val uri = runCatching { URI(rawUri) }.getOrNull() ?: return ExternalEntryRoute.Rejected
        if (!uri.scheme.equals("nasfinder", ignoreCase = true)) return ExternalEntryRoute.PassThrough
        if (uri.userInfo != null || uri.port != -1 || uri.rawFragment != null || uri.rawPath.orEmpty() !in setOf("", "/")) {
            return ExternalEntryRoute.Rejected
        }
        val host = uri.host?.lowercase() ?: return ExternalEntryRoute.Rejected
        if (host == "inbox" && uri.rawQuery != null) return ExternalEntryRoute.PassThrough
        if (uri.rawQuery != null) return ExternalEntryRoute.Rejected
        return when (host) {
            "inbox" -> ExternalEntryRoute.Inbox
            "webhard" -> ExternalEntryRoute.WebHard
            "browser" -> ExternalEntryRoute.WebBrowser
            else -> ExternalEntryRoute.Rejected
        }
    }
}
