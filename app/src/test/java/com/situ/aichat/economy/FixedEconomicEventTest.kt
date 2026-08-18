package com.situ.aichat.economy

import com.situ.aichat.testutil.withDefaultLocale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * 房租/季度奖金纯函数单测（断言反推 iOS）。stableRate 因 Kotlin hashCode 与 iOS 值不同（SPEC §4.6），
 * 不断言具体金额，只断言「落在比例区间 + minAmount 兜底 + 确定性」；key 格式/日期/季度边界则精确断言。
 */
class FixedEconomicEventTest {

    // ── key / note ──
    @Test fun rent_key_format() {
        assertEquals("rent_abc_202606", rentKey("abc", 2026, 6))
        assertEquals("rent_abc_202611", rentKey("abc", 2026, 11))
    }

    @Test fun bonus_key_format() {
        assertEquals("bonus_abc_2026Q2", bonusKey("abc", 2026, 2))
        assertEquals("bonus_abc_2026Q4", bonusKey("abc", 2026, 4))
    }

    @Test fun rent_arrears_note_contains_qianzu() {
        // 欠租检测靠 note 含「欠租」二字，必须保留
        assertTrue(rentArrearsNote(3000).contains("欠租"))
        assertEquals("🏠 房租(欠租 · 本应 3000)", rentArrearsNote(3000))
    }

    // ── stableRate：区间内 + 确定性 ──
    @Test fun stable_rate_within_range() {
        for (seed in listOf("a", "b", "c", "char1_202606_rent", "x_2026Q3_bonus")) {
            val r = stableRate(0.3, 0.4, seed)
            assertTrue("$seed -> $r", r in 0.3..0.4)
        }
    }

    @Test fun stable_rate_deterministic() {
        assertEquals(stableRate(0.5, 2.0, "same"), stableRate(0.5, 2.0, "same"), 0.0)
    }

    // ── 房租金额：区间 + min 兜底 ──
    @Test fun rent_amount_in_range_for_normal_salary() {
        val a = computeRentAmount(10000, "char", 2026, 6)
        assertTrue("rent=$a", a in 3000..4000) // round(10000*0.3..0.4)
    }

    @Test fun rent_amount_floored_to_min_for_tiny_salary() {
        // 月薪 100 → 30~40 < 50 → 恒 50
        assertEquals(50, computeRentAmount(100, "char", 2026, 6))
    }

    // ── 奖金金额：区间 + min 兜底 ──
    @Test fun bonus_amount_in_range_for_normal_salary() {
        val a = computeBonusAmount(10000, "char", 2026, 2)
        assertTrue("bonus=$a", a in 5000..20000) // round(10000*0.5..2.0)
    }

    @Test fun bonus_amount_floored_to_min() {
        // 月薪 100 → 50~200 → max(100,_) → [100,200]
        val a = computeBonusAmount(100, "char", 2026, 2)
        assertTrue("bonus=$a", a in 100..200)
    }

    // ── calculateRentDay：clamp(salaryDay,1,28)+5，超月底 clamp ──
    @Test fun rent_day_normal() = assertEquals(20, calculateRentDay(15, 2026, 4)) // 15+5=20, 4 月 30 天

    @Test fun rent_day_clamps_salaryDay_low() = assertEquals(6, calculateRentDay(0, 2026, 6)) // 0→1, +5=6

    @Test fun rent_day_clamps_to_month_end_feb() = assertEquals(28, calculateRentDay(28, 2026, 2)) // 28+5=33→clamp 28(2026 平年)

    @Test fun rent_day_clamps_to_month_end_feb_25() = assertEquals(28, calculateRentDay(25, 2026, 2)) // 30→clamp 28

    // ── bonusDay = clamp(salaryDay,1,28)+1 ──
    @Test fun bonus_day_normal() = assertEquals(16, bonusDay(15))

    @Test fun bonus_day_clamps() {
        assertEquals(2, bonusDay(0)) // 0→1,+1=2
        assertEquals(29, bonusDay(99)) // 99→28,+1=29
    }

    // ── quarterNumber：仅 3/6/9/12 ──
    @Test fun quarter_number_mapping() {
        assertEquals(1, quarterNumber(3))
        assertEquals(2, quarterNumber(6))
        assertEquals(3, quarterNumber(9))
        assertEquals(4, quarterNumber(12))
    }

    @Test fun quarter_number_non_quarter_months_zero() {
        for (m in listOf(1, 2, 4, 5, 7, 8, 10, 11)) assertEquals(0, quarterNumber(m))
    }

    // ── Locale 钉死（2026-07-12 性能线程专项 K1·钱路）──
    // key 被本地化数字改写 → 房租重复扣/奖金重复发；seed 被改写 → hashCode 变 → 金额随设备语言跳变。
    // 修复 = format 钉 Locale.ROOT；断言与既有金标同字面量 + 金额跨 locale 恒等。
    @Test fun rent_and_bonus_keys_ascii_under_arabic_digit_locale() = withDefaultLocale(Locale.forLanguageTag("ar")) {
        assertEquals("rent_abc_202606", rentKey("abc", 2026, 6))
        assertEquals("bonus_abc_2026Q2", bonusKey("abc", 2026, 2))
    }

    @Test fun amounts_locale_invariant() {
        val rent = computeRentAmount(10000, "char", 2026, 6)
        val bonus = computeBonusAmount(10000, "char", 2026, 2)
        withDefaultLocale(Locale.forLanguageTag("ar")) {
            assertEquals(rent, computeRentAmount(10000, "char", 2026, 6))
            assertEquals(bonus, computeBonusAmount(10000, "char", 2026, 2))
        }
    }
}
