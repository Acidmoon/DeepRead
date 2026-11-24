# Keep Room entities and schema bits
-keep class com.vibecoding.reader.data.db.** { *; }
-keep class com.vibecoding.reader.domain.model.** { *; }

# PdfBox-android uses reflection
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**
-dontwarn org.bouncycastle.**
-dontwarn org.apache.**

# Kotlin / coroutines
-keepclassmembers class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# Compose / enums used in settings
-keepclassmembers enum * { *; }
