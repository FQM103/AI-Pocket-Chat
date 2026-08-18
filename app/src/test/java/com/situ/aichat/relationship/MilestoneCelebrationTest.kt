package com.situ.aichat.relationship

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-33 里程碑庆祝决策单测（mini design pass 八案例 + 阶梯完整性）。iOS 零通知 → 无 iOS 真值可反推，
 * 断言来自 §9.1 拍板（只 tier 升级/phase-only 不通知/防降级/首次达到）+ mini pass 裁定（庆祝阶梯/
 * 未知名不庆祝/仅 aiAutomatic）。
 */
class MilestoneCelebrationTest {

    private fun decide(history: List<String>, newName: String, trigger: String = "aiAutomatic") =
        milestoneCelebrationDecision(history, newName, trigger)

    @Test
    fun `案例1 phase-only 同名再评估 不通知`() {
        // changed=true 但 newRelationship 与当前同名（仅时期变）→ 名在历史 → 不通知。
        assertFalse(decide(history = listOf("好朋友"), newName = "好朋友"))
    }

    @Test
    fun `案例2 新名首达且高于历史最高 通知`() {
        assertTrue(decide(history = listOf("陌生人"), newName = "网友"))
        assertTrue(decide(history = listOf("陌生人", "网友", "普通朋友"), newName = "好朋友"))
        assertTrue(decide(history = listOf("好朋友"), newName = "恋人")) // 跳跃升级
    }

    @Test
    fun `案例3 降级即便首见 不通知`() {
        // 恋人→普通朋友：普通朋友首见但 rank 4 < 历史最高 11 → 不为分手庆祝。
        assertFalse(decide(history = listOf("恋人"), newName = "普通朋友"))
    }

    @Test
    fun `案例4 复升回历史名 不通知`() {
        assertFalse(decide(history = listOf("好朋友", "恋人", "前任"), newName = "恋人"))
        // 复合期与恋人同 rank：历史已有恋人(11) → 11 > 11 不成立 → 不重复庆祝。
        assertFalse(decide(history = listOf("恋人", "前任"), newName = "复合期"))
    }

    @Test
    fun `案例5 初始设定已是高位 再评估横移 不通知`() {
        // 初始设定「恋人」在历史（recordRelationship 落的首条）→ 评估输出「热恋期」是升级该通知、
        // 输出「恋人」同名不通知。
        assertFalse(decide(history = listOf("恋人"), newName = "恋人"))
        assertTrue(decide(history = listOf("恋人"), newName = "热恋期"))
    }

    @Test
    fun `案例6 空历史首条已知名 通知`() {
        assertTrue(decide(history = emptyList(), newName = "网友"))
        assertTrue(decide(history = emptyList(), newName = "陌生人"))
    }

    @Test
    fun `案例7 自创未知名与负向态 不通知`() {
        assertFalse(decide(history = listOf("好朋友"), newName = "灵魂的共犯")) // LLM 自创
        assertFalse(decide(history = listOf("恋人"), newName = "前任"))       // 负向态无 rank
        assertFalse(decide(history = listOf("恋人"), newName = "冷战中"))
        assertFalse(decide(history = listOf("恋人", "前任"), newName = "藕断丝连"))
    }

    @Test
    fun `案例8 非 aiAutomatic 不通知`() {
        // 将来移植手动推进（userAdvance）时其自有结果 toast，防双提示。
        assertFalse(decide(history = listOf("陌生人"), newName = "网友", trigger = "userAdvance"))
        assertFalse(decide(history = emptyList(), newName = "恋人", trigger = "bogus"))
    }

    @Test
    fun `历史含未知名不参与最高位计算`() {
        // 历史最高已知=好朋友(6)，未知名忽略 → 死党(7) 仍算升级。
        assertTrue(decide(history = listOf("好朋友", "灵魂的共犯"), newName = "死党"))
    }

    @Test
    fun `庆祝阶梯 完整性与序`() {
        val rank = MILESTONE_CELEBRATION_RANK
        // 正向递进序抽查。
        assertTrue(rank.getValue("陌生人") < rank.getValue("好朋友"))
        assertTrue(rank.getValue("好朋友") < rank.getValue("恋人"))
        assertTrue(rank.getValue("恋人") < rank.getValue("热恋期"))
        assertTrue(rank.getValue("热恋期") < rank.getValue("老夫老妻"))
        assertTrue(rank.getValue("老夫老妻") < rank.getValue("灵魂伴侣"))
        // 负向/回退态绝不在阶梯上（不为分手/冷战庆祝的硬保证）。
        for (name in listOf("冷战中", "前任", "藕断丝连", "相爱相杀", "若即若离")) {
            assertNull(name, rank[name])
        }
    }

    // MARK: - P1-44 单槽匹配（删角色撤里程碑庆祝：固定共享 id，仅最后庆祝者==被删角色才撤）

    @Test
    fun `purge requires non-null slot matching deleted character`() {
        assertFalse(MilestoneCelebrationNotifier.shouldPurgeMilestone(null, "x"))
        assertFalse(MilestoneCelebrationNotifier.shouldPurgeMilestone("y", "x"))
        assertTrue(MilestoneCelebrationNotifier.shouldPurgeMilestone("x", "x"))
    }
}
