package com.situ.aichat.world.notify

import com.situ.aichat.data.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WorldNotifyRules] T1 金标（W8 图纸 §7·E1–E4）：档位 3×2 放行表 / 封顶曲线 13·14 边界 / 过期 ±1ms / 排队 ±1ms。
 * 断言从图纸 §3.3/§3.5 独立反推（不照搬实现）：数值即世界「会不会吵你」的物理常数。
 */
class WorldNotifyRulesTest {

    // MARK: - E1 档位金标（3 档 × 2 kind = 6 格）

    @Test
    fun `E1 silent 档_两腿全跳`() {
        assertFalse(WorldNotifyRules.tierAllows(AppSettings.WORLD_NOTIFICATION_SILENT, isUserLeg = false))
        assertFalse(WorldNotifyRules.tierAllows(AppSettings.WORLD_NOTIFICATION_SILENT, isUserLeg = true))
    }

    @Test
    fun `E1 gentle 档_只放来访腿_跳用户腿`() {
        assertTrue("克制档放来访腿（TA 到达你的城）", WorldNotifyRules.tierAllows(AppSettings.WORLD_NOTIFICATION_GENTLE, isUserLeg = false))
        assertFalse("克制档跳用户腿（你到达目的地）", WorldNotifyRules.tierAllows(AppSettings.WORLD_NOTIFICATION_GENTLE, isUserLeg = true))
    }

    @Test
    fun `E1 all 档_两腿全放`() {
        assertTrue(WorldNotifyRules.tierAllows(AppSettings.WORLD_NOTIFICATION_ALL, isUserLeg = false))
        assertTrue(WorldNotifyRules.tierAllows(AppSettings.WORLD_NOTIFICATION_ALL, isUserLeg = true))
    }

    @Test
    fun `E1 未知档_保守跳`() {
        assertFalse(WorldNotifyRules.tierAllows("garbage", isUserLeg = false))
        assertFalse(WorldNotifyRules.tierAllows("garbage", isUserLeg = true))
    }

    // MARK: - E2 封顶曲线边界（13→2·14→1·恒 ≥1）

    @Test
    fun `E2 封顶曲线_13天内2条_14天起1条_永不0`() {
        assertEquals(2, WorldNotifyRules.dailyCapFor(0L))
        assertEquals(2, WorldNotifyRules.dailyCapFor(13L))   // 13 < 14 → 2
        assertEquals(1, WorldNotifyRules.dailyCapFor(14L))   // 14 ≥ 14 → 1
        assertEquals(1, WorldNotifyRules.dailyCapFor(15L))
        assertEquals(1, WorldNotifyRules.dailyCapFor(3650L)) // 十年不回 → 仍 1，永不 0
    }

    // MARK: - E3 过期边界（now-arriveAt = 12h ±1ms）

    @Test
    fun `E3 过期边界_恰12h放_超1ms跳`() {
        val arriveAt = 1_000_000_000_000L
        assertFalse("恰 12h → 放（不算过期）", WorldNotifyRules.isStale(arriveAt + WorldNotifyRules.T_STALE_MS, arriveAt))
        assertTrue("12h+1ms → 跳（过期）", WorldNotifyRules.isStale(arriveAt + WorldNotifyRules.T_STALE_MS + 1L, arriveAt))
        assertFalse("12h-1ms → 放", WorldNotifyRules.isStale(arriveAt + WorldNotifyRules.T_STALE_MS - 1L, arriveAt))
    }

    // MARK: - E4 排队边界（距上次出声 119_999 / 120_000ms）

    @Test
    fun `E4 排队边界_不足120s顺延_满120s放`() {
        val lastPost = 1_000_000_000_000L
        assertTrue("119_999ms → 顺延", WorldNotifyRules.shouldDefer(lastPost + 119_999L, lastPost))
        assertFalse("恰 120_000ms → 放", WorldNotifyRules.shouldDefer(lastPost + WorldNotifyRules.PACER_GAP_MS, lastPost))
        assertFalse("120_001ms → 放", WorldNotifyRules.shouldDefer(lastPost + WorldNotifyRules.PACER_GAP_MS + 1L, lastPost))
    }
}
