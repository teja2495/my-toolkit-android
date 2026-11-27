package com.tk.myapp.feature.typemachine

import android.content.Context
import android.os.Build
import android.view.accessibility.AccessibilityEvent

/** Turns accessibility text-change events into [TypemachineStore] entries. */
object TypemachineRecorder {

    fun onTextChanged(context: Context, event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        if (packageName == context.packageName) return
        if (event.isPassword) return
        val store = TypemachineStore.getInstance(context)
        if (store.isExcluded(packageName)) return

        val source = runCatching { event.source }.getOrNull()
        if (source != null) {
            if (source.isPassword) return
            // Some apps fire text changes for plain labels; only keep input fields.
            if (!source.isEditable) return
        }

        val showingHint = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && source?.isShowingHintText == true
        val text = when {
            showingHint -> ""
            source?.text != null -> source.text.toString()
            else -> event.text.joinToString("")
        }

        val fieldKey = "$packageName|${event.windowId}|${source?.viewIdResourceName.orEmpty()}"
        store.record(packageName, fieldKey, text)
    }
}
