package com.situ.aichat.economy

import com.situ.aichat.testutil.withDefaultLocale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * 月薪推断/发薪纯函数单测（断言反推 iOS）：JSON 解析（Int/Double/围栏/think 标签）、clamp、去重 key 格式、
 * 发薪日判定（含 salaryDay 越界钳位）、入职储蓄金额（防 0）。
 */
class SalaryServicesTest {

    // ── parseSalary ──
    @Test fun parse_plain_int() = assertEquals(8000, parseSalary("""{"monthlySalary": 8000}"""))

    @Test fun parse_double_truncates() = assertEquals(8000, parseSalary("""{"monthlySalary": 8000.0}"""))

    @Test fun parse_double_truncates_toward_zero() = assertEquals(7999, parseSalary("""{"monthlySalary": 7999.9}"""))

    @Test fun parse_json_fence() = assertEquals(5000, parseSalary("```json\n{\"monthlySalary\": 5000}\n```"))

    @Test fun parse_with_think_tag() = assertEquals(3000, parseSalary("<think>估一下</think>{\"monthlySalary\": 3000}"))

    @Test fun parse_with_surrounding_text() = assertEquals(6000, parseSalary("好的，结果是 {\"monthlySalary\": 6000} 仅供参考"))

    @Test fun parse_missing_field_null() = assertNull(parseSalary("""{"foo": 1}"""))

    @Test fun parse_invalid_null() = assertNull(parseSalary("not json at all"))

    // ── clampSalary [0, 50000] ──
    @Test fun clamp_negative_to_floor() = assertEquals(0, clampSalary(-100))

    @Test fun clamp_over_ceiling() = assertEquals(50000, clampSalary(99999))

    @Test fun clamp_in_range_unchanged() = assertEquals(8000, clampSalary(8000))

    // ── 去重 key ──
    @Test fun salary_key_format_pads() {
        assertEquals("salary_abc_202606", salaryKey("abc", 2026, 6))
        assertEquals("salary_abc_202611", salaryKey("abc", 2026, 11))
    }

    @Test fun onboarding_key_format() = assertEquals("onboarding_abc", onboardingKey("abc"))

    // ── isPayday（含 salaryDay 越界钳位 1-28） ──
    @Test fun payday_on_or_after_day() {
        assertTrue(isPayday(15, 15))
        assertTrue(isPayday(15, 20))
        assertFalse(isPayday(15, 14))
    }

    @Test fun payday_clamps_out_of_range_salaryDay() {
        assertTrue(isPayday(0, 1)) // 0 → clamp 1
        assertTrue(isPayday(99, 28)) // 99 → clamp 28
        assertFalse(isPayday(99, 27))
    }

    // ── onboardingAmount = max(1, salary*0.5 截断) ──
    @Test fun onboarding_half_salary() = assertEquals(4000, onboardingAmount(8000))

    @Test fun onboarding_min_one_guards_zero() = assertEquals(1, onboardingAmount(1)) // 1*0.5=0.5→0→max(1)=1

    @Test fun onboarding_truncates() = assertEquals(2, onboardingAmount(5)) // 5*0.5=2.5→2

    // ── Locale 钉死（2026-07-12 性能线程专项 K1·钱路）──
    // %04d/%02d 走默认 Locale：阿拉伯语系设备上数字被本地化（202606 → ٢٠٢٦٠٦），与账本里旧 key
    // 失配 → transactionExists 判「本月未发」→ 工资重复发放。修复 = format 钉 Locale.ROOT。
    // 断言与既有金标同字面量：锁「任何默认 Locale 下 key 恒为 ASCII」。
    @Test fun salary_key_ascii_under_arabic_digit_locale() = withDefaultLocale(Locale.forLanguageTag("ar")) {
        assertEquals("salary_abc_202606", salaryKey("abc", 2026, 6))
        assertEquals("salary_abc_202611", salaryKey("abc", 2026, 11))
    }
}
