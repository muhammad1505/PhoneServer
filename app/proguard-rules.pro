# PocketShare release rules.
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-keepclassmembers class kotlinx.serialization.json.** { *; }
-keep,includedescriptorclasses class dev.pocketshare.**$$serializer { *; }
-keepclassmembers class dev.pocketshare.** { *** Companion; }
-keepclasseswithmembers class dev.pocketshare.** { kotlinx.serialization.KSerializer serializer(...); }

# Ktor uses service loading and coroutine internals.
-keep class io.ktor.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
-dontwarn kotlinx.coroutines.**
