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
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.tan

/**
 * Lightweight Filament 3D Viewer for AR overlay over CameraX preview.
 * Supports Android 8.0 (API 26) through Android 15 (API 35+).
 */
class ModelViewer(val surfaceView: SurfaceView) {

    companion object {
        private const val TAG = "ModelViewer"
    }

    private val choreographer = Choreographer.getInstance()
    private var renderFrameCount = 0
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

    private var sunEntity: Int = 0
    private var fillEntity: Int = 0
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
            Log.d(TAG, "Initializing Filament AR Engine...")

            // SurfaceView Z-Order on top to ensure 3D model renders over camera preview
            surfaceView.setZOrderOnTop(true)
            surfaceView.holder.setFormat(PixelFormat.TRANSLUCENT)

            engine = Engine.create()
            Log.d(TAG, "Engine created")

            scene = engine!!.createScene()
            Log.d(TAG, "Scene created")

            camera = engine!!.createCamera(engine!!.entityManager.create())
            Log.d(TAG, "Camera created")

            view = engine!!.createView()
            Log.d(TAG, "View created")

            renderer = engine!!.createRenderer()
            Log.d(TAG, "Renderer created")

            val materialProvider = UbershaderProvider(engine!!)
            assetLoader = AssetLoader(engine!!, materialProvider, EntityManager.get())
            resourceLoader = ResourceLoader(engine!!)
            Log.d(TAG, "AssetLoader and ResourceLoader created")

            view!!.scene = scene
            view!!.camera = camera

            // Transparent background for AR overlay over camera
            view!!.blendMode = View.BlendMode.TRANSLUCENT

            // Direct + Ambient lighting
            setupLights()

            displayHelper = DisplayHelper(surfaceView.context)
            uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK).apply {
                renderCallback = object : UiHelper.RendererCallback {
                    override fun onNativeWindowChanged(surface: Surface) {
                        Log.d(TAG, "Native window changed -> Creating SwapChain")
                        swapChain?.let { engine?.destroySwapChain(it) }
                        swapChain = engine?.createSwapChain(surface)
                        Log.d(TAG, "SwapChain created successfully")
                    }

                    override fun onDetachedFromSurface() {
                        Log.d(TAG, "Detached from surface -> Destroying SwapChain")
                        swapChain?.let {
                            engine?.destroySwapChain(it)
                            engine?.flushAndWait()
                        }
                        swapChain = null
                    }

                    override fun onResized(width: Int, height: Int) {
                        Log.d(TAG, "Surface resized: ${width}x${height}")
                        view?.viewport = Viewport(0, 0, width, height)
                        val aspect = width.toDouble() / height.toDouble()
                        camera?.setProjection(45.0, aspect, 0.1, 100.0, Camera.Fov.VERTICAL)
                    }
                }
                attachTo(surfaceView)
            }

            isAvailable = true
            startRendering()
            Log.i(TAG, "Filament AR Engine fully initialized and ready")
        } catch (t: Throwable) {
            isAvailable = false
            lastError = t.message
            Log.e(TAG, "Filament initialization failed", t)
        }
    }

    private fun setupLights() {
        val em = engine!!.entityManager

        // Front main sun light
        sunEntity = em.create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1.0f, 1.0f, 1.0f)
            .intensity(120000.0f)
            .direction(0.0f, -0.5f, -1.0f)
            .castShadows(false)
            .build(engine!!, sunEntity)
        scene?.addEntity(sunEntity)

        // Front-bottom fill light
        fillEntity = em.create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1.0f, 0.95f, 0.9f)
            .intensity(60000.0f)
            .direction(0.0f, 0.5f, -1.0f)
            .castShadows(false)
            .build(engine!!, fillEntity)
        scene?.addEntity(fillEntity)
        Log.d(TAG, "Directional & Fill Lights initialized")
    }

    fun loadGlb(buffer: ByteBuffer) {
        destroyModel()
        try {
            Log.d(TAG, "Loading GLB from buffer...")
            val asset = assetLoader?.createAsset(buffer)
            if (asset == null) {
                Log.e(TAG, "GLB failed: assetLoader returned null")
                return
            }
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

            val t1 = createTranslationMat4(-center[0] * scaleFactor, -center[1] * scaleFactor, -center[2] * scaleFactor - 1.4f)
            val s1 = createScaleMat4(scaleFactor, scaleFactor, scaleFactor)
            val transform = multiplyMat4(t1, s1)

            val tm = engine!!.transformManager
            val rootInstance = tm.getInstance(asset.root)
            tm.setTransform(rootInstance, transform)

            Log.i(TAG, "GLB loaded successfully: entity count=${asset.entities.size}")
        } catch (e: Exception) {
            Log.e(TAG, "GLB failed: ${e.message}", e)
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

        val aspect = viewW.toFloat() / viewH.toFloat()

        // FOV-accurate projection mapping to frustum at Z = -1.4f
        val frustumH = (2.0f * 1.4f * tan(Math.toRadians(22.5))).toFloat() // ~1.16
        val frustumW = frustumH * aspect

        val centerX = ((rect.centerX() / viewW) - 0.5f) * frustumW
        val centerY = -((rect.centerY() / viewH) - 0.5f) * frustumH // invert Y for 3D

        // Scale based on box size relative to screen
        val boxWidthNorm = rect.width() / viewW
        val scale = (boxWidthNorm * 2.2f).coerceIn(0.5f, 2.5f)

        // Smooth translation interpolation
        targetX += (centerX - targetX) * 0.35f
        targetY += (centerY - targetY) * 0.35f
        modelScale += (scale - modelScale) * 0.35f
        rotAngle = (rotAngle + 1.5f) % 360f

        val boundingBox = asset.boundingBox
        val center = boundingBox.center
        val halfExtent = boundingBox.halfExtent
        val maxExtent = max(halfExtent[0], max(halfExtent[1], halfExtent[2]))
        val baseScale = if (maxExtent > 0f) 0.6f / maxExtent else 1.0f

        val finalScale = baseScale * modelScale

        val t1 = createTranslationMat4(targetX, targetY, -1.4f)
        val r1 = createRotationYMat4(rotAngle)
        val s1 = createScaleMat4(finalScale, finalScale, finalScale)
        val t2 = createTranslationMat4(-center[0], -center[1], -center[2])

        val transformMat = multiplyMat4(t1, multiplyMat4(r1, multiplyMat4(s1, t2)))

        tm.setTransform(rootInstance, transformMat)

        if (renderFrameCount % 30 == 0) {
            Log.d(TAG, "Model transform updated: X=${"%.2f".format(targetX)}, Y=${"%.2f".format(targetY)}, scale=${"%.2f".format(modelScale)}")
        }
    }

    fun playAnimation(index: Int?, loop: Boolean = true, onComplete: (() -> Unit)? = null) {
        val anim = animator ?: return
        if (anim.animationCount == 0) return

        val animIndex = index ?: 0
        if (animIndex < anim.animationCount) {
            anim.applyAnimation(animIndex, 0f)
            Log.i(TAG, "Animation started: index=$animIndex, totalCount=${anim.animationCount}")
        }
    }

    private fun render(frameTimeNanos: Long) {
        val r = renderer ?: return
        val v = view ?: return
        val sc = swapChain ?: return

        renderFrameCount++
        if (renderFrameCount % 60 == 0) {
            Log.d(TAG, "render() frame callback running actively ($renderFrameCount frames)")
        }

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
            Log.d(TAG, "Active GLB model destroyed")
        }
    }

    private fun startRendering() {
        if (!isFrameCallbackActive) {
            choreographer.postFrameCallback(frameCallback)
            isFrameCallbackActive = true
            Log.d(TAG, "Rendering loop started")
        }
    }

    fun onPause() {
        if (isFrameCallbackActive) {
            choreographer.removeFrameCallback(frameCallback)
            isFrameCallbackActive = false
            Log.d(TAG, "Rendering loop paused")
        }
    }

    fun onResume() {
        startRendering()
    }

    fun release() {
        onPause()
        destroyModel()

        if (sunEntity != 0) {
            engine?.destroyEntity(sunEntity)
            engine?.entityManager?.destroy(sunEntity)
        }
        if (fillEntity != 0) {
            engine?.destroyEntity(fillEntity)
            engine?.entityManager?.destroy(fillEntity)
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
        Log.i(TAG, "Filament AR Engine released")
    }

    // ── 4x4 Matrix Math Helpers ──────────────────────────────────────────────

    private fun multiplyMat4(a: FloatArray, b: FloatArray): FloatArray {
        val res = FloatArray(16)
        for (i in 0 until 4) {
            for (j in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) {
                    sum += a[k * 4 + i] * b[j * 4 + k]
                }
                res[j * 4 + i] = sum
            }
        }
        return res
    }

    private fun createTranslationMat4(x: Float, y: Float, z: Float): FloatArray {
        return floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            x,  y,  z,  1f
        )
    }

    private fun createScaleMat4(sx: Float, sy: Float, sz: Float): FloatArray {
        return floatArrayOf(
            sx, 0f, 0f, 0f,
            0f, sy, 0f, 0f,
            0f, 0f, sz, 0f,
            0f, 0f, 0f, 1f
        )
    }

    private fun createRotationYMat4(angleDeg: Float): FloatArray {
        val rad = Math.toRadians(angleDeg.toDouble()).toFloat()
        val c = Math.cos(rad.toDouble()).toFloat()
        val s = Math.sin(rad.toDouble()).toFloat()
        return floatArrayOf(
            c,  0f, -s, 0f,
            0f, 1f, 0f, 0f,
            s,  0f,  c, 0f,
            0f, 0f, 0f, 1f
        )
    }
}
