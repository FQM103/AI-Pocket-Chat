package com.situ.aichat.economy

import com.situ.aichat.data.model.ChatEconomicPressureLevel
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 经济状态纯函数单测（断言反推 iOS 阈值/边界/截断/相对时间）。欠租/事件摘要的 DB 取数走真机/独立复核。
 */
class CharacterEconomicStateServiceTest {

    // ── chatPressureLevel：月薪守卫 + 欠租一票否决 + 4 档比例边界（阈值 0.30/0.60/1.00） ──

    @Test fun salary_zero_returns_null() = assertNull(chatPressureLevel(0, 100, false))

    @Test fun salary_negative_returns_null() = assertNull(chatPressureLevel(-1, 100, false))

    @Test fun arrears_overrides_to_struggling_even_if_rich() =
        assertEquals(ChatEconomicPressureLevel.STRUGGLING, chatPressureLevel(1000, 5000, true))

    @Test fun ratio_020_struggling() =
        assertEquals(ChatEconomicPressureLevel.STRUGGLING, chatPressureLevel(1000, 200, false))

    @Test fun ratio_030_is_frugal_not_struggling() = // 0.30 不 < 0.30
        assertEquals(ChatEconomicPressureLevel.FRUGAL, chatPressureLevel(1000, 300, false))

    @Test fun ratio_059_frugal() =
        assertEquals(ChatEconomicPressureLevel.FRUGAL, chatPressureLevel(1000, 599, false))

    @Test fun ratio_060_is_comfortable_not_frugal() = // 0.60 不 < 0.60
        assertEquals(ChatEconomicPressureLevel.COMFORTABLE, chatPressureLevel(1000, 600, false))

    @Test fun ratio_099_comfortable() =
        assertEquals(ChatEconomicPressureLevel.COMFORTABLE, chatPressureLevel(1000, 999, false))

    @Test fun ratio_100_is_abundant_not_comfortable() = // 1.00 不 < 1.00
        assertEquals(ChatEconomicPressureLevel.ABUNDANT, chatPressureLevel(1000, 1000, false))

    @Test fun negative_balance_coerced_to_zero_struggling() =
        assertEquals(ChatEconomicPressureLevel.STRUGGLING, chatPressureLevel(1000, -50, false))

    // ── trimEventNote：trim + >20 字截 18+… ──

    @Test fun trim_short_note_unchanged() = assertEquals("🍲 餐饮 · 海底捞", trimEventNote("🍲 餐饮 · 海底捞"))

    @Test fun trim_whitespace() = assertEquals("x", trimEventNote("  x  "))

    @Test fun trim_blank_to_empty() = assertEquals("", trimEventNote("   "))

    @Test
    fun trim_long_note_to_18_plus_ellipsis() {
        val note = "消".repeat(21)
        assertEquals("消".repeat(18) + "…", trimEventNote(note))
        assertEquals(19, trimEventNote(note).length) // 18 字 + 1 省略号
    }

    @Test
    fun trim_exactly_20_unchanged() {
        val note = "消".repeat(20)
        assertEquals(note, trimEventNote(note))
    }

    // ── relativeDayText：自然日之差（用 UTC 固定时区断言） ──

    private val utc = ZoneOffset.UTC
    private val now = Instant.parse("2026-06-02T12:00:00Z").toEpochMilli()

    @Test fun rel_today() =
        assertEquals("今天", relativeDayText(Instant.parse("2026-06-02T01:00:00Z").toEpochMilli(), now, utc))

    @Test fun rel_yesterday_late_evening() = // 昨天 23:00 在今天看 = 昨天（自然日，非小时差）
        assertEquals("昨天", relativeDayText(Instant.parse("2026-06-01T23:00:00Z").toEpochMilli(), now, utc))

    @Test fun rel_three_days_ago() =
        assertEquals("3 天前", relativeDayText(Instant.parse("2026-05-30T12:00:00Z").toEpochMilli(), now, utc))

    @Test fun rel_future_guard() =
        assertEquals("未来", relativeDayText(Instant.parse("2026-06-03T00:00:00Z").toEpochMilli(), now, utc))

    // ── 枚举 ──

    @Test
    fun pressure_level_labels_match_ios() {
        assertEquals("捉襟见肘", ChatEconomicPressureLevel.STRUGGLING.promptLabel)
        assertEquals("精打细算", ChatEconomicPressureLevel.FRUGAL.promptLabel)
        assertEquals("收支平衡", ChatEconomicPressureLevel.COMFORTABLE.promptLabel)
        assertEquals("宽裕从容", ChatEconomicPressureLevel.ABUNDANT.promptLabel)
        assertEquals(4, ChatEconomicPressureLevel.entries.size)
    }
}
