# 🎯 Kinder Joy Detector — Android App

Real-time YOLO11n toy detector using ONNX Runtime + CameraX.

**Detects:** Harry Potter · Hermione Granger · Batman · Flash  
**Model:** `exp-3.onnx` (YOLO11n, 5.3 MB)  
**Min Android:** API 21 (Android 5.0 Lollipop) — Universal APK

---

## Quick Build (Android Studio)

### Prerequisites
| Tool | Version |
|------|---------|
| Android Studio | Hedgehog 2023.1.1 or newer |
| JDK | 17 (bundled with AS) |
| Android SDK | API 34 |
| Gradle | 8.4 (downloaded automatically) |

### Steps

1. **Open project**
   - File → Open → select the `KinderJoyDetector/` folder

2. **Sync Gradle**
   - Android Studio will auto-sync; internet required to download dependencies

3. **Build APK**
   ```
   Build → Build Bundle(s) / APK(s) → Build APK(s)
   ```
   Output: `app/build/outputs/apk/release/app-release.apk`

4. **Install on device**
   ```bash
   adb install app/build/outputs/apk/release/app-release.apk
   ```

### Command-line build (CI / no Android Studio)
```bash
# Make sure ANDROID_HOME is set, then:
./gradlew assembleRelease
```

---

## Project Structure

```
KinderJoyDetector/
├── app/src/main/
│   ├── assets/
│   │   └── exp-3.onnx              ← YOLO11n model (5.3 MB)
│   ├── java/com/madrasmindworks/kinderjoydetector/
│   │   ├── MainActivity.kt         ← CameraX pipeline + FPS HUD
│   │   ├── YoloDetector.kt         ← ONNX inference + NMS
│   │   └── OverlayView.kt          ← Canvas bounding boxes
│   ├── res/layout/activity_main.xml
│   └── AndroidManifest.xml
```

---

## Architecture

```
Camera Frame (CameraX)
       │
       ▼  [inference thread]
 Letterbox → 640×640 RGB
       │
       ▼
 ONNX Runtime (NNAPI / CPU)
       │
       ▼
 Output [1, 8, 8400]
  → transpose → [8400, 8]
  → threshold (conf > 0.40)
  → per-class NMS (IoU 0.45)
       │
       ▼
 OverlayView (Canvas)
  → coloured boxes + labels
```

---

## Performance Tuning

| Setting | Default | Notes |
|---------|---------|-------|
| CONF_THRESHOLD | 0.40 | Lower → more detections, more false positives |
| IOU_THRESHOLD  | 0.45 | Lower → fewer overlapping boxes |
| Input size | 640 | Fixed by model |
| Camera resolution | 640×480 | Reduce to 320×240 for faster capture |
| Threads | intra=4, inter=2 | Tune per device |

**NNAPI** is enabled automatically on devices that support it (most Android 8.1+).  
The app falls back to multi-threaded CPU silently.

---

## Expected Performance

| Device tier | FPS |
|-------------|-----|
| Flagship (Snapdragon 8 Gen) | 40–60 FPS |
| Mid-range (Snapdragon 6xx)  | 25–35 FPS |
| Low-end (Snapdragon 4xx)    | 15–25 FPS |

---

## Customisation

### Add more toy classes
1. Re-export model with new classes
2. Update `CLASS_NAMES` in `YoloDetector.kt`
3. Add colour to `CLASS_COLORS`

### Change confidence threshold
Edit `CONF_THRESHOLD` in `YoloDetector.kt`.

### Portrait vs Landscape
Change `android:screenOrientation` in `AndroidManifest.xml`.
