package com.narzoai.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * NarzoAccessibilityService - Accessibility Service for phone control.
 *
 * This service enables NarzoAI to:
 * - Read notifications aloud
 * - Interact with apps programmatically
 * - Perform global actions (back, home, recents)
 * - Inject gestures and touch events
 *
 * The user must enable this service in Settings > Accessibility > NarzoAI Assistant.
 */
class NarzoAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "NarzoAI_Accessibility"
        private const val PACKAGE_SETTINGS = "com.android.settings"
        private const val PACKAGE_WHATSAPP = "com.whatsapp"
        private const val PACKAGE_MESSAGES = "com.android.messaging"
        private const val PACKAGE_CLOCK = "com.android.deskclock"

        // Singleton instance for communication with PhoneController
        @Volatile
        private var instance: NarzoAccessibilityService? = null

        fun getInstance(): NarzoAccessibilityService? = instance
    }

    // Last notification text read
    private val _lastNotification = MutableStateFlow<String?>(null)
    val lastNotification: StateFlow<String?> = _lastNotification

    // Current active app
    private val _currentApp = MutableStateFlow<String?>(null)
    val currentApp: StateFlow<String?> = _currentApp

    // Service connection state
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    // Callback for notification reading
    var onNotificationReceived: ((String) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "Accessibility service created")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        _isConnected.value = true
        instance = this

        // Configure service info
        val info = AccessibilityServiceInfo().apply {
            // Listen to all events
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK

            // Only listen to events from relevant packages or all packages
            packageNames = null // Listen to all apps

            // Feedback type
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC

            // Notification timeout
            notificationTimeout = 100

            // Retrieve window content
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE or
                    AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY
        }

        this.serviceInfo = info

        Log.d(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                handleNotificationEvent(event)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowChange(event)
            }
            AccessibilityEvent.TYPE_ANNOUNCEMENT -> {
                handleAnnouncement(event)
            }
        }
    }

    /**
     * Handle notification events - reads notifications aloud.
     */
    private fun handleNotificationEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return

        // Extract notification text
        val notificationText = when {
            event.text.isNotEmpty() -> {
                event.text.joinToString(" ")
            }
            event.contentDescription != null -> {
                event.contentDescription.toString()
            }
            else -> return
        }

        Log.d(TAG, "Notification from $packageName: $notificationText")
        _lastNotification.value = notificationText

        // Notify callback
        onNotificationReceived?.invoke("Notification from $packageName: $notificationText")
    }

    /**
     * Handle window change events - track the current app.
     */
    private fun handleWindowChange(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return

        if (packageName != _currentApp.value) {
            Log.d(TAG, "App changed to: $packageName")
            _currentApp.value = packageName
        }
    }

    /**
     * Handle announcement events.
     */
    private fun handleAnnouncement(event: AccessibilityEvent) {
        if (event.text.isNotEmpty()) {
            val announcement = event.text.joinToString(" ")
            Log.d(TAG, "Announcement: $announcement")
        }
    }

    /**
     * Find a UI element by text in the current window.
     * Useful for automation tasks like clicking buttons.
     */
    fun findAndClickByText(text: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false

        val matchedNodes = rootNode.findAccessibilityNodeInfosByText(text)
        rootNode.recycle()

        if (matchedNodes.isNotEmpty()) {
            val targetNode = matchedNodes[0]
            if (targetNode.isClickable) {
                targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                targetNode.recycle()
                return true
            }
            // Try parent if not clickable
            var parent = targetNode.parent
            while (parent != null) {
                if (parent.isClickable) {
                    parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    parent.recycle()
                    targetNode.recycle()
                    return true
                }
                val grandParent = parent.parent
                parent.recycle()
                parent = grandParent
            }
            targetNode.recycle()
        }

        return false
    }

    /**
     * Find a UI element by content description.
     */
    fun findAndClickByContentDescription(description: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false

        // findAccessibilityNodeInfosByContentDescription not available, traverse tree instead
        val matchedNodes = mutableListOf<AccessibilityNodeInfo>()
        fun traverse(node: AccessibilityNodeInfo) {
            if (node.contentDescription?.toString()?.contains(description, ignoreCase = true) == true) {
                matchedNodes.add(node)
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                traverse(child)
            }
        }
        traverse(rootNode)
        rootNode.recycle()

        if (matchedNodes.isNotEmpty()) {
            val targetNode = matchedNodes[0]
            if (targetNode.isClickable) {
                targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } else {
                targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            }
            targetNode.recycle()
            return true
        }

        return false
    }

    /**
     * Find and interact with a UI element by its view ID.
     */
    fun findAndClickById(viewId: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false

        val matchedNodes = rootNode.findAccessibilityNodeInfosByViewId(viewId)
        rootNode.recycle()

        if (matchedNodes.isNotEmpty()) {
            val targetNode = matchedNodes[0]
            if (targetNode.isClickable) {
                targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } else {
                targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            }
            targetNode.recycle()
            return true
        }

        return false
    }

    /**
     * Type text into a text field that currently has focus.
     */
    fun typeText(text: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false

        // Find focused node
        val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        rootNode.recycle()

        if (focusedNode != null) {
            // Create a clipboard-like approach by setting text
            val args = android.os.Bundle()
            args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
            focusedNode.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                args
            )
            focusedNode.recycle()
            return true
        }

        return false
    }

    /**
     * Scroll down in the current window.
     */
    fun scrollDown(): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val result = rootNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        rootNode.recycle()
        return result
    }

    /**
     * Scroll up in the current window.
     */
    fun scrollUp(): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val result = rootNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        rootNode.recycle()
        return result
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        _isConnected.value = false
        _currentApp.value = null
        Log.d(TAG, "Accessibility service destroyed")
    }

    /**
     * Get the current foreground app package name.
     */
    fun getForegroundApp(): String? = _currentApp.value

    /**
     * Get the last received notification text.
     */
    fun getLastNotification(): String? = _lastNotification.value

    /**
     * Check if the service is currently connected.
     */
    override fun onUnbind(intent: Intent?): Boolean {
        _isConnected.value = false
        return super.onUnbind(intent)
    }
}
