package com.situ.aichat.ui.world.planet

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.tan

/** 相机瞬时态快照（GL 线程每帧写·Compose 家乡标记投影读·[PlanetCamera.snapshot]）。 */
internal class CameraSnapshot(val yaw: Float, val pitch: Float, val dist: Float)

/**
 * 星球相机（W9a 图纸 §3.3·常量全部 demo 锁死·图纸 §9 禁改）。
 *
 * **线程纪律**：触摸事件（UI 线程）只经 [onDragBy]/[onPinchBy]/[setPointerDown]/[setPinching] 入队
 * （synchronized 累加器）；**积分只在 GL 线程** [integrate] 每帧执行（demo 单线程语义等价移植），积分后把
 * (yaw,pitch,dist) 写 [snapshot]（@Volatile）供 Compose 投影读。
 *
 * 帧率无关积分：demo 的 `0.94/帧`、`0.00045/帧` 均**以 60fps 为基准**（图纸 §3.3「@60」）。本块帧泵是
 * 60/30 多档（§3.4），故按真实经过时间折算 `steps = dt*60`（60fps → steps=1 与 demo 逐帧等价；30fps →
 * steps=2 保持真实角速度 0.027 rad/s 不减半）。惯性档恒在 60Hz（§3.4·|v|>阈=交互中），闲置自转在 30Hz。
 *
 * **跟手拖动（用户 2026-07-06 拍板·取代 demo 固定灵敏度）**：像素→角度换算不再用锁死常量，改为每帧按
 * 当前距离实时算 `sens = 2·(dist−R)·tan(FOV/2) / 视口高px`（R=1 单位球·FOV=[PlanetMath.FOV]）——即球面
 * 被按住的点与手指位移 1:1，任何缩放档手感一致。惯性/闲置自转机制保持既有拍板不变；惯性种子沿用同一换算。
 * 视口高未知（[setViewportHeight] 未调）时回退旧常量。
 *
 * **方向 = 球面粘手指（用户 2026-07-06 拍板·取代 2026-07-03「方向反转」）**：按住哪块地、哪块地跟着
 * 手指走（地球仪通用直觉）——模拟器实证旧反转方向是「家标记永远感觉在背面」体感 bug 的主因（W15.2）。
 *
 * **W15.3 真·抓取（用户 2026-07-06「参考成熟地球仪·一步到位」）**：单指拖动主通道升级为**锚定反解**——
 * [PlanetGLView] 在按下时把触点射线打到球面记锚（[PlanetMath.screenToSphere]+[PlanetMath.modelAnchor]），
 * 每个 MOVE 反解姿态（[PlanetMath.solveGrabPose]）经 [onGrabPose] 入队，积分帧直接落姿态、锚点严格钉在
 * 手指下（Google Earth/Cesium 同语义）。射线未命中（远缩点到星空）→ 回落本类原 delta 通道（[onDragBy]·
 * 1:1 换算保留）。惯性种子 = 抓取姿态的逐帧差，衰减/闲置/捏合/overpinch/cinematic 全部不变。
 */
internal class PlanetCamera {

    private companion object {
        const val INITIAL_YAW = 0.6f
        const val INITIAL_PITCH = -0.25f
        const val INITIAL_DIST = 3.1f
        const val YAW_SENS = 0.005f    // 仅视口高未知时的回退值
        const val PITCH_SENS = 0.004f  // 同上（回退值）
        const val SPHERE_RADIUS = 1f   // 单位球（demo 几何·MARKER_LIFT=1.01 同源）
        const val PITCH_LIMIT = 1.25f
        const val DIST_MIN = 1.9f
        const val DIST_MAX = 6.4f
        const val DAMPING = 0.94f
        const val IDLE_SECONDS = 1.6f
        const val IDLE_SPIN = 0.00045f // rad/帧@60（= 0.027 rad/s）
        const val INTERACT_V = 0.0002f // |v| 超此=交互中（帧泵 60Hz）
        const val OVERPINCH_TRIGGER = 0.90f // 顶格内捏累积 ≤ 此 → 触发进大陆（图纸 §3.5·E10）
    }

    // ── GL 线程私有态 ──
    private var yaw = INITIAL_YAW
    private var pitch = INITIAL_PITCH
    private var dist = INITIAL_DIST
    private var vyaw = 0f
    private var vpitch = 0f
    private var idleT = 0f

    // ── UI 线程 → GL 线程 输入累加器（lock 保护）──
    private val lock = Any()
    private var dragDxSum = 0f
    private var dragDySum = 0f
    private var lastDx = 0f
    private var lastDy = 0f
    private var pinchAccum = 1f
    // W15.3 抓取姿态通道（UI 线程反解好绝对姿态·GL 线程积分帧落地）
    private var grabYaw = 0f
    private var grabPitch = 0f
    private var grabFresh = false

    @Volatile private var pointerDown = false
    @Volatile private var pinching = false
    /** 视口高（px·触摸坐标同单位·UI 线程 onSizeChanged 写·GL 线程积分读）——跟手换算分母。 */
    @Volatile private var viewportHeightPx = 0f
    /** 上一帧积分判定的高帧率需求（含惯性未衰减·帧泵 30/60 选档读）。 */
    @Volatile private var velocityHot = false

    // ── W9b 俯冲转场态（加法·图纸 §3.5·既有积分零改）──
    /** cinematic 覆写：转场镜头期间积分只吐 [cinYaw]/[cinPitch]/[cinDist]、忽略一切输入累加。 */
    @Volatile private var cinematic = false
    private var cinYaw = 0f
    private var cinPitch = 0f
    private var cinDist = 0f
    /** overpinch-in：顶格(DIST_MIN)仍继续内捏时累积的比率乘积（≤ [OVERPINCH_TRIGGER] 触发进大陆）。 */
    private var overPinch = 1f
    /** 本次捏合手势已触发过俯冲（一次性·防同一手势内重复触发）。 */
    private var diveArmed = false
    /** 待主线程消费的进大陆信号（GL 线程置·[consumeDiveRequested] 取清）。 */
    @Volatile private var diveRequested = false

    @Volatile
    var snapshot: CameraSnapshot = CameraSnapshot(INITIAL_YAW, INITIAL_PITCH, INITIAL_DIST)
        private set

    // ── 输入事件（UI 线程）──
    fun onDragBy(dx: Float, dy: Float) = synchronized(lock) {
        dragDxSum += dx; dragDySum += dy; lastDx = dx; lastDy = dy
    }

    fun onPinchBy(ratio: Float) = synchronized(lock) { pinchAccum *= ratio }

    /** W15.3：抓取反解出的绝对姿态入队（UI 线程·每 MOVE 一次·覆盖式取最新）。 */
    fun onGrabPose(yaw: Float, pitch: Float) = synchronized(lock) {
        grabYaw = yaw; grabPitch = pitch; grabFresh = true
    }

    fun setPointerDown(down: Boolean) { pointerDown = down }

    fun setPinching(active: Boolean) { pinching = active }

    /** 设视口高（px·与触摸事件同坐标系）——跟手 1:1 换算的分母（UI 线程 onSizeChanged 调）。 */
    fun setViewportHeight(px: Float) { viewportHeightPx = px }

    /** 帧泵：是否需要 60Hz（指针按下 / 捏合 / 惯性未止）。否则环境态走 30Hz。 */
    fun wantsHighFps(): Boolean = pointerDown || pinching || velocityHot

    /** 帧泵静帧模式：当前是否有手势在进行（决定是否补渲染一帧）。 */
    fun isGesturing(): Boolean = pointerDown || pinching

    /**
     * GL 线程每帧积分（demo:L215-217 语义·[dtSeconds] 真实经过秒·[reduceMotion] 时无闲置自转）。
     * 返回后 [snapshot] 已更新。
     */
    fun integrate(dtSeconds: Float, reduceMotion: Boolean) {
        val steps = dtSeconds * 60f
        // cinematic（转场镜头·加法·图纸 §3.5）：覆写积分输出、清空输入累加防解锁突跳、忽略惯性/闲置。
        if (cinematic) {
            val cy: Float; val cp: Float; val cd: Float
            synchronized(lock) {
                dragDxSum = 0f; dragDySum = 0f; lastDx = 0f; lastDy = 0f; pinchAccum = 1f; grabFresh = false
                cy = cinYaw; cp = cinPitch; cd = cinDist
            }
            yaw = cy; pitch = cp; dist = cd; vyaw = 0f; vpitch = 0f; idleT = 0f
            velocityHot = false
            snapshot = CameraSnapshot(cy, cp, cd)
            return
        }
        var dx: Float; var dy: Float; var ldx: Float; var ldy: Float; var pinch: Float
        var gYaw: Float; var gPitch: Float; var gFresh: Boolean
        synchronized(lock) {
            dx = dragDxSum; dy = dragDySum; ldx = lastDx; ldy = lastDy; pinch = pinchAccum
            gYaw = grabYaw; gPitch = grabPitch; gFresh = grabFresh
            dragDxSum = 0f; dragDySum = 0f; pinchAccum = 1f; grabFresh = false
        }
        val down = pointerDown

        val atFloorBefore = dist <= DIST_MIN // 本帧前是否已顶格（到底那一捏不计 overpinch·E10）
        if (pinch != 1f) {
            dist = (dist * pinch).coerceIn(DIST_MIN, DIST_MAX)
            idleT = 0f
        }
        // overpinch-in（加法·图纸 §3.5·拍板① 逆向）：捏合中「已顶格」仍继续内捏(pinch<1)则累积比率；
        // 累积 ≤ OVERPINCH_TRIGGER → 一次性置进大陆信号；离底 / 松手 → 复位重新武装。
        if (pinching && atFloorBefore && pinch < 1f) {
            overPinch *= pinch
            if (overPinch <= OVERPINCH_TRIGGER && !diveArmed) {
                diveRequested = true; diveArmed = true
            }
        } else if (!pinching || dist > DIST_MIN) {
            overPinch = 1f; diveArmed = false
        }
        if (down && gFresh) {
            // W15.3 抓取主通道：UI 线程反解好的绝对姿态直接落地——锚点严格钉在手指下；
            // 惯性种子 = 姿态逐帧差（语义对齐 delta 通道「最后一个事件」）。
            vyaw = gYaw - yaw
            vpitch = gPitch - pitch
            yaw = gYaw
            pitch = gPitch.coerceIn(-PITCH_LIMIT, PITCH_LIMIT)
            idleT = 0f
        } else if (down && (dx != 0f || dy != 0f)) {
            // 兜底 delta 通道（射线未命中球面时）：跟手 1:1（2026-07-06 拍板）——屏高映射球面正面深度处
            // 的世界宽度 2·(dist−R)·tan(FOV/2)，除以屏高 px = 每像素球面弧长（R=1 → 弧长即弧度）。
            val h = viewportHeightPx
            val yawSens = if (h > 0f) 2f * (dist - SPHERE_RADIUS) * tan(PlanetMath.FOV / 2f) / h else YAW_SENS
            val pitchSens = if (h > 0f) yawSens else PITCH_SENS
            yaw += dx * yawSens     // 正号=球面粘手指（2026-07-06 拍板·取代 07-03 反转）
            pitch = (pitch + dy * pitchSens).coerceIn(-PITCH_LIMIT, PITCH_LIMIT)
            vyaw = ldx * yawSens    // 惯性种子=最后一个事件（demo:L185）·沿用跟手换算
            vpitch = ldy * pitchSens
            idleT = 0f
        }
        if (!down) {
            yaw += vyaw * steps
            pitch = (pitch + vpitch * steps).coerceIn(-PITCH_LIMIT, PITCH_LIMIT)
            vyaw *= DAMPING.pow(steps)
            vpitch *= DAMPING.pow(steps)
            idleT += dtSeconds
            if (!reduceMotion && idleT > IDLE_SECONDS) yaw += IDLE_SPIN * steps
        }

        velocityHot = abs(vyaw) > INTERACT_V || abs(vpitch) > INTERACT_V
        snapshot = CameraSnapshot(yaw, pitch, dist)
    }

    // ── W9b 俯冲转场 API（加法·图纸 §3.5）──

    /** 设 cinematic 覆写姿态（转场镜头·UI 线程逐帧 lerp 调）：积分只吐此姿态、忽略输入。 */
    fun setCinematicPose(yaw: Float, pitch: Float, dist: Float) = synchronized(lock) {
        cinematic = true; cinYaw = yaw; cinPitch = pitch; cinDist = dist
    }

    /** 解除 cinematic（转场结束）：积分从最后 cinematic 姿态平滑续跑。 */
    fun clearCinematic() = synchronized(lock) { cinematic = false }

    /** 恢复出发前姿态（回星球时·须在首次 [integrate] 前调·令重建的星球场景从原姿态起）。 */
    fun restore(yaw: Float, pitch: Float, dist: Float) {
        this.yaw = yaw; this.pitch = pitch; this.dist = dist
        vyaw = 0f; vpitch = 0f; idleT = 0f
        snapshot = CameraSnapshot(yaw, pitch, dist)
    }

    /** 取并清进大陆信号（主线程·overpinch 触发后消费一次）。 */
    fun consumeDiveRequested(): Boolean {
        if (!diveRequested) return false
        diveRequested = false
        return true
    }
}
