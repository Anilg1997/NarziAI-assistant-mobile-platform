# ============================================================================
# CMakeLists.txt - NarzoAI Assistant Native Build
# Builds llama.cpp and whisper.cpp libraries for Android via NDK.
# ============================================================================

cmake_minimum_required(VERSION 3.22)
project("narzoai_native" LANGUAGES C CXX)

# ============================================================================
# Project Configuration
# ============================================================================

# Android ABI detection - set by NDK toolchain
message(STATUS "Building for Android ABI: ${ANDROID_ABI}")
message(STATUS "Android Platform: ${ANDROID_PLATFORM}")
message(STATUS "NDK Version: ${ANDROID_NDK_VERSION}")

# C++ Standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

# Position Independent Code
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Optimization flags for release builds
set(CMAKE_CXX_FLAGS_RELEASE "-O2 -DNDEBUG -flto")
set(CMAKE_C_FLAGS_RELEASE "-O2 -DNDEBUG -flto")

# ARM-specific optimizations
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(LLAMA_ARCH "arm64")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8.2-a+fp16+rcpc+dotprod+crypto")
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -march=armv8.2-a+fp16+rcpc+dotprod+crypto")
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(LLAMA_ARCH "armv7")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv7-a -mfloat-abi=softfp -mfpu=neon")
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -march=armv7-a -mfloat-abi=softfp -mfpu=neon")
endif()

# ============================================================================
# Determine whether to download sources or use local copies
# ============================================================================

# Option to use locally cloned repositories instead of FetchContent
# Set this to ON and place llama.cpp and whisper.cpp in the cpp/ directory
option(USE_LOCAL_REPOS "Use local cloned repos instead of FetchContent" OFF)

if(USE_LOCAL_REPOS)
    message(STATUS "Using local repository copies")
    set(LLAMA_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp")
    set(WHISPER_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp")
else()
    message(STATUS "Will download repositories via FetchContent")
endif()

# ============================================================================
# llama.cpp Library
# ============================================================================

if(USE_LOCAL_REPOS)
    # Use local copy
    if(EXISTS "${LLAMA_CPP_DIR}/CMakeLists.txt")
        message(STATUS "Found local llama.cpp at: ${LLAMA_CPP_DIR}")
        add_subdirectory(${LLAMA_CPP_DIR} llama.cpp EXCLUDE_FROM_ALL)
    else()
        message(FATAL_ERROR "USE_LOCAL_REPOS=ON but llama.cpp not found at ${LLAMA_CPP_DIR}")
    endif()
else()
    # Download llama.cpp using FetchContent
    include(FetchContent)

    # Use a stable release tag for llama.cpp
    set(LLAMA_CPP_VERSION "b3865")  # Stable release with llama.h API
    set(LLAMA_CPP_REPO "https://github.com/ggml-org/llama.cpp")

    message(STATUS "Fetching llama.cpp version: ${LLAMA_CPP_VERSION}")

    # Configure llama.cpp build options for Android
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build shared libraries" FORCE)
    set(LLAMA_BUILD_TESTS OFF CACHE BOOL "Build tests" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "Build examples" FORCE)
    set(LLAMA_BUILD_SERVER OFF CACHE BOOL "Build server" FORCE)
    set(GGML_OPENMP OFF CACHE BOOL "OpenMP support" FORCE)
    set(GGML_LLAMAFILE OFF CACHE BOOL "llamafile support" FORCE)
    set(GGML_BLAS OFF CACHE BOOL "BLAS support" FORCE)
    set(GGML_CUDA OFF CACHE BOOL "CUDA support" FORCE)
    set(GGML_METAL OFF CACHE BOOL "Metal support" FORCE)
    set(GGML_VULKAN OFF CACHE BOOL "Vulkan support" FORCE)
    set(GGML_SYCL OFF CACHE BOOL "SYCL support" FORCE)
    set(GGML_CPU ON CACHE BOOL "CPU support" FORCE)
    set(GGML_NATIVE OFF CACHE BOOL "Native optimizations" FORCE)

    # Disable unnecessary GGML backends for smaller binary size
    set(GGML_CUDA OFF)
    set(GGML_METAL OFF)
    set(GGML_VULKAN OFF)
    set(GGML_SYCL OFF)
    set(GGML_OPENCL OFF)
    set(GGML_RPC OFF)

    # Use GGML static library to avoid missing symbols
    set(GGML_STATIC ON CACHE BOOL "Build GGML as static library" FORCE)

    FetchContent_Declare(
        llama.cpp
        GIT_REPOSITORY ${LLAMA_CPP_REPO}
        GIT_TAG ${LLAMA_CPP_VERSION}
        GIT_SHALLOW TRUE
        GIT_PROGRESS TRUE
        OVERRIDE_FIND_PACKAGE
    )

    # Set llama.cpp options before adding
    set(LLAMA_STATIC ON CACHE BOOL "Build llama.cpp as static library" FORCE)

    FetchContent_MakeAvailable(llama.cpp)

    # After fetch, the targets are available
    if(NOT TARGET llama)
        message(WARNING "llama target not found after FetchContent, checking for alternative target names...")
        if(TARGET llama_static)
            set(LLAMA_TARGET llama_static)
        else()
            # Create a custom target if needed
            add_library(imported_llama INTERFACE)
            set(LLAMA_TARGET imported_llama)
        endif()
    else()
        set(LLAMA_TARGET llama)
    endif()
endif()

message(STATUS "llama.cpp target: ${LLAMA_TARGET}")

# ============================================================================
# whisper.cpp Library
# ============================================================================

if(USE_LOCAL_REPOS)
    if(EXISTS "${WHISPER_CPP_DIR}/CMakeLists.txt")
        message(STATUS "Found local whisper.cpp at: ${WHISPER_CPP_DIR}")

        # Configure whisper.cpp build options for Android
        set(WHISPER_BUILD_TESTS OFF CACHE BOOL "Build tests" FORCE)
        set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "Build examples" FORCE)
        set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "Warnings" FORCE)

        add_subdirectory(${WHISPER_CPP_DIR} whisper.cpp EXCLUDE_FROM_ALL)
    else()
        message(FATAL_ERROR "USE_LOCAL_REPOS=ON but whisper.cpp not found at ${WHISPER_CPP_DIR}")
    endif()
else()
    # Download whisper.cpp using FetchContent
    set(WHISPER_CPP_VERSION "v1.7.4")  # Stable release
    set(WHISPER_CPP_REPO "https://github.com/ggerganov/whisper.cpp")

    message(STATUS "Fetching whisper.cpp version: ${WHISPER_CPP_VERSION}")

    # Configure whisper.cpp build options for Android
    set(WHISPER_BUILD_TESTS OFF CACHE BOOL "Build tests" FORCE)
    set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "Build examples" FORCE)
    set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "Warnings" FORCE)
    set(WHISPER_SDL2 OFF CACHE BOOL "SDL2 support" FORCE)
    set(WHISPER_COREML OFF CACHE BOOL "CoreML support" FORCE)

    FetchContent_Declare(
        whisper.cpp
        GIT_REPOSITORY ${WHISPER_CPP_REPO}
        GIT_TAG ${WHISPER_CPP_VERSION}
        GIT_SHALLOW TRUE
        GIT_PROGRESS TRUE
        OVERRIDE_FIND_PACKAGE
    )

    FetchContent_MakeAvailable(whisper.cpp)

    if(NOT TARGET whisper)
        message(WARNING "whisper target not found after FetchContent, checking for alternative names...")
        if(TARGET whisper_static)
            set(WHISPER_TARGET whisper_static)
        else()
            add_library(imported_whisper INTERFACE)
            set(WHISPER_TARGET imported_whisper)
        endif()
    else()
        set(WHISPER_TARGET whisper)
    endif()
endif()

message(STATUS "whisper.cpp target: ${WHISPER_TARGET}")

# ============================================================================
# NarzoAI JNI Bridge Library
# ============================================================================

# Source files for our JNI bridge
set(NARZOAI_JNI_SOURCES
    llama_jni.cpp
    whisper_jni.cpp
)

# Create the shared library that will be loaded by System.loadLibrary()
add_library(narzoai_jni SHARED ${NARZOAI_JNI_SOURCES})

# Include directories for llama.cpp and whisper.cpp
target_include_directories(narzoai_jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# Link against the native libraries
target_link_libraries(narzoai_jni PRIVATE
    ${LLAMA_TARGET}
    ${WHISPER_TARGET}
    log      # Android logging
    dl       # Dynamic loading
    z        # Compression (needed by GGML)
)

# Set compiler flags for our JNI library
target_compile_options(narzoai_jni PRIVATE
    -Wall
    -Wextra
    -Wno-unused-parameter
    -Wno-sign-compare
)

# Set JNI library properties
set_target_properties(narzoai_jni PROPERTIES
    POSITION_INDEPENDENT_CODE ON
    LINKER_LANGUAGE CXX
)

# ============================================================================
# Post-build: Copy built libraries to jniLibs for APK packaging
# Note: Gradle's externalNativeBuild usually handles this automatically
# ============================================================================

message(STATUS "============================================")
message(STATUS "NarzoAI Native Build Configuration:")
message(STATUS "  ABI: ${ANDROID_ABI}")
message(STATUS "  C++ Standard: 17")
message(STATUS "  Platform: ${ANDROID_PLATFORM}")
message(STATUS "  llama.cpp: ${LLAMA_CPP_VERSION}")
message(STATUS "  whisper.cpp: ${WHISPER_CPP_VERSION}")
message(STATUS "  JNI Library: narzoai_jni")
message(STATUS "============================================")

# ============================================================================
# Build instructions for development
# ============================================================================
# To build manually:
#   mkdir build && cd build
#   cmake -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
#         -DANDROID_ABI=arm64-v8a \
#         -DANDROID_PLATFORM=android-29 \
#         ..
#   cmake --build .
#
# Or via Android Studio / Gradle (recommended):
#   ./gradlew assembleDebug
