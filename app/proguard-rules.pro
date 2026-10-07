# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.arnav.music.**$$serializer { *; }
-keepclassmembers class com.arnav.music.** { *** Companion; }
-keepclasseswithmembers class com.arnav.music.** { kotlinx.serialization.KSerializer serializer(...); }

# Firestore maps POJOs reflectively for the few typed documents we use
-keepclassmembers class com.arnav.music.data.sync.remote.** { <init>(); <fields>; }

# YouTube IFrame player bridge (JavaScript interface)
-keep class com.pierfrancescosoffritti.androidyoutubeplayer.** { *; }

# OkHttp optional platform classes
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Release builds drop debug/verbose logging, including libraries' (e.g. the App Check debug
# provider prints its secret at debug level).
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
