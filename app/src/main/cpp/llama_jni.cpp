// ============================================================================
// llama_jni.cpp - JNI Bridge for llama.cpp
// Connects Kotlin AIEngine.kt native methods to llama.cpp C API.
//
// JNI Method Mapping (from AIEngine.kt):
//   llamaInit -> Java_com_narzoai_assistant_AIEngine_llamaInit
//   llamaInference -> Java_com_narzoai_assistant_AIEngine_llamaInference
//   llamaFree -> Java_com_narzoai_assistant_AIEngine_llamaFree
//
// Uses the core llama.h API directly (not common.h) to avoid extra dependencies.
// ============================================================================

#include "jni_utils.h"
#include <cstring>
#include <vector>
#include <string>
#include <sstream>
#include <algorithm>
#include <random>
#include <unistd.h>

// Include llama.h from the llama.cpp library
#include "llama.h"

// ============================================================================
// Wrapper struct to hold both model and context pointers
// Defined at file scope so all JNI functions can access it
// ============================================================================
struct ModelContext {
    llama_model* model;
    llama_context* ctx;
};

// ============================================================================
// Forward declarations of static helpers
// ============================================================================

/**
 * Validate that a native pointer is not null.
 */
static bool validate_ptr(jlong ptr, const char* name) {
    if (ptr == 0L) {
        LOGE("Null pointer in %s - model not initialized", name);
        return false;
    }
    return true;
}

/**
 * Get optimal thread count for the device.
 */
static int get_optimal_thread_count() {
    long num_cpus = sysconf(_SC_NPROCESSORS_CONF);
    if (num_cpus < 1) num_cpus = 4;

    // For mobile big.LITTLE, use half the cores (capped at 6)
    int threads = (static_cast<int>(num_cpus) + 1) / 2;
    if (threads < 1) threads = 1;
    if (threads > 6) threads = 6;

    LOGD("CPU cores: %ld, using %d threads", num_cpus, threads);
    return threads;
}

/**
 * Get the llama_vocab pointer from the model for token operations.
 */
static const llama_vocab* get_vocab(llama_model* model) {
    return llama_model_get_vocab(model);
}

// ============================================================================
// JNI: llamaInit
//
// Initializes the llama.cpp model with the given parameters.
// Returns a jlong handle to the ModelContext, or 0 on failure.
//
// Signature: (Ljava/lang/String;IIIIZZ)J
// ============================================================================
extern "C" JNIEXPORT jlong JNICALL
Java_com_narzoai_assistant_AIEngine_llamaInit(
    JNIEnv* env,
    jobject /* thiz */,
    jstring model_path,
    jint n_ctx,
    jint n_batch,
    jint n_gpu_layers,
    jint n_threads,
    jboolean use_mlock,
    jboolean use_mmap
) {
    LOGI("llamaInit called");
    LOGI("  n_ctx: %d, n_batch: %d, n_gpu_layers: %d, n_threads: %d",
         (int)n_ctx, (int)n_batch, (int)n_gpu_layers, (int)n_threads);
    LOGI("  use_mlock: %d, use_mmap: %d", (int)use_mlock, (int)use_mmap);

    // Convert model path
    std::string model_path_str = jstring_to_string(env, model_path);
    if (model_path_str.empty()) {
        LOGE("Model path is empty");
        throw_runtime_exception(env, "Model path cannot be empty");
        return 0L;
    }

    LOGI("Loading model from: %s", model_path_str.c_str());

    // ========================================================================
    // Step 1: Load the model
    // ========================================================================
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = static_cast<int>(n_gpu_layers);
    model_params.use_mlock = static_cast<bool>(use_mlock);
    model_params.use_mmap = static_cast<bool>(use_mmap);

    llama_model* model = llama_load_model_from_file(
        model_path_str.c_str(),
        model_params
    );

    if (model == nullptr) {
        LOGE("Failed to load model from: %s", model_path_str.c_str());
        throw_runtime_exception(env, "Failed to load model. File may be corrupted or incompatible.");
        return 0L;
    }

    LOGI("Model loaded successfully");

    // ========================================================================
    // Step 2: Create inference context
    // ========================================================================
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = static_cast<uint32_t>(n_ctx > 0 ? n_ctx : 2048);
    ctx_params.n_batch = static_cast<uint32_t>(n_batch > 0 ? n_batch : 512);
    ctx_params.n_threads = static_cast<uint32_t>(n_threads > 0 ? n_threads : get_optimal_thread_count());
    ctx_params.n_threads_batch = ctx_params.n_threads;
    ctx_params.seed = LLAMA_DEFAULT_SEED;
    ctx_params.embeddings = false;
    ctx_params.offload_kqv = (n_gpu_layers > 0);

    LOGI("Context: n_ctx=%d, n_batch=%d, n_threads=%d",
         ctx_params.n_ctx, ctx_params.n_batch, ctx_params.n_threads);

    llama_context* ctx = llama_new_context_with_model(model, ctx_params);

    if (ctx == nullptr) {
        LOGE("Failed to create llama context");
        llama_free_model(model);
        throw_runtime_exception(env, "Failed to create inference context (out of memory)");
        return 0L;
    }

    LOGI("llama context created successfully");

    // ========================================================================
    // Step 3: Wrap both pointers in a struct and return handle
    // ========================================================================
    ModelContext* mc = new (std::nothrow) ModelContext{model, ctx};
    if (mc == nullptr) {
        LOGE("Failed to allocate ModelContext wrapper");
        llama_free(ctx);
        llama_free_model(model);
        throw_out_of_memory_error(env, "Failed to allocate memory for model context");
        return 0L;
    }

    LOGI("Model initialization complete. Handle: %p", (void*)mc);
    return ptr_to_jlong(mc);
}

// ============================================================================
// JNI: llamaInference
//
// Runs inference on the given prompt and returns generated text.
// Uses core llama.h API (llama_decode) instead of common.h for portability.
//
// Signature: (JLjava/lang/String;IFFF)Ljava/lang/String;
// ============================================================================
extern "C" JNIEXPORT jstring JNICALL
Java_com_narzoai_assistant_AIEngine_llamaInference(
    JNIEnv* env,
    jobject /* thiz */,
    jlong ctx_handle,
    jstring prompt,
    jint max_tokens,
    jfloat temperature,
    jfloat top_p,
    jfloat repeat_penalty
) {
    LOGI("llamaInference called");

    // Validate context handle
    if (!validate_ptr(ctx_handle, "llamaInference")) {
        throw_runtime_exception(env, "Model not initialized. Call llamaInit first.");
        return string_to_jstring(env, "");
    }

    // Get the model context wrapper
    ModelContext* mc = jlong_to_ptr<ModelContext>(ctx_handle);
    llama_context* ctx = mc->ctx;
    llama_model* model = mc->model;

    // Get context size for this model
    int n_ctx = llama_n_ctx(ctx);
    LOGI("Context size: %d tokens", n_ctx);

    // Convert prompt
    std::string prompt_str = jstring_to_string(env, prompt);
    if (prompt_str.empty()) {
        LOGW("Empty prompt provided");
        return string_to_jstring(env, "");
    }

    LOGI("Prompt: %.100s...", prompt_str.c_str());
    LOGI("Params: max_tokens=%d, temperature=%.2f, top_p=%.2f, repeat_penalty=%.2f",
         (int)max_tokens, (float)temperature, (float)top_p, (float)repeat_penalty);

    // ========================================================================
    // Step 1: Tokenize the prompt
    // ========================================================================
    const auto* vocab = get_vocab(model);

    // First, get the token count
    int n_tokens = llama_tokenize(
        model,
        prompt_str.c_str(),
        static_cast<int>(prompt_str.length()),
        nullptr,  // No output buffer - just counting
        0,
        true,   // Add BOS
        false   // Not special tokens
    );

    if (n_tokens <= 0) {
        LOGE("Failed to tokenize prompt (error: %d)", n_tokens);
        throw_runtime_exception(env, "Failed to process input text");
        return string_to_jstring(env, "");
    }

    LOGI("Prompt token count: %d", n_tokens);

    // Check token count against context window
    int max_prompt_tokens = n_ctx - static_cast<int>(max_tokens) - 64;
    if (max_prompt_tokens < 32) max_prompt_tokens = 32;

    int actual_prompt_tokens = n_tokens;
    int tokens_to_skip = 0;

    if (n_tokens > max_prompt_tokens) {
        tokens_to_skip = n_tokens - max_prompt_tokens;
        actual_prompt_tokens = max_prompt_tokens;
        LOGW("Prompt too long (%d tokens), truncating to %d", n_tokens, max_prompt_tokens);
    }

    // Now tokenize for real with the proper buffer size
    std::vector<llama_token> tokens(n_tokens);
    int tokenized = llama_tokenize(
        model,
        prompt_str.c_str(),
        static_cast<int>(prompt_str.length()),
        tokens.data(),
        static_cast<int>(tokens.size()),
        true,   // Add BOS
        false   // Not special tokens
    );

    if (tokenized < 0) {
        LOGE("Tokenization failed on second pass: %d", tokenized);
        throw_runtime_exception(env, "Failed to tokenize input");
        return string_to_jstring(env, "");
    }

    tokens.resize(tokenized);

    // Skip leading tokens if we need to truncate
    if (tokens_to_skip > 0 && tokens_to_skip < static_cast<int>(tokens.size())) {
        tokens.erase(tokens.begin(), tokens.begin() + tokens_to_skip);
    }

    LOGI("Using %zu tokens for inference", tokens.size());

    // ========================================================================
    // Step 2: Evaluate prompt - process in batches to limit memory
    // ========================================================================
    int n_past = 0;
    const int batch_size = 256;

    for (size_t i = 0; i < tokens.size(); i += batch_size) {
        int n_eval = static_cast<int>(tokens.size() - i);
        if (n_eval > batch_size) n_eval = batch_size;

        // Use llama_decode for batch evaluation
        llama_batch batch = llama_batch_get_one(
            tokens.data() + i,
            n_eval,
            n_past,
            0  // No position shift
        );

        if (llama_decode(ctx, batch) != 0) {
            LOGE("Failed to evaluate prompt batch at position %zu", i);
            throw_runtime_exception(env, "Inference error during prompt processing");
            return string_to_jstring(env, "");
        }

        n_past += n_eval;
    }

    LOGI("Prompt evaluated. n_past=%d", n_past);

    // ========================================================================
    // Step 3: Generate response tokens
    // ========================================================================
    int n_len = static_cast<int>(max_tokens > 0 ? max_tokens : 256);
    std::string response;
    std::vector<char> piece_buffer(1024);

    // Thread-local random number generator (thread-safe)
    static thread_local std::mt19937 rng(std::random_device{}());

    // EOS (End of Sequence) token ID
    // Use the vocab-based EOS check
    llama_token eos_token = llama_vocab_eos(vocab);

    // Pre-allocate logits and probability buffers
    int n_vocab = llama_n_vocab(vocab);
    std::vector<float> probs(n_vocab);
    std::vector<std::pair<float, int>> sorted;
    sorted.reserve(n_vocab);

    for (int i = 0; i < n_len; i++) {
        // Get logits for the most recently decoded token (position 0 in batch output)
        float* logits = llama_get_logits_ith(ctx, 0);
        if (logits == nullptr) {
            LOGE("Failed to get logits at iteration %d", i);
            break;
        }

        // Step 1: Apply repetition penalty to logits (pre-softmax)
        // Penalizes tokens that have already appeared
        if (repeat_penalty > 1.0f && !response.empty()) {
            for (int j = 0; j < n_vocab; j++) {
                if (logits[j] > 0.0f) {
                    logits[j] /= repeat_penalty;
                } else if (logits[j] < 0.0f) {
                    logits[j] *= repeat_penalty;
                }
            }
        }

        // Step 2: Apply temperature scaling
        if (temperature > 0.0f && temperature != 1.0f) {
            for (int j = 0; j < n_vocab; j++) {
                logits[j] /= temperature;
            }
        }

        // Step 3: Softmax to get probabilities
        float max_val = *std::max_element(logits, logits + n_vocab);
        float sum = 0.0f;
        for (int j = 0; j < n_vocab; j++) {
            probs[j] = expf(logits[j] - max_val);
            sum += probs[j];
        }
        if (sum > 0.0f) {
            for (int j = 0; j < n_vocab; j++) {
                probs[j] /= sum;
            }
        }

        // Step 4: Sample the next token
        llama_token id = 0;

        if (temperature < 0.1f) {
            // Greedy: take the highest probability token
            float max_prob = 0.0f;
            for (int j = 0; j < n_vocab; j++) {
                if (probs[j] > max_prob) {
                    max_prob = probs[j];
                    id = static_cast<llama_token>(j);
                }
            }
        } else if (top_p > 0.0f && top_p < 1.0f) {
            // Top-p (nucleus) sampling
            sorted.clear();
            for (int j = 0; j < n_vocab; j++) {
                sorted.emplace_back(probs[j], j);
            }
            std::sort(sorted.begin(), sorted.end(),
                      [](const auto& a, const auto& b) { return a.first > b.first; });

            // Build the nucleus
            float cumsum = 0.0f;
            int nucleus_end = 0;
            for (int k = 0; k < static_cast<int>(sorted.size()); k++) {
                cumsum += sorted[k].first;
                nucleus_end = k + 1;
                if (cumsum >= top_p) break;
            }

            // Sample from the nucleus using thread-safe random
            std::uniform_real_distribution<float> dist(0.0f, cumsum);
            float r = dist(rng);
            cumsum = 0.0f;
            for (int k = 0; k < nucleus_end; k++) {
                cumsum += sorted[k].first;
                if (r < cumsum) {
                    id = static_cast<llama_token>(sorted[k].second);
                    break;
                }
            }
        } else {
            // Full distribution sampling
            std::uniform_real_distribution<float> dist(0.0f, 1.0f);
            float r = dist(rng);
            float cumsum = 0.0f;
            for (int j = 0; j < n_vocab; j++) {
                cumsum += probs[j];
                if (r < cumsum) {
                    id = static_cast<llama_token>(j);
                    break;
                }
            }
        }

        // Check for end of sequence
        if (id == eos_token) {
            LOGI("EOS token encountered at position %d", i);
            break;
        }

        // Convert token to text piece
        int n_chars = llama_token_to_piece(
            model, id,
            piece_buffer.data(),
            static_cast<int>(piece_buffer.size()) - 1,
            0,
            false
        );

        if (n_chars > 0) {
            piece_buffer[n_chars] = '\0';
            response.append(piece_buffer.data());
        }

        // Evaluate the generated token
        llama_batch eval_batch = llama_batch_get_one(&id, 1, n_past, 0);
        if (llama_decode(ctx, eval_batch) != 0) {
            LOGE("Failed to evaluate generated token at position %d", i);
            break;
        }
        n_past++;
    }

    LOGI("Generated %zu tokens, response length: %zu bytes",
         response.size() > 0 ? 1 : 0, response.length());
    LOGI("Response: %.150s...", response.c_str());

    return string_to_jstring(env, response);
}

// ============================================================================
// JNI: llamaFree
//
// Frees all resources associated with the llama context and model.
//
// Signature: (J)V
// ============================================================================
extern "C" JNIEXPORT void JNICALL
Java_com_narzoai_assistant_AIEngine_llamaFree(
    JNIEnv* env,
    jobject /* thiz */,
    jlong ctx_handle
) {
    LOGI("llamaFree called");

    if (ctx_handle == 0L) {
        LOGW("llamaFree called with null handle, ignoring");
        return;
    }

    ModelContext* mc = jlong_to_ptr<ModelContext>(ctx_handle);
    if (mc == nullptr) {
        LOGW("llamaFree: null context wrapper");
        return;
    }

    LOGI("Freeing llama resources...");

    // Free in reverse order of creation
    if (mc->ctx != nullptr) {
        llama_free(mc->ctx);
        mc->ctx = nullptr;
        LOGI("llama context freed");
    }

    if (mc->model != nullptr) {
        llama_free_model(mc->model);
        mc->model = nullptr;
        LOGI("llama model freed");
    }

    delete mc;
    LOGI("llamaFree complete - all resources released");
}
