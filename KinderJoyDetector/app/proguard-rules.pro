# Keep ONNX Runtime
-keep class ai.onnxruntime.** { *; }
-keep class com.microsoft.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Keep our detector classes
-keep class com.madrasmindworks.kinderjoydetector.** { *; }

# Keep CameraX
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# General Android rules
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
