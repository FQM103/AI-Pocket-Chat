package com.situ.aichat.ui.world.gl

import kotlin.math.abs

/**
 * 一帧自由漫游手势累积（[FreeRoamInput.consume] 产出·GL 线程消费）。
 * [panDx]/[panDz] = 本帧世界系平移增量累计；[lastPanDx]/[lastPanDz] = 末事件增量（惯性种子）；
 * [pinch] = span 比值乘积（无捏合 = 1）；[twist] = 双指角度增量累计（rad·过死区后才计）；
 * [tiltDy] = 双指中点垂直位移累计（px·过死区后才计）；[focalX]/[focalZ] = 捏合焦点地面点（最新值）。
 */
internal class FreeRoamFrame(
    val panDx: Float, val panDz: Float,
    val lastPanDx: Float, val lastPanDz: Float,
    val pinch: Float,
    val twist: Float,
    val tiltDy: Float,
    val focalX: Float, val focalZ: Float, val hasFocal: Boolean,
)

/**
 * 三盒景相机共用的线程安全手势累积器（W15 图纸 §4A.1）：UI 线程写（平移 / 捏合 / 旋转 / 俯仰 / 焦点地面点
 * + 双指死区闩锁），GL 线程 [consume] 一次性取走并复位。全字段单锁 synchronized；**不做任何物理**
 * （阻尼 / 钳位 / 边界全在相机 integrate）。
 *
 * twist / tilt 死区为**闩锁**：双指段内累积绝对量越阈后解锁，此后段内后续增量全数生效直到 [endTwoFinger]；
 * 阈内累积量丢弃不补（跨越阈值那次增量也丢弃）。两通道独立解锁。
 */
internal class FreeRoamInput(private val tiltDeadzonePx: Float) {

    private companion object {
        /** 双指旋转死区（rad ≈ 3.4°·防捏合时手指自然微转晃动画面·图纸 §4A.1）。 */
        const val TWIST_DEADZONE = 0.06f
    }

    private val lock = Any()

    private var panDx = 0f
    private var panDz = 0f
    private var lastPanDx = 0f
    private var lastPanDz = 0f
    private var pinch = 1f
    private var twist = 0f
    private var tiltDy = 0f
    private var focalX = 0f
    private var focalZ = 0f
    private var hasFocal = false

    // 双指死区闩锁（各通道独立·随 begin/end/clear 复位）。
    private var twistAccum = 0f
    private var twistUnlocked = false
    private var tiltAccum = 0f
    private var tiltUnlocked = false

    fun onPanBy(wdx: Float, wdz: Float) = synchronized(lock) {
        panDx += wdx; panDz += wdz; lastPanDx = wdx; lastPanDz = wdz
    }

    fun onPinchBy(ratio: Float) = synchronized(lock) { pinch *= ratio }

    /** 死区：双指段内 Σ|dAngle| > [TWIST_DEADZONE] 后开始累计（阈内累积量含跨阈那次丢弃不补）。 */
    fun onTwistBy(dAngle: Float) = synchronized(lock) {
        if (twistUnlocked) {
            twist += dAngle
        } else {
            twistAccum += abs(dAngle)
            if (twistAccum > TWIST_DEADZONE) twistUnlocked = true
        }
    }

    /** 死区：双指段内 Σ|dyPx| > [tiltDeadzonePx] 后开始累计（同 [onTwistBy]）。 */
    fun onTiltBy(dyPx: Float) = synchronized(lock) {
        if (tiltUnlocked) {
            tiltDy += dyPx
        } else {
            tiltAccum += abs(dyPx)
            if (tiltAccum > tiltDeadzonePx) tiltUnlocked = true
        }
    }

    fun setPinchFocal(x: Float, z: Float) = synchronized(lock) {
        focalX = x; focalZ = z; hasFocal = true
    }

    fun clearPinchFocal() = synchronized(lock) { hasFocal = false }

    /** 进入双指段：重置两死区闩锁与累计。 */
    fun beginTwoFinger() = synchronized(lock) {
        twistAccum = 0f; twistUnlocked = false
        tiltAccum = 0f; tiltUnlocked = false
    }

    /** 退出双指段：清 focal；闩锁复位。 */
    fun endTwoFinger() = synchronized(lock) {
        hasFocal = false
        twistAccum = 0f; twistUnlocked = false
        tiltAccum = 0f; tiltUnlocked = false
    }

    /** cinematic 用：全部累积清零 + focal 清 + 闩锁复位。 */
    fun clear() = synchronized(lock) {
        panDx = 0f; panDz = 0f; lastPanDx = 0f; lastPanDz = 0f
        pinch = 1f; twist = 0f; tiltDy = 0f
        hasFocal = false
        twistAccum = 0f; twistUnlocked = false
        tiltAccum = 0f; tiltUnlocked = false
    }

    /** synchronized 取走并复位（focal 保留最新值不复位，随 [endTwoFinger] / [clear] 清）。 */
    fun consume(): FreeRoamFrame = synchronized(lock) {
        val f = FreeRoamFrame(
            panDx, panDz, lastPanDx, lastPanDz, pinch, twist, tiltDy, focalX, focalZ, hasFocal,
        )
        panDx = 0f; panDz = 0f; lastPanDx = 0f; lastPanDz = 0f
        pinch = 1f; twist = 0f; tiltDy = 0f
        f
    }
}
