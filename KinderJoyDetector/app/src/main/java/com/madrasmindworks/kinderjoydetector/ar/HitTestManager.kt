package com.madrasmindworks.kinderjoydetector.ar

import android.graphics.PointF
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import com.google.ar.core.Plane
import com.google.ar.core.Point

/**
 * Wraps ARCore's hitTest() raycast so the rest of the app only has to think
 * in terms of "screen point in → world Pose out". Per the integration plan
 * we do NOT use image tracking/augmented images — this is pure motion
 * tracking + plane/depth hit-testing, which is what lets any toy (not a
 * pre-registered image target) get anchored.
 */
class HitTestManager {

    /**
     * Hit-tests at [screenPoint] (in the ARCore-camera-image pixel space,
     * scaled to the ArSceneView's viewport by the caller — see
     * ArSessionManager.imagePointToViewport()). Prefers a tracked
     * plane/depth-point hit closest to the camera; falls back to any hit if
     * no plane exists yet (e.g. a blank wall or table not yet mapped) so the
     * character can still be placed rather than silently failing.
     *
     * Returns null only if ARCore genuinely has nothing to hit (tracking
     * lost, or camera pointed at open space with no depth data at all).
     */
    fun hitTest(frame: Frame, screenPoint: PointF): Pose? {
        if (frame.camera.trackingState != TrackingState.TRACKING) return null

        val results = try {
            frame.hitTest(screenPoint.x, screenPoint.y)
        } catch (e: Exception) {
            return null
        }

        // Prefer a hit against an actual tracked plane (most stable surface
        // to lock onto), then a depth "Point" hit, then just take the closest
        // result of any kind so we degrade gracefully instead of refusing to
        // anchor at all.
        val planeHit = results.firstOrNull { hit ->
            val trackable = hit.trackable
            trackable is Plane && trackable.isPoseInPolygon(hit.hitPose) &&
                trackable.trackingState == TrackingState.TRACKING
        }
        if (planeHit != null) return planeHit.hitPose

        val pointHit = results.firstOrNull { hit ->
            val trackable = hit.trackable
            trackable is Point && trackable.trackingState == TrackingState.TRACKING
        }
        if (pointHit != null) return pointHit.hitPose

        return results.firstOrNull()?.hitPose
    }
}
