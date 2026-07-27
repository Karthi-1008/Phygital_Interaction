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
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.madrasmindworks.kinderjoydetector.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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

    // Guide box, defined once in FRAME pixel coords (lazily, once frame size known)
    private var guideBoxFrame: RectF? = null
    private var guideBoxRect: Rect? = null   // integer version, used for the crop

    // Temporal smoothing: hold the last-seen detections briefly when a single
    // frame comes back empty/borderline, so the box doesn't flicker.
    private var lastDets: List<YoloDetector.Detection> = emptyList()
    private var framesSinceLastDet = 0
    private val HOLD_FRAMES = 4

    // "Hold the toy in the box" progress — fills while a confident detection
    // sits inside the guide box, decays when it doesn't.
    private var progress = 0f
    private val PROGRESS_STEP = 0.055f   // ~ fills in well under 2s at typical FPS
    private val PROGRESS_DECAY = 0.09f

    // Once true, all further frame processing / camera analysis stops until
    // the user taps "Scan Again". Nothing runs in the background meanwhile.
    @Volatile private var detectionLocked = false

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

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()

            val preview = Preview.Builder()
                .setTargetResolution(Size(480, 640))   // portrait — smaller = faster
                .build()
                .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(480, 640))
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

    /** Fully stops the camera + analyzer — no background work runs after this. */
    private fun stopCamera() {
        if (::cameraProvider.isInitialized) cameraProvider.unbindAll()
    }

    // ── Guide box ─────────────────────────────────────────────────────────────

    /** Centered square guide box, ~62% of the shorter frame dimension. */
    private fun ensureGuideBox(frameW: Int, frameH: Int) {
        if (guideBoxFrame != null) return
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

        // Temporal smoothing
        val dets: List<YoloDetector.Detection>
        if (inBoxDets.isNotEmpty()) {
            dets = inBoxDets
            lastDets = inBoxDets
            framesSinceLastDet = 0
        } else if (framesSinceLastDet < HOLD_FRAMES) {
            dets = lastDets
            framesSinceLastDet++
        } else {
            dets = emptyList()
        }

        // Progress: fill while something valid is held in the box, decay otherwise
        if (dets.isNotEmpty()) progress += PROGRESS_STEP else progress -= PROGRESS_DECAY
        progress = progress.coerceIn(0f, 1f)

        val completed = progress >= 1f
        if (completed) detectionLocked = true   // stop taking on new frames immediately

        runOnUiThread {
            binding.overlayView.setFrameGeometry(srcW, srcH, boxF)
            binding.overlayView.setProgress(progress, dets.isNotEmpty())
            updateStatus(dets)
            if (completed) onDetectionComplete(dets.first())
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

    private fun updateStatus(dets: List<YoloDetector.Detection>) {
        binding.statusText.text = when {
            dets.isEmpty() -> "Hold a toy inside the box"
            else -> "${dets[0].className}  ${"%.0f".format(dets[0].confidence * 100)}% — hold steady…"
        }
        binding.statusText.setTextColor(if (dets.isEmpty()) Color.LTGRAY else Color.WHITE)
    }

    // ── Success → celebration ────────────────────────────────────────────────

    private fun onDetectionComplete(det: YoloDetector.Detection) {
        try {
            onDetectionCompleteInternal(det)
        } catch (t: Throwable) {
            Log.e(TAG, "Celebration screen failed — showing fallback text only", t)
            binding.celebrationContainer.visibility = View.VISIBLE
            binding.celebrationTitle.text = "🎉 ${det.className} found!"
        }
    }

    private fun onDetectionCompleteInternal(det: YoloDetector.Detection) {
        stopCamera()   // no background camera/inference work while celebrating

        binding.previewView.visibility = View.GONE
        binding.overlayView.visibility = View.GONE
        binding.statusText.visibility = View.GONE
        binding.celebrationContainer.visibility = View.VISIBLE

        val toy = TOY_ASSETS[det.classIndex]
        binding.celebrationTitle.text = "${toy?.emoji ?: "🎉"} ${det.className} found!"

        val viewer = modelViewer ?: ModelViewer(binding.modelSurface).also { modelViewer = it }
        if (!viewer.isAvailable) {
            // 3D isn't available on this device (e.g. no OpenGL ES 3 support,
            // or a native lib mismatch) — fail soft: keep the celebration
            // text/emoji and "Scan Again" working instead of crashing.
            Log.w(TAG, "ModelViewer unavailable: ${viewer.lastError}")
            binding.celebrationTitle.text = "${toy?.emoji ?: "🎉"} ${det.className} found!\n(3D preview unavailable on this device)"
            return
        }
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

    /** "Scan Again" — tears down the celebration and resumes live detection. */
    private fun resetForNewScan() {
        modelViewer?.destroyModel()

        binding.celebrationContainer.visibility = View.GONE
        binding.previewView.visibility = View.VISIBLE
        binding.overlayView.visibility = View.VISIBLE
        binding.statusText.visibility = View.VISIBLE

        progress = 0f
        lastDets = emptyList()
        framesSinceLastDet = 0
        detectionLocked = false
        binding.overlayView.setProgress(0f, false)
        binding.statusText.text = "Point camera at a toy — hold it in the box"

        startCamera()
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
