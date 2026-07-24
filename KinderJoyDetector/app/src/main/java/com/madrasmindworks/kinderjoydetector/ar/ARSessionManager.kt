package com.madrasmindworks.kinderjoydetector.ar

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session

/**
 * Central place for the ARCore [Config] this app wants — kept separate from
 * whatever view class (ArSceneView) actually owns the [Session], so the
 * config policy is easy to read/change independently of the rendering layer.
 *
 * Per the integration plan:
 *  - Motion tracking: always on by default with any ARCore Session — nothing
 *    extra to enable.
 *  - Plane detection: HORIZONTAL_AND_VERTICAL, so a toy sitting on a table
 *    OR held up against a wall/vertical surface both hit-test successfully.
 *  - Light estimation: ENVIRONMENTAL_HDR — matches the plan's "Environmental
 *    HDR OR Light Estimation" requirement with the higher-fidelity option;
 *    this feeds Filament/SceneView's IBL so the rendered GLB is lit to match
 *    the real room instead of a fixed studio light rig.
 *  - Explicitly NOT configuring augmented images / cloud anchors — the plan
 *    is explicit that image tracking must never be used; YOLO is the only
 *    detector, ARCore is only used for tracking + hit-testing.
 */
object ARSessionManager {

    private const val TAG = "ARSessionManager"

    /**
     * Checks whether this device can run ARCore at all, and whether Google
     * Play Services for AR needs installing/updating first. Call this before
     * ever attempting to create a Session or show the AR view — on
     * INSTALL_REQUESTED, ARCore itself drives the install UI via
     * requestInstall() and your Activity should retry onResume().
     *
     * Returns false only for [ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE]
     * — every other state is either already fine or resolvable by the user.
     */
    fun isSupported(context: Context): Boolean {
        val availability = ArCoreApk.getInstance().checkAvailability(context)
        return availability != ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE
    }

    /**
     * Call from an Activity, typically at the top of onResume(), before
     * creating/resuming the ARCore-backed view. Returns true if a Session can
     * proceed right now; false if ARCore just launched its own install/update
     * flow (in which case bail out of AR setup this pass — onResume() will
     * run again after the user returns from that flow).
     */
    fun ensureInstalled(activity: Activity): Boolean {
        return try {
            val installStatus = ArCoreApk.getInstance().requestInstall(activity, /* userRequestedInstall = */ true)
            installStatus == ArCoreApk.InstallStatus.INSTALLED
        } catch (e: Exception) {
            Log.e(TAG, "ARCore install/availability check failed", e)
            false
        }
    }

    /**
     * Applies this app's tracking config to an existing [Session]. Wire this
     * into whichever hook your ArSceneView version exposes for session
     * configuration — in SceneView 2.x that's typically
     * `sceneView.configureSession { session, config -> ARSessionManager.configure(session, config) }`
     * (confirm the exact lambda signature against the SceneView version
     * pinned in build.gradle; this method's *contents* are plain ARCore API
     * and stable regardless of that wiring detail).
     */
    fun configure(session: Session, config: Config) {
        config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
        config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
        config.focusMode = Config.FocusMode.AUTO
        config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE

        // Explicitly NOT touching augmented-image DB or cloud anchor mode —
        // both stay at their ARCore defaults (disabled), per the plan.

        session.configure(config)
    }
}
