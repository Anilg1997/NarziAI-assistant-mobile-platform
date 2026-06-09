package com.narzoai.assistant

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * SettingsActivity - Settings screen for NarzoAI Assistant.
 *
 * Allows users to:
 * - View app and model information
 * - Check and request permissions
 * - Configure accessibility service
 * - Adjust TTS settings
 * - Download model files (info page)
 * - Enable/disable wake word detection
 * - View storage usage
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "NarzoAI_Settings"
    }

    private lateinit var aiEngine: AIEngine
    private lateinit var ttsEngine: TTSEngine
    private lateinit var phoneController: PhoneController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Initialize engines
        aiEngine = AIEngine(this)
        ttsEngine = TTSEngine(this)
        phoneController = PhoneController(this)

        // Setup toolbar
        setSupportActionBar(findViewById(R.id.toolbar_settings))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"

        // Initialize UI components
        initializeSettingsUI()
    }

    private fun initializeSettingsUI() {
        // Model Information Section
        setupModelInfoSection()

        // Permissions Section
        setupPermissionsSection()

        // Accessibility Service Section
        setupAccessibilitySection()

        // TTS Settings Section
        setupTTSSettings()

        // Wake Word Section
        setupWakeWordSection()

        // App Information Section
        setupAppInfoSection()

        // Model Download Guide
        setupModelDownloadGuide()
    }

    /**
     * Show model information and status.
     */
    private fun setupModelInfoSection() {
        val modelStatusText = findViewById<TextView>(R.id.model_status_text)
        val modelSizeText = findViewById<TextView>(R.id.model_size_text)
        val modelCard = findViewById<View>(R.id.model_info_card)

        // Check AI model status
        val modelInfo = aiEngine.getModelInfo()
        if (modelInfo != null) {
            modelStatusText.text = "Model Ready"
            modelStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            modelSizeText.text = "${modelInfo.fileSizeMB}MB"
        } else {
            modelStatusText.text = "Not Downloaded"
            modelStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))
            modelSizeText.text = "-"
        }

        modelCard.setOnClickListener {
            showModelDetailsDialog()
        }
    }

    /**
     * Setup permission check and request UI.
     */
    private fun setupPermissionsSection() {
        // Record Audio
        setupPermissionItem(
            R.id.permission_microphone,
            R.id.permission_microphone_switch,
            android.Manifest.permission.RECORD_AUDIO,
            "Microphone for voice recognition"
        )

        // Camera
        setupPermissionItem(
            R.id.permission_camera,
            R.id.permission_camera_switch,
            android.Manifest.permission.CAMERA,
            "Camera for AI vision features"
        )

        // Notifications
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setupPermissionItem(
                R.id.permission_notifications,
                R.id.permission_notifications_switch,
                android.Manifest.permission.POST_NOTIFICATIONS,
                "Notifications for reading alerts aloud"
            )
        }

        // Bluetooth
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setupPermissionItem(
                R.id.permission_bluetooth,
                R.id.permission_bluetooth_switch,
                android.Manifest.permission.BLUETOOTH_CONNECT,
                "Bluetooth control"
            )
        }

        // Overlay permission
        val overlaySwitch = findViewById<Switch>(R.id.permission_overlay_switch)
        overlaySwitch?.isChecked = Settings.canDrawOverlays(this)
        overlaySwitch?.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }
    }

    /**
     * Setup individual permission toggle.
     */
    private fun setupPermissionItem(
        textViewId: Int,
        switchId: Int,
        permission: String,
        description: String
    ) {
        val switchView = findViewById<Switch>(switchId)

        switchView?.apply {
            isChecked = ContextCompat.checkSelfPermission(
                this@SettingsActivity, permission
            ) == PackageManager.PERMISSION_GRANTED

            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        requestPermissions(arrayOf(permission), 1)
                    }
                }
            }
        }
    }

    /**
     * Setup accessibility service section.
     */
    private fun setupAccessibilitySection() {
        val accessibilitySwitch = findViewById<Switch>(R.id.accessibility_switch)
        val accessibilityStatus = findViewById<TextView>(R.id.accessibility_status)

        // Check if accessibility service is enabled
        val isAccessibilityEnabled = isAccessibilityServiceEnabled()
        accessibilitySwitch?.isChecked = isAccessibilityEnabled
        accessibilityStatus?.text = if (isAccessibilityEnabled) "Enabled" else "Disabled"

        accessibilitySwitch?.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !isAccessibilityEnabled) {
                // Open accessibility settings
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            }
        }
    }

    /**
     * Check if the accessibility service is enabled.
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = "$packageName/.NarzoAccessibilityService"
        try {
            val enabledServices = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabledServices.split(":").contains(service)
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Setup TTS settings section.
     */
    private fun setupTTSSettings() {
        val ttsRateSeekBar = findViewById<SeekBar>(R.id.tts_rate_seekbar)
        val ttsPitchSeekBar = findViewById<SeekBar>(R.id.tts_pitch_seekbar)
        val ttsTestButton = findViewById<Button>(R.id.tts_test_button)

        // Speech rate (0.5x to 2.0x)
        ttsRateSeekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val rate = 0.5f + (progress / 100f) * 1.5f
                ttsEngine.setSpeechRate(rate)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Speech pitch (0.5x to 2.0x)
        ttsPitchSeekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val pitch = 0.5f + (progress / 100f) * 1.5f
                ttsEngine.setSpeechPitch(pitch)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Test TTS
        ttsTestButton?.setOnClickListener {
            ttsEngine.initialize(
                onReady = {
                    lifecycleScope.launch {
                        ttsEngine.speakImmediately("Hello! This is Narzo AI Assistant. I am ready to help you.")
                    }
                },
                onError = { error ->
                    Toast.makeText(this, "TTS error: $error", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    /**
     * Setup wake word detection settings.
     */
    private fun setupWakeWordSection() {
        val wakeWordSwitch = findViewById<Switch>(R.id.wake_word_switch)
        val wakeWordStatus = findViewById<TextView>(R.id.wake_word_status)

        wakeWordSwitch?.setOnCheckedChangeListener { _, isChecked ->
            wakeWordStatus?.text = if (isChecked) "Enabled - Say 'Hey Narzo'" else "Disabled"
            // In a full implementation, this would start/stop continuous listening
        }
    }

    /**
     * Setup app information section.
     */
    private fun setupAppInfoSection() {
        val versionText = findViewById<TextView>(R.id.app_version_text)
        val buildText = findViewById<TextView>(R.id.app_build_text)

        try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            versionText?.text = packageInfo.versionName ?: "1.0.0"
            buildText?.text = packageInfo.versionCode.toString()
        } catch (e: Exception) {
            versionText?.text = "1.0.0"
            buildText?.text = "1"
        }

        findViewById<View>(R.id.licenses_card)?.setOnClickListener {
            showLicensesDialog()
        }

        findViewById<View>(R.id.privacy_card)?.setOnClickListener {
            showPrivacyDialog()
        }
    }

    /**
     * Setup model download guide section.
     */
    private fun setupModelDownloadGuide() {
        findViewById<View>(R.id.download_gemma_card)?.setOnClickListener {
            val url = "https://huggingface.co/google/gemma-2b-GGUF"
            openLink(url)
        }

        findViewById<View>(R.id.download_whisper_card)?.setOnClickListener {
            val url = "https://huggingface.co/ggerganov/whisper.cpp"
            openLink(url)
        }
    }

    /**
     * Open a URL in the browser.
     */
    private fun openLink(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open browser", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Show model details dialog.
     */
    private fun showModelDetailsDialog() {
        val modelInfo = aiEngine.getModelInfo()
        val message = if (modelInfo != null) {
            """
                Model: ${modelInfo.fileName}
                Size: ${modelInfo.fileSizeMB}MB
                Path: ${modelInfo.path}
                
                Status: Loaded and ready
            """.trimIndent()
        } else {
            """
                Model not found.
                
                To download:
                1. Visit HuggingFace
                2. Download Gemma 2B GGUF
                3. Place in app files directory
            """.trimIndent()
        }

        AlertDialog.Builder(this)
            .setTitle("AI Model Details")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    /**
     * Show licenses dialog.
     */
    private fun showLicensesDialog() {
        AlertDialog.Builder(this)
            .setTitle("Open Source Licenses")
            .setMessage(
                """
                NarzoAI Assistant uses the following open source components:
                
                - Gemma 2B: Google (Apache 2.0)
                - Whisper Tiny: OpenAI (MIT)
                - llama.cpp: ggerganov (MIT)
                - whisper.cpp: ggerganov (MIT)
                - ML Kit: Google (Apache 2.0)
                - AndroidX: Google (Apache 2.0)
                
                This application is MIT licensed.
                """.trimIndent()
            )
            .setPositiveButton("OK", null)
            .show()
    }

    /**
     * Show privacy dialog.
     */
    private fun showPrivacyDialog() {
        AlertDialog.Builder(this)
            .setTitle("Privacy Policy")
            .setMessage(
                """
                NarzoAI Assistant is designed to work completely offline.
                
                - All AI processing happens on-device
                - No data is sent to external servers
                - Voice recordings are processed locally and discarded
                - Camera data is processed on-device only
                - No analytics or tracking
                - No internet connection required
                """.trimIndent()
            )
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        ttsEngine.shutdown()
    }
}
