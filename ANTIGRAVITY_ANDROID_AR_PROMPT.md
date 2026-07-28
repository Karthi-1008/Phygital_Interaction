# ANTIGRAVITY TASK — Pure Android APK: Camera Detection + Animated GLB Model AR Overlay
# Language: Kotlin | No Unity | No ARCore dependency | Min SDK 24 → Target SDK 35

---

## SECTION 0 — PRE-TASK: FBX → GLB CONVERSION (do this BEFORE generating code)

Android cannot load .fbx files natively. Convert your FBX once using Blender:
1. Blender → File → Import → FBX → select ToyCharacter.fbx
2. File → Export → glTF 2.0
3. Format: **GLB (Binary)** | Check: Include Selected Objects, Animations, Skinning
4. Save to: `app/src/main/assets/models/toy_character.glb`

Place the .glb file at that assets path before building. The code references this path.

---

## SECTION 1 — PROJECT CONTEXT

**Repository:** Phygital_Interaction (Kotlin Android project)
**Package:** `com.madrasmindworks.phygital` (use this exact package name)
**Existing:** OpenCV Android SDK already imported as a local module named `opencv`
**Existing:** A class `KinderJoyDetector` exists — DO NOT delete or rename it
**Target:** Pure Android APK, no Unity engine, no ARCore, no Google Play Services dependency
**Android versions:** Min SDK **24** (Android 7.0, covers 95% of devices — below this
  is impossible for real-time 3D AR; do not attempt lower)
**Target SDK:** 35
**Language:** Kotlin only — no Java files
**Build system:** Gradle with Kotlin DSL (`build.gradle.kts`)

---

## SECTION 2 — EXACT GRADLE DEPENDENCIES

Add these to `app/build.gradle.kts`. Use **exact versions shown** — do not substitute:

```kotlin
android {
    defaultConfig {
        minSdk = 24
        targetSdk = 35
        compileSdk = 35
    }
    buildFeatures {
        viewBinding = true
    }
    packagingOptions {
        // Prevent duplicate OpenCV native lib conflicts
        pickFirst("lib/arm64-v8a/libopencv_java4.so")
        pickFirst("lib/armeabi-v7a/libopencv_java4.so")
    }
}

dependencies {
    // Camera (API 21+, works on all target versions)
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")

    // 3D model rendering with animation (API 24+, GLB/GLTF, no ARCore required)
    implementation("io.github.sceneview:sceneview:2.2.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // ViewModel + Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // Activity Result API for permissions
    implementation("androidx.activity:activity-ktx:1.10.1")

    // OpenCV (local module — already in project)
    implementation(project(":opencv"))
}
```

---

## SECTION 3 — MANDATORY ARCHITECTURE

### 3.1 Layout Stack (activity_main.xml)

```
ConstraintLayout (match_parent)
├── PreviewView              id="previewView"
│   width=match_parent, height=match_parent
│   (CameraX camera feed — bottom layer)
│
├── SceneView                id="sceneView"
│   width=match_parent, height=match_parent
│   (3D model overlay — transparent background over camera)
│
└── OverlayDebugView         id="debugOverlay"  (custom View, optional)
    width=match_parent, height=match_parent
    (shows detection rect outline — for debugging only)
```

SceneView must be drawn OVER PreviewView. Z-order: `sceneView` declared after `previewView` in XML.
Set SceneView transparent so camera shows through:
```kotlin
sceneView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
sceneView.holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
sceneView.setZOrderOnTop(true)
```

### 3.2 Detection → World Position Conversion

OpenCV detection fires a `Rect` in the analysis frame (640×480 pixels).
Convert to a 3D world position for the SceneView model node:

```kotlin
fun detectionRectToWorldPosition(
    rect: org.opencv.core.Rect,
    analysisWidth: Int,   // always 640
    analysisHeight: Int,  // always 480
    placementDepthMeters: Float = 2.0f
): dev.romainguy.kotlin.math.Float3 {

    // 1. Normalised center [0..1] with Y flipped (OpenCV Y=down, SceneView Y=up)
    val nx = (rect.x + rect.width  / 2f) / analysisWidth
    val ny = 1f - (rect.y + rect.height / 2f) / analysisHeight

    // 2. Convert to NDC [-1..1]
    val ndcX = nx * 2f - 1f
    val ndcY = ny * 2f - 1f

    // 3. Scale by placement depth using camera FOV (60° default SceneView FOV)
    val halfFovRad = Math.toRadians(30.0).toFloat()
    val worldX = ndcX * placementDepthMeters * kotlin.math.tan(halfFovRad)
    val worldY = ndcY * placementDepthMeters * kotlin.math.tan(halfFovRad) * 0.75f // 4:3 correction
    val worldZ = -placementDepthMeters  // negative Z = in front of camera in right-hand system

    return dev.romainguy.kotlin.math.Float3(worldX, worldY, worldZ)
}
```

### 3.3 Threading Model

```
Main Thread:       UI updates, SceneView model position, permission dialogs
IO Dispatcher:     OpenCV detection (heavy computation)
CameraX Executor:  ImageAnalysis frames (use Executors.newSingleThreadExecutor())
```

Never call OpenCV `Mat` operations on the Main Thread.
Never update SceneView node positions from a background thread — use `withContext(Dispatchers.Main)`.

---

## SECTION 4 — FILES TO CREATE

Package for all new files: `com.madrasmindworks.phygital`
All files in `app/src/main/java/com/madrasmindworks/phygital/`

---

### FILE 1: `utils/PermissionHelper.kt`

```
Purpose: Handle camera permission for API 24+ using ActivityResultContracts.
         Do NOT use deprecated requestPermissions() or onRequestPermissionsResult().

class PermissionHelper(
    private val activity: ComponentActivity,
    private val onGranted: () -> Unit,
    private val onDenied: () -> Unit
) {
    // Use ActivityResultContracts.RequestPermission
    // Register launcher in constructor with activity.registerForActivityResult()
    // fun checkAndRequest(): checks if already granted → onGranted(), else launches request
    // fun isGranted(): Boolean → checks ContextCompat.checkSelfPermission
}
```

Requested permission: `android.Manifest.permission.CAMERA`
Never use: `ActivityCompat.requestPermissions` (old pattern, use Result API)

---

### FILE 2: `camera/CameraEngine.kt`

```
Purpose: Start CameraX, deliver frames to OpenCV detector, show preview on PreviewView.

class CameraEngine(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val onFrameAnalyzed: (Mat) -> Unit   // called on analysis executor thread
) {
    private var cameraProvider: ProcessCameraProvider? = null

    // fun start():
    //   val future = ProcessCameraProvider.getInstance(context)
    //   future.addListener({ bindCamera(future.get()) }, ContextCompat.getMainExecutor(context))

    // private fun bindCamera(provider: ProcessCameraProvider):
    //   val preview = Preview.Builder().build()
    //   preview.setSurfaceProvider(previewView.surfaceProvider)
    //
    //   val imageAnalysis = ImageAnalysis.Builder()
    //       .setTargetResolution(Size(640, 480))
    //       .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    //       .build()
    //   imageAnalysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
    //       val mat = imageProxyToMat(imageProxy)
    //       onFrameAnalyzed(mat)
    //       imageProxy.close()   // CRITICAL: always close imageProxy
    //   }
    //
    //   val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    //   provider.unbindAll()
    //   provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)

    // private fun imageProxyToMat(imageProxy: ImageProxy): Mat:
    //   Convert ImageProxy (YUV_420_888) to OpenCV Mat in BGR format
    //   Use Imgproc.cvtColor(yuvMat, bgrMat, Imgproc.COLOR_YUV2BGR_NV21) or
    //       Imgproc.cvtColor(yuvMat, bgrMat, Imgproc.COLOR_YUV420sp2BGR)
    //   Return the BGR Mat — caller is responsible for release()

    // fun stop(): cameraProvider?.unbindAll()
}
```

**CRITICAL:** Never store `ImageProxy` beyond the `setAnalyzer` lambda. Close it immediately after conversion.
**CRITICAL:** Release every `Mat` after use — never let Mat objects accumulate (causes native heap OOM).

---

### FILE 3: `detection/ToyDetector.kt`

```
Purpose: Feature-matching toy detection using OpenCV ORB + BFMatcher.
         Existing KinderJoyDetector logic can be ported here — do not duplicate it if it already works.

interface DetectionListener {
    fun onToyDetected(rect: org.opencv.core.Rect)
    fun onToyLost()
}

class ToyDetector(
    private val context: Context,
    private val listener: DetectionListener
) {
    // [SerializeField equivalent] — tune these constants:
    private val MIN_MATCH_COUNT = 15        // minimum good matches to confirm detection
    private val LOWE_RATIO_THRESHOLD = 0.75f

    // private var referenceDescriptors: Mat? = null
    // private var referenceKeypoints: MatOfKeyPoint? = null
    // private val orb: ORB = ORB.create(500)
    // private val matcher: BFMatcher = BFMatcher(Core.NORM_HAMMING, false)

    // fun loadReferenceImage(assetFileName: String):
    //   Load from assets as Bitmap → Mat
    //   Detect ORB keypoints and compute descriptors from reference image
    //   Store in referenceDescriptors and referenceKeypoints

    // fun detect(frameMat: Mat):
    //   Called from IO thread via coroutine — this method is thread-safe
    //   Detect ORB keypoints in frameMat
    //   Match with referenceDescriptors using KNN match (k=2)
    //   Apply Lowe ratio test to filter good matches
    //   If goodMatches.size >= MIN_MATCH_COUNT:
    //       Compute bounding rect of matched keypoints in frame
    //       Post to main thread: listener.onToyDetected(boundingRect)
    //   Else:
    //       Post to main thread: listener.onToyLost()
    //   ALWAYS release intermediate Mat objects (queryDescriptors, matches list)

    // private fun postToMain(action: () -> Unit):
    //   Handler(Looper.getMainLooper()).post(action)
}
```

Reference image (toy photo for matching): placed at `app/src/main/assets/reference/kinder_toy.jpg`
Load it in `loadReferenceImage("reference/kinder_toy.jpg")` from `context.assets`.

---

### FILE 4: `ar/ARPositionCalculator.kt`

```
Purpose: Pure calculation class — converts OpenCV detection rect to SceneView 3D position.
         No Android dependencies — only math. Fully testable.

object ARPositionCalculator {
    const val ANALYSIS_WIDTH  = 640
    const val ANALYSIS_HEIGHT = 480
    const val PLACEMENT_DEPTH = 2.0f   // metres in front of camera
    const val CAMERA_FOV_DEG  = 60f

    // [Use exact formula from Section 3.2 above]
    fun toWorldPosition(rect: org.opencv.core.Rect): dev.romainguy.kotlin.math.Float3

    // Scale model based on detection bounding box size
    // Larger box = toy is closer = model should appear larger
    fun toModelScale(rect: org.opencv.core.Rect, baseScale: Float = 0.4f): Float {
        val normalizedArea = (rect.width.toFloat() * rect.height) /
                             (ANALYSIS_WIDTH * ANALYSIS_HEIGHT).toFloat()
        return baseScale * (0.5f + normalizedArea * 5f).coerceIn(0.2f, 2.0f)
    }
}
```

---

### FILE 5: `ar/ARViewController.kt`

```
Purpose: SceneView lifecycle management, GLB model loading, animation playback, node positioning.

class ARViewController(
    private val sceneView: io.github.sceneview.SceneView,
    private val lifecycleOwner: LifecycleOwner,
    private val coroutineScope: CoroutineScope
) {
    private var modelNode: io.github.sceneview.node.ModelNode? = null
    private var isModelLoaded = false
    private var isVisible = false

    // fun init():
    //   Make SceneView background transparent:
    //       sceneView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
    //   Disable SceneView's built-in environment lighting (causes dark model on transparent bg):
    //       sceneView.environment = null  [check SceneView 2.x API — may be sceneView.scene.environment]
    //   Load GLB model asynchronously

    // private fun loadModel():
    //   coroutineScope.launch(Dispatchers.IO) {
    //       try {
    //           val modelInstance = sceneView.modelLoader.loadModelInstance("models/toy_character.glb")
    //           withContext(Dispatchers.Main) {
    //               if (modelInstance != null) {
    //                   modelNode = ModelNode(modelInstance = modelInstance, scaleToUnits = 0.4f)
    //                   modelNode!!.isVisible = false
    //                   sceneView.scene.addChildNode(modelNode!!)   [check SceneView 2.x API]
    //                   isModelLoaded = true
    //               }
    //           }
    //       } catch (e: Exception) {
    //           Log.e("ARViewController", "GLB load failed: ${e.message}")
    //       }
    //   }

    // fun showModel(worldPosition: Float3, scale: Float):
    //   Must be called on Main thread
    //   if (!isModelLoaded || modelNode == null) return
    //   modelNode!!.worldPosition = worldPosition
    //   modelNode!!.scale = Float3(scale, scale, scale)
    //   if (!isVisible) {
    //       modelNode!!.isVisible = true
    //       playAnimation()
    //       isVisible = true
    //   }

    // fun hideModel():
    //   Must be called on Main thread
    //   if (!isVisible) return
    //   modelNode?.isVisible = false
    //   stopAnimation()
    //   isVisible = false

    // private fun playAnimation():
    //   Try modelNode?.playAnimation(animationIndex = 0)
    //   Wrap in try-catch — animation may not exist in all GLB files

    // private fun stopAnimation():
    //   Try modelNode?.stopAnimation(animationIndex = 0)

    // fun destroy():
    //   modelNode?.let { sceneView.scene.removeChildNode(it) }
    //   modelNode = null
    //   isModelLoaded = false
    //   isVisible = false
}
```

**CRITICAL SceneView 2.x API notes:**
- Model loading: `sceneView.modelLoader.loadModelInstance(assetPath)` (not `ModelRenderable.Builder`)
- Node add: `sceneView.scene.addChildNode(node)` or `sceneView.addChildNode(node)` — check 2.x docs
- Position type: `dev.romainguy.kotlin.math.Float3` (not `Vector3` — that's ARCore)
- Scale: `modelNode.scale = Float3(s, s, s)` or `modelNode.scaleToUnits = f`
- Animation: `modelNode.playAnimation(0)` / `modelNode.stopAnimation(0)`

---

### FILE 6: `MainActivity.kt`

```
Purpose: Orchestrate all components. Handle permission → camera → detection → AR flow.

class MainActivity : AppCompatActivity(), DetectionListener {
    private lateinit var binding: ActivityMainBinding
    private lateinit var permissionHelper: PermissionHelper
    private lateinit var cameraEngine: CameraEngine
    private lateinit var toyDetector: ToyDetector
    private lateinit var arViewController: ARViewController
    private val scope = lifecycleScope

    // private var lastDetectedRect: Rect? = null
    // private var toyLostJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep screen on during AR use
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Full screen immersive (hide nav bar + status bar)
        enableEdgeToEdge()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        permissionHelper = PermissionHelper(this,
            onGranted = { startARPipeline() },
            onDenied  = { showPermissionDeniedMessage() }
        )
        permissionHelper.checkAndRequest()
    }

    private fun startARPipeline() {
        // 1. Init AR view controller
        arViewController = ARViewController(binding.sceneView, this, scope)
        arViewController.init()

        // 2. Init detector
        toyDetector = ToyDetector(this, this)
        toyDetector.loadReferenceImage("reference/kinder_toy.jpg")

        // 3. Start camera
        cameraEngine = CameraEngine(this, this, binding.previewView) { mat ->
            // Called on analysis executor thread
            scope.launch(Dispatchers.IO) {
                toyDetector.detect(mat)
                mat.release()   // release every frame mat
            }
        }
        cameraEngine.start()
    }

    // DetectionListener implementation
    override fun onToyDetected(rect: org.opencv.core.Rect) {
        // Already on Main thread (ToyDetector posts via Handler)
        toyLostJob?.cancel()
        lastDetectedRect = rect
        val worldPos = ARPositionCalculator.toWorldPosition(rect)
        val scale    = ARPositionCalculator.toModelScale(rect)
        arViewController.showModel(worldPos, scale)
    }

    override fun onToyLost() {
        // Already on Main thread
        // Debounce: wait 1.5s before hiding (avoid flicker on momentary detection loss)
        toyLostJob?.cancel()
        toyLostJob = scope.launch {
            delay(1500)
            arViewController.hideModel()
        }
    }

    private fun showPermissionDeniedMessage() {
        // Show a Toast or AlertDialog explaining camera is needed
        // Offer button to open app settings: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraEngine.stop()
        arViewController.destroy()
    }
}
```

---

## SECTION 5 — ANDROIDMANIFEST.XML EXACT CHANGES

In `app/src/main/AndroidManifest.xml`:

```xml
<!-- Inside <manifest> before <application> -->
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera"         android:required="true" />
<uses-feature android:name="android.hardware.camera.autofocus" android:required="false" />

<!-- Inside <application> -->
<uses-library android:name="org.apache.http.legacy" android:required="false" />

<!-- Inside <activity android:name=".MainActivity"> -->
<activity
    android:name=".MainActivity"
    android:exported="true"
    android:screenOrientation="portrait"
    android:configChanges="orientation|screenSize|keyboardHidden"
    android:windowSoftInputMode="adjustResize">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>
```

Add `android:largeHeap="true"` to `<application>` to handle OpenCV Mat memory peaks:
```xml
<application android:largeHeap="true" ...>
```

---

## SECTION 6 — FORBIDDEN PATTERNS (Antigravity must NOT generate any of these)

| ❌ FORBIDDEN | ✅ USE INSTEAD |
|---|---|
| `import com.google.ar.core.*` | No ARCore — SceneView non-AR mode only |
| `import com.google.ar.sceneform.*` | Old deprecated Sceneform — use `io.github.sceneview` |
| `Camera` or `Camera2` API directly | Use `androidx.camera.camera2` via CameraX only |
| `ModelRenderable.Builder()` | Old API — use `modelLoader.loadModelInstance()` |
| `ArSceneView` | AR variant needs ARCore — use `SceneView` (non-AR) |
| `imageProxy.image!!.planes[0].buffer` without null check | Always null-check ImageProxy planes |
| Storing `ImageProxy` beyond analyzer lambda | Always call `imageProxy.close()` within lambda |
| `Handler(Looper.getMainLooper()).post { ... }` for coroutine-managed flows | Use `withContext(Dispatchers.Main)` |
| `runOnUiThread { ... }` inside coroutines | Use `withContext(Dispatchers.Main)` |
| `GlobalScope.launch` | Use `lifecycleScope` or passed-in `CoroutineScope` |
| `Thread { ... }.start()` | Use coroutines with `Dispatchers.IO` |
| `mat.release()` called more than once | Track ownership; release only once per Mat |
| `Vector3` from ARCore | Use `dev.romainguy.kotlin.math.Float3` from SceneView |
| `ActivityCompat.requestPermissions()` | Use `ActivityResultContracts.RequestPermission` |
| Hardcoded string path `"com.madrasmindworks"` in code | Use `BuildConfig.APPLICATION_ID` |
| `Camera.open()` Camera1 API | CameraX only |
| Any `TODO()` or placeholder comment | Every method body must be complete |

---

## SECTION 7 — SCENEVIEW TRANSPARENCY FIX (most common reason AR doesn't show)

SceneView must render with a transparent surface over the camera preview. 
This requires both code AND layout config. Generate **both**:

In `activity_main.xml`: SceneView must have `android:background="@android:color/transparent"`

In `MainActivity.onCreate()` or `ARViewController.init()`:
```kotlin
binding.sceneView.apply {
    setZOrderOnTop(true)
    holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
    setBackgroundColor(android.graphics.Color.TRANSPARENT)
}
```

If `sceneView.scene` has a skybox or environment: remove it.
SceneView 2.x: `sceneView.environment = null` or `sceneView.scene.skybox = null`

The model must have a directional light or it appears black:
```kotlin
// Add ambient light to scene so model is visible on transparent background
sceneView.scene.apply {
    // SceneView 2.x: configure scene's light or use a LightNode
    // Add a white directional light pointing slightly downward
}
```

---

## SECTION 8 — PROGUARD RULES (add to proguard-rules.pro)

```proguard
# OpenCV
-keep class org.opencv.** { *; }
-keepclassmembers class org.opencv.** { *; }

# SceneView / Filament
-keep class com.google.android.filament.** { *; }
-keep class io.github.sceneview.** { *; }
-dontwarn io.github.sceneview.**

# kotlin-math
-keep class dev.romainguy.kotlin.math.** { *; }
```

---

## SECTION 9 — FINAL VERIFICATION CHECKLIST

Before declaring done, check every item:

- [ ] `imageProxy.close()` called in every branch of the analyzer lambda (including exceptions)
- [ ] Every `Mat` created in detection has a corresponding `.release()` — use try/finally
- [ ] `setZOrderOnTop(true)` + `PixelFormat.TRANSLUCENT` on SceneView
- [ ] `sceneView.environment = null` (or skybox removed) — otherwise scene renders opaque black
- [ ] `ARViewController.showModel()` and `hideModel()` called ONLY from Main thread
- [ ] `ToyDetector.detect()` called ONLY from IO/background thread
- [ ] PermissionHelper uses `ActivityResultContracts.RequestPermission` — not deprecated method
- [ ] AndroidManifest has `<uses-permission android:name="android.permission.CAMERA"/>`
- [ ] AndroidManifest `<activity>` has `android:screenOrientation="portrait"`
- [ ] ProGuard rules file updated (OpenCV + SceneView entries)
- [ ] `toy_character.glb` is at `app/src/main/assets/models/toy_character.glb`
- [ ] `kinder_toy.jpg` reference image is at `app/src/main/assets/reference/kinder_toy.jpg`
- [ ] No `GlobalScope`, no `Thread{}`, no `runOnUiThread` in coroutine-managed flows
- [ ] Detection debounce `toyLostJob` cancels and restarts on every `onToyLost()` call
- [ ] `window.addFlags(FLAG_KEEP_SCREEN_ON)` in MainActivity (prevents screen sleep during AR)

---

*Generate all six Kotlin files and the updated AndroidManifest.xml now.
Do not use TODO comments or placeholder implementations.
Every function body must be complete and compilable against the dependency versions listed.*
