package com.situ.aichat.ui.world.interior

import com.situ.aichat.ui.world.gl.FreeRoamInput
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

/** 室内相机瞬时态快照（GL 线程每帧写·Compose 锚投影读·[InteriorCamera.snapshot]）。[tx]/[ty]/[tz] = 当前 target（W15：可平移）。 */
internal class InteriorCamSnapshot(
    val yaw: Float, val pitch: Float, val dist: Float,
    val tx: Float, val ty: Float, val tz: Float,
)

/**
 * 室内盒景「地图式自由漫游」相机（W9d 图纸 §4.5 底座 + W15 图纸 §4A.4 漫游改造·§9 禁改）。
 *
 * **W15 拖动语义变更（与小镇同构）**：单指拖动 = 地面锚定**平移**（target 由恒定改可平移·小边界 ±3.5·带惯性
 * + 橡皮筋 + 松手弹回）；双指旋转 = 转 yaw（**过 yaw 扇形钳 `[0.06,1.38]`**——敞顶盒景只有两面墙·扇形外即
 * 虚空·这是与大陆/小镇唯一的公式差异）、双指同向上下滑 = 调 pitch（过 `[0.16,1.14]` 钳）；捏合朝两指中点地面点
 * 缩。闲置 2.4s 后 **sin 轻摇**（非匀速·替代大陆的匀速慢旋·照抄现式不额外钳）。
 *
 * **线程纪律同 [com.situ.aichat.ui.world.town.TownCamera]**：触摸（UI 线程）只累积（synchronized）；积分只在
 * GL 线程 [integrate] 每帧执行写 [snapshot]（@Volatile）。intro 俯冲入场（yaw 恒 0.55）。帧率无关折算 `steps=dt*60`。
 */
internal class InteriorCamera(reduceMotion: Boolean, tiltDeadzonePx: Float) {

    private companion object {
        const val INITIAL_YAW = 0.55f
        const val LANDED_PITCH = 0.42f
        const val INTRO_PITCH = 0.98f
        const val FAR_DIST = 16.5f       // intro 起点
        const val LANDED_DIST = 11.5f    // intro 终点 / tDist 初值
        const val YAW_MIN = 0.06f
        const val YAW_MAX = 1.38f
        const val PITCH_MIN = 0.16f
        const val PITCH_MAX = 1.14f
        const val TDIST_MIN = 6.5f
        const val TDIST_MAX = 17f
        const val DAMPING = 0.93f
        const val IDLE_SECONDS = 2.4f
        const val SWAY_FREQ = 0.22f
        const val SWAY_AMP = 0.00045f
        const val INTRO_RATE = 1f / 90f
        const val FOLLOW_BASE = 0.92f     // 跟随 f = 1 − 0.92^steps（= 0.08@60）
        const val OVERZOOM_TRIGGER = 1.10f
        const val UP_HINT_DIST = 15.8f
        const val FOLLOW_DIST_EPS = 0.01f  // W15：跟随热判（同大陆值）
        const val FOLLOW_TARGET_EPS = 0.005f
        // ── W15 自由漫游平移（图纸 §4A.4·房间小 → 边界收紧）──
        const val PAN_BOUND = 3.5f         // 平移软边界（房间地板约 19×12·±3.5 足贴看蛋巢/角色/家具不出前景框）
        const val PAN_OVER_MAX = 1.5f      // 边缘软拦最大越界量
        const val PAN_RESIST = 0.35f       // 越界段拖动阻尼系数（橡皮筋）
        const val PAN_V_EPS = 0.01f        // 平移惯性热判阈（世界单位/帧）
        const val TILT_SENS = 0.004f       // 双指俯仰灵敏度（= 原 PITCH_SENS 同值·手感连续）
    }

    // ── target（W15：由恒定改可平移·初值沿用 demo:L319）──
    private val target = floatArrayOf(-0.5f, 1.05f, -0.5f)
    private val tTarget = floatArrayOf(-0.5f, 1.05f, -0.5f)

    // ── GL 线程私有态 ──
    private var yaw = INITIAL_YAW
    private var pitch = INTRO_PITCH
    private var dist = FAR_DIST
    @Volatile private var vpanX = 0f       // 平移惯性（UI 线程 catch 时清零 → @Volatile 保可见）
    @Volatile private var vpanZ = 0f
    private var idleT = 0f
    private var sceneT = 0f
    private var introT = 0f
    private var tDist = LANDED_DIST
    private var overZoom = 1f
    private var returnArmed = false
    private var cinPitch = 0f
    private var cinDist = 0f
    private var focalX = 0f                  // 捏合焦点地面点镜像（每帧从 frame 更新）
    private var focalZ = 0f
    private var hasFocal = false

    // ── UI 线程 → GL 线程 输入 ──
    private val input = FreeRoamInput(tiltDeadzonePx)
    private val lock = Any()

    @Volatile private var pointerDown = false
    @Volatile private var pinching = false
    @Volatile private var cinematic = false
    @Volatile private var velocityHot = false
    @Volatile private var followHot = false
    @Volatile private var introDone = false
    @Volatile private var returnRequested = false

    @Volatile
    var snapshot: InteriorCamSnapshot = InteriorCamSnapshot(INITIAL_YAW, INTRO_PITCH, FAR_DIST, -0.5f, 1.05f, -0.5f)
        private set

    init {
        if (reduceMotion) { pitch = LANDED_PITCH; dist = LANDED_DIST; introT = 1f }
        introDone = introT >= 1f
        snapshot = InteriorCamSnapshot(yaw, pitch, dist, target[0], target[1], target[2])
    }

    // ── 输入事件（UI 线程·薄委托到 [input]）──
    /** 单指平移增量（世界系·地面锚定·由 GLView 反投影算出）。 */
    fun onPanBy(wdx: Float, wdz: Float) = input.onPanBy(wdx, wdz)

    fun onPinchBy(ratio: Float) = input.onPinchBy(ratio)

    /** 双指旋转增量（rad·过死区后才计·应用处过 yaw 扇形钳）。 */
    fun onTwistBy(dAngle: Float) = input.onTwistBy(dAngle)

    /** 双指同向上下滑增量（px·过死区后才计）。 */
    fun onTiltBy(dyPx: Float) = input.onTiltBy(dyPx)

    /** 捏合焦点地面点（两指中点反投影）。 */
    fun setPinchFocal(x: Float, z: Float) = input.setPinchFocal(x, z)

    fun clearPinchFocal() = input.clearPinchFocal()

    fun setPointerDown(down: Boolean) {
        pointerDown = down
        if (down) { vpanX = 0f; vpanZ = 0f } // catch：接住惯性滑行中的地图
    }

    fun setPinching(active: Boolean) {
        pinching = active
        if (active) input.beginTwoFinger() else input.endTwoFinger()
    }

    /** cinematic 覆写（回小镇逆放·pitch/dist 覆写·yaw/target 冻结·忽略输入）。 */
    fun setCinematicPose(pitch: Float, dist: Float) = synchronized(lock) {
        cinematic = true; cinPitch = pitch; cinDist = dist
    }

    fun clearCinematic() = synchronized(lock) { cinematic = false }

    // ── 帧泵 / 覆盖层查询 ──
    fun wantsHighFps(): Boolean = pointerDown || pinching || velocityHot || followHot || !introDone
    fun isGesturing(): Boolean = pointerDown || pinching
    fun wantsUpHint(): Boolean = snapshot.dist > UP_HINT_DIST && introDone
    fun consumeReturnRequested(): Boolean {
        if (!returnRequested) return false
        returnRequested = false
        return true
    }

    /** 测试可观测：intro 进度（0..1）。 */
    fun introFraction(): Float = introT

    /** GL 线程每帧积分（demo:L376-387 语义 + intro 新造 + W15 §4A.4 自由漫游）。返回后 [snapshot] 已更新。 */
    fun integrate(dtSeconds: Float, reduceMotion: Boolean) {
        val steps = dtSeconds * 60f
        // cinematic 覆写（回小镇转场·yaw/target 冻结·清空输入防解锁突跳）。
        if (cinematic) {
            val cp: Float; val cd: Float
            synchronized(lock) { cp = cinPitch; cd = cinDist }
            input.clear()
            pitch = cp; dist = cd; vpanX = 0f; vpanZ = 0f
            velocityHot = false; followHot = false
            snapshot = InteriorCamSnapshot(yaw, pitch, dist, target[0], target[1], target[2])
            return
        }
        sceneT += dtSeconds

        val frame = input.consume()
        focalX = frame.focalX; focalZ = frame.focalZ; hasFocal = frame.hasFocal
        val down = pointerDown
        if (down || pinching) introT = 1f // 任何输入即接管 intro。

        // 捏合 → tDist（demo:L331/L342）+ overzoom-out（顶格外捏累积 ≥1.10 → 回小镇·§4.5）。
        val tDistAtCapBefore = tDist >= TDIST_MAX
        if (frame.pinch != 1f) {
            tDist = (tDist * frame.pinch).coerceIn(TDIST_MIN, TDIST_MAX)
            idleT = 0f
        }
        if (pinching && tDistAtCapBefore && frame.pinch > 1f) {
            overZoom *= frame.pinch
            if (overZoom >= OVERZOOM_TRIGGER && !returnArmed) { returnRequested = true; returnArmed = true }
        } else if (!pinching || tDist < TDIST_MAX) {
            overZoom = 1f; returnArmed = false
        }

        // 旋转（双指转 yaw·**过扇形钳**——与大陆/小镇唯一公式差异）/ 俯仰（双指上下滑·过 pitch 钳）。
        if (frame.twist != 0f) { yaw = (yaw + -frame.twist).coerceIn(YAW_MIN, YAW_MAX); idleT = 0f }
        if (frame.tiltDy != 0f) {
            pitch = (pitch + frame.tiltDy * TILT_SENS).coerceIn(PITCH_MIN, PITCH_MAX); idleT = 0f
        }

        // 平移（拖动中·地面锚定·惯性种子 = 末事件增量）。
        if (down && (frame.panDx != 0f || frame.panDz != 0f)) {
            applyPan(frame.panDx, frame.panDz)
            vpanX = frame.lastPanDx; vpanZ = frame.lastPanDz
            idleT = 0f
        }

        // intro 俯冲（yaw 恒 0.55）/ 平移惯性 / 闲置轻摇 / 松手回弹 / dist 跟随（demo:L382-385 + intro + W15）。
        if (introT < 1f) {
            introT = minOf(1f, introT + INTRO_RATE * steps)
            val k = 1f - (1f - introT).pow(3)
            dist = FAR_DIST + (LANDED_DIST - FAR_DIST) * k
            pitch = INTRO_PITCH + (LANDED_PITCH - INTRO_PITCH) * k
        } else {
            if (!down) {
                applyPan(vpanX * steps, vpanZ * steps)
                vpanX *= DAMPING.pow(steps); vpanZ *= DAMPING.pow(steps)
                idleT += dtSeconds
                if (!reduceMotion && idleT > IDLE_SECONDS) yaw += sin(sceneT * SWAY_FREQ) * SWAY_AMP * steps // 轻摇（非匀速）
                tTarget[0] = tTarget[0].coerceIn(-PAN_BOUND, PAN_BOUND)
                tTarget[2] = tTarget[2].coerceIn(-PAN_BOUND, PAN_BOUND)
            }
            val distBefore = dist
            dist += (tDist - dist) * (1f - FOLLOW_BASE.pow(steps))
            if (pinching && hasFocal && distBefore > 0f) {
                val r = dist / distBefore
                val k = 1f - r
                val moveX = (focalX - target[0]) * k; val moveZ = (focalZ - target[2]) * k
                target[0] += moveX; target[2] += moveZ; tTarget[0] += moveX; tTarget[2] += moveZ
                hardClampPan()
            }
        }
        introDone = introT >= 1f

        // 跟随 target（W15：恒执行·服务平移回弹与焦点补偿·帧率无关）。
        val ft = 1f - FOLLOW_BASE.pow(steps)
        for (i in 0..2) target[i] += (tTarget[i] - target[i]) * ft

        velocityHot = abs(vpanX) > PAN_V_EPS || abs(vpanZ) > PAN_V_EPS
        followHot = abs(tDist - dist) > FOLLOW_DIST_EPS ||
            abs(tTarget[0] - target[0]) > FOLLOW_TARGET_EPS ||
            abs(tTarget[1] - target[1]) > FOLLOW_TARGET_EPS ||
            abs(tTarget[2] - target[2]) > FOLLOW_TARGET_EPS
        snapshot = InteriorCamSnapshot(yaw, pitch, dist, target[0], target[1], target[2])
    }

    /** 地面锚定平移（拖动与惯性共用·逐轴橡皮筋 + 硬止·图纸 §4A.4）。y 轴永不动。 */
    private fun applyPan(dx: Float, dz: Float) {
        var mx = dx; var mz = dz
        if ((tTarget[0] > PAN_BOUND && mx > 0f) || (tTarget[0] < -PAN_BOUND && mx < 0f)) mx *= PAN_RESIST
        if ((tTarget[2] > PAN_BOUND && mz > 0f) || (tTarget[2] < -PAN_BOUND && mz < 0f)) mz *= PAN_RESIST
        target[0] += mx; target[2] += mz; tTarget[0] += mx; tTarget[2] += mz
        hardClampPan()
    }

    /** 平移四值硬止在 ±(PAN_BOUND+PAN_OVER_MAX)。 */
    private fun hardClampPan() {
        val lim = PAN_BOUND + PAN_OVER_MAX
        target[0] = target[0].coerceIn(-lim, lim)
        target[2] = target[2].coerceIn(-lim, lim)
        tTarget[0] = tTarget[0].coerceIn(-lim, lim)
        tTarget[2] = tTarget[2].coerceIn(-lim, lim)
    }
}
