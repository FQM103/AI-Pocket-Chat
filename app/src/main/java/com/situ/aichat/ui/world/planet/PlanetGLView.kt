package com.situ.aichat.ui.world.planet

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import com.situ.aichat.ui.world.gl.MsaaConfigChooser
import com.situ.aichat.ui.world.gl.WorldFramePump
import kotlin.math.hypot

/**
 * 星球 GLSurfaceView（W9a 图纸 §2/§3.4·W9b 加法）：EGL 配置 + DPR≤2 封顶（demo:L209）+ 触摸→相机事件 +
 * 帧泵。EGL chooser（[MsaaConfigChooser]）与帧泵（[WorldFramePump]）已**只搬不改**迁至 `ui/world/gl/` 与大陆
 * 视图共用。相机与渲染器由本视图内部持有（GL 栈封装）。
 *
 * **W9b 转场加法（图纸 §3.5）**：[setInputLocked]（转场期间吞触摸）+ cinematic 姿态透传（[setCinematicPose]/
 * [clearCinematic]）+ [restore]（回星球恢复出发前姿态）+ [onDiveGesture] 回调（overpinch 触发进大陆·帧泵
 * 每拍轮询 [PlanetCamera.consumeDiveRequested]·主线程回调）+ 工厂 [initialPose]（默认 = 现行初值·9a 行为不变）。
 *
 * **性能卷 B 加法**：`onFirstFrame`（渲染器首帧画完 → post 回主线程）供回星球转场「画好再揭幕」，默认空。
 */
internal class PlanetGLView(
    context: Context,
    seed: Long,
    seedOff: Float,
    onGlError: () -> Unit,
    initialPose: Triple<Float, Float, Float>? = null,
    private val onDiveGesture: () -> Unit = {},
    onFirstFrame: () -> Unit = {},
) : GLSurfaceView(context) {

    private val camera = PlanetCamera()
    private val renderer: PlanetRenderer

    private var lastX = 0f
    private var lastY = 0f
    private var prevSpan = 0f

    /** W15.3 抓取锚：按下时命中的球面点（模型系·null=未命中走 delta 兜底）。 */
    private var grabAnchor: FloatArray? = null

    /**
     * W15.3 点按回调（UP 时若整个手势位移 < 24dp 且无捏合 → 视为点按·吐屏幕坐标）。
     * 标记/雪佛龙的点击路由收到这里——Compose 叠层若自带 clickable 会在命中测试里**遮蔽整段拖动**
     * （按住家标记拖球 = 最自然的抓取动作会被吞掉），故叠层只留 a11y 语义、触摸全归本视图。
     */
    var onTapListener: ((Float, Float) -> Unit)? = null
    private var downX = 0f
    private var downY = 0f
    private var gestureIsTap = false
    private val tapSlopPx = 24f * resources.displayMetrics.density

    @Volatile private var inputLocked = false

    private val framePump = WorldFramePump(
        frozen = { renderer.reduceMotion || renderer.staticMode },
        gesturing = { camera.isGesturing() },
        highFps = { camera.wantsHighFps() },
        render = { requestRender() },
        onTick = { if (camera.consumeDiveRequested()) onDiveGesture() },
    )

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(MsaaConfigChooser())
        val pointScale = resources.displayMetrics.density.coerceAtMost(2f)
        renderer = PlanetRenderer(
            camera, seed, seedOff, pointScale, onGlError,
            onFirstFrame = { post { onFirstFrame() } }, // GL 线程触发 → post 回主线程（照 ContinentGLView 惯例）
        )
        // 回星球时恢复出发前姿态（在首次 integrate/render 前生效·RENDERMODE_WHEN_DIRTY 期间无渲染）。
        initialPose?.let { camera.restore(it.first, it.second, it.third) }
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /** 家乡标记投影读取相机瞬时态（Compose 侧）。 */
    fun cameraSnapshot(): CameraSnapshot = camera.snapshot

    /** 设降档信号（reduceMotion=Compose 侧取·staticMode=省电/发热）→ 渲染器 + 帧泵同源读。 */
    fun setRenderFlags(reduceMotion: Boolean, staticMode: Boolean) {
        renderer.reduceMotion = reduceMotion
        renderer.staticMode = staticMode
    }

    /** 转场期间锁输入（onTouchEvent 直接吞掉不入队·图纸 §3.5·E11）。 */
    fun setInputLocked(locked: Boolean) { inputLocked = locked }

    /** cinematic 覆写姿态透传（转场镜头逐帧 lerp·图纸 §3.6）。 */
    fun setCinematicPose(yaw: Float, pitch: Float, dist: Float) {
        camera.setCinematicPose(yaw, pitch, dist)
        requestRender()
    }

    /** 解除 cinematic（转场结束）。 */
    fun clearCinematic() { camera.clearCinematic() }

    fun resumeWorld() {
        onResume()
        framePump.start()
        requestRender()
    }

    fun pauseWorld() {
        framePump.stop()
        onPause()
    }

    // DPR≤2 封顶（demo:L209）：density>2 → 表面固定到 2× 逻辑分辨率，省像素、控发热。
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 跟手拖动换算分母 = 视图高（触摸事件同坐标系·非 DPR 封顶后的表面高）。
        if (h > 0) camera.setViewportHeight(h.toFloat())
        val density = resources.displayMetrics.density
        if (density > 2f && w > 0 && h > 0) {
            holder.setFixedSize((w * 2f / density).toInt(), (h * 2f / density).toInt())
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (inputLocked) return true // 转场期间吞掉一切触摸（无 pick·相机快照不动）。
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                camera.setPointerDown(true)
                lastX = event.x; lastY = event.y
                grabAnchor = anchorAt(event.x, event.y)
                downX = event.x; downY = event.y; gestureIsTap = true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    camera.setPinching(true)
                    prevSpan = spanOf(event)
                    grabAnchor = null // 进入捏合即弃锚（缩放改变命中关系）
                    gestureIsTap = false
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    val span = spanOf(event)
                    camera.onPinchBy(prevSpan / span.coerceAtLeast(1f))
                    prevSpan = span
                } else {
                    if (gestureIsTap && hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()) > tapSlopPx) {
                        gestureIsTap = false
                    }
                    val anchor = grabAnchor
                    val snap = camera.snapshot
                    val q = if (anchor != null && width > 0 && height > 0) {
                        PlanetMath.screenToSphere(event.x, event.y, width.toFloat(), height.toFloat(), snap.dist)
                    } else {
                        null
                    }
                    if (anchor != null && q != null) {
                        // W15.3 抓取主通道：反解姿态令锚点钉在手指下（滑出球面的事件姿态保持）
                        val (y2, p2) = PlanetMath.solveGrabPose(anchor, q, snap.yaw, snap.pitch, PITCH_LIMIT)
                        camera.onGrabPose(y2, p2)
                    } else if (anchor == null) {
                        camera.onDragBy(event.x - lastX, event.y - lastY) // 未锚定（点在星空）→ delta 兜底
                    }
                    lastX = event.x; lastY = event.y
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // 双指→单指：结束捏合，把 last 重设到剩余那根手指防跳变，并按新缩放重新锚定。
                camera.setPinching(false)
                val remaining = if (event.actionIndex == 0) 1 else 0
                lastX = event.getX(remaining); lastY = event.getY(remaining)
                grabAnchor = anchorAt(lastX, lastY)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                camera.setPointerDown(false)
                camera.setPinching(false)
                grabAnchor = null
                if (event.actionMasked == MotionEvent.ACTION_UP && gestureIsTap) {
                    onTapListener?.invoke(event.x, event.y)
                }
                gestureIsTap = false
            }
        }
        requestRender() // 唤醒本拍渲染（静帧模式手势期间靠此驱动）
        return true
    }

    private fun spanOf(e: MotionEvent): Float =
        hypot((e.getX(0) - e.getX(1)).toDouble(), (e.getY(0) - e.getY(1)).toDouble()).toFloat()

    /** 触点 → 模型系抓取锚（W15.3·未命中球面 = null → delta 兜底）。 */
    private fun anchorAt(xPx: Float, yPx: Float): FloatArray? {
        if (width <= 0 || height <= 0) return null
        val snap = camera.snapshot
        val q = PlanetMath.screenToSphere(xPx, yPx, width.toFloat(), height.toFloat(), snap.dist) ?: return null
        return PlanetMath.modelAnchor(q, snap.yaw, snap.pitch)
    }

    private companion object {
        /** 与 [PlanetCamera] 俯仰钳一致（抓取反解在 UI 线程用）。 */
        const val PITCH_LIMIT = 1.25f
    }
}
