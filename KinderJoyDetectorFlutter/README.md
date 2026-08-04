# 🎯 Kinder Joy Detector & 3D AR Overlay — Flutter (Android & iOS)

A complete cross-platform **Flutter** implementation of the offline Kinder Joy toy detector and 3D AR overlay system, converted from native Kotlin and Unity C#.

---

## 🎯 Supported Toys & Model
- **Detects:** Harry Potter · Hermione Granger · Batman · Flash
- **YOLO Engine:** `exp-3.onnx` (YOLO11n model, 38 MB)
- **3D AR Models:** `harry_potter.glb`, `hermione.glb`, `batman.glb`, `flash.glb`
- **Platforms:** Android (min API 21) & iOS (iOS 13.0+)

---

## 📁 Project Architecture

```
KinderJoyDetectorFlutter/
├── assets/
│   ├── exp-3.onnx                  ← Trained YOLO11n ONNX model
│   └── models/                     ← 3D GLB model assets
│       ├── harry_potter.glb
│       ├── hermione.glb
│       ├── batman.glb
│       └── flash.glb
├── lib/
│   ├── main.dart                   ← Main app entry point & dark theme
│   ├── models/
│   │   └── detection.dart          ← Detection data model, colors & thresholds
│   ├── services/
│   │   ├── yolo_detector.dart      ← ONNX Runtime engine, letterboxing & NMS
│   │   └── detector_manager.dart   ← 3-frame confirmation state machine & progress
│   └── views/
│       ├── camera_detector_view.dart← Hardware camera preview & main stack
│       ├── overlay_painter.dart    ← CustomPainter for guide box & bounding boxes
│       ├── ar_model_overlay.dart   ← 3D GLB model renderer over detected toy
│       └── result_card_view.dart   ← Glassmorphic confirmation modal & scan again
├── android/                        ← Android native project files & Manifest
├── ios/                            ← iOS native project files & Info.plist
└── pubspec.yaml                    ← Flutter dependencies & asset declarations
```

---

## ⚡ Key Features

1. **YOLO11n ONNX Engine (`yolo_detector.dart`)**:
   - Letterboxing & RGB normalization.
   - Adaptive rotation augmentation (0°, 90°, 180°, 270°).
   - Per-class thresholding (Harry: 0.35, Hermione: 0.40, Batman: 0.45, Flash: 0.38).
   - Single-pass Non-Maximum Suppression (NMS IoU 0.40).

2. **Temporal Stability State Machine (`detector_manager.dart`)**:
   - Requires **3 continuous stable frames** with **>= 60% confidence** and **>= 50% guide box coverage**.
   - **Unknown Object Timeout**: 35-frame timeout.
   - **Temporal Box Smoothing**: Holds last-seen bounding box (`HOLD_FRAMES = 6`).
   - **AR Lock Mode**: Locks toy class upon confirmation, continuously updating 3D position.

3. **High-Performance UI Overlay (`overlay_painter.dart`)**:
   - Guide box aperture cutout with dark vignette background.
   - Dynamic circular radial progress ring.
   - Real-time bounding boxes with class colors & confidence pills.

4. **3D AR Model Renderer (`ar_model_overlay.dart`)**:
   - Interactive 3D GLB model view rendered over detected bounding box.

---

## 🚀 Building APK on GitHub Actions

This repository includes a pre-configured GitHub Actions workflow in `.github/workflows/flutter_build.yml`.

When you push code to GitHub:
1. GitHub Actions automatically installs Flutter & Java 17.
2. Runs `flutter pub get`.
3. Builds the Release APK (`app-release.apk`).
4. Uploads `kinder-joy-detector-apk` under the workflow **Artifacts** tab for direct download!
