# KinderJoy Detector & 3D AR Overlay — Unity C# Project

This is a complete C# Unity implementation converted from the Android Kotlin YOLO11n ONNX toy detector and 3D AR overlay system.

## 📁 Project Structure

```
KinderJoyDetectorUnity/
├── Assets/
│   ├── Scripts/
│   │   ├── YoloDetector.cs          # YOLO11n ONNX inference engine, preprocessing, rotation aug, NMS
│   │   ├── MainDetectorManager.cs   # Detection state machine, 3-frame confirmation, progress & reset logic
│   │   ├── ArModelViewer.cs         # 3D AR model placement, screen-to-world mapping, smooth scale/rotation
│   │   ├── CameraController.cs      # WebCamTexture camera feed acquisition & UI raw image binding
│   │   ├── GuideBoxUI.cs            # Overlay box drawing, progress ring, status text & result banner UI
│   │   └── KinderJoyDetector.asmdef # Assembly Definition file
│   └── StreamingAssets/
│       ├── exp-3.onnx               # Trained YOLO11n ONNX model weights (38 MB)
│       └── models/                  # 3D GLB model assets
│           ├── harry_potter.glb
│           ├── hermione.glb
│           ├── batman.glb
│           └── flash.glb
├── package.json
└── README.md
```

---

## 🚀 Key Features Converted to Unity C#

1. **YOLO11n ONNX Detection Engine (`YoloDetector.cs`)**:
   - Supports 320x320 / 640x640 inputs with letterboxing.
   - Per-class confidence thresholds:
     - **Harry Potter**: 0.35
     - **Hermione Granger**: 0.40
     - **Batman**: 0.45
     - **Flash**: 0.38
   - **Adaptive Rotation Augmentation (0°, 90°, 180°, 270°)** with inverse bounding box mapping.
   - Single-pass **Non-Maximum Suppression (NMS)** with 0.40 IoU threshold.

2. **Multi-Frame Stability & State Machine (`MainDetectorManager.cs`)**:
   - Requires **3 continuous stable frames** with **>= 60% confidence** and **>= 50% guide box coverage** before confirming a toy.
   - **Unknown Object Timeout**: 35-frame timeout if an object is held inside the guide box without reaching 60% confidence.
   - **Temporal Box Smoothing**: Holds last-seen bounding box (`HOLD_FRAMES = 6`) to prevent flickering.
   - **AR Lock Mode**: Toy type selection is locked upon confirmation, while real-time bounding box tracking continues updating 3D position and scale every frame.
   - **Scan Reset**: Resets detection state when user taps "Scan Again".

3. **Frustum-Accurate 3D AR Placement (`ArModelViewer.cs`)**:
   - Converts 2D screen bounding box `(x, y, width, height)` to 3D View Frustum World Coordinates at depth `Z = -1.4f`.
   - Exponential Lerp smoothing (`0.35f`) for position and scale.
   - Continuous Y-axis rotation (1.5°/frame) for interactive display.

4. **Hardware Camera Manager (`CameraController.cs`)**:
   - WebCamTexture control selecting the rear-facing camera on mobile devices or default camera on PC.
   - Automatic aspect-ratio fitting to Canvas UI.

5. **UI & Guide Box Overlay (`GuideBoxUI.cs`)**:
   - Centered square guide box (~62% of screen dimension).
   - Dynamic fill progress ring/bar.
   - Status labels and result card popups.

---

## ⚙️ Unity Setup & Requirements

### Recommended Unity Version
- **Unity 2022.3 LTS**, **Unity 2023.x**, or **Unity 6 (6000+)**.

### Dependencies / Packages
1. **Unity Sentis** (Unity's high-performance ONNX inference package):
   - Open **Window > Package Manager** > Click `+` > **Add package by name...**
   - Enter: `com.unity.sentis`
2. **glTFast** (For loading 3D GLB models at runtime):
   - Enter: `com.atteneder.gltfast`

---

## 🎮 How to Setup the Scene in Unity

1. Open `KinderJoyDetectorUnity` as a project in Unity Editor.
2. Create a new Scene (`MainScene.unity`).
3. **Canvas Setup**:
   - Create a Canvas set to `Screen Space - Overlay` or `Camera`.
   - Add a `RawImage` for camera preview -> attach `AspectRatioFitter`.
   - Add a `Text` element for Status Text.
   - Add an `Image` for Progress Ring (Set Image Type to `Filled`).
   - Add a Panel for Result Card Banner with a Button for "Scan Again".
4. **GameObjects & Scripts**:
   - Create an empty GameObject `DetectorManager` -> attach `MainDetectorManager.cs`.
   - Create an empty GameObject `CameraController` -> attach `CameraController.cs`. Assign `cameraPreviewImage` & `aspectFitter`.
   - Create an empty GameObject `ArModelViewer` -> attach `ArModelViewer.cs`. Assign `ToyModelMapping` prefabs for Harry Potter, Hermione, Batman, and Flash.
   - Create an empty GameObject `GuideBoxUI` -> attach `GuideBoxUI.cs`. Assign UI text, progress ring, result card banner, and button.
5. Press **Play** in Unity Editor to run using your webcam!
