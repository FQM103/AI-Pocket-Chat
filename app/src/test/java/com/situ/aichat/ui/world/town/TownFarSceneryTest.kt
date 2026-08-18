package com.situ.aichat.ui.world.town

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TownGeometry.buildFarScenery] T1（图纸 §3.3·§7 E1 确定性）：远景层几何随 worldSeed 逐字节确定、结构守恒
 * （双层剪影环 48 段 + 邻村灯火 5–8）、高度落 §3.3 域（基 -2·顶 = 基 + [2.4,5.2]）。视觉（视差 / 观感）归 T4 装机。
 */
class TownFarSceneryTest {

    @Test
    fun deterministic_sameSeed_bytewiseEqual() {
        assertTrue(TownGeometry.buildFarScenery(20260707L).contentEquals(TownGeometry.buildFarScenery(20260707L)))
    }

    @Test
    fun differentSeed_differs() {
        assertFalse(TownGeometry.buildFarScenery(1L).contentEquals(TownGeometry.buildFarScenery(2L)))
    }

    @Test
    fun structure_twoRingsPlusFiveToEightLights() {
        val a = TownGeometry.buildFarScenery(42L)
        assertEquals("9 分量/顶点", 0, a.size % 9)
        val verts = a.size / 9
        // 双层环各 48 段 ×6 顶点 = 576·灯火 n×6（n∈5..8）。
        val lightVerts = verts - 576
        assertEquals("灯火顶点 6 的倍数", 0, lightVerts % 6)
        assertTrue("灯火 5–8 盏", lightVerts / 6 in 5..8)
    }

    @Test
    fun heights_withinDomain() {
        val a = TownGeometry.buildFarScenery(7L)
        for (i in a.indices step 9) {
            val y = a[i + 1]
            assertTrue("y=$y 落 [-2, 3.2]", y >= -2.0 - 1e-4 && y <= -2.0 + 5.2 + 1e-4)
        }
    }
}
