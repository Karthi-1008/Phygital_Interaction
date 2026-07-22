package com.madrasmindworks.kinderjoydetector

import ai.onnxruntime.*
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * YOLO11n ONNX detector for Kinder Joy / Phygital toys.
 *
 * Model output shape: [1, 8, 8400]
 *   Axis-1 layout: [cx, cy, w, h, score_class0 … score_class3]
 *   We transpose to [8400, 8] for easy iteration.
 */
class YoloDetector(private val context: Context) {

    data class Detection(
        val rect: RectF,        // pixel coords relative to the original image
        val classIndex: Int,
        val className: String,
        val confidence: Float
    )

    companion object {
        private const val TAG = "YoloDetector"
        private const val MODEL_FILE = "exp-3.onnx"
        private const val INPUT_SIZE = 640
        private const val CONF_THRESHOLD = 0.40f
        private const val IOU_THRESHOLD  = 0.45f

        // Must match model metadata names={0:'Harrypotter',1:'Hermionegranger',2:'Batman',3:'Flash'}
        val CLASS_NAMES = arrayOf("Harry Potter", "Hermione Granger", "Batman", "Flash")

        // Vivid colours per class for bounding boxes
        val CLASS_COLORS = intArrayOf(
            0xFF_FF6B35.toInt(),   // Harry Potter  — orange
            0xFF_9B59B6.toInt(),   // Hermione      — purple
            0xFF_1A1A2E.toInt(),   // Batman        — dark blue
            0xFF_E74C3C.toInt()    // Flash         — red
        )
    }

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var isLoaded = false

    // Pre-allocated float array to avoid per-frame GC
    private val inputBuffer = FloatArray(1 * 3 * INPUT_SIZE * INPUT_SIZE)
    private val pixelBuffer = IntArray(INPUT_SIZE * INPUT_SIZE)

    /** Call once on a background thread — loads model into ONNX Runtime. */
    fun load() {
        try {
            ortEnv = OrtEnvironment.getEnvironment()

            val sessionOptions = OrtSession.SessionOptions().apply {
                // Inter/intra-op parallelism
                setIntraOpNumThreads(4)
                setInterOpNumThreads(2)

                // Try NNAPI (Android GPU/DSP) first, fallback to CPU automatically
                try {
                    addNnapi()
                    Log.i(TAG, "NNAPI execution provider enabled")
                } catch (e: Exception) {
                    Log.w(TAG, "NNAPI unavailable, using CPU: ${e.message}")
                }

                // Graph optimisations
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }

            val modelBytes = context.assets.open(MODEL_FILE).readBytes()
            ortSession = ortEnv!!.createSession(modelBytes, sessionOptions)
            isLoaded = true
            Log.i(TAG, "YOLO11n loaded — ${CLASS_NAMES.size} classes")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
            isLoaded = false
        }
    }

    /**
     * Run inference on a camera frame bitmap.
     * [bitmap] is the raw camera output (any size).
     * Returns a list of Detections in *bitmap* coordinate space.
     */
    fun detect(bitmap: Bitmap): List<Detection> {
        if (!isLoaded || ortSession == null || ortEnv == null) return emptyList()

        val origW = bitmap.width.toFloat()
        val origH = bitmap.height.toFloat()

        // 1. Letterbox-scale to 640×640
        val (scaledBitmap, padLeft, padTop, scale) = letterbox(bitmap)

        // 2. Bitmap → normalised CHW float tensor (RGB, 0-1)
        bitmapToInputBuffer(scaledBitmap)
        if (scaledBitmap !== bitmap) scaledBitmap.recycle()

        // 3. Build ONNX tensor
        val shape = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        val inputTensor = OnnxTensor.createTensor(
            ortEnv, FloatBuffer.wrap(inputBuffer), shape
        )

        // 4. Inference
        val outputs = ortSession!!.run(mapOf("images" to inputTensor))
        inputTensor.close()

        // 5. Parse output [1, 8, 8400]
        val rawOutput = (outputs[0].value as Array<*>)[0] as Array<*>
        // rawOutput is [8][8400] (float arrays)
        val numAnchors = 8400
        val numClasses = CLASS_NAMES.size  // 4

        val detections = mutableListOf<Detection>()

        for (a in 0 until numAnchors) {
            // Model output axis-1: [cx, cy, w, h, c0, c1, c2, c3]
            val cx = (rawOutput[0] as FloatArray)[a]
            val cy = (rawOutput[1] as FloatArray)[a]
            val w  = (rawOutput[2] as FloatArray)[a]
            val h  = (rawOutput[3] as FloatArray)[a]

            // Find best class
            var bestScore = -1f
            var bestClass = 0
            for (c in 0 until numClasses) {
                val score = (rawOutput[4 + c] as FloatArray)[a]
                if (score > bestScore) {
                    bestScore = score
                    bestClass = c
                }
            }

            if (bestScore < CONF_THRESHOLD) continue

            // Convert cx/cy/w/h (in 640-space) → pixel coords on original image
            val x1 = ((cx - w / 2f) - padLeft) / scale
            val y1 = ((cy - h / 2f) - padTop)  / scale
            val x2 = ((cx + w / 2f) - padLeft) / scale
            val y2 = ((cy + h / 2f) - padTop)  / scale

            detections.add(
                Detection(
                    rect = RectF(
                        x1.coerceIn(0f, origW),
                        y1.coerceIn(0f, origH),
                        x2.coerceIn(0f, origW),
                        y2.coerceIn(0f, origH)
                    ),
                    classIndex = bestClass,
                    className  = CLASS_NAMES[bestClass],
                    confidence = bestScore
                )
            )
        }

        outputs.close()

        // 6. NMS per class
        return nms(detections)
    }

    // ─── Letterbox ───────────────────────────────────────────────────────────

    private data class LetterboxResult(
        val bitmap: Bitmap,
        val padLeft: Float,
        val padTop: Float,
        val scale: Float
    )

    private fun letterbox(src: Bitmap): LetterboxResult {
        val scale = min(
            INPUT_SIZE.toFloat() / src.width,
            INPUT_SIZE.toFloat() / src.height
        )
        val newW = (src.width  * scale).toInt()
        val newH = (src.height * scale).toInt()
        val padLeft = (INPUT_SIZE - newW) / 2f
        val padTop  = (INPUT_SIZE - newH) / 2f

        // Scaled version
        val scaled = Bitmap.createScaledBitmap(src, newW, newH, true)

        // Paste onto 640×640 grey canvas
        val out = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.drawColor(android.graphics.Color.rgb(114, 114, 114))  // YOLO grey pad
        canvas.drawBitmap(scaled, padLeft, padTop, null)
        if (scaled !== src) scaled.recycle()

        return LetterboxResult(out, padLeft, padTop, scale)
    }

    // ─── Bitmap → CHW float buffer ────────────────────────────────────────────

    private fun bitmapToInputBuffer(bmp: Bitmap) {
        bmp.getPixels(pixelBuffer, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        val rOffset = 0
        val gOffset = INPUT_SIZE * INPUT_SIZE
        val bOffset = 2 * INPUT_SIZE * INPUT_SIZE
        for (i in pixelBuffer.indices) {
            val px = pixelBuffer[i]
            inputBuffer[rOffset + i] = ((px shr 16) and 0xFF) / 255f
            inputBuffer[gOffset + i] = ((px shr  8) and 0xFF) / 255f
            inputBuffer[bOffset + i] = ( px         and 0xFF) / 255f
        }
    }

    // ─── Non-Maximum Suppression ──────────────────────────────────────────────

    private fun nms(detections: List<Detection>): List<Detection> {
        val sorted = detections.sortedByDescending { it.confidence }.toMutableList()
        val kept = mutableListOf<Detection>()
        val suppressed = BooleanArray(sorted.size)

        for (i in sorted.indices) {
            if (suppressed[i]) continue
            kept.add(sorted[i])
            for (j in i + 1 until sorted.size) {
                if (suppressed[j]) continue
                if (sorted[i].classIndex != sorted[j].classIndex) continue
                if (iou(sorted[i].rect, sorted[j].rect) > IOU_THRESHOLD) {
                    suppressed[j] = true
                }
            }
        }
        return kept
    }

    private fun iou(a: RectF, b: RectF): Float {
        val interX1 = max(a.left,   b.left)
        val interY1 = max(a.top,    b.top)
        val interX2 = min(a.right,  b.right)
        val interY2 = min(a.bottom, b.bottom)
        val interW  = max(0f, interX2 - interX1)
        val interH  = max(0f, interY2 - interY1)
        val inter   = interW * interH
        if (inter == 0f) return 0f
        val areaA   = (a.right - a.left) * (a.bottom - a.top)
        val areaB   = (b.right - b.left) * (b.bottom - b.top)
        return inter / (areaA + areaB - inter)
    }

    /** Release native resources — call in onDestroy. */
    fun close() {
        try {
            ortSession?.close()
            ortEnv?.close()
        } catch (_: Exception) {}
    }
}
