# NikonLink ProGuard Rules
-keepattributes *Annotation*
-keep class com.nikonlink.data.db.entity.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
