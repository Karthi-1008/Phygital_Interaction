package com.madrasmindworks.kinderjoydetector.ar

import android.graphics.PointF

/**
 * Temporal filter sitting between raw per-frame YOLO output and anchor
 * creation, per the integration plan's "Stability Filter" section:
 *
 *  - Only detections with confidence >= [CONFIDENCE_THRESHOLD] count at all.
 *  - A detection must be seen on the SAME class for [STABLE_FRAMES_REQUIRED]
 *    consecutive frames before it's considered "stable" (ready to anchor).
 *  - The bounding-box center is exponentially smoothed across frames (not a
 *    plain average) to kill jitter without adding a full moving-window buffer.
 *  - If the toy disappears for less than [LOST_GRACE_MS], state is preserved
 *    (the anchor/model should stay exactly as-is) — only after the grace
 *    period elapses does the caller get told the toy is really gone.
 *
 * This class does no ARCore/rendering work itself — it's a pure state
 * machine over (classIndex, confidence, bbox center) inputs, which keeps it
 * unit-testable and independent of the AR stack.
 */
class DetectionStabilizer(
    private val confidenceThreshold: Float = 0.85f,
    private val stableFramesRequired: Int = 5,
    private val lostGraceMs: Long = 2000L,
    /** Exponential smoothing factor for the bbox-center; lower = smoother/laggier. */
    private val smoothingAlpha: Float = 0.35f
) {

    sealed class State {
        /** No toy currently tracked (either never seen, or lost beyond the grace period). */
        object Idle : State()

        /** Seeing a toy, but not yet [stableFramesRequired] consecutive frames — no anchor yet. */
        data class Accumulating(val classIndex: Int, val framesSeen: Int) : State()

        /** Stable for long enough — caller should create/refresh an anchor at [smoothedCenter]. */
        data class Stable(val classIndex: Int, val smoothedCenter: PointF) : State()

        /** Was stable, toy not seen this frame, but still within the grace window — keep rendering. */
        data class GracePeriod(val classIndex: Int, val smoothedCenter: PointF, val lostAtMs: Long) : State()
    }

    var state: State = State.Idle
        private set

    private var smoothedX = 0f
    private var smoothedY = 0f
    private var framesSeen = 0
    private var currentClass: Int? = null

    /**
     * Feed one frame's result. [center] is the bbox center in whatever pixel
     * space the caller does hit-testing against (e.g. ARCore camera-image
     * pixel coords). Pass classIndex=null / confidence=0 for "nothing detected
     * this frame".
     *
     * Returns the updated [state] for convenience (same as reading [state]
     * right after calling).
     */
    fun update(classIndex: Int?, confidence: Float, center: PointF?, nowMs: Long = System.currentTimeMillis()): State {
        val sawValidDetection = classIndex != null && confidence >= confidenceThreshold && center != null

        if (sawValidDetection) {
            val cls = classIndex!!
            if (cls != currentClass) {
                // Different class than whatever we were tracking — restart accumulation.
                currentClass = cls
                framesSeen = 1
                smoothedX = center!!.x
                smoothedY = center.y
            } else {
                framesSeen++
                // Exponential smoothing, not a plain running average — reacts
                // to real movement while still damping frame-to-frame jitter.
                smoothedX += smoothingAlpha * (center!!.x - smoothedX)
                smoothedY += smoothingAlpha * (center.y - smoothedY)
            }

            state = if (framesSeen >= stableFramesRequired) {
                State.Stable(cls, PointF(smoothedX, smoothedY))
            } else {
                State.Accumulating(cls, framesSeen)
            }
            return state
        }

        // No valid detection this frame.
        return when (val s = state) {
            is State.Stable -> {
                // Just lost it — enter the grace window instead of dropping immediately.
                state = State.GracePeriod(s.classIndex, s.smoothedCenter, nowMs)
                state
            }
            is State.GracePeriod -> {
                if (nowMs - s.lostAtMs < lostGraceMs) {
                    state // still within grace — keep the model up, don't touch anchor
                } else {
                    reset()
                    state
                }
            }
            is State.Accumulating -> {
                // Wasn't stable yet and we dropped it — no grace period applies
                // pre-anchor, just start over next time it's seen.
                reset()
                state
            }
            State.Idle -> state
        }
    }

    fun reset() {
        state = State.Idle
        currentClass = null
        framesSeen = 0
    }
}
