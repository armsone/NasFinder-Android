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
}
