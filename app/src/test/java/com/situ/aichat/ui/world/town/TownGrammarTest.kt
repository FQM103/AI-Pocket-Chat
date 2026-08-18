package com.situ.aichat.ui.world.town

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TownGrammar] T1（图纸 §7 T1-1 / §8 chunk1「确定性 / 参数域 / 原语数量守恒」）。断言从 §3.1 参数域 **独立反推**
 * （非照搬实现）：域边界、按样式的屋顶高、二层几何、窗数守恒、以及概率分布用固定种子长序列点验（N=20000·±0.02）。
 */
class TownGrammarTest {

    /** 单条 LCG 连抽 [n] 栋（各栋顺序消耗随机流·均匀采样）。 */
    private fun sample(n: Int, seed: Long = 20260707L): List<List<GrammarPart>> {
        val rnd = TownGrammar.Lcg(seed)
        return List(n) { TownGrammar.building(rnd, 0.0, 0.0) }
    }

    private fun wall(parts: List<GrammarPart>): GrammarPart.LitBox =
        parts.filterIsInstance<GrammarPart.LitBox>().first { it.role == GrammarPart.BoxRole.WALL }

    private fun roof(parts: List<GrammarPart>): GrammarPart.Roof = parts.filterIsInstance<GrammarPart.Roof>().single()

    private fun hasRole(parts: List<GrammarPart>, role: GrammarPart.BoxRole): Boolean =
        parts.any { it is GrammarPart.LitBox && it.role == role }

    // ─────────────────────────── T1-1 确定性 ───────────────────────────

    @Test
    fun deterministic_sameSeed_bytewiseEqual() {
        val a = TownGrammar.building(TownGrammar.Lcg(99L), 1.5, -2.0)
        val b = TownGrammar.building(TownGrammar.Lcg(99L), 1.5, -2.0)
        assertEquals(a.size, b.size)
        a.zip(b).forEach { (pa, pb) ->
            assertEquals(pa::class, pb::class)
            when (pa) {
                is GrammarPart.LitBox -> {
                    pb as GrammarPart.LitBox
                    assertEquals(pa.x, pb.x, 0.0); assertEquals(pa.y, pb.y, 0.0); assertEquals(pa.z, pb.z, 0.0)
                    assertEquals(pa.sx, pb.sx, 0.0); assertEquals(pa.h, pb.h, 0.0); assertEquals(pa.sz, pb.sz, 0.0)
                    assertEquals(pa.role, pb.role); assertTrue(pa.col.contentEquals(pb.col))
                }
                is GrammarPart.Roof -> {
                    pb as GrammarPart.Roof
                    assertEquals(pa.style, pb.style)
                    assertEquals(pa.x, pb.x, 0.0); assertEquals(pa.h, pb.h, 0.0); assertEquals(pa.sx, pb.sx, 0.0)
                    assertTrue(pa.col.contentEquals(pb.col))
                }
                is GrammarPart.EmisBox -> {
                    pb as GrammarPart.EmisBox
                    assertEquals(pa.x, pb.x, 0.0); assertEquals(pa.sx, pb.sx, 0.0); assertEquals(pa.sz, pb.sz, 0.0)
                }
            }
        }
    }

    @Test
    fun seedOf_isDeterministicXor() {
        assertEquals("city_yunye".hashCode().toLong() xor 42L, TownGrammar.seedOf("city_yunye", 42L))
        assertEquals(TownGrammar.seedOf("c", 7L), TownGrammar.seedOf("c", 7L))
    }

    // ─────────────────────────── 参数域边界（§3.1）───────────────────────────

    @Test
    fun baseFootprint_withinDomain() {
        for (parts in sample(4000)) {
            val w = wall(parts)
            assertTrue("sx=${w.sx}", w.sx >= 2.2 && w.sx <= 3.8)
            assertTrue("sz=${w.sz}", w.sz >= 1.9 && w.sz <= 3.1)
        }
    }

    @Test
    fun roofHeights_withinDomainByStyle() {
        for (parts in sample(4000)) {
            val r = roof(parts)
            when (r.style) {
                RoofStyle.GABLE -> assertTrue("gable h=${r.h}", r.h >= 0.9 && r.h <= 1.3)
                RoofStyle.PYRAMID -> assertTrue("pyr h=${r.h}", r.h >= 1.0 && r.h <= 1.3)
                RoofStyle.FLAT -> assertEquals(0.18, r.h, 0.0)
            }
        }
    }

    @Test
    fun windows_countBetween1And3() {
        for (parts in sample(4000)) {
            val n = parts.count { it is GrammarPart.EmisBox }
            assertTrue("wn=$n", n in 1..3)
        }
    }

    // ─────────────────────────── 原语数量守恒（§3.1 组成规则）───────────────────────────

    @Test
    fun composition_exactlyOneRoof_oneBaseWall() {
        for (parts in sample(2000)) {
            assertEquals("恰一顶", 1, parts.count { it is GrammarPart.Roof })
            assertEquals("恰一层墙", 1, parts.count { it is GrammarPart.LitBox && it.role == GrammarPart.BoxRole.WALL })
        }
    }

    @Test
    fun composition_porchPostAndRoofAlwaysPaired() {
        for (parts in sample(4000)) {
            assertEquals("门廊柱↔顶板成对", hasRole(parts, GrammarPart.BoxRole.PORCH_POST), hasRole(parts, GrammarPart.BoxRole.PORCH_ROOF))
        }
    }

    // ─────────────────────────── 概率分布点验（§3.1·N=20000·±0.02）───────────────────────────

    @Test
    fun roofStyle_distribution_45_35_20() {
        val s = sample(20000)
        val g = s.count { roof(it).style == RoofStyle.GABLE } / 20000.0
        val p = s.count { roof(it).style == RoofStyle.PYRAMID } / 20000.0
        val f = s.count { roof(it).style == RoofStyle.FLAT } / 20000.0
        assertEquals(0.45, g, 0.02); assertEquals(0.35, p, 0.02); assertEquals(0.20, f, 0.02)
    }

    @Test
    fun floors_twoFloorProbability_30() {
        val two = sample(20000).count { hasRole(it, GrammarPart.BoxRole.WALL_UPPER) } / 20000.0
        assertEquals(0.30, two, 0.02)
    }

    @Test
    fun accessory_probabilities_chimney55_porch40_sign35() {
        val s = sample(20000)
        assertEquals(0.55, s.count { hasRole(it, GrammarPart.BoxRole.CHIMNEY) } / 20000.0, 0.02)
        assertEquals(0.40, s.count { hasRole(it, GrammarPart.BoxRole.PORCH_POST) } / 20000.0, 0.02)
        assertEquals(0.35, s.count { hasRole(it, GrammarPart.BoxRole.SIGN) } / 20000.0, 0.02)
    }

    // ─────────────────────────── 二层几何（§3.1·缩进 0.1·高 ×0.7·坐一层顶）───────────────────────────

    @Test
    fun upperFloor_insetAndHeightAndSit() {
        var checked = 0
        for (parts in sample(4000)) {
            val boxes = parts.filterIsInstance<GrammarPart.LitBox>()
            val base = boxes.first { it.role == GrammarPart.BoxRole.WALL }
            val upper = boxes.firstOrNull { it.role == GrammarPart.BoxRole.WALL_UPPER } ?: continue
            assertEquals(base.sx - 0.2, upper.sx, 1e-9)
            assertEquals(base.sz - 0.2, upper.sz, 1e-9)
            assertEquals(base.h * 0.7, upper.h, 1e-9)
            assertEquals(base.h, upper.y, 1e-9)                 // 二层坐落一层顶
            assertEquals(base.x + 0.1, upper.x, 1e-9)           // 缩进 0.1
            checked++
        }
        assertTrue("样本含二层", checked > 0)
    }
}
