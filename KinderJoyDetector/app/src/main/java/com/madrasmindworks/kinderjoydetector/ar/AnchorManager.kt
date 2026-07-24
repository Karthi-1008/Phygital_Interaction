package com.madrasmindworks.kinderjoydetector.ar

import android.util.Log
import com.google.ar.core.Anchor
import com.google.ar.core.Pose
import com.google.ar.core.Session
import kotlin.math.sqrt

/**
 * Owns exactly one live [Anchor] at a time (one per currently-tracked toy —
 * this app anchors a single toy at once, matching the existing single-target
 * detection flow). Implements the plan's anchor rules explicitly:
 *
 *  - Anchors are created ONLY when [DetectionStabilizer] reports [Stable] —
 *    never recreated every frame.
 *  - A new candidate pose within [MOVE_THRESHOLD_M] of the current anchor is
 *    ignored entirely (this is what prevents flicker from a few centimeters
 *    of natural hand-shake).
 *  - A candidate pose beyond the threshold triggers a full recreate (ARCore
 *    anchors are immutable in position — you cannot move one in place, so
 *    "smoothly move" is implemented as detach-old / attach-new; the GLB node
 *    itself can still tween its local position for a smooth visual instead
 *    of an instant jump — see SceneRenderer).
 */
class AnchorManager {

    companion object {
        private const val TAG = "AnchorManager"
        private const val MOVE_THRESHOLD_M = 0.10f   // 10 cm, per the plan
    }

    private var currentAnchor: Anchor? = null
    private var currentClassIndex: Int? = null

    val anchor: Anchor? get() = currentAnchor

    /**
     * Call when [DetectionStabilizer] is in the Stable state and a hit-test
     * pose is available. Returns true if the anchor changed (created OR
     * recreated) — callers use that to know whether they need to re-parent
     * the rendered model node.
     */
    fun onStableDetection(session: Session, classIndex: Int, pose: Pose): Boolean {
        val existing = currentAnchor

        if (existing == null || currentClassIndex != classIndex) {
            // Nothing anchored yet, or the tracked toy changed identity —
            // this is a genuine new anchor, not a flicker.
            recreate(session, classIndex, pose)
            return true
        }

        val distance = distanceMeters(existing.pose, pose)
        if (distance < MOVE_THRESHOLD_M) {
            // Within noise threshold — ignore per the plan, no flicker.
            return false
        }

        Log.i(TAG, "Toy moved ${"%.2f".format(distance)}m — recreating anchor")
        recreate(session, classIndex, pose)
        return true
    }

    /** Called when DetectionStabilizer's grace period fully expires (toy really gone). */
    fun clear() {
        currentAnchor?.detach()
        currentAnchor = null
        currentClassIndex = null
    }

    private fun recreate(session: Session, classIndex: Int, pose: Pose) {
        currentAnchor?.detach()
        currentAnchor = try {
            session.createAnchor(pose)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create anchor", e)
            null
        }
        currentClassIndex = classIndex
    }

    private fun distanceMeters(a: Pose, b: Pose): Float {
        val dx = a.tx() - b.tx()
        val dy = a.ty() - b.ty()
        val dz = a.tz() - b.tz()
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
