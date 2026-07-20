# BouncyCastle reflects over provider classes at registration time.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Shizuku is compileOnly and reached by reflection; the provider must survive.
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# Tink (via androidx.security EncryptedSharedPreferences) references compile-only
# annotations from Error Prone and JSR-305 that are not on the runtime classpath.
# They are erased at build time and never needed at runtime, so silence R8's
# missing-class warnings rather than shipping the annotation jars.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# kotlinx.serialization generates serializers referenced only by name.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.hereliesaz.mcpserved.** {
    *** Companion;
}
-keepclasseswithmembers class com.hereliesaz.mcpserved.** {
    kotlinx.serialization.KSerializer serializer(...);
}
