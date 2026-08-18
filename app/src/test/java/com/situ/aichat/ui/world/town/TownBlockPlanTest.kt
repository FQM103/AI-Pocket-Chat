package com.situ.aichat.ui.world.town

import com.situ.aichat.ui.world.continent.ContinentStyle
import com.situ.aichat.ui.world.continent.RegionStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * [TownBlockPlan] T1（图纸 §7 T1·§8 chunk2·§5 边界 E1/E2/E3/E4/E5）：网络确定性、支巷让位、地标唯一 + 无河回退、
 * 预算削减序、树采样守卫。断言从 §3.2 规则独立反推——街 / 巷宽度、建筑排数、削减顺序、地标映射。
 */
class TownBlockPlanTest {

    private val style: RegionStyle = ContinentStyle.STYLES.values.first()

    private fun plan(
        cityId: String, water: TownWater = TownWater.WEST_RIVER,
        reserved: List<Pair<Double, Double>> = emptyList(), cap: Int = 100000, warn: (String) -> Unit = {},
    ) = TownBlockPlan.plan(42L, cityId, style, water, reserved, cap, warn)

    private fun walls(r: TownBlockPlan.Result) =
        r.grammar.count { it is GrammarPart.LitBox && it.role == GrammarPart.BoxRole.WALL }

    private fun units(r: TownBlockPlan.Result) = r.litBoxes.size + walls(r) + r.trees.size + r.lanterns.size

    private fun alleys(r: TownBlockPlan.Result) = r.litBoxes.filter { it.sx == 1.0 }.map { it.cx }

    // ─────────────────────────── E1 确定性 ───────────────────────────

    @Test
    fun e1_deterministic_sameCitySeed() {
        val a = plan("city_x"); val b = plan("city_x")
        assertEquals(a.litBoxes.size, b.litBoxes.size)
        assertEquals(a.grammar.size, b.grammar.size)
        assertEquals(a.trees.size, b.trees.size)
        assertEquals(a.lanterns.size, b.lanterns.size)
        a.litBoxes.forEachIndexed { i, box -> assertEquals(box.cx, b.litBoxes[i].cx, 0.0); assertEquals(box.sx, b.litBoxes[i].sx, 0.0) }
        a.trees.forEachIndexed { i, t -> assertEquals(t.cx, b.trees[i].cx, 0.0); assertEquals(t.cz, b.trees[i].cz, 0.0); assertEquals(t.s, b.trees[i].s, 0.0) }
        a.lanterns.forEachIndexed { i, l -> assertEquals(l.cx, b.lanterns[i].cx, 0.0) }
    }

    @Test
    fun mainStreetAndAlleysAndPlaza_present() {
        val r = plan("city_y")
        assertTrue("主街 sx=23", r.litBoxes.any { abs(it.sx - 23.0) < 1e-9 && it.sz == 1.2 })
        assertEquals("支巷 2 条 宽 1.0", 2, alleys(r).size)
        assertTrue("广场 4.6×4.6", r.litBoxes.any { it.sx == 4.6 && it.sz == 4.6 })
    }

    // ─────────────────────────── E2 支巷让位 ───────────────────────────

    @Test
    fun e2_alleyYieldsOneGridWhenReservedCollides() {
        val base = alleys(plan("city_z")).sorted()
        // 在第一条支巷位置放一个可点建筑中心 → 该支巷右移 1 格（3.6）·另一条不动。
        val moved = alleys(plan("city_z", reserved = listOf(base[0] to -4.0))).sorted()
        assertTrue("撞位支巷右移 3.6", moved.any { abs(it - (base[0] + 3.6)) < 1e-9 })
        assertTrue("无撞支巷不动", moved.any { abs(it - base[1]) < 1e-9 })
        assertNotEquals(base, moved)
    }

    @Test
    fun e2_noReserved_noShift() {
        val a = alleys(plan("city_z")).sorted()
        val b = alleys(plan("city_z", reserved = listOf(999.0 to 999.0))).sorted()
        assertEquals(a, b)
    }

    // ─────────────────────────── E3 预算削减序（树→边缘民居→支巷排）───────────────────────────

    @Test
    fun e3_budgetReduction_orderTreesThenEdgeThenAlleyRow() {
        val full = plan("city_b", cap = 100000)
        val u = units(full); val t = full.trees.size; val w = walls(full)
        assertTrue("默认预算不触发", TownBlockPlan.DEFAULT_BUDGET >= u)

        val warnsTrees = mutableListOf<String>()
        val trimTrees = plan("city_b", cap = u - 1, warn = { warnsTrees += it })
        assertEquals("先削树", 0, trimTrees.trees.size)
        assertEquals("边缘 / 支巷排未动", w, walls(trimTrees))
        assertTrue(warnsTrees.any { it.contains("削树") })

        val trimEdge = plan("city_b", cap = u - t - 1)
        assertEquals(0, trimEdge.trees.size)
        assertEquals("再削边缘民居 3 栋", w - 3, walls(trimEdge))

        val trimAlley = plan("city_b", cap = u - t - 3 - 1)
        assertEquals("末削支巷排 3 栋", w - 6, walls(trimAlley))
    }

    // ─────────────────────────── E4 地标映射 + 无河回退 ───────────────────────────

    @Test
    fun e4_landmarkKind_byHashMod_andRiverFallback() {
        val ids = (0..80).map { "lm_$it" }
        fun idOfMod(m: Int) = ids.first { Math.floorMod(it.hashCode(), 3) == m }
        val m0 = idOfMod(0); val m1 = idOfMod(1); val m2 = idOfMod(2)
        assertEquals(TownBlockPlan.LandmarkKind.CLOCK, TownBlockPlan.landmarkKindFor(m0, TownWater.WEST_RIVER))
        assertEquals(TownBlockPlan.LandmarkKind.WATERWHEEL, TownBlockPlan.landmarkKindFor(m1, TownWater.WEST_RIVER))
        assertEquals("水车无西河→钟楼", TownBlockPlan.LandmarkKind.CLOCK, TownBlockPlan.landmarkKindFor(m1, TownWater.NONE))
        assertEquals("水车东海→钟楼", TownBlockPlan.LandmarkKind.CLOCK, TownBlockPlan.landmarkKindFor(m1, TownWater.EAST_SEA))
        assertEquals(TownBlockPlan.LandmarkKind.WINDMILL, TownBlockPlan.landmarkKindFor(m2, TownWater.WEST_RIVER))
    }

    @Test
    fun e4_waterwheel_onlyEmitsWithRiver() {
        // 水车城（mod1）：西河 → 8 环盒；无河 → 回退钟楼（有 pyr 顶语法·无 8 盒环）。
        val m1 = (0..80).map { "lm_$it" }.first { Math.floorMod(it.hashCode(), 3) == 1 }
        val river = plan(m1, TownWater.WEST_RIVER)
        val dry = plan(m1, TownWater.NONE)
        assertTrue("西河城有水车 8 盒(0.6³ 环)", river.litBoxes.count { it.sx == 0.6 && it.h == 0.6 } >= 8)
        assertTrue("无河城回退钟楼(有 PYRAMID 顶)", dry.grammar.any { it is GrammarPart.Roof && it.style == RoofStyle.PYRAMID })
        assertEquals("无河城无水车环", 0, dry.litBoxes.count { it.sx == 0.6 && it.h == 0.6 })
        assertTrue("无河城有钟楼墙(h=4.6)", dry.grammar.any { it is GrammarPart.LitBox && it.h == 4.6 })
    }

    // ─────────────────────────── E5 树采样守卫（可少不可崩）───────────────────────────

    @Test
    fun e5_trees_boundedAndTerminates_manyCities() {
        for (i in 0..40) {
            val r = plan("tc_$i", water = if (i % 2 == 0) TownWater.WEST_RIVER else TownWater.EAST_SEA)
            assertTrue("树数 ≤18", r.trees.size <= 18)
            assertTrue("正常城树数 ≥12", r.trees.size >= 12)   // 采样空间充裕·守卫不误伤
        }
    }
}
