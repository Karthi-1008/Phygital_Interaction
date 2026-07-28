# ProGuard rules for KinderJoyDetector

# OpenCV
-keep class org.opencv.** { *; }
-keepclassmembers class org.opencv.** { *; }

# SceneView / Filament
-keep class com.google.android.filament.** { *; }
-keep class io.github.sceneview.** { *; }
-dontwarn io.github.sceneview.**

# kotlin-math
-keep class dev.romainguy.kotlin.math.** { *; }
