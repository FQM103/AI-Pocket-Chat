package com.situ.aichat.ui.world.continent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ContinentCamera] T1-3（W9b 底座 §5 + W15 图纸 §5 E3/E4/E5/E7/E8/E10/E14·§7·断言从规格独立反推）：
 * 保留系（intro 俯冲金标 / 接管 / reduceMotion / tDist 钳 / 帧率无关跟随 / overzoom-out / townOverPinch /
 * focusSite / cinematic）改输入源后全绿；W15 漫游系（onTiltBy 过死区调 pitch / 平移惯性 0.93 / 地面锚定平移
 * lockstep / 边缘橡皮筋 + 硬止 + 松手弹回 / catch / 焦点缩放 / twist 负增 / 空点·关卡不回中·换区回中）。
 * 以 1/60s 步进（steps=1）等价 demo 逐帧。
 */
class ContinentCameraTest {

    private val frame = 1f / 60f
    private val eps = 1e-4f
    private fun cam(reduce: Boolean = false) = ContinentCamera(reduce, tiltDeadzonePx = 12f)

    // ─────────────────────────── intro 俯冲曲线 + 接管 ───────────────────────────

    @Test
    fun intro_curveGoldenAt55Steps() {
        val cam = cam()
        repeat(55) { cam.integrate(frame, reduceMotion = false) } // introT=0.5 → k=0.875
        assertEquals(41.625f, cam.snapshot.dist, 0.05f)
        assertEquals(0.77f, cam.snapshot.pitch, 0.005f)
    }

    @Test
    fun input_takesOverIntroImmediately() {
        val byPointer = cam()
        repeat(5) { byPointer.integrate(frame, reduceMotion = false) }
        assertTrue("接管前 intro 未完成", byPointer.introFraction() < 1f)
        byPointer.setPointerDown(true); byPointer.integrate(frame, reduceMotion = false)
        assertEquals("单指按下即 introT=1", 1f, byPointer.introFraction(), 0f)

        val byPinch = cam()
        repeat(5) { byPinch.integrate(frame, reduceMotion = false) }
        byPinch.setPinching(true); byPinch.integrate(frame, reduceMotion = false)
        assertEquals("双指即 introT=1", 1f, byPinch.introFraction(), 0f)
    }

    @Test
    fun reduceMotion_startsLanded_noIntro() {
        val cam = cam(reduce = true)
        assertEquals("reduce 无 intro", 1f, cam.introFraction(), 0f)
        assertEquals(34f, cam.snapshot.dist, eps)
        assertEquals(0.72f, cam.snapshot.pitch, eps)
    }

    // ─────────────────────────── 俯仰（双指上下滑过死区）/ tDist 钳位 ───────────────────────────

    @Test
    fun pitch_clampsTo0_30and1_22_viaTilt() {
        val up = cam(reduce = true)
        up.setPinching(true)
        up.onTiltBy(20f)      // 过死区（12dp）解锁·跨阈那次丢弃
        up.onTiltBy(100000f)  // 解锁后全量 → pitch 上顶
        up.integrate(frame, reduceMotion = true)
        assertEquals(1.22f, up.snapshot.pitch, eps)

        val down = cam(reduce = true)
        down.setPinching(true)
        down.onTiltBy(-20f)
        down.onTiltBy(-100000f)
        down.integrate(frame, reduceMotion = true)
        assertEquals(0.30f, down.snapshot.pitch, eps)
    }

    @Test
    fun tDist_clampsTo8and60() {
        val near = cam(reduce = true)
        near.onPinchBy(0.0001f); repeat(200) { near.integrate(frame, reduceMotion = true) }
        assertEquals(8f, near.snapshot.dist, 0.1f)
        val far = cam(reduce = true)
        far.onPinchBy(10000f); repeat(200) { far.integrate(frame, reduceMotion = true) }
        assertEquals(60f, far.snapshot.dist, 0.1f)
    }

    // ─────────────────────────── 平移惯性 0.93 / 跟随帧率无关 / 闲置 ───────────────────────────

    @Test
    fun inertia_decaysBy0_93PerFrame_pan() {
        val cam = cam(reduce = true)
        cam.setPointerDown(true); cam.onPanBy(3f, 0f); cam.integrate(frame, reduceMotion = true) // tx→3·vpanX=3
        cam.setPointerDown(false)
        var prev = cam.snapshot.tx
        val deltas = mutableListOf<Float>()
        repeat(4) { cam.integrate(frame, reduceMotion = true); val x = cam.snapshot.tx; deltas.add(x - prev); prev = x }
        assertEquals("首帧惯性 = 释放速度 3", 3f, deltas[0], eps)
        assertEquals(0.93f, deltas[1] / deltas[0], 1e-3f)
        assertEquals(0.93f, deltas[2] / deltas[1], 1e-3f)
    }

    @Test
    fun follow_frameRateIndependent_doubleStepIs0_1536() {
        val cam = cam(reduce = true) // introT=1
        cam.focusSite(10f, 1.2f, 0f)
        cam.integrate(2f / 60f, reduceMotion = true) // steps=2 → f = 1 − 0.92² = 0.1536
        assertEquals(0f + (10f - 0f) * 0.1536f, cam.snapshot.tx, 1e-3f)
    }

    @Test
    fun idleDrift_afterEnoughIdle_andSuppressedUnderReduce() {
        val cam = cam()
        repeat(400) { cam.integrate(frame, reduceMotion = false) }
        assertTrue("越 2.2s 闲置 → 漂移", cam.snapshot.yaw > 0.78f)
        val r = cam(reduce = true)
        repeat(400) { r.integrate(frame, reduceMotion = true) }
        assertEquals("reduce 无闲置漂移", 0.78f, r.snapshot.yaw, eps)
    }

    // ─────────────────────────── W15 地面锚定平移（lockstep / 橡皮筋 / catch）───────────────────────────

    @Test
    fun pan_movesTargetAndGoalInLockstep() {
        // 从静止拖：target 与 tTarget 同步位移（follow 贡献 0），tx=+2 / tz=−3。
        val a = cam(reduce = true)
        a.setPointerDown(true); a.onPanBy(2f, -3f); a.integrate(frame, reduceMotion = true)
        assertEquals(2f, a.snapshot.tx, eps)
        assertEquals(-3f, a.snapshot.tz, eps)

        // 聚焦追赶间隙保持：制造 target≠tTarget 后再拖，applyPan 同步位移二者 → 间隙不变，follow 照常收 8%。
        val b = cam(reduce = true)
        b.focusSite(10f, 1.2f, 0f); b.integrate(frame, reduceMotion = true) // target[0]=0.8·tTarget[0]=10（间隙 9.2）
        b.setPointerDown(true); b.onPanBy(1f, 0f); b.integrate(frame, reduceMotion = true)
        // 期望：(0.8+1) + (10+1 − (0.8+1))·0.08 = 1.8 + 9.2·0.08 = 2.536
        assertEquals("间隙保持 → 2.536", 2.536f, b.snapshot.tx, 5e-3f)
    }

    @Test
    fun pan_rubberBeyondBound_thenSpringsBack() {
        val cam = cam(reduce = true)
        cam.setPointerDown(true)
        cam.onPanBy(19f, 0f); cam.integrate(frame, reduceMotion = true)  // 0→19（未越界·无阻尼）
        assertEquals(19f, cam.snapshot.tx, 1e-3f)
        cam.onPanBy(2f, 0f); cam.integrate(frame, reduceMotion = true)   // 越界段：2×0.35=0.7 → 19.7
        assertEquals("越界增量 ×0.35", 19.7f, cam.snapshot.tx, 1e-3f)
        cam.onPanBy(100f, 0f); cam.integrate(frame, reduceMotion = true) // 硬止 ±(18+4)=22
        cam.onPanBy(0.01f, 0f); cam.integrate(frame, reduceMotion = true) // 末事件极小 → 惯性种子≈0
        assertEquals("硬止 22", 22f, cam.snapshot.tx, 0.05f)
        cam.setPointerDown(false)
        repeat(120) { cam.integrate(frame, reduceMotion = true) }
        assertEquals("松手弹回软边界 18", 18f, cam.snapshot.tx, 0.3f)
    }

    @Test
    fun pan_catchStopsInertia() {
        val cam = cam(reduce = true)
        cam.setPointerDown(true); cam.onPanBy(5f, 0f); cam.integrate(frame, reduceMotion = true) // vpanX=5
        cam.setPointerDown(false); cam.integrate(frame, reduceMotion = true) // 惯性一帧 → tx=10
        cam.setPointerDown(true); cam.integrate(frame, reduceMotion = true)  // catch：vpanX 清零·down 停惯性
        val caught = cam.snapshot.tx
        cam.setPointerDown(false)
        repeat(5) { cam.integrate(frame, reduceMotion = true) }
        assertEquals("catch 后无残余惯性", caught, cam.snapshot.tx, eps)
    }

    // ─────────────────────────── W15 焦点缩放 / twist ───────────────────────────

    @Test
    fun focalZoom_pullsTowardFocalGround() {
        val cam = cam(reduce = true)
        cam.setPointerDown(true); cam.setPinching(true); cam.setPinchFocal(5f, 0f)
        val xs = mutableListOf<Float>()
        repeat(30) {
            cam.onPinchBy(0.97f); cam.setPinchFocal(5f, 0f)
            cam.integrate(frame, reduceMotion = true); xs.add(cam.snapshot.tx)
        }
        assertTrue("单调趋近焦点 5", xs.last() > 0.5f && xs.last() <= 5f)
        for (i in 1 until xs.size) assertTrue("单调不减", xs[i] >= xs[i - 1] - 1e-4f)
        // clearPinchFocal 后无补偿：target.x 冻结。
        cam.clearPinchFocal()
        val xBefore = cam.snapshot.tx
        repeat(10) { cam.onPinchBy(0.97f); cam.integrate(frame, reduceMotion = true) }
        assertEquals("清焦点后无补偿", xBefore, cam.snapshot.tx, 1e-3f)
    }

    @Test
    fun twist_appliesNegative_afterDeadzone() {
        val cam = cam(reduce = true)
        cam.setPinching(true)
        val yaw0 = cam.snapshot.yaw
        cam.onTwistBy(0.05f) // Σ=0.05 < 0.06 锁
        cam.onTwistBy(0.05f) // Σ=0.10 > 0.06 解锁·跨阈丢弃
        cam.onTwistBy(0.2f)  // 解锁后全量
        cam.integrate(frame, reduceMotion = true)
        assertEquals("yaw += −twist", yaw0 - 0.2f, cam.snapshot.yaw, 1e-4f)
    }

    // ─────────────────────────── overzoom-out 回星球 ───────────────────────────

    @Test
    fun overzoom_triggersOnceWhenPinchingOutPastCap() {
        val cam = cam()
        cam.setPinching(true)
        cam.onPinchBy(1000f); cam.integrate(frame, reduceMotion = false) // tDist→60（到顶不计）
        assertFalse("到顶本身不触发", cam.consumeReturnRequested())
        cam.onPinchBy(1.06f); cam.integrate(frame, reduceMotion = false) // 1.06 < 1.10
        assertFalse(cam.consumeReturnRequested())
        cam.onPinchBy(1.05f); cam.integrate(frame, reduceMotion = false) // 1.113 ≥ 1.10 → 触发
        assertTrue("累积 ≥ 1.10 → 回星球", cam.consumeReturnRequested())
        cam.onPinchBy(1.05f); cam.integrate(frame, reduceMotion = false)
        assertFalse("一次性·不复触", cam.consumeReturnRequested())
    }

    @Test
    fun overzoom_resetsWhenPinchEnds() {
        val cam = cam()
        cam.setPinching(true)
        cam.onPinchBy(1000f); cam.integrate(frame, reduceMotion = false)
        repeat(4) { cam.onPinchBy(1.1f); cam.integrate(frame, reduceMotion = false) }
        assertTrue(cam.consumeReturnRequested())
        cam.setPinching(false); cam.integrate(frame, reduceMotion = false) // 松手复位
        cam.setPinching(true)
        repeat(4) { cam.onPinchBy(1.1f); cam.integrate(frame, reduceMotion = false) }
        assertTrue("复位后可再触发", cam.consumeReturnRequested())
    }

    // ─────────────────────────── 站点聚焦 / 复位语义（不回中/回中）/ cinematic ───────────────────────────

    @Test
    fun focusSite_convergesToTargetAt10_5() {
        val cam = cam(reduce = true)
        cam.focusSite(5f, 1.5f, -3f)
        repeat(80) { cam.integrate(frame, reduceMotion = true) }
        assertEquals(5f, cam.snapshot.tx, 0.3f)
        assertEquals(1.5f, cam.snapshot.ty, 0.1f)
        assertEquals(-3f, cam.snapshot.tz, 0.3f)
        assertEquals(10.5f, cam.snapshot.dist, 0.3f)
    }

    @Test
    fun clearFocus_closeSheet_doNotRecenter() {
        val cam = cam(reduce = true)
        cam.focusSite(5f, 1.5f, -3f); repeat(80) { cam.integrate(frame, reduceMotion = true) }
        cam.clearFocus(); repeat(120) { cam.integrate(frame, reduceMotion = true) }
        assertEquals("空点 → max(tDist,26)=26", 26f, cam.snapshot.dist, 0.5f)
        assertEquals("空点不回中·tx 留在 5", 5f, cam.snapshot.tx, 0.3f)
        cam.closeSheet(); repeat(120) { cam.integrate(frame, reduceMotion = true) }
        assertEquals("关卡 → 34", 34f, cam.snapshot.dist, 0.5f)
        assertEquals("关卡不回中·tx 仍在 5", 5f, cam.snapshot.tx, 0.3f)
    }

    @Test
    fun resetForRegion_recenters() {
        val cam = cam(reduce = true)
        cam.focusSite(5f, 1.5f, -3f); repeat(80) { cam.integrate(frame, reduceMotion = true) }
        cam.resetForRegion(); repeat(120) { cam.integrate(frame, reduceMotion = true) }
        assertEquals("换区回中 tx→0", 0f, cam.snapshot.tx, 0.3f)
        assertEquals("ty→1.2", 1.2f, cam.snapshot.ty, 0.1f)
        assertEquals("tz→0", 0f, cam.snapshot.tz, 0.3f)
        assertEquals("tDist→34", 34f, cam.snapshot.dist, 0.5f)
    }

    @Test
    fun cinematic_overridesPitchDist_freezesYawTarget_ignoresGestures_thenResumes() {
        val cam = cam()
        repeat(120) { cam.integrate(frame, reduceMotion = false) } // 落定
        val yaw0 = cam.snapshot.yaw
        val tx0 = cam.snapshot.tx
        cam.setCinematicPose(1.12f, 95f)
        // cinematic 期间所有新手势无效（E7/E8）。
        cam.setPointerDown(true); cam.onPanBy(9f, 9f); cam.onTwistBy(1f); cam.onTiltBy(1000f); cam.setPinchFocal(7f, 7f)
        cam.integrate(frame, reduceMotion = false)
        assertEquals(1.12f, cam.snapshot.pitch, eps)
        assertEquals(95f, cam.snapshot.dist, eps)
        assertEquals("yaw 冻结", yaw0, cam.snapshot.yaw, eps)
        assertEquals("target 冻结", tx0, cam.snapshot.tx, eps)
        cam.setCinematicPose(1.0f, 80f); cam.integrate(frame, reduceMotion = false)
        assertEquals(80f, cam.snapshot.dist, eps)
        cam.clearCinematic(); cam.setPointerDown(false); cam.integrate(frame, reduceMotion = false)
        assertEquals("clear 后从 cinematic 姿态续跑", 1.0f, cam.snapshot.pitch, eps)
    }

    // ─────────────────────────── townOverPinch 进小镇（W9c 加法·§3.4 路径 B）───────────────────────────

    @Test
    fun townOverPinch_triggersOnceWhenPinchingInPastFloor() {
        val cam = cam()
        cam.setPinching(true)
        cam.onPinchBy(0.0001f); cam.integrate(frame, reduceMotion = false) // tDist→8（到底不计）
        assertFalse("到底本身不触发", cam.consumeTownDiveRequested())
        cam.onPinchBy(0.97f); cam.integrate(frame, reduceMotion = false) // 0.97 > 0.90
        assertFalse(cam.consumeTownDiveRequested())
        cam.onPinchBy(0.95f); cam.integrate(frame, reduceMotion = false) // 0.9215 > 0.90
        assertFalse(cam.consumeTownDiveRequested())
        cam.onPinchBy(0.97f); cam.integrate(frame, reduceMotion = false) // 0.8939 ≤ 0.90 → 触发
        assertTrue("累积 ≤ 0.90 → 进小镇", cam.consumeTownDiveRequested())
        cam.onPinchBy(0.97f); cam.integrate(frame, reduceMotion = false)
        assertFalse("一次性·不复触", cam.consumeTownDiveRequested())
    }

    @Test
    fun townOverPinch_resetsWhenPinchEnds() {
        val cam = cam()
        cam.setPinching(true)
        cam.onPinchBy(0.0001f); cam.integrate(frame, reduceMotion = false) // → 8
        repeat(4) { cam.onPinchBy(0.9f); cam.integrate(frame, reduceMotion = false) }
        assertTrue(cam.consumeTownDiveRequested())
        cam.setPinching(false); cam.integrate(frame, reduceMotion = false) // 松手复位
        cam.setPinching(true)
        repeat(4) { cam.onPinchBy(0.9f); cam.integrate(frame, reduceMotion = false) }
        assertTrue("复位后可再触发", cam.consumeTownDiveRequested())
    }

    @Test
    fun townOverPinch_notAccumulatedAboveFloor() {
        val cam = cam()
        cam.setPinching(true)
        cam.onPinchBy(0.5f); cam.integrate(frame, reduceMotion = false)  // before=34 非顶底·after=17
        cam.onPinchBy(0.55f); cam.integrate(frame, reduceMotion = false) // before=17 非顶底·after=9.35
        assertFalse("非顶底不累积（乘积 0.275 ≤0.90 仍不触发）", cam.consumeTownDiveRequested())
    }

    @Test
    fun restoreSnapshot_restoresPoseAndTDist_landed() {
        val cam = cam()
        val snap = ContinentCamSnapshot(0.9f, 0.5f, 12f, 3f, 1.2f, -2f)
        cam.restoreSnapshot(snap, tDistValue = 15f)
        assertEquals(0.9f, cam.snapshot.yaw, eps); assertEquals(0.5f, cam.snapshot.pitch, eps)
        assertEquals(12f, cam.snapshot.dist, eps)
        assertEquals(3f, cam.snapshot.tx, eps); assertEquals(-2f, cam.snapshot.tz, eps)
        assertEquals("tDist 镜像恢复", 15f, cam.currentTDist(), eps)
        // 恢复后无 intro：dist 向 tDist(15) 跟随而非从 95 高空落。
        repeat(120) { cam.integrate(frame, reduceMotion = false) }
        assertEquals(15f, cam.snapshot.dist, 0.5f)
    }
}
