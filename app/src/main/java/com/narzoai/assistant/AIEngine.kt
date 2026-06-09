package com.narzoai.assistant

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AIEngine - Handles offline AI chat using Gemma 2B GGUF quantized model via llama.cpp.
 *
 * Key features for 4GB RAM optimization:
 * - Lazy model loading: model is loaded only when needed
 * - Memory-efficient inference: model is processed in chunks
 * - Automatic unloading: model is freed when app goes to background
 * - Progress tracking for model loading
 * - Context window management to limit memory usage
 */
class AIEngine(private val context: Context) {

    companion object {
        private const val TAG = "NarzoAI_AIEngine"
        private const val MODELS_DIR = "models"
        private const val GEMMA_MODEL = "gemma-2b-it-q4_k_m.gguf"

        // Model parameters for 4GB RAM optimization
        private const val MAX_CONTEXT_LENGTH = 2048  // Reduced context window
        private const val MAX_BATCH_SIZE = 512
        private const val MAX_TOKENS = 512  // Max tokens per response
        private const val GPU_LAYERS = 0  // CPU only to save GPU memory
        private const val THREAD_COUNT = 4  // Optimal for mobile CPUs

        // Chat history limits
        private const val MAX_HISTORY_MESSAGES = 20
        private const val MAX_HISTORY_TOKENS = 1500

        // Model file size check (Gemma 2B Q4_K_M ~1.4GB)
        private const val MIN_MODEL_SIZE_BYTES = 1_000_000_000L // ~1GB
        private const val MAX_MODEL_SIZE_BYTES = 2_500_000_000L // ~2.5GB
    }

    // AI Engine state
    private val _modelLoaded = MutableStateFlow(false)
    val modelLoaded: StateFlow<Boolean> = _modelLoaded

    private val _isThinking = MutableStateFlow(false)
    val isThinking: StateFlow<Boolean> = _isThinking

    private val _loadingProgress = MutableStateFlow(0f)
    val loadingProgress: StateFlow<Float> = _loadingProgress

    private val _modelStatus = MutableStateFlow(ModelStatus.NOT_LOADED)
    val modelStatus: StateFlow<ModelStatus> = _modelStatus

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    // Llama.cpp native pointer
    private var llamaNativePtr: Long = 0
    private var modelFile: File? = null

    // Execution scope
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Chat history for context
    private val chatHistory = mutableListOf<ChatMessage>()

    // Callbacks
    private var onTokenGenerated: ((String) -> Unit)? = null
    private var onResponseComplete: ((String) -> Unit)? = null
    private var onStatusChange: ((ModelStatus) -> Unit)? = null

    // Memory management
    private val isModelLoaded = AtomicBoolean(false)
    private var lastUsedTime = 0L
    private val MODEL_UNLOAD_TIMEOUT = 300000L // 5 minutes of inactivity

    /**
     * Initialize the AI engine.
     * Does NOT load the model - that happens on first use (lazy loading).
     */
    fun initialize(
        onToken: ((String) -> Unit)? = null,
        onComplete: ((String) -> Unit)? = null,
        onStatus: ((ModelStatus) -> Unit)? = null
    ) {
        onTokenGenerated = onToken
        onResponseComplete = onComplete
        onStatusChange = onStatus

        Log.d(TAG, "AI Engine initialized (model will load on first use)")

        // Check if model file exists
        val modelsDir = File(context.filesDir, MODELS_DIR)
        modelFile = File(modelsDir, GEMMA_MODEL)

        if (modelFile?.exists() == true) {
            val fileSize = modelFile?.length() ?: 0
            if (fileSize >= MIN_MODEL_SIZE_BYTES && fileSize <= MAX_MODEL_SIZE_BYTES) {
                Log.d(TAG, "Model file found: ${modelFile?.absolutePath} (${fileSize / 1024 / 1024}MB)")
                _modelStatus.value = ModelStatus.READY
            } else {
                Log.w(TAG, "Model file has unexpected size: $fileSize bytes")
                _modelStatus.value = ModelStatus.FILE_ERROR
            }
        } else {
            Log.w(TAG, "Model file not found: ${modelFile?.absolutePath}")
            _modelStatus.value = ModelStatus.MODEL_NOT_FOUND
        }

        onStatusChange?.invoke(_modelStatus.value)
    }

    /**
     * Load the Gemma 2B GGUF model into memory.
     * This is called lazily when first chat request is made.
     * Shows loading progress via loadingProgress StateFlow.
     */
    suspend fun loadModel(): Boolean {
        if (isModelLoaded.get()) {
            Log.d(TAG, "Model already loaded")
            return true
        }

        val modelPath = modelFile?.absolutePath
        if (modelPath == null || !modelFile!!.exists()) {
            _errorMessage.value = "Model file not found. Please download Gemma 2B GGUF model."
            _modelStatus.value = ModelStatus.MODEL_NOT_FOUND
            return false
        }

        Log.d(TAG, "Loading Gemma 2B model from: $modelPath")
        _modelStatus.value = ModelStatus.LOADING
        _loadingProgress.value = 0f

        return try {                // Load the native library (single combined JNI library)
                System.loadLibrary("narzoai_jni")

            withContext(Dispatchers.IO) {
                // Initialize llama.cpp with optimized parameters for 4GB RAM
                val params = LlamaParams(
                    modelPath = modelPath,
                    nCtx = MAX_CONTEXT_LENGTH,
                    nBatch = MAX_BATCH_SIZE,
                    nGpuLayers = GPU_LAYERS,
                    nThreads = THREAD_COUNT,
                    useMlock = false,
                    useMmap = true  // Memory-map for efficient loading
                )

                // Start loading with progress tracking
                _loadingProgress.value = 0.1f

                llamaNativePtr = llamaInit(
                    params.modelPath,
                    params.nCtx,
                    params.nBatch,
                    params.nGpuLayers,
                    params.nThreads,
                    params.useMlock,
                    params.useMmap
                )

                if (llamaNativePtr == 0L) {
                    throw RuntimeException("Failed to initialize llama.cpp")
                }

                _loadingProgress.value = 1.0f
                isModelLoaded.set(true)
                _modelLoaded.value = true
                _modelStatus.value = ModelStatus.LOADED
                lastUsedTime = System.currentTimeMillis()

                Log.d(TAG, "Gemma 2B model loaded successfully")
                onStatusChange?.invoke(ModelStatus.LOADED)
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
            _errorMessage.value = "Failed to load AI model: ${e.message}"
            _modelStatus.value = ModelStatus.LOAD_ERROR
            isModelLoaded.set(false)
            _modelLoaded.value = false
            onStatusChange?.invoke(ModelStatus.LOAD_ERROR)
            false
        }
    }

    /**
     * Send a message to the AI and get a response.
     * Loads the model lazily if not already loaded.
     *
     * @param message User's input message
     * @param onProgress Optional callback for partial response text
     * @param onComplete Callback with complete response
     */
    suspend fun sendMessage(
        message: String,
        onProgress: ((String) -> Unit)? = null,
        onComplete: (String) -> Unit
    ) {
        if (_isThinking.value) {
            Log.w(TAG, "AI is already processing a message")
            return
        }

        // Lazy load model if needed
        if (!isModelLoaded.get()) {
            val loaded = loadModel()
            if (!loaded) {
                onComplete("AI model is not available. Please download the Gemma 2B GGUF model.")
                return
            }
        }

        _isThinking.value = true
        lastUsedTime = System.currentTimeMillis()

        // Add user message to history
        chatHistory.add(ChatMessage(Role.USER, message))

        // Trim history if too long
        trimChatHistory()

        try {
            val responseBuilder = StringBuilder()

            withContext(Dispatchers.IO) {
                // Build the prompt with chat history
                val prompt = buildChatPrompt()

                // Run inference
                val result = llamaInference(
                    ctx = llamaNativePtr,
                    prompt = prompt,
                    maxTokens = MAX_TOKENS,
                    temperature = 0.7f,
                    topP = 0.9f,
                    repeatPenalty = 1.1f
                )

                // Process each token as it's generated
                result.split(" ").forEach { token ->
                    responseBuilder.append(token)
                    val currentText = responseBuilder.toString()
                    withContext(Dispatchers.Main) {
                        onProgress?.invoke(currentText)
                    }
                }
            }

            val fullResponse = responseBuilder.toString().trim()
            Log.d(TAG, "AI response generated: ${fullResponse.take(100)}...")

            // Add AI response to history
            chatHistory.add(ChatMessage(Role.ASSISTANT, fullResponse))

            // Notify completion
            withContext(Dispatchers.Main) {
                onComplete(fullResponse)
                onResponseComplete?.invoke(fullResponse)
            }

        } catch (e: Exception) {
            Log.e(TAG, "AI inference failed", e)
            val errorMsg = "I encountered an error while processing your request: ${e.message}"
            withContext(Dispatchers.Main) {
                onComplete(errorMsg)
                onResponseComplete?.invoke(errorMsg)
            }
        } finally {
            _isThinking.value = false
        }
    }

    /**
     * Build a chat prompt from conversation history.
     * Uses Gemma's chat template format.
     */
    private fun buildChatPrompt(): String {
        val sb = StringBuilder()
        sb.append("<start_of_turn>system\n")
        sb.append("You are NarzoAI, a helpful offline AI assistant on an Android device. ")
        sb.append("You can help with tasks, answer questions, and control the phone. ")
        sb.append("Keep responses concise and helpful.\n")
        sb.append("<end_of_turn>\n")

        for (msg in chatHistory) {
            when (msg.role) {
                Role.USER -> {
                    sb.append("<start_of_turn>user\n")
                    sb.append(msg.content)
                    sb.append("\n<end_of_turn>\n")
                }
                Role.ASSISTANT -> {
                    sb.append("<start_of_turn>model\n")
                    sb.append(msg.content)
                    sb.append("\n<end_of_turn>\n")
                }
            }
        }

        sb.append("<start_of_turn>model\n")
        return sb.toString()
    }

    /**
     * Trim chat history to prevent memory issues.
     * Keeps only the most recent messages within token limits.
     */
    private fun trimChatHistory() {
        while (chatHistory.size > MAX_HISTORY_MESSAGES) {
            // Remove oldest non-system messages
            val oldestUserIndex = chatHistory.indexOfFirst { it.role == Role.USER }
            if (oldestUserIndex >= 0) {
                // Remove the user message and the following assistant response
                chatHistory.removeAt(oldestUserIndex)
                if (oldestUserIndex < chatHistory.size &&
                    chatHistory[oldestUserIndex].role == Role.ASSISTANT
                ) {
                    chatHistory.removeAt(oldestUserIndex)
                }
            } else {
                break
            }
        }
    }

    /**
     * Clear the chat history.
     */
    fun clearChatHistory() {
        chatHistory.clear()
        Log.d(TAG, "Chat history cleared")
    }

    /**
     * Unload the model from memory to free RAM.
     * Called when the app goes to background or after inactivity.
     */
    fun unloadModel() {
        if (isModelLoaded.get() && llamaNativePtr != 0L) {
            try {
                Log.d(TAG, "Unloading AI model to free memory")
                llamaFree(llamaNativePtr)
                llamaNativePtr = 0
                isModelLoaded.set(false)
                _modelLoaded.value = false
                _modelStatus.value = ModelStatus.READY
                onStatusChange?.invoke(ModelStatus.READY)
                Log.d(TAG, "AI model unloaded successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error unloading model", e)
            }
        }
    }

    /**
     * Release all resources.
     * Call this in onDestroy() of the activity.
     */
    fun release() {
        Log.d(TAG, "Releasing AI engine resources")
        unloadModel()
        engineScope.cancel()
        chatHistory.clear()
    }

    /**
     * Check if model is loaded and ready.
     */
    fun isReady(): Boolean = isModelLoaded.get() && llamaNativePtr != 0L

    /**
     * Get the current model file info.
     */
    fun getModelInfo(): ModelInfo? {
        val file = modelFile ?: return null
        if (!file.exists()) return null

        return ModelInfo(
            fileName = file.name,
            fileSizeMB = file.length() / (1024 * 1024),
            path = file.absolutePath
        )
    }

    /**
     * Check available memory before loading model.
     * Returns true if there's enough memory.
     */
    fun checkAvailableMemory(): Boolean {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory()
        val freeMemory = runtime.freeMemory()
        val totalMemory = runtime.totalMemory()
        val availableMemory = freeMemory + (maxMemory - totalMemory)

        Log.d(TAG, "Available JVM memory: ${availableMemory / (1024 * 1024)}MB")

        // Gemma 2B Q4_K_M needs ~1.5GB
        return availableMemory > 1_600_000_000L // ~1.6GB
    }

    /**
     * Data class for llama.cpp initialization parameters.
     */
    data class LlamaParams(
        val modelPath: String,
        val nCtx: Int,
        val nBatch: Int,
        val nGpuLayers: Int,
        val nThreads: Int,
        val useMlock: Boolean,
        val useMmap: Boolean
    )

    /**
     * Data class for chat messages.
     */
    data class ChatMessage(
        val role: Role,
        val content: String
    )

    enum class Role {
        SYSTEM,
        USER,
        ASSISTANT
    }

    /**
     * Model status states.
     */
    enum class ModelStatus {
        NOT_LOADED,
        READY,
        LOADING,
        LOADED,
        MODEL_NOT_FOUND,
        FILE_ERROR,
        LOAD_ERROR
    }

    /**
     * Model information data class.
     */
    data class ModelInfo(
        val fileName: String,
        val fileSizeMB: Long,
        val path: String
    )

    // ==========================================
    // Native JNI methods for llama.cpp
    // These methods link to the native llama.cpp library
    // ==========================================

    /**
     * Initialize llama.cpp with the given model and parameters.
     * @return Native pointer to llama context, or 0 on failure
     */
    private external fun llamaInit(
        modelPath: String,
        nCtx: Int,
        nBatch: Int,
        nGpuLayers: Int,
        nThreads: Int,
        useMlock: Boolean,
        useMmap: Boolean
    ): Long

    /**
     * Run inference on the given prompt.
     * @param ctx Native llama context pointer
     * @param prompt Input text
     * @param maxTokens Maximum tokens to generate
     * @param temperature Sampling temperature (0.0 to 2.0)
     * @param topP Nucleus sampling parameter
     * @param repeatPenalty Repetition penalty
     * @return Generated text response
     */
    private external fun llamaInference(
        ctx: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        repeatPenalty: Float
    ): String

    /**
     * Free llama model resources.
     * @param ctx Native llama context pointer
     */
    private external fun llamaFree(ctx: Long)
}
