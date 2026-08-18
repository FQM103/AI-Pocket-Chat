package com.situ.aichat.gift

import com.situ.aichat.data.model.MoodHistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

/**
 * 时机加成单测（断言**反推 iOS `GiftTimingBonusServiceTests`**）：生日 month+day 判定（跨年仍触发）、情绪低落方案 B
 * （最近 5 条 + 24h 内 + red≥3）、组合取最大不叠加。日期用设备默认时区 Calendar（= iOS `Calendar.current`）。
 */
class GiftTimingBonusTest {

    /** 2026-06-15 12:00 本地（固定参考时间，避免边界闪烁）。 */
    private fun fixedNow(): Long = millis(2026, 6, 15, 12)

    private fun millis(year: Int, month: Int, day: Int, hour: Int = 12): Long {
        val c = Calendar.getInstance()
        c.clear()
        c.set(year, month - 1, day, hour, 0, 0)
        return c.timeInMillis
    }

    private fun mood(timestamp: Long, color: String = "green") =
        MoodHistoryEntry(timestamp = timestamp, emoji = "😊", colorName = color, text = "test")

    private val hour = 3600_000L

    // MARK: - birthdayMultiplier

    @Test fun birthday_null_returns_1() {
        assertEquals(1.0, GiftTimingBonusService.birthdayMultiplier(null, fixedNow()), 0.0)
    }

    @Test fun birthday_same_month_day_returns_3() {
        // 生日 1990-06-15，今天 2026-06-15 → 生日当天
        assertEquals(3.0, GiftTimingBonusService.birthdayMultiplier(millis(1990, 6, 15), fixedNow()), 0.0)
    }

    @Test fun birthday_different_day_returns_1() {
        assertEquals(1.0, GiftTimingBonusService.birthdayMultiplier(millis(1990, 6, 14), fixedNow()), 0.0)
    }

    @Test fun birthday_different_month_returns_1() {
        assertEquals(1.0, GiftTimingBonusService.birthdayMultiplier(millis(1990, 7, 15), fixedNow()), 0.0)
    }

    @Test fun birthday_cross_year_same_day_returns_3() {
        // 即使跨 26 年，month+day 同即触发
        assertEquals(3.0, GiftTimingBonusService.birthdayMultiplier(millis(2000, 6, 15), fixedNow()), 0.0)
    }

    // MARK: - moodLowMultiplier（方案 B）

    @Test fun mood_empty_returns_1() {
        assertEquals(1.0, GiftTimingBonusService.moodLowMultiplier(emptyList(), fixedNow()), 0.0)
    }

    @Test fun mood_3_red_within_24h_returns_15() {
        val now = fixedNow()
        val history = listOf(
            mood(now - 1 * hour, "red"),
            mood(now - 2 * hour, "red"),
            mood(now - 3 * hour, "yellow"),
            mood(now - 4 * hour, "red"),
            mood(now - 5 * hour, "green"),
        )
        assertEquals(1.5, GiftTimingBonusService.moodLowMultiplier(history, now), 0.0)
    }

    @Test fun mood_only_2_red_returns_1() {
        val now = fixedNow()
        val history = listOf(
            mood(now - 1 * hour, "red"),
            mood(now - 2 * hour, "red"),
            mood(now - 3 * hour, "yellow"),
            mood(now - 4 * hour, "green"),
            mood(now - 5 * hour, "green"),
        )
        assertEquals(1.0, GiftTimingBonusService.moodLowMultiplier(history, now), 0.0)
    }

    @Test fun mood_all_red_but_over_24h_returns_1() {
        val now = fixedNow()
        // 5 条 red 都在 25-29 小时前
        val history = (0 until 5).map { mood(now - (25 + it) * hour, "red") }
        assertEquals(1.0, GiftTimingBonusService.moodLowMultiplier(history, now), 0.0)
    }

    @Test fun mood_older_reds_but_recent5_green_returns_1() {
        val now = fixedNow()
        val history = mutableListOf<MoodHistoryEntry>()
        // 较旧的 10 条 red（每半小时）
        for (i in 6 until 16) history.add(mood(now - i * 1800_000L, "red"))
        // 最近 5 条 green（每 10 分钟）
        for (i in 0 until 5) history.add(mood(now - i * 600_000L, "green"))
        assertEquals(1.0, GiftTimingBonusService.moodLowMultiplier(history, now), 0.0)
    }

    @Test fun mood_only_3_entries_all_red_returns_15() {
        val now = fixedNow()
        val history = listOf(
            mood(now - 1 * hour, "red"),
            mood(now - 2 * hour, "red"),
            mood(now - 3 * hour, "red"),
        )
        assertEquals(1.5, GiftTimingBonusService.moodLowMultiplier(history, now), 0.0)
    }

    @Test fun mood_unsorted_order_still_correct() {
        val now = fixedNow()
        val history = listOf(
            mood(now - 4 * hour, "red"),
            mood(now - 1 * hour, "red"),
            mood(now - 5 * hour, "green"),
            mood(now - 2 * hour, "red"),
            mood(now - 3 * hour, "yellow"),
        )
        // 排序后最近 5 条 3 红 → 触发
        assertEquals(1.5, GiftTimingBonusService.moodLowMultiplier(history, now), 0.0)
    }

    @Test fun mood_more_than_5_only_recent_5() {
        val now = fixedNow()
        val history = mutableListOf<MoodHistoryEntry>()
        for (i in 0 until 5) history.add(mood(now - i * 600_000L, "green"))   // 较新 green
        for (i in 5 until 10) history.add(mood(now - i * 600_000L, "red"))    // 较旧 red
        // 最近 5 条全 green → 不触发
        assertEquals(1.0, GiftTimingBonusService.moodLowMultiplier(history, now), 0.0)
    }

    // MARK: - multiplier 组合（取最大不叠加）

    @Test fun multiplier_no_trigger_returns_1() {
        assertEquals(1.0, GiftTimingBonusService.multiplier(null, emptyList(), fixedNow()), 0.0)
    }

    @Test fun multiplier_birthday_returns_3() {
        assertEquals(3.0, GiftTimingBonusService.multiplier(millis(1990, 6, 15), emptyList(), fixedNow()), 0.0)
    }

    @Test fun multiplier_mood_low_returns_15() {
        val now = fixedNow()
        val history = listOf(
            mood(now - 1 * hour, "red"),
            mood(now - 2 * hour, "red"),
            mood(now - 3 * hour, "red"),
        )
        assertEquals(1.5, GiftTimingBonusService.multiplier(null, history, now), 0.0)
    }

    @Test fun multiplier_both_takes_max_not_sum() {
        val now = fixedNow()
        val history = listOf(
            mood(now - 1 * hour, "red"),
            mood(now - 2 * hour, "red"),
            mood(now - 3 * hour, "red"),
        )
        // 生日 3.0 vs 情绪 1.5 → max 3.0（不是 4.5）
        assertEquals(3.0, GiftTimingBonusService.multiplier(millis(1990, 6, 15), history, now), 0.0)
    }
}
