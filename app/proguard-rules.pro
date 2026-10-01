# Keep kotlinx.serialization generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class dev.ryan.opencode.** {
    *** Companion;
}
-keepclasseswithmembers class dev.ryan.opencode.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.ryan.opencode.**$$serializer { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**

# sshj. Its HostKeyVerifier, KeyType and signature classes are reached
# reflectively by BouncyCastle and by sshj's own dispatch, and R8 will happily
# remove or rename them — which fails at runtime with a bare
# NoSuchAlgorithmException rather than a build error.
-keep class net.schmizz.sshj.** { *; }
-dontwarn net.schmizz.sshj.**
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn net.i2p.crypto.eddsa.**
-dontwarn org.slf4j.**
