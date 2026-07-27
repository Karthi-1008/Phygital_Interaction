package com.madrasmindworks.kinderjoydetector

import android.graphics.PixelFormat
import android.graphics.RectF
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceView
import com.google.android.filament.*
import com.google.android.filament.android.DisplayHelper
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.*
import com.google.android.filament.utils.Float3
import com.google.android.filament.utils.Mat4
import java.nio.ByteBuffer
import kotlin.math.max

/**
 * Lightweight Filament 3D Viewer for AR overlay over CameraX preview.
 * Supports Android 8.0 (API 26) through Android 15 (API 35+).
 */
class ModelViewer(val surfaceView: SurfaceView) {

    companion object {
        private const val TAG = "ModelViewer"
    }

    private val choreographer = Choreographer.getInstance()
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            choreographer.postFrameCallback(this)
            render(frameTimeNanos)
        }
    }

    private var engine: Engine? = null
    private var scene: Scene? = null
    private var camera: Camera? = null
    private var view: View? = null
    private var renderer: Renderer? = null
    private var swapChain: SwapChain? = null
    private var uiHelper: UiHelper? = null
    private var displayHelper: DisplayHelper? = null

    private var assetLoader: AssetLoader? = null
    private var resourceLoader: ResourceLoader? = null
    private var loadedAsset: FilamentAsset? = null
    private var animator: Animator? = null

    private var lightEntity: Int = 0
    private var isFrameCallbackActive = false

    var isAvailable: Boolean = false
        private set
    var lastError: String? = null
        private set

    // Real-time transform state
    private var modelScale = 1.0f
    private var targetX = 0f
    private var targetY = 0f
    private var rotAngle = 0f

    init {
        setupFilament()
    }

    private fun setupFilament() {
        try {
            surfaceView.setZOrderOnTop(true)
            surfaceView.holder.setFormat(PixelFormat.TRANSLUCENT)

            engine = Engine.create()
            scene = engine!!.createScene()
            camera = engine!!.createCamera(engine!!.entityManager.create())
            view = engine!!.createView()
            renderer = engine!!.createRenderer()

            val materialProvider = UbershaderProvider(engine!!)
            assetLoader = AssetLoader(engine!!, materialProvider, EntityManager.get())
            resourceLoader = ResourceLoader(engine!!)

            view!!.scene = scene
            view!!.camera = camera

            // Transparent background for AR overlay over camera
            view!!.blendMode = View.BlendMode.TRANSLUCENT

            // Indirect lighting & Sun
            setupLights()

            displayHelper = DisplayHelper(surfaceView.context)
            uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK).apply {
                renderCallback = object : UiHelper.RenderCallback {
                    override fun onNativeWindowChanged(surface: Surface) {
                        swapChain?.let { engine?.destroySwapChain(it) }
                        swapChain = engine?.createSwapChain(surface)
                    }

                    override fun onDetachedFromSurface() {
                        swapChain?.let {
                            engine?.destroySwapChain(it)
                            engine?.flush()
                        }
                        swapChain = null
                    }

                    override fun onResized(width: Int, height: Int) {
                        view?.viewport = Viewport(0, 0, width, height)
                        val aspect = width.toDouble() / height.toDouble()
                        camera?.setProjection(45.0, aspect, 0.1, 100.0, Camera.Fov.VERTICAL)
                    }
                }
                attachTo(surfaceView)
            }

            isAvailable = true
            startRendering()
            Log.i(TAG, "Filament AR Engine initialized successfully")
        } catch (t: Throwable) {
            isAvailable = false
            lastError = t.message
            Log.e(TAG, "Filament initialization failed", t)
        }
    }

    private fun setupLights() {
        val em = engine!!.entityManager
        lightEntity = em.create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1.0f, 0.95f, 0.9f)
            .intensity(90000.0f)
            .direction(0.2f, -1.0f, -0.4f)
            .castShadows(true)
            .build(engine!!, lightEntity)

        scene?.addEntity(lightEntity)
    }

    fun loadGlb(buffer: ByteBuffer) {
        destroyModel()
        try {
            val asset = assetLoader?.createAsset(buffer) ?: return
            resourceLoader?.loadResources(asset)
            asset.releaseSourceData()

            scene?.addEntities(asset.entities)
            loadedAsset = asset
            animator = asset.instance.animator

            // Center model and fit to unit bounding box
            val boundingBox = asset.boundingBox
            val center = boundingBox.center
            val halfExtent = boundingBox.halfExtent
            val maxExtent = max(halfExtent[0], max(halfExtent[1], halfExtent[2]))
            val scaleFactor = if (maxExtent > 0f) 0.6f / maxExtent else 1.0f

            val transform = Mat4.translation(Float3(-center[0] * scaleFactor, -center[1] * scaleFactor, -center[2] * scaleFactor - 2.5f)) *
                    Mat4.scale(Float3(scaleFactor, scaleFactor, scaleFactor))

            val tm = engine!!.transformManager
            val rootInstance = tm.getInstance(asset.root)
            tm.setTransform(rootInstance, transform.toFloatArray())

            Log.i(TAG, "GLB asset loaded into AR scene successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load GLB asset", e)
        }
    }

    /**
     * Updates model AR position ($x, y$, scale) to lock directly over the detected toy bounding box in screen coordinates.
     */
    fun updateModelTransform(rect: RectF, viewW: Int, viewH: Int) {
        val asset = loadedAsset ?: return
        val tm = engine?.transformManager ?: return
        val rootInstance = tm.getInstance(asset.root)
        if (rootInstance == 0) return

        if (viewW <= 0 || viewH <= 0) return

        // Normalized screen coords (-1.0 to 1.0)
        val centerX = (rect.centerX() / viewW) * 2.0f - 1.0f
        val centerY = -((rect.centerY() / viewH) * 2.0f - 1.0f) // invert Y for 3D

        // Scale based on box size relative to screen
        val boxWidthNorm = rect.width() / viewW
        val scale = (boxWidthNorm * 2.2f).coerceIn(0.5f, 2.5f)

        // Smooth translation interpolation
        targetX += (centerX * 0.9f - targetX) * 0.3f
        targetY += (centerY * 0.9f - targetY) * 0.3f
        modelScale += (scale - modelScale) * 0.3f
        rotAngle = (rotAngle + 1.2f) % 360f

        val boundingBox = asset.boundingBox
        val center = boundingBox.center
        val halfExtent = boundingBox.halfExtent
        val maxExtent = max(halfExtent[0], max(halfExtent[1], halfExtent[2]))
        val baseScale = if (maxExtent > 0f) 0.6f / maxExtent else 1.0f

        val finalScale = baseScale * modelScale

        val transformMat = Mat4.translation(Float3(targetX, targetY, -2.5f)) *
                Mat4.rotation(rotAngle, Float3(0f, 1f, 0f)) *
                Mat4.scale(Float3(finalScale, finalScale, finalScale)) *
                Mat4.translation(Float3(-center[0], -center[1], -center[2]))

        tm.setTransform(rootInstance, transformMat.toFloatArray())
    }

    fun playAnimation(index: Int?, loop: Boolean = true, onComplete: (() -> Unit)? = null) {
        val anim = animator ?: return
        if (anim.animationCount == 0) return

        val animIndex = index ?: 0
        if (animIndex < anim.animationCount) {
            anim.applyAnimation(animIndex, 0f)
        }
    }

    private fun render(frameTimeNanos: Long) {
        val r = renderer ?: return
        val v = view ?: return
        val sc = swapChain ?: return

        if (uiHelper?.isReadyToRender == true) {
            animator?.let { anim ->
                if (anim.animationCount > 0) {
                    val duration = anim.getAnimationDuration(0)
                    val seconds = (frameTimeNanos / 1_000_000_000.0).toFloat()
                    anim.applyAnimation(0, seconds % duration)
                }
            }

            if (r.beginFrame(sc, frameTimeNanos)) {
                r.render(v)
                r.endFrame()
            }
        }
    }

    fun destroyModel() {
        loadedAsset?.let { asset ->
            scene?.removeEntities(asset.entities)
            assetLoader?.destroyAsset(asset)
            loadedAsset = null
            animator = null
        }
    }

    private fun startRendering() {
        if (!isFrameCallbackActive) {
            choreographer.postFrameCallback(frameCallback)
            isFrameCallbackActive = true
        }
    }

    fun onPause() {
        if (isFrameCallbackActive) {
            choreographer.removeFrameCallback(frameCallback)
            isFrameCallbackActive = false
        }
    }

    fun onResume() {
        startRendering()
    }

    fun release() {
        onPause()
        destroyModel()

        lightEntity.let {
            if (it != 0) {
                engine?.destroyEntity(it)
                engine?.entityManager?.destroy(it)
            }
        }

        assetLoader?.destroy()
        resourceLoader?.destroy()

        engine?.let { eng ->
            view?.let { eng.destroyView(it) }
            camera?.let {
                eng.destroyCameraComponent(it.entity)
                eng.entityManager.destroy(it.entity)
            }
            scene?.let { eng.destroyScene(it) }
            renderer?.let { eng.destroyRenderer(it) }
            swapChain?.let { eng.destroySwapChain(it) }
            eng.destroy()
        }

        engine = null
        scene = null
        camera = null
        view = null
        renderer = null
        swapChain = null
    }
}
