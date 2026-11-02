package com.tk.myapp.feature.phoneintegration

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val store = PhoneIntegrationStore(context)
        if (!store.isBridgeEnabled()) return
        ContextCompat.startForegroundService(
            context,
            Intent(context, PhoneIntegrationService::class.java)
        )
    }
}
