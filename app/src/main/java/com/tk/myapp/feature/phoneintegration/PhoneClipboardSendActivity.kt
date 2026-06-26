package com.tk.myapp.feature.phoneintegration

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat

class PhoneClipboardSendActivity : ComponentActivity() {
    private var hasSent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ContextCompat.startForegroundService(
            this,
            Intent(this, PhoneIntegrationService::class.java)
        )
    }

    override fun onResume() {
        super.onResume()
        if (hasSent) {
            finish()
            overridePendingTransition(0, 0)
            return
        }
        hasSent = true
        window.decorView.postDelayed({
            PhoneIntegrationController.getInstance(this).sendCurrentClipboardToMac(showToast = true)
            finish()
            overridePendingTransition(0, 0)
        }, 180)
    }
}
