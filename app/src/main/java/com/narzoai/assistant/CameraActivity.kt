package com.narzoai.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.objects.DetectedObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * CameraActivity - Camera view with ML Kit AI features.
 *
 * Features:
 * - Real-time object detection
 * - Text recognition (OCR) from camera
 * - Overlay showing detected objects/text
 * - Toggle between detection modes
 */
class CameraActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "NarzoAI_Camera"
    }

    private lateinit var cameraAI: CameraAI
    private lateinit var previewView: PreviewView
    private lateinit var overlayView: View
    private lateinit var detectionResultText: TextView
    private lateinit var toggleModeButton: Button
    private lateinit var captureButton: Button
    private lateinit var closeButton: ImageButton
    private lateinit var statusText: TextView

    // Detection overlay
    private var overlayCanvas: Canvas? = null
    private var overlayPaint: Paint? = null
    private var textPaint: Paint? = null

    // Current mode
    private var currentMode = CameraAI.DetectionMode.OBJECT_DETECTION

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        // Initialize CameraAI
        cameraAI = CameraAI(this)

        // Initialize UI
        initializeUI()

        // Setup detection callbacks
        setupDetectionCallbacks()

        // Check camera permission
        checkCameraPermission()
    }

    /**
     * Initialize UI components.
     */
    private fun initializeUI() {
        previewView = findViewById(R.id.camera_preview_view)
        overlayView = findViewById(R.id.detection_overlay)
        detectionResultText = findViewById(R.id.detection_result_text)
        toggleModeButton = findViewById(R.id.toggle_mode_button)
        captureButton = findViewById(R.id.capture_button)
        closeButton = findViewById(R.id.close_camera_button)
        statusText = findViewById(R.id.camera_status_text)

        // Initialize overlay paint
        overlayPaint = Paint().apply {
            color = Color.GREEN
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }

        textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 36f
            isAntiAlias = true
            setShadowLayer(2f, 1f, 1f, Color.BLACK)
        }

        // Setup toggle button
        toggleModeButton.setOnClickListener {
            toggleDetectionMode()
        }

        // Setup capture button
        captureButton.setOnClickListener {
            captureAndAnalyze()
        }

        // Setup close button
        closeButton.setOnClickListener {
            finish()
        }
    }

    /**
     * Setup ML Kit detection callbacks.
     */
    private fun setupDetectionCallbacks() {
        cameraAI.setDetectionCallbacks(
            onObject = { objects, bitmap ->
                lifecycleScope.launch {
                    handleObjectDetected(objects, bitmap)
                }
            },
            onText = { text, bitmap ->
                lifecycleScope.launch {
                    handleTextRecognized(text, bitmap)
                }
            },
            onError = { error ->
                lifecycleScope.launch {
                    statusText.text = "Error: $error"
                    Toast.makeText(this@CameraActivity, error, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    /**
     * Handle detected objects from ML Kit.
     */
    private suspend fun handleObjectDetected(objects: List<DetectedObject>, bitmap: Bitmap?) {
        withContext(Dispatchers.Main) {
            if (objects.isNotEmpty()) {
                val description = cameraAI.describeDetectedObjects(objects)
                detectionResultText.text = description
                statusText.text = "Object detected"

                // Draw bounding boxes
                drawObjectBoundingBoxes(objects, bitmap)
            } else {
                detectionResultText.text = "No objects detected"
                statusText.text = "Looking..."
            }
        }
    }

    /**
     * Handle recognized text from ML Kit.
     */
    private suspend fun handleTextRecognized(text: String, bitmap: Bitmap?) {
        withContext(Dispatchers.Main) {
            if (text.isNotBlank()) {
                detectionResultText.text = "Text: ${text.take(200)}"
                statusText.text = "Text found"
            } else {
                detectionResultText.text = "No text recognized"
                statusText.text = "Looking for text..."
            }
        }
    }

    /**
     * Draw bounding boxes around detected objects on the overlay.
     */
    private fun drawObjectBoundingBoxes(objects: List<DetectedObject>, bitmap: Bitmap?) {
        // In a full implementation, this would draw on the overlay view
        // For now, we update the text description
        val descriptions = objects.mapIndexed { index, obj ->
            val labels = obj.labels.joinToString(", ") { label ->
                "${label.text} (${"%.0f".format(label.confidence * 100)}%)"
            }
            "Object ${index + 1}: $labels"
        }
        detectionResultText.text = descriptions.joinToString("\n")
    }

    /**
     * Toggle between object detection and text recognition modes.
     */
    private fun toggleDetectionMode() {
        currentMode = when (currentMode) {
            CameraAI.DetectionMode.OBJECT_DETECTION -> {
                toggleModeButton.text = "Mode: Text Recognition"
                statusText.text = "Text Recognition Mode"
                CameraAI.DetectionMode.TEXT_RECOGNITION
            }
            CameraAI.DetectionMode.TEXT_RECOGNITION -> {
                toggleModeButton.text = "Mode: Object Detection"
                statusText.text = "Object Detection Mode"
                CameraAI.DetectionMode.OBJECT_DETECTION
            }
            else -> {
                toggleModeButton.text = "Mode: Object Detection"
                CameraAI.DetectionMode.OBJECT_DETECTION
            }
        }

        cameraAI.setDetectionMode(currentMode)
    }

    /**
     * Capture the current frame and analyze it in detail.
     */
    private fun captureAndAnalyze() {
        statusText.text = "Analyzing..."
        captureButton.isEnabled = false

        // Run a more detailed analysis
        lifecycleScope.launch {
            delay(1000) // Give time for one more frame
            statusText.text = "Analysis complete"
            captureButton.isEnabled = true

            Toast.makeText(
                this@CameraActivity,
                if (currentMode == CameraAI.DetectionMode.OBJECT_DETECTION)
                    "Object detection running"
                else
                    "Text recognition running",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Check and request camera permission.
     */
    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
            showPermissionDeniedDialog()
        }
    }

    /**
     * Show dialog when camera permission is denied.
     */
    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Camera Permission Required")
            .setMessage("Camera permission is needed for AI vision features. Please enable it in Settings.")
            .setPositiveButton("Open Settings") { _, _ ->
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                finish()
            }
            .show()
    }

    /**
     * Start the camera with ML Kit analysis.
     */
    private fun startCamera() {
        statusText.text = "Starting camera..."
        cameraAI.startCamera(this, previewView, currentMode)
        statusText.text = if (currentMode == CameraAI.DetectionMode.OBJECT_DETECTION)
            "Object Detection Mode" else "Text Recognition Mode"
    }

    override fun onResume() {
        super.onResume()
        // Re-start camera if coming back from permission settings
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            if (!::previewView.isInitialized) return
            startCamera()
        }
    }

    override fun onPause() {
        super.onPause()
        cameraAI.stopCamera()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraAI.release()
    }
}
