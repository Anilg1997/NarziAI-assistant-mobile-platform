# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the Android SDK tools proguard configuration.

# Keep model-related classes
-keep class com.narzoai.assistant.** { *; }

# Keep JNI native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep llama.cpp native interface
-keep class com.narzoai.assistant.AIEngine { *; }
-keep class com.narzoai.assistant.VoiceEngine { *; }

# Keep ML Kit model classes
-keep class com.google.mlkit.** { *; }

# Keep Gson serialization classes
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * extends com.google.gson.TypeAdapter

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# AndroidX
-keep class androidx.** { *; }
-keep interface androidx.** { *; }
-dontwarn androidx.**

# Google Play Services / Firebase
-dontwarn com.google.android.gms.**
-keep class com.google.android.gms.** { *; }

# Material Design
-dontwarn com.google.android.material.**
-keep class com.google.android.material.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# CameraX
-keep class androidx.camera.** { *; }

# Remove logging in release builds
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
