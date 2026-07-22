package com.madrasmindworks.kinderjoydetector

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.TextView
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

    // Dedicated single-thread executor for inference (avoids UI-thread stalls)
    private lateinit var inferenceExecutor: ExecutorService
    private lateinit var cameraExecutor: ExecutorService

    // FPS tracking
    private var lastFpsTime = System.currentTimeMillis()
    private var frameCount = 0

    // Throttle: skip frames if previous inference still running
    @Volatile private var isProcessing = false

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_CAMERA = 10
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Show loading state
        binding.statusText.text = "Loading model…"
        binding.fpsText.visibility = View.GONE

        // Initialise executors
        cameraExecutor    = Executors.newSingleThreadExecutor()
        inferenceExecutor = Executors.newSingleThreadExecutor()

        // Load ONNX model off the main thread
        inferenceExecutor.execute {
            detector = YoloDetector(this)
            detector.load()

            runOnUiThread {
                binding.statusText.text = "Point camera at a toy"
                binding.fpsText.visibility = View.VISIBLE

                // Now request camera
                if (hasCameraPermission()) startCamera()
                else requestCameraPermission()
            }
        }
    }

    // ─── Camera Setup ─────────────────────────────────────────────────────────

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val cameraProvider = providerFuture.get()

            // Preview use-case — renders to PreviewView
            val preview = Preview.Builder()
                .setTargetResolution(Size(640, 480))
                .build()
                .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

            // Analysis use-case — delivers frames for inference
            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(inferenceExecutor) { imageProxy ->
                        processFrame(imageProxy)
                    }
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ─── Frame Processing ─────────────────────────────────────────────────────

    private fun processFrame(imageProxy: ImageProxy) {
        // Skip if last inference still running (keeps UI smooth on slow devices)
        if (isProcessing) {
            imageProxy.close()
            return
        }
        isProcessing = true

        try {
            val bitmap = imageProxyToBitmap(imageProxy)
            imageProxy.close()   // Release immediately — camera can reuse buffer

            val detections = detector.detect(bitmap)
            bitmap.recycle()

            // Update overlay on UI thread
            runOnUiThread {
                binding.overlayView.setDetections(
                    detections,
                    imageProxy.width,
                    imageProxy.height
                )
                updateStatus(detections)
                updateFps()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Frame processing error", e)
            imageProxy.close()
        } finally {
            isProcessing = false
        }
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        // RGBA_8888 format — single plane
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride  = plane.pixelStride
        val rowStride    = plane.rowStride
        val rowPadding   = rowStride - pixelStride * image.width

        val bmp = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bmp.copyPixelsFromBuffer(buffer)

        // Crop to exact size if padding was added
        return if (rowPadding == 0) bmp
        else Bitmap.createBitmap(bmp, 0, 0, image.width, image.height)
    }

    // ─── UI helpers ──────────────────────────────────────────────────────────

    private fun updateStatus(detections: List<YoloDetector.Detection>) {
        binding.statusText.text = when {
            detections.isEmpty() -> "No toy detected"
            detections.size == 1 -> detections[0].className
            else -> detections.joinToString(" · ") { it.className }
        }
    }

    private fun updateFps() {
        frameCount++
        val now = System.currentTimeMillis()
        val elapsed = now - lastFpsTime
        if (elapsed >= 1000) {
            val fps = frameCount * 1000f / elapsed
            binding.fpsText.text = "%.0f FPS".format(fps)
            frameCount = 0
            lastFpsTime = now
        }
    }

    // ─── Permission handling ──────────────────────────────────────────────────

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            REQUEST_CODE_CAMERA
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_CAMERA) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
                binding.statusText.text = "Camera permission denied"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        inferenceExecutor.shutdown()
        if (::detector.isInitialized) detector.close()
    }
}
