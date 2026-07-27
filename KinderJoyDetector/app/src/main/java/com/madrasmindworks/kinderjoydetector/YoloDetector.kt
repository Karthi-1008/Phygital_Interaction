package com.madrasmindworks.kinderjoydetector

import ai.onnxruntime.*
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * YOLO11n ONNX detector — optimized for accuracy + speed.
 * Supports zero-allocation inference, adaptive rotation augmentation (0°, 90°, 270°, 180°),
 * inverse box transformation, and single-pass NMS.
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

        private const val PREFERRED_DYNAMIC_INPUT_SIZE = 320
        private const val FALLBACK_INPUT_SIZE = 640

        val CLASS_NAMES = arrayOf("Harry Potter", "Hermione Granger", "Batman", "Flash")

        // Per-class thresholds
        val CLASS_THRESHOLDS = floatArrayOf(
            0.35f,   // Harry Potter
            0.40f,   // Hermione
            0.45f,   // Batman         — raised to prevent shape-only color-mismatched false positives
            0.38f    // Flash
        )

        private const val IOU_THRESHOLD = 0.40f   // NMS threshold

        val CLASS_COLORS = intArrayOf(
            0xFF_FF6B35.toInt(),   // Harry Potter  — orange
            0xFF_9B59B6.toInt(),   // Hermione      — purple
            0xFF_4A4A8A.toInt(),   // Batman        — dark blue
            0xFF_E74C3C.toInt()    // Flash         — red
        )
    }

    /**
     * Configuration toggle for rotation augmentation.
     * Set to false to disable rotation inference and run 0° only.
     */
    var enableRotationAugmentation: Boolean = true

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    var isLoaded = false
        private set

    var inputSize = FALLBACK_INPUT_SIZE
        private set

    // Pre-allocated FloatArray for normalized planar NCHW input — zero GC per frame
    private lateinit var inputBuffer: FloatArray

    // Reusable cropped source pixel array — resized only if crop dimensions change
    private var srcPixels = IntArray(0)
    private var srcPixelsW = 0
    private var srcPixelsH = 0
    private var frameCounter = 0

    fun load() {
        try {
            ortEnv = OrtEnvironment.getEnvironment()
            val cores = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(cores)
                setInterOpNumThreads(1)                 // single graph
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setMemoryPatternOptimization(true)
                setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)

                var accelerated = false
                try {
                    addXnnpack(mapOf("intra_op_num_threads" to cores.toString()))
                    Log.i(TAG, "XNNPACK enabled, $cores threads")
                    accelerated = true
                } catch (e: Exception) {
                    Log.w(TAG, "XNNPACK unavailable: ${e.message}")
                }
                if (!accelerated) {
                    try {
                        addNnapi()
                        Log.i(TAG, "NNAPI enabled, $cores threads")
                    } catch (e: Exception) {
                        Log.w(TAG, "CPU fallback: ${e.message}")
                    }
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

    private fun detectInputSize(session: OrtSession): Int {
        return try {
            val inputInfo = session.inputInfo.values.first().info as TensorInfo
            val shape = inputInfo.shape
            val h = if (shape.size >= 3) shape[2] else -1L
            when {
                h > 0 -> h.toInt()
                else  -> PREFERRED_DYNAMIC_INPUT_SIZE
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read input shape, defaulting to $FALLBACK_INPUT_SIZE", e)
            FALLBACK_INPUT_SIZE
        }
    }

    /**
     * Run detection on the region of [bitmap] inside [cropRect].
     * Uses direct zero-allocation pixel buffer extraction + adaptive rotation inference.
     * Optional [isValidDet] callback allows early termination if a candidate detection
     * satisfies all constraints (confidence + coverage >= 50%).
     */
    fun detect(
        bitmap: Bitmap,
        cropRect: Rect? = null,
        isValidDet: ((RectF, Int, Float) -> Boolean)? = null
    ): List<Detection> {
        if (!isLoaded) return emptyList()

        val cropLeft = (cropRect?.left ?: 0).coerceIn(0, bitmap.width - 1)
        val cropTop  = (cropRect?.top ?: 0).coerceIn(0, bitmap.height - 1)
        val cropW    = (cropRect?.width() ?: bitmap.width).coerceIn(1, bitmap.width - cropLeft)
        val cropH    = (cropRect?.height() ?: bitmap.height).coerceIn(1, bitmap.height - cropTop)

        // Read source pixels directly into reusable IntArray — ZERO Bitmap allocations
        if (srcPixelsW != cropW || srcPixelsH != cropH) {
            srcPixels = IntArray(cropW * cropH)
            srcPixelsW = cropW
            srcPixelsH = cropH
        }
        bitmap.getPixels(srcPixels, 0, cropW, cropLeft, cropTop, cropW, cropH)

        val rotationAngles = if (enableRotationAugmentation) intArrayOf(0, 90, 270, 180) else intArrayOf(0)
        val allDets = mutableListOf<Detection>()

        for (angle in rotationAngles) {
            val dets = runInferenceForRotation(angle, cropW, cropH, cropLeft, cropTop)
            if (dets.isNotEmpty()) {
                allDets.addAll(dets)
                // Adaptive early exit: if any detection at this rotation angle is valid (passes coverage & threshold),
                // stop trying further rotations!
                val hasValid = isValidDet != null && dets.any { isValidDet(it.rect, it.classIndex, it.confidence) }
                if (hasValid || isValidDet == null) {
                    break
                }
            }
        }

        return nms(allDets)
    }

    private fun runInferenceForRotation(
        angle: Int,
        cropW: Int,
        cropH: Int,
        cropLeft: Int,
        cropTop: Int
    ): List<Detection> {
        val INPUT_SIZE = inputSize

        // Rotated crop dimensions
        val rotW = if (angle == 90 || angle == 270) cropH else cropW
        val rotH = if (angle == 90 || angle == 270) cropW else cropH

        // 1. Letterbox geometry for rotW x rotH into INPUT_SIZE x INPUT_SIZE
        val scale    = min(INPUT_SIZE / rotW.toFloat(), INPUT_SIZE / rotH.toFloat())
        val newW     = (rotW * scale).toInt().coerceAtLeast(1)
        val newH     = (rotH * scale).toInt().coerceAtLeast(1)
        val padLeft  = (INPUT_SIZE - newW) / 2
        val padTop   = (INPUT_SIZE - newH) / 2

        val rOff = 0
        val gOff = INPUT_SIZE * INPUT_SIZE
        val bOff = 2 * INPUT_SIZE * INPUT_SIZE
        val padVal = 114f / 255f
        val inv255 = 1f / 255f

        // 2. Ultra-fast pixel extraction with rotation into pre-allocated inputBuffer
        //    Outer branch hoisting eliminates 400,000+ conditional checks per frame.
        when (angle) {
            0 -> {
                for (y in 0 until INPUT_SIZE) {
                    val srcY = y - padTop
                    val rowIsPad = srcY < 0 || srcY >= newH
                    val ry = if (rowIsPad) 0 else (srcY * rotH / newH).coerceIn(0, rotH - 1)
                    val rowBase = y * INPUT_SIZE
                    val syBase = ry * cropW
                    for (x in 0 until INPUT_SIZE) {
                        val srcX = x - padLeft
                        val idx = rowBase + x
                        if (rowIsPad || srcX < 0 || srcX >= newW) {
                            inputBuffer[rOff + idx] = padVal
                            inputBuffer[gOff + idx] = padVal
                            inputBuffer[bOff + idx] = padVal
                        } else {
                            val rx = (srcX * rotW / newW).coerceIn(0, rotW - 1)
                            val px = srcPixels[syBase + rx]
                            inputBuffer[rOff + idx] = ((px shr 16) and 0xFF) * inv255
                            inputBuffer[gOff + idx] = ((px shr  8) and 0xFF) * inv255
                            inputBuffer[bOff + idx] = ( px         and 0xFF) * inv255
                        }
                    }
                }
            }
            90 -> { // 90° CW: sx = ry, sy = cropH - 1 - rx
                for (y in 0 until INPUT_SIZE) {
                    val srcY = y - padTop
                    val rowIsPad = srcY < 0 || srcY >= newH
                    val ry = if (rowIsPad) 0 else (srcY * rotH / newH).coerceIn(0, rotH - 1)
                    val rowBase = y * INPUT_SIZE
                    val sx = ry.coerceIn(0, cropW - 1)
                    for (x in 0 until INPUT_SIZE) {
                        val srcX = x - padLeft
                        val idx = rowBase + x
                        if (rowIsPad || srcX < 0 || srcX >= newW) {
                            inputBuffer[rOff + idx] = padVal
                            inputBuffer[gOff + idx] = padVal
                            inputBuffer[bOff + idx] = padVal
                        } else {
                            val rx = (srcX * rotW / newW).coerceIn(0, rotW - 1)
                            val sy = (cropH - 1 - rx).coerceIn(0, cropH - 1)
                            val px = srcPixels[sy * cropW + sx]
                            inputBuffer[rOff + idx] = ((px shr 16) and 0xFF) * inv255
                            inputBuffer[gOff + idx] = ((px shr  8) and 0xFF) * inv255
                            inputBuffer[bOff + idx] = ( px         and 0xFF) * inv255
                        }
                    }
                }
            }
            180 -> { // 180°: sx = cropW - 1 - rx, sy = cropH - 1 - ry
                for (y in 0 until INPUT_SIZE) {
                    val srcY = y - padTop
                    val rowIsPad = srcY < 0 || srcY >= newH
                    val ry = if (rowIsPad) 0 else (srcY * rotH / newH).coerceIn(0, rotH - 1)
                    val rowBase = y * INPUT_SIZE
                    val sy = (cropH - 1 - ry).coerceIn(0, cropH - 1)
                    val syBase = sy * cropW
                    for (x in 0 until INPUT_SIZE) {
                        val srcX = x - padLeft
                        val idx = rowBase + x
                        if (rowIsPad || srcX < 0 || srcX >= newW) {
                            inputBuffer[rOff + idx] = padVal
                            inputBuffer[gOff + idx] = padVal
                            inputBuffer[bOff + idx] = padVal
                        } else {
                            val rx = (srcX * rotW / newW).coerceIn(0, rotW - 1)
                            val sx = (cropW - 1 - rx).coerceIn(0, cropW - 1)
                            val px = srcPixels[syBase + sx]
                            inputBuffer[rOff + idx] = ((px shr 16) and 0xFF) * inv255
                            inputBuffer[gOff + idx] = ((px shr  8) and 0xFF) * inv255
                            inputBuffer[bOff + idx] = ( px         and 0xFF) * inv255
                        }
                    }
                }
            }
            270 -> { // 270° CW: sx = cropW - 1 - ry, sy = rx
                for (y in 0 until INPUT_SIZE) {
                    val srcY = y - padTop
                    val rowIsPad = srcY < 0 || srcY >= newH
                    val ry = if (rowIsPad) 0 else (srcY * rotH / newH).coerceIn(0, rotH - 1)
                    val rowBase = y * INPUT_SIZE
                    val sx = (cropW - 1 - ry).coerceIn(0, cropW - 1)
                    for (x in 0 until INPUT_SIZE) {
                        val srcX = x - padLeft
                        val idx = rowBase + x
                        if (rowIsPad || srcX < 0 || srcX >= newW) {
                            inputBuffer[rOff + idx] = padVal
                            inputBuffer[gOff + idx] = padVal
                            inputBuffer[bOff + idx] = padVal
                        } else {
                            val rx = (srcX * rotW / newW).coerceIn(0, rotW - 1)
                            val sy = rx.coerceIn(0, cropH - 1)
                            val px = srcPixels[sy * cropW + sx]
                            inputBuffer[rOff + idx] = ((px shr 16) and 0xFF) * inv255
                            inputBuffer[gOff + idx] = ((px shr  8) and 0xFF) * inv255
                            inputBuffer[bOff + idx] = ( px         and 0xFF) * inv255
                        }
                    }
                }
            }
        }

        // 3. ONNX Inference
        val shape  = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        val tensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(inputBuffer), shape)
        val t0 = System.nanoTime()
        val result = ortSession!!.run(mapOf("images" to tensor))
        val inferMs = (System.nanoTime() - t0) / 1_000_000f
        tensor.close()

        if (++frameCounter % 15 == 0) {
            Log.d(TAG, "rot=$angle° inference: ${"%.1f".format(inferMs)}ms")
        }

        // 4. Parse output and map bounding boxes back to unrotated full image coordinates
        val raw       = (result[0].value as Array<*>)[0] as Array<*>
        val numClass  = CLASS_NAMES.size
        val numAnch   = (raw[4] as FloatArray).size
        val dets      = mutableListOf<Detection>()
        val cropLeftF = cropLeft.toFloat()
        val cropTopF  = cropTop.toFloat()
        val padLeftF  = padLeft.toFloat()
        val padTopF   = padTop.toFloat()
        val cropWF    = cropW.toFloat()
        val cropHF    = cropH.toFloat()

        for (a in 0 until numAnch) {
            var bestScore = -1f
            var bestCls   = 0
            for (c in 0 until numClass) {
                val s = (raw[4 + c] as FloatArray)[a]
                if (s > bestScore) { bestScore = s; bestCls = c }
            }

            if (bestScore < CLASS_THRESHOLDS[bestCls]) continue

            val cx = (raw[0] as FloatArray)[a]
            val cy = (raw[1] as FloatArray)[a]
            val w  = (raw[2] as FloatArray)[a]
            val h  = (raw[3] as FloatArray)[a]

            // Unletterbox to rotated crop coordinates (rx1, ry1, rx2, ry2)
            val rx1 = (((cx - w * 0.5f) - padLeftF) / scale).coerceIn(0f, rotW.toFloat())
            val ry1 = (((cy - h * 0.5f) - padTopF)  / scale).coerceIn(0f, rotH.toFloat())
            val rx2 = (((cx + w * 0.5f) - padLeftF) / scale).coerceIn(0f, rotW.toFloat())
            val ry2 = (((cy + h * 0.5f) - padTopF)  / scale).coerceIn(0f, rotH.toFloat())

            // Inverse box transformation from rotated crop to unrotated crop coordinates
            val x1: Float
            val y1: Float
            val x2: Float
            val y2: Float
            when (angle) {
                0 -> {
                    x1 = rx1
                    y1 = ry1
                    x2 = rx2
                    y2 = ry2
                }
                90 -> {
                    x1 = ry1
                    y1 = cropHF - rx2
                    x2 = ry2
                    y2 = cropHF - rx1
                }
                180 -> {
                    x1 = cropWF - rx2
                    y1 = cropHF - ry2
                    x2 = cropWF - rx1
                    y2 = cropHF - ry1
                }
                270 -> {
                    x1 = cropWF - ry2
                    y1 = rx1
                    x2 = cropWF - ry1
                    y2 = rx2
                }
                else -> {
                    x1 = rx1; y1 = ry1; x2 = rx2; y2 = ry2
                }
            }

            dets.add(Detection(
                rect = RectF(
                    x1.coerceIn(0f, cropWF) + cropLeftF,
                    y1.coerceIn(0f, cropHF) + cropTopF,
                    x2.coerceIn(0f, cropWF) + cropLeftF,
                    y2.coerceIn(0f, cropHF) + cropTopF
                ),
                classIndex = bestCls,
                className  = CLASS_NAMES[bestCls],
                confidence = bestScore
            ))
        }

        result.close()
        return dets
    }

    private fun nms(dets: List<Detection>): List<Detection> {
        if (dets.isEmpty()) return emptyList()
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
