# Codex for Android — ProGuard rules.
# Vendored terminal emulator is reached from the native layer / reflection.
-keep class com.termux.terminal.** { *; }
-keep class com.termux.view.** { *; }
-keepclassmembers class com.termux.terminal.TerminalSession {
  private static java.io.FileDescriptor wrapFileDescriptor(int, com.termux.terminal.TerminalSessionClient);
}

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class ai.codex.android.**$$serializer { *; }
-keepclasseswithmembers class ai.codex.android.** {
  kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
