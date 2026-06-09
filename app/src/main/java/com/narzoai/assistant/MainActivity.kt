package com.narzoai.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * MainActivity - The main chat interface for NarzoAI Assistant.
 *
 * Features:
 * - Chat UI with message history
 * - Mic button with hold-to-speak
 * - Status indicator (listening/thinking/speaking)
 * - Wake word detection trigger
 * - Integration with all AI engines
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "NarzoAI_Main"
        private const val PERMISSION_REQUEST_CODE = 100
    }

    // Core engine references
    private lateinit var aiEngine: AIEngine
    private lateinit var voiceEngine: VoiceEngine
    private lateinit var ttsEngine: TTSEngine
    private lateinit var phoneController: PhoneController
    private lateinit var cameraAI: CameraAI

    // UI Components
    private lateinit var chatRecyclerView: RecyclerView
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var micButton: ImageButton
    private lateinit var statusIndicator: TextView
    private lateinit var statusDot: View
    private lateinit var voiceLevelBar: ProgressBar
    private lateinit var progressOverlay: View
    private lateinit var progressText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var mainLayout: View

    // Chat messages
    private val messages = mutableListOf<ChatMessage>()

    // Status tracking
    private var currentStatus = Status.IDLE

    enum class Status {
        IDLE,
        LISTENING,
        PROCESSING,
        THINKING,
        SPEAKING,
        ERROR
    }

    /**
     * Data class for chat messages in the UI.
     */
    data class ChatMessage(
        val text: String,
        val isUser: Boolean,
        val timestamp: Long = System.currentTimeMillis()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize engines
        initializeEngines()

        // Initialize UI
        initializeUI()

        // Check and request permissions
        checkPermissions()

        // Start with welcome message
        addWelcomeMessage()
    }

    /**
     * Initialize all AI engines.
     */
    private fun initializeEngines() {
        aiEngine = AIEngine(this)
        voiceEngine = VoiceEngine(this)
        ttsEngine = TTSEngine(this)
        phoneController = PhoneController(this)
        cameraAI = CameraAI(this)

        // Initialize AI engine (lazy loads model)
        aiEngine.initialize(
            onStatus = { status ->
                lifecycleScope.launch {
                    when (status) {
                        AIEngine.ModelStatus.LOADED -> {
                            updateStatus("AI Ready")
                            hideProgressOverlay()
                        }
                        AIEngine.ModelStatus.LOADING -> {
                            updateStatus("Loading AI Model...")
                        }
                        AIEngine.ModelStatus.MODEL_NOT_FOUND -> {
                            showModelError()
                        }
                        AIEngine.ModelStatus.LOAD_ERROR -> {
                            showModelError()
                        }
                        else -> {}
                    }
                }
            }
        )

        // Initialize TTS
        ttsEngine.initialize(
            onReady = {
                Log.d(TAG, "TTS ready")
            },
            onError = { error ->
                Log.e(TAG, "TTS error: $error")
            }
        )

        // Initialize voice engine
        voiceEngine.initialize(
            onWakeWord = {
                lifecycleScope.launch {
                    onWakeWordDetected()
                }
            },
            onRecognized = { text ->
                lifecycleScope.launch {
                    onSpeechRecognized(text)
                }
            },
            onErrorCallback = { error ->
                lifecycleScope.launch {
                    showError(error)
                }
            }
        )
    }

    /**
     * Initialize UI components.
     */
    private fun initializeUI() {
        mainLayout = findViewById(R.id.main_layout)

        // Chat RecyclerView
        chatRecyclerView = findViewById(R.id.chat_recycler_view)
        chatAdapter = ChatAdapter(messages)
        chatRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply {
                stackFromEnd = true
            }
            adapter = chatAdapter
            setHasFixedSize(false)
        }

        // Mic button with hold-to-speak
        micButton = findViewById(R.id.mic_button)
        setupMicButton()

        // Status indicator
        statusIndicator = findViewById(R.id.status_indicator)
        statusDot = findViewById(R.id.status_dot)
        voiceLevelBar = findViewById(R.id.voice_level_bar)

        // Progress overlay
        progressOverlay = findViewById(R.id.progress_overlay)
        progressText = findViewById(R.id.progress_text)
        progressBar = findViewById(R.id.progress_bar)

        // Setup toolbar
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.title = getString(R.string.app_name)
    }

    /**
     * Setup the microphone button with touch handlers for hold-to-speak.
     */
    private fun setupMicButton() {
        micButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // Start listening when button is pressed
                    startListening()
                    micButton.isPressed = true
                    micButton.setImageResource(R.drawable.ic_mic_active)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // Stop listening when button is released
                    stopListening()
                    micButton.isPressed = false
                    micButton.setImageResource(R.drawable.ic_mic)
                    true
                }
                else -> false
            }
        }

        // Also handle click for devices without touch hold
        micButton.setOnClickListener {
            if (voiceEngine.isListening.value) {
                stopListening()
            } else {
                startListening()
            }
        }
    }

    /**
     * Start voice recognition.
     */
    private fun startListening() {
        if (checkPermission(Manifest.permission.RECORD_AUDIO)) {
            voiceEngine.startListening(isCommandMode = true)
            updateStatus(Status.LISTENING)
            showVoiceLevelAnimation()
        } else {
            requestPermission(Manifest.permission.RECORD_AUDIO)
        }
    }

    /**
     * Stop voice recognition.
     */
    private fun stopListening() {
        voiceEngine.stopListening()
        hideVoiceLevelAnimation()
        if (currentStatus == Status.LISTENING) {
            updateStatus(Status.IDLE)
        }
    }

    /**
     * Called when wake word "Hey Narzo" is detected.
     */
    private suspend fun onWakeWordDetected() {
        withContext(Dispatchers.Main) {
            addMessage("Hey Narzo detected! I'm listening...", false)
            updateStatus(Status.LISTENING)
            ttsEngine.speakImmediately("Yes, I'm listening")

            // Automatically start listening after wake word
            delay(500)
            if (checkPermission(Manifest.permission.RECORD_AUDIO)) {
                voiceEngine.startListening(isCommandMode = true)
            }
        }
    }

    /**
     * Called when speech is recognized from voice input.
     */
    private suspend fun onSpeechRecognized(text: String) {
        withContext(Dispatchers.Main) {
            if (text.isBlank()) return@withContext

            // Add user message to chat
            addMessage(text, true)

            // Update status to thinking
            updateStatus(Status.THINKING)

            // Process the command or send to AI
            processInput(text)
        }
    }

    /**
     * Process user input - check for commands or send to AI.
     */
    private suspend fun processInput(text: String) {
        val lowerText = text.lowercase().trim()

        // Check for specific commands first
        when {
            lowerText.startsWith("open ") -> {
                val appName = lowerText.removePrefix("open ")
                handleOpenApp(appName)
            }
            lowerText.startsWith("set alarm") || lowerText.startsWith("alarm") -> {
                handleAlarmCommand(lowerText)
            }
            lowerText.startsWith("send whatsapp") || lowerText.startsWith("whatsapp") -> {
                handleWhatsAppCommand(lowerText)
            }
            lowerText.startsWith("wifi on") || lowerText.startsWith("turn on wifi") -> {
                phoneController.setWifiEnabled(true)
                addMessage("Turning WiFi on", false)
            }
            lowerText.startsWith("wifi off") || lowerText.startsWith("turn off wifi") -> {
                phoneController.setWifiEnabled(false)
                addMessage("Turning WiFi off", false)
            }
            lowerText.startsWith("bluetooth on") || lowerText.startsWith("turn on bluetooth") -> {
                phoneController.setBluetoothEnabled(true)
                addMessage("Turning Bluetooth on", false)
            }
            lowerText.startsWith("bluetooth off") || lowerText.startsWith("turn off bluetooth") -> {
                phoneController.setBluetoothEnabled(false)
                addMessage("Turning Bluetooth off", false)
            }
            lowerText.startsWith("volume up") -> {
                phoneController.volumeUp()
                addMessage("Volume increased", false)
            }
            lowerText.startsWith("volume down") -> {
                phoneController.volumeDown()
                addMessage("Volume decreased", false)
            }
            lowerText.startsWith("mute") -> {
                phoneController.mute()
                addMessage("Volume muted", false)
            }
            lowerText.startsWith("brightness ") -> {
                val value = lowerText.removePrefix("brightness ").trim()
                val brightness = value.replace("%", "").toFloatOrNull()?.div(100f) ?: 0.5f
                phoneController.setBrightness(brightness)
                addMessage("Brightness set to ${(brightness * 100).toInt()}%", false)
            }
            lowerText.startsWith("go home") || lowerText == "home" -> {
                phoneController.goHome()
                addMessage("Going home", false)
            }
            lowerText.startsWith("go back") || lowerText == "back" -> {
                phoneController.goBack()
                addMessage("Going back", false)
            }
            lowerText.contains("battery") -> {
                val level = phoneController.getBatteryLevel()
                val response = if (level >= 0) {
                    "Your battery is at $level%"
                } else {
                    "I couldn't read the battery level"
                }
                addMessage(response, false)
                speakResponse(response)
            }
            lowerText.startsWith("read notifications") -> {
                addMessage("Checking notifications...", false)
                val notification = NarzoAccessibilityService.getInstance()?.getLastNotification()
                if (notification != null) {
                    speakResponse("Last notification: $notification")
                    addMessage("Last notification: $notification", false)
                } else {
                    speakResponse("No recent notifications found")
                    addMessage("No recent notifications found", false)
                }
            }
            lowerText.contains("camera") || lowerText.contains("look") || lowerText.contains("see") -> {
                openCamera()
            }
            lowerText.startsWith("clear chat") || lowerText.startsWith("clear history") -> {
                clearChat()
            }
            else -> {
                // Send to AI for general conversation
                getAIResponse(text)
            }
        }
    }

    /**
     * Send text to AI engine and get response.
     */
    private suspend fun getAIResponse(text: String) {
        updateStatus(Status.THINKING)

        aiEngine.sendMessage(
            message = text,
            onProgress = { partialResponse ->
                // Update UI with partial response (streaming)
            },
            onComplete = { response ->
                lifecycleScope.launch {
                    addMessage(response, false)
                    speakResponse(response)
                    updateStatus(Status.IDLE)
                }
            }
        )
    }

    /**
     * Handle "open app" command.
     */
    private fun handleOpenApp(appName: String) {
        val success = phoneController.openAppByName(appName)
        val response = if (success) {
            "Opening $appName"
        } else {
            "I couldn't find the app '$appName'. Please check the name."
        }
        addMessage(response, false)
        speakResponse(response)
    }

    /**
     * Handle alarm setting command.
     * Expected format: "set alarm at 7:30 AM" or "alarm at 14:30"
     */
    private fun handleAlarmCommand(text: String) {
        // Simple time parsing
        val timePattern = Regex("(\\d{1,2})[: ](\\d{2})")
        val match = timePattern.find(text)

        if (match != null) {
            var hour = match.groupValues[1].toInt()
            val minute = match.groupValues[2].toInt()

            // Handle AM/PM
            if (text.contains("pm") && hour < 12) hour += 12
            if (text.contains("am") && hour == 12) hour = 0

            val success = phoneController.setAlarm(hour, minute)
            val response = if (success) {
                "Alarm set for ${String.format("%02d:%02d", hour, minute)}"
            } else {
                "Failed to set alarm. Please check the clock app."
            }
            addMessage(response, false)
            speakResponse(response)
        } else {
            val response = "Please specify a time. For example: 'Set alarm at 7:30 AM'"
            addMessage(response, false)
            speakResponse(response)
        }
    }

    /**
     * Handle WhatsApp message command.
     * Expected format: "send whatsapp to +1234567890 Hello there"
     */
    private fun handleWhatsAppCommand(text: String) {
        // Extract phone number
        val phonePattern = Regex("(\\+?\\d{10,13})")
        val match = phonePattern.find(text)

        if (match != null) {
            val phoneNumber = match.groupValues[1]
            val message = text
                .replace(Regex("send whatsapp|whatsapp|to|${phoneNumber}"), "")
                .trim()
                .replace(Regex("\\s+"), " ")

            phoneController.sendWhatsAppMessage(phoneNumber, message)
            addMessage("Opening WhatsApp to send message to $phoneNumber", false)
            speakResponse("Opening WhatsApp")
        } else {
            val response = "Please provide a phone number. For example: 'Send WhatsApp to +1234567890 Hello'"
            addMessage(response, false)
            speakResponse(response)
        }
    }

    /**
     * Open the camera activity for AI vision features.
     */
    private fun openCamera() {
        if (checkPermission(Manifest.permission.CAMERA)) {
            val intent = Intent(this, CameraActivity::class.java)
            startActivity(intent)
        } else {
            requestPermission(Manifest.permission.CAMERA)
        }
    }

    /**
     * Speak a response using TTS.
     */
    private fun speakResponse(text: String) {
        if (ttsEngine.isReady()) {
            updateStatus(Status.SPEAKING)
            ttsEngine.speakImmediately(text)
        }
    }

    /**
     * Add a message to the chat UI.
     */
    private fun addMessage(text: String, isUser: Boolean) {
        val message = ChatMessage(text, isUser)
        messages.add(message)
        chatAdapter.notifyItemInserted(messages.size - 1)
        chatRecyclerView.smoothScrollToPosition(messages.size - 1)
    }

    /**
     * Add welcome message when app starts.
     */
    private fun addWelcomeMessage() {
        val welcome = "Hello! I'm NarzoAI, your offline AI assistant. " +
                "Press and hold the mic button to speak, or type your message. " +
                "I can help with tasks, answer questions, and control your phone."
        addMessage(welcome, false)

        Handler(Looper.getMainLooper()).postDelayed({
            val tip = "Try saying: 'Hey Narzo, open WhatsApp' or 'Set alarm at 7:30 AM'"
            addMessage(tip, false)
        }, 1000)
    }

    /**
     * Clear the chat history.
     */
    private fun clearChat() {
        messages.clear()
        chatAdapter.notifyDataSetChanged()
        aiEngine.clearChatHistory()
        addWelcomeMessage()
    }

    /**
     * Update the status indicator.
     */
    private fun updateStatus(status: Status) {
        currentStatus = status
        lifecycleScope.launch {
            when (status) {
                Status.IDLE -> {
                    statusIndicator.text = "Ready"
                    statusDot.setBackgroundResource(R.drawable.status_idle)
                }
                Status.LISTENING -> {
                    statusIndicator.text = "Listening..."
                    statusDot.setBackgroundResource(R.drawable.status_listening)
                }
                Status.PROCESSING -> {
                    statusIndicator.text = "Processing..."
                    statusDot.setBackgroundResource(R.drawable.status_processing)
                }
                Status.THINKING -> {
                    statusIndicator.text = "Thinking..."
                    statusDot.setBackgroundResource(R.drawable.status_thinking)
                }
                Status.SPEAKING -> {
                    statusIndicator.text = "Speaking..."
                    statusDot.setBackgroundResource(R.drawable.status_speaking)
                }
                Status.ERROR -> {
                    statusIndicator.text = "Error"
                    statusDot.setBackgroundResource(R.drawable.status_error)
                }
            }
        }
    }

    /**
     * Update status text directly.
     */
    private fun updateStatus(text: String) {
        statusIndicator.text = text
    }

    /**
     * Show voice level animation bar when listening.
     */
    private fun showVoiceLevelAnimation() {
        voiceLevelBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            while (voiceEngine.isListening.value) {
                val level = voiceEngine.voiceLevel.value
                val progress = (level / 1000f * 100).toInt().coerceIn(0, 100)
                voiceLevelBar.progress = progress
                delay(50) // Update every 50ms
            }
        }
    }

    /**
     * Hide voice level animation.
     */
    private fun hideVoiceLevelAnimation() {
        voiceLevelBar.visibility = View.GONE
        voiceLevelBar.progress = 0
    }

    /**
     * Show progress overlay during model loading.
     */
    private fun showProgressOverlay(text: String) {
        progressOverlay.visibility = View.VISIBLE
        progressText.text = text
        progressBar.isIndeterminate = true
    }

    /**
     * Hide progress overlay.
     */
    private fun hideProgressOverlay() {
        progressOverlay.visibility = View.GONE
    }

    /**
     * Show error message.
     */
    private fun showError(message: String) {
        updateStatus(Status.ERROR)
        Snackbar.make(mainLayout, message, Snackbar.LENGTH_LONG)
            .setAction("Dismiss") { }
            .show()
    }

    /**
     * Show dialog when model files are missing.
     */
    private fun showModelError() {
        AlertDialog.Builder(this)
            .setTitle("AI Model Not Found")
            .setMessage("The AI model files are missing. Please download Gemma 2B GGUF from HuggingFace and place it in the app's files directory.")
            .setPositiveButton("Open Settings") { _, _ ->
                val intent = Intent(this, SettingsActivity::class.java)
                startActivity(intent)
            }
            .setNegativeButton("Later") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    /**
     * Check if a permission is granted.
     */
    private fun checkPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) ==
                PackageManager.PERMISSION_GRANTED
    }

    /**
     * Request a single permission.
     */
    private fun requestPermission(permission: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(arrayOf(permission), PERMISSION_REQUEST_CODE)
        }
    }

    /**
     * Request all required permissions.
     */
    private fun checkPermissions() {
        val permissions = mutableListOf<String>()

        if (!checkPermission(Manifest.permission.RECORD_AUDIO)) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (!checkPermission(Manifest.permission.CAMERA)) {
            permissions.add(Manifest.permission.CAMERA)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !checkPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !checkPermission(Manifest.permission.BLUETOOTH_CONNECT)
        ) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (permissions.isNotEmpty()) {
            requestPermissions(permissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            val deniedPermissions = permissions.filterIndexed { index, _ ->
                grantResults[index] == PackageManager.PERMISSION_DENIED
            }

            if (deniedPermissions.isNotEmpty()) {
                showError("Some permissions were denied: ${deniedPermissions.joinToString(", ")}")
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                val intent = Intent(this, SettingsActivity::class.java)
                startActivity(intent)
                true
            }
            R.id.action_clear_chat -> {
                clearChat()
                true
            }
            R.id.action_camera -> {
                openCamera()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        // Reinitialize engines if needed
    }

    override fun onPause() {
        super.onPause()
        // Unload heavy resources when app is in background (4GB RAM optimization)
        voiceEngine.stopListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Release all resources
        voiceEngine.release()
        aiEngine.release()
        ttsEngine.shutdown()
        cameraAI.release()
    }
}
