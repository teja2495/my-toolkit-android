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
    val trustedPeers: List<TrustedPhonePeer> = emptyList()
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
