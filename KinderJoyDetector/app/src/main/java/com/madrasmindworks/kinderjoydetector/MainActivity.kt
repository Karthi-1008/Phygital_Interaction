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
import com.madrasmindworks.kinderjoydetector.ar.ArManager
import com.madrasmindworks.kinderjoydetector.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var detector: YoloDetector
    private lateinit var inferenceExecutor: ExecutorService
    private lateinit var cameraProvider: ProcessCameraProvider
    private lateinit var arManager: ArManager

    private var activeModelClassIndex = -1
    private var lockedClassIndex = -1

    // Frame skip — never pile up work
    @Volatile private var isProcessing = false

    // Reused across frames — camera resolution is fixed, so allocate once
    private var reusableBitmap: Bitmap? = null

    // Guide box, defined once in FRAME pixel coords
    private var guideBoxFrame: RectF? = null
    private var guideBoxRect: Rect? = null

    // Temporal smoothing
    private var lastDets: List<YoloDetector.Detection> = emptyList()
    private var framesSinceLastDet = 0
    private val HOLD_FRAMES = 6

    // Multi-frame temporal stability tracking & 60% confidence confirmation
    private var candidateClassIndex = -1
    private var candidateFrameCount = 0
    private var attemptFrameCount = 0
    private val REQUIRED_CONFIDENCE = 0.60f
    private val REQUIRED_STABLE_FRAMES = 3
    private val MIN_COVERAGE = 0.50f
    private val UNKNOWN_TIMEOUT_FRAMES = 35

    // Progress bar
    private var progress = 0f
    private val PROGRESS_STEP = 0.055f
    private val PROGRESS_DECAY = 0.09f

    // Locks toy selection upon 3 stable confirmations
    @Volatile private var detectionLocked = false

    companion object {
        private const val TAG = "MainActivity"
        private const val REQ_CAMERA = 10
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        arManager = ArManager(this)
        arManager.setup(binding.arSceneView)

        val isArSupported = arManager.checkArSupport()
        if (!isArSupported) {
            Toast.makeText(this, "AR is not supported on this device", Toast.LENGTH_LONG).show()
        }

        binding.statusText.text = "Loading model…"
        binding.btnScanAgain.setOnClickListener { resetForNewScan() }

        inferenceExecutor = Executors.newSingleThreadExecutor()

        inferenceExecutor.execute {
            detector = YoloDetector(this)
            detector.load()
            runOnUiThread {
                if (detector.isLoaded) {
                    binding.statusText.text = if (isArSupported) "Point camera at a toy — hold it in the box" else "Point camera at a toy (Detection only - AR unsupported)"
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
            cameraProvider = future.get()

            val preview = Preview.Builder()
                .setTargetResolution(Size(360, 480))
                .build()

            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(360, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(inferenceExecutor, ::processFrame) }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                Log.i(TAG, "Camera bound successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        if (::cameraProvider.isInitialized) cameraProvider.unbindAll()
    }

    // ── Guide box ─────────────────────────────────────────────────────────────

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
        if (isProcessing) { image.close(); return }
        isProcessing = true

        val bmp = imageProxyToBitmap(image)
        image.close()

        val srcW = bmp.width
        val srcH = bmp.height
        ensureGuideBox(srcW, srcH)
        val box = guideBoxRect!!
        val boxF = guideBoxFrame!!

        val margin = (box.width() * 0.15f).toInt()
        val cropRect = Rect(
            (box.left - margin).coerceAtLeast(0),
            (box.top - margin).coerceAtLeast(0),
            (box.right + margin).coerceAtMost(srcW),
            (box.bottom + margin).coerceAtMost(srcH)
        )

        val rawDets = detector.detect(bmp, cropRect) { rect, _, conf ->
            conf >= REQUIRED_CONFIDENCE && calculateCoverage(rect, boxF) >= MIN_COVERAGE
        }

        val highConfInBoxDets = rawDets.filter { it.confidence >= REQUIRED_CONFIDENCE && calculateCoverage(it.rect, boxF) >= MIN_COVERAGE }

        val confirmedDet: YoloDetector.Detection?
        val isConfirmed: Boolean

        if (!detectionLocked) {
            if (highConfInBoxDets.isNotEmpty()) {
                val topDet = highConfInBoxDets.maxByOrNull { it.confidence }!!
                if (topDet.classIndex == candidateClassIndex) {
                    candidateFrameCount++
                } else {
                    candidateClassIndex = topDet.classIndex
                    candidateFrameCount = 1
                }
                attemptFrameCount++

                if (candidateFrameCount >= REQUIRED_STABLE_FRAMES) {
                    confirmedDet = topDet
                    isConfirmed = true
                    lockedClassIndex = topDet.classIndex
                    Log.i(TAG, "[AR-DIAG] Toy confirmed: ${topDet.className} (${topDet.confidence * 100}%)")
                } else {
                    confirmedDet = null
                    isConfirmed = false
                    lastDets = highConfInBoxDets
                    framesSinceLastDet = 0
                }
            } else {
                val hasObjectInBox = rawDets.any { calculateCoverage(it.rect, boxF) >= 0.20f }
                if (hasObjectInBox) {
                    attemptFrameCount++
                } else {
                    attemptFrameCount = (attemptFrameCount - 1).coerceAtLeast(0)
                }

                candidateClassIndex = -1
                candidateFrameCount = 0

                if (attemptFrameCount >= UNKNOWN_TIMEOUT_FRAMES) {
                    confirmedDet = YoloDetector.Detection(
                        rect = boxF,
                        classIndex = -1,
                        className = "Unknown",
                        confidence = 0f
                    )
                    isConfirmed = true
                    lockedClassIndex = -1
                } else {
                    confirmedDet = null
                    isConfirmed = false
                    if (framesSinceLastDet < HOLD_FRAMES && lastDets.isNotEmpty()) {
                        framesSinceLastDet++
                    } else {
                        lastDets = emptyList()
                    }
                }
            }
        } else {
            confirmedDet = null
            isConfirmed = false
            if (highConfInBoxDets.isNotEmpty()) {
                lastDets = highConfInBoxDets
                framesSinceLastDet = 0
            } else if (rawDets.isNotEmpty()) {
                lastDets = rawDets
                framesSinceLastDet = 0
            } else if (framesSinceLastDet < HOLD_FRAMES && lastDets.isNotEmpty()) {
                framesSinceLastDet++
            } else {
                lastDets = emptyList()
            }
        }

        val liveDets = rawDets.filter { it.classIndex >= 0 && calculateCoverage(it.rect, boxF) >= 0.20f }
        val activeDets = if (highConfInBoxDets.isNotEmpty()) highConfInBoxDets else (if (liveDets.isNotEmpty()) liveDets else (if (framesSinceLastDet < HOLD_FRAMES) lastDets else emptyList()))

        if (isConfirmed && confirmedDet != null) {
            detectionLocked = true
            progress = 1.0f

            runOnUiThread {
                binding.overlayView.setFrameGeometry(srcW, srcH, boxF)
                binding.overlayView.setProgress(1.0f, confirmedDet.classIndex >= 0)
                updateStatus(if (confirmedDet.classIndex >= 0) listOf(confirmedDet) else emptyList())
                showDetectionResult(confirmedDet)

                if (confirmedDet.classIndex >= 0) {
                    triggerSingleArAnchor(confirmedDet.rect, srcW, srcH, confirmedDet.classIndex)
                }
            }
        } else {
            if (activeDets.isNotEmpty()) progress += PROGRESS_STEP else progress -= PROGRESS_DECAY
            progress = progress.coerceIn(0f, 1f)

            runOnUiThread {
                binding.overlayView.setFrameGeometry(srcW, srcH, boxF)
                binding.overlayView.setProgress(progress, activeDets.isNotEmpty())
                updateStatus(activeDets)

                if (activeDets.isNotEmpty() && activeDets[0].classIndex >= 0) {
                    triggerSingleArAnchor(activeDets[0].rect, srcW, srcH, activeDets[0].classIndex)
                }
            }
        }

        isProcessing = false
    }

    /**
     * Executes single ARCore Hit Test ONCE upon toy confirmation, creating a true 3D spatial Anchor.
     * ARCore maintains 6-DoF pose updates automatically afterwards.
     */
    private fun triggerSingleArAnchor(rect: RectF, frameW: Int, frameH: Int, classIndex: Int) {
        val viewW = binding.arSceneView.width.takeIf { it > 0 } ?: frameW
        val viewH = binding.arSceneView.height.takeIf { it > 0 } ?: frameH

        val scaleX = viewW.toFloat() / frameW
        val scaleY = viewH.toFloat() / frameH
        val scale  = minOf(scaleX, scaleY)
        val offsetX = (viewW - frameW * scale) / 2f
        val offsetY = (viewH - frameH * scale) / 2f

        val centerX = rect.centerX() * scale + offsetX
        val centerY = rect.centerY() * scale + offsetY

        arManager.onToyConfirmed(classIndex, centerX, centerY)
    }

    private fun calculateCoverage(pred: RectF, guideBox: RectF): Float {
        val ix1 = maxOf(pred.left, guideBox.left)
        val iy1 = maxOf(pred.top, guideBox.top)
        val ix2 = minOf(pred.right, guideBox.right)
        val iy2 = minOf(pred.bottom, guideBox.bottom)
        val iw = maxOf(0f, ix2 - ix1)
        val ih = maxOf(0f, iy2 - iy1)
        val inter = iw * ih
        val predArea = (pred.right - pred.left) * (pred.bottom - pred.top)
        if (predArea <= 0f) return 0f
        return inter / predArea
    }

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

        return bmp
    }

    private fun updateStatus(dets: List<YoloDetector.Detection>) {
        binding.statusText.text = when {
            dets.isEmpty() -> "Hold a toy inside the box"
            dets[0].classIndex < 0 -> "Unknown object"
            else -> "${dets[0].className}  ${"%.0f".format(dets[0].confidence * 100)}% — AR Spatial Anchor Active"
        }
        binding.statusText.setTextColor(if (dets.isEmpty()) Color.LTGRAY else Color.WHITE)
    }

    private fun showDetectionResult(det: YoloDetector.Detection) {
        binding.resultContainer.visibility = View.VISIBLE
        if (det.classIndex < 0 || det.className == "Unknown") {
            binding.resultTitle.text = "Unknown Object"
        } else {
            binding.resultTitle.text = "${det.className} Confirmed!"
        }
    }

    private fun resetForNewScan() {
        activeModelClassIndex = -1
        lockedClassIndex = -1
        arManager.clearArSession()

        binding.resultContainer.visibility = View.GONE
        binding.arSceneView.visibility = View.VISIBLE
        binding.overlayView.visibility = View.VISIBLE
        binding.statusText.visibility = View.VISIBLE

        progress = 0f
        lastDets = emptyList()
        framesSinceLastDet = 0
        candidateClassIndex = -1
        candidateFrameCount = 0
        attemptFrameCount = 0
        detectionLocked = false
        binding.overlayView.setProgress(0f, false)
        binding.statusText.text = "Point camera at a toy — hold it in the box"
    }

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
        stopCamera()
        inferenceExecutor.shutdown()
        if (::detector.isInitialized) detector.close()
        reusableBitmap?.recycle()
        arManager.clearArSession()
    }
}
