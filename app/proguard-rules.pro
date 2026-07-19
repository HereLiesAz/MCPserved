# BouncyCastle reflects over provider classes at registration time.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Shizuku is compileOnly and reached by reflection; the provider must survive.
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# kotlinx.serialization generates serializers referenced only by name.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.hereliesaz.mcpserved.** {
    *** Companion;
}
-keepclasseswithmembers class com.hereliesaz.mcpserved.** {
    kotlinx.serialization.KSerializer serializer(...);
}
