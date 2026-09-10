# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-keep class com.huanchengfly.icebridge.modules.IceBridgeModule { public <init>(); }
-keep class com.huanchengfly.icebridge.modules.IceBridgeModule$* { *; }


# IceBridge is loaded by LSPosed from a resource-listed class rather than by an
# Android component entry point. Keep the entry point and its nested callback/state types.
-keep class com.huanchengfly.icebridge.modules.IceBridgeModule { *; }
-keep class com.huanchengfly.icebridge.modules.IceBridgeModule$* { *; }

# These classes cross the system_server/content-provider boundary or are used by
# reflection/serialization. Keep names, constructors and members stable.
-keep class com.huanchengfly.icebridge.SystemServerConfigContract { *; }
-keep class com.huanchengfly.icebridge.providers.ConfigProvider { *; }
-keep class com.huanchengfly.icebridge.databases.AppConfig { *; }
-keep class com.huanchengfly.icebridge.beans.** { *; }
-keep class com.huanchengfly.icebridge.automation.** { *; }

# Android framework components are referenced by manifest name; keeping the
# application-owned component classes also protects non-standard callbacks.
-keep class com.huanchengfly.icebridge.base.MyApplication { *; }
-keep class com.huanchengfly.icebridge.receivers.** { *; }
-keep class com.huanchengfly.icebridge.services.** { *; }
-keep class com.huanchengfly.icebridge.activities.** { *; }

# Gson uses reflective field access and generic signatures for API payloads.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# LitePal discovers mapped model classes through the litepal.xml asset.
-keep class org.litepal.** { *; }

# OkHttp probes for optional TLS providers. These providers are not packaged by
# IceBridge; keep R8 from treating runtime platform detection as a hard dependency.
-dontwarn org.bouncycastle.jsse.BCSSLParameters
-dontwarn org.bouncycastle.jsse.BCSSLSocket
-dontwarn org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
-dontwarn org.conscrypt.Conscrypt$Version
-dontwarn org.conscrypt.Conscrypt
-dontwarn org.conscrypt.ConscryptHostnameVerifier
-dontwarn org.openjsse.javax.net.ssl.SSLParameters
-dontwarn org.openjsse.javax.net.ssl.SSLSocket
-dontwarn org.openjsse.net.ssl.OpenJSSE
