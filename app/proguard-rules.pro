# Keep kotlinx.serialization generated serializers for engine models
-keepclassmembers class dev.muisc.** { *** Companion; }
-keepclasseswithmembers class dev.muisc.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class dev.muisc.**$$serializer { *; }
-dontwarn javax.sound.**
