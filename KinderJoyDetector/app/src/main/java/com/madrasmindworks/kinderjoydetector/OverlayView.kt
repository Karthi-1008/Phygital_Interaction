package com.madrasmindworks.kinderjoydetector

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * Draws ONLY what the user needs to guide detection:
 *  1. A fixed guide box (in FRAME pixel coords — same source of truth used
 *     by MainActivity to crop the bitmap fed to the detector).
 *  2. A progress ring around that box that fills as a valid toy is held
 *     inside it, so the user gets clear "hold steady" feedback.
 *
 * No per-detection debug boxes, corner accents, label badges, or FPS text —
 * kept deliberately minimal per the "no extra visualization" requirement.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var frameWidth  = 1
    private var frameHeight = 1
    private var guideBoxFrame: RectF? = null

    /** 0f..1f — how "full" the progress ring is. */
    private var progress = 0f

    /** Whether a valid toy is currently held inside the box (tints the box green). */
    private var isMatching = false

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap   = Paint.Cap.ROUND
        color       = Color.WHITE
    }

    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(90, 0, 0, 0)
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        strokeWidth = 12f
        strokeCap   = Paint.Cap.ROUND
        color       = Color.parseColor("#00E676")
    }

    private val progressBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        strokeWidth = 12f
        color       = Color.argb(70, 255, 255, 255)
    }

    private val cutoutPath = Path()
    private val fullPath   = Path()

    /** Called once per processed frame to update source-frame size + guide box (frame coords). */
    fun setFrameGeometry(srcWidth: Int, srcHeight: Int, guideBox: RectF) {
        frameWidth   = srcWidth
        frameHeight  = srcHeight
        guideBoxFrame = guideBox
        postInvalidate()
    }

    fun setProgress(value: Float, matching: Boolean) {
        progress    = value.coerceIn(0f, 1f)
        isMatching  = matching
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val box = guideBoxFrame ?: return

        // fitCenter math — identical to PreviewView scaleType="fitCenter" so
        // the box drawn here always lines up with the camera preview.
        val scaleX  = width.toFloat()  / frameWidth
        val scaleY  = height.toFloat() / frameHeight
        val scale   = minOf(scaleX, scaleY)
        val offsetX = (width  - frameWidth  * scale) / 2f
        val offsetY = (height - frameHeight * scale) / 2f

        val l = box.left   * scale + offsetX
        val t = box.top    * scale + offsetY
        val r = box.right  * scale + offsetX
        val b = box.bottom * scale + offsetY
        val corner = 28f

        // Dim everything outside the box so attention is drawn to it
        fullPath.reset()
        fullPath.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        cutoutPath.reset()
        cutoutPath.addRoundRect(l, t, r, b, corner, corner, Path.Direction.CW)
        fullPath.op(cutoutPath, Path.Op.DIFFERENCE)
        canvas.drawPath(fullPath, dimPaint)

        // Guide box outline — green while a valid toy is held inside it
        boxPaint.color = if (isMatching) Color.parseColor("#00E676") else Color.WHITE
        canvas.drawRoundRect(l, t, r, b, corner, corner, boxPaint)

        // Progress ring traced along the box perimeter
        if (progress > 0f) {
            val perimeterPath = Path().apply { addRoundRect(l, t, r, b, corner, corner, Path.Direction.CW) }
            canvas.drawPath(perimeterPath, progressBgPaint)

            val measure = PathMeasure(perimeterPath, true)
            val segment = Path()
            measure.getSegment(0f, measure.length * progress, segment, true)
            canvas.drawPath(segment, progressPaint)
        }
    }
}
