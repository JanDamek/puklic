# Release shrinking/obfuscation of the desktop app image (PUKLIC-137: idle RSS).
# Reflective or JNI-bound libraries and own code are kept whole; kotlinx.coroutines and ktor must stay
# unobfuscated (obfuscated coroutines fail bytecode verification, ktor loads engines reflectively).
# ServiceLoader implementations are listed explicitly because ProGuard renames them otherwise.
-dontoptimize
-dontwarn **
-ignorewarnings

-keep class dev.puklic.desktop.MainKt { public static void main(java.lang.String[]); }

-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,AnnotationDefault,Exceptions

-keep class dev.puklic.** { *; }
-keepclassmembers class **$$serializer { *; }
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    static **$* Companion;
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

-keep class org.jetbrains.skia.** { *; }
-keep class org.jetbrains.skiko.** { *; }
-keep class com.sun.jna.** { *; }
-keep class * extends com.sun.jna.** { *; }
-keep class org.freedesktop.dbus.** { *; }
-keep class jnr.** { *; }
-keep class com.kenai.** { *; }
-keep class org.sqlite.** { *; }
-keep class org.bytedeco.** { *; }
-keep class org.slf4j.** { *; }
-keep class io.ktor.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class * implements coil3.util.FetcherServiceLoaderTarget { *; }
-keep class * implements com.arkivanov.decompose.mainthread.MainThreadChecker { *; }
-keep class * implements org.freedesktop.dbus.spi.transport.ITransportProvider { *; }

-keep class * implements org.slf4j.spi.SLF4JServiceProvider { *; }
