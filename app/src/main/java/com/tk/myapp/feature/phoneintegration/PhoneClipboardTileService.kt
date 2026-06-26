package com.tk.myapp.feature.phoneintegration

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.tk.myapp.R

class PhoneClipboardTileService : TileService() {
    private val controller: PhoneIntegrationController
        get() = PhoneIntegrationController.getInstance(applicationContext)

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, PhoneClipboardSendActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val uiState = controller.uiState.value
        tile.label = getString(R.string.phone_clipboard_tile_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when (uiState.connectionState) {
                PhoneBridgeConnectionState.Connected -> "Mac"
                PhoneBridgeConnectionState.Error -> "Error"
                else -> "Tap to send"
            }
        }
        tile.state = when (uiState.connectionState) {
            PhoneBridgeConnectionState.Connected -> Tile.STATE_ACTIVE
            PhoneBridgeConnectionState.Error -> Tile.STATE_UNAVAILABLE
            else -> Tile.STATE_INACTIVE
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_clipboard_send)
        tile.updateTile()
    }
}
