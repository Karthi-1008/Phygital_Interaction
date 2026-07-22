package com.madrasmindworks.kinderjoydetector

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * Transparent overlay drawn on top of PreviewView.
 * Renders bounding boxes + labels for each Detection.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var detections: List<YoloDetector.Detection> = emptyList()

    // The original frame size the detections were computed on
    private var frameWidth  = 1
    private var frameHeight = 1

    // ── Paints ────────────────────────────────────────────────────────────────

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 38f
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }

    private val confPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
        textSize = 28f
        typeface = Typeface.MONOSPACE
    }

    // Corner radius for label badge
    private val cornerRadius = 12f

    /** Update detections — call from background thread safe via post(). */
    fun setDetections(
        dets: List<YoloDetector.Detection>,
        srcWidth: Int,
        srcHeight: Int
    ) {
        detections  = dets
        frameWidth  = srcWidth
        frameHeight = srcHeight
        // Trigger a redraw on UI thread
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (detections.isEmpty()) return

        // Scale factor from frame coords → view coords (fit + letterbox like PreviewView FILL_CENTER)
        val scaleX = width.toFloat()  / frameWidth
        val scaleY = height.toFloat() / frameHeight

        // Use the smaller scale to maintain aspect ratio (mirrors PreviewView FIT_CENTER behaviour)
        val scale = minOf(scaleX, scaleY)
        val offsetX = (width  - frameWidth  * scale) / 2f
        val offsetY = (height - frameHeight * scale) / 2f

        for (det in detections) {
            val color = YoloDetector.CLASS_COLORS[det.classIndex]

            // Map rect to view coordinates
            val left   = det.rect.left   * scale + offsetX
            val top    = det.rect.top    * scale + offsetY
            val right  = det.rect.right  * scale + offsetX
            val bottom = det.rect.bottom * scale + offsetY

            // Bounding box
            boxPaint.color = color
            canvas.drawRoundRect(left, top, right, bottom, 8f, 8f, boxPaint)

            // Label text
            val label = det.className
            val conf  = "${(det.confidence * 100).toInt()}%"

            val textW  = textPaint.measureText(label)
            val confW  = confPaint.measureText(conf)
            val badgeW = maxOf(textW, confW) + 24f
            val badgeH = 78f

            // Badge background (clamp so it doesn't go off-screen)
            val badgeLeft  = left.coerceAtLeast(0f)
            val badgeTop   = (top - badgeH).coerceAtLeast(0f)
            val badgeRight = (badgeLeft + badgeW).coerceAtMost(width.toFloat())

            bgPaint.color = color
            canvas.drawRoundRect(
                badgeLeft, badgeTop,
                badgeRight, badgeTop + badgeH,
                cornerRadius, cornerRadius,
                bgPaint
            )

            // Class name
            canvas.drawText(label, badgeLeft + 8f, badgeTop + 40f, textPaint)
            // Confidence
            canvas.drawText(conf,  badgeLeft + 8f, badgeTop + 68f, confPaint)
        }
    }
}
