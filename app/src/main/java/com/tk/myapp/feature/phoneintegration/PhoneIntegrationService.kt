package com.tk.myapp.feature.phoneintegration

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.net.wifi.WifiManager
import androidx.core.app.NotificationCompat
import com.tk.myapp.R

class PhoneIntegrationService : Service() {
    private lateinit var controller: PhoneIntegrationController
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate() {
        super.onCreate()
        controller = PhoneIntegrationController.getInstance(this)
        createNotificationChannel()
        acquireBridgeLocks()
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
        releaseBridgeLocks()
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

    private fun acquireBridgeLocks() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:phone-bridge"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "$packageName:phone-bridge"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
        multicastLock = wifiManager.createMulticastLock("$packageName:phone-bridge").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseBridgeLocks() {
        multicastLock?.takeIf { it.isHeld }?.release()
        multicastLock = null
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    companion object {
        private const val CHANNEL_ID = "phone_bridge"
        private const val NOTIFICATION_ID = 4401
    }
}
