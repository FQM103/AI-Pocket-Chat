package com.situ.aichat.ui.world.continent

import com.situ.aichat.ui.world.gl.FreeRoamInput
import kotlin.math.abs
import kotlin.math.pow

/** 大陆相机瞬时态快照（GL 线程每帧写·Compose 站位投影读·[ContinentCamera.snapshot]）。[tx]/[ty]/[tz] = 当前 target。 */
internal class ContinentCamSnapshot(
    val yaw: Float, val pitch: Float, val dist: Float,
    val tx: Float, val ty: Float, val tz: Float,
)

/**
 * 大陆盒景「地图式自由漫游」相机（W9b 图纸 §3.4 底座 + W15 图纸 §4A.2 漫游改造·§9 禁改）。
 *
 * **W15 拖动语义变更**：单指拖动 = 地面锚定**平移**（target 跟手移动·带惯性 + 边缘橡皮筋软拦 + 松手弹回）；
 * 双指旋转 = 转 yaw、双指同向上下滑 = 调 pitch（均带死区·由 [FreeRoamInput] 累积）；捏合朝两指中点地面点缩。
 * 旋转 / 俯仰不再有惯性（取代 W9b 的绕心 yaw/pitch 拖动）；其余机制（intro/overzoom/焦点距离/cinematic/
 * restore/闲置慢旋/跟随）一字不改。
 *
 * **线程纪律同 [com.situ.aichat.ui.world.planet.PlanetCamera]**：触摸事件（UI 线程）只经 [FreeRoamInput]
 * 累积 / 待处理焦点入队（synchronized）；积分只在 GL 线程 [integrate] 每帧执行，写 [snapshot]（@Volatile）供
 * Compose 投影读。帧率无关：demo 的 `1/110`（intro）、`0.93`（惯性）、`0.00035`（闲置）均以 60fps 为基准，
 * 按 `steps = dt*60` 折算（30fps→steps=2·跟随双步 = 1−0.92²=0.1536 与逐帧 0.08 复合等价）。
 */
internal class ContinentCamera(reduceMotion: Boolean, tiltDeadzonePx: Float) {

    private companion object {
        const val INITIAL_YAW = 0.78f
        const val LANDED_PITCH = 0.72f
        const val INTRO_PITCH = 1.12f
        const val FAR_DIST = 95f          // intro 起点（高空）
        const val LANDED_DIST = 34f       // intro 终点 / tDist 初值
        const val PITCH_MIN = 0.30f
        const val PITCH_MAX = 1.22f
        const val TDIST_MIN = 8f
        const val TDIST_MAX = 60f
        const val DAMPING = 0.93f          // 平移惯性衰减/帧@60（非星球 0.94）
        const val IDLE_SECONDS = 2.2f
        const val IDLE_SPIN = 0.00035f
        const val INTRO_RATE = 1f / 110f   // introT/帧@60
        const val FOLLOW_BASE = 0.92f      // 跟随 f = 1 − 0.92^steps（= 0.08@60）
        const val FOCUS_DIST = 10.5f       // 站点聚焦 tDist
        const val REST_TDIST_MIN = 26f     // 空点复位 tDist=max(tDist,26)
        const val CLOSE_TDIST = 34f        // 卡片关闭 / 换区 tDist
        const val OVERZOOM_TRIGGER = 1.10f // 顶格外捏累积 ≥ 此 → 回星球（E8）
        const val TOWN_DIVE_TRIGGER = 0.90f // W9c 加法：顶底内捏累积 ≤ 此 → 进小镇（§3.4 路径 B·E6）
        const val UP_HINT_DIST = 50f
        const val FOLLOW_DIST_EPS = 0.01f
        const val FOLLOW_TARGET_EPS = 0.005f
        // ── W15 自由漫游平移（图纸 §4A.2）──
        const val PAN_BOUND = 18f          // 平移软边界（站位散布 ±14 + 4 余量·仍在台座半宽 23 内）
        const val PAN_OVER_MAX = 4f        // 边缘软拦最大越界量（硬止 ±22 = 台座边缘留 1）
        const val PAN_RESIST = 0.35f       // 越界段拖动阻尼系数（橡皮筋）
        const val PAN_V_EPS = 0.01f        // 平移惯性热判阈（世界单位/帧）
        const val TILT_SENS = 0.004f       // 双指俯仰灵敏度（= 原 PITCH_SENS 同值·手感连续）
    }

    // ── GL 线程私有态 ──
    private var yaw = INITIAL_YAW
    private var pitch = INTRO_PITCH
    private var dist = FAR_DIST
    @Volatile private var vpanX = 0f       // 平移惯性（UI 线程 catch 时清零 → @Volatile 保可见）
    @Volatile private var vpanZ = 0f
    private var idleT = 0f
    private var introT = 0f
    private val target = floatArrayOf(0f, 1.2f, 0f)
    private val tTarget = floatArrayOf(0f, 1.2f, 0f)
    private var tDist = LANDED_DIST
    private var overZoom = 1f
    private var returnArmed = false
    private var townOverPinch = 1f          // W9c 加法：顶底继续内捏累积（≤0.90 进小镇）
    private var townDiveArmed = false
    private var cinPitch = 0f
    private var cinDist = 0f
    private var focalX = 0f                  // 捏合焦点地面点镜像（每帧从 frame 更新）
    private var focalZ = 0f
    private var hasFocal = false

    // ── UI 线程 → GL 线程 输入 ──
    private val input = FreeRoamInput(tiltDeadzonePx)
    private val lock = Any()
    private var pendingTTarget: FloatArray? = null
    private var pendingTDistExact: Float? = null
    private var pendingTDistAtLeast: Float? = null

    @Volatile private var pointerDown = false
    @Volatile private var pinching = false
    @Volatile private var cinematic = false
    @Volatile private var velocityHot = false
    @Volatile private var followHot = false
    @Volatile private var introDone = false
    @Volatile private var returnRequested = false
    @Volatile private var townDiveRequested = false   // W9c 加法：进小镇信号（主线程消费）
    @Volatile private var tDistMirror = LANDED_DIST    // W9c 加法：tDist 镜像（回大陆快照恢复读）

    @Volatile
    var snapshot: ContinentCamSnapshot = ContinentCamSnapshot(INITIAL_YAW, INTRO_PITCH, FAR_DIST, 0f, 1.2f, 0f)
        private set

    init {
        if (reduceMotion) { pitch = LANDED_PITCH; dist = LANDED_DIST; introT = 1f }
        introDone = introT >= 1f
        snapshot = ContinentCamSnapshot(yaw, pitch, dist, target[0], target[1], target[2])
    }

    // ── 输入事件（UI 线程·薄委托到 [input]）──
    /** 单指平移增量（世界系·地面锚定·由 GLView 反投影算出）。 */
    fun onPanBy(wdx: Float, wdz: Float) = input.onPanBy(wdx, wdz)

    fun onPinchBy(ratio: Float) = input.onPinchBy(ratio)

    /** 双指旋转增量（rad·过死区后才计）。 */
    fun onTwistBy(dAngle: Float) = input.onTwistBy(dAngle)

    /** 双指同向上下滑增量（px·过死区后才计）。 */
    fun onTiltBy(dyPx: Float) = input.onTiltBy(dyPx)

    /** 捏合焦点地面点（两指中点反投影）。 */
    fun setPinchFocal(x: Float, z: Float) = input.setPinchFocal(x, z)

    fun clearPinchFocal() = input.clearPinchFocal()

    fun setPointerDown(down: Boolean) {
        pointerDown = down
        if (down) { vpanX = 0f; vpanZ = 0f } // catch：接住惯性滑行中的地图（标准手感）
    }

    fun setPinching(active: Boolean) {
        pinching = active
        if (active) input.beginTwoFinger() else input.endTwoFinger()
    }

    /** 站点聚焦（demo:L334·tTarget=站点·tDist=10.5）。 */
    fun focusSite(x: Float, y: Float, z: Float) = synchronized(lock) {
        pendingTTarget = floatArrayOf(x, y, z); pendingTDistExact = FOCUS_DIST; pendingTDistAtLeast = null
    }

    /** 空点复位（W15：只调距 tDist=max(tDist,26)·**不再回中**·视野留原地）。 */
    fun clearFocus() = synchronized(lock) {
        pendingTDistAtLeast = REST_TDIST_MIN; pendingTDistExact = null; pendingTTarget = null
    }

    /** 卡片 ✕ 关闭（W15：只调距 tDist=34·**不再回中**·视野留在刚看的站点）。 */
    fun closeSheet() = synchronized(lock) {
        pendingTDistExact = CLOSE_TDIST; pendingTDistAtLeast = null; pendingTTarget = null
    }

    /** 换区重置（W15：**保持回中**·新区从全景开始·独立实现不再别名 closeSheet）。 */
    fun resetForRegion() = synchronized(lock) {
        pendingTTarget = floatArrayOf(0f, 1.2f, 0f); pendingTDistExact = CLOSE_TDIST; pendingTDistAtLeast = null
    }

    /** cinematic 覆写（回星球逆放·pitch/dist 覆写·yaw/target 冻结·忽略输入）。 */
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

    /** GL 线程每帧积分（demo:L361-370 语义 + W15 §4A.2 自由漫游）。返回后 [snapshot] 已更新。 */
    fun integrate(dtSeconds: Float, reduceMotion: Boolean) {
        val steps = dtSeconds * 60f
        // cinematic 覆写（回星球转场·yaw/target 冻结·清空输入防解锁突跳）。
        if (cinematic) {
            val cp: Float; val cd: Float
            synchronized(lock) {
                pendingTTarget = null; pendingTDistExact = null; pendingTDistAtLeast = null
                cp = cinPitch; cd = cinDist
            }
            input.clear()
            pitch = cp; dist = cd; vpanX = 0f; vpanZ = 0f
            velocityHot = false; followHot = false
            snapshot = ContinentCamSnapshot(yaw, pitch, dist, target[0], target[1], target[2])
            return
        }

        val frame = input.consume()
        focalX = frame.focalX; focalZ = frame.focalZ; hasFocal = frame.hasFocal
        var focusT: FloatArray?; var distExact: Float?; var distAtLeast: Float?
        synchronized(lock) {
            focusT = pendingTTarget; distExact = pendingTDistExact; distAtLeast = pendingTDistAtLeast
            pendingTTarget = null; pendingTDistExact = null; pendingTDistAtLeast = null
        }
        val down = pointerDown
        if (down || pinching) introT = 1f // 任何输入即接管 intro（demo:L302,321）

        // 焦点命令（选中 / 空点 / 关卡 / 换区）。
        focusT?.let { tTarget[0] = it[0]; tTarget[1] = it[1]; tTarget[2] = it[2] }
        distExact?.let { tDist = it }
        distAtLeast?.let { tDist = maxOf(tDist, it) }

        // 捏合 → tDist（demo:L311）+ overzoom-out（顶格外捏累积 ≥1.10 → 回星球·§3.4·E8）。
        val tDistAtCapBefore = tDist >= TDIST_MAX
        val tDistAtFloorBefore = tDist <= TDIST_MIN // W9c 加法：进小镇 overzoom-in 判据（到底那一捏不计·E6）
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
        // W9c 加法（§3.4 路径 B·PlanetCamera overpinch-in 同构）：顶底继续内捏累积 ≤0.90 → 进小镇；离底/松手复位。
        if (pinching && tDistAtFloorBefore && frame.pinch < 1f) {
            townOverPinch *= frame.pinch
            if (townOverPinch <= TOWN_DIVE_TRIGGER && !townDiveArmed) { townDiveRequested = true; townDiveArmed = true }
        } else if (!pinching || tDist > TDIST_MIN) {
            townOverPinch = 1f; townDiveArmed = false
        }

        // 旋转（双指转 yaw·符号见图纸 §4A.2 步 4）/ 俯仰（双指上下滑）。
        if (frame.twist != 0f) { yaw += -frame.twist; idleT = 0f }
        if (frame.tiltDy != 0f) {
            pitch = (pitch + frame.tiltDy * TILT_SENS).coerceIn(PITCH_MIN, PITCH_MAX); idleT = 0f
        }

        // 平移（拖动中·地面锚定·惯性种子 = 末事件增量）。
        if (down && (frame.panDx != 0f || frame.panDz != 0f)) {
            applyPan(frame.panDx, frame.panDz)
            vpanX = frame.lastPanDx; vpanZ = frame.lastPanDz
            idleT = 0f
        }

        // intro 俯冲 / 平移惯性 / 闲置慢旋 / 松手回弹 / dist 跟随（demo:L361-367 + W15）。
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
                if (!reduceMotion && idleT > IDLE_SECONDS) yaw += IDLE_SPIN * steps
                // 松手回弹：tTarget 钳回软边界，target 经跟随滤波弹回（软弹簧）。
                tTarget[0] = tTarget[0].coerceIn(-PAN_BOUND, PAN_BOUND)
                tTarget[2] = tTarget[2].coerceIn(-PAN_BOUND, PAN_BOUND)
            }
            // dist 跟随 + 焦点补偿（保捏合焦点地面点定影·图纸 §4A.2 步 9）。
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

        // 跟随 target（demo:L368-370·恒执行·服务焦点命令与回弹·帧率无关）。
        val ft = 1f - FOLLOW_BASE.pow(steps)
        for (i in 0..2) target[i] += (tTarget[i] - target[i]) * ft

        velocityHot = abs(vpanX) > PAN_V_EPS || abs(vpanZ) > PAN_V_EPS
        followHot = abs(tDist - dist) > FOLLOW_DIST_EPS ||
            abs(tTarget[0] - target[0]) > FOLLOW_TARGET_EPS ||
            abs(tTarget[1] - target[1]) > FOLLOW_TARGET_EPS ||
            abs(tTarget[2] - target[2]) > FOLLOW_TARGET_EPS
        tDistMirror = tDist // W9c 加法：随快照同步 tDist 镜像（回大陆恢复读）
        snapshot = ContinentCamSnapshot(yaw, pitch, dist, target[0], target[1], target[2])
    }

    /** 地面锚定平移（拖动与惯性共用·逐轴橡皮筋 + 硬止·图纸 §4A.2）。y 轴永不动。 */
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

    // ── W9c 进小镇 / 回大陆恢复（加法·图纸 §3.4/§3.5·既有积分零改）──

    /** 取并清进小镇信号（主线程·overpinch-in 触发后消费一次·由 ContinentSceneView 按选中态决定是否转发）。 */
    fun consumeTownDiveRequested(): Boolean {
        if (!townDiveRequested) return false
        townDiveRequested = false
        return true
    }

    /** 当前 tDist 镜像（进小镇时随快照一并存·回大陆恢复用）。 */
    fun currentTDist(): Float = tDistMirror

    /** 回大陆恢复出发前姿态（含 target/tDist·须在首次 [integrate] 前调·令重建的大陆场景从原姿态起·landed 态）。 */
    fun restoreSnapshot(s: ContinentCamSnapshot, tDistValue: Float) {
        yaw = s.yaw; pitch = s.pitch; dist = s.dist
        target[0] = s.tx; target[1] = s.ty; target[2] = s.tz
        tTarget[0] = s.tx; tTarget[1] = s.ty; tTarget[2] = s.tz
        tDist = tDistValue; tDistMirror = tDistValue
        introT = 1f; introDone = true; vpanX = 0f; vpanZ = 0f
        snapshot = ContinentCamSnapshot(yaw, pitch, dist, target[0], target[1], target[2])
    }
}
