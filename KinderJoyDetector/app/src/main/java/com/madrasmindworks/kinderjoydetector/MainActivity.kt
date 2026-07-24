package com.madrasmindworks.kinderjoydetector

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.madrasmindworks.kinderjoydetector.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var detector: YoloDetector
    private lateinit var inferenceExecutor: ExecutorService
    private lateinit var cameraProvider: ProcessCameraProvider

    private var modelViewer: ModelViewer? = null

    // Preloaded once at startup so the celebration screen never waits on
    // disk I/O — asset reads (~1-2MB each) happen in the background while
    // the user is still scanning, not at the exciting moment.
    private val glbBytesCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    // Frame skip — never pile up work
    @Volatile private var isProcessing = false

    // Reused across frames — camera resolution is fixed, so allocate once
    private var reusableBitmap: Bitmap? = null

    // Guide box in FRAME pixel coords — recomputed whenever the camera's
    // actual output size changes (device/rotation dependent), not just once,
    // so it stays correct across every device/screen/camera combination.
    private var guideBoxFrame: RectF? = null
    private var guideBoxRect: Rect? = null   // integer version, used for the crop
    private var lastFrameW = -1
    private var lastFrameH = -1

    // Trigger rule: confidence >= 80% on the SAME class for 3 consecutive
    // frames fires the animation immediately — no progress-bar smoothing,
    // no extra verification pass once that condition is met.
    private var streakClass: Int? = null
    private var streakCount = 0
    private val CONFIDENCE_TRIGGER = 0.80f
    private val STREAK_REQUIRED = 3

    // Once true, all further frame processing / camera analysis stops until
    // the user taps "Scan Again". Nothing runs in the background meanwhile.
    @Volatile private var detectionLocked = false

    // FPS — exponential moving average over actually-processed frames
    // (frames skipped because a previous one was still running, or because
    // detection is locked, don't count — this reflects true throughput).
    private var lastFrameNanos = 0L
    private var fpsEma = 0f

    // className index -> celebration asset info
    private data class ToyAsset(val assetPath: String, val animationIndex: Int?, val emoji: String)
    private val TOY_ASSETS = mapOf(
        0 to ToyAsset("models/harry_potter.glb", null, "🧙"),   // no embedded animation — shown as a static pose
        1 to ToyAsset("models/hermione.glb", 0, "🪄"),           // "metarig|idle"
        2 to ToyAsset("models/batman.glb", 0, "🦇"),             // "Armature|...mixamo"
        3 to ToyAsset("models/flash.glb", 10, "⚡")              // "waveHello" — celebratory
    )

    companion object {
        private const val TAG = "MainActivity"
        private const val REQ_CAMERA = 10
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.statusText.text = "Loading model…"
        binding.btnScanAgain.setOnClickListener { resetForNewScan() }

        inferenceExecutor = Executors.newSingleThreadExecutor()

        inferenceExecutor.execute {
            detector = YoloDetector(this)
            detector.load()
            runOnUiThread {
                if (detector.isLoaded) {
                    binding.statusText.text = "Point camera at a toy — hold it in the box"
                } else {
                    binding.statusText.text = "Model failed to load"
                    binding.statusText.setTextColor(Color.RED)
                }
                if (hasCameraPermission()) startCamera() else requestCameraPermission()
            }
        }

        preloadCelebrationAssets()
    }

    /**
     * Reads every .glb into memory and warms up the Filament engine (which
     * compiles its ubershader materials on first use) in the background,
     * well before the user ever completes a detection — so the celebration
     * screen has nothing left to do but call loadGlb() on already-loaded
     * bytes, instead of hitting disk + shader compilation right when it
     * needs to feel instant.
     */
    private fun preloadCelebrationAssets() {
        Thread({
            for (toy in TOY_ASSETS.values) {
                try {
                    glbBytesCache[toy.assetPath] = assets.open(toy.assetPath).readBytes()
                } catch (e: Exception) {
                    Log.e(TAG, "Preload failed for ${toy.assetPath}", e)
                }
            }
            runOnUiThread {
                // Created on the UI thread (SurfaceHolder callbacks require it),
                // but all the expensive one-time setup (shader/material
                // compilation) happens now instead of at celebration time.
                modelViewer = ModelViewer(binding.modelSurface)
            }
        }, "GlbPreload").start()
    }

    // ── Camera ────────────────────────────────────────────────────────────────

    // Target capture size only — NOT the model's input size. The model's
    // tensor is always exactly 640x640 regardless of what the sensor
    // delivers, because YoloDetector letterboxes whatever crop it receives
    // into that fixed size (see runInference()). This selector just asks
    // CameraX for a stream close to that area so the crop+resize work stays
    // cheap; every device that reports a different native size still works
    // correctly, it just resizes from a different starting point.
    private val CAMERA_TARGET = Size(640, 480)

    /**
     * setTargetResolution() (the old API) is deprecated AND unreliable — it's
     * only a "hint" CameraX is free to ignore, and different OEM camera HALs
     * pick wildly different actual sizes for the same hint. ResolutionSelector
     * is the modern replacement: it picks the closest AVAILABLE stream size
     * to our target and degrades gracefully (closest-higher, then
     * closest-lower) on every device instead of silently substituting an
     * arbitrary resolution.
     */
    private fun buildResolutionSelector(): ResolutionSelector =
        ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(CAMERA_TARGET, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
            )
            .build()

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()

            val preview = Preview.Builder()
                .setResolutionSelector(buildResolutionSelector())
                .build()
                .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(buildResolutionSelector())
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(inferenceExecutor, ::processFrame) }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ── Guide box ─────────────────────────────────────────────────────────────

    /**
     * Centered square guide box, ~62% of the shorter frame dimension.
     * Recomputed whenever the actual camera frame size changes (first frame,
     * a device that reports a different stream size than expected, or an
     * orientation/config change) instead of being frozen after the first
     * call — this is what makes the box correct on every device/camera/
     * screen combination rather than just the one it happened to start on.
     */
    private fun ensureGuideBox(frameW: Int, frameH: Int) {
        if (guideBoxFrame != null && frameW == lastFrameW && frameH == lastFrameH) return
        lastFrameW = frameW
        lastFrameH = frameH
        val size = (minOf(frameW, frameH) * 0.62f)
        val left = (frameW - size) / 2f
        val top  = (frameH - size) / 2f
        guideBoxFrame = RectF(left, top, left + size, top + size)
        guideBoxRect = Rect(left.toInt(), top.toInt(), (left + size).toInt(), (top + size).toInt())
    }

    // ── Frame processing ──────────────────────────────────────────────────────

    private fun processFrame(image: ImageProxy) {
        if (isProcessing || detectionLocked) { image.close(); return }
        isProcessing = true

        // FPS: measured across actually-processed frames (skipped frames
        // above don't count — this is real throughput, not camera rate).
        val now = System.nanoTime()
        if (lastFrameNanos != 0L) {
            val dt = (now - lastFrameNanos) / 1_000_000_000f
            if (dt > 0f) {
                val instFps = 1f / dt
                fpsEma = if (fpsEma == 0f) instFps else fpsEma * 0.9f + instFps * 0.1f
            }
        }
        lastFrameNanos = now

        val bmp = imageProxyToBitmap(image)
        image.close()    // release camera buffer immediately

        val srcW = bmp.width
        val srcH = bmp.height
        ensureGuideBox(srcW, srcH)
        val box = guideBoxRect!!
        val boxF = guideBoxFrame!!

        // Detector only ever looks inside (a small margin around) the guide box.
        val margin = (box.width() * 0.15f).toInt()
        val cropRect = Rect(
            (box.left - margin).coerceAtLeast(0),
            (box.top - margin).coerceAtLeast(0),
            (box.right + margin).coerceAtMost(srcW),
            (box.bottom + margin).coerceAtMost(srcH)
        )

        val rawDets = detector.detect(bmp, cropRect)

        // Only detections that substantially overlap the guide box count —
        // a toy peeking in at the edge of the crop margin shouldn't register.
        val inBoxDets = rawDets.filter { iou(it.rect, boxF) > 0.30f }
        val top = inBoxDets.maxByOrNull { it.confidence }

        // Trigger rule, exactly as specified: confidence >= 80% on the same
        // class, 3 frames running. Any frame that doesn't clear the bar, or
        // that clears it on a DIFFERENT class, resets the streak to zero —
        // no extra checks once the streak hits 3, it fires immediately.
        if (top != null && top.confidence >= CONFIDENCE_TRIGGER) {
            streakCount = if (top.classIndex == streakClass) streakCount + 1 else 1
            streakClass = top.classIndex
        } else {
            streakClass = null
            streakCount = 0
        }

        val completed = streakCount >= STREAK_REQUIRED
        if (completed) detectionLocked = true   // stop taking on new frames immediately

        runOnUiThread {
            binding.overlayView.setFrameGeometry(srcW, srcH, boxF)
            binding.overlayView.setProgress(
                streakCount / STREAK_REQUIRED.toFloat(),
                top != null && top.confidence >= CONFIDENCE_TRIGGER
            )
            updateStatus(top)
            binding.fpsText.text = "${fpsEma.roundToInt()} FPS · ${"%.0f".format(detector.lastInferenceMs)}ms"
            if (completed && top != null) onDetectionComplete(top)
        }

        isProcessing = false
    }

    private fun iou(a: RectF, b: RectF): Float {
        val ix1 = maxOf(a.left, b.left);   val iy1 = maxOf(a.top, b.top)
        val ix2 = minOf(a.right, b.right); val iy2 = minOf(a.bottom, b.bottom)
        val iw = maxOf(0f, ix2 - ix1);     val ih = maxOf(0f, iy2 - iy1)
        val inter = iw * ih
        if (inter == 0f) return 0f
        // Overlap relative to the smaller of the two areas — a toy fully inside
        // the box scores ~1.0 even if the box itself is much bigger than it.
        val smaller = minOf((a.right - a.left) * (a.bottom - a.top), (b.right - b.left) * (b.bottom - b.top))
        return if (smaller <= 0f) 0f else inter / smaller
    }

    /**
     * Reuses a single ARGB_8888 Bitmap across frames instead of allocating a
     * new one every frame.
     */
    private fun imageProxyToBitmap(img: ImageProxy): Bitmap {
        val plane      = img.planes[0]
        val rowStride  = plane.rowStride
        val pixStride  = plane.pixelStride
        val rowPad     = rowStride - pixStride * img.width
        val strideW    = img.width + rowPad / pixStride

        var bmp = reusableBitmap
        if (bmp == null || bmp.width != strideW || bmp.height != img.height) {
            bmp?.recycle()
            bmp = Bitmap.createBitmap(strideW, img.height, Bitmap.Config.ARGB_8888)
            reusableBitmap = bmp
        }
        bmp.copyPixelsFromBuffer(plane.buffer)

        return if (rowPad == 0) bmp
        else Bitmap.createBitmap(bmp, 0, 0, img.width, img.height)
    }

    // ── Status text ───────────────────────────────────────────────────────────

    private fun updateStatus(top: YoloDetector.Detection?) {
        binding.statusText.text = when {
            top == null -> "Hold a toy inside the box"
            top.confidence < CONFIDENCE_TRIGGER ->
                "${top.className}  ${"%.0f".format(top.confidence * 100)}% — a little closer…"
            else ->
                "${top.className}  ${"%.0f".format(top.confidence * 100)}% — hold steady ($streakCount/$STREAK_REQUIRED)"
        }
        binding.statusText.setTextColor(if (top == null) Color.LTGRAY else Color.WHITE)
    }

    // ── Success → screen-locked AR reveal ───────────────────────────────────
    // The camera feed is NEVER stopped or hidden here — that's what makes
    // this feel like AR rather than a "results screen". Only the analyzer
    // stops doing work (via detectionLocked, checked at the top of
    // processFrame). The .glb renders on a transparent SurfaceView sized and
    // positioned to exactly cover the toy's last-known screen rect, so the
    // character appears to pop up right where the toy is, in place, with a
    // fixed (non-orbiting) camera — not drifting, not a separate preview pane.

    private fun onDetectionComplete(det: YoloDetector.Detection) {
        try {
            onDetectionCompleteInternal(det)
        } catch (t: Throwable) {
            Log.e(TAG, "AR reveal failed — showing fallback text only", t)
            binding.celebrationTitle.visibility = View.VISIBLE
            binding.celebrationTitle.text = "🎉 ${det.className} found!"
        }
    }

    /** Converts a rect in camera-FRAME pixel coords to on-screen VIEW pixel coords, matching OverlayView's fitCenter math exactly. */
    private fun frameRectToViewRect(r: RectF, viewW: Int, viewH: Int, frameW: Int, frameH: Int): RectF {
        val scale   = minOf(viewW.toFloat() / frameW, viewH.toFloat() / frameH)
        val offsetX = (viewW - frameW * scale) / 2f
        val offsetY = (viewH - frameH * scale) / 2f
        return RectF(
            r.left * scale + offsetX, r.top * scale + offsetY,
            r.right * scale + offsetX, r.bottom * scale + offsetY
        )
    }

    private fun onDetectionCompleteInternal(det: YoloDetector.Detection) {
        val toy = TOY_ASSETS[det.classIndex]

        val viewer = modelViewer
        if (viewer == null || !viewer.isAvailable) {
            Log.w(TAG, "ModelViewer unavailable: ${viewer?.lastError}")
            binding.celebrationTitle.visibility = View.VISIBLE
            binding.celebrationTitle.text = "${toy?.emoji ?: "🎉"} ${det.className} found! (3D unavailable on this device)"
            binding.btnScanAgain.visibility = View.VISIBLE
            return
        }

        // Lock the model to the toy's screen position at the moment of
        // detection — inflated a bit beyond the tight bounding box so the
        // character has room to render, and never touched again afterwards
        // (no re-tracking, no drift, no rotation animation).
        val arLayerW = binding.arLayer.width
        val arLayerH = binding.arLayer.height
        val inflated = RectF(det.rect).apply {
            val padX = width() * 0.45f
            val padY = height() * 0.45f
            inset(-padX, -padY)
        }
        val vr = frameRectToViewRect(inflated, arLayerW, arLayerH, lastFrameW, lastFrameH)

        val params = FrameLayout.LayoutParams(
            vr.width().toInt().coerceAtLeast(1),
            vr.height().toInt().coerceAtLeast(1)
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = vr.left.toInt().coerceAtLeast(0)
            topMargin = vr.top.toInt().coerceAtLeast(0)
        }
        binding.modelSurface.layoutParams = params
        binding.modelSurface.visibility = View.VISIBLE

        binding.celebrationTitle.text = "${toy?.emoji ?: "🎉"} ${det.className} found!"
        binding.celebrationTitle.visibility = View.VISIBLE
        binding.celebrationTitle.post {
            // Placed once the label is measured, so it sits centered directly
            // above the locked model box regardless of label text length.
            val lp = binding.celebrationTitle.layoutParams as FrameLayout.LayoutParams
            lp.gravity = Gravity.TOP or Gravity.START
            lp.leftMargin = (vr.centerX() - binding.celebrationTitle.width / 2f).toInt().coerceAtLeast(8)
            lp.topMargin = (vr.top - binding.celebrationTitle.height - 12).toInt().coerceAtLeast(8)
            binding.celebrationTitle.layoutParams = lp
        }

        binding.btnScanAgain.visibility = View.VISIBLE

        if (toy != null) {
            try {
                val bytes = glbBytesCache[toy.assetPath] ?: assets.open(toy.assetPath).readBytes()
                    .also { glbBytesCache[toy.assetPath] = it }   // fallback if preload hadn't finished yet
                viewer.loadGlb(java.nio.ByteBuffer.wrap(bytes))
                viewer.playAnimation(toy.animationIndex, loop = toy.animationIndex == null) {
                    // Animation finished once — loop it gently so the screen
                    // doesn't look frozen while the user decides to scan again.
                    runOnUiThread { viewer.playAnimation(toy.animationIndex, loop = true) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load ${toy.assetPath}", e)
            }
        }
    }

    /**
     * "Scan Again" — tears down the AR overlay and resumes live detection.
     * The camera was never stopped, so this is instant: no rebind, no
     * preview flicker, just clearing state and letting processFrame() run
     * again.
     */
    private fun resetForNewScan() {
        modelViewer?.destroyModel()

        binding.modelSurface.visibility = View.GONE
        binding.celebrationTitle.visibility = View.GONE
        binding.btnScanAgain.visibility = View.GONE

        streakClass = null
        streakCount = 0
        detectionLocked = false
        binding.overlayView.setProgress(0f, false)
        binding.statusText.text = "Point camera at a toy — hold it in the box"
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() =
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)

    override fun onRequestPermissionsResult(req: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(req, perms, results)
        if (req == REQ_CAMERA) {
            if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera()
            else Toast.makeText(this, "Camera permission needed", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        inferenceExecutor.shutdown()
        if (::detector.isInitialized) detector.close()
        reusableBitmap?.recycle()
        modelViewer?.release()
    }
}
