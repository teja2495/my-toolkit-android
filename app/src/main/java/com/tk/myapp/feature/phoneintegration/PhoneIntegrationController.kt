package com.tk.myapp.feature.phoneintegration

import android.content.Context
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
        pendingConnections.clear()
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

    private suspend fun handleClient(socket: Socket) {
        socket.tcpNoDelay = true
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
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
            _uiState.update {
                it.copy(
                    connectionState = PhoneBridgeConnectionState.Connected,
                    statusMessage = "Paired with ${pending.peerName}",
                    pendingPairing = null,
                    trustedPeers = store.getTrustedPeers()
                )
            }

            while (!socket.isClosed) {
                val message = pending.readEncrypted()
                Log.d(
                    TAG,
                    "Received encrypted message type=${message.optString("type")} peerId=${pending.peerId}"
                )
                if (message.optString("type") == PhoneBridgeProtocol.typeListFiles) {
                    handleListFiles(message, pending)
                }
            }
        }.onFailure { error ->
            Log.e(
                TAG,
                "Client connection failed remote=${socket.inetAddress.hostAddress}",
                error
            )
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

    companion object {
        private const val MAX_FRAME_SIZE = 2 * 1024 * 1024
        private const val TAG = "PhoneIntegration"

        @Volatile
        private var instance: PhoneIntegrationController? = null

        fun getInstance(context: Context): PhoneIntegrationController =
            instance ?: synchronized(this) {
                instance ?: PhoneIntegrationController(context).also { instance = it }
            }
    }
}
