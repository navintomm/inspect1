# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep OpenCV classes
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# Keep CameraX
-keep class androidx.camera.** { *; }

# Keep app model classes (used in LiveData)
-keep class com.colorinspector.app.model.** { *; }
