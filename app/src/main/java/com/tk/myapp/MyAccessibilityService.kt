package com.tk.myapp

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.tk.myapp.api.ApiClient
import com.tk.myapp.api.Message
import com.tk.myapp.api.OpenAiRequest
import com.tk.myapp.data.common.Storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

class MyAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentNode: WeakReference<AccessibilityNodeInfo>? = null
    private var textBeforeShortcutRemoval = ""
    
    @Volatile
    private var isFetchInProgress = false
    
    @Volatile
    private var ignoringTextChanges = false
    
    private val storage by lazy { Storage(applicationContext) }
    
    private val channelId = "accessibility_service_channel"
    private val notificationId = 1001
    
    companion object {
        private const val TAG = "MyAccessibilityService"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Service connected")
        startForeground()
    }

    private fun startForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Text Rewriting Service",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Monitors text input for rewriting shortcuts"
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Text Rewriting Active")
            .setContentText("Monitoring text input for shortcuts")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(notificationId, notification)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (ignoringTextChanges) return
        
        when (event?.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val focused = findCurrentFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                handleShortcutTriggers(focused)
            }
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                handleFocusChange(event.source)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleFocusChange(findCurrentFocus(AccessibilityNodeInfo.FOCUS_INPUT))
            }
        }
    }

    private fun handleFocusChange(node: AccessibilityNodeInfo?) {
        val validNode = node?.let { tryObtain(it)?.takeIf { it.isEditable } }
        currentNode = validNode?.let { WeakReference(it) }
    }

    private fun handleShortcutTriggers(node: AccessibilityNodeInfo?) {
        // Prevent multiple simultaneous API calls
        if (isFetchInProgress) return
        
        val validNode = currentNode?.get()?.takeIf { it.stillValid() }
            ?: node?.let { tryObtain(it)?.takeIf { it.isEditable } }
            ?: return
        
        // Check if app is in allowlist
        val packageName = validNode.packageName?.toString()
        if (!shouldMonitorApp(packageName)) return
        
        currentNode = WeakReference(validNode)
        val currentText = validNode.text?.toString().orEmpty()
        if (currentText.isBlank()) return
        
        // Get all shortcuts and check each one
        val shortcuts = storage.getShortcuts()
        if (shortcuts.isEmpty()) return
        
        for (shortcut in shortcuts) {
            if (!shortcut.useChatGPT) continue // Only process ChatGPT shortcuts
            
            // Detect shortcut pattern: "[shortcut] " (shortcut followed by space)
            val pattern = Regex(Regex.escape(shortcut.name) + "\\s", RegexOption.IGNORE_CASE)
            
            if (pattern.containsMatchIn(currentText)) {
                // Extract text after shortcut removal
                val cleanedText = currentText.replaceFirst(pattern, "").trim()
                if (cleanedText.isBlank()) continue
                
                // Store original text for restoration on failure
                textBeforeShortcutRemoval = cleanedText
                
                // Remove shortcut from field immediately
                ignoringTextChanges = true
                setInputFieldText(cleanedText)
                scope.launch {
                    delay(120) // Small delay to ensure text is set
                    ignoringTextChanges = false
                }
                
                // Trigger API call with this shortcut's prompt
                fetchNewText(cleanedText, shortcut.prompt)
                break // Only process first matching shortcut
            }
        }
    }

    private fun shouldMonitorApp(packageName: String?): Boolean {
        if (packageName == null) return false
        
        // Don't monitor our own app
        if (packageName == this.packageName) return false
        
        val selectedApps = storage.getSelectedApps()
        // If no apps selected, monitor all apps (except our own)
        if (selectedApps.isEmpty()) return true
        // Otherwise, only monitor selected apps
        return selectedApps.any { it.packageName == packageName }
    }

    private fun fetchNewText(extractedText: String, configuredPrompt: String) {
        if (isFetchInProgress) return
        
        val apiKey = storage.getApiKey(Storage.KEY_OPENAI_API_KEY)
        if (apiKey.isNullOrBlank()) {
            Toast.makeText(this, "OpenAI API Key not set.", Toast.LENGTH_LONG).show()
            return
        }
        
        // Ensure prompt ends with ": " (colon and space)
        val normalizedPrompt = when {
            configuredPrompt.endsWith(": ") -> configuredPrompt
            configuredPrompt.endsWith(":") -> "${configuredPrompt.dropLast(1)}: "
            else -> "$configuredPrompt: "
        }
        
        val fullPrompt = "$normalizedPrompt$extractedText"
        
        isFetchInProgress = true
        
        // Show "Rewriting..." placeholder
        showRewritingPlaceholder()
        
        scope.launch(Dispatchers.IO) {
            try {
                val request = OpenAiRequest(
                    messages = listOf(Message(content = fullPrompt))
                )
                val response = ApiClient.instance.getCompletion("Bearer $apiKey", request)
                
                withContext(Dispatchers.Main) {
                    val result = response.body()?.choices?.firstOrNull()?.message?.content?.trim()
                    
                    if (response.isSuccessful && !result.isNullOrBlank()) {
                        // Success: Replace with API response
                        applyRewriteResult(result)
                    } else {
                        // Failure: Restore original text
                        val errorMsg = response.body()?.error?.message ?: "API Error"
                        Toast.makeText(applicationContext, errorMsg, Toast.LENGTH_LONG).show()
                        restoreTextOnFailure()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "API call failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, "Network Error: ${e.message}", Toast.LENGTH_LONG).show()
                    restoreTextOnFailure()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    isFetchInProgress = false
                }
            }
        }
    }

    private fun showRewritingPlaceholder() {
        val node = currentNode?.get()?.takeIf { it.stillValid() } ?: return
        ignoringTextChanges = true
        setInputFieldText("Rewriting...")
        scope.launch {
            delay(150)
            ignoringTextChanges = false
        }
    }

    private fun applyRewriteResult(result: String) {
        ignoringTextChanges = true
        setInputFieldText(result)
        scope.launch {
            delay(200)
            ignoringTextChanges = false
        }
    }

    private fun restoreTextOnFailure() {
        if (textBeforeShortcutRemoval.isNotBlank()) {
            ignoringTextChanges = true
            setInputFieldText(textBeforeShortcutRemoval)
            textBeforeShortcutRemoval = ""
            scope.launch {
                delay(200)
                ignoringTextChanges = false
            }
        }
    }

    private fun setInputFieldText(text: String) {
        val node = currentNode?.get()?.takeIf { it.stillValid() } ?: return
        
        // Set the text
        Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, this)
        }
        
        // Move cursor to end
        Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, text.length)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length)
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, this)
        }
    }

    private fun findCurrentFocus(focusType: Int): AccessibilityNodeInfo? {
        return rootInActiveWindow?.let { root ->
            root.findFocus(focusType)?.let { tryObtain(it) }
        }
    }

    private fun tryObtain(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return runCatching {
            AccessibilityNodeInfo.obtain(node).apply { refresh() }
        }.getOrNull()
    }

    private fun AccessibilityNodeInfo.stillValid(): Boolean {
        return runCatching {
            refresh()
            isEditable && isFocused
        }.getOrDefault(false)
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        Log.d(TAG, "Service destroyed")
    }
}
