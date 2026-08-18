package com.situ.aichat.economy

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 月薪手动编辑相关纯函数单测（14.6b·断言反推 iOS CharacterWalletEditSheet.save / onboarding）：
 * [parseSalaryInput] 空/非数字→0 再 clamp[0,50000]；[onboardingAmount] = max(1, salary*0.5)。
 */
class SalaryEditTest {

    @Test fun parseSalary_empty_isZero() {
        assertEquals(0, parseSalaryInput(""))
        assertEquals(0, parseSalaryInput("   "))
    }

    @Test fun parseSalary_nonNumeric_isZero() {
        assertEquals(0, parseSalaryInput("abc"))
        assertEquals(0, parseSalaryInput("12a3"))
    }

    @Test fun parseSalary_clampsToCeiling() {
        assertEquals(50000, parseSalaryInput("99999"))
        assertEquals(50000, parseSalaryInput("50000"))
    }

    @Test fun parseSalary_normal() {
        assertEquals(8000, parseSalaryInput("8000"))
        assertEquals(8000, parseSalaryInput(" 8000 "))
    }

    @Test fun parseSalary_negativeSignNotDigit_isZero() {
        // 一元数字键盘不产生负号；含非数字字符→toIntOrNull null→0（与 UI 仅允许数字一致）。
        assertEquals(0, parseSalaryInput("-500"))
    }

    @Test fun onboardingAmount_floorAtOne() {
        assertEquals(1, onboardingAmount(1))     // 1*0.5=0.5→0→max(1,…)=1
        assertEquals(4000, onboardingAmount(8000))
        assertEquals(25000, onboardingAmount(50000))
    }
}
