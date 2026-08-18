package com.situ.aichat.ui.world.gl

import com.situ.aichat.ui.world.continent.ContinentMath
import com.situ.aichat.ui.world.continent.SiteProjection
import com.situ.aichat.ui.world.interior.InteriorMath
import com.situ.aichat.ui.world.town.TownMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WorldCameraMath] T1-1（W15 图纸 §7·断言从 §4A.1 规格独立反推）：
 * ① 回程互证——地面点投回三盒景 MVP+投影落回原像素 ±0.5px（三姿态各一组·三像素）；
 * ③ 地平线以上返回 null；④ 超程（s>4·dist）返回 null；⑤ [WorldCameraMath.wrapPi] 归一金标。
 * 反投影正确性靠此回程锁死，不依赖手推公式（图纸 §4A.1）。
 */
class WorldCameraMathTest {

    private val viewW = 1080f
    private val viewH = 2340f
    private val aspect = viewW / viewH

    /** 三像素跨越屏幕（中心 / 左下 / 右上偏上），全部应命中且回投落回原像素。 */
    private val pixels = listOf(540f to 1170f, 200f to 1600f, 880f to 700f)

    private fun assertRoundTrip(
        yaw: Float, pitch: Float, dist: Float,
        tx: Float, ty: Float, tz: Float, fov: Float,
        project: (Float, Float, Float) -> SiteProjection,
    ) {
        for ((px, py) in pixels) {
            val g = WorldCameraMath.groundPoint(yaw, pitch, dist, tx, ty, tz, px, py, viewW, viewH, fov)
            assertNotNull("像素($px,$py)应命中地面", g)
            val proj = project(g!![0], ty, g[1]) // ② 返回点在 y=ty 平面 → 断言可见
            assertTrue("回投可见", proj.visible)
            assertEquals("px 回程 ±0.5", px, proj.x, 0.5f)
            assertEquals("py 回程 ±0.5", py, proj.y, 0.5f)
        }
    }

    @Test
    fun groundPoint_roundTrips_continent() {
        val yaw = 0.78f; val pitch = 0.72f; val dist = 34f
        val tx = 0f; val ty = 1.2f; val tz = 0f; val fov = 0.85f
        val mvp = ContinentMath.continentMvp(yaw, pitch, dist, tx, ty, tz, aspect)
        assertRoundTrip(yaw, pitch, dist, tx, ty, tz, fov) { x, y, z ->
            ContinentMath.projectSite(mvp, x, y, z, viewW, viewH)
        }
    }

    @Test
    fun groundPoint_roundTrips_town() {
        val yaw = 0.7f; val pitch = 0.62f; val dist = 24f
        val tx = -1.5f; val ty = 0.8f; val tz = -1.0f; val fov = 0.85f
        val mvp = TownMath.townMvp(yaw, pitch, dist, tx, ty, tz, aspect)
        assertRoundTrip(yaw, pitch, dist, tx, ty, tz, fov) { x, y, z ->
            TownMath.projectPlace(mvp, x, y, z, viewW, viewH)
        }
    }

    @Test
    fun groundPoint_roundTrips_interior() {
        val yaw = 0.55f; val pitch = 0.42f; val dist = 11.5f
        val tx = -0.5f; val ty = 1.05f; val tz = -0.5f; val fov = 0.8f
        val mvp = InteriorMath.interiorMvp(yaw, pitch, dist, tx, ty, tz, aspect)
        assertRoundTrip(yaw, pitch, dist, tx, ty, tz, fov) { x, y, z ->
            InteriorMath.projectAnchor(mvp, x, y, z, viewW, viewH)
        }
    }

    // ── ③ 地平线以上 → null ──

    @Test
    fun groundPoint_aboveHorizon_returnsNull() {
        // pitch=0.30（PITCH_MIN）+ 顶缘 py=0：视线在地平线之上（dir.y>0）→ 打不到地面。
        val g = WorldCameraMath.groundPoint(0.78f, 0.30f, 34f, 0f, 1.2f, 0f, 540f, 0f, viewW, viewH, 0.85f)
        assertNull("地平线以上返回 null", g)
    }

    // ── ④ 超程（s > 4·dist）→ null ──

    @Test
    fun groundPoint_beyondRange_returnsNull() {
        // pitch=0.30 下地平线约 py≈370；py=420 位于地平线下方（dir.y<0）但命中距离 s≈550 > 4·34=136 → null。
        val far = WorldCameraMath.groundPoint(0.78f, 0.30f, 34f, 0f, 1.2f, 0f, 540f, 420f, viewW, viewH, 0.85f)
        assertNull("超程返回 null", far)
        // 对照：更靠下的像素在量程内 → 命中（证明 null 来自超程守卫而非全线失效）。
        val near = WorldCameraMath.groundPoint(0.78f, 0.30f, 34f, 0f, 1.2f, 0f, 540f, 1600f, viewW, viewH, 0.85f)
        assertNotNull("量程内像素命中", near)
    }

    // ── ⑤ wrapPi 金标 ──

    @Test
    fun wrapPi_golden() {
        val twoPi = (2.0 * Math.PI).toFloat()
        assertEquals(3.5f - twoPi, WorldCameraMath.wrapPi(3.5f), 1e-5f)
        assertEquals(-3.5f + twoPi, WorldCameraMath.wrapPi(-3.5f), 1e-5f)
        assertEquals(0.1f, WorldCameraMath.wrapPi(0.1f), 1e-6f)
    }
}
