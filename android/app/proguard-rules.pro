# ProGuard rules for Monotonic
-keep class com.monotonic.metronome.audio.NativeMetronomeEngine { *; }
-keepclasseswithmembers class * {
    native <methods>;
}
