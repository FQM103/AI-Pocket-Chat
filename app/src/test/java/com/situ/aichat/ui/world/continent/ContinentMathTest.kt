package com.situ.aichat.ui.world.continent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * [ContinentMath] T1（W9b 图纸 §5 E1–E4 / §7 T1-1）：金标从 demo `continent-3d-demo.html` §4.1 公式
 * **独立反推**（噪声在测里按 GLSL/JS 公式重写、heightAt/colorFor 按分支独立复算），任何常量/分支序漂移即红。
 * E1/E2 金标另与图纸 §5 值（互证脚本 verify_9b_math.js 复核过）逐值对齐（Double·JS float64 语义·±1e-9）。
 */
class ContinentMathTest {

    private val eps = 1e-9

    // ── 从 demo:L85-91 独立复写的参照噪声（float64）──
    private fun refHash2(x: Double, z: Double): Double {
        val h = sin(x * 127.1 + z * 311.7) * 43758.5453123
        return h - floor(h)
    }

    private fun refVnoise2(x: Double, z: Double): Double {
        val ix = floor(x); val iz = floor(z)
        var fx = x - ix; var fz = z - iz
        fx = fx * fx * (3.0 - 2.0 * fx); fz = fz * fz * (3.0 - 2.0 * fz)
        val a = refHash2(ix, iz); val b = refHash2(ix + 1, iz)
        val c = refHash2(ix, iz + 1); val d = refHash2(ix + 1, iz + 1)
        return a + (b - a) * fx + (c - a) * fz + (a - b - c + d) * fx * fz
    }

    private fun refFbm2(x: Double, z: Double): Double {
        var v = 0.0; var a = 0.5; var px = x; var pz = z
        repeat(5) { v += a * refVnoise2(px, pz); px *= 2.03; pz *= 2.03; a *= 0.5 }
        return v
    }

    private fun refSmooth(a: Double, b: Double, x: Double): Double {
        val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0); return t * t * (3.0 - 2.0 * t)
    }

    // ── 参照 heightAt（demo:L166-175·仅本测用·pads 显式传入）──
    private fun refHeight(x: Double, z: Double, s: RegionStyle, pads: List<DoubleArray>): Double {
        val f = refFbm2((x + s.seed * 13.7) * 0.075, (z + s.seed * 7.9) * 0.075)
        val u = kotlin.math.abs(x) / 22.0; val v = kotlin.math.abs(z) / 22.0
        val r = Math.pow(Math.pow(u, 4.0) + Math.pow(v, 4.0), 0.25)
        val m = 1.0 - refSmooth(s.coast, 1.0, r)
        var h = (f - s.sea) * s.amp * m - (1.0 - m) * 2.2
        if (s.terrace && h > 0.0) h = h * 0.35 + (floor(h / 1.1) * 1.1 + 0.55) * 0.65
        for (c in pads) { val d = hypot(x - c[0], z - c[1]); val w = 1.0 - refSmooth(1.6, 3.0, d); if (w > 0.0) h = h * (1.0 - w) + s.padH * w }
        return h
    }

    private fun d(v: Double) = doubleArrayOf(v, v, v)

    private fun testStyle(
        seed: Double = 11.7, sea: Double = 0.46, amp: Double = 5.2, coast: Double = 0.60,
        padH: Double = 1.5, terrace: Boolean = false, snowLine: Double = 4.4,
        bed: DoubleArray = d(0.11), snow: DoubleArray = d(0.22), rock: DoubleArray = d(0.33),
        cliff: DoubleArray = d(0.44), beach: DoubleArray = d(0.55),
        g1: DoubleArray = d(0.66), g2: DoubleArray = d(0.77),
    ) = RegionStyle(
        styleKey = "test", seed = seed, sea = sea, amp = amp, coast = coast, padH = padH, terrace = terrace,
        snowLine = snowLine, treeN = 0, trunk = 0.5, treeR = 0.5, treeH = 1.0,
        warm = floatArrayOf(1f, 1f, 1f), haze = floatArrayOf(0f, 0f, 0f),
        water = d(0.0), bed = bed, beach = beach, g1 = g1, g2 = g2, cliff = cliff, snow = snow, rock = rock,
        earth = d(0.0), leafs = listOf(d(0.0)), sky = emptyList(), glowA = 1f,
    )

    // ─────────────────────────── E1 fbm2 金标 ───────────────────────────

    @Test
    fun fbm2_matchesReimpl_andBlueprintGolden() {
        val pts = listOf(0.0 to 0.0, 3.7 to -2.1, 12.34 to 56.78, -5.5 to 8.2)
        for ((x, z) in pts) {
            assertEquals("fbm2($x,$z) vs reimpl", refFbm2(x, z), ContinentMath.fbm2(x, z), 0.0)
        }
        // 图纸 §5 E1 逐值（JS float64）。
        assertEquals(0.0, ContinentMath.fbm2(0.0, 0.0), 0.0)
        assertEquals(0.2429280182, ContinentMath.fbm2(3.7, -2.1), eps)
        assertEquals(0.3201605451, ContinentMath.fbm2(12.34, 56.78), eps)
    }

    @Test
    fun hash2_vnoise2_smooth_matchReimpl() {
        assertEquals(refHash2(5.0, -3.0), ContinentMath.hash2(5.0, -3.0), 0.0)
        assertEquals(refVnoise2(2.1, -1.3), ContinentMath.vnoise2(2.1, -1.3), 0.0)
        // smooth 边界：≤a→0，≥b→1，中点=0.5。
        assertEquals(0.0, ContinentMath.smooth(1.6, 3.0, 1.6), 0.0)
        assertEquals(1.0, ContinentMath.smooth(1.6, 3.0, 3.0), 0.0)
        assertEquals(0.5, ContinentMath.smooth(0.0, 1.0, 0.5), 0.0)
        assertEquals(0.0, ContinentMath.smooth(1.6, 3.0, 0.0), 0.0) // 越下界钳 0
    }

    // ─────────────────────────── E2 heightAt 金标 ───────────────────────────

    private val yunze = testStyle(seed = 11.7, sea = 0.46, amp = 5.2, coast = 0.60, padH = 1.5, terrace = false, snowLine = 4.4)
    private val huangsha = testStyle(seed = 41.2, sea = 0.38, amp = 6.0, coast = 0.58, padH = 2.4, terrace = true, snowLine = 99.0)

    // 站点集 = 该区精修城 + 奇观（seed 无关·图纸 §5 E2 口径）。
    private val yunzePads = listOf(
        doubleArrayOf(0.0, 0.0),                                             // 云野镇(600,1300) center(600,1300) r90
        doubleArrayOf((560 - 600) / 90.0 * 14.0, (1240 - 1300) / 90.0 * 14.0), // 镜湖(560,1240)
    )
    private val huangshaPads = listOf(
        doubleArrayOf((1070 - 1100) / 110.0 * 14.0, (640 - 600) / 110.0 * 14.0), // 陶丘(1070,640) center(1100,600) r110
        doubleArrayOf((1160 - 1100) / 110.0 * 14.0, (560 - 600) / 110.0 * 14.0), // 风语石林(1160,560)
    )

    @Test
    fun heightAt_yunzeGolden() {
        assertEquals(-0.0754470142, ContinentMath.heightAt(6.0, -3.0, yunze, yunzePads), eps)
        assertEquals(-2.2, ContinentMath.heightAt(20.0, 20.0, yunze, yunzePads), eps) // 出海：m=0 → 恒 -2.2
        assertEquals(-0.6479469778, ContinentMath.heightAt(-10.0, 4.0, yunze, yunzePads), eps)
        // 与独立参照一致。
        assertEquals(refHeight(6.0, -3.0, yunze, yunzePads), ContinentMath.heightAt(6.0, -3.0, yunze, yunzePads), 0.0)
    }

    @Test
    fun heightAt_huangshaTerraceGolden() {
        // 台地路径 + 邻近风语石林 pad 抬升。
        assertEquals(-0.0541935692, ContinentMath.heightAt(5.0, -6.0, huangsha, huangshaPads), eps)
        assertEquals(refHeight(5.0, -6.0, huangsha, huangshaPads), ContinentMath.heightAt(5.0, -6.0, huangsha, huangshaPads), 0.0)
    }

    // ─────────────────────────── E4 pad 整平 ───────────────────────────

    @Test
    fun pad_centerIsExactlyPadH() {
        val pads = listOf(doubleArrayOf(4.0, -7.0))
        // 中心 d=0 → smooth(1.6,3.0,0)=0 → w=1 → h=padH（十区口径一致）。
        assertEquals(yunze.padH, ContinentMath.heightAt(4.0, -7.0, yunze, pads), eps)
        assertEquals(huangsha.padH, ContinentMath.heightAt(4.0, -7.0, huangsha, pads), eps)
    }

    @Test
    fun pad_twoOverlappingSamePadH_areContinuous() {
        // 两 pad 同 padH·相距 2（<融合半径 3）→ 中点仍被整平、无阶跃。
        val a = doubleArrayOf(0.0, 0.0); val b = doubleArrayOf(2.0, 0.0)
        val mid = ContinentMath.heightAt(1.0, 0.0, yunze, listOf(a, b))
        val nearMid = ContinentMath.heightAt(1.05, 0.0, yunze, listOf(a, b))
        assertTrue("双 pad 中点连续无跳变", kotlin.math.abs(mid - nearMid) < 0.05)
    }

    // ─────────────────────────── E3 colorFor 分支 + 抖动域 ───────────────────────────

    private fun sameHue(c: DoubleArray, base: Double, x: Double, z: Double): Boolean {
        val j = 0.94 + ContinentMath.hash2(floor(x * 2.1), floor(z * 2.1)) * 0.10
        return kotlin.math.abs(c[0] - base * j) < eps
    }

    @Test
    fun colorFor_belowZeroIsBed() {
        val s = testStyle(bed = d(0.11))
        assertTrue(sameHue(ContinentMath.colorFor(-1.0, 0.9, 3.0, 4.0, s), 0.11, 3.0, 4.0))
    }

    @Test
    fun colorFor_aboveSnowLine_rockWhenSteep_snowWhenFlat() {
        val s = testStyle(snowLine = 2.0, rock = d(0.33), snow = d(0.22))
        // h>snowLine·ny<0.5 → rock。
        assertTrue(sameHue(ContinentMath.colorFor(3.0, 0.4, 1.0, 1.0, s), 0.33, 1.0, 1.0))
        // h>snowLine·ny≥0.5 → snow。
        assertTrue(sameHue(ContinentMath.colorFor(3.0, 0.6, 1.0, 1.0, s), 0.22, 1.0, 1.0))
    }

    @Test
    fun colorFor_cliffBeachGrassBranches() {
        val s = testStyle(snowLine = 99.0, cliff = d(0.44), beach = d(0.55), g1 = d(0.66), g2 = d(0.77))
        // ny<0.52（且未过雪线）→ cliff。
        assertTrue(sameHue(ContinentMath.colorFor(1.0, 0.5, 2.0, 2.0, s), 0.44, 2.0, 2.0))
        // ny≥0.52 且 h<0.45 → beach。
        assertTrue(sameHue(ContinentMath.colorFor(0.2, 0.9, 2.0, 2.0, s), 0.55, 2.0, 2.0))
        // ny≥0.52 且 h≥0.45 → 草混合 [g1,g2]（分量落 [0.66,0.77]·jitter 后）。
        val grass = ContinentMath.colorFor(1.0, 0.9, 2.0, 2.0, s)
        val j = 0.94 + ContinentMath.hash2(floor(2.0 * 2.1), floor(2.0 * 2.1)) * 0.10
        assertTrue(grass[0] in (0.66 * j - eps)..(0.77 * j + eps))
    }

    @Test
    fun colorFor_jitterDomainWithinRange() {
        // 逐面抖动 j = 0.94 + hash2·0.10 ∈ [0.94, 1.04)。扫一片面验域。
        val s = testStyle(bed = d(1.0))
        var min = 2.0; var max = 0.0
        for (i in -40..40) for (k in -40..40) {
            val c = ContinentMath.colorFor(-1.0, 0.9, i.toDouble(), k.toDouble(), s)[0] // bed=1 → c = j
            if (c < min) min = c; if (c > max) max = c
        }
        assertTrue("j ≥ 0.94", min >= 0.94 - eps)
        assertTrue("j < 1.04", max < 1.04)
    }

    // ─────────────────────────── 矩阵 / 投影金标 ───────────────────────────

    @Test
    fun trans3_populatesTranslationColumn() {
        val m = ContinentMath.trans3(1f, 2f, 3f)
        assertEquals(1f, m[12], 0f); assertEquals(2f, m[13], 0f); assertEquals(3f, m[14], 0f)
        assertEquals(1f, m[0], 0f); assertEquals(1f, m[5], 0f); assertEquals(1f, m[10], 0f); assertEquals(1f, m[15], 0f)
    }

    @Test
    fun projectSite_identityMvp_mapsNdcToScreen() {
        val id = com.situ.aichat.ui.world.planet.PlanetMath.identity()
        val p = ContinentMath.projectSite(id, 0.5f, 0f, 0.3f, 1000f, 2000f)
        assertTrue(p.visible)
        assertEquals((0.5f * 0.5f + 0.5f) * 1000f, p.x, 1e-3f) // (0.75)*W
        assertEquals((0f + 0.5f) * 2000f, p.y, 1e-3f)          // 0.5*H
        // w≤0（相机后方）→ 不可见：手造 mvp 令 w = z 分量、点 z<0 → c.w<0。
        val wFromZ = FloatArray(16).also { it[11] = 1f } // v4[3] = m[11]*z + m[15] = z
        assertFalse(ContinentMath.projectSite(wFromZ, 0f, 0f, -1f, 1000f, 2000f).visible)
    }

    @Test
    fun continentMvp_projectsTargetNearScreenCenter() {
        // 相机盯住 target=(0,1.2,0)·适中距离 → target 应投到屏幕中央附近。
        val mvp = ContinentMath.continentMvp(0.78f, 0.72f, 34f, 0f, 1.2f, 0f, 0.5f)
        val p = ContinentMath.projectSite(mvp, 0f, 1.2f, 0f, 1080f, 2160f)
        assertTrue(p.visible)
        assertTrue("水平约居中", kotlin.math.abs(p.x - 540f) < 2f)
        assertTrue("垂直约居中", kotlin.math.abs(p.y - 1080f) < 2f)
    }
}
