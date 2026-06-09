package com.narzoai.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.AlarmClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * PhoneController - Handles phone control operations via Android Accessibility Service.
 *
 * Capabilities:
 * - Open any app by package name
 * - Set alarms and timers
 * - Send WhatsApp messages
 * - Control WiFi, Bluetooth, brightness, volume
 * - Read notifications aloud
 * - Perform gestures for navigation
 *
 * All operations require the Accessibility Service to be enabled and
 * appropriate permissions to be granted.
 */
class PhoneController(private val context: Context) {

    companion object {
        private const val TAG = "NarzoAI_PhoneCtrl"
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
        private const val SETTINGS_PACKAGE = "com.android.settings"
    }

    private var accessibilityService: NarzoAccessibilityService? = null
    private var isServiceConnected = false

    // Audio manager for volume control
    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    // WiFi manager
    private val wifiManager: WifiManager by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    // Power manager for wake lock
    private val powerManager: PowerManager by lazy {
        context.getSystemService(Context.POWER_SERVICE) as PowerManager
    }

    /**
     * Connect to the accessibility service.
     * Must be called before performing any accessibility-based actions.
     */
    fun connectToAccessibilityService(service: NarzoAccessibilityService) {
        accessibilityService = service
        isServiceConnected = true
        Log.d(TAG, "Connected to accessibility service")
    }

    /**
     * Disconnect from the accessibility service.
     */
    fun disconnectFromAccessibilityService() {
        accessibilityService = null
        isServiceConnected = false
        Log.d(TAG, "Disconnected from accessibility service")
    }

    /**
     * Open an app by its package name.
     * Uses the package manager to launch the app.
     *
     * @param packageName The Android package name (e.g., "com.whatsapp")
     * @return true if the app was launched successfully
     */
    fun openApp(packageName: String): Boolean {
        return try {
            val packageManager = context.packageManager
            val intent = packageManager.getLaunchIntentForPackage(packageName)

            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Log.d(TAG, "Opened app: $packageName")
                true
            } else {
                Log.w(TAG, "App not found: $packageName")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open app: $packageName", e)
            false
        }
    }

    /**
     * Open an app by its common name.
     * Maps common names to package names.
     */
    fun openAppByName(appName: String): Boolean {
        val packageName = resolveAppName(appName.lowercase().trim())
        return if (packageName != null) {
            openApp(packageName)
        } else {
            Log.w(TAG, "Unknown app name: $appName")
            false
        }
    }

    /**
     * Resolve common app names to Android package names.
     */
    private fun resolveAppName(name: String): String? {
        return when (name) {
            "whatsapp", "whats app", "what's app" -> "com.whatsapp"
            "youtube" -> "com.google.android.youtube"
            "chrome", "browser", "internet" -> "com.android.chrome"
            "gmail", "mail", "email" -> "com.google.android.gm"
            "maps", "google maps" -> "com.google.android.apps.maps"
            "photos", "gallery" -> "com.google.android.apps.photos"
            "settings" -> "com.android.settings"
            "camera" -> "com.android.camera"
            "phone", "dialer" -> "com.android.dialer"
            "messages", "sms" -> "com.android.messaging"
            "clock", "alarm" -> "com.android.deskclock"
            "calculator" -> "com.android.calculator2"
            "calendar" -> "com.google.android.calendar"
            "play store" -> "com.android.vending"
            "spotify" -> "com.spotify.music"
            "instagram" -> "com.instagram.android"
            "facebook" -> "com.facebook.katana"
            "twitter", "x" -> "com.twitter.android"
            "linkedin" -> "com.linkedin.android"
            "telegram" -> "org.telegram.messenger"
            "discord" -> "com.discord"
            "netflix" -> "com.netflix.mediaclient"
            else -> null
        }
    }

    /**
     * Set an alarm using the clock app intent.
     *
     * @param hour Hour (0-23)
     * @param minute Minute (0-59)
     * @param label Optional alarm label
     */
    fun setAlarm(hour: Int, minute: Int, label: String = "NarzoAI Alarm"): Boolean {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                Log.d(TAG, "Alarm set for $hour:$minute - $label")
                true
            } else {
                Log.w(TAG, "No alarm app available")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set alarm", e)
            false
        }
    }

    /**
     * Set a timer for the specified duration.
     *
     * @param durationSeconds Duration in seconds
     * @param label Optional timer label
     */
    fun setTimer(durationSeconds: Int, label: String = "NarzoAI Timer"): Boolean {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, durationSeconds)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                Log.d(TAG, "Timer set for ${durationSeconds}s - $label")
                true
            } else {
                Log.w(TAG, "No timer app available")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set timer", e)
            false
        }
    }

    /**
     * Send a WhatsApp message to a contact.
     * Opens WhatsApp with pre-filled message.
     *
     * @param phoneNumber Phone number with country code (e.g., "+1234567890")
     * @param message Message text to send
     */
    fun sendWhatsAppMessage(phoneNumber: String, message: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                setPackage(WHATSAPP_PACKAGE)
                setType("text/plain")
                putExtra(Intent.EXTRA_TEXT, message)
                putExtra("jid", "${phoneNumber}@s.whatsapp.net")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)
            Log.d(TAG, "WhatsApp message opened for $phoneNumber")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open WhatsApp", e)
            false
        }
    }

    /**
     * Control WiFi state.
     *
     * @param enabled true to enable WiFi, false to disable
     */
    fun setWifiEnabled(enabled: Boolean): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ requires Settings panel for WiFi
                val intent = Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Log.d(TAG, "Opened WiFi settings panel")
                true
            } else {
                @Suppress("DEPRECATION")
                wifiManager.isWifiEnabled = enabled
                Log.d(TAG, "WiFi ${if (enabled) "enabled" else "disabled"}")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle WiFi", e)
            false
        }
    }

    /**
     * Control Bluetooth state.
     *
     * @param enabled true to enable Bluetooth, false to disable
     */
    fun setBluetoothEnabled(enabled: Boolean): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ requires Bluetooth permission
                if (ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    Log.w(TAG, "Bluetooth permission not granted")
                    return false
                }
            }

            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            if (bluetoothAdapter != null) {
                if (enabled) {
                    bluetoothAdapter.enable()
                } else {
                    bluetoothAdapter.disable()
                }
                Log.d(TAG, "Bluetooth ${if (enabled) "enabled" else "disabled"}")
                true
            } else {
                Log.w(TAG, "Bluetooth not available")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle Bluetooth", e)
            false
        }
    }

    /**
     * Set screen brightness.
     *
     * @param brightness 0.0 to 1.0 (0 = minimum, 1 = maximum)
     */
    fun setBrightness(brightness: Float): Boolean {
        return try {
            val brightnessInt = (brightness * 255).toInt().coerceIn(0, 255)

            // Requires WRITE_SETTINGS permission
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.System.canWrite(context)) {
                    Log.w(TAG, "Write settings permission not granted")
                    // Open settings for permission
                    val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    return false
                }
            }

            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                brightnessInt
            )
            Log.d(TAG, "Brightness set to $brightnessInt")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set brightness", e)
            false
        }
    }

    /**
     * Set media volume.
     *
     * @param volume 0 to max volume (automatically clamped)
     */
    fun setVolume(volume: Int): Boolean {
        return try {
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val newVolume = volume.coerceIn(0, maxVolume)

            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                newVolume,
                AudioManager.FLAG_SHOW_UI
            )
            Log.d(TAG, "Volume set to $newVolume/$maxVolume")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set volume", e)
            false
        }
    }

    /**
     * Increase volume by one step.
     */
    fun volumeUp(): Boolean {
        return try {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_RAISE,
                AudioManager.FLAG_SHOW_UI
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to increase volume", e)
            false
        }
    }

    /**
     * Decrease volume by one step.
     */
    fun volumeDown(): Boolean {
        return try {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrease volume", e)
            false
        }
    }

    /**
     * Mute all media volume.
     */
    fun mute(): Boolean {
        return try {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_MUTE,
                AudioManager.FLAG_SHOW_UI
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mute", e)
            false
        }
    }

    /**
     * Get the current battery level.
     */
    fun getBatteryLevel(): Int {
        // This would require a broadcast receiver for battery info
        // Simplified: returns a default or calls a secondary method
        return try {
            val intent = context.registerReceiver(
                null,
                android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            val level = intent?.getIntExtra("level", -1) ?: -1
            val scale = intent?.getIntExtra("scale", -1) ?: -1
            if (level >= 0 && scale > 0) {
                (level * 100 / scale)
            } else {
                -1
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get battery level", e)
            -1
        }
    }

    /**
     * Perform a click action at the specified screen coordinates
     * using the Accessibility Service.
     */
    fun performClick(x: Float, y: Float): Boolean {
        if (!isServiceConnected || accessibilityService == null) {
            Log.w(TAG, "Accessibility service not connected")
            return false
        }

        return try {
            val path = Path()
            path.moveTo(x, y)
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
                .build()

            accessibilityService!!.dispatchGesture(gesture, null, null)
            Log.d(TAG, "Click performed at ($x, $y)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to perform click", e)
            false
        }
    }

    /**
     * Perform a swipe gesture.
     */
    fun performSwipe(startX: Float, startY: Float, endX: Float, endY: Float): Boolean {
        if (!isServiceConnected || accessibilityService == null) {
            Log.w(TAG, "Accessibility service not connected")
            return false
        }

        return try {
            val path = Path()
            path.moveTo(startX, startY)
            path.lineTo(endX, endY)
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
                .build()

            accessibilityService!!.dispatchGesture(gesture, null, null)
            Log.d(TAG, "Swipe performed from ($startX, $startY) to ($endX, $endY)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to perform swipe", e)
            false
        }
    }

    /**
     * Go back (simulate back button press).
     */
    fun goBack(): Boolean {
        if (!isServiceConnected || accessibilityService == null) {
            Log.w(TAG, "Accessibility service not connected")
            return false
        }

        return try {
            accessibilityService!!.performGlobalAction(
                AccessibilityService.GLOBAL_ACTION_BACK
            )
            Log.d(TAG, "Back navigation performed")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to go back", e)
            false
        }
    }

    /**
     * Go to home screen.
     */
    fun goHome(): Boolean {
        if (!isServiceConnected || accessibilityService == null) {
            return try {
                val intent = Intent(Intent.ACTION_MAIN)
                intent.addCategory(Intent.CATEGORY_HOME)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to go home", e)
                false
            }
        }

        return try {
            accessibilityService!!.performGlobalAction(
                AccessibilityService.GLOBAL_ACTION_HOME
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to go home", e)
            false
        }
    }

    /**
     * Open recent apps.
     */
    fun openRecentApps(): Boolean {
        if (!isServiceConnected || accessibilityService == null) {
            Log.w(TAG, "Accessibility service not connected")
            return false
        }

        return try {
            accessibilityService!!.performGlobalAction(
                AccessibilityService.GLOBAL_ACTION_RECENTS
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open recent apps", e)
            false
        }
    }

    /**
     * Check if the accessibility service is connected.
     */
    fun isAccessibilityServiceConnected(): Boolean = isServiceConnected

    /**
     * Check if the device can run shell commands (requires root).
     */
    fun hasRootAccess(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su -c 'echo root_access_test'")
            val reader = java.io.BufferedReader(
                java.io.InputStreamReader(process.inputStream)
            )
            val output = reader.readLine()
            process.destroy()
            output == "root_access_test"
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Execute a shell command (requires root or ADB).
     * Used for advanced phone control operations.
     */
    private fun executeShellCommand(command: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("sh -c '$command'")
            process.waitFor()
            process.exitValue() == 0
        } catch (e: Exception) {
            Log.e(TAG, "Shell command failed: $command", e)
            false
        }
    }
}
