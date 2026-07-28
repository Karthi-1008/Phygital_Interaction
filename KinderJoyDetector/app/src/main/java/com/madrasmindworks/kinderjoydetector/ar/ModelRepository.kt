package com.madrasmindworks.kinderjoydetector.ar

import android.content.Context
import android.util.Log
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.model.ModelInstance

/**
 * Caches loaded GLB ModelInstance objects by class index to guarantee
 * models are loaded from disk ONCE and never re-allocated inside rendering loops.
 */
class ModelRepository(private val context: Context) {

    companion object {
        private const val TAG = "ModelRepository"
        val TOY_GLB_PATHS = mapOf(
            0 to "models/harry_potter.glb",
            1 to "models/hermione.glb",
            2 to "models/batman.glb",
            3 to "models/flash.glb"
        )
    }

    private val modelInstanceCache = mutableMapOf<Int, ModelInstance>()

    suspend fun getModelInstance(sceneView: ARSceneView, classIndex: Int): ModelInstance? {
        modelInstanceCache[classIndex]?.let { return it }

        val glbPath = TOY_GLB_PATHS[classIndex] ?: return null
        return try {
            Log.d(TAG, "[AR-DIAG] Preloading GLB model instance for class $classIndex ($glbPath)...")
            val instance = sceneView.modelLoader.createModelInstance(assetFileLocation = glbPath)
            if (instance != null) {
                modelInstanceCache[classIndex] = instance
                Log.i(TAG, "[AR-DIAG] GLB model instance cached successfully for class $classIndex")
            }
            instance
        } catch (e: Exception) {
            Log.e(TAG, "[AR-DIAG] Failed to load GLB model for class $classIndex: $glbPath", e)
            null
        }
    }
}
