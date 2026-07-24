package com.tk.myapp.feature.phoneintegration

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.IBinder
import android.os.PowerManager
import android.net.wifi.WifiManager
import androidx.core.app.NotificationCompat
import com.tk.myapp.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class PhoneIntegrationService : Service() {
    private lateinit var controller: PhoneIntegrationController
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var eligibilityJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private lateinit var connectivityManager: ConnectivityManager
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = updateBridgeForNetwork()

        override fun onLost(network: Network) = updateBridgeForNetwork()

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
            updateBridgeForNetwork()
    }

    override fun onCreate() {
        super.onCreate()
        controller = PhoneIntegrationController.getInstance(this)
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
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
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
        eligibilityJob = serviceScope.launch {
            controller.uiState
                .map { state ->
                    Triple(
                        state.currentWifiNetwork,
                        state.trustedNetworks,
                        state.trustedPeers.map { peer -> peer.id to peer.publicKeyBase64 }
                    )
                }
                .distinctUntilChanged()
                .collectLatest { updateBridgeForNetwork() }
        }
        updateBridgeForNetwork()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateBridgeForNetwork()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        eligibilityJob?.cancel()
        eligibilityJob = null
        serviceScope.cancel()
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        controller.stop()
        releaseBridgeLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateBridgeForNetwork() {
        controller.refreshNetworkState()
        val isWifiTrusted = controller.isCurrentWifiTrusted()
        val hasTrustedDevice = controller.hasTrustedDevice()
        if (isWifiTrusted && hasTrustedDevice) {
            controller.start()
        } else {
            android.util.Log.d(
                "PhoneIntegration",
                "Bridge waiting: trustedWifi=$isWifiTrusted trustedDevice=$hasTrustedDevice"
            )
            if (controller.uiState.value.connectionState != PhoneBridgeConnectionState.Stopped) {
                controller.stop()
            }
        }
    }

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
