package com.tk.myapp.feature.phoneintegration

import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

class PhoneIntegrationController private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = PhoneIntegrationStore(appContext)
    private val crypto = PhoneBridgeCrypto()
    private val fileRepository = PhoneFileRepository(appContext)
    private val clipboardManager = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val deviceId = store.getDeviceId()
    private val deviceName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
    private val pendingConnections = ConcurrentHashMap<String, PendingConnection>()
    private val activeConnections = ConcurrentHashMap<String, PendingConnection>()
    private val queuedOutgoingShares = mutableListOf<QueuedOutgoingShare>()
    private val activeOutgoingShareRequestIds = mutableSetOf<String>()
    private val backgroundOutgoingShareRequestIds = ConcurrentHashMap<String, String>()
    private val activeIncomingShareTransfers = ConcurrentHashMap<String, IncomingShareTransfer>()
    private val activeMacFileDownloads = ConcurrentHashMap<String, ActiveMacFileDownload>()
    private val activeOutgoingClipboardRequestIds = mutableSetOf<String>()
    private val outgoingClipboardToastRequests = ConcurrentHashMap<String, Boolean>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val macFolderCache = ConcurrentHashMap<String, List<MacRemoteFileItem>>()

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    private val _uiState = MutableStateFlow(
        PhoneBridgeUiState(
            deviceName = deviceName,
            deviceId = deviceId,
            trustedPeers = store.getTrustedPeers(),
            currentWifiNetwork = currentWifiNetworkName(),
            trustedNetworks = store.getTrustedNetworks()
        )
    )
    val uiState: StateFlow<PhoneBridgeUiState> = _uiState

    fun start() {
        if (serverJob?.isActive == true) return
        if (!isCurrentWifiTrusted()) {
            refreshNetworkState()
            _uiState.update {
                it.copy(
                    connectionState = PhoneBridgeConnectionState.Stopped,
                    statusMessage = "Connect to a trusted Wi-Fi network to start the bridge",
                    connectedPeerName = null
                )
            }
            return
        }
        Log.d(TAG, "Starting phone bridge deviceId=$deviceId deviceName=$deviceName")
        _uiState.update {
            it.copy(
                connectionState = PhoneBridgeConnectionState.Starting,
                statusMessage = "Starting secure phone bridge",
                connectedPeerName = null
            )
        }

        serverJob = scope.launch {
            runCatching {
                val socket = ServerSocket(0)
                serverSocket = socket
                Log.d(TAG, "Phone bridge server listening on port=${socket.localPort}")
                registerService(socket.localPort)
                _uiState.update {
                    it.copy(
                        connectionState = PhoneBridgeConnectionState.Listening,
                        statusMessage = "Discoverable on this Wi-Fi network",
                        connectedPeerName = null,
                        trustedPeers = store.getTrustedPeers()
                    )
                }
                while (!socket.isClosed) {
                    val client = socket.accept()
                    Log.d(TAG, "Accepted client from=${client.inetAddress.hostAddress}")
                    launch { handleClient(client) }
                }
            }.onFailure { error ->
                Log.e(TAG, "Phone bridge start failed", error)
                _uiState.update {
                    it.copy(
                        connectionState = PhoneBridgeConnectionState.Error,
                        statusMessage = error.message ?: "Phone bridge failed",
                        connectedPeerName = null
                    )
                }
            }
        }
    }

    fun stop() {
        registrationListener?.let { listener ->
            runCatching { nsdManager.unregisterService(listener) }
        }
        registrationListener = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        serverJob?.cancel()
        serverJob = null
        pendingConnections.values.forEach { it.close() }
        activeConnections.values.forEach { it.close() }
        pendingConnections.clear()
        activeConnections.clear()
        queuedOutgoingShares.clear()
        activeOutgoingShareRequestIds.clear()
        activeOutgoingClipboardRequestIds.clear()
        outgoingClipboardToastRequests.clear()
        activeIncomingShareTransfers.values.forEach { transfer ->
            runCatching { transfer.close() }
            transfer.tempFile.delete()
        }
        activeIncomingShareTransfers.clear()
        activeMacFileDownloads.values.forEach { transfer ->
            runCatching { transfer.output.close() }
            transfer.tempFile.delete()
        }
        activeMacFileDownloads.clear()
        macFolderCache.clear()
        _uiState.update {
            it.copy(
                connectionState = PhoneBridgeConnectionState.Stopped,
                statusMessage = "Phone bridge is off",
                connectedPeerName = null,
                pendingPairing = null,
                isLoadingMacFolder = false,
                macFolderStatusMessage = "Connect Toolkit on your Mac to browse files.",
                currentMacFolderCategory = null,
                currentMacFolderTitle = "",
                currentMacFolderDocumentUri = null,
                currentMacFolderEntries = emptyList()
            )
        }
    }

    fun approvePendingPairing(requestId: String) {
        val pending = pendingConnections[requestId] ?: return
        scope.launch {
            runCatching {
                pending.sendEncrypted(
                    JSONObject()
                        .put("type", PhoneBridgeProtocol.typePairDecision)
                        .put("approved", true)
                )
                pending.localDecision.complete(true)
                _uiState.update {
                    it.copy(
                        connectionState = PhoneBridgeConnectionState.Pairing,
                        statusMessage = "Waiting for ${pending.peerName} to approve",
                        connectedPeerName = null,
                        pendingPairing = null
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        connectionState = PhoneBridgeConnectionState.Error,
                        statusMessage = error.message ?: "Pairing approval failed",
                        connectedPeerName = null,
                        pendingPairing = null
                    )
                }
            }
        }
    }

    fun rejectPendingPairing(requestId: String) {
        val pending = pendingConnections.remove(requestId) ?: return
        pending.localDecision.complete(false)
        scope.launch {
            runCatching {
                pending.sendEncrypted(
                    JSONObject()
                        .put("type", PhoneBridgeProtocol.typePairDecision)
                        .put("approved", false)
                )
            }
            pending.close()
            _uiState.update {
                it.copy(
                    connectionState = PhoneBridgeConnectionState.Listening,
                    statusMessage = "Pairing rejected",
                    connectedPeerName = null,
                    pendingPairing = null
                )
            }
        }
    }

    fun removeTrustedPeer(peerId: String) {
        store.removeTrustedPeer(peerId)
        _uiState.update {
            it.copy(
                trustedPeers = store.getTrustedPeers(),
                statusMessage = "Removed paired device"
            )
        }
    }

    fun trustCurrentWifiNetwork(): Boolean {
        val networkName = currentWifiNetworkName() ?: return false
        store.saveTrustedNetwork(networkName)
        refreshNetworkState()
        return true
    }

    fun removeTrustedNetwork(networkName: String) {
        store.removeTrustedNetwork(networkName)
        refreshNetworkState()
    }

    fun isCurrentWifiTrusted(): Boolean {
        val networkName = currentWifiNetworkName() ?: return false
        return networkName in store.getTrustedNetworks()
    }

    fun refreshNetworkState() {
        _uiState.update {
            it.copy(
                currentWifiNetwork = currentWifiNetworkName(),
                trustedNetworks = store.getTrustedNetworks()
            )
        }
    }

    fun openMacFolder(
        category: MacRemoteFileCategory,
        documentUri: String? = null,
        title: String = category.title,
        forceRefresh: Boolean = false
    ) {
        val connection = activeConnections.values.firstOrNull()
        if (connection == null) {
            _uiState.update {
                it.copy(
                    isLoadingMacFolder = false,
                    macFolderStatusMessage = "Connect Toolkit on your Mac to browse files.",
                    currentMacFolderCategory = null,
                    currentMacFolderTitle = "",
                    currentMacFolderDocumentUri = null,
                    currentMacFolderEntries = emptyList()
                )
            }
            return
        }

        val cacheKey = macFolderCacheKey(category, documentUri)
        val cachedEntries = if (forceRefresh) null else macFolderCache[cacheKey]
        if (cachedEntries != null) {
            _uiState.update {
                it.copy(
                    isLoadingMacFolder = false,
                    macFolderStatusMessage = if (cachedEntries.isEmpty()) "No files found in $title." else "",
                    currentMacFolderCategory = category,
                    currentMacFolderTitle = title,
                    currentMacFolderDocumentUri = documentUri,
                    currentMacFolderEntries = cachedEntries
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                isLoadingMacFolder = true,
                macFolderStatusMessage = "Loading $title from ${connection.peerName}...",
                currentMacFolderCategory = category,
                currentMacFolderTitle = title,
                currentMacFolderDocumentUri = documentUri,
                currentMacFolderEntries = emptyList()
            )
        }
        scope.launch {
            runCatching {
                connection.sendEncrypted(
                    JSONObject()
                        .put("type", PhoneBridgeProtocol.typeListFiles)
                        .put("category", category.protocolValue)
                        .put("pageSize", 200)
                        .put("pageToken", 0)
                        .apply {
                            if (documentUri != null) {
                                put("documentUri", documentUri)
                            }
                        }
                )
            }.onFailure { error ->
                Log.e(TAG, "Failed to request Mac folder category=${category.protocolValue}", error)
                _uiState.update {
                    it.copy(
                        isLoadingMacFolder = false,
                        macFolderStatusMessage = error.message ?: "Toolkit could not load files from your Mac."
                    )
                }
            }
        }
    }

    fun refreshMacFiles() {
        val state = _uiState.value
        val category = state.currentMacFolderCategory
        if (category != null) {
            openMacFolder(
                category = category,
                documentUri = state.currentMacFolderDocumentUri,
                title = state.currentMacFolderTitle.ifBlank { category.title },
                forceRefresh = true
            )
        }
    }

    /** Invalidates cached Mac folder listings; call when the app returns to the foreground. */
    fun onAppForegrounded() {
        refreshNetworkState()
        macFolderCache.clear()
        if (_uiState.value.currentMacFolderCategory != null) {
            refreshMacFiles()
        }
    }

    private fun currentWifiNetworkName(): String? {
        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ssid = wifiManager.connectionInfo?.ssid
            ?.removePrefix("\"")
            ?.removeSuffix("\"")
            ?.trim()
        return ssid?.takeUnless { it.isNullOrEmpty() || it == WifiManager.UNKNOWN_SSID }
    }

    private fun macFolderCacheKey(category: MacRemoteFileCategory, documentUri: String?): String =
        "${category.protocolValue}|${documentUri.orEmpty()}"

    fun openMacFile(file: MacRemoteFileItem) {
        val connection = activeConnections.values.firstOrNull()
        if (connection == null) {
            _uiState.update {
                it.copy(statusMessage = "Connect Toolkit on your Mac before opening files.")
            }
            return
        }

        val requestId = "${connection.peerId}-${System.currentTimeMillis()}-${file.filename.hashCode()}"
        val tempFile = fileRepository.createIncomingShareTempFile(requestId)
        val output = FileOutputStream(tempFile)
        activeMacFileDownloads[requestId] = ActiveMacFileDownload(
            file = file,
            tempFile = tempFile,
            output = output
        )
        _uiState.update {
            it.copy(statusMessage = "Opening ${file.filename} from ${connection.peerName}")
        }
        scope.launch {
            runCatching {
                connection.sendEncrypted(
                    JSONObject()
                        .put("type", PhoneBridgeProtocol.typeReadFile)
                        .put("requestId", requestId)
                        .put("documentUri", file.documentUri)
                )
            }.onFailure { error ->
                Log.e(TAG, "Failed to request Mac file download requestId=$requestId", error)
                finishMacFileDownload(
                    requestId,
                    error.message ?: "Toolkit could not download the Mac file."
                )
            }
        }
    }

    fun clearMacFolderSelection() {
        _uiState.update {
            it.copy(
                isLoadingMacFolder = false,
                macFolderStatusMessage = "",
                currentMacFolderCategory = null,
                currentMacFolderTitle = "",
                currentMacFolderDocumentUri = null,
                currentMacFolderEntries = emptyList()
            )
        }
    }

    fun sendCurrentClipboardToMac(showToast: Boolean = false) {
        val connection = activeConnections.values.firstOrNull()
        if (connection == null) {
            val message = "Connect Toolkit on your Mac before sending clipboard."
            if (showToast) {
                showToast(message)
            }
            _uiState.update {
                it.copy(statusMessage = message)
            }
            return
        }

        val clipboardText = clipboardManager.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(appContext)
            ?.toString()

        if (clipboardText.isNullOrEmpty()) {
            val message = "Copy text on Android before sending clipboard."
            if (showToast) {
                showToast(message)
            }
            _uiState.update {
                it.copy(statusMessage = message)
            }
            return
        }

        scope.launch {
            val requestId = "${connection.peerId}-${System.currentTimeMillis()}-clipboard"
            runCatching {
                activeOutgoingClipboardRequestIds += requestId
                outgoingClipboardToastRequests[requestId] = showToast
                connection.sendEncrypted(
                    JSONObject()
                        .put("type", PhoneBridgeProtocol.typeSetClipboard)
                        .put("requestId", requestId)
                        .put("text", clipboardText)
                )
                val message = "Sending clipboard to ${connection.peerName}..."
                if (showToast) {
                    showToast(message)
                }
                _uiState.update {
                    it.copy(statusMessage = message)
                }
                launch {
                    delay(3000)
                    if (activeOutgoingClipboardRequestIds.remove(requestId)) {
                        outgoingClipboardToastRequests.remove(requestId)
                        val timeoutMessage = "Mac did not confirm the clipboard update."
                        if (showToast) {
                            showToast(timeoutMessage)
                        }
                        _uiState.update {
                            it.copy(statusMessage = timeoutMessage)
                        }
                    }
                }
            }.onFailure { error ->
                activeOutgoingClipboardRequestIds.remove(requestId)
                outgoingClipboardToastRequests.remove(requestId)
                Log.e(TAG, "Failed to send clipboard to peerId=${connection.peerId}", error)
                val message = error.message ?: "Toolkit could not send the clipboard."
                if (showToast) {
                    showToast(message)
                }
                _uiState.update {
                    it.copy(statusMessage = message)
                }
            }
        }
    }

    fun handleShareIntent(
        intent: Intent,
        mode: ShareHandlingMode = ShareHandlingMode.Foreground
    ) {
        val uris = extractShareUris(intent)
        if (uris.isEmpty()) {
            notifyShareFailure("No file was attached to share to Toolkit.", mode)
            _uiState.update {
                it.copy(statusMessage = "No file was attached to share to Toolkit.")
            }
            return
        }
        val preparedFiles = uris.mapNotNull { uri -> fileRepository.prepareSharedFile(uri) }
        if (preparedFiles.isEmpty()) {
            notifyShareFailure("Toolkit could not open the shared file.", mode)
            _uiState.update {
                it.copy(statusMessage = "Toolkit could not open the shared file.")
            }
            return
        }
        if (mode == ShareHandlingMode.Background && activeConnections.isEmpty()) {
            val message = "Connect Toolkit on your Mac before sharing files."
            notifyShareFailure(message, mode)
            _uiState.update {
                it.copy(statusMessage = message)
            }
            return
        }
        synchronized(queuedOutgoingShares) {
            queuedOutgoingShares += preparedFiles.map { file ->
                QueuedOutgoingShare(
                    file = file,
                    mode = mode
                )
            }
        }
        _uiState.update {
            it.copy(
                statusMessage = if (preparedFiles.size == 1) {
                    "Waiting to send ${preparedFiles.first().filename} to your Mac"
                } else {
                    "Waiting to send ${preparedFiles.size} files to your Mac"
                }
            )
        }
        flushQueuedOutgoingShares()
    }

    private suspend fun handleClient(socket: Socket) {
        socket.tcpNoDelay = true
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        var activePeerId: String? = null
        runCatching {
            val hello = JSONObject(readFrame(input).decodeToString())
            require(hello.optString("type") == PhoneBridgeProtocol.typePairHello)
            require(hello.optInt("protocolVersion") == PhoneBridgeProtocol.version)

            val localKeyPair = crypto.getOrCreateIdentityKeyPair()
            val localPublicKey = crypto.publicKeyToBase64(localKeyPair.public)
            val remotePublicKeyBase64 = hello.getString("publicKey")
            val remotePublicKey = crypto.publicKeyFromBase64(remotePublicKeyBase64)
            val salt = crypto.randomBytes(32)
            val sessionKey = crypto.deriveSessionKey(localKeyPair, remotePublicKey, salt)
            val peerId = hello.getString("deviceId")
            val peerName = hello.optString("deviceName", "Mac")
            val code = crypto.verificationCode(localPublicKey, remotePublicKeyBase64)
            val requestId = "${peerId}-${System.currentTimeMillis()}"
            val isTrustedPeer = store.getTrustedPeers().any {
                it.id == peerId && it.publicKeyBase64 == remotePublicKeyBase64
            }
            Log.d(
                TAG,
                "Received pair hello peerId=$peerId peerName=$peerName remote=${socket.inetAddress.hostAddress}"
            )

            writeFrame(
                output,
                JSONObject()
                    .put("type", PhoneBridgeProtocol.typePairChallenge)
                    .put("protocolVersion", PhoneBridgeProtocol.version)
                    .put("deviceId", deviceId)
                    .put("deviceName", deviceName)
                    .put("publicKey", localPublicKey)
                    .put("salt", android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP))
                    .toString()
                    .toByteArray()
            )

            val pending = PendingConnection(
                socket = socket,
                input = input,
                output = output,
                sessionKey = sessionKey,
                peerId = peerId,
                peerName = peerName,
                remotePublicKeyBase64 = remotePublicKeyBase64
            )
            pendingConnections[requestId] = pending
            if (isTrustedPeer) {
                Log.d(TAG, "Auto-approving trusted peerId=$peerId peerName=$peerName")
                pending.localDecision.complete(true)
                _uiState.update {
                    it.copy(
                        connectionState = PhoneBridgeConnectionState.Pairing,
                        statusMessage = "Waiting for $peerName to approve",
                        connectedPeerName = null,
                        pendingPairing = null
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        connectionState = PhoneBridgeConnectionState.Pairing,
                        statusMessage = "Confirm pairing code on both devices",
                        connectedPeerName = null,
                        pendingPairing = PendingPairingRequest(
                            requestId = requestId,
                            peerId = peerId,
                            peerName = peerName,
                            verificationCode = code,
                            remoteAddress = socket.inetAddress.hostAddress ?: "Local network"
                        )
                    )
                }
            }

            val remoteDecision = pending.readEncrypted()
            Log.d(
                TAG,
                "Received pair decision peerId=$peerId approved=${remoteDecision.optBoolean("approved", false)}"
            )
            if (remoteDecision.optString("type") != PhoneBridgeProtocol.typePairDecision ||
                !remoteDecision.optBoolean("approved", false)
            ) {
                pendingConnections.remove(requestId)
                pending.close()
                _uiState.update {
                    it.copy(
                        connectionState = PhoneBridgeConnectionState.Listening,
                        statusMessage = "$peerName rejected pairing",
                        connectedPeerName = null,
                        pendingPairing = null
                    )
                }
                return@runCatching
            }

            val localApproved = pending.localDecision.await()
            if (!localApproved) {
                pendingConnections.remove(requestId)
                pending.close()
                return@runCatching
            }

            val now = System.currentTimeMillis()
            store.saveTrustedPeer(
                TrustedPhonePeer(
                    id = pending.peerId,
                    name = pending.peerName,
                    publicKeyBase64 = pending.remotePublicKeyBase64,
                    pairedAtMillis = now,
                    lastSeenMillis = now
                )
            )
            pending.sendEncrypted(
                JSONObject()
                    .put("type", PhoneBridgeProtocol.typePairComplete)
                    .put("trusted", true)
            )
            Log.d(TAG, "Pairing completed peerId=${pending.peerId} peerName=${pending.peerName}")
            pendingConnections.remove(requestId)
            activeConnections[pending.peerId] = pending
            activePeerId = pending.peerId
            _uiState.update {
                it.copy(
                    connectionState = PhoneBridgeConnectionState.Connected,
                    statusMessage = "Paired with ${pending.peerName}",
                    connectedPeerName = pending.peerName,
                    pendingPairing = null,
                    trustedPeers = store.getTrustedPeers()
                )
            }
            flushQueuedOutgoingShares()

            while (!socket.isClosed) {
                val message = pending.readEncrypted()
                val type = message.optString("type")
                Log.d(TAG, "Received encrypted message type=$type peerId=${pending.peerId}")
                when (type) {
                    PhoneBridgeProtocol.typeListFiles -> handleListFiles(message, pending)
                    PhoneBridgeProtocol.typeListFilesResult -> handleMacListFilesResult(message)
                    PhoneBridgeProtocol.typeReadFile -> handleReadFile(message, pending)
                    PhoneBridgeProtocol.typeReadFileResult -> handleMacReadFileResult(message)
                    PhoneBridgeProtocol.typeShareFileChunk -> handleIncomingShareChunk(message, pending)
                    PhoneBridgeProtocol.typeShareFileResult -> handleOutgoingShareResult(message, pending)
                    PhoneBridgeProtocol.typeSetClipboard -> handleSetClipboard(message, pending)
                    PhoneBridgeProtocol.typeSetClipboardResult -> handleSetClipboardResult(message, pending)
                    PhoneBridgeProtocol.typeError -> handleShareError(message)
                }
            }
        }.onFailure { error ->
            Log.e(
                TAG,
                "Client connection failed remote=${socket.inetAddress.hostAddress}",
                error
            )
            if (activePeerId != null) {
                activeConnections.remove(activePeerId)
            }
            activeMacFileDownloads.values.forEach { transfer ->
                runCatching { transfer.output.close() }
                transfer.tempFile.delete()
            }
            activeMacFileDownloads.clear()
            withContext(Dispatchers.Main.immediate) {
                _uiState.update {
                    it.copy(
                        connectionState = PhoneBridgeConnectionState.Listening,
                        statusMessage = error.message ?: "Connection closed",
                        connectedPeerName = null,
                        pendingPairing = null,
                        trustedPeers = store.getTrustedPeers(),
                        isLoadingMacFolder = false,
                        macFolderStatusMessage = "Connect Toolkit on your Mac to browse files.",
                        currentMacFolderCategory = null,
                        currentMacFolderTitle = "",
                        currentMacFolderDocumentUri = null,
                        currentMacFolderEntries = emptyList()
                    )
                }
            }
            runCatching { socket.close() }
        }
    }

    private fun handleListFiles(message: JSONObject, pending: PendingConnection) {
        Log.d(
            TAG,
            "Handling files.list peerId=${pending.peerId} category=${message.optString("category")} pageSize=${message.optInt("pageSize", 50)} pageToken=${message.optInt("pageToken", 0)}"
        )
        if (!fileRepository.hasRequiredPermissions()) {
            Log.w(TAG, "Rejecting files.list due to missing Android file permissions")
            pending.sendEncrypted(
                JSONObject()
                    .put("type", PhoneBridgeProtocol.typeError)
                    .put("message", "Grant file access on Android to browse files from your Mac.")
            )
            return
        }
        val category = PhoneFileCategory.values().firstOrNull {
            it.protocolValue == message.optString("category")
        } ?: PhoneFileCategory.Other
        val pageSize = message.optInt("pageSize", 50)
        val pageToken = message.optInt("pageToken", 0)
        val files = fileRepository.listFiles(category, pageSize, pageToken)
        Log.d(
            TAG,
            "Sending files.list.result peerId=${pending.peerId} category=${category.protocolValue} count=${files.size}"
        )
        val array = org.json.JSONArray()
        files.forEach { file ->
            array.put(JSONObject().apply {
                put("id", file.id)
                put("filename", file.displayName)
                put("documentUri", file.documentUri)
                put("size", file.sizeBytes)
                put("modifiedDate", file.modifiedAtMillis)
                put("mimeType", file.mimeType)
                put("thumbnail", file.thumbnailBase64)
            })
        }
        pending.sendEncrypted(
            JSONObject()
                .put("type", PhoneBridgeProtocol.typeListFilesResult)
                .put("category", category.protocolValue)
                .put("files", array)
                .put("nextPageToken", pageToken + files.size)
        )
    }

    private fun handleReadFile(message: JSONObject, pending: PendingConnection) {
        val requestId = message.optString("requestId")
        val documentUri = message.optString("documentUri")
        if (requestId.isBlank() || documentUri.isBlank()) {
            pending.sendEncrypted(
                JSONObject()
                    .put("type", PhoneBridgeProtocol.typeError)
                    .put("requestId", requestId)
                    .put("message", "Missing file request details.")
            )
            return
        }

        runCatching {
            fileRepository.openFileStream(documentUri)?.use { input ->
                val chunks = mutableListOf<String>()
                val buffer = ByteArray(FILE_CHUNK_SIZE_BYTES)
                while (true) {
                    val count = input.read(buffer)
                    if (count <= 0) break
                    val chunk = if (count == buffer.size) {
                        buffer.copyOf()
                    } else {
                        buffer.copyOf(count)
                    }
                    chunks += android.util.Base64.encodeToString(chunk, android.util.Base64.NO_WRAP)
                }
                require(chunks.isNotEmpty()) { "File is empty or unavailable." }
                chunks
            } ?: error("Unable to read file from Android storage.")
        }.onSuccess { chunks ->
            chunks.forEachIndexed { index, chunk ->
                pending.sendEncrypted(
                    JSONObject()
                        .put("type", PhoneBridgeProtocol.typeReadFileResult)
                        .put("requestId", requestId)
                        .put("chunkIndex", index)
                        .put("totalChunks", chunks.size)
                        .put("data", chunk)
                )
            }
        }.onFailure { error ->
            Log.e(TAG, "Failed to stream file requestId=$requestId uri=$documentUri", error)
            pending.sendEncrypted(
                JSONObject()
                    .put("type", PhoneBridgeProtocol.typeError)
                    .put("requestId", requestId)
                    .put("message", error.message ?: "Unable to open file from Android.")
            )
        }
    }

    private fun handleMacListFilesResult(message: JSONObject) {
        val category = MacRemoteFileCategory.fromProtocolValue(message.optString("category")) ?: return
        val files = message.optJSONArray("files")
        val parsedFiles = buildList {
            if (files == null) return@buildList
            for (index in 0 until files.length()) {
                val item = files.optJSONObject(index) ?: continue
                add(
                    MacRemoteFileItem(
                        id = item.optString("id"),
                        filename = item.optString("filename"),
                        documentUri = item.optString("documentUri"),
                        sizeBytes = item.optLong("size"),
                        modifiedAtMillis = item.optLong("modifiedDate"),
                        mimeType = item.optString("mimeType").ifBlank { "application/octet-stream" },
                        category = category,
                        isDirectory = item.optBoolean("isDirectory", false),
                        thumbnailBase64 = item.optString("thumbnail").ifBlank { null }
                    )
                )
            }
        }
        macFolderCache[macFolderCacheKey(category, _uiState.value.currentMacFolderDocumentUri)] = parsedFiles
        _uiState.update { state ->
            state.copy(
                isLoadingMacFolder = false,
                macFolderStatusMessage = if (parsedFiles.isEmpty()) {
                    "No files found in ${state.currentMacFolderTitle.ifBlank { category.title }}."
                } else {
                    ""
                },
                currentMacFolderCategory = category,
                currentMacFolderEntries = parsedFiles
            )
        }
    }

    private fun handleMacReadFileResult(message: JSONObject) {
        val requestId = message.optString("requestId")
        val transfer = activeMacFileDownloads[requestId] ?: return
        val chunkIndex = message.optInt("chunkIndex", -1)
        val totalChunks = message.optInt("totalChunks", -1)
        val data = message.optString("data")
        if (chunkIndex != transfer.nextChunkIndex || totalChunks <= 0) {
            finishMacFileDownload(requestId, "Toolkit received an invalid Mac file response.")
            return
        }

        runCatching {
            val decoded = android.util.Base64.decode(data, android.util.Base64.DEFAULT)
            transfer.output.write(decoded)
            transfer.nextChunkIndex += 1
            if (transfer.nextChunkIndex == totalChunks) {
                transfer.output.close()
                val savedUri = fileRepository.saveDownloadedMacFile(
                    filename = transfer.file.filename,
                    mimeType = transfer.file.mimeType,
                    sourceFile = transfer.tempFile
                )
                transfer.tempFile.delete()
                activeMacFileDownloads.remove(requestId)
                _uiState.update {
                    it.copy(statusMessage = "Opened ${transfer.file.filename} from Android Downloads")
                }
                Log.d(TAG, "Saved Mac file requestId=$requestId uri=$savedUri")
                openSavedMacFile(savedUri, transfer.file.mimeType)
            }
        }.onFailure { error ->
            finishMacFileDownload(requestId, error.message ?: "Toolkit could not save the Mac file.")
        }
    }

    private fun handleIncomingShareChunk(message: JSONObject, pending: PendingConnection) {
        val requestId = message.optString("requestId")
        val filename = message.optString("filename")
        val mimeType = message.optString("mimeType")
        val chunkIndex = message.optInt("chunkIndex", -1)
        val isLastChunk = message.optBoolean("isLastChunk", false)
        val data = message.optString("data")
        if (requestId.isBlank() || filename.isBlank() || mimeType.isBlank() || chunkIndex < 0 || (!isLastChunk && data.isEmpty())) {
            pending.sendEncrypted(
                JSONObject()
                    .put("type", PhoneBridgeProtocol.typeError)
                    .put("requestId", requestId)
                    .put("message", "Toolkit received an invalid shared file payload.")
            )
            return
        }

        runCatching {
            val chunkBytes = android.util.Base64.decode(data, android.util.Base64.DEFAULT)
            val transfer = activeIncomingShareTransfers[requestId] ?: IncomingShareTransfer(
                requestId = requestId,
                filename = filename,
                mimeType = mimeType,
                tempFile = fileRepository.createIncomingShareTempFile(requestId)
            ).also {
                activeIncomingShareTransfers[requestId] = it
            }
            require(transfer.nextChunkIndex == chunkIndex) { "Toolkit received an invalid shared file chunk order." }
            transfer.append(chunkBytes)
            transfer.nextChunkIndex += 1
            if (isLastChunk) {
                transfer.close()
                val savedUri = fileRepository.saveIncomingSharedFile(filename, mimeType, transfer.tempFile)
                activeIncomingShareTransfers.remove(requestId)
                transfer.tempFile.delete()
                pending.sendEncrypted(
                    JSONObject()
                        .put("type", PhoneBridgeProtocol.typeShareFileResult)
                        .put("requestId", requestId)
                        .put("success", true)
                        .put("savedFilename", filename)
                )
                _uiState.update {
                    it.copy(statusMessage = "Saved $filename to Android Downloads")
                }
                Log.d(TAG, "Saved incoming shared file requestId=$requestId uri=$savedUri")
            }
        }.onFailure { error ->
            Log.e(TAG, "Failed to receive shared file requestId=$requestId", error)
            activeIncomingShareTransfers.remove(requestId)?.let { transfer ->
                runCatching { transfer.close() }
                transfer.tempFile.delete()
            }
            pending.sendEncrypted(
                JSONObject()
                    .put("type", PhoneBridgeProtocol.typeError)
                    .put("requestId", requestId)
                    .put("message", error.message ?: "Toolkit could not save the shared file.")
            )
        }
    }

    private fun handleSetClipboard(message: JSONObject, pending: PendingConnection) {
        val requestId = message.optString("requestId")
        val text = message.optString("text")
        if (text.isEmpty()) {
            scope.launch {
                pending.sendEncrypted(
                    JSONObject()
                        .put("type", PhoneBridgeProtocol.typeError)
                        .put("message", "Toolkit received an invalid clipboard payload.")
                )
            }
            return
        }

        mainHandler.post {
            clipboardManager.setPrimaryClip(ClipData.newPlainText("Toolkit Mac Clipboard", text))
            _uiState.update {
                it.copy(statusMessage = "Updated Android clipboard from ${pending.peerName}")
            }
            scope.launch {
                pending.sendEncrypted(
                    JSONObject()
                        .put("type", PhoneBridgeProtocol.typeSetClipboardResult)
                        .put("requestId", requestId)
                        .put("success", true)
                        .put("message", "Updated Android clipboard.")
                )
            }
        }
        Log.d(TAG, "Updated Android clipboard from peerId=${pending.peerId}")
    }

    private fun handleSetClipboardResult(message: JSONObject, pending: PendingConnection) {
        val requestId = message.optString("requestId")
        if (requestId.isBlank() || !activeOutgoingClipboardRequestIds.remove(requestId)) {
            return
        }
        val shouldToast = outgoingClipboardToastRequests.remove(requestId) == true
        val success = message.optBoolean("success", false)
        val statusMessage = message.optString(
            "message",
            if (success) "Updated Mac clipboard." else "Toolkit could not update the Mac clipboard."
        )
        if (success) {
            if (shouldToast) {
                showToast("Sent Android clipboard to ${pending.peerName}")
            }
        } else {
            if (shouldToast) {
                showToast(statusMessage)
            }
        }
        _uiState.update {
            it.copy(statusMessage = statusMessage)
        }
    }

    private fun flushQueuedOutgoingShares() {
        val connection = activeConnections.values.firstOrNull()
        if (connection == null) {
            _uiState.update {
                if (queuedOutgoingShares.isNotEmpty()) {
                    it.copy(statusMessage = "Connect Toolkit on your Mac before sharing files.")
                } else {
                    it
                }
            }
            return
        }
        val pendingFiles = synchronized(queuedOutgoingShares) {
            if (queuedOutgoingShares.isEmpty()) return
            queuedOutgoingShares.toList().also { queuedOutgoingShares.clear() }
        }
        scope.launch {
            pendingFiles.forEach { queuedShare ->
                runCatching {
                    sendSharedFile(queuedShare, connection)
                }.onFailure { error ->
                    Log.e(TAG, "Failed to send shared file filename=${queuedShare.file.filename}", error)
                    notifyShareFailure(
                        error.message ?: "Toolkit could not send the shared file.",
                        queuedShare.mode
                    )
                    _uiState.update {
                        it.copy(statusMessage = error.message ?: "Toolkit could not send the shared file.")
                    }
                }
            }
        }
    }

    private fun sendSharedFile(queuedShare: QueuedOutgoingShare, pending: PendingConnection) {
        val file = queuedShare.file
        val requestId = "${pending.peerId}-${System.currentTimeMillis()}-${file.filename.hashCode()}"
        activeOutgoingShareRequestIds += requestId
        if (queuedShare.mode == ShareHandlingMode.Background) {
            backgroundOutgoingShareRequestIds[requestId] = file.filename
        }
        try {
            fileRepository.openSharedFileInputStream(file.uri)?.use { input ->
                val buffer = ByteArray(FILE_CHUNK_SIZE_BYTES)
                var chunkIndex = 0
                while (true) {
                    val count = input.read(buffer)
                    val isLastChunk = count < 0 || count < buffer.size
                    val payload = if (count > 0) {
                        buffer.copyOf(count)
                    } else {
                        ByteArray(0)
                    }
                    pending.sendEncrypted(
                        JSONObject()
                            .put("type", PhoneBridgeProtocol.typeShareFileChunk)
                            .put("requestId", requestId)
                            .put("filename", file.filename)
                            .put("mimeType", file.mimeType)
                            .put("chunkIndex", chunkIndex)
                            .put("isLastChunk", isLastChunk)
                            .put("data", android.util.Base64.encodeToString(payload, android.util.Base64.NO_WRAP))
                    )
                    if (isLastChunk) {
                        break
                    }
                    chunkIndex += 1
                }
            }
                ?: error("Toolkit could not open the shared file.")
        } catch (error: Throwable) {
            activeOutgoingShareRequestIds.remove(requestId)
            backgroundOutgoingShareRequestIds.remove(requestId)
            throw error
        }
        _uiState.update {
            it.copy(statusMessage = "Sending ${file.filename} to ${pending.peerName}")
        }
    }

    private fun handleOutgoingShareResult(message: JSONObject, pending: PendingConnection) {
        val requestId = message.optString("requestId")
        if (requestId.isBlank() || !activeOutgoingShareRequestIds.remove(requestId)) {
            return
        }
        if (message.optBoolean("success", false)) {
            val savedFilename = message.optString("savedFilename").ifBlank { "the file" }
            backgroundOutgoingShareRequestIds.remove(requestId)?.let {
                showToast("Sent $savedFilename to your Mac")
            }
            _uiState.update {
                it.copy(statusMessage = "Saved $savedFilename to Mac Downloads and copied it to the clipboard")
            }
        } else {
            backgroundOutgoingShareRequestIds.remove(requestId)?.let {
                showToast(message.optString("message", "Toolkit on Mac could not save the shared file."))
            }
            _uiState.update {
                it.copy(statusMessage = message.optString("message", "Toolkit on Mac could not save the shared file."))
            }
        }
        Log.d(TAG, "Completed outgoing share requestId=$requestId peerId=${pending.peerId}")
    }

    private fun handleShareError(message: JSONObject) {
        val requestId = message.optString("requestId")
        if (requestId.isNotBlank() && activeMacFileDownloads.containsKey(requestId)) {
            finishMacFileDownload(
                requestId,
                message.optString("message", "Toolkit could not download the Mac file.")
            )
            return
        }
        if (requestId.isNotBlank() && activeOutgoingShareRequestIds.remove(requestId)) {
            backgroundOutgoingShareRequestIds.remove(requestId)?.let {
                showToast(message.optString("message", "Toolkit could not complete the shared file transfer."))
            }
            _uiState.update {
                it.copy(statusMessage = message.optString("message", "Toolkit could not complete the shared file transfer."))
            }
        }
    }

    private fun finishMacFileDownload(requestId: String, message: String) {
        activeMacFileDownloads.remove(requestId)?.let { transfer ->
            runCatching { transfer.output.close() }
            transfer.tempFile.delete()
        }
        _uiState.update {
            it.copy(statusMessage = message)
        }
    }

    private fun notifyShareFailure(message: String, mode: ShareHandlingMode) {
        if (mode == ShareHandlingMode.Background) {
            showToast(message)
        }
    }

    private fun showToast(message: String) {
        mainHandler.post {
            Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun openSavedMacFile(uri: Uri, mimeType: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType.ifBlank { "*/*" })
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            appContext.startActivity(intent)
        }.onFailure {
            showToast("No app found to open this file.")
        }
    }

    private fun extractShareUris(intent: Intent): List<Uri> {
        return when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
            )
            Intent.ACTION_SEND_MULTIPLE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
                }
            }
            else -> emptyList()
        }
    }

    private fun registerService(port: Int) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "${PhoneBridgeProtocol.serviceNamePrefix} ${Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID).takeLast(4)}"
            serviceType = PhoneBridgeProtocol.serviceType
            this.port = port
            setAttribute("deviceId", deviceId)
            setAttribute("protocol", PhoneBridgeProtocol.version.toString())
            setAttribute("platform", "android")
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        registrationListener = listener
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun readFrame(input: BufferedInputStream): ByteArray {
        val header = input.readNBytesCompat(4)
        val size = ByteBuffer.wrap(header).int
        require(size in 1..MAX_FRAME_SIZE) { "Invalid bridge frame size" }
        return input.readNBytesCompat(size)
    }

    private fun writeFrame(output: BufferedOutputStream, payload: ByteArray) {
        require(payload.size <= MAX_FRAME_SIZE) { "Bridge frame is too large" }
        output.write(ByteBuffer.allocate(4).putInt(payload.size).array())
        output.write(payload)
        output.flush()
    }

    private fun BufferedInputStream.readNBytesCompat(size: Int): ByteArray {
        val buffer = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = read(buffer, offset, size - offset)
            if (count < 0) error("Connection closed")
            offset += count
        }
        return buffer
    }

    private inner class PendingConnection(
        val socket: Socket,
        val input: BufferedInputStream,
        val output: BufferedOutputStream,
        val sessionKey: ByteArray,
        val peerId: String,
        val peerName: String,
        val remotePublicKeyBase64: String,
        val localDecision: CompletableDeferred<Boolean> = CompletableDeferred()
    ) {
        fun readEncrypted(): JSONObject {
            val decrypted = crypto.decrypt(sessionKey, readFrame(input))
            return JSONObject(decrypted.decodeToString())
        }

        fun sendEncrypted(message: JSONObject) {
            writeFrame(output, crypto.encrypt(sessionKey, message.toString().toByteArray()))
        }

        fun close() {
            runCatching { socket.close() }
        }
    }

    private class ActiveMacFileDownload(
        val file: MacRemoteFileItem,
        val tempFile: File,
        val output: FileOutputStream,
        var nextChunkIndex: Int = 0
    )

    private class IncomingShareTransfer(
        val requestId: String,
        val filename: String,
        val mimeType: String,
        val tempFile: File
    ) {
        private val output = tempFile.outputStream().buffered()
        var nextChunkIndex: Int = 0

        fun append(bytes: ByteArray) {
            output.write(bytes)
            output.flush()
        }

        fun close() {
            output.close()
        }
    }

    private data class QueuedOutgoingShare(
        val file: SharedPhoneFile,
        val mode: ShareHandlingMode
    )

    companion object {
        private const val MAX_FRAME_SIZE = 2 * 1024 * 1024
        private const val FILE_CHUNK_SIZE_BYTES = 256 * 1024
        private const val TAG = "PhoneIntegration"

        @Volatile
        private var instance: PhoneIntegrationController? = null

        fun getInstance(context: Context): PhoneIntegrationController =
            instance ?: synchronized(this) {
                instance ?: PhoneIntegrationController(context).also { instance = it }
            }
    }
}
