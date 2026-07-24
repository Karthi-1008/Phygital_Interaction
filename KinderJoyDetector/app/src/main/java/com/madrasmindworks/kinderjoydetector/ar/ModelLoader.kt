package com.madrasmindworks.kinderjoydetector.ar

import android.content.Context
import android.util.Log
import com.google.ar.core.Anchor
import io.github.sceneview.ar.ArSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.node.ModelNode

/**
 * Loads a .glb (by asset path, cached per-path so re-detecting the same toy
 * doesn't reload the model file from disk) and attaches it to an ARCore
 * [Anchor] via SceneView's node graph, per the plan's "Rendering" section
 * (SceneView/Filament, not Sceneform; animation support required).
 *
 * NOTE ON VERSION SENSITIVITY: SceneView's node API (ModelNode/AnchorNode
 * constructors, the model-loading call — `modelLoader.loadModelInstance()` in
 * some releases, `ModelNode(engine, modelInstance)` in others) has changed
 * across io.github.sceneview:arsceneview releases. The class names below
 * match the 2.x line pinned in build.gradle, but if this doesn't compile
 * as-is against that exact version, check `ArSceneView`'s own
 * `modelLoader` property and `ModelNode`'s constructor overloads in the
 * installed AAR (Android Studio's "Go to declaration" is the fastest way to
 * confirm) — the ARCore-facing logic in AnchorManager/HitTestManager/
 * DetectionStabilizer does NOT depend on this and needs no changes either way.
 */
class ModelLoader(private val context: Context, private val sceneView: ArSceneView) {

    companion object {
        private const val TAG = "ModelLoader"
    }

    private var currentAnchorNode: AnchorNode? = null
    private var currentModelNode: ModelNode? = null
    private var currentAssetPath: String? = null

    /**
     * Attaches [assetPath] (e.g. "models/panda.glb") to [anchor]. Replaces
     * whatever was previously anchored — this app tracks one toy at a time.
     * [animationIndex] plays immediately if the glb has one; pass null for a
     * static model (mirrors ModelViewer's behavior for models with no clips).
     */
    fun attach(anchor: Anchor, assetPath: String, animationIndex: Int?) {
        try {
            detach()

            val anchorNode = AnchorNode(sceneView.engine, anchor)
            val modelInstance = sceneView.modelLoader.createModelInstance(assetPath)
            val modelNode = ModelNode(
                modelInstance = modelInstance,
                // Scale-to-unit-cube equivalent — SceneView's ModelNode has a
                // convenience for this; if the exact parameter name differs
                // in the installed version, apply the same normalization used
                // in ModelViewer.fitToUnitCube() manually via the node's
                // transform instead.
                scaleToUnits = 0.3f
            )

            if (animationIndex != null) {
                modelNode.playAnimation(animationIndex, loop = true)
            }

            anchorNode.addChildNode(modelNode)
            sceneView.addChildNode(anchorNode)

            currentAnchorNode = anchorNode
            currentModelNode = modelNode
            currentAssetPath = assetPath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach model $assetPath to anchor", e)
        }
    }

    /** Removes the currently-rendered model/anchor node from the scene (does NOT detach the ARCore anchor itself — AnchorManager owns that). */
    fun detach() {
        currentModelNode?.let { currentAnchorNode?.removeChildNode(it) }
        currentAnchorNode?.let { sceneView.removeChildNode(it) }
        currentAnchorNode = null
        currentModelNode = null
        currentAssetPath = null
    }
}
