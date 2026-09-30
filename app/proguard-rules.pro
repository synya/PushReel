# Link Audio uses statically named JNI entry points in libpushreel_link_audio.so.
-keep class com.pushreel.linkaudio.JniNativeBridge { *; }

# Keep actionable diagnostics in release while removing verbose camera/codec chatter.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
