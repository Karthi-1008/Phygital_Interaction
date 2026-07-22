package com.madrasmindworks.kinderjoydetector

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.madrasmindworks.kinderjoydetector.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var detector: YoloDetector
    private lateinit var inferenceExecutor: ExecutorService

    // FPS
    private var lastFpsTime  = System.currentTimeMillis()
    private var frameCount   = 0
    private var currentFps   = 0f

    // Frame skip — never pile up work
    @Volatile private var isProcessing = false

    // Reused across frames — camera resolution is fixed, so allocate once
    private var reusableBitmap: Bitmap? = null

    // Temporal smoothing: hold the last-seen detections briefly when a single
    // frame comes back empty/borderline. YOLO confidence naturally dips a few
    // frames in a row even while the toy is in view; this stops the box from
    // flickering on and off and stops "missed" detections the user notices.
    private var lastDets: List<YoloDetector.Detection> = emptyList()
    private var framesSinceLastDet = 0
    private val HOLD_FRAMES = 4

    companion object {
        private const val TAG = "MainActivity"
        private const val REQ_CAMERA = 10
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on while app is open
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.statusText.text = "Loading model…"
        binding.fpsText.visibility = View.INVISIBLE

        inferenceExecutor = Executors.newSingleThreadExecutor()

        inferenceExecutor.execute {
            detector = YoloDetector(this)
            detector.load()
            runOnUiThread {
                if (detector.isLoaded) {
                    binding.statusText.text = "Point camera at a toy"
                    binding.fpsText.visibility = View.VISIBLE
                } else {
                    binding.statusText.text = "Model failed to load"
                    binding.statusText.setTextColor(Color.RED)
                }
                if (hasCameraPermission()) startCamera() else requestCameraPermission()
            }
        }
    }

    // ── Camera ────────────────────────────────────────────────────────────────

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()

            val preview = Preview.Builder()
                .setTargetResolution(Size(480, 640))   // portrait — smaller = faster
                .build()
                .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(480, 640))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)  // always freshest frame
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(inferenceExecutor, ::processFrame) }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ── Frame processing ──────────────────────────────────────────────────────

    private fun processFrame(image: ImageProxy) {
        if (isProcessing) { image.close(); return }
        isProcessing = true

        val bmp = imageProxyToBitmap(image)
        image.close()    // release camera buffer immediately

        val srcW = bmp.width
        val srcH = bmp.height

        val rawDets = detector.detect(bmp)

        // Temporal smoothing — see field comments above
        val dets: List<YoloDetector.Detection>
        if (rawDets.isNotEmpty()) {
            dets = rawDets
            lastDets = rawDets
            framesSinceLastDet = 0
        } else if (framesSinceLastDet < HOLD_FRAMES) {
            dets = lastDets
            framesSinceLastDet++
        } else {
            dets = emptyList()
        }

        runOnUiThread {
            binding.overlayView.setDetections(dets, srcW, srcH)
            updateHud(dets)
        }

        isProcessing = false
    }

    /**
     * Reuses a single ARGB_8888 Bitmap across frames instead of allocating a
     * new one every frame (camera resolution is fixed after startCamera(), so
     * there's no reason to ever re-allocate). This is the other big source of
     * per-frame GC pressure alongside the detector's old scaled/padded Bitmaps.
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
        else Bitmap.createBitmap(bmp, 0, 0, img.width, img.height)  // small crop view, cheap
    }

    // ── HUD ───────────────────────────────────────────────────────────────────

    private fun updateHud(dets: List<YoloDetector.Detection>) {
        // Detection label
        binding.statusText.text = when {
            dets.isEmpty()  -> "No toy detected"
            dets.size == 1  -> "${dets[0].className}  ${"%.0f".format(dets[0].confidence * 100)}%"
            else            -> dets.joinToString(" · ") { it.className }
        }
        binding.statusText.setTextColor(if (dets.isEmpty()) Color.GRAY else Color.WHITE)

        // FPS counter
        frameCount++
        val now     = System.currentTimeMillis()
        val elapsed = now - lastFpsTime
        if (elapsed >= 800) {
            currentFps  = frameCount * 1000f / elapsed
            binding.fpsText.text = "%.0f FPS".format(currentFps)
            binding.fpsText.setTextColor(when {
                currentFps >= 20 -> Color.parseColor("#00FF88")
                currentFps >= 12 -> Color.YELLOW
                else             -> Color.RED
            })
            frameCount  = 0
            lastFpsTime = now
        }
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
    }
}
