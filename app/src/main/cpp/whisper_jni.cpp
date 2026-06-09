// ============================================================================
// whisper_jni.cpp - JNI Bridge for whisper.cpp
// Connects Kotlin VoiceEngine.kt native methods to whisper.cpp C API.
//
// JNI Method Mapping (from VoiceEngine.kt):
//   whisperInit -> Java_com_narzoai_assistant_VoiceEngine_whisperInit
//   whisperFull -> Java_com_narzoai_assistant_VoiceEngine_whisperFull
//   whisperFree -> Java_com_narzoai_assistant_VoiceEngine_whisperFree
// ============================================================================

#include "jni_utils.h"
#include <cstring>
#include <vector>
#include <string>
#include <sstream>

// Include whisper.h from the whisper.cpp library
#include "whisper.h"

// ============================================================================
// Constants
// ============================================================================

static constexpr int WHISPER_N_THREADS = 4;    // Optimal for mobile CPUs
static constexpr bool WHISPER_USE_GPU = false;  // CPU-only to save GPU memory

// Whisper sample rate is always 16kHz for whisper.cpp
static constexpr int WHISPER_SAMPLE_RATE_VAL = 16000;

/**
 * Validate that a native pointer exists.
 */
static bool validate_whisper_ptr(jlong ptr, const char* name) {
    if (ptr == 0L) {
        LOGE("Null pointer in %s - whisper not initialized", name);
        return false;
    }
    return true;
}

// ============================================================================
// JNI: whisperInit
//
// Loads whisper model from file and returns a context handle.
//
// Signature: (Ljava/lang/String;)J
// ============================================================================
extern "C" JNIEXPORT jlong JNICALL
Java_com_narzoai_assistant_VoiceEngine_whisperInit(
    JNIEnv* env,
    jobject /* thiz */,
    jstring model_path
) {
    LOGI("whisperInit called");

    std::string model_path_str = jstring_to_string(env, model_path);
    if (model_path_str.empty()) {
        LOGE("Whisper model path is empty");
        throw_runtime_exception(env, "Whisper model path cannot be empty");
        return 0L;
    }

    LOGI("Loading whisper model from: %s", model_path_str.c_str());

    // Configure whisper context parameters
    struct whisper_context_params ctx_params = whisper_context_default_params();
    ctx_params.use_gpu = WHISPER_USE_GPU;
    ctx_params.flash_attn = false;
    ctx_params.gpu_device = 0;

    LOGI("whisper params: use_gpu=%d, flash_attn=%d",
         ctx_params.use_gpu, ctx_params.flash_attn);

    // Initialize the whisper model
    struct whisper_context* ctx = whisper_init_from_file_with_params(
        model_path_str.c_str(),
        ctx_params
    );

    if (ctx == nullptr) {
        LOGE("Failed to initialize whisper model from: %s", model_path_str.c_str());
        LOGE("Ensure the file is a valid ggml-tiny.bin from ggerganov/whisper.cpp");
        throw_runtime_exception(env,
            "Failed to load whisper model. Ensure the file is a valid ggml-tiny.bin "
            "from HuggingFace (ggerganov/whisper.cpp).");
        return 0L;
    }

    LOGI("Whisper model loaded successfully");
    LOGI("  Sample rate: %d", whisper_sample_rate(ctx));
    LOGI("  N channels: %d", whisper_n_channels(ctx));
    LOGI("  N languages: %d", whisper_lang_max_id(ctx));

    return ptr_to_jlong(ctx);
}

// ============================================================================
// JNI: whisperFull
//
// Transcribes audio samples to text using the loaded whisper model.
//
// Signature: (J[FI)Ljava/lang/String;
// ============================================================================
extern "C" JNIEXPORT jstring JNICALL
Java_com_narzoai_assistant_VoiceEngine_whisperFull(
    JNIEnv* env,
    jobject /* thiz */,
    jlong ctx_handle,
    jfloatArray samples,
    jint n_samples
) {
    LOGI("whisperFull called");

    if (!validate_whisper_ptr(ctx_handle, "whisperFull")) {
        return string_to_jstring(env, "");
    }

    if (samples == nullptr || n_samples <= 0) {
        LOGW("whisperFull: no audio samples provided");
        return string_to_jstring(env, "");
    }

    struct whisper_context* ctx = jlong_to_ptr<whisper_context>(ctx_handle);
    if (ctx == nullptr) {
        LOGE("whisperFull: invalid context pointer");
        return string_to_jstring(env, "");
    }

    // Convert jfloatArray to float vector
    std::vector<float> pcm_data = jfloatarray_to_float_vector(env, samples);

    int actual_samples = static_cast<int>(pcm_data.size());
    if (actual_samples < 160) {  // Minimum ~10ms at 16kHz
        LOGW("Audio too short: %d samples (minimum 160)", actual_samples);
        return string_to_jstring(env, "");
    }

    float duration_sec = static_cast<float>(actual_samples) /
                         static_cast<float>(WHISPER_SAMPLE_RATE_VAL);
    LOGI("Processing %d audio samples (%.2f seconds)", actual_samples, duration_sec);

    // Configure whisper full parameters
    struct whisper_full_params wparams = whisper_full_default_params(
        WHISPER_SAMPLING_GREEDY
    );

    wparams.n_threads = WHISPER_N_THREADS;
    wparams.sample_rate = WHISPER_SAMPLE_RATE_VAL;
    wparams.language = "en";
    wparams.n_max_text_ctx = 60;
    wparams.offset_ms = 0;
    wparams.duration_ms = 0;

    // Speed optimizations for mobile
    wparams.speed_up = false;
    wparams.debug_mode = false;
    wparams.audio_ctx = 0;

    // Output filtering
    wparams.suppress_non_speech_tokens = true;
    wparams.tdrz_enable = false;
    wparams.initial_prompt = nullptr;

    // Token-level settings
    wparams.token_timestamps = false;
    wparams.thold_pt = 0.01f;
    wparams.thold_ptsum = 0.01f;
    wparams.max_len = 0;
    wparams.split_on_word = false;
    wparams.max_tokens = 0;

    // Run the transcription
    LOGI("Running whisper transcription...");
    int result = whisper_full(ctx, wparams, pcm_data.data(), actual_samples);

    if (result != 0) {
        LOGE("whisper_full failed with error code: %d", result);
        return string_to_jstring(env, "");
    }

    // Collect transcribed text from all segments
    int n_segments = whisper_full_n_segments(ctx);
    LOGI("Transcription complete: %d segments", n_segments);

    std::string transcribed_text;
    for (int i = 0; i < n_segments; i++) {
        const char* segment_text = whisper_full_get_segment_text(ctx, i);
        if (segment_text != nullptr) {
            if (!transcribed_text.empty()) {
                transcribed_text += " ";
            }
            transcribed_text += segment_text;
        }
    }

    LOGI("Transcribed text: \"%s\"", transcribed_text.c_str());
    return string_to_jstring(env, transcribed_text);
}

// ============================================================================
// JNI: whisperFree
//
// Releases all whisper model resources.
//
// Signature: (J)V
// ============================================================================
extern "C" JNIEXPORT void JNICALL
Java_com_narzoai_assistant_VoiceEngine_whisperFree(
    JNIEnv* env,
    jobject /* thiz */,
    jlong ctx_handle
) {
    LOGI("whisperFree called");

    if (ctx_handle == 0L) {
        LOGW("whisperFree called with null handle, ignoring");
        return;
    }

    struct whisper_context* ctx = jlong_to_ptr<whisper_context>(ctx_handle);
    if (ctx == nullptr) {
        LOGW("whisperFree: null context, ignoring");
        return;
    }

    LOGI("Freeing whisper resources...");
    whisper_free(ctx);
    LOGI("whisperFree complete");
}
