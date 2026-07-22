package com.madrasmindworks.kinderjoydetector

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * Transparent overlay matching PreviewView scaleType="fitCenter".
 * Uses the SAME scale math as the preview so boxes land exactly on the toy.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var detections: List<YoloDetector.Detection> = emptyList()
    private var frameWidth  = 1
    private var frameHeight = 1

    // ── Paints ────────────────────────────────────────────────────────────────

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        strokeWidth = 6f
        strokeJoin  = Paint.Join.ROUND
        strokeCap   = Paint.Cap.ROUND
    }

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        strokeWidth = 10f
        strokeCap   = Paint.Cap.SQUARE
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color    = Color.WHITE
        textSize = 40f
        typeface = Typeface.DEFAULT_BOLD
    }

    private val confPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color    = Color.argb(230, 255, 255, 255)
        textSize = 30f
        typeface = Typeface.MONOSPACE
    }

    fun setDetections(dets: List<YoloDetector.Detection>, srcWidth: Int, srcHeight: Int) {
        detections  = dets
        frameWidth  = srcWidth
        frameHeight = srcHeight
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (detections.isEmpty()) return

        // ── fitCenter math (matches PreviewView scaleType="fitCenter") ─────────
        // Scale uniformly so the full frame fits inside the view
        val scaleX   = width.toFloat()  / frameWidth
        val scaleY   = height.toFloat() / frameHeight
        val scale    = minOf(scaleX, scaleY)           // ← fitCenter uses MIN
        val offsetX  = (width  - frameWidth  * scale) / 2f
        val offsetY  = (height - frameHeight * scale) / 2f

        for (det in detections) {
            val color = YoloDetector.CLASS_COLORS[det.classIndex]
            val alpha = (det.confidence * 255).toInt().coerceIn(180, 255)

            // Map detection rect → view coords
            val l = det.rect.left   * scale + offsetX
            val t = det.rect.top    * scale + offsetY
            val r = det.rect.right  * scale + offsetX
            val b = det.rect.bottom * scale + offsetY
            val boxW = r - l
            val boxH = b - t

            // Dim filled rect inside box
            bgPaint.color = Color.argb(40, Color.red(color), Color.green(color), Color.blue(color))
            canvas.drawRect(l, t, r, b, bgPaint)

            // Main box stroke
            boxPaint.color = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
            canvas.drawRect(l, t, r, b, boxPaint)

            // Corner accent lines (top-left, top-right, bottom-left, bottom-right)
            cornerPaint.color = color
            val cs = minOf(boxW, boxH) * 0.18f   // corner segment length
            // TL
            canvas.drawLine(l, t, l + cs, t, cornerPaint)
            canvas.drawLine(l, t, l, t + cs, cornerPaint)
            // TR
            canvas.drawLine(r, t, r - cs, t, cornerPaint)
            canvas.drawLine(r, t, r, t + cs, cornerPaint)
            // BL
            canvas.drawLine(l, b, l + cs, b, cornerPaint)
            canvas.drawLine(l, b, l, b - cs, cornerPaint)
            // BR
            canvas.drawLine(r, b, r - cs, b, cornerPaint)
            canvas.drawLine(r, b, r, b - cs, cornerPaint)

            // Label badge
            val label  = det.className
            val conf   = "%.0f%%".format(det.confidence * 100)
            val textW  = labelPaint.measureText(label)
            val confW  = confPaint.measureText(conf)
            val badgeW = maxOf(textW, confW) + 20f
            val badgeH = 76f

            val bx1 = l.coerceAtLeast(0f)
            val by1 = (t - badgeH).coerceAtLeast(0f)
            val bx2 = (bx1 + badgeW).coerceAtMost(width.toFloat())

            // Badge background
            bgPaint.color = Color.argb(220, Color.red(color), Color.green(color), Color.blue(color))
            canvas.drawRoundRect(bx1, by1, bx2, by1 + badgeH, 10f, 10f, bgPaint)

            canvas.drawText(label, bx1 + 8f, by1 + 42f, labelPaint)
            canvas.drawText(conf,  bx1 + 8f, by1 + 70f, confPaint)
        }
    }
}
