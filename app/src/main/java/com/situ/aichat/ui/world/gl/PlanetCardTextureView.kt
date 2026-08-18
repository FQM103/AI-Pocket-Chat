package com.situ.aichat.ui.world.gl

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Choreographer
import android.view.TextureView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.situ.aichat.ui.world.planet.PlanetCamera
import com.situ.aichat.ui.world.planet.PlanetRenderer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface

/**
 * TextureView 宿主（W11 图纸 §2/§4.2）：自管 EGL 线程驱动**未改动**的 [PlanetRenderer]，令 W9a 星球窗景住进
 * Compose 圆角卡片里（GLSurfaceView 无法被裁剪，TextureView 走正常视图合成·可裁剪可滚动）。星球栈锁死区
 * （`ui/world/planet/`）**只调用绝不改**：机位 [PlanetCamera.restore]（demo 0.6/−0.18/3.0）、自转 = renderer
 * 既有环境积分（0.027 rad/s）、[MsaaConfigChooser] 复用（EGL10）。
 *
 * 渲染纪律（§17）：Choreographer 时间基准节流 30Hz 环境态（与 [WorldFramePump] 同源常量·卷 B）；
 * `reduceMotion||staticMode`（frozen）→ 渲染 1 帧后停；
 * hub 非 RESUMED（Lifecycle 观察）→ 停。GL 失败（着色器编译/EGL）→ [onGlError] 回主线程（调用方隐藏星球层·
 * 不崩·§5 E2）。所有 EGL / 循环状态**只在 GL 线程**触碰（控制方法 post 到 [glHandler]）。
 */
internal class PlanetCardTextureView(
    context: Context,
    seed: Long,
    seedOff: Float,
    private val onGlError: () -> Unit,
) : TextureView(context), TextureView.SurfaceTextureListener {

    private val camera = PlanetCamera().apply { restore(CARD_YAW, CARD_PITCH, CARD_DIST) } // demo 机位（§4.2）
    private val renderer: PlanetRenderer

    private var glThread: android.os.HandlerThread? = null
    private var glHandler: android.os.Handler? = null

    // ── GL 线程私有态（无跨线程共享·不需 @Volatile）──
    private var egl: EGL10? = null
    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null
    private var eglConfig: EGLConfig? = null
    private var surface: SurfaceTexture? = null
    private var surfaceW = 0
    private var surfaceH = 0
    private var glReady = false
    private var glError = false
    private var choreographer: Choreographer? = null
    private var looping = false
    private var lastDrawNanos = 0L // 上次实画的帧时间戳（时间基准节流·0=下一拍立即画）
    private var frozenFrameDrawn = false

    // paused/frozen 跨线程：主线程（lifecycle / setRenderFlags）写、GL 线程（maybeStartLoop）读 → @Volatile。
    // 直接写字段（不经 glHandler.post）：resume 可能在 surface 就绪前（glHandler==null）到达，post 会被丢，
    // 直写保证 initGl 里的 maybeStartLoop 读到正确门控（否则渲染循环卡在 paused=true）。
    @Volatile private var paused = true
    @Volatile private var frozen = false

    private var lifecycle: Lifecycle? = null
    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_RESUME -> resumeCard()
            Lifecycle.Event.ON_PAUSE -> pauseCard()
            else -> {}
        }
    }

    init {
        isOpaque = true // renderer 清不透明黑 + 自绘完整窗景（背景/星点/星球/云）→ 表面不透明，省合成。
        val pointScale = resources.displayMetrics.density.coerceAtMost(2f) // demo:L209 / PlanetGLView:48 照抄
        renderer = PlanetRenderer(camera, seed, seedOff, pointScale, ::handleGlError)
        surfaceTextureListener = this
    }

    // ── 公开 API（§2）──

    /** 降档信号透传（reduceMotion=系统「移除动画」·staticMode=省电/发热）→ renderer @Volatile + 循环门控。 */
    fun setRenderFlags(reduceMotion: Boolean, staticMode: Boolean) {
        renderer.reduceMotion = reduceMotion
        renderer.staticMode = staticMode
        frozen = reduceMotion || staticMode // 直写（surface 未就绪时 post 会丢·initGl 的 maybeStartLoop 读之）
        glHandler?.post {
            frozenFrameDrawn = false // 状态变→重画 1 帧（frozen 静帧 / 非 frozen 恢复 30Hz）
            maybeStartLoop()
        }
    }

    fun release() {
        val handler = glHandler
        if (handler != null) {
            handler.post { teardownEgl() }
            glThread?.quitSafely()
        }
        glThread = null
        glHandler = null
        surfaceTextureListener = null
    }

    private fun resumeCard() {
        paused = false // 直写：resume 可能早于 surface 就绪（glHandler==null）·initGl 的 maybeStartLoop 读之
        glHandler?.post { maybeStartLoop() }
    }

    private fun pauseCard() {
        paused = true
        glHandler?.post { stopLoop() }
    }

    // ── Lifecycle（hub RESUMED 门控·§3）──

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lifecycle = findViewTreeLifecycleOwner()?.lifecycle
        lifecycle?.addObserver(lifecycleObserver) // 已 RESUMED 则同步补发 ON_RESUME → resumeCard
    }

    override fun onDetachedFromWindow() {
        lifecycle?.removeObserver(lifecycleObserver)
        lifecycle = null
        super.onDetachedFromWindow()
    }

    // ── SurfaceTextureListener ──

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        if (glThread == null) {
            val t = android.os.HandlerThread("planet-card-gl").also { it.start() }
            glThread = t
            glHandler = android.os.Handler(t.looper)
        }
        Log.d(TAG, "surface available ${width}x$height")
        glHandler?.post { initGl(st, width, height) }
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
        glHandler?.post { resizeGl(width, height) }
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        // 同步等 GL 线程拆完再交回系统释放 ST（防 use-after-free）。
        val handler = glHandler ?: return true
        val latch = CountDownLatch(1)
        handler.post { teardownEgl(); latch.countDown() }
        runCatching { latch.await(1, TimeUnit.SECONDS) }
        Log.d(TAG, "surface destroyed")
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    // ── GL 线程：EGL 生命周期 ──

    private fun initGl(st: SurfaceTexture, width: Int, height: Int) {
        val e = (EGLContext.getEGL() as EGL10)
        egl = e
        val display = e.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
        if (display == EGL10.EGL_NO_DISPLAY || !e.eglInitialize(display, IntArray(2))) {
            handleGlError(); return
        }
        eglDisplay = display
        val config = runCatching { MsaaConfigChooser().chooseConfig(e, display) }.getOrNull()
        if (config == null) { handleGlError(); return }
        eglConfig = config
        val context = e.eglCreateContext(
            display, config, EGL10.EGL_NO_CONTEXT,
            intArrayOf(EGL_CONTEXT_CLIENT_VERSION, 2, EGL10.EGL_NONE),
        )
        if (context == null || context == EGL10.EGL_NO_CONTEXT) { handleGlError(); return }
        eglContext = context
        surface = st
        if (!createEglSurface(st, width, height)) return
        renderer.onSurfaceCreated(null, config) // 着色器编译/链接失败 → 内部回调 handleGlError
        renderer.onSurfaceChanged(null, surfaceW, surfaceH)
        glReady = true
        choreographer = Choreographer.getInstance()
        maybeStartLoop()
    }

    private fun resizeGl(width: Int, height: Int) {
        val st = surface ?: return
        if (!glReady) return
        val e = egl ?: return
        eglSurface?.let { e.eglDestroySurface(eglDisplay, it) }
        eglSurface = null
        if (!createEglSurface(st, width, height)) return
        renderer.onSurfaceChanged(null, surfaceW, surfaceH)
        maybeStartLoop()
    }

    /** 建 EGL window surface（DPR≤2 封顶·demo:L209）+ makeCurrent。失败 → handleGlError 返回 false。 */
    private fun createEglSurface(st: SurfaceTexture, width: Int, height: Int): Boolean {
        val e = egl ?: return false
        val density = resources.displayMetrics.density
        if (density > 2f && width > 0 && height > 0) {
            surfaceW = (width * 2f / density).toInt()
            surfaceH = (height * 2f / density).toInt()
        } else {
            surfaceW = width; surfaceH = height
        }
        st.setDefaultBufferSize(surfaceW, surfaceH)
        val s = e.eglCreateWindowSurface(eglDisplay, eglConfig, st, null)
        if (s == null || s == EGL10.EGL_NO_SURFACE) { handleGlError(); return false }
        eglSurface = s
        if (!e.eglMakeCurrent(eglDisplay, s, s, eglContext)) { handleGlError(); return false }
        return true
    }

    private fun teardownEgl() {
        stopLoop()
        glReady = false
        val e = egl ?: return
        val display = eglDisplay
        if (display != null) {
            e.eglMakeCurrent(display, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT)
            eglSurface?.let { e.eglDestroySurface(display, it) }
            eglContext?.let { e.eglDestroyContext(display, it) }
            e.eglTerminate(display)
        }
        eglSurface = null
        eglContext = null
        eglDisplay = null
        eglConfig = null
        surface = null
        egl = null
    }

    // ── GL 线程：渲染循环（Choreographer 时间基准节流 30Hz·§4.2·卷 B）──

    private fun maybeStartLoop() {
        if (!glReady || glError || paused) { stopLoop(); return }
        if (frozen && frozenFrameDrawn) { stopLoop(); return }
        if (!looping) {
            looping = true
            lastDrawNanos = 0L // 重启即刻画（首拍 now-0 恒 ≥ 间隔）
            choreographer?.postFrameCallback(frameCallback)
        }
    }

    private fun stopLoop() {
        if (looping) {
            looping = false
            choreographer?.removeFrameCallback(frameCallback)
        }
    }

    private val frameCallback: Choreographer.FrameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        if (!looping) return@FrameCallback
        // 时间基准节流 = 30Hz（§17 环境态口径·常量与帧泵同源·任何刷新率下恒定）
        if (WorldFramePump.shouldRender(frameTimeNanos, lastDrawNanos, WorldFramePump.AMBIENT_MIN_INTERVAL_NANOS)) {
            lastDrawNanos = frameTimeNanos
            drawFrame()
            if (frozen) { frozenFrameDrawn = true; looping = false; return@FrameCallback } // 静帧：1 帧后停
        }
        choreographer?.postFrameCallback(frameCallback)
    }

    private fun drawFrame() {
        val e = egl ?: return
        val display = eglDisplay ?: return
        val s = eglSurface ?: return
        renderer.onDrawFrame(null) // camera.integrate + 四 pass（星球栈内部·未改）
        e.eglSwapBuffers(display, s)
    }

    /** GL / EGL 失败兜底（GL 线程调）：停循环 + 回主线程通知调用方隐藏星球层（卡片其余照常）。 */
    private fun handleGlError() {
        glError = true
        stopLoop()
        post { onGlError() }
    }

    private companion object {
        const val TAG = "PlanetCardGL"
        const val CARD_YAW = 0.6f       // demo:L239
        const val CARD_PITCH = -0.18f   // demo:L239
        const val CARD_DIST = 3.0f      // demo:L239
        const val EGL_CONTEXT_CLIENT_VERSION = 0x3098
    }
}
