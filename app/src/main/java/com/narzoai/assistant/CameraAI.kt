package com.narzoai.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * CameraAI - Handles camera-based AI features using Google ML Kit.
 *
 * Features:
 * - Object Detection: Detect and label objects in real-time camera feed
 * - Text Recognition: Extract text from camera view (OCR)
 * - Barcode Scanning: Read barcodes and QR codes
 *
 * ML Kit runs entirely on-device, no internet required.
 */
class CameraAI(private val context: Context) {

    companion object {
        private const val TAG = "NarzoAI_CameraAI"
    }

    // ML Kit detectors (lazy initialized to save memory)
    private var objectDetector: com.google.mlkit.vision.objects.ObjectDetector? = null
    private var textRecognizer: com.google.mlkit.vision.text.TextRecognizer? = null

    // Camera state
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null

    // Analysis results callbacks
    private var onObjectDetected: ((List<DetectedObject>, Bitmap?) -> Unit)? = null
    private var onTextRecognized: ((String, Bitmap?) -> Unit)? = null
    private var onDetectionError: ((String) -> Unit)? = null

    // Camera executor for background processing
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    // Detection mode
    private var currentMode = DetectionMode.OBJECT_DETECTION

    enum class DetectionMode {
        OBJECT_DETECTION,
        TEXT_RECOGNITION,
        DISABLED
    }

    /**
     * Initialize ML Kit detectors.
     * Detectors are loaded lazily to save memory on 4GB RAM devices.
     */
    private fun initObjectDetector() {
        if (objectDetector != null) return

        try {
            val options = ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
                .enableMultipleObjects()
                .enableClassification()
                .build()

            objectDetector = ObjectDetection.getClient(options)
            Log.d(TAG, "Object detector initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize object detector", e)
            onDetectionError?.invoke("Failed to initialize object detection")
        }
    }

    /**
     * Initialize ML Kit text recognizer.
     */
    private fun initTextRecognizer() {
        if (textRecognizer != null) return

        try {
            textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            Log.d(TAG, "Text recognizer initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize text recognizer", e)
            onDetectionError?.invoke("Failed to initialize text recognition")
        }
    }

    /**
     * Start the camera for AI analysis.
     *
     * @param lifecycleOwner The lifecycle owner (usually the Activity)
     * @param previewView The PreviewView to display camera feed
     * @param mode Detection mode (object detection or text recognition)
     */
    fun startCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: androidx.camera.view.PreviewView,
        mode: DetectionMode = DetectionMode.OBJECT_DETECTION
    ) {
        currentMode = mode
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()

                // Preview
                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                // Image analysis for ML Kit
                val imageAnalyzer = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480)) // Lower resolution for 4GB RAM
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalyzer.setAnalyzer(cameraExecutor) { imageProxy ->
                    processImageProxy(imageProxy)
                }

                // Image capture (for still images)
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                // Select camera
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                // Bind to lifecycle
                camera = cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalyzer,
                    imageCapture
                )

                Log.d(TAG, "Camera started successfully")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start camera", e)
                onDetectionError?.invoke("Failed to start camera: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Process camera image through ML Kit based on current mode.
     */
    private fun processImageProxy(imageProxy: ImageProxy) {
        when (currentMode) {
            DetectionMode.OBJECT_DETECTION -> detectObjects(imageProxy)
            DetectionMode.TEXT_RECOGNITION -> recognizeText(imageProxy)
            DetectionMode.DISABLED -> imageProxy.close()
        }
    }

    /**
     * Detect objects in the camera frame using ML Kit.
     */
    private fun detectObjects(imageProxy: ImageProxy) {
        initObjectDetector()

        @androidx.camera.core.ExperimentalGetImage val mediaImage = imageProxy.image
        if (mediaImage == null || objectDetector == null) {
            imageProxy.close()
            return
        }

        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        objectDetector!!.process(inputImage)
            .addOnSuccessListener { detectedObjects ->
                if (detectedObjects.isNotEmpty()) {
                    // Convert ImageProxy to Bitmap for display
                    val bitmap = imageProxyToBitmap(imageProxy)

                    // Summarize detections
                    val descriptions = detectedObjects.map { obj ->
                        val labels = obj.labels.joinToString(", ") { label ->
                            "${label.text} (${"%.1f".format(label.confidence * 100)}%)"
                        }
                        "Object: $labels"
                    }

                    Log.d(TAG, "Detected objects: ${descriptions.joinToString(" | ")}")
                    onObjectDetected?.invoke(detectedObjects, bitmap)
                }
                imageProxy.close()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Object detection failed", e)
                imageProxy.close()
            }
    }

    /**
     * Recognize text in the camera frame using ML Kit.
     */
    private fun recognizeText(imageProxy: ImageProxy) {
        initTextRecognizer()

        @androidx.camera.core.ExperimentalGetImage val mediaImage = imageProxy.image
        if (mediaImage == null || textRecognizer == null) {
            imageProxy.close()
            return
        }

        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        textRecognizer!!.process(inputImage)
            .addOnSuccessListener { visionText ->
                val extractedText = visionText.text

                if (extractedText.isNotBlank()) {
                    val bitmap = imageProxyToBitmap(imageProxy)
                    Log.d(TAG, "Text recognized: ${extractedText.take(100)}")
                    onTextRecognized?.invoke(extractedText, bitmap)
                }
                imageProxy.close()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Text recognition failed", e)
                imageProxy.close()
            }
    }

    /**
     * Convert ImageProxy to Bitmap for display/cropping.
     * This is memory-intensive, so we only do it when detections are found.
     */
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            val buffer = imageProxy.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            bitmap?.let {
                val matrix = android.graphics.Matrix()
                matrix.postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                Bitmap.createBitmap(it, 0, 0, it.width, it.height, matrix, true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert ImageProxy to Bitmap", e)
            null
        }
    }

    /**
     * Set the detection mode.
     */
    fun setDetectionMode(mode: DetectionMode) {
        currentMode = mode
        Log.d(TAG, "Detection mode changed to: $mode")
    }

    /**
     * Get the current detection mode.
     */
    fun getCurrentMode(): DetectionMode = currentMode

    /**
     * Set callbacks for detection results.
     */
    fun setDetectionCallbacks(
        onObject: ((List<DetectedObject>, Bitmap?) -> Unit)? = null,
        onText: ((String, Bitmap?) -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        onObjectDetected = onObject
        onTextRecognized = onText
        onDetectionError = onError
    }

    /**
     * Stop the camera and release resources.
     */
    fun stopCamera() {
        try {
            cameraProvider?.unbindAll()
            cameraProvider = null
            camera = null
            Log.d(TAG, "Camera stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping camera", e)
        }
    }

    /**
     * Release all ML Kit resources.
     */
    fun release() {
        stopCamera()
        cameraExecutor.shutdown()

        try {
            objectDetector?.close()
            textRecognizer?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing detectors", e)
        }

        objectDetector = null
        textRecognizer = null
        Log.d(TAG, "CameraAI resources released")
    }

    /**
     * Check if device has a camera.
     */
    fun hasCamera(): Boolean {
        return context.packageManager.hasSystemFeature(
            android.content.pm.PackageManager.FEATURE_CAMERA_ANY
        )
    }

    /**
     * Get a description of detected objects for voice output.
     */
    fun describeDetectedObjects(objects: List<DetectedObject>): String {
        if (objects.isEmpty()) return "No objects detected"

        val descriptions = mutableListOf<String>()
        objects.forEach { obj ->
            val labels = obj.labels
            if (labels.isNotEmpty()) {
                val topLabel = labels.maxByOrNull { it.confidence }
                descriptions.add(topLabel?.text ?: "unknown object")
            } else {
                descriptions.add("object at position ${obj.boundingBox.centerX()}, ${obj.boundingBox.centerY()}")
            }
        }

        return "I can see: ${descriptions.joinToString(", ")}"
    }

    /**
     * Detect objects in a static bitmap (not live camera).
     * Use this for analyzing captured images.
     */
    fun detectInBitmap(bitmap: Bitmap, onComplete: (List<DetectedObject>) -> Unit) {
        initObjectDetector()

        val inputImage = InputImage.fromBitmap(bitmap, 0)

        objectDetector!!.process(inputImage)
            .addOnSuccessListener { objects ->
                onComplete(objects)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Bitmap detection failed", e)
                onComplete(emptyList())
            }
    }

    /**
     * Recognize text in a static bitmap.
     */
    fun recognizeTextInBitmap(bitmap: Bitmap, onComplete: (String) -> Unit) {
        initTextRecognizer()

        val inputImage = InputImage.fromBitmap(bitmap, 0)

        textRecognizer!!.process(inputImage)
            .addOnSuccessListener { visionText ->
                onComplete(visionText.text)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Bitmap text recognition failed", e)
                onComplete("")
            }
    }
}
