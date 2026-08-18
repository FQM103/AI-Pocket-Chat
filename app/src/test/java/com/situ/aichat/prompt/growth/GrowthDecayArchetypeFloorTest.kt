package com.situ.aichat.prompt.growth

import com.situ.aichat.data.model.RelationshipQuality
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T2-3（图纸 §7）：[computeDecayedQuality] 衰减地板 max(原型地板,5)（已识别）/ legacy dynamicFloor（未识别）；
 * E7 三态（上方衰 / 到界停 / 界外不动）；trust/respect 恒不衰；attachment 规则不变。断言从 D-6 规格独立反推。
 */
class GrowthDecayArchetypeFloorTest {

    private val lover = RelationshipArchetype.byId("LOVER")!! // 地板[55,30,50,45,45,40,0,0]

    private fun q(vararg v: Int) = RelationshipQuality(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7])

    @Test fun `已识别 - 熟悉上方衰到原型地板停`() {
        // fam90 → 90-7=83 > 地板55 → 83（上方衰）
        val out = computeDecayedQuality(q(90, 30, 50, 45, 45, 40, 5, 5), inactiveDays = 10, newDecayDays = 7, dynamicFloor = 99, archetype = lover)
        assertEquals(83, out.familiarity)
    }

    @Test fun `已识别 - 到界停不越地板`() {
        // fam58 → 58-7=51 < 地板55 → 钳到 55（到界停）
        val out = computeDecayedQuality(q(58, 30, 50, 45, 45, 40, 5, 5), inactiveDays = 10, newDecayDays = 7, dynamicFloor = 99, archetype = lover)
        assertEquals(55, out.familiarity)
    }

    @Test fun `已识别 - 界外低值一动不动（V-b 守卫）`() {
        // fam40 < 地板55（手调界外）→ 不衰也不抬 → 40
        val out = computeDecayedQuality(q(40, 30, 50, 45, 45, 40, 5, 5), inactiveDays = 10, newDecayDays = 7, dynamicFloor = 99, archetype = lover)
        assertEquals(40, out.familiarity)
    }

    @Test fun `已识别 - 低地板原型地板取 max 5`() {
        val stranger = RelationshipArchetype.byId("STRANGER")!! // 地板全 0 → max(0,5)=5
        // fam8 → 8-7=1 < 5 → 5；fam3 < 5 → 界外不动 3
        assertEquals(5, computeDecayedQuality(q(8, 0, 0, 0, 0, 0, 0, 0), 10, 7, 99, stranger).familiarity)
        assertEquals(3, computeDecayedQuality(q(3, 0, 0, 0, 0, 0, 0, 0), 10, 7, 99, stranger).familiarity)
    }

    @Test fun `未识别 - 走 legacy dynamicFloor`() {
        // archetype=null，dynamicFloor=30（=equilibriumPoint("朋友")40-10）。fam50 → 50-7=43 > 30 → 43；fam20<30 界外不动
        assertEquals(43, computeDecayedQuality(q(50, 30, 50, 45, 45, 40, 5, 5), 10, 7, 30, null).familiarity)
        assertEquals(20, computeDecayedQuality(q(20, 30, 50, 45, 45, 40, 5, 5), 10, 7, 30, null).familiarity)
    }

    @Test fun `trust 与 respect 恒不衰`() {
        val out = computeDecayedQuality(q(90, 90, 90, 90, 90, 90, 90, 90), inactiveDays = 30, newDecayDays = 30, dynamicFloor = 10, archetype = lover)
        assertEquals("信任不在衰减维", 90, out.trust)
        assertEquals("尊重不在衰减维", 90, out.respect)
    }

    @Test fun `张力 startDay7 固定地板5`() {
        // inactiveDays=6 ≤7 → 张力不衰；inactiveDays=10 → 张力 20-7=13>5 → 13
        assertEquals(20, computeDecayedQuality(q(60, 30, 50, 45, 45, 40, 20, 5), 6, 3, 99, lover).tension)
        assertEquals(13, computeDecayedQuality(q(60, 30, 50, 45, 45, 40, 20, 5), 10, 7, 99, lover).tension)
    }

    @Test fun `attachment 规则不变 - 想念与淡化`() {
        // ≤7 天想念 +days（封顶100）
        assertEquals(23, computeDecayedQuality(q(60, 30, 50, 45, 45, 40, 5, 20), inactiveDays = 5, newDecayDays = 3, dynamicFloor = 10, archetype = lover).attachment)
        // >7 天淡化 -days，至 5
        assertEquals(13, computeDecayedQuality(q(60, 30, 50, 45, 45, 40, 5, 20), inactiveDays = 10, newDecayDays = 7, dynamicFloor = 10, archetype = lover).attachment)
        assertEquals(5, computeDecayedQuality(q(60, 30, 50, 45, 45, 40, 5, 8), inactiveDays = 10, newDecayDays = 7, dynamicFloor = 10, archetype = lover).attachment)
    }
}
