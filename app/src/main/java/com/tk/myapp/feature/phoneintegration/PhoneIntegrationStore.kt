package com.tk.myapp.feature.phoneintegration

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class PhoneIntegrationStore(context: Context) {
    private val appContext = context.applicationContext
    private val masterKey = MasterKey.Builder(appContext)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val preferences = EncryptedSharedPreferences.create(
        appContext,
        "phone_integration_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun getDeviceId(): String {
        preferences.getString(KEY_DEVICE_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        preferences.edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }

    fun getTrustedPeers(): List<TrustedPhonePeer> {
        val raw = preferences.getString(KEY_TRUSTED_PEERS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                TrustedPhonePeer(
                    id = item.getString("id"),
                    name = item.getString("name"),
                    publicKeyBase64 = item.getString("publicKeyBase64"),
                    pairedAtMillis = item.getLong("pairedAtMillis"),
                    lastSeenMillis = item.optLong("lastSeenMillis", item.getLong("pairedAtMillis"))
                )
            }
        }.getOrElse { emptyList() }
    }

    fun saveTrustedPeer(peer: TrustedPhonePeer) {
        val peers = getTrustedPeers()
            .filterNot { it.id == peer.id }
            .plus(peer)
            .sortedBy { it.name.lowercase() }
        saveTrustedPeers(peers)
    }

    fun removeTrustedPeer(peerId: String) {
        saveTrustedPeers(getTrustedPeers().filterNot { it.id == peerId })
    }

    private fun saveTrustedPeers(peers: List<TrustedPhonePeer>) {
        val array = JSONArray()
        peers.forEach { peer ->
            array.put(JSONObject().apply {
                put("id", peer.id)
                put("name", peer.name)
                put("publicKeyBase64", peer.publicKeyBase64)
                put("pairedAtMillis", peer.pairedAtMillis)
                put("lastSeenMillis", peer.lastSeenMillis)
            })
        }
        preferences.edit().putString(KEY_TRUSTED_PEERS, array.toString()).apply()
    }

    companion object {
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_TRUSTED_PEERS = "trusted_peers"
    }
}
