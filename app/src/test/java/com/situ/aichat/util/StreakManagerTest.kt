package com.situ.aichat.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * StreakManager.computeStreak 单测（对齐 iOS `Services/StreakManager.swift` checkStreak）：
 * active(今天聊过) / needsChat(昨天聊过、今天还没) / broken(>1 天没聊或从未)。
 * 固定时区(Asia/Shanghai，国行无夏令时)+ 固定 now 保证确定性；断言从 iOS 行为反推
 * (active/needsChat 原样回传 streakCount；broken 无 days)。
 */
class StreakManagerTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 某日在该时区当天 00:00 的 epoch millis。 */
    private fun day(y: Int, m: Int, d: Int): Long =
        LocalDate.of(y, m, d).atStartOfDay(zone).toInstant().toEpochMilli()

    private val now: Long = day(2026, 3, 15)

    @Test fun neverChatted_broken() {
        assertEquals(StreakStatus.Broken, StreakManager.computeStreak(null, 0, now, zone))
    }

    @Test fun chattedToday_active() {
        // 今天任意时刻聊过（用 13:30 验证不是只按 0 点判断）→ active，days 原样回传
        val today1330 = LocalDate.of(2026, 3, 15).atStartOfDay(zone)
            .plusHours(13).plusMinutes(30).toInstant().toEpochMilli()
        assertEquals(StreakStatus.Active(7), StreakManager.computeStreak(today1330, 7, now, zone))
    }

    @Test fun chattedYesterday_needsChat() {
        assertEquals(
            StreakStatus.NeedsChat(3),
            StreakManager.computeStreak(day(2026, 3, 14), 3, now, zone),
        )
    }

    @Test fun chattedTwoDaysAgo_broken() {
        assertEquals(StreakStatus.Broken, StreakManager.computeStreak(day(2026, 3, 13), 5, now, zone))
    }

    @Test fun crossYearBoundary_needsChat() {
        // 昨天=去年最后一天，今天=元旦：仍应是 needsChat（按日历日相邻，不受跨年影响）
        val newYear = day(2026, 1, 1)
        assertEquals(
            StreakStatus.NeedsChat(10),
            StreakManager.computeStreak(day(2025, 12, 31), 10, newYear, zone),
        )
    }
}
