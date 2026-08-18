package com.situ.aichat.prompt.growth

import com.situ.aichat.data.model.RelationshipQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1-2（图纸 §7）：关系原型表 §3.1 五条不变量机器断言 + [RelationshipArchetype.waterLevel] 边界（±1 精度）
 * + E20 洞察逐原型验算 + E6 穿地板钳 0。
 *
 * 洞察阈值为**从 PromptBuilderGrowth.kt:217-234 独立反推的字面量**（PITFALLS §1e：绝不引用实现常量）。
 */
class RelationshipArchetypeTableTest {

    private val DIM = 8 // 维序：熟悉0 信任1 亲近2 默契3 尊重4 趣味5 张力6 依恋7

    private fun qualityOf(floors: IntArray) = RelationshipQuality(
        familiarity = floors[0], trust = floors[1], closeness = floors[2], rapport = floors[3],
        respect = floors[4], funValue = floors[5], tension = floors[6], attachment = floors[7],
    )

    /** 五条组合洞察的触发条件（阈值字面量独立反推 · buildDimensionCombinationInsight）。返回触发编号集合。 */
    private fun firedInsights(q: RelationshipQuality): Set<Int> = buildSet {
        if (q.trust >= 70 && q.funValue <= 35) add(1)             // 高信任+低趣味
        if (q.closeness >= 65 && q.tension >= 55) add(2)          // 高亲近+高张力
        if (q.familiarity >= 65 && q.closeness <= 35) add(3)      // 高熟悉+低亲近
        if (q.trust <= 35 && q.attachment >= 55) add(4)           // 低信任+高依恋
        if (q.respect >= 75 && q.rapport >= 75 && q.trust >= 75) add(5) // 灵魂伴侣感
    }

    @Test fun `19 原型 id 唯一且 byId 往返`() {
        val all = RelationshipArchetype.ALL
        assertEquals(19, all.size)
        assertEquals(19, all.map { it.id }.toSet().size)
        for (a in all) assertEquals(a, RelationshipArchetype.byId(a.id))
        assertNull(RelationshipArchetype.byId("NOPE"))
    }

    @Test fun `不变量1 - 地板∈0到99 且天花板严格大于同维地板`() {
        for (a in RelationshipArchetype.ALL) {
            assertEquals("${a.id} floors 长度", DIM, a.floors.size)
            a.ceilings?.let { assertEquals("${a.id} ceilings 长度", DIM, it.size) }
            for (i in 0 until DIM) {
                val f = a.floors[i]
                assertTrue("${a.id} dim$i 地板∈[0,100)", f in 0..99)
                val c = a.ceilings?.get(i) ?: -1
                if (c >= 0) assertTrue("${a.id} dim$i 天花板($c)>地板($f)", c > f)
            }
        }
    }

    @Test fun `不变量2 - 张力依恋地板全0`() {
        for (a in RelationshipArchetype.ALL) {
            assertEquals("${a.id} 张力地板", 0, a.floors[6])
            assertEquals("${a.id} 依恋地板", 0, a.floors[7])
        }
    }

    @Test fun `不变量3 - 信任≤55 尊重≤55 默契≤60`() {
        for (a in RelationshipArchetype.ALL) {
            assertTrue("${a.id} 信任地板≤55", a.floors[1] <= 55)
            assertTrue("${a.id} 尊重地板≤55", a.floors[4] <= 55)
            assertTrue("${a.id} 默契地板≤60", a.floors[3] <= 60)
        }
    }

    @Test fun `不变量4 - fam≥65 的原型 closeness 地板均大于35`() {
        for (a in RelationshipArchetype.ALL) {
            if (a.floors[0] >= 65) assertTrue("${a.id} fam≥65 但 close≤35", a.floors[2] > 35)
        }
    }

    @Test fun `E20 - 任何原型纯播种态不触发任何洞察`() {
        for (a in RelationshipArchetype.ALL) {
            val fired = firedInsights(qualityOf(a.floors))
            assertEquals("${a.id} 纯播种不应触发洞察，实触发=$fired", emptySet<Int>(), fired)
        }
    }

    @Test fun `不变量5 - 每原型 family 为合法枚举`() {
        val valid = ScriptFamily.values().toSet()
        for (a in RelationshipArchetype.ALL) assertTrue("${a.id} family 非法", a.family in valid)
    }

    @Test fun `waterLevel 边界 - t 恰 0 0_30 0_65 0_90 1`() {
        assertEquals(0f, RelationshipArchetype.waterLevel(0, 0, -1), 1e-4f)
        assertEquals(0.30f, RelationshipArchetype.waterLevel(30, 0, -1), 1e-4f)
        assertEquals(0.65f, RelationshipArchetype.waterLevel(65, 0, -1), 1e-4f)
        assertEquals(0.90f, RelationshipArchetype.waterLevel(90, 0, -1), 1e-4f)
        assertEquals(1f, RelationshipArchetype.waterLevel(100, 0, -1), 1e-4f)
    }

    @Test fun `waterLevel 天花板收窄区间 - EX 信任 floor15 ceil45`() {
        assertEquals(0f, RelationshipArchetype.waterLevel(15, 15, 45), 1e-4f)
        assertEquals(0.5f, RelationshipArchetype.waterLevel(30, 15, 45), 1e-4f)
        assertEquals(1f, RelationshipArchetype.waterLevel(45, 15, 45), 1e-4f)
        assertEquals(1f, RelationshipArchetype.waterLevel(80, 15, 45), 1e-4f) // 超天花板→钳1
    }

    @Test fun `E6 - 分数穿地板 t 钳 0`() {
        assertEquals(0f, RelationshipArchetype.waterLevel(5, 20, -1), 1e-4f)
        assertEquals(0f, RelationshipArchetype.waterLevel(0, 55, -1), 1e-4f)
    }

    @Test fun `assertFalse 占位 - 无天花板维不参与回拉`() {
        // 张力/依恋恒无天花板：ceiling=-1 → hi=100
        assertFalse(RelationshipArchetype.ALL.any { it.ceilings?.get(6)?.let { c -> c >= 0 } == true })
        assertFalse(RelationshipArchetype.ALL.any { it.ceilings?.get(7)?.let { c -> c >= 0 } == true })
    }
}
