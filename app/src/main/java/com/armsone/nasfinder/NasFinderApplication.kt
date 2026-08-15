package com.armsone.nasfinder

import android.app.Application
import com.armsone.nasfinder.data.ConnectionRepository
import com.armsone.nasfinder.data.FavoriteRepository
import com.armsone.nasfinder.data.AppSettingsRepository
import com.armsone.nasfinder.data.SharedInboxStore

class NasFinderApplication : Application() {
    val connections by lazy { ConnectionRepository(this) }
    val favorites by lazy { FavoriteRepository(this) }
    val settings by lazy { AppSettingsRepository(this) }
    val inbox by lazy { SharedInboxStore(this) }

    override fun onCreate() {
        super.onCreate()
        // Apply a launcher choice before MainActivity exists so OEM launchers cannot close a
        // visible settings screen while aliases are switched.
        settings.restoreAppIcon()
    }
}
