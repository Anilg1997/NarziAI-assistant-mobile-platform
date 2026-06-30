package com.narzoai.assistant

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.junit.MockitoJUnitRunner

@RunWith(MockitoJUnitRunner::class)
class VoiceEngineTest {

    @Mock
    private lateinit var mockContext: Context

    private lateinit var voiceEngine: VoiceEngine

    @Before
    fun setUp() {
        `when`(mockContext.filesDir).thenReturn(java.io.File("/tmp/test-files"))
        voiceEngine = VoiceEngine(mockContext)
    }

    @Test
    fun `isListening should default to false`() {
        assertFalse(voiceEngine.isListening.value)
    }

    @Test
    fun `isProcessing should default to false`() {
        assertFalse(voiceEngine.isProcessing.value)
    }

    @Test
    fun `wakeWordDetected should default to false`() {
        assertFalse(voiceEngine.wakeWordDetected.value)
    }

    @Test
    fun `recognizedText should default to empty`() {
        assertEquals("", voiceEngine.recognizedText.value)
    }

    @Test
    fun `voiceLevel should default to 0`() {
        assertEquals(0f, voiceEngine.voiceLevel.value, 0.01f)
    }

    @Test
    fun `isModelLoaded should return false before init`() {
        assertFalse(voiceEngine.isModelLoaded())
    }

    @Test
    fun `getModelStatus should return NOT_LOADED before init`() {
        assertEquals(VoiceEngine.ModelStatus.NOT_LOADED, voiceEngine.getModelStatus())
    }

    @Test
    fun `startListening should not crash when model not loaded`() {
        voiceEngine.startListening()
    }

    @Test
    fun `stopListening should not crash`() {
        voiceEngine.stopListening()
    }

    @Test
    fun `release should not crash`() {
        voiceEngine.release()
    }

    @Test
    fun `resetWakeWordDetection should set to false`() {
        voiceEngine.resetWakeWordDetection()
        assertFalse(voiceEngine.wakeWordDetected.value)
    }

    @Test
    fun `startListening with command mode should not crash`() {
        voiceEngine.startListening(isCommandMode = true)
    }

    @Test
    fun `initialize should not crash`() {
        voiceEngine.initialize()
    }
}
