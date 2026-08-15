package com.armsone.nasfinder.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.armsone.nasfinder.data.AppSettingsRepository

class AppIconRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // Repository initialization performs exactly one persisted-icon reconciliation.
            AppSettingsRepository(context)
        }
    }
}
