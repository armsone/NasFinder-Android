package com.armsone.nasfinder.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import com.armsone.nasfinder.MainActivity
import com.armsone.nasfinder.R

object NasFinderShortcuts {
    fun installDynamic(context: Context, launcherActivity: ComponentName? = null) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        val shortcuts = listOf(
            shortcut(
                context,
                ID_WEB_HARD,
                R.string.shortcut_webhard_short,
                R.string.shortcut_webhard_long,
                R.drawable.ic_shortcut_webhard,
                ExternalEntryRouteParser.WEB_HARD_URI,
                launcherActivity,
            ),
            shortcut(
                context,
                ID_WEB_BROWSER,
                R.string.shortcut_browser_short,
                R.string.shortcut_browser_long,
                R.drawable.ic_shortcut_browser,
                ExternalEntryRouteParser.WEB_BROWSER_URI,
                launcherActivity,
            ),
        )
        runCatching { manager.dynamicShortcuts = shortcuts }
    }

    fun entryIntent(context: Context, uri: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(uri), context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

    private fun shortcut(
        context: Context,
        id: String,
        shortLabel: Int,
        longLabel: Int,
        icon: Int,
        uri: String,
        launcherActivity: ComponentName?,
    ) = ShortcutInfo.Builder(context, id)
        .setShortLabel(context.getString(shortLabel))
        .setLongLabel(context.getString(longLabel))
        .setIcon(Icon.createWithResource(context, icon))
        .setIntent(entryIntent(context, uri))
        .apply { if (launcherActivity != null) setActivity(launcherActivity) }
        .build()

    private const val ID_WEB_HARD = "open_webhard"
    private const val ID_WEB_BROWSER = "open_browser"
}
