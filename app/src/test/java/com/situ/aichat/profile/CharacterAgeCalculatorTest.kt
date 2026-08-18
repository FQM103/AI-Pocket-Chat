package com.situ.aichat.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * [CharacterAgeCalculator] 单测——断言反推 iOS `AICharacter.currentAge`：
 * fixed 模式 fixedAge>0 才有值、否则 null；growing 模式按生日整年、生日缺失 null、不满 1 岁=0。
 */
class CharacterAgeCalculatorTest {

    private val utc = ZoneOffset.UTC
    private fun millis(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(utc).toInstant().toEpochMilli()

    @Test fun fixedMode_usesFixedAgeOnlyWhenPositive() {
        assertEquals(25, CharacterAgeCalculator.currentAge("fixed", 25, null, millis(2020, 1, 1), utc))
        assertNull(CharacterAgeCalculator.currentAge("fixed", 0, millis(2000, 1, 1), millis(2020, 1, 1), utc))
    }

    @Test fun growingMode_nullWithoutBirthday() {
        assertNull(CharacterAgeCalculator.currentAge("growing", 0, null, millis(2020, 1, 1), utc))
    }

    @Test fun growingMode_fullYearsFromBirthday() {
        val birthday = millis(2000, 6, 15)
        assertEquals(20, CharacterAgeCalculator.currentAge("growing", 0, birthday, millis(2020, 6, 15), utc))
        // 生日前一天 → 还差一天满 20，记 19。
        assertEquals(19, CharacterAgeCalculator.currentAge("growing", 0, birthday, millis(2020, 6, 14), utc))
        // 不满 1 岁 → 0（iOS dateComponents([.year]) 同样返回 0）。
        assertEquals(0, CharacterAgeCalculator.currentAge("growing", 0, millis(2020, 1, 1), millis(2020, 6, 1), utc))
    }

    @Test fun unknownMode_treatedAsGrowing() {
        assertEquals(10, CharacterAgeCalculator.currentAge("", 0, millis(2010, 3, 3), millis(2020, 3, 3), utc))
    }
}
