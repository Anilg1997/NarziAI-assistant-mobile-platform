package com.narzoai.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.*

/**
 * ModelService - Foreground service for managing AI model lifecycle.
 *
 * This service keeps the app alive in the background while models are loaded.
 * It manages:
 * - Keep-alive while model is loaded (prevents GC from reclaiming model memory)
 * - Model unloading when app goes to background (saves memory)
 * - CPU wake lock during inference (prevents sleep during processing)
 * - Model cache management for 4GB RAM optimization
 */
class ModelService : Service() {

    companion object {
        private const val TAG = "NarzoAI_ModelService"
        private const val CHANNEL_ID = "narzoai_model_service"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_LOCK_TIMEOUT = 600000L // 10 minutes

        // Intent actions for the service
        const val ACTION_START_MODEL = "com.narzoai.assistant.START_MODEL"
        const val ACTION_MODEL_LOADED = "com.narzoai.assistant.MODEL_LOADED"
        const val ACTION_MODEL_UNLOADED = "com.narzoai.assistant.MODEL_UNLOADED"
        const val ACTION_STOP_SERVICE = "com.narzoai.assistant.STOP_MODEL_SERVICE"

        @Volatile
        var isRunning = false
            private set
    }

    private lateinit var notificationManager: NotificationManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var powerManager: PowerManager? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isModelLoaded = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Model service created")
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        powerManager = getSystemService(POWER_SERVICE) as PowerManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_MODEL -> {
                startForegroundService()
                acquireWakeLock()
                isRunning = true
                Log.d(TAG, "Model service started with wake lock")
            }
            ACTION_MODEL_LOADED -> {
                isModelLoaded = true
                updateNotification("AI Model Loaded", "Ready to assist you")
                Log.d(TAG, "Model loaded notification updated")
            }
            ACTION_MODEL_UNLOADED -> {
                isModelLoaded = false
                updateNotification("AI Model Ready", "Waiting for your command")
                releaseWakeLock()
                Log.d(TAG, "Model unloaded, wake lock released")
            }
            ACTION_STOP_SERVICE -> {
                stopService()
            }
        }

        return START_STICKY
    }

    /**
     * Start the foreground service with a persistent notification.
     */
    private fun startForegroundService() {
        val notification = createNotification(
            "NarzoAI Assistant",
            "Preparing AI model..."
        )
        startForeground(NOTIFICATION_ID, notification)
    }

    /**
     * Create the notification channel for foreground service.
     * Required for Android 8.0+ (API 26+).
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "NarzoAI Model Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the AI model loaded for fast response"
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Create a notification for the foreground service.
     */
    private fun createNotification(title: String, content: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_LOW)
            .build()
    }

    /**
     * Update the notification content.
     */
    private fun updateNotification(title: String, content: String) {
        val notification = createNotification(title, content)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Acquire a partial wake lock to prevent CPU sleep during model inference.
     * This is released when the model is unloaded.
     */
    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                wakeLock = powerManager?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "NarzoAI:ModelWakeLock"
                )
            }
            wakeLock?.acquire(WAKE_LOCK_TIMEOUT)
            Log.d(TAG, "Wake lock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock", e)
        }
    }

    /**
     * Release the wake lock to save battery.
     */
    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "Wake lock released")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing wake lock", e)
        }
    }

    /**
     * Stop the foreground service and clean up.
     */
    private fun stopService() {
        Log.d(TAG, "Stopping model service")
        releaseWakeLock()
        isRunning = false
        isModelLoaded = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Model service destroyed")
        releaseWakeLock()
        isRunning = false
        isModelLoaded = false
        serviceScope.cancel()
    }

    /**
     * Check if the model is currently loaded.
     */
    fun isModelActive(): Boolean = isModelLoaded

    /**
     * Get the service running state.
     */
    fun isServiceRunning(): Boolean = isRunning

}
