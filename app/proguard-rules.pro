# Proguard rules for Todd Android Agent

# Keep Room entities and DAOs
-keep class com.todd.data.local.** { *; }

# Keep Domain Models
-keep class com.todd.core.model.** { *; }

# Keep Coroutines internals
-keep class kotlinx.coroutines.** { *; }

# Firebase Vertex AI rules
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn com.google.firebase.vertexai.**
