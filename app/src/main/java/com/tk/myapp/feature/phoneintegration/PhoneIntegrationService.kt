package com.tk.myapp.feature.phoneintegration

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.tk.myapp.R

class PhoneIntegrationService : Service() {
    private lateinit var controller: PhoneIntegrationController

    override fun onCreate() {
        super.onCreate()
        controller = PhoneIntegrationController.getInstance(this)
        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Toolkit Phone Bridge")
                .setContentText("Listening for trusted Macs on your local network")
                .setOngoing(true)
                .build()
        )
        controller.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        controller.start()
        return START_STICKY
    }

    override fun onDestroy() {
        controller.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Phone bridge",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "phone_bridge"
        private const val NOTIFICATION_ID = 4401
    }
}
