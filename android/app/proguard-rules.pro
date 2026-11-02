# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# WebRTC uses JNI callbacks
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
