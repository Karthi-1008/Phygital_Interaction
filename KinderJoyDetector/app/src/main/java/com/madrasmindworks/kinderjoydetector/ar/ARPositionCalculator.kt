package com.madrasmindworks.kinderjoydetector.ar

import android.graphics.RectF
import dev.romainguy.kotlin.math.Float3

/**
 * Pure calculation class — converts 2D detection rect to SceneView 3D world position (Float3)
 * using NDC projection and FOV perspective depth.
 */
object ARPositionCalculator {
    const val ANALYSIS_WIDTH = 640
    const val ANALYSIS_HEIGHT = 480
    const val PLACEMENT_DEPTH = 2.0f // metres in front of camera
    const val CAMERA_FOV_DEG = 60f

    fun toWorldPosition(
        rect: RectF,
        analysisWidth: Int = ANALYSIS_WIDTH,
        analysisHeight: Int = ANALYSIS_HEIGHT,
        placementDepthMeters: Float = PLACEMENT_DEPTH
    ): Float3 {
        val nx = (rect.left + rect.width() / 2f) / analysisWidth
        val ny = 1f - (rect.top + rect.height() / 2f) / analysisHeight

        val ndcX = nx * 2f - 1f
        val ndcY = ny * 2f - 1f

        val halfFovRad = Math.toRadians(30.0).toFloat()
        val worldX = ndcX * placementDepthMeters * kotlin.math.tan(halfFovRad)
        val worldY = ndcY * placementDepthMeters * kotlin.math.tan(halfFovRad) * 0.75f
        val worldZ = -placementDepthMeters

        return Float3(worldX, worldY, worldZ)
    }

    fun toModelScale(rect: RectF, baseScale: Float = 0.4f): Float {
        val normalizedArea = (rect.width() * rect.height()) / (ANALYSIS_WIDTH * ANALYSIS_HEIGHT).toFloat()
        return baseScale * (0.5f + normalizedArea * 5f).coerceIn(0.2f, 2.0f)
    }
}
