package com.madrasmindworks.kinderjoydetector.ar

import android.content.Context
import android.util.Log
import com.google.ar.core.ArCoreApk
import io.github.sceneview.ar.ARSceneView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * High-level AR Manager encapsulating SceneView, ARCore session lifecycle,
 * model loading, and spatial anchor tracking.
 */
class ArManager(private val context: Context) {

    companion object {
        private const val TAG = "ArManager"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val modelRepository = ModelRepository(context)
    private val anchorManager = AnchorManager()

    private var sceneView: ARSceneView? = null
    var isArSupported: Boolean = false
        private set

    fun checkArSupport(): Boolean {
        return try {
            val availability = ArCoreApk.getInstance().checkAvailability(context)
            isArSupported = availability.isSupported
            Log.i(TAG, "[AR-DIAG] ARCore Availability: $availability (Supported=$isArSupported)")
            isArSupported
        } catch (e: Exception) {
            isArSupported = false
            Log.e(TAG, "[AR-DIAG] ARCore availability check failed", e)
            false
        }
    }

    fun setup(arSceneView: ARSceneView) {
        this.sceneView = arSceneView
        try {
            arSceneView.planeRenderer.isEnabled = false
            Log.i(TAG, "[AR-DIAG] ARSceneView configured successfully")
        } catch (e: Exception) {
            Log.e(TAG, "[AR-DIAG] Failed to configure ARSceneView", e)
        }
    }

    fun onToyConfirmed(classIndex: Int, centerX: Float, centerY: Float) {
        val sv = sceneView ?: return
        if (!isArSupported) return

        if (anchorManager.hasValidAnchor(classIndex)) {
            anchorManager.updateLastSeen()
            return
        }

        scope.launch {
            val modelInstance = modelRepository.getModelInstance(sv, classIndex) ?: run {
                Log.w(TAG, "[AR-DIAG] ModelInstance null for class $classIndex")
                return@launch
            }

            withContext(Dispatchers.Main) {
                anchorManager.createAnchorAndAttachModel(
                    sceneView = sv,
                    classIndex = classIndex,
                    centerX = centerX,
                    centerY = centerY,
                    modelInstance = modelInstance
                )
            }
        }
    }

    fun clearArSession() {
        sceneView?.let { anchorManager.clearAnchor(it) }
    }
}
