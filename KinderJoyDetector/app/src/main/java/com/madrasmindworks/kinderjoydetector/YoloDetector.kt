package com.madrasmindworks.kinderjoydetector

import ai.onnxruntime.*
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import ai.onnxruntime.TensorInfo
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

        // Preferred input size when the model graph allows a dynamic input
        // shape (smaller tensor = less resize work + fewer FLOPs = faster on
        // low-end phones). exp-3.onnx is currently exported with a FIXED
        // 640x640 input, so this only takes effect if/when the model is
        // re-exported with dynamic H/W axes — detectInputSize() below reads
        // the graph's actual shape at load time and falls back to 640
        // automatically, so this is always safe to leave enabled.
        private const val PREFERRED_DYNAMIC_INPUT_SIZE = 320
        private const val FALLBACK_INPUT_SIZE = 640

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

    // Resolved once at load() from the model's own graph metadata — see
    // detectInputSize(). Not a compile-time constant any more.
    var inputSize = FALLBACK_INPUT_SIZE
        private set

    // Pre-allocated — zero GC per frame. Sized once inputSize is known.
    private lateinit var inputBuffer: FloatArray

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

            inputSize = detectInputSize(ortSession!!)
            inputBuffer = FloatArray(3 * inputSize * inputSize)

            isLoaded = true
            Log.i(TAG, "Model loaded — ${CLASS_NAMES.size} classes, input=${inputSize}x$inputSize")
        } catch (e: Exception) {
            Log.e(TAG, "Model load failed", e)
        }
    }

    /**
     * Reads the model's actual input tensor shape (NCHW) to decide what
     * square size we should resize/letterbox into. If the graph declares a
     * concrete H/W (the normal case for exp-3.onnx, fixed at 640) we must use
     * exactly that value. If the graph instead declares a dynamic axis (-1),
     * we're free to pick a smaller size for speed.
     */
    private fun detectInputSize(session: OrtSession): Int {
        return try {
            val inputInfo = session.inputInfo.values.first().info as TensorInfo
            val shape = inputInfo.shape   // [N, C, H, W]
            val h = if (shape.size >= 3) shape[2] else -1L
            when {
                h > 0 -> h.toInt()                       // fixed shape — must match exactly
                else  -> PREFERRED_DYNAMIC_INPUT_SIZE    // dynamic — pick the fast option
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read input shape, defaulting to $FALLBACK_INPUT_SIZE", e)
            FALLBACK_INPUT_SIZE
        }
    }

    /**
     * Run detection on the region of [bitmap] inside [cropRect] (full bitmap
     * if null). Returned detections are in ORIGINAL [bitmap] pixel coordinates
     * (the crop offset is already added back in), so callers never have to
     * think about the crop again.
     *
     * Cropping to the on-screen guide box before inference means the model
     * only ever has to reason about the region the user is actually pointing
     * at — less background clutter for it to reject, and less source-pixel
     * area to read per frame.
     */
    fun detect(bitmap: Bitmap, cropRect: android.graphics.Rect? = null): List<Detection> {
        if (!isLoaded) return emptyList()

        val cropLeft: Int
        val cropTop: Int
        val srcBitmap: Bitmap
        if (cropRect != null) {
            cropLeft = cropRect.left
            cropTop = cropRect.top
            // Cheap: createBitmap(Bitmap, l, t, w, h) shares the same pixel
            // buffer when no rotation/matrix is applied — no pixel copy here.
            srcBitmap = Bitmap.createBitmap(bitmap, cropRect.left, cropRect.top, cropRect.width(), cropRect.height())
        } else {
            cropLeft = 0
            cropTop = 0
            srcBitmap = bitmap
        }

        return runInference(srcBitmap, cropLeft, cropTop)
    }

    private fun runInference(bitmap: Bitmap, cropLeft: Int, cropTop: Int): List<Detection> {
        val origW = bitmap.width
        val origH = bitmap.height
        val INPUT_SIZE = inputSize

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

        // 4. Parse [1, 4+numClass, numAnchors] → each anchor: [cx,cy,w,h, s0..sN]
        //    numAnchors is read from the actual output tensor rather than a
        //    hardcoded 8400, so this keeps working if inputSize ever changes
        //    (anchor count scales with input resolution: 640→8400, 320→2100).
        val raw       = (result[0].value as Array<*>)[0] as Array<*>
        val numClass  = CLASS_NAMES.size
        val numAnch   = (raw[4] as FloatArray).size
        val dets      = mutableListOf<Detection>()
        val cropLeftF = cropLeft.toFloat()
        val cropTopF  = cropTop.toFloat()

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
                    x1.coerceIn(0f, origW.toFloat()) + cropLeftF, y1.coerceIn(0f, origH.toFloat()) + cropTopF,
                    x2.coerceIn(0f, origW.toFloat()) + cropLeftF, y2.coerceIn(0f, origH.toFloat()) + cropTopF
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
