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
import com.armsone.nasfinder.data.ConnectionRepository
import com.armsone.nasfinder.data.SharedInboxStore
import java.io.File

/** Small dependency-free home-screen summary widget. */
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
            val repository = ConnectionRepository(context)
            val connections = repository.load()
            val preferredId = repository.preferredId()
            val defaultConnection = connections.firstOrNull { it.id == preferredId }
            val receivedCount = widgetInboxRecordCount(context.filesDir)
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
                    setTextViewText(
                        R.id.widget_default_connection,
                        defaultConnection?.name ?: context.getString(R.string.widget_no_default_connection),
                    )
                    setTextViewText(
                        R.id.widget_connection_count,
                        context.resources.getQuantityString(
                            R.plurals.widget_connection_count,
                            connections.size,
                            connections.size,
                        ),
                    )
                    setTextViewText(
                        R.id.widget_received_count,
                        context.resources.getQuantityString(
                            R.plurals.widget_received_count,
                            receivedCount,
                            receivedCount,
                        ),
                    )
                    setContentDescription(
                        R.id.widget_root,
                        context.getString(
                            R.string.widget_content_description,
                            defaultConnection?.name ?: context.getString(R.string.widget_no_default_connection),
                            connections.size,
                            receivedCount,
                        ),
                    )
                    setOnClickPendingIntent(R.id.widget_root, pendingIntent)
                }
                manager.updateAppWidget(widgetId, views)
            }
        }
    }
}

/** Pure record count used by the widget; manifest and orphan payloads are never counted. */
internal fun widgetInboxRecordCount(filesDirectory: File): Int =
    runCatching { SharedInboxStore(filesDirectory).records().size }.getOrDefault(0)
