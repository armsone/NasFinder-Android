package com.armsone.nasfinder.platform

import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class WebHardTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val intent = NasFinderShortcuts.entryIntent(this, ExternalEntryRouteParser.WEB_HARD_URI)
        val pendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_WEB_HARD,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(pendingIntent)
        } else {
            startActivityAndCollapse(intent)
        }
    }

    private companion object { const val REQUEST_OPEN_WEB_HARD = 0x5748 }
}
