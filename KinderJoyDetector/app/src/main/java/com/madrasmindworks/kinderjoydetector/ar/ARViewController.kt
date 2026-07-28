package com.madrasmindworks.kinderjoydetector.ar

import android.graphics.Color
import android.graphics.PixelFormat
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.SceneView
import io.github.sceneview.node.ModelNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manages SceneView 3D overlay lifecycle, GLB model preloading, animation playback,
 * and 3D world node positioning.
 */
class ARViewController(
    private val sceneView: SceneView,
    private val lifecycleOwner: LifecycleOwner,
    private val coroutineScope: CoroutineScope
) {
    companion object {
        private const val TAG = "ARViewController"
        val TOY_GLB_PATHS = mapOf(
            0 to "models/harry_potter.glb",
            1 to "models/hermione.glb",
            2 to "models/batman.glb",
            3 to "models/flash.glb"
        )
    }

    private var modelNode: ModelNode? = null
    private var isModelLoaded = false
    private var isVisible = false
    private var currentClassIndex = -1

    fun init() {
        sceneView.apply {
            setZOrderOnTop(true)
            holder.setFormat(PixelFormat.TRANSLUCENT)
            setBackgroundColor(Color.TRANSPARENT)
        }
    }

    fun loadAndShowModel(classIndex: Int, worldPosition: Float3, scale: Float) {
        if (currentClassIndex != classIndex) {
            destroyModel()
            currentClassIndex = classIndex
            loadModel(classIndex, worldPosition, scale)
        } else {
            showModel(worldPosition, scale)
        }
    }

    private fun loadModel(classIndex: Int, initialPosition: Float3, scale: Float) {
        val glbPath = TOY_GLB_PATHS[classIndex] ?: "models/flash.glb"
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val modelInstance = sceneView.modelLoader.createModelInstance(assetFileLocation = glbPath)
                withContext(Dispatchers.Main) {
                    if (modelInstance != null) {
                        val node = ModelNode(modelInstance = modelInstance, scaleToUnits = scale)
                        node.worldPosition = initialPosition
                        node.isVisible = true

                        sceneView.addChildNode(node)
                        modelNode = node
                        isModelLoaded = true
                        isVisible = true

                        playAnimation()
                        Log.i(TAG, "GLB Model loaded & visible for class $classIndex ($glbPath)")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "GLB load failed for class $classIndex: ${e.message}", e)
            }
        }
    }

    fun showModel(worldPosition: Float3, scale: Float) {
        val node = modelNode ?: return
        if (!isModelLoaded) return

        node.worldPosition = worldPosition
        node.scale = Float3(scale, scale, scale)

        if (!isVisible) {
            node.isVisible = true
            playAnimation()
            isVisible = true
        }
    }

    fun hideModel() {
        if (!isVisible) return
        modelNode?.isVisible = false
        stopAnimation()
        isVisible = false
    }

    private fun playAnimation() {
        try {
            modelNode?.playAnimation(0)
        } catch (e: Exception) {
            Log.w(TAG, "Animation playback failed: ${e.message}")
        }
    }

    private fun stopAnimation() {
        try {
            modelNode?.stopAnimation(0)
        } catch (e: Exception) {
            Log.w(TAG, "Animation stop failed: ${e.message}")
        }
    }

    fun destroyModel() {
        modelNode?.let { node ->
            sceneView.removeChildNode(node)
            node.destroy()
        }
        modelNode = null
        isModelLoaded = false
        isVisible = false
        currentClassIndex = -1
    }

    fun destroy() {
        destroyModel()
    }
}
