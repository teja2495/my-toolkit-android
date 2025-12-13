package com.tk.myapp.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

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

    private val regularPreferences: SharedPreferences = 
        context.getSharedPreferences("shortcuts_prefs", Context.MODE_PRIVATE)

    fun saveApiKey(keyName: String, apiKey: String) {
        sharedPreferences.edit().putString(keyName, apiKey).apply()
    }

    fun getApiKey(keyName: String): String? {
        return sharedPreferences.getString(keyName, null)
    }

    fun clearApiKey(keyName: String) {
        sharedPreferences.edit().remove(keyName).apply()
    }

    fun saveShortcuts(shortcuts: List<Shortcut>) {
        val jsonArray = JSONArray()
        shortcuts.forEach { shortcut ->
            val jsonObject = JSONObject().apply {
                put("name", shortcut.name)
                put("prompt", shortcut.prompt)
                put("useChatGPT", shortcut.useChatGPT)
            }
            jsonArray.put(jsonObject)
        }
        regularPreferences.edit().putString(KEY_SHORTCUTS, jsonArray.toString()).apply()
    }

    fun getShortcuts(): List<Shortcut> {
        val jsonString = regularPreferences.getString(KEY_SHORTCUTS, null)
        return if (jsonString != null) {
            try {
                val jsonArray = JSONArray(jsonString)
                val shortcuts = mutableListOf<Shortcut>()
                for (i in 0 until jsonArray.length()) {
                    val jsonObject = jsonArray.getJSONObject(i)
                    shortcuts.add(
                        Shortcut(
                            name = jsonObject.getString("name"),
                            prompt = jsonObject.getString("prompt"),
                            useChatGPT = jsonObject.getBoolean("useChatGPT")
                        )
                    )
                }
                shortcuts
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    fun addShortcut(shortcut: Shortcut) {
        val shortcuts = getShortcuts().toMutableList()
        shortcuts.add(shortcut)
        saveShortcuts(shortcuts)
    }

    fun deleteShortcut(shortcut: Shortcut) {
        val shortcuts = getShortcuts().toMutableList()
        shortcuts.remove(shortcut)
        saveShortcuts(shortcuts)
    }

    fun updateShortcut(oldShortcut: Shortcut, newShortcut: Shortcut) {
        val shortcuts = getShortcuts().toMutableList()
        val index = shortcuts.indexOf(oldShortcut)
        if (index != -1) {
            shortcuts[index] = newShortcut
            saveShortcuts(shortcuts)
        }
    }

    companion object {
        const val KEY_GEMINI_API_KEY = "gemini_api_key"
        const val KEY_OPENAI_API_KEY = "openai_api_key"
        const val KEY_SHORTCUTS = "shortcuts"
    }
}
