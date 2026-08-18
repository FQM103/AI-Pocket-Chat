package com.situ.aichat.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * [ZodiacCalculator] 单测——断言反推 iOS `ZodiacCalculator.zodiacSign(for:)` 的逐段日期区间，
 * 重点验各星座**交界日**（cusp）落在正确一侧，避免移植时区间错一天。
 */
class ZodiacCalculatorTest {

    @Test fun cuspBoundaries_landOnCorrectSign() {
        assertEquals("摩羯座 ♑", ZodiacCalculator.zodiacSign(1, 19))
        assertEquals("水瓶座 ♒", ZodiacCalculator.zodiacSign(1, 20))
        assertEquals("水瓶座 ♒", ZodiacCalculator.zodiacSign(2, 18))
        assertEquals("双鱼座 ♓", ZodiacCalculator.zodiacSign(2, 19))
        assertEquals("双鱼座 ♓", ZodiacCalculator.zodiacSign(3, 20))
        assertEquals("白羊座 ♈", ZodiacCalculator.zodiacSign(3, 21))
        assertEquals("双子座 ♊", ZodiacCalculator.zodiacSign(6, 15))
        assertEquals("巨蟹座 ♋", ZodiacCalculator.zodiacSign(6, 22))
        assertEquals("射手座 ♐", ZodiacCalculator.zodiacSign(12, 21))
        assertEquals("摩羯座 ♑", ZodiacCalculator.zodiacSign(12, 22))
    }

    @Test fun outOfRange_returnsEmpty() {
        assertEquals("", ZodiacCalculator.zodiacSign(0, 0))
        assertEquals("", ZodiacCalculator.zodiacSign(13, 5))
    }

    @Test fun epochMillisOverload_resolvesViaDate() {
        val millis = LocalDate.of(1995, 8, 23).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals("处女座 ♍", ZodiacCalculator.zodiacSign(millis, ZoneOffset.UTC))
    }
}
