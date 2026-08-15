package com.armsone.nasfinder.platform

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.armsone.nasfinder.MainActivity
import com.armsone.nasfinder.R

/** Android counterpart of the iOS accessory widget: one glyph that opens NasFinder. */
class NasFinderAppWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        update(context, manager, appWidgetIds)
    }

    override fun onEnabled(context: Context) {
        updateAll(context)
    }

    companion object {
        /** Call after connections, the preferred connection, or SharedInbox contents change. */
        @JvmStatic
        fun updateAll(context: Context) {
            val applicationContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(applicationContext)
            val component = ComponentName(applicationContext, NasFinderAppWidgetProvider::class.java)
            update(applicationContext, manager, manager.getAppWidgetIds(component))
        }

        private fun update(context: Context, manager: AppWidgetManager, widgetIds: IntArray) {
            if (widgetIds.isEmpty()) return
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            widgetIds.forEach { widgetId ->
                val views = RemoteViews(context.packageName, R.layout.nasfinder_widget).apply {
                    setContentDescription(
                        R.id.widget_root,
                        context.getString(R.string.widget_open_label),
                    )
                    setOnClickPendingIntent(R.id.widget_root, pendingIntent)
                }
                manager.updateAppWidget(widgetId, views)
            }
        }
    }
}
