// ============================================================================
// jni_utils.h - Common JNI utility functions for NarzoAI Assistant
// Provides helper functions for converting between Java/JNI types and C++ types.
// ============================================================================

#ifndef NARZOAI_JNI_UTILS_H
#define NARZOAI_JNI_UTILS_H

#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <android/log.h>

// ============================================================================
// Logging Macros
// ============================================================================

#define LOG_TAG "NarzoAI_Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ============================================================================
// JNI String Conversion Utilities
// ============================================================================

/**
 * Convert a jstring to a C++ std::string.
 * Returns empty string if jstr is null.
 */
inline std::string jstring_to_string(JNIEnv* env, jstring jstr) {
    if (jstr == nullptr) {
        return "";
    }

    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    if (chars == nullptr) {
        return "";  // Out of memory
    }

    std::string result(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return result;
}

/**
 * Convert a C++ std::string to a jstring.
 * Returns nullptr on failure.
 */
inline jstring string_to_jstring(JNIEnv* env, const std::string& str) {
    return env->NewStringUTF(str.c_str());
}

// ============================================================================
// JNI Array Conversion Utilities
// ============================================================================

/**
 * Convert a jfloatArray to a C++ float vector.
 */
inline std::vector<float> jfloatarray_to_float_vector(JNIEnv* env, jfloatArray jarray) {
    if (jarray == nullptr) {
        return {};
    }

    jsize length = env->GetArrayLength(jarray);
    std::vector<float> result(length);

    jfloat* elements = env->GetFloatArrayElements(jarray, nullptr);
    if (elements == nullptr) {
        return {};  // Out of memory
    }

    memcpy(result.data(), elements, length * sizeof(float));
    env->ReleaseFloatArrayElements(jarray, elements, JNI_ABORT);

    return result;
}

// ============================================================================
// JNI Exception Handling
// ============================================================================

/**
 * Check if a Java exception occurred and log it.
 * Returns true if an exception occurred.
 */
inline bool check_java_exception(JNIEnv* env) {
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        return true;
    }
    return false;
}

/**
 * Throw a Java RuntimeException with the given message.
 */
inline void throw_runtime_exception(JNIEnv* env, const std::string& message) {
    jclass exception_class = env->FindClass("java/lang/RuntimeException");
    if (exception_class != nullptr) {
        env->ThrowNew(exception_class, message.c_str());
        env->DeleteLocalRef(exception_class);
    }
}

/**
 * Throw a Java OutOfMemoryError with the given message.
 */
inline void throw_out_of_memory_error(JNIEnv* env, const std::string& message) {
    jclass exception_class = env->FindClass("java/lang/OutOfMemoryError");
    if (exception_class != nullptr) {
        env->ThrowNew(exception_class, message.c_str());
        env->DeleteLocalRef(exception_class);
    }
}

// ============================================================================
// Pointer/Handle Utilities
// ============================================================================

/**
 * Store a native pointer in a Java long field (opaque handle pattern).
 */
template<typename T>
inline jlong ptr_to_jlong(T* ptr) {
    return static_cast<jlong>(reinterpret_cast<intptr_t>(ptr));
}

/**
 * Retrieve a native pointer from a Java long field.
 */
template<typename T>
inline T* jlong_to_ptr(jlong jptr) {
    return reinterpret_cast<T*>(static_cast<intptr_t>(jptr));
}

// ============================================================================
// Android-Specific Utilities
// ============================================================================



#endif // NARZOAI_JNI_UTILS_H
