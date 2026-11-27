package com.tk.myapp.feature.typemachine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class TypedEntry(
    val id: String,
    val packageName: String,
    val text: String,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Keeps everything typed into other apps' input fields. One entry per typing session:
 * typing in the same field keeps updating the same entry; switching fields, clearing the
 * field (e.g. after sending a message) or pausing for a while starts a new one.
 */
class TypemachineStore private constructor(context: Context) {

    private val file = File(context.filesDir, "typemachine_entries.json")
    private val prefs = context.getSharedPreferences("typemachine_prefs", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<TypedEntry>> = _entries.asStateFlow()

    private val _excludedApps = MutableStateFlow(
        prefs.getStringSet(KEY_EXCLUDED_APPS, emptySet())?.toSet().orEmpty()
    )
    val excludedApps: StateFlow<Set<String>> = _excludedApps.asStateFlow()

    fun isExcluded(packageName: String): Boolean = packageName in _excludedApps.value

    /** Stops capturing [packageName] and deletes everything already recorded from it. */
    @Synchronized
    fun excludeApp(packageName: String) {
        setExcluded(_excludedApps.value + packageName)
        if (activeEntryId?.let { id -> _entries.value.firstOrNull { it.id == id }?.packageName } == packageName) {
            endSession()
        }
        _entries.value = _entries.value.filterNot { it.packageName == packageName }
        scheduleSave()
    }

    @Synchronized
    fun includeApp(packageName: String) {
        setExcluded(_excludedApps.value - packageName)
    }

    private fun setExcluded(apps: Set<String>) {
        _excludedApps.value = apps
        prefs.edit().putStringSet(KEY_EXCLUDED_APPS, apps).apply()
    }

    private var activeFieldKey: String? = null
    private var activeEntryId: String? = null
    private var saveJob: Job? = null

    @Synchronized
    fun record(packageName: String, fieldKey: String, text: String) {
        if (isExcluded(packageName)) return
        val now = System.currentTimeMillis()
        if (text.isBlank()) {
            // Field was cleared (message sent, text deleted): next typing starts a new entry.
            endSession()
            return
        }
        val current = _entries.value
        val active = activeEntryId?.let { id -> current.firstOrNull { it.id == id } }
        val continueSession = active != null &&
            activeFieldKey == fieldKey &&
            now - active.updatedAt < SESSION_GAP_MS

        val updated = if (continueSession) {
            if (active!!.text == text) return
            listOf(active.copy(text = text, updatedAt = now)) + current.filterNot { it.id == active.id }
        } else {
            val entry = TypedEntry(
                id = UUID.randomUUID().toString(),
                packageName = packageName,
                text = text,
                createdAt = now,
                updatedAt = now
            )
            activeFieldKey = fieldKey
            activeEntryId = entry.id
            listOf(entry) + current
        }
        _entries.value = updated.take(MAX_ENTRIES)
        scheduleSave()
    }

    @Synchronized
    fun endSession() {
        activeFieldKey = null
        activeEntryId = null
    }

    @Synchronized
    fun delete(id: String) {
        if (activeEntryId == id) endSession()
        _entries.value = _entries.value.filterNot { it.id == id }
        scheduleSave()
    }

    @Synchronized
    fun clear() {
        endSession()
        _entries.value = emptyList()
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_DEBOUNCE_MS)
            save(_entries.value)
        }
    }

    private fun save(entries: List<TypedEntry>) {
        runCatching {
            val array = JSONArray()
            entries.forEach { entry ->
                array.put(
                    JSONObject().apply {
                        put("id", entry.id)
                        put("packageName", entry.packageName)
                        put("text", entry.text)
                        put("createdAt", entry.createdAt)
                        put("updatedAt", entry.updatedAt)
                    }
                )
            }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(array.toString())
            tmp.renameTo(file)
        }.onFailure { Log.e(TAG, "Failed to save entries", it) }
    }

    private fun load(): List<TypedEntry> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            List(array.length()) { i ->
                val obj = array.getJSONObject(i)
                TypedEntry(
                    id = obj.getString("id"),
                    packageName = obj.getString("packageName"),
                    text = obj.getString("text"),
                    createdAt = obj.getLong("createdAt"),
                    updatedAt = obj.getLong("updatedAt")
                )
            }.take(MAX_ENTRIES)
        }.getOrElse {
            Log.e(TAG, "Failed to load entries", it)
            emptyList()
        }
    }

    companion object {
        private const val TAG = "TypemachineStore"
        private const val MAX_ENTRIES = 250
        private const val SESSION_GAP_MS = 5 * 60 * 1000L
        private const val SAVE_DEBOUNCE_MS = 1000L
        private const val KEY_EXCLUDED_APPS = "typemachine.excludedApps"

        @Volatile
        private var instance: TypemachineStore? = null

        fun getInstance(context: Context): TypemachineStore =
            instance ?: synchronized(this) {
                instance ?: TypemachineStore(context.applicationContext).also { instance = it }
            }
    }
}
