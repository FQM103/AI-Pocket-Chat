package com.situ.aichat.ui.world.interior

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [InteriorCamera] T1-5（W9d 底座 + W15 图纸 §5 E19/E20·§7·断言从 §4A.4 独立反推）。dt=1/60（steps=1）令
 * intro/惯性算式可逐帧金标。保留系（intro 金标 / reduceMotion / overzoom 回小镇 / cinematic）改输入源后全绿；
 * W15 漫游系（twist 过 yaw 扇形钳 / tilt 过 pitch 钳 / 平移惯性 / 边界 ±3.5 弹回 / 闲置轻摇保持）。
 */
class InteriorCameraTest {

    private val dt = 1f / 60f
    private fun cam(reduce: Boolean = false) = InteriorCamera(reduce, tiltDeadzonePx = 12f)
    private fun InteriorCamera.step(n: Int, reduce: Boolean = false) = repeat(n) { integrate(dt, reduce) }
    private fun InteriorCamera.finishIntro() = step(95) // 90 帧 introT→1

    // ---- 初值 / TARGET / reduce 直落定 ----

    @Test
    fun `E15 初值_intro起点far high pitch_TARGET恒`() {
        val c = cam()
        assertEquals(0.55f, c.snapshot.yaw, 1e-4f)
        assertEquals(0.98f, c.snapshot.pitch, 1e-4f) // intro 起点 = INTRO_PITCH
        assertEquals(16.5f, c.snapshot.dist, 1e-4f)  // intro 起点 = FAR_DIST
        assertEquals(-0.5f, c.snapshot.tx, 1e-4f)
        assertEquals(1.05f, c.snapshot.ty, 1e-4f)
        assertEquals(-0.5f, c.snapshot.tz, 1e-4f)
    }

    @Test
    fun `E20 reduce直落定_无intro无摇曳`() {
        val c = cam(reduce = true)
        assertEquals(0.42f, c.snapshot.pitch, 1e-4f)
        assertEquals(11.5f, c.snapshot.dist, 1e-4f)
        assertEquals(1f, c.introFraction(), 1e-4f)
        c.step(200, reduce = true) // 过闲置阈仍不摇
        assertEquals(0.55f, c.snapshot.yaw, 1e-6f)
    }

    // ---- intro 俯冲 45 步金标 ----

    @Test
    fun `E15 intro 45步金标_k0_875 dist12_125 pitch0_49`() {
        val c = cam()
        c.step(45) // introT = 45/90 = 0.5 → k = 1-(0.5)^3 = 0.875
        assertEquals(12.125f, c.snapshot.dist, 2e-3f)
        assertEquals(0.49f, c.snapshot.pitch, 2e-3f)
        assertEquals(0.55f, c.snapshot.yaw, 1e-4f) // intro 期间 yaw 恒 0.55
    }

    @Test
    fun `E15 输入接管intro`() {
        val c = cam()
        assertTrue(c.introFraction() < 1f)
        c.setPointerDown(true); c.integrate(dt, false)
        assertEquals(1f, c.introFraction(), 1e-6f) // 任何输入 → introT=1
    }

    // ---- W15 twist 过 yaw 扇形钳 / tilt 过 pitch 钳（E19）----

    @Test
    fun `E19 twist过yaw扇形钳_0_06到1_38`() {
        val c = cam(); c.finishIntro()
        c.setPinching(true)
        c.onTwistBy(-0.1f)  // 过死区解锁·跨阈丢弃
        c.onTwistBy(-100f)  // yaw = 0.55 − (−100) 上顶
        c.integrate(dt, false)
        assertEquals(1.38f, c.snapshot.yaw, 1e-3f)
        c.onTwistBy(200f)   // yaw = 1.38 − 200 下顶
        c.integrate(dt, false)
        assertEquals(0.06f, c.snapshot.yaw, 1e-3f)
    }

    @Test
    fun `E19 tilt过pitch钳_0_16到1_14`() {
        val c = cam(); c.finishIntro()
        c.setPinching(true)
        c.onTiltBy(20f); c.onTiltBy(100000f)
        c.integrate(dt, false)
        assertEquals(1.14f, c.snapshot.pitch, 1e-3f)
        c.onTiltBy(-100000f)
        c.integrate(dt, false)
        assertEquals(0.16f, c.snapshot.pitch, 1e-3f)
    }

    @Test
    fun `E15 tDist钳位_6_5到17`() {
        val c = cam(); c.finishIntro()
        c.onPinchBy(100f); c.step(400) // tDist→17·dist 跟随收敛
        assertEquals(17f, c.snapshot.dist, 5e-2f)
        c.onPinchBy(0.0001f); c.step(400) // tDist→6.5
        assertEquals(6.5f, c.snapshot.dist, 5e-2f)
    }

    // ---- W15 平移惯性 / 边界 ±3.5 / catch（E20）----

    @Test
    fun `E20 平移惯性0_93衰减_久后不再高帧`() {
        val c = cam(); c.finishIntro() // target.x=-0.5
        c.setPointerDown(true); c.onPanBy(1f, 0f); c.integrate(dt, false) // tx→0.5·vpanX=1
        c.setPointerDown(false)
        var prev = c.snapshot.tx
        val deltas = mutableListOf<Float>()
        repeat(3) { c.integrate(dt, false); val x = c.snapshot.tx; deltas.add(x - prev); prev = x }
        assertEquals(1f, deltas[0], 1e-4f)
        assertEquals(0.93f, deltas[1] / deltas[0], 1e-3f)
        assertEquals(0.93f, deltas[2] / deltas[1], 1e-3f)
        c.step(300)
        assertFalse("久后速度衰减 → 不再高帧", c.wantsHighFps())
    }

    @Test
    fun `E20 平移边界3_5_橡皮筋_硬止5_松手弹回`() {
        val c = cam(); c.finishIntro()
        c.setPointerDown(true)
        c.onPanBy(4.5f, 0f); c.integrate(dt, false)  // −0.5+4.5=4.0（>3.5·未阻尼·起点界内）
        assertEquals(4.0f, c.snapshot.tx, 1e-3f)
        c.onPanBy(1f, 0f); c.integrate(dt, false)    // 越界段：1×0.35=0.35 → 4.35
        assertEquals("越界增量 ×0.35", 4.35f, c.snapshot.tx, 1e-3f)
        c.onPanBy(100f, 0f); c.integrate(dt, false)  // 硬止 ±(3.5+1.5)=5.0
        c.onPanBy(0.01f, 0f); c.integrate(dt, false) // 末事件极小 → 惯性种子≈0
        assertEquals("硬止 5.0", 5.0f, c.snapshot.tx, 0.05f)
        c.setPointerDown(false); c.step(200)
        assertEquals("松手弹回软边界 3.5", 3.5f, c.snapshot.tx, 0.3f)
    }

    @Test
    fun `E20 平移越钳_立绘随平移（快照 target 更新）`() {
        // target 平移后快照 tx/tz 随之更新（投影层读快照 → 立绘/蛋巢/气泡零错位·机制既有·此处验快照跟随）。
        val c = cam(); c.finishIntro()
        c.setPointerDown(true); c.onPanBy(2f, -1.5f); c.integrate(dt, false)
        assertEquals(-0.5f + 2f, c.snapshot.tx, 1e-4f)
        assertEquals(-0.5f - 1.5f, c.snapshot.tz, 1e-4f)
    }

    @Test
    fun `E20 焦点缩放趋近焦点地面点`() {
        val c = cam(); c.finishIntro() // target.x=-0.5
        c.setPointerDown(true); c.setPinching(true)
        val xs = mutableListOf<Float>()
        repeat(30) {
            c.onPinchBy(0.97f); c.setPinchFocal(3f, 0f)
            c.integrate(dt, false); xs.add(c.snapshot.tx)
        }
        assertTrue("单调趋近焦点 3", xs.last() > -0.5f && xs.last() <= 3f)
        for (i in 1 until xs.size) assertTrue("单调不减", xs[i] >= xs[i - 1] - 1e-4f)
    }

    // ---- 闲置轻摇 ----

    @Test
    fun `E15 闲置摇曳_过2_4s后yaw微动`() {
        val c = cam(); c.finishIntro()
        c.step(160) // 过 2.4s(144帧) 闲置阈
        val a = c.snapshot.yaw
        c.step(20)
        assertTrue("闲置后应有轻摇", kotlin.math.abs(c.snapshot.yaw - a) > 1e-6f)
        assertTrue("摇幅极小", kotlin.math.abs(c.snapshot.yaw - a) < 0.02f)
    }

    // ---- overzoom-out 回小镇 / up-hint / cinematic ----

    @Test
    fun `E15 overzoom_顶后累积触发一次_复位重武装`() {
        val c = cam(); c.finishIntro()
        c.setPinching(true)
        c.onPinchBy(100f); c.integrate(dt, false) // tDist→17（首帧未到 cap-before）
        c.onPinchBy(1.06f); c.integrate(dt, false) // overZoom=1.06 <1.10
        assertFalse(c.consumeReturnRequested())
        c.onPinchBy(1.05f); c.integrate(dt, false) // 1.06*1.05=1.113 ≥1.10 → 触发一次
        assertTrue(c.consumeReturnRequested())
        assertFalse("一次性", c.consumeReturnRequested())
        c.setPinching(false); c.integrate(dt, false)
        c.setPinching(true); c.onPinchBy(1.20f); c.integrate(dt, false)
        assertTrue(c.consumeReturnRequested())
    }

    @Test
    fun `E15 up-hint阈15_8`() {
        val c = cam(); c.finishIntro() // 落定 dist≈11.5 < 15.8
        assertFalse(c.wantsUpHint())
        c.onPinchBy(100f); c.step(400) // dist→17 > 15.8
        assertTrue(c.wantsUpHint())
    }

    @Test
    fun `cinematic覆写pitch_dist_冻结yaw_target_忽略手势`() {
        val c = cam(); c.finishIntro()
        val yaw0 = c.snapshot.yaw
        val tx0 = c.snapshot.tx
        c.setCinematicPose(0.9f, 17f)
        c.setPointerDown(true); c.onPanBy(9f, 9f); c.onTwistBy(1f); c.onTiltBy(1000f); c.setPinchFocal(2f, 2f)
        c.integrate(dt, false)
        assertEquals(0.9f, c.snapshot.pitch, 1e-4f)
        assertEquals(17f, c.snapshot.dist, 1e-4f)
        assertEquals("yaw 冻结", yaw0, c.snapshot.yaw, 1e-4f)
        assertEquals("target 冻结", tx0, c.snapshot.tx, 1e-4f)
        c.clearCinematic(); c.setPointerDown(false); c.integrate(dt, false)
        assertEquals("clear 后从 cinematic 姿态续跑", 0.9f, c.snapshot.pitch, 1e-4f)
    }
}
