package com.situ.aichat.economy

import com.situ.aichat.notification.EconomyNotificationTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-40 经济可见性纯函数单测：通知三档决策（[EconomyNotificationPlanner]）+ 钱包卡高亮判定
 * （[hasEconomyNews]）+ 三档枚举回退。纯展示层（iOS 零通知 → 无 iOS 真值可反推，断言来自 §9.3 拍板规格）。
 */
class EconomyVisibilityTest {

    private fun ev(kind: EconomyEventKind, name: String, amount: Int, uuid: String = "uuid-$name") =
        EconomyEvent(kind, uuid, name, amount, timestamp = 1_000L)

    // ── EconomyNotificationPlanner ──────────────────────────────────────────

    @Test
    fun `空事件 任何档位都不发`() {
        assertEquals(EconomyNotificationPlanner.Plan.None, EconomyNotificationPlanner.plan(emptyList(), EconomyNotificationTier.DETAILED))
        assertEquals(EconomyNotificationPlanner.Plan.None, EconomyNotificationPlanner.plan(emptyList(), EconomyNotificationTier.BRIEF))
    }

    @Test
    fun `OFF 档 有事件也不发`() {
        val events = listOf(ev(EconomyEventKind.SALARY, "小桃", 500))
        assertEquals(EconomyNotificationPlanner.Plan.None, EconomyNotificationPlanner.plan(events, EconomyNotificationTier.OFF))
    }

    @Test
    fun `BRIEF 按去重角色数计数`() {
        val events = listOf(
            ev(EconomyEventKind.SALARY, "小桃", 500),
            ev(EconomyEventKind.RENT, "小桃", 200),
            ev(EconomyEventKind.SALARY, "阿澈", 800),
        )
        val plan = EconomyNotificationPlanner.plan(events, EconomyNotificationTier.BRIEF)
        assertEquals(EconomyNotificationPlanner.Plan.Brief(characterCount = 2), plan)
    }

    @Test
    fun `DETAILED 按角色分组 保持事件发生序`() {
        val events = listOf(
            ev(EconomyEventKind.SALARY, "小桃", 500),
            ev(EconomyEventKind.BONUS, "阿澈", 2000),
            ev(EconomyEventKind.RENT, "小桃", 200),
        )
        val plan = EconomyNotificationPlanner.plan(events, EconomyNotificationTier.DETAILED)
        check(plan is EconomyNotificationPlanner.Plan.Detailed)
        assertEquals(2, plan.lines.size)
        assertEquals("小桃", plan.lines[0].characterName)
        assertEquals(listOf(EconomyEventKind.SALARY, EconomyEventKind.RENT), plan.lines[0].events.map { it.kind })
        assertEquals("阿澈", plan.lines[1].characterName)
    }

    @Test
    fun `欠租 0 元留痕事件参与决策`() {
        // 维护层把 RentCharge(charged=0, due=300) 收集为 RENT_ARREARS（amount=本应额）——0 元欠租不是「无事」。
        val events = listOf(ev(EconomyEventKind.RENT_ARREARS, "小桃", 300))
        val plan = EconomyNotificationPlanner.plan(events, EconomyNotificationTier.BRIEF)
        assertEquals(EconomyNotificationPlanner.Plan.Brief(characterCount = 1), plan)
    }

    @Test
    fun `重名角色按 uuid 分别计数与分组`() {
        // 批2 复核修 LOW#6：角色名无唯一约束，两个「小桃」必须算 2 位、各成一行。
        val events = listOf(
            ev(EconomyEventKind.SALARY, "小桃", 500, uuid = "u1"),
            ev(EconomyEventKind.SALARY, "小桃", 800, uuid = "u2"),
        )
        assertEquals(
            EconomyNotificationPlanner.Plan.Brief(characterCount = 2),
            EconomyNotificationPlanner.plan(events, EconomyNotificationTier.BRIEF),
        )
        val detailed = EconomyNotificationPlanner.plan(events, EconomyNotificationTier.DETAILED)
        check(detailed is EconomyNotificationPlanner.Plan.Detailed)
        assertEquals(2, detailed.lines.size)
    }

    // ── hasEconomyNews（钱包卡「新变动」高亮） ───────────────────────────────

    @Test
    fun `最新流水晚于上次浏览 才亮`() {
        assertTrue(hasEconomyNews(latestTxMillis = 2_000L, lastViewedMillis = 1_000L))
        assertFalse(hasEconomyNews(latestTxMillis = 1_000L, lastViewedMillis = 1_000L)) // 相等不亮
        assertFalse(hasEconomyNews(latestTxMillis = 500L, lastViewedMillis = 1_000L))
        assertFalse(hasEconomyNews(latestTxMillis = null, lastViewedMillis = 0L)) // 无流水不亮
    }

    @Test
    fun `从未浏览过 lastViewed=0 有流水即亮`() {
        assertTrue(hasEconomyNews(latestTxMillis = 1L, lastViewedMillis = 0L))
    }

    // ── EconomyNotificationTier.fromRaw ────────────────────────────────────

    @Test
    fun `tier 解析 未知与缺值回退 BRIEF`() {
        assertEquals(EconomyNotificationTier.DETAILED, EconomyNotificationTier.fromRaw("detailed"))
        assertEquals(EconomyNotificationTier.BRIEF, EconomyNotificationTier.fromRaw("brief"))
        assertEquals(EconomyNotificationTier.OFF, EconomyNotificationTier.fromRaw("off"))
        assertEquals(EconomyNotificationTier.BRIEF, EconomyNotificationTier.fromRaw(null))
        assertEquals(EconomyNotificationTier.BRIEF, EconomyNotificationTier.fromRaw("bogus"))
    }

    // ── RentCharge 形态（出参改造·写入不动） ────────────────────────────────

    @Test
    fun `RentCharge 欠租判定 charged 小于 due`() {
        assertTrue(RentCharge(charged = 0, due = 300).charged < RentCharge(charged = 0, due = 300).due)
        assertTrue(RentCharge(charged = 120, due = 300).charged < 300)
        assertFalse(RentCharge(charged = 300, due = 300).charged < 300)
    }
}
