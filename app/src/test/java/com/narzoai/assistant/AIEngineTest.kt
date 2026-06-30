package com.narzoai.assistant

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.junit.MockitoJUnitRunner
import java.io.File

@RunWith(MockitoJUnitRunner::class)
class AIEngineTest {

    @Mock
    private lateinit var mockContext: android.content.Context

    private lateinit var aiEngine: AIEngine

    @Before
    fun setUp() {
        `when`(mockContext.filesDir).thenReturn(File("/tmp/test-files"))
        aiEngine = AIEngine(mockContext)
    }

    @Test
    fun `initialize should set MODEL_NOT_FOUND when model file missing`() {
        aiEngine.initialize()
        assertEquals(AIEngine.ModelStatus.MODEL_NOT_FOUND, aiEngine.modelStatus.value)
    }

    @Test
    fun `initialize should set status properly with model present`() {
        val modelsDir = File("/tmp/test-files/models")
        modelsDir.mkdirs()
        val modelFile = File(modelsDir, "gemma-2b-it-q4_k_m.gguf")
        modelFile.writeText("x".repeat(1_200_000_000))

        aiEngine.initialize()
        assertEquals(AIEngine.ModelStatus.READY, aiEngine.modelStatus.value)

        modelFile.delete()
    }

    @Test
    fun `modelLoaded should be false after initialization without model`() {
        aiEngine.initialize()
        assertFalse(aiEngine.modelLoaded.value)
    }

    @Test
    fun `isReady should return false when model not loaded`() {
        aiEngine.initialize()
        assertFalse(aiEngine.isReady())
    }

    @Test
    fun `checkAvailableMemory should return boolean`() {
        val result = aiEngine.checkAvailableMemory()
        assertNotNull(result)
    }

    @Test
    fun `clearChatHistory should not throw`() {
        aiEngine.initialize()
        aiEngine.clearChatHistory()
    }

    @Test
    fun `release should not throw`() {
        aiEngine.initialize()
        aiEngine.release()
    }

    @Test
    fun `getModelInfo should return null when no model file`() {
        aiEngine.initialize()
        assertNull(aiEngine.getModelInfo())
    }

    @Test
    fun `sendMessage should return error when model not found`() {
        aiEngine.initialize()
        aiEngine.sendMessage(
            message = "Hello",
            onComplete = { response ->
                assertTrue(response.contains("not available"))
            }
        )
    }
}
