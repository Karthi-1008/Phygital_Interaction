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
 * YOLO11n ONNX detector — optimised for accuracy + speed.
 *
 * Key fixes vs v1:
 *  • Per-class confidence thresholds (Harry Potter & Batman need lower threshold)
 *  • Proper letterbox inverse mapping (eliminates box drift)
 *  • Reuse pre-allocated RGB buffer — no per-frame GC
 *  • Multi-label output: reports ALL classes above threshold in one pass
 */
class YoloDetector(private val context: Context) {

    data class Detection(
        val rect: RectF,
        val classIndex: Int,
        val className: String,
        val confidence: Float
    )

    companion object {
        private const val TAG = "YoloDetector"
        private const val MODEL_FILE = "exp-3.onnx"
        const val INPUT_SIZE = 640

        // Model class order from metadata
        val CLASS_NAMES = arrayOf("Harry Potter", "Hermione Granger", "Batman", "Flash")

        // Per-class thresholds — lower for classes that under-detect
        // Harry Potter & Batman need lower thresholds because they're harder to distinguish
        private val CLASS_THRESHOLDS = floatArrayOf(
            0.30f,   // Harry Potter   — was missing detections, lowered
            0.40f,   // Hermione       — good detection, keep moderate
            0.28f,   // Batman         — dark toy, harder to detect, lowered more
            0.38f    // Flash          — good detection
        )

        private const val IOU_THRESHOLD = 0.40f   // tighter NMS = fewer duplicate boxes

        val CLASS_COLORS = intArrayOf(
            0xFF_FF6B35.toInt(),   // Harry Potter  — orange
            0xFF_9B59B6.toInt(),   // Hermione      — purple
            0xFF_4A4A8A.toInt(),   // Batman        — dark blue
            0xFF_E74C3C.toInt()    // Flash         — red
        )
    }

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    var isLoaded = false
        private set

    // Pre-allocated — zero GC per frame
    private val inputBuffer = FloatArray(3 * INPUT_SIZE * INPUT_SIZE)

    // Reusable source-pixel buffer, resized only if camera resolution changes
    private var srcPixels = IntArray(0)
    private var srcPixelsW = 0
    private var srcPixelsH = 0

    fun load() {
        try {
            ortEnv = OrtEnvironment.getEnvironment()

            val cores = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)

            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(cores)
                setInterOpNumThreads(1)                 // single graph, no parallel branches to gain from >1
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setMemoryPatternOptimization(true)
                setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                try {
                    addNnapi()
                    Log.i(TAG, "NNAPI enabled, $cores threads")
                } catch (e: Exception) {
                    Log.w(TAG, "CPU fallback: ${e.message}")
                }
            }

            val bytes = context.assets.open(MODEL_FILE).readBytes()
            ortSession = ortEnv!!.createSession(bytes, opts)
            isLoaded = true
            Log.i(TAG, "Model loaded — ${CLASS_NAMES.size} classes")
        } catch (e: Exception) {
            Log.e(TAG, "Model load failed", e)
        }
    }

    /**
     * Run detection on [bitmap] (any size).
     * Returns detections in original bitmap pixel coordinates.
     *
     * No intermediate Bitmap objects are created here — the previous version
     * allocated a scaled Bitmap + a padded Bitmap + a Canvas every single frame,
     * which is the single biggest source of GC pauses (and therefore dropped
     * frames) on low-end phones. This version reads pixels once into a reused
     * IntArray and resizes+letterboxes directly into the float tensor buffer.
     */
    fun detect(bitmap: Bitmap): List<Detection> {
        if (!isLoaded) return emptyList()

        val origW = bitmap.width
        val origH = bitmap.height

        // Resize source-pixel buffer only when camera resolution actually changes
        if (srcPixelsW != origW || srcPixelsH != origH) {
            srcPixels = IntArray(origW * origH)
            srcPixelsW = origW
            srcPixelsH = origH
        }
        bitmap.getPixels(srcPixels, 0, origW, 0, 0, origW, origH)

        // 1. Letterbox geometry (kept aspect ratio, matches OverlayView math)
        val scale    = min(INPUT_SIZE / origW.toFloat(), INPUT_SIZE / origH.toFloat())
        val newW     = (origW * scale).toInt().coerceAtLeast(1)
        val newH     = (origH * scale).toInt().coerceAtLeast(1)
        val padLeft  = (INPUT_SIZE - newW) / 2
        val padTop   = (INPUT_SIZE - newH) / 2

        val rOff = 0
        val gOff = INPUT_SIZE * INPUT_SIZE
        val bOff = 2 * INPUT_SIZE * INPUT_SIZE
        val padVal = 114f / 255f

        // 2. Direct nearest-neighbour resize + letterbox straight into the
        //    normalised planar float tensor — zero extra allocations, zero Bitmaps.
        for (y in 0 until INPUT_SIZE) {
            val srcY = y - padTop
            val rowIsPad = srcY < 0 || srcY >= newH
            val sy = if (rowIsPad) 0 else (srcY * origH / newH).coerceIn(0, origH - 1)
            val rowBase = y * INPUT_SIZE
            for (x in 0 until INPUT_SIZE) {
                val srcX = x - padLeft
                if (rowIsPad || srcX < 0 || srcX >= newW) {
                    inputBuffer[rOff + rowBase + x] = padVal
                    inputBuffer[gOff + rowBase + x] = padVal
                    inputBuffer[bOff + rowBase + x] = padVal
                } else {
                    val sx = (srcX * origW / newW).coerceIn(0, origW - 1)
                    val px = srcPixels[sy * origW + sx]
                    inputBuffer[rOff + rowBase + x] = ((px shr 16) and 0xFF) * (1f / 255f)
                    inputBuffer[gOff + rowBase + x] = ((px shr  8) and 0xFF) * (1f / 255f)
                    inputBuffer[bOff + rowBase + x] = ( px         and 0xFF) * (1f / 255f)
                }
            }
        }

        val padLeftF = padLeft.toFloat()
        val padTopF  = padTop.toFloat()

        // 3. Inference
        val shape  = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        val tensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(inputBuffer), shape)
        val result = ortSession!!.run(mapOf("images" to tensor))
        tensor.close()

        // 4. Parse [1, 8, 8400]  →  each anchor: [cx,cy,w,h, s0,s1,s2,s3]
        val raw       = (result[0].value as Array<*>)[0] as Array<*>
        val numAnch   = 8400
        val numClass  = CLASS_NAMES.size
        val dets      = mutableListOf<Detection>()

        for (a in 0 until numAnch) {
            // Best class score
            var bestScore = -1f
            var bestCls   = 0
            for (c in 0 until numClass) {
                val s = (raw[4 + c] as FloatArray)[a]
                if (s > bestScore) { bestScore = s; bestCls = c }
            }

            // Per-class threshold check
            if (bestScore < CLASS_THRESHOLDS[bestCls]) continue

            // 640-space cx,cy,w,h  →  original image pixel coords
            val cx = (raw[0] as FloatArray)[a]
            val cy = (raw[1] as FloatArray)[a]
            val w  = (raw[2] as FloatArray)[a]
            val h  = (raw[3] as FloatArray)[a]

            val x1 = ((cx - w * 0.5f) - padLeftF) / scale
            val y1 = ((cy - h * 0.5f) - padTopF)  / scale
            val x2 = ((cx + w * 0.5f) - padLeftF) / scale
            val y2 = ((cy + h * 0.5f) - padTopF)  / scale

            dets.add(Detection(
                rect       = RectF(
                    x1.coerceIn(0f, origW.toFloat()), y1.coerceIn(0f, origH.toFloat()),
                    x2.coerceIn(0f, origW.toFloat()), y2.coerceIn(0f, origH.toFloat())
                ),
                classIndex = bestCls,
                className  = CLASS_NAMES[bestCls],
                confidence = bestScore
            ))
        }

        result.close()
        return nms(dets)
    }

    // ── Non-Maximum Suppression (per class) ───────────────────────────────────

    private fun nms(dets: List<Detection>): List<Detection> {
        val sorted     = dets.sortedByDescending { it.confidence }
        val suppressed = BooleanArray(sorted.size)
        val kept       = mutableListOf<Detection>()

        for (i in sorted.indices) {
            if (suppressed[i]) continue
            kept.add(sorted[i])
            for (j in i + 1 until sorted.size) {
                if (suppressed[j]) continue
                if (sorted[i].classIndex != sorted[j].classIndex) continue
                if (iou(sorted[i].rect, sorted[j].rect) > IOU_THRESHOLD) suppressed[j] = true
            }
        }
        return kept
    }

    private fun iou(a: RectF, b: RectF): Float {
        val ix1 = max(a.left, b.left);   val iy1 = max(a.top,    b.top)
        val ix2 = min(a.right, b.right); val iy2 = min(a.bottom, b.bottom)
        val iw  = max(0f, ix2 - ix1);   val ih  = max(0f, iy2 - iy1)
        val inter = iw * ih
        if (inter == 0f) return 0f
        return inter / ((a.right-a.left)*(a.bottom-a.top) + (b.right-b.left)*(b.bottom-b.top) - inter)
    }

    fun close() {
        try { ortSession?.close(); ortEnv?.close() } catch (_: Exception) {}
    }
}
