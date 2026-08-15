package com.armsone.nasfinder.platform

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.armsone.nasfinder.MainActivity
import kotlinx.coroutines.launch

class OAuthCallbackActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val callbackUri = intent?.dataString
        lifecycleScope.launch {
            val succeeded = callbackUri != null && runCatching {
                OAuthCoordinator(applicationContext).completeCallback(callbackUri)
            }.isSuccess
            startActivity(
                Intent(this@OAuthCallbackActivity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(EXTRA_OAUTH_SUCCEEDED, succeeded)
            )
            finish()
        }
    }

    companion object { const val EXTRA_OAUTH_SUCCEEDED = "com.armsone.nasfinder.oauth.SUCCEEDED" }
}
