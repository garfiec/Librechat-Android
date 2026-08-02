# Switchboard ProGuard Rules

# Keep Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.garfiec.librechat.**$$serializer { *; }
-keepclassmembers class com.garfiec.librechat.** { *** Companion; }
-keepclasseswithmembers class com.garfiec.librechat.** { kotlinx.serialization.KSerializer serializer(...); }

# Keep Ktor
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# Keep Room entities
-keep class com.garfiec.librechat.core.data.db.entity.** { *; }

# Koin - keep ViewModel constructors for reflection-based instantiation
-keep class * extends androidx.lifecycle.ViewModel { <init>(...); }
