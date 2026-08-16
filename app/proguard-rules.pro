# ── Music Studio ProGuard Rules ────────────────────────────────────────

# Keep data models (Parcelable, serialisation)
-keep class com.musicstudio.app.data.** { *; }

# Keep all audio engine classes (used via reflection by some DSP paths)
-keep class com.musicstudio.app.audio.** { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Kotlin metadata (needed for reflection in data classes)
-keepattributes RuntimeVisibleAnnotations
-keep class kotlin.Metadata { *; }

# AndroidX navigation
-keepnames class androidx.navigation.fragment.NavHostFragment

# Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ── General ─────────────────────────────────────────────────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
