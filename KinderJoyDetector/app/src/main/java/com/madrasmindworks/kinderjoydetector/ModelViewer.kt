package com.madrasmindworks.kinderjoydetector

import android.opengl.Matrix
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
 * network fetch, no external environment/IBL files required. Lighting is a
 * simple 3-point rig set up in code, and the model is auto-centered/scaled
 * into a unit cube on load so it fits the screen the same way on every
 * device, regardless of the source model's original scale or pivot.
 *
 * Usage:
 *   val viewer = ModelViewer(surfaceView)
 *   viewer.loadGlb(bytes)
 *   viewer.playAnimation(index, loop = false) { /* onComplete */ }
 *   ...
 *   viewer.destroyModel()   // when leaving the celebration screen
 *   viewer.release()        // when the surface is being torn down for good
 */
class ModelViewer(private val surfaceView: SurfaceView) : SurfaceHolder.Callback,
    Choreographer.FrameCallback {

    private val engine: Engine = Engine.create()
    private val scene: Scene = engine.createScene()
    private val renderer: Renderer = engine.createRenderer()
    private val cameraEntity = EntityManager.get().create()
    private val camera: Camera = engine.createCamera(cameraEntity)
    private val view: View = engine.createView().apply {
        scene = this@ModelViewer.scene
        camera = this@ModelViewer.camera
    }

    private val assetLoader = AssetLoader(engine, UbershaderProvider(engine), EntityManager.get())
    private val resourceLoader = ResourceLoader(engine)

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
        surfaceView.holder.addCallback(this)
        setupLights()
        // Simple dark solid background — the celebration screen provides its
        // own UI chrome around the SurfaceView, so a plain color keeps things
        // fast (no IBL texture needs to be bundled/decoded).
        scene.skybox = Skybox.Builder().color(0.06f, 0.05f, 0.10f, 1.0f).build(engine)
    }

    private fun setupLights() {
        // Key light
        addDirectionalLight(120_000f, floatArrayOf(1f, 1f, 1f), floatArrayOf(-0.5f, -1.0f, -0.3f))
        // Fill light (softer, cooler, opposite side) — fills in shadows
        addDirectionalLight(35_000f, floatArrayOf(0.85f, 0.9f, 1.0f), floatArrayOf(0.6f, -0.2f, 0.5f))
        // Rim light (warm) — gives models a bit of a celebratory glow at the edges
        addDirectionalLight(45_000f, floatArrayOf(1.0f, 0.85f, 0.55f), floatArrayOf(0.2f, 0.6f, -0.8f))
    }

    private fun addDirectionalLight(intensity: Float, color: FloatArray, dir: FloatArray) {
        val light = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(color[0], color[1], color[2])
            .intensity(intensity)
            .direction(dir[0], dir[1], dir[2])
            .castShadows(false)
            .build(engine, light)
        scene.addEntity(light)
    }

    /** Load a .glb already sitting fully in memory (e.g. read from assets). */
    fun loadGlb(buffer: ByteBuffer) {
        destroyModel()
        val loaded = assetLoader.createAsset(buffer) ?: return
        resourceLoader.loadResources(loaded)
        loaded.releaseSourceData()
        scene.addEntities(loaded.entities)
        asset = loaded
        fitToUnitCube(loaded)
        positionCamera()
        completionFired = false
    }

    /** Centers + scales the asset so it always fits the same view volume, on any device. */
    private fun fitToUnitCube(loaded: FilamentAsset) {
        val box = loaded.boundingBox
        val center = box.center
        val halfExtent = box.halfExtent
        val maxExtent = 2.0f * maxOf(halfExtent[0], halfExtent[1], halfExtent[2], 1e-5f)
        val scale = 2.0f / maxExtent

        val transform = FloatArray(16)
        Matrix.setIdentityM(transform, 0)
        Matrix.scaleM(transform, 0, scale, scale, scale)
        Matrix.translateM(transform, 0, -center[0], -center[1], -center[2])

        val tm = engine.transformManager
        val root = tm.getInstance(loaded.root)
        tm.setTransform(root, transform)
    }

    private fun positionCamera() {
        // Model is normalized into roughly [-1, 1]^3 by fitToUnitCube — a fixed
        // camera distance/FOV therefore frames it consistently on every screen.
        camera.lookAt(0.0, 0.3, 4.2, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
        camera.setExposure(16.0f, 1.0f / 125.0f, 100.0f)
    }

    /**
     * Plays one embedded animation by index. If [loop] is false, [onComplete]
     * fires once after the clip's duration has elapsed.
     */
    fun playAnimation(index: Int?, loop: Boolean = false, onComplete: (() -> Unit)? = null) {
        playingAnimIndex = index
        loopAnimation = loop
        onAnimationComplete = onComplete
        animStartNanos = System.nanoTime()
        completionFired = false
    }

    fun destroyModel() {
        asset?.let {
            scene.removeEntities(it.entities)
            assetLoader.destroyAsset(it)
        }
        asset = null
        playingAnimIndex = null
        onAnimationComplete = null
    }

    /** Fully tears down the Filament engine — call when the surface is gone for good. */
    fun release() {
        choreographer.removeFrameCallback(this)
        destroyModel()
        resourceLoader.destroy()
        assetLoader.destroy()
        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(cameraEntity)
        EntityManager.get().destroy(cameraEntity)
        engine.destroy()
    }

    // ── SurfaceHolder.Callback ───────────────────────────────────────────────

    override fun surfaceCreated(holder: SurfaceHolder) {
        swapChain = engine.createSwapChain(holder.surface)
        choreographer.postFrameCallback(this)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        view.viewport = Viewport(0, 0, width, height)
        val aspect = width.toDouble() / height.toDouble()
        camera.setProjection(45.0, aspect, 0.1, 50.0, Camera.Fov.VERTICAL)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        choreographer.removeFrameCallback(this)
        swapChain?.let { engine.destroySwapChain(it); engine.flushAndWait() }
        swapChain = null
    }

    // ── Choreographer.FrameCallback ──────────────────────────────────────────

    override fun doFrame(frameTimeNanos: Long) {
        choreographer.postFrameCallback(this)

        val a = asset
        val animator = a?.instance?.animator
        if (a != null && animator != null && animator.animationCount > 0) {
            val idx = playingAnimIndex ?: 0
            val safeIdx = idx.coerceIn(0, animator.animationCount - 1)
            val duration = animator.getAnimationDuration(safeIdx)
            var elapsed = (frameTimeNanos - animStartNanos) / 1_000_000_000f

            if (!loopAnimation && elapsed >= duration && !completionFired) {
                completionFired = true
                elapsed = duration
                onAnimationComplete?.invoke()
            }
            val t = if (loopAnimation) elapsed % duration else elapsed
            animator.applyAnimation(safeIdx, t)
            animator.updateBoneMatrices()
        }

        val sc = swapChain ?: return
        if (renderer.beginFrame(sc, frameTimeNanos)) {
            renderer.render(view)
            renderer.endFrame()
        }
    }
}
