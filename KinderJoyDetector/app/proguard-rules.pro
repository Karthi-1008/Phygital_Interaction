# Keep ONNX Runtime JNI entry points
-keep class ai.onnxruntime.** { *; }
-keep class com.microsoft.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
-dontwarn com.microsoft.onnxruntime.**

# Keep CameraX
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# Keep Activity, Custom Views, and Detector
-keep class com.madrasmindworks.kinderjoydetector.MainActivity { *; }
-keep class com.madrasmindworks.kinderjoydetector.YoloDetector** { *; }
-keep class com.madrasmindworks.kinderjoydetector.OverlayView {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# General R8 optimizations & dead-code elimination
-dontwarn kotlin.reflect.**
-repackageclasses ''
-allowaccessmodification

# Strip verbose and debug logging in release APK
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}

