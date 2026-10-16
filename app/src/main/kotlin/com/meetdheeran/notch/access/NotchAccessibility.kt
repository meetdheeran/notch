package com.meetdheeran.notch.access

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Notch's accessibility switch. It does two things and reads nothing on screen:
 *  - its context can add a TYPE_ACCESSIBILITY_OVERLAY, which Android keeps above the status bar — that is what lets
 *    the island live in the notch (an ordinary overlay sits under the status bar, so a tap would pull the shade);
 *  - it reports which app is in front ([foreground]), so a vault app can be hidden again when you leave it.
 */
class NotchAccessibility : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _connected.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // Ignore the system UI's own windows (shade, volume, keyboard) — they're not "leaving" the app.
        if (pkg == "com.android.systemui" || pkg == packageName || pkg.contains("inputmethod", ignoreCase = true)) return
        _foreground.value = pkg
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        _connected.value = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        _connected.value = false
        super.onDestroy()
    }

    companion object {
        @Volatile var instance: NotchAccessibility? = null
            private set
        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected
        private val _foreground = MutableStateFlow<String?>(null)
        /** The package of the app in front (system UI and Notch itself excluded). */
        val foreground: StateFlow<String?> = _foreground
    }
}
