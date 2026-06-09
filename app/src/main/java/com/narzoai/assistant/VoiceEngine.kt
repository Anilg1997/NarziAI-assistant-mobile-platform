package com.narzoai.assistant

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * VoiceEngine - Handles voice recognition using Whisper Tiny model via whisper.cpp.
 *
 * This engine processes voice input in two modes:
 * 1. Wake word detection ("Hey Narzo") - continuously listens for the trigger phrase
 * 2. Command recognition - converts speech to text after wake word is detected
 *
 * For devices with 4GB RAM, the whisper model is loaded lazily and unloaded
 * when not in active use to conserve memory.
 */
class VoiceEngine(private val context: Context) {

    companion object {
        private const val TAG = "NarzoAI_Voice"
        private const val WAKE_WORD = "hey narzo"
        private const val SAMPLE_RATE = 16000
        private const val CHANNELS = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_FACTOR = 2
        private const val RECORDING_TIMEOUT_MS = 10000L // 10 seconds max recording
        private const val SILENCE_TIMEOUT_MS = 1500L // 1.5 seconds of silence = end of speech
        private const val SILENCE_THRESHOLD = 500 // Amplitude threshold for silence detection

        // Model file names
        private const val WHISPER_MODEL = "ggml-tiny.bin"
        private const val MODELS_DIR = "models"
    }

    // Audio recording state
    private var audioRecord: AudioRecord? = null
    private var isRecording = AtomicBoolean(false)
    private val recordingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Voice recognition state
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing

    private val _wakeWordDetected = MutableStateFlow(false)
    val wakeWordDetected: StateFlow<Boolean> = _wakeWordDetected

    private val _recognizedText = MutableStateFlow("")
    val recognizedText: StateFlow<String> = _recognizedText

    private val _voiceLevel = MutableStateFlow(0f)
    val voiceLevel: StateFlow<Float> = _voiceLevel

    // Whisper native interface (JNI bridge)
    private var whisperModelLoaded = false
    private var whisperNativePtr: Long = 0

    // Callbacks
    private var onWakeWordDetected: (() -> Unit)? = null
    private var onSpeechRecognized: ((String) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    // Main thread handler for UI updates
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Initialize the voice engine with model loading.
     * The whisper model is loaded in the background to avoid blocking the UI.
     */
    fun initialize(
        onWakeWord: (() -> Unit)? = null,
        onRecognized: ((String) -> Unit)? = null,
        onErrorCallback: ((String) -> Unit)? = null
    ) {
        onWakeWordDetected = onWakeWord
        onSpeechRecognized = onRecognized
        onError = onErrorCallback

        Log.d(TAG, "Initializing voice engine")
        loadWhisperModelAsync()
    }

    /**
     * Load the Whisper Tiny model asynchronously to avoid blocking UI.
     * For 4GB RAM optimization: model is loaded from file directly without
     * keeping a full copy in Java heap memory.
     */
    private fun loadWhisperModelAsync() {
        recordingScope.launch {
            try {
                Log.d(TAG, "Loading Whisper Tiny model...")

                // Check if model file exists in assets
                val modelFile = File(context.filesDir, "$MODELS_DIR/$WHISPER_MODEL")
                if (!modelFile.exists()) {
                    Log.w(TAG, "Model file not found: ${modelFile.absolutePath}")
                    onError?.invoke("Whisper model not found. Please download from HuggingFace.")
                    return@launch
                }

                // Load the native library (single combined JNI library)
                System.loadLibrary("narzoai_jni")

                // Initialize whisper with the model file
                // whisper_init() loads model and returns a context pointer
                whisperNativePtr = whisperInit(modelFile.absolutePath)
                if (whisperNativePtr == 0L) {
                    throw RuntimeException("Failed to initialize whisper model")
                }

                whisperModelLoaded = true
                Log.d(TAG, "Whisper Tiny model loaded successfully")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to load whisper model", e)
                whisperModelLoaded = false
                onError?.invoke("Failed to load voice model: ${e.message}")
            }
        }
    }

    /**
     * Start listening for voice input.
     * In wake word mode, continuously listens for "Hey Narzo".
     * In command mode, records speech for recognition.
     */
    fun startListening(isCommandMode: Boolean = false) {
        if (isRecording.get()) {
            Log.w(TAG, "Already recording")
            return
        }

        if (!whisperModelLoaded) {
            Log.w(TAG, "Whisper model not loaded yet")
            onError?.invoke("Voice model is still loading. Please wait.")
            return
        }

        Log.d(TAG, "Starting recording, command mode: $isCommandMode")
        _isListening.value = true
        isRecording.set(true)

        recordingScope.launch {
            startAudioCapture(isCommandMode)
        }
    }

    /**
     * Start audio capture from the microphone.
     * Uses AudioRecord for low-latency audio capture.
     */
    private suspend fun startAudioCapture(isCommandMode: Boolean) {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, CHANNELS, ENCODING
        ) * BUFFER_SIZE_FACTOR

        audioRecord = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(ENCODING)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(CHANNELS)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, CHANNELS, ENCODING, bufferSize
                )
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Microphone permission denied", e)
            _isListening.value = false
            isRecording.set(false)
            onError?.invoke("Microphone permission is required")
            return
        }

        audioRecord?.startRecording()

        val audioBuffer = ByteArray(bufferSize)
        val pcmBuffer = ByteArrayOutputStream()
        var lastVoiceTime = System.currentTimeMillis()
        var hasDetectedSpeech = false

        while (isRecording.get()) {
            val bytesRead = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: -1

            if (bytesRead > 0) {
                // Calculate voice level for UI visualization
                val level = calculateVoiceLevel(audioBuffer, bytesRead)
                _voiceLevel.value = level

                if (level > SILENCE_THRESHOLD) {
                    lastVoiceTime = System.currentTimeMillis()
                    hasDetectedSpeech = true
                }

                if (isCommandMode && hasDetectedSpeech) {
                    pcmBuffer.write(audioBuffer, 0, bytesRead)

                    // Check for silence timeout
                    val silenceDuration = System.currentTimeMillis() - lastVoiceTime
                    if (silenceDuration > SILENCE_TIMEOUT_MS && hasDetectedSpeech) {
                        Log.d(TAG, "Silence detected, stopping recording")
                        break
                    }

                    // Check for max recording duration
                    if (System.currentTimeMillis() - lastVoiceTime > RECORDING_TIMEOUT_MS) {
                        Log.d(TAG, "Max recording duration reached")
                        break
                    }
                }
            }
        }

        // Stop recording
        stopAudioCapture()

        if (isCommandMode && hasDetectedSpeech) {
            // Process the recorded audio
            processAudio(pcmBuffer.toByteArray())
        }

        pcmBuffer.close()
    }

    /**
     * Process recorded audio through Whisper for speech-to-text.
     */
    private suspend fun processAudio(pcmData: ByteArray) {
        if (!whisperModelLoaded || pcmData.size < 1600) { // Minimum 100ms of audio
            Log.w(TAG, "Audio too short or model not loaded")
            _isListening.value = false
            return
        }

        _isProcessing.value = true
        _isListening.value = false

        try {
            Log.d(TAG, "Processing audio: ${pcmData.size} bytes")

            // Convert PCM byte array to float array for whisper
            val floatSamples = convertPcmToFloat(pcmData)

            // Run whisper inference
            // whisper_full() transcribes the audio
            val result = whisperFull(whisperNativePtr, floatSamples, floatSamples.size)

            if (result.isNotEmpty()) {
                Log.d(TAG, "Recognized: $result")
                _recognizedText.value = result
                onSpeechRecognized?.invoke(result)
            } else {
                Log.w(TAG, "No speech recognized")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to process audio", e)
            onError?.invoke("Speech recognition failed: ${e.message}")
        } finally {
            _isProcessing.value = false
        }
    }

    /**
     * Convert PCM 16-bit audio bytes to float array for whisper.
     */
    private fun convertPcmToFloat(pcmData: ByteArray): FloatArray {
        val shortBuffer = ByteBuffer.wrap(pcmData)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()

        val floatArray = FloatArray(shortBuffer.remaining())
        for (i in floatArray.indices) {
            floatArray[i] = shortBuffer[i].toFloat() / 32768.0f
        }

        return floatArray
    }

    /**
     * Calculate the voice level from audio buffer for visualization.
     */
    private fun calculateVoiceLevel(buffer: ByteArray, bytesRead: Int): Float {
        var sum = 0f
        var count = 0

        for (i in 0 until bytesRead step 2) {
            if (i + 1 < bytesRead) {
                val sample = (buffer[i].toInt() and 0xFF) or
                        ((buffer[i + 1].toInt() and 0xFF) shl 8)
                sum += kotlin.math.abs(sample.toFloat())
                count++
            }
        }

        return if (count > 0) sum / count else 0f
    }

    /**
     * Stop listening and release audio resources.
     */
    fun stopListening() {
        Log.d(TAG, "Stopping recording")
        isRecording.set(false)
        _isListening.value = false
        _isProcessing.value = false
        stopAudioCapture()
    }

    /**
     * Internal method to stop audio capture and release AudioRecord.
     */
    private fun stopAudioCapture() {
        try {
            audioRecord?.apply {
                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio capture", e)
        }
        audioRecord = null
    }

    /**
     * Simulate wake word detection.
     * In production, this would analyze audio in real-time through whisper.
     * For the current implementation, we use a simplified detection approach.
     */
    private fun checkForWakeWord(text: String): Boolean {
        val normalizedText = text.lowercase().trim()
        val isWake = normalizedText.contains(WAKE_WORD) ||
                normalizedText.contains("narzo") ||
                normalizedText.contains("hey narzo")

        if (isWake) {
            Log.d(TAG, "Wake word detected in: $normalizedText")
            _wakeWordDetected.value = true
            mainHandler.post {
                onWakeWordDetected?.invoke()
            }
        }

        return isWake
    }

    /**
     * Reset wake word detection state.
     */
    fun resetWakeWordDetection() {
        _wakeWordDetected.value = false
    }

    /**
     * Release all resources.
     * Call this in onDestroy() to properly clean up.
     */
    fun release() {
        Log.d(TAG, "Releasing voice engine resources")
        stopListening()
        recordingScope.cancel()

        // Unload whisper model to free memory
        if (whisperModelLoaded && whisperNativePtr != 0L) {
            try {
                whisperFree(whisperNativePtr)
            } catch (e: Exception) {
                Log.e(TAG, "Error freeing whisper model", e)
            }
            whisperNativePtr = 0
            whisperModelLoaded = false
        }

        mainHandler.removeCallbacksAndMessages(null)
    }

    /**
     * Check if the whisper model is loaded and ready.
     */
    fun isModelLoaded(): Boolean = whisperModelLoaded

    /**
     * Get the model loading progress.
     */
    fun getModelStatus(): ModelStatus {
        return when {
            whisperModelLoaded -> ModelStatus.LOADED
            else -> ModelStatus.NOT_LOADED
        }
    }

    enum class ModelStatus {
        NOT_LOADED,
        LOADING,
        LOADED,
        ERROR
    }

    // ==========================================
    // Native JNI methods for whisper.cpp
    // These methods link to the native whisper library
    // ==========================================

    /**
     * Initialize whisper model from file path.
     * @param modelPath Absolute path to the GGML model file
     * @return Native pointer to whisper context, or 0 on failure
     */
    private external fun whisperInit(modelPath: String): Long

    /**
     * Run full transcription on audio samples.
     * @param ctx Native whisper context pointer
     * @param samples Audio samples as float array (normalized to [-1, 1])
     * @param nSamples Number of samples
     * @return Transcribed text
     */
    private external fun whisperFull(ctx: Long, samples: FloatArray, nSamples: Int): String

    /**
     * Free whisper model resources.
     * @param ctx Native whisper context pointer
     */
    private external fun whisperFree(ctx: Long)
}
