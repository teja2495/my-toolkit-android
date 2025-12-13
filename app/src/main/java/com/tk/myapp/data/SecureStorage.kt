package com.tk.myapp.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecureStorage(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences = EncryptedSharedPreferences.create(
        context,
        "secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveApiKey(keyName: String, apiKey: String) {
        sharedPreferences.edit().putString(keyName, apiKey).apply()
    }

    fun getApiKey(keyName: String): String? {
        return sharedPreferences.getString(keyName, null)
    }

    fun clearApiKey(keyName: String) {
        sharedPreferences.edit().remove(keyName).apply()
    }

    companion object {
        const val KEY_GEMINI_API_KEY = "gemini_api_key"
        const val KEY_OPENAI_API_KEY = "openai_api_key"
    }
}
