package com.tk.myapp.feature.phoneintegration

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
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
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val deviceId = store.getDeviceId()
    private val deviceName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
    private val pendingConnections = ConcurrentHashMap<String, PendingConnection>()
    private val activeConnections = ConcurrentHashMap<String, PendingConnection>()
    private val queuedOutgoingShares = mutableListOf<SharedPhoneFile>()
    private val activeOutgoingShareRequestIds = mutableSetOf<String>()
    private val activeIncomingShareTransfers = ConcurrentHashMap<String, IncomingShareTransfer>()

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    private val _uiState = MutableStateFlow(
        PhoneBridgeUiState(
            deviceName = deviceName,
            deviceId = deviceId,
            trustedPeers = store.getTrustedPeers()
        )
    )
    val uiState: StateFlow<PhoneBridgeUiState> = _uiState

    fun start() {
        if (serverJob?.isActive == true) return
        Log.d(TAG, "Starting phone bridge deviceId=$deviceId deviceName=$deviceName")
        _uiState.update {
            it.copy(
                connectionState = PhoneBridgeConnectionState.Starting,
                statusMessage = "Starting secure phone bridge"
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
                        statusMessage = error.message ?: "Phone bridge failed"
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
        activeIncomingShareTransfers.values.forEach { transfer ->
            runCatching { transfer.close() }
            transfer.tempFile.delete()
        }
        activeIncomingShareTransfers.clear()
        _uiState.update {
            it.copy(
                connectionState = PhoneBridgeConnectionState.Stopped,
                statusMessage = "Phone bridge is off",
                pendingPairing = null
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
                        pendingPairing = null
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        connectionState = PhoneBridgeConnectionState.Error,
                        statusMessage = error.message ?: "Pairing approval failed",
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

    fun handleShareIntent(intent: Intent) {
        val uris = extractShareUris(intent)
        if (uris.isEmpty()) {
            _uiState.update {
                it.copy(statusMessage = "No file was attached to share to Toolkit.")
            }
            return
        }
        val preparedFiles = uris.mapNotNull { uri -> fileRepository.prepareSharedFile(uri) }
        if (preparedFiles.isEmpty()) {
            _uiState.update {
                it.copy(statusMessage = "Toolkit could not open the shared file.")
            }
            return
        }
        synchronized(queuedOutgoingShares) {
            queuedOutgoingShares += preparedFiles
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
                        pendingPairing = null
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        connectionState = PhoneBridgeConnectionState.Pairing,
                        statusMessage = "Confirm pairing code on both devices",
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
                    PhoneBridgeProtocol.typeReadFile -> handleReadFile(message, pending)
                    PhoneBridgeProtocol.typeShareFileChunk -> handleIncomingShareChunk(message, pending)
                    PhoneBridgeProtocol.typeShareFileResult -> handleOutgoingShareResult(message, pending)
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
            withContext(Dispatchers.Main.immediate) {
                _uiState.update {
                    it.copy(
                        connectionState = PhoneBridgeConnectionState.Listening,
                        statusMessage = error.message ?: "Connection closed",
                        pendingPairing = null,
                        trustedPeers = store.getTrustedPeers()
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
            pendingFiles.forEach { file ->
                runCatching {
                    sendSharedFile(file, connection)
                }.onFailure { error ->
                    Log.e(TAG, "Failed to send shared file filename=${file.filename}", error)
                    _uiState.update {
                        it.copy(statusMessage = error.message ?: "Toolkit could not send the shared file.")
                    }
                }
            }
        }
    }

    private fun sendSharedFile(file: SharedPhoneFile, pending: PendingConnection) {
        val requestId = "${pending.peerId}-${System.currentTimeMillis()}-${file.filename.hashCode()}"
        activeOutgoingShareRequestIds += requestId
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
        } ?: error("Toolkit could not open the shared file.")
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
            _uiState.update {
                it.copy(statusMessage = "Saved $savedFilename to Mac Downloads and copied it to the clipboard")
            }
        } else {
            _uiState.update {
                it.copy(statusMessage = message.optString("message", "Toolkit on Mac could not save the shared file."))
            }
        }
        Log.d(TAG, "Completed outgoing share requestId=$requestId peerId=${pending.peerId}")
    }

    private fun handleShareError(message: JSONObject) {
        val requestId = message.optString("requestId")
        if (requestId.isNotBlank() && activeOutgoingShareRequestIds.remove(requestId)) {
            _uiState.update {
                it.copy(statusMessage = message.optString("message", "Toolkit could not complete the shared file transfer."))
            }
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
