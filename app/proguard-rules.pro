# kotlinx.serialization: keep generated serializers for @Serializable models.
-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers @kotlinx.serialization.Serializable class ca.mslab.forge.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class ca.mslab.forge.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class ca.mslab.forge.**$$serializer { *; }

# The terminal WebView bridge is invoked by name from JavaScript.
-keepclassmembers class ca.mslab.forge.ui.TerminalBridge { @android.webkit.JavascriptInterface <methods>; }

# OkHttp / Markwon optional platform integrations.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.commonmark.ext.gfm.strikethrough.**
-dontwarn io.noties.markwon.**
