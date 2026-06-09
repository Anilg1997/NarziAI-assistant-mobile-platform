package com.narzoai.assistant

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * TTSEngine - Handles Text-to-Speech functionality using Android's built-in TTS engine.
 * Optimized for offline use with support for multiple languages and voice control.
 *
 * This engine runs completely offline using the device's native TTS capabilities,
 * no internet connection required.
 */
class TTSEngine(private val context: Context) {

    companion object {
        private const val TAG = "NarzoAI_TTS"
        private const val UTTERANCE_ID = "narzoai_tts_utterance"
    }

    private var tts: TextToSpeech? = null
    private var isInitialized = false

    // TTS state
    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized

    // Current volume level (0.0 to 1.0)
    private var volumeLevel = 1.0f

    // Speech rate (0.1 to 2.0)
    private var speechRate = 1.0f

    // Pitch (0.1 to 2.0)
    private var speechPitch = 1.0f

    private var onDoneCallback: (() -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null

    /**
     * Initialize the TTS engine. Must be called before using speak().
     * Sets up the progress listener to track speech state.
     */
    fun initialize(
        onReady: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        onDoneCallback = onReady
        onErrorCallback = onError

        tts = TextToSpeech(context) { status ->
            when (status) {
                TextToSpeech.SUCCESS -> {
                    Log.d(TAG, "TTS initialized successfully")
                    isInitialized = true
                    _isInitialized.value = true

                    // Configure TTS settings
                    val langResult = tts?.setLanguage(Locale.US)
                    if (langResult == TextToSpeech.LANG_MISSING_DATA ||
                        langResult == TextToSpeech.LANG_NOT_SUPPORTED
                    ) {
                        Log.w(TAG, "Language not supported, using default")
                    }

                    // Set speech rate and pitch
                    tts?.setSpeechRate(speechRate)
                    tts?.setPitch(speechPitch)

                    // Set up utterance progress listener
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            Log.d(TAG, "Speech started: $utteranceId")
                            _isSpeaking.value = true
                        }

                        override fun onDone(utteranceId: String?) {
                            Log.d(TAG, "Speech completed: $utteranceId")
                            _isSpeaking.value = false
                            onDoneCallback?.invoke()
                        }

                        override fun onError(utteranceId: String?) {
                            Log.e(TAG, "Speech error: $utteranceId")
                            _isSpeaking.value = false
                            onErrorCallback?.invoke("Speech synthesis failed")
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?, errorCode: Int) {
                            Log.e(TAG, "Speech error: $utteranceId, code: $errorCode")
                            _isSpeaking.value = false
                            onErrorCallback?.invoke("Speech error code: $errorCode")
                        }
                    })

                    onReady?.invoke()
                }
                TextToSpeech.ERROR -> {
                    Log.e(TAG, "TTS initialization failed")
                    isInitialized = false
                    onError?.invoke("Failed to initialize text-to-speech engine")
                }
            }
        }
    }

    /**
     * Speak the given text aloud.
     * Uses Android's offline TTS engine.
     *
     * @param text The text to speak
     * @param queueMode QUEUE_ADD (default) to queue, QUEUE_FLUSH to interrupt current speech
     */
    fun speak(text: String, queueMode: Int = TextToSpeech.QUEUE_ADD) {
        if (!isInitialized || tts == null) {
            Log.w(TAG, "TTS not initialized, cannot speak")
            return
        }

        Log.d(TAG, "Speaking: $text")

        // Configure speaking parameters
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volumeLevel)
            putFloat(TextToSpeech.Engine.KEY_PARAM_PAN, 0.0f) // Center pan
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tts?.speak(text, queueMode, params, UTTERANCE_ID)
        } else {
            @Suppress("DEPRECATION")
            tts?.speak(text, queueMode, params)
        }
    }

    /**
     * Speak text immediately, interrupting any current speech.
     */
    fun speakImmediately(text: String) {
        speak(text, TextToSpeech.QUEUE_FLUSH)
    }

    /**
     * Stop any ongoing speech.
     */
    fun stop() {
        if (isSpeaking.value) {
            tts?.stop()
            _isSpeaking.value = false
        }
    }

    /**
     * Check if the TTS engine is currently speaking.
     */
    fun isSpeaking(): Boolean = isSpeaking.value

    /**
     * Set the speech rate.
     * @param rate 0.1 to 2.0 (1.0 is normal)
     */
    fun setSpeechRate(rate: Float) {
        speechRate = rate.coerceIn(0.1f, 2.0f)
        tts?.setSpeechRate(speechRate)
    }

    /**
     * Set the speech pitch.
     * @param pitch 0.1 to 2.0 (1.0 is normal)
     */
    fun setSpeechPitch(pitch: Float) {
        speechPitch = pitch.coerceIn(0.1f, 2.0f)
        tts?.setPitch(speechPitch)
    }

    /**
     * Set the volume level.
     * @param volume 0.0 to 1.0
     */
    fun setVolume(volume: Float) {
        volumeLevel = volume.coerceIn(0.0f, 1.0f)
    }

    /**
     * Get available voices on the device.
     */
    fun getAvailableVoices(): Set<Voice>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tts?.voices
        } else {
            null
        }
    }

    /**
     * Set a specific voice for TTS.
     */
    fun setVoice(voice: Voice) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tts?.setVoice(voice)
        }
    }

    /**
     * Shutdown the TTS engine and release resources.
     * Call this in onDestroy() of the activity.
     */
    fun shutdown() {
        Log.d(TAG, "Shutting down TTS engine")
        stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
        _isInitialized.value = false
        _isSpeaking.value = false
    }

    /**
     * Check if TTS engine is ready.
     */
    fun isReady(): Boolean = isInitialized && tts != null

    /**
     * Get the current initialization state.
     */
    fun getState(): TTSState {
        return when {
            !isInitialized -> TTSState.INITIALIZING
            isSpeaking.value -> TTSState.SPEAKING
            else -> TTSState.READY
        }
    }

    enum class TTSState {
        INITIALIZING,
        READY,
        SPEAKING
    }
}
