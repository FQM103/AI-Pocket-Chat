package com.situ.aichat.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * [StructuredMemoryStats] 单测——断言反推 iOS `StructuredMemoryStats`：
 * 初次相识 / 聊天最多的一天 / 最晚夜聊(0:00–5:59 取最晚) / 最长连续对话(间隔>2h断开) / 最长连续天数。
 */
class StructuredMemoryStatsTest {

    private val utc = ZoneOffset.UTC
    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).toInstant(utc).toEpochMilli()

    private fun startOfDay(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(utc).toInstant().toEpochMilli()

    @Test fun empty_returnsEmpty() {
        val r = StructuredMemoryStats.compute(emptyList(), firstMessageDate = at(2026, 6, 1, 9, 0), zone = utc)
        assertNull(r.firstMeetDate) // 空时一律 nil（含 firstMessageDate 也忽略，1:1 iOS guard）
        assertNull(r.busiestDay)
        assertNull(r.latestNightChat)
        assertEquals(0, r.longestConversation)
        assertEquals(0, r.longestStreak)
        assertTrue(!r.hasAnyData)
    }

    @Test fun fullScenario() {
        val ts = listOf(
            at(2026, 6, 1, 10, 0),
            at(2026, 6, 1, 10, 30), // +30min ≤2h → 连续对话 run=2
            at(2026, 6, 1, 14, 0),  // +3.5h >2h → 断开
            at(2026, 6, 2, 2, 30),  // 夜聊 mod=150
            at(2026, 6, 2, 3, 45),  // 夜聊 mod=225（最晚）；与 02:30 间隔 1h15 → run=2
            at(2026, 6, 4, 23, 0),  // 非夜聊
        )
        val r = StructuredMemoryStats.compute(ts, firstMessageDate = null, zone = utc)
        assertEquals(at(2026, 6, 1, 10, 0), r.firstMeetDate) // firstMessageDate 空 → 最早一条
        assertEquals(3, r.busiestDay?.count) // 06-01 三条最多
        assertEquals(startOfDay(2026, 6, 1), r.busiestDay?.dateMillis)
        assertEquals(at(2026, 6, 2, 3, 45), r.latestNightChat) // 0–6 点最晚
        assertEquals(2, r.longestConversation) // 最长连续对话 2 条
        assertEquals(2, r.longestStreak) // 06-01、06-02 连续 2 天（06-04 断）
    }

    @Test fun firstMessageDate_overridesEarliest() {
        val ts = listOf(at(2026, 6, 5, 12, 0), at(2026, 6, 6, 12, 0))
        val pinned = at(2026, 6, 1, 8, 0)
        val r = StructuredMemoryStats.compute(ts, firstMessageDate = pinned, zone = utc)
        assertEquals(pinned, r.firstMeetDate)
    }

    @Test fun noNightChat_leavesNull() {
        val ts = listOf(at(2026, 6, 1, 9, 0), at(2026, 6, 1, 18, 0))
        val r = StructuredMemoryStats.compute(ts, firstMessageDate = null, zone = utc)
        assertNull(r.latestNightChat)
        assertEquals(1, r.longestConversation) // 间隔 9h >2h → 各自独立，最长 1
        assertEquals(1, r.longestStreak)
    }
}
