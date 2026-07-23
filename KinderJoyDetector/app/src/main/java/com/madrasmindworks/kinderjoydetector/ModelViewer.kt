package com.madrasmindworks.kinderjoydetector

import android.opengl.Matrix
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.Skybox
import com.google.android.filament.SwapChain
import com.google.android.filament.Viewport
import com.google.android.filament.View
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import java.nio.ByteBuffer

/**
 * Minimal, fully-local .glb viewer/player built directly on Filament's core
 * rendering primitives (Engine/Scene/View/Camera/Renderer) + gltfio's
 * AssetLoader/ResourceLoader/Animator.
 *
 * Bundled entirely inside the APK (filament-android / gltfio-android) — no
 * network fetch, no external environment/IBL files required.
 *
 * IMPORTANT: every Filament call is wrapped so a failure here (missing native
 * lib on an unusual device/emulator ABI, no OpenGL ES 3 support, a bad/huge
 * .glb, etc.) can never crash the rest of the app — it just logs and the
 * celebration screen falls back to text-only. Check [isAvailable] /
 * [lastError] if you want to react to that.
 *
 * Usage:
 *   val viewer = ModelViewer(surfaceView)
 *   if (viewer.isAvailable) {
 *       viewer.loadGlb(bytes)
 *       viewer.playAnimation(index, loop = false) { /* onComplete */ }
 *   }
 *   ...
 *   viewer.destroyModel()   // when leaving the celebration screen
 *   viewer.release()        // when the surface is being torn down for good
 */
class ModelViewer(private val surfaceView: SurfaceView) : SurfaceHolder.Callback,
    Choreographer.FrameCallback {

    companion object {
        private const val TAG = "ModelViewer"

        @Volatile private var nativeLibLoaded = false

        /**
         * Filament requires this exact call once per process before ANY
         * Engine/Scene/etc. is touched — it's what actually calls
         * System.loadLibrary("filament-jni") under the hood. Skipping it is
         * why Engine.create() was throwing UnsatisfiedLinkError on every
         * device (nCreateBuilder native method never got linked) rather than
         * only failing on some architectures.
         */
        @Synchronized
        fun ensureNativeLibLoaded() {
            if (nativeLibLoaded) return
            com.google.android.filament.Filament.init()
            nativeLibLoaded = true
        }
    }

    /** False if Filament failed to initialize on this device — callers should skip 3D and fall back. */
    var isAvailable = false
        private set
    var lastError: String? = null
        private set

    private var engine: Engine? = null
    private var scene: Scene? = null
    private var renderer: Renderer? = null
    private var cameraEntity: Int = 0
    private var camera: Camera? = null
    private var view: View? = null
    private var assetLoader: AssetLoader? = null
    private var resourceLoader: ResourceLoader? = null

    private var swapChain: SwapChain? = null
    private var asset: FilamentAsset? = null
    private val choreographer = Choreographer.getInstance()

    // Animation playback state
    private var animStartNanos = 0L
    private var playingAnimIndex: Int? = null
    private var loopAnimation = false
    private var onAnimationComplete: (() -> Unit)? = null
    private var completionFired = false

    init {
        try {
            ensureNativeLibLoaded()

            val eng = Engine.create()
            val scn = eng.createScene()
            val camEntity = EntityManager.get().create()
            val cam = eng.createCamera(camEntity)
            val vw = eng.createView().apply {
                scene = scn
                camera = cam
            }

            engine = eng
            scene = scn
            renderer = eng.createRenderer()
            cameraEntity = camEntity
            camera = cam
            view = vw
            assetLoader = AssetLoader(eng, UbershaderProvider(eng), EntityManager.get())
            resourceLoader = ResourceLoader(eng)

            surfaceView.holder.addCallback(this)
            setupLights(eng, scn)
            // Simple dark solid background — no IBL texture needs bundling/decoding.
            scn.skybox = Skybox.Builder().color(0.06f, 0.05f, 0.10f, 1.0f).build(eng)

            isAvailable = true
        } catch (t: Throwable) {
            lastError = t.message
            Log.e(TAG, "Filament unavailable on this device — 3D celebration disabled", t)
            isAvailable = false
        }
    }

    private fun setupLights(eng: Engine, scn: Scene) {
        addDirectionalLight(eng, scn, 120_000f, floatArrayOf(1f, 1f, 1f), floatArrayOf(-0.5f, -1.0f, -0.3f))
        addDirectionalLight(eng, scn, 35_000f, floatArrayOf(0.85f, 0.9f, 1.0f), floatArrayOf(0.6f, -0.2f, 0.5f))
        addDirectionalLight(eng, scn, 45_000f, floatArrayOf(1.0f, 0.85f, 0.55f), floatArrayOf(0.2f, 0.6f, -0.8f))
    }

    private fun addDirectionalLight(eng: Engine, scn: Scene, intensity: Float, color: FloatArray, dir: FloatArray) {
        val light = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(color[0], color[1], color[2])
            .intensity(intensity)
            .direction(dir[0], dir[1], dir[2])
            .castShadows(false)
            .build(eng, light)
        scn.addEntity(light)
    }

    /** Load a .glb already sitting fully in memory (e.g. read from assets). No-op if unavailable. */
    fun loadGlb(buffer: ByteBuffer) {
        if (!isAvailable) return
        try {
            destroyModel()
            val loader = assetLoader ?: return
            val loaded = loader.createAsset(buffer) ?: return
            resourceLoader?.loadResources(loaded)
            loaded.releaseSourceData()
            scene?.addEntities(loaded.entities)
            asset = loaded
            fitToUnitCube(loaded)
            positionCamera()
            completionFired = false
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load glb", t)
        }
    }

    /** Centers + scales the asset so it always fits the same view volume, on any device. */
    private fun fitToUnitCube(loaded: FilamentAsset) {
        val eng = engine ?: return
        val box = loaded.boundingBox
        val center = box.center
        val halfExtent = box.halfExtent
        val maxExtent = 2.0f * maxOf(halfExtent[0], halfExtent[1], halfExtent[2], 1e-5f)
        val scale = 2.0f / maxExtent

        val transform = FloatArray(16)
        Matrix.setIdentityM(transform, 0)
        Matrix.scaleM(transform, 0, scale, scale, scale)
        Matrix.translateM(transform, 0, -center[0], -center[1], -center[2])

        val tm = eng.transformManager
        val root = tm.getInstance(loaded.root)
        tm.setTransform(root, transform)
    }

    private fun positionCamera() {
        // Model is normalized into roughly [-1, 1]^3 by fitToUnitCube — a fixed
        // camera distance/FOV therefore frames it consistently on every screen.
        camera?.lookAt(0.0, 0.3, 4.2, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
        camera?.setExposure(16.0f, 1.0f / 125.0f, 100.0f)
    }

    /**
     * Plays one embedded animation by index. If [loop] is false, [onComplete]
     * fires once after the clip's duration has elapsed. No-op if unavailable
     * or if the model has no animations — [onComplete] still fires once so
     * callers (e.g. "loop after first play") don't get stuck.
     */
    fun playAnimation(index: Int?, loop: Boolean = false, onComplete: (() -> Unit)? = null) {
        if (!isAvailable) { onComplete?.invoke(); return }
        playingAnimIndex = index
        loopAnimation = loop
        onAnimationComplete = onComplete
        animStartNanos = System.nanoTime()
        completionFired = false
    }

    fun destroyModel() {
        try {
            asset?.let {
                scene?.removeEntities(it.entities)
                assetLoader?.destroyAsset(it)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error destroying model", t)
        }
        asset = null
        playingAnimIndex = null
        onAnimationComplete = null
    }

    /** Fully tears down the Filament engine — call when the surface is gone for good. */
    fun release() {
        choreographer.removeFrameCallback(this)
        try {
            destroyModel()
            resourceLoader?.destroy()
            assetLoader?.destroy()
            val eng = engine
            if (eng != null) {
                renderer?.let { eng.destroyRenderer(it) }
                view?.let { eng.destroyView(it) }
                scene?.let { eng.destroyScene(it) }
                if (cameraEntity != 0) eng.destroyCameraComponent(cameraEntity)
                EntityManager.get().destroy(cameraEntity)
                eng.destroy()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error releasing Filament engine", t)
        } finally {
            engine = null; scene = null; renderer = null; camera = null; view = null
            assetLoader = null; resourceLoader = null; isAvailable = false
        }
    }

    // ── SurfaceHolder.Callback ───────────────────────────────────────────────

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (!isAvailable) return
        try {
            swapChain = engine?.createSwapChain(holder.surface)
            choreographer.postFrameCallback(this)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to create swap chain", t)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (!isAvailable || width <= 0 || height <= 0) return
        try {
            view?.viewport = Viewport(0, 0, width, height)
            val aspect = width.toDouble() / height.toDouble()
            camera?.setProjection(45.0, aspect, 0.1, 50.0, Camera.Fov.VERTICAL)
        } catch (t: Throwable) {
            Log.e(TAG, "surfaceChanged failed", t)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        choreographer.removeFrameCallback(this)
        try {
            swapChain?.let { engine?.destroySwapChain(it); engine?.flushAndWait() }
        } catch (t: Throwable) {
            Log.e(TAG, "surfaceDestroyed cleanup failed", t)
        }
        swapChain = null
    }

    // ── Choreographer.FrameCallback ──────────────────────────────────────────

    override fun doFrame(frameTimeNanos: Long) {
        if (!isAvailable) return
        choreographer.postFrameCallback(this)

        try {
            val a = asset
            val animator = a?.instance?.animator
            if (a != null && animator != null && animator.animationCount > 0) {
                val idx = playingAnimIndex ?: 0
                val safeIdx = idx.coerceIn(0, animator.animationCount - 1)
                val duration = animator.getAnimationDuration(safeIdx)
                var elapsed = (frameTimeNanos - animStartNanos) / 1_000_000_000f

                if (duration > 0f) {
                    if (!loopAnimation && elapsed >= duration && !completionFired) {
                        completionFired = true
                        elapsed = duration
                        onAnimationComplete?.invoke()
                    }
                    val t = if (loopAnimation) elapsed % duration else elapsed
                    animator.applyAnimation(safeIdx, t)
                    animator.updateBoneMatrices()
                }
            } else if (a != null && !completionFired) {
                // No embedded animation on this model — fire completion once
                // immediately so callers don't wait forever for a callback
                // that will never come (e.g. Harry Potter's static model).
                completionFired = true
                onAnimationComplete?.invoke()
            }

            val sc = swapChain ?: return
            val rend = renderer ?: return
            val vw = view ?: return
            if (rend.beginFrame(sc, frameTimeNanos)) {
                rend.render(vw)
                rend.endFrame()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "doFrame failed — disabling further rendering", t)
            isAvailable = false
        }
    }
}
