package com.madrasmindworks.kinderjoydetector.ar

import android.util.Log
import com.google.ar.core.Anchor
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.node.ModelNode

/**
 * Manages ARCore Anchor & AnchorNode lifecycles.
 * Executes single hit-test ONLY when a toy is confirmed, then lets ARCore maintain 6-DoF pose tracking.
 */
class AnchorManager {

    companion object {
        private const val TAG = "AnchorManager"
        private const val TIMEOUT_MS = 800L
    }

    var activeAnchorNode: AnchorNode? = null
        private set
    var activeModelNode: ModelNode? = null
        private set
    var activeClassIndex: Int = -1
        private set

    private var lastSeenTimeMs: Long = System.currentTimeMillis()

    fun hasValidAnchor(classIndex: Int): Boolean {
        val anchorNode = activeAnchorNode ?: return false
        val anchor = anchorNode.anchor ?: return false

        if (activeClassIndex != classIndex) return false
        if (anchor.trackingState != TrackingState.TRACKING) return false

        val elapsed = System.currentTimeMillis() - lastSeenTimeMs
        if (elapsed > TIMEOUT_MS) return false

        return true
    }

    fun updateLastSeen() {
        lastSeenTimeMs = System.currentTimeMillis()
    }

    fun createAnchorAndAttachModel(
        sceneView: ARSceneView,
        classIndex: Int,
        centerX: Float,
        centerY: Float,
        modelInstance: ModelInstance
    ): Boolean {
        // Clear previous anchor if toy class changed or tracking lost
        clearAnchor(sceneView)

        val frame: Frame = sceneView.currentFrame ?: sceneView.arSession?.currentFrame ?: run {
            Log.w(TAG, "[AR-DIAG] Cannot perform hit test: ARFrame is null")
            return false
        }

        val hitResults = frame.hitTest(centerX, centerY)
        val validHit = hitResults.firstOrNull { hit ->
            val trackable = hit.trackable
            hit.isHitInFront && (
                (trackable is Plane && trackable.isPoseInPolygon(hit.hitPose)) ||
                trackable?.trackingState == TrackingState.TRACKING
            )
        } ?: hitResults.firstOrNull()

        if (validHit == null) {
            Log.w(TAG, "[AR-DIAG] Single Hit Test returned no valid surface at ($centerX, $centerY)")
            return false
        }

        val anchor = validHit.createAnchor()
        val anchorNode = AnchorNode(engine = sceneView.engine, anchor = anchor)
        val modelNode = ModelNode(
            modelInstance = modelInstance,
            scaleToUnits = 0.4f
        )

        modelNode.parent = anchorNode
        anchorNode.parent = sceneView

        activeAnchorNode = anchorNode
        activeModelNode = modelNode
        activeClassIndex = classIndex
        lastSeenTimeMs = System.currentTimeMillis()

        Log.i(TAG, "[AR-DIAG] Single ARCore Anchor created successfully for toy class $classIndex at hitPose: ${validHit.hitPose}")
        return true
    }

    fun clearAnchor(sceneView: ARSceneView) {
        activeModelNode?.let { model ->
            model.parent = null
            model.destroy()
            activeModelNode = null
        }
        activeAnchorNode?.let { anchorNode ->
            anchorNode.parent = null
            anchorNode.destroy()
            activeAnchorNode = null
        }
        activeClassIndex = -1
        Log.d(TAG, "[AR-DIAG] ARCore Anchor cleared")
    }
}
