package com.narzoai.assistant

import android.content.Context
import android.speech.tts.TextToSpeech
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.junit.MockitoJUnitRunner

@RunWith(MockitoJUnitRunner::class)
class TTSEngineTest {

    @Mock
    private lateinit var mockContext: Context

    private lateinit var ttsEngine: TTSEngine

    @Before
    fun setUp() {
        ttsEngine = TTSEngine(mockContext)
    }

    @Test
    fun `isReady should return false before initialization`() {
        assertFalse(ttsEngine.isReady())
    }

    @Test
    fun `isSpeaking should default to false`() {
        assertFalse(ttsEngine.isSpeaking())
    }

    @Test
    fun `getState should return INITIALIZING before init`() {
        assertEquals(TTSEngine.TTSState.INITIALIZING, ttsEngine.getState())
    }

    @Test
    fun `speak should not throw before initialization`() {
        ttsEngine.speak("Hello")
    }

    @Test
    fun `speakImmediately should not throw before initialization`() {
        ttsEngine.speakImmediately("Hello")
    }

    @Test
    fun `stop should not throw before initialization`() {
        ttsEngine.stop()
    }

    @Test
    fun `setSpeechRate should clamp to minimum 0_1`() {
        ttsEngine.setSpeechRate(0.05f)
    }

    @Test
    fun `setSpeechRate should clamp to maximum 2_0`() {
        ttsEngine.setSpeechRate(3.0f)
    }

    @Test
    fun `setSpeechRate should accept valid values`() {
        ttsEngine.setSpeechRate(1.0f)
    }

    @Test
    fun `setSpeechPitch should clamp to minimum 0_1`() {
        ttsEngine.setSpeechPitch(0.05f)
    }

    @Test
    fun `setSpeechPitch should clamp to maximum 2_0`() {
        ttsEngine.setSpeechPitch(3.0f)
    }

    @Test
    fun `setVolume should clamp to minimum 0`() {
        ttsEngine.setVolume(-0.5f)
    }

    @Test
    fun `setVolume should clamp to maximum 1`() {
        ttsEngine.setVolume(1.5f)
    }

    @Test
    fun `setVolume should accept valid values`() {
        ttsEngine.setVolume(0.75f)
    }

    @Test
    fun `shutdown should not throw`() {
        ttsEngine.shutdown()
        assertFalse(ttsEngine.isReady())
    }

    @Test
    fun `getState should return READY after successful init`() {
        assertEquals(TTSEngine.TTSState.INITIALIZING, ttsEngine.getState())
    }

    @Test
    fun `isInitialized should default to false`() {
        assertFalse(ttsEngine.isInitialized.value)
    }
}
