package com.situ.aichat.ui.world.planet

import kotlin.math.tan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PlanetCamera] T1（W9a 图纸 §5 E6–E7 / §7 T1-2）：方向 / 三钳位 / 惯性 0.94 衰减 /
 * 闲置 1.6s 阈 / reduceMotion 不自转。以 1/60s 步进（steps=1）驱动，等价 demo 逐帧语义。
 *
 * 跟手拖动（用户 2026-07-06 两拍板）：① 力度 = `2·(dist−1)·tan(FOV/2)/视口高px`（取代 demo 固定灵敏度·
 * 期望值在测试内从该规格独立算，不照搬实现；视口高未设 → 回退旧常量 0.005/0.004）；② 方向 = 球面粘手指
 * （正号·dx>0 → yaw 增 → 正面球面右移随手指·**取代 2026-07-03「方向反转」拍板**·W15.2）。
 */
class PlanetCameraTest {

    private val frame = 1f / 60f
    private val eps = 1e-5f

    @Test
    fun initialSnapshot_matchesDemoStartPose() {
        val s = PlanetCamera().snapshot
        assertEquals(0.6f, s.yaw, eps)
        assertEquals(-0.25f, s.pitch, eps)
        assertEquals(3.1f, s.dist, eps)
    }

    // ─────────────────────────── E6 方向（球面粘手指）+ 三钳位 ───────────────────────────

    @Test
    fun drag_surfaceFollowsFinger_fallbackConstantsWhenViewportUnknown() {
        val cam = PlanetCamera() // 未设视口高 → 回退旧固定灵敏度
        cam.setPointerDown(true)
        cam.onDragBy(10f, 8f) // dx>0 → yaw 增（正面球面右移随手指）；dy>0 → pitch 增（2026-07-06 拍板）
        cam.integrate(frame, reduceMotion = false)
        assertEquals(0.6f + 10f * 0.005f, cam.snapshot.yaw, eps)
        assertEquals(-0.25f + 8f * 0.004f, cam.snapshot.pitch, eps)
    }

    // ─────────────────────────── 跟手拖动（2026-07-06 拍板）───────────────────────────

    /** 规格独立换算：屏高像素覆盖球面正面深度处世界宽 2·(dist−1)·tan(FOV/2)，每像素弧长=弧度。 */
    private fun sensSpec(dist: Float, viewportH: Float): Float =
        2f * (dist - 1f) * tan(0.9f / 2f) / viewportH

    @Test
    fun drag_followsFingerOneToOne_atDefaultDistance() {
        val cam = PlanetCamera()
        cam.setViewportHeight(2400f)
        cam.setPointerDown(true)
        cam.onDragBy(10f, 8f)
        cam.integrate(frame, reduceMotion = false)
        val s = sensSpec(3.1f, 2400f) // 初始 dist=3.1
        assertEquals(0.6f + 10f * s, cam.snapshot.yaw, eps)
        assertEquals("双轴同一换算（不再 0.005/0.004 分家）", -0.25f + 8f * s, cam.snapshot.pitch, eps)
    }

    @Test
    fun drag_sensitivityScalesWithCameraDistance() {
        // 拉到最远 6.4：同样 10px 拖动应转更大角度；捏到最近 1.9：更小角度。
        val far = PlanetCamera()
        far.setViewportHeight(2400f)
        far.onPinchBy(1000f); far.integrate(frame, reduceMotion = false) // dist 钳 6.4
        far.setPointerDown(true)
        far.onDragBy(10f, 0f); far.integrate(frame, reduceMotion = false)
        assertEquals(0.6f + 10f * sensSpec(6.4f, 2400f), far.snapshot.yaw, eps)

        val near = PlanetCamera()
        near.setViewportHeight(2400f)
        near.onPinchBy(0.001f); near.integrate(frame, reduceMotion = false) // dist 钳 1.9
        near.setPointerDown(true)
        near.onDragBy(10f, 0f); near.integrate(frame, reduceMotion = false)
        assertEquals(0.6f + 10f * sensSpec(1.9f, 2400f), near.snapshot.yaw, eps)
        assertTrue(
            "近处每像素角度 < 远处（跟手核心：缩放不改变球面/手指 1:1）",
            sensSpec(1.9f, 2400f) < sensSpec(6.4f, 2400f),
        )
    }

    // ─────────────────────────── W15.3 抓取姿态通道 ───────────────────────────

    @Test
    fun grabPose_landsExactPose_andSeedsInertiaFromFrameDelta() {
        val cam = PlanetCamera()
        cam.setPointerDown(true)
        cam.onGrabPose(1.0f, 0.3f)
        cam.integrate(frame, reduceMotion = false)
        assertEquals(1.0f, cam.snapshot.yaw, eps)
        assertEquals(0.3f, cam.snapshot.pitch, eps)
        cam.onGrabPose(1.1f, 0.35f)
        cam.integrate(frame, reduceMotion = false)
        assertEquals(1.1f, cam.snapshot.yaw, eps)
        // 松手：惯性种子 = 最后一次抓取的逐帧差（0.1）→ 首帧继续 +0.1。
        cam.setPointerDown(false)
        cam.integrate(frame, reduceMotion = false)
        assertEquals(1.2f, cam.snapshot.yaw, 1e-4f)
    }

    @Test
    fun grabPose_pitchClampedAtLimit() {
        val cam = PlanetCamera()
        cam.setPointerDown(true)
        cam.onGrabPose(0f, 9f) // 越界俯仰 → 积分钳 ±1.25
        cam.integrate(frame, reduceMotion = false)
        assertEquals(1.25f, cam.snapshot.pitch, eps)
    }

    @Test
    fun grabPose_ignoredWhileCinematic() {
        val cam = PlanetCamera()
        cam.setCinematicPose(2.0f, 0.1f, 4.0f)
        cam.setPointerDown(true)
        cam.onGrabPose(1.0f, 0.3f) // cinematic 覆写期输入清空
        cam.integrate(frame, reduceMotion = false)
        assertEquals(2.0f, cam.snapshot.yaw, eps)
        cam.clearCinematic()
        cam.integrate(frame, reduceMotion = false)
        assertEquals("解锁后不突跳（抓取姿态已被清空）", 2.0f, cam.snapshot.yaw, eps)
    }

    @Test
    fun inertiaSeed_usesSameFollowFingerConversion() {
        val cam = PlanetCamera()
        cam.setViewportHeight(2400f)
        cam.setPointerDown(true)
        cam.onDragBy(10f, 0f)
        cam.integrate(frame, reduceMotion = false)
        cam.setPointerDown(false)
        val before = cam.snapshot.yaw
        cam.integrate(frame, reduceMotion = false) // 惯性首帧位移 = 种子速度 ×1 步
        assertEquals(10f * sensSpec(3.1f, 2400f), cam.snapshot.yaw - before, eps)
    }

    @Test
    fun pitch_clampsToPlusMinus1_25() {
        val up = PlanetCamera()
        up.setPointerDown(true)
        up.onDragBy(0f, 100000f) // pitch += +400 → 钳 +1.25（粘手指正号）
        up.integrate(frame, reduceMotion = false)
        assertEquals(1.25f, up.snapshot.pitch, eps)

        val down = PlanetCamera()
        down.setPointerDown(true)
        down.onDragBy(0f, -100000f) // pitch += -400 → 钳 -1.25
        down.integrate(frame, reduceMotion = false)
        assertEquals(-1.25f, down.snapshot.pitch, eps)
    }

    @Test
    fun dist_clampsTo1_9and6_4() {
        val near = PlanetCamera()
        near.onPinchBy(0.001f) // dist*0.001 → 钳 1.9
        near.integrate(frame, reduceMotion = false)
        assertEquals(1.9f, near.snapshot.dist, eps)

        val far = PlanetCamera()
        far.onPinchBy(1000f) // dist*1000 → 钳 6.4
        far.integrate(frame, reduceMotion = false)
        assertEquals(6.4f, far.snapshot.dist, eps)
    }

    // ─────────────────────────── E7 惯性 / 闲置 / reduceMotion ───────────────────────────

    @Test
    fun inertia_decaysByPointNineFourPerFrame() {
        val cam = PlanetCamera()
        cam.setPointerDown(true)
        cam.onDragBy(10f, 0f)
        cam.integrate(frame, reduceMotion = false) // yaw=0.65, vyaw=+0.05（粘手指正号）
        cam.setPointerDown(false)

        var prev = cam.snapshot.yaw
        val deltas = mutableListOf<Float>()
        repeat(4) {
            cam.integrate(frame, reduceMotion = false)
            val y = cam.snapshot.yaw
            deltas.add(y - prev)
            prev = y
        }
        // 每帧位移 = vyaw·0.94^k → 相邻比恒 0.94。
        assertEquals(0.05f, deltas[0], eps)
        assertEquals(0.94f, deltas[1] / deltas[0], 1e-3f)
        assertEquals(0.94f, deltas[2] / deltas[1], 1e-3f)
        assertEquals(0.94f, deltas[3] / deltas[2], 1e-3f)
    }

    @Test
    fun inertia_convergesToRest() {
        val cam = PlanetCamera()
        cam.setPointerDown(true)
        cam.onDragBy(20f, 0f)
        cam.integrate(frame, reduceMotion = false)
        cam.setPointerDown(false)
        repeat(300) { cam.integrate(frame, reduceMotion = false) } // 远超衰减 + 越 1.6s（会转，但幅度微）
        val a = cam.snapshot.yaw
        cam.integrate(frame, reduceMotion = false)
        // 惯性已归零，此后每帧只剩闲置自转 0.00045。
        assertEquals(0.00045f, cam.snapshot.yaw - a, 1e-4f)
    }

    @Test
    fun idleSpin_startsOnlyAfter1_6Seconds() {
        val cam = PlanetCamera()
        repeat(90) { cam.integrate(frame, reduceMotion = false) } // 1.5s < 1.6s
        assertEquals("1.5s 未越 1.6s → 不自转", 0.6f, cam.snapshot.yaw, 1e-6f)
        repeat(30) { cam.integrate(frame, reduceMotion = false) } // 累计 2.0s > 1.6s
        assertTrue("越 1.6s → 开始自转", cam.snapshot.yaw > 0.6f)
    }

    @Test
    fun idleSpin_suppressedUnderReduceMotion() {
        val cam = PlanetCamera()
        repeat(200) { cam.integrate(frame, reduceMotion = true) } // 3.3s 闲置但 reduceMotion
        assertEquals("reduceMotion → 无闲置自转", 0.6f, cam.snapshot.yaw, 1e-6f)
    }

    // ─────────────────────────── E10 overpinch-in 进大陆（W9b 加法）───────────────────────────

    @Test
    fun overpinch_triggersOnceAfterAccumulatingPastThreshold() {
        val cam = PlanetCamera()
        cam.setPinching(true)
        // 到底：一大口内捏（atFloorBefore=false → 不计 overpinch）。
        cam.onPinchBy(0.001f); cam.integrate(frame, reduceMotion = false)
        assertEquals(1.9f, cam.snapshot.dist, eps)
        assertFalse("到底本身不触发", cam.consumeDiveRequested())
        // 顶格内捏 ×0.97 ×0.95 = 0.9215 > 0.90 → 未触发。
        cam.onPinchBy(0.97f); cam.integrate(frame, reduceMotion = false)
        cam.onPinchBy(0.95f); cam.integrate(frame, reduceMotion = false)
        assertFalse("0.9215 > 0.90 未触发", cam.consumeDiveRequested())
        // 再 ×0.97 → 0.8939 ≤ 0.90 → 触发一次。
        cam.onPinchBy(0.97f); cam.integrate(frame, reduceMotion = false)
        assertTrue("累积 ≤ 0.90 → 触发", cam.consumeDiveRequested())
        // 同一手势内不复触。
        cam.onPinchBy(0.97f); cam.integrate(frame, reduceMotion = false)
        assertFalse("一次性·不复触", cam.consumeDiveRequested())
    }

    @Test
    fun overpinch_resetsOnPinchEnd_andReArms() {
        val cam = PlanetCamera()
        cam.setPinching(true)
        cam.onPinchBy(0.001f); cam.integrate(frame, reduceMotion = false) // 到底
        repeat(6) { cam.onPinchBy(0.9f); cam.integrate(frame, reduceMotion = false) } // 累积至触发
        assertTrue(cam.consumeDiveRequested())
        // 松手 → 复位重新武装。
        cam.setPinching(false); cam.integrate(frame, reduceMotion = false)
        // 再次捏合 → 可再触发（证明已复位）。
        cam.setPinching(true)
        repeat(6) { cam.onPinchBy(0.9f); cam.integrate(frame, reduceMotion = false) }
        assertTrue("复位后可再触发", cam.consumeDiveRequested())
    }

    @Test
    fun overpinch_notTriggeredWhenNotAtFloor() {
        val cam = PlanetCamera()
        cam.setPinching(true)
        // 温和内捏、始终不到底（dist 停在 >1.9）→ 不累积、不触发。
        repeat(20) { cam.onPinchBy(0.999f); cam.integrate(frame, reduceMotion = false) }
        assertTrue("仍未到底", cam.snapshot.dist > 1.9f)
        assertFalse("非顶格不触发", cam.consumeDiveRequested())
    }

    // ─────────────────────────── E11 cinematic 覆写（输入忽略）+ restore ───────────────────────────

    @Test
    fun cinematic_overridesIntegration_andIgnoresInput() {
        val cam = PlanetCamera()
        cam.setCinematicPose(1.0f, 0.5f, 4.0f)
        cam.setPointerDown(true)
        cam.onDragBy(50f, 50f) // 应被忽略
        cam.onPinchBy(0.5f)    // 应被忽略
        cam.integrate(frame, reduceMotion = false)
        assertEquals(1.0f, cam.snapshot.yaw, eps)
        assertEquals(0.5f, cam.snapshot.pitch, eps)
        assertEquals(4.0f, cam.snapshot.dist, eps)
        // 逐帧更新 cinematic 姿态 → 吐新姿态。
        cam.setCinematicPose(1.2f, 0.6f, 3.0f)
        cam.integrate(frame, reduceMotion = false)
        assertEquals(1.2f, cam.snapshot.yaw, eps)
        assertEquals(3.0f, cam.snapshot.dist, eps)
        // clearCinematic + 无输入 → 从最后姿态续跑（不跳变）。
        cam.clearCinematic()
        cam.setPointerDown(false)
        cam.integrate(frame, reduceMotion = false)
        assertEquals(1.2f, cam.snapshot.yaw, eps)
    }

    @Test
    fun restore_takesEffectBeforeFirstIntegrate() {
        val cam = PlanetCamera()
        cam.restore(2.0f, 0.3f, 5.0f)
        assertEquals("restore 即改快照", 2.0f, cam.snapshot.yaw, eps)
        assertEquals(5.0f, cam.snapshot.dist, eps)
        // 首次积分（无输入）保持恢复姿态，不回默认初值。
        cam.integrate(frame, reduceMotion = false)
        assertEquals(2.0f, cam.snapshot.yaw, eps)
        assertEquals(0.3f, cam.snapshot.pitch, eps)
        assertEquals(5.0f, cam.snapshot.dist, eps)
    }
}
