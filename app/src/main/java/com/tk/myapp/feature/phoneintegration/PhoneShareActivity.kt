package com.tk.myapp.feature.phoneintegration

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat

class PhoneShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShareIntent(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
        finish()
    }

    private fun handleShareIntent(intent: Intent?) {
        val shareIntent = intent ?: return
        val action = shareIntent.action ?: return
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) {
            return
        }
        ContextCompat.startForegroundService(
            this,
            Intent(this, PhoneIntegrationService::class.java)
        )
        val controller = PhoneIntegrationController.getInstance(this)
        controller.start()
        controller.handleShareIntent(
            intent = shareIntent,
            mode = ShareHandlingMode.Background
        )
    }
}
