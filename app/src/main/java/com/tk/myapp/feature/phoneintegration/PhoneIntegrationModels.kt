package com.tk.myapp.feature.phoneintegration

data class TrustedPhonePeer(
    val id: String,
    val name: String,
    val publicKeyBase64: String,
    val pairedAtMillis: Long,
    val lastSeenMillis: Long
)

data class PendingPairingRequest(
    val requestId: String,
    val peerId: String,
    val peerName: String,
    val verificationCode: String,
    val remoteAddress: String
)

enum class PhoneBridgeConnectionState {
    Stopped,
    Starting,
    Listening,
    Pairing,
    Connected,
    Error
}

data class PhoneBridgeUiState(
    val deviceName: String,
    val deviceId: String,
    val connectionState: PhoneBridgeConnectionState = PhoneBridgeConnectionState.Stopped,
    val statusMessage: String = "Phone bridge is off",
    val connectedPeerName: String? = null,
    val pendingPairing: PendingPairingRequest? = null,
    val trustedPeers: List<TrustedPhonePeer> = emptyList(),
    val isLoadingMacFolder: Boolean = false,
    val macFolderStatusMessage: String = "Connect Toolkit on your Mac to browse files.",
    val currentMacFolderCategory: MacRemoteFileCategory? = null,
    val currentMacFolderTitle: String = "",
    val currentMacFolderDocumentUri: String? = null,
    val currentMacFolderEntries: List<MacRemoteFileItem> = emptyList()
)

enum class ShareHandlingMode {
    Foreground,
    Background
}

enum class PhoneFileCategory(val protocolValue: String) {
    PhotosVideos("photos_videos"),
    Documents("documents"),
    Music("music"),
    Other("other")
}

data class PhoneFileMetadata(
    val id: String,
    val displayName: String,
    val documentUri: String,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
    val mimeType: String,
    val thumbnailBase64: String?
)

enum class MacRemoteFileCategory(val protocolValue: String, val title: String) {
    Desktop("mac_desktop", "Desktop"),
    Downloads("mac_downloads", "Downloads");

    companion object {
        val allCases: List<MacRemoteFileCategory> = listOf(Desktop, Downloads)

        fun fromProtocolValue(value: String): MacRemoteFileCategory? =
            allCases.firstOrNull { it.protocolValue == value }
    }
}

data class MacRemoteFileItem(
    val id: String,
    val filename: String,
    val documentUri: String,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
    val mimeType: String,
    val category: MacRemoteFileCategory,
    val isDirectory: Boolean,
    val thumbnailBase64: String? = null
)
