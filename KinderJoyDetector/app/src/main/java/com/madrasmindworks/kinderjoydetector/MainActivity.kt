package com.madrasmindworks.kinderjoydetector

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
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.madrasmindworks.kinderjoydetector.ar.ARPositionCalculator
import com.madrasmindworks.kinderjoydetector.ar.ARViewController
import com.madrasmindworks.kinderjoydetector.databinding.ActivityMainBinding
import com.madrasmindworks.kinderjoydetector.utils.PermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var permissionHelper: PermissionHelper
    private lateinit var detector: YoloDetector
    private lateinit var inferenceExecutor: ExecutorService
    private lateinit var cameraProvider: ProcessCameraProvider
    private lateinit var arViewController: ARViewController

    private var toyLostJob: Job? = null
    private var lastDetectedRect: RectF? = null

    // Frame skip guard
    @Volatile private var isProcessing = false
    private var reusableBitmap: Bitmap? = null

    // Guide box
    private var guideBoxFrame: RectF? = null
    private var guideBoxRect: Rect? = null

    // Temporal smoothing
    private var lastDets: List<YoloDetector.Detection> = emptyList()
    private var framesSinceLastDet = 0
    private val HOLD_FRAMES = 6

    // Multi-frame temporal stability tracking
    private var candidateClassIndex = -1
    private var candidateFrameCount = 0
    private var attemptFrameCount = 0
    private val REQUIRED_CONFIDENCE = 0.60f
    private val REQUIRED_STABLE_FRAMES = 3
    private val MIN_COVERAGE = 0.50f
    private val UNKNOWN_TIMEOUT_FRAMES = 35

    private var progress = 0f
    private val PROGRESS_STEP = 0.055f
    private val PROGRESS_DECAY = 0.09f

    @Volatile private var detectionLocked = false

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        permissionHelper = PermissionHelper(
            activity = this,
            onGranted = { startARPipeline() },
            onDenied = { showPermissionDeniedMessage() }
        )

        permissionHelper.checkAndRequest()
    }

    private fun startARPipeline() {
        binding.statusText.text = "Loading detector model…"
        binding.btnScanAgain.setOnClickListener { resetForNewScan() }

        // 1. Initialize AR View Controller
        arViewController = ARViewController(binding.sceneView, this, lifecycleScope)
        arViewController.init()

        // 2. Initialize Executor & YOLO Detector
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
                startCamera()
            }
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()

            val preview = Preview.Builder()
                .setTargetResolution(Size(360, 480))
                .build()
                .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(360, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(inferenceExecutor, ::processFrame) }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                Log.i(TAG, "Camera bound successfully to PreviewView")
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        if (::cameraProvider.isInitialized) cameraProvider.unbindAll()
    }

    private fun ensureGuideBox(frameW: Int, frameH: Int) {
        if (guideBoxFrame != null) return
        val size = (minOf(frameW, frameH) * 0.62f)
        val left = (frameW - size) / 2f
        val top  = (frameH - size) / 2f
        guideBoxFrame = RectF(left, top, left + size, top + size)
        guideBoxRect = Rect(left.toInt(), top.toInt(), (left + size).toInt(), (top + size).toInt())
    }

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
                    Log.i(TAG, "Toy confirmed: ${topDet.className} (${topDet.confidence * 100}%)")
                } else {
                    confirmedDet = null
                    isConfirmed = false
                    lastDets = highConfInBoxDets
                    framesSinceLastDet = 0
                }
            } else {
                candidateClassIndex = -1
                candidateFrameCount = 0
                confirmedDet = null
                isConfirmed = false
                if (framesSinceLastDet < HOLD_FRAMES && lastDets.isNotEmpty()) {
                    framesSinceLastDet++
                } else {
                    lastDets = emptyList()
                }
            }
        } else {
            confirmedDet = null
            isConfirmed = false
        }

        val activeDets = if (highConfInBoxDets.isNotEmpty()) highConfInBoxDets else (if (framesSinceLastDet < HOLD_FRAMES) lastDets else emptyList())

        if (isConfirmed && confirmedDet != null) {
            detectionLocked = true
            progress = 1.0f

            runOnUiThread {
                binding.overlayView.setFrameGeometry(srcW, srcH, boxF)
                binding.overlayView.setProgress(1.0f, confirmedDet.classIndex >= 0)
                updateStatus(if (confirmedDet.classIndex >= 0) listOf(confirmedDet) else emptyList())
                showDetectionResult(confirmedDet)

                if (confirmedDet.classIndex >= 0) {
                    onToyDetected(confirmedDet.rect, confirmedDet.classIndex)
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
                    onToyDetected(activeDets[0].rect, activeDets[0].classIndex)
                } else {
                    onToyLost()
                }
            }
        }

        isProcessing = false
    }

    private fun onToyDetected(rect: RectF, classIndex: Int) {
        toyLostJob?.cancel()
        lastDetectedRect = rect

        val worldPos = ARPositionCalculator.toWorldPosition(rect)
        val scale = ARPositionCalculator.toModelScale(rect)

        arViewController.loadAndShowModel(classIndex, worldPos, scale)
    }

    private fun onToyLost() {
        toyLostJob?.cancel()
        toyLostJob = lifecycleScope.launch {
            delay(1500)
            arViewController.hideModel()
        }
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
        val plane = img.planes[0]
        val rowStride = plane.rowStride
        val pixStride = plane.pixelStride
        val rowPad = rowStride - pixStride * img.width
        val strideW = img.width + rowPad / pixStride

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
            else -> "${dets[0].className} ${"%.0f".format(dets[0].confidence * 100)}% — 3D Overlay Active"
        }
        binding.statusText.setTextColor(if (dets.isEmpty()) Color.LTGRAY else Color.WHITE)
    }

    private fun showDetectionResult(det: YoloDetector.Detection) {
        binding.resultContainer.visibility = View.VISIBLE
        binding.resultTitle.text = if (det.classIndex < 0 || det.className == "Unknown") "Unknown Object" else "${det.className} Confirmed!"
    }

    private fun resetForNewScan() {
        arViewController.hideModel()
        binding.resultContainer.visibility = View.GONE
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

    private fun showPermissionDeniedMessage() {
        Toast.makeText(this, "Camera permission is required to detect toys and render 3D AR overlays.", Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::cameraProvider.isInitialized) stopCamera()
        if (::inferenceExecutor.isInitialized) inferenceExecutor.shutdown()
        if (::detector.isInitialized) detector.close()
        reusableBitmap?.recycle()
        if (::arViewController.isInitialized) arViewController.destroy()
    }
}
