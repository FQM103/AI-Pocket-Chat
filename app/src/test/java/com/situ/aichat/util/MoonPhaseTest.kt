package com.situ.aichat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.min

/**
 * T1：月相纯函数（图纸 2026-07-10-见面回忆那晚的天色 SKY-1）。
 * 断言从天文年历独立反推：2024-01-11 11:57 UTC 朔、2024-01-25 17:54 UTC 望（容差 ±0.035 相位 ≈ ±1 天）。
 */
class MoonPhaseTest {

    private fun utc(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    @Test
    fun `历元本身 - 相位为 0`() {
        assertEquals(0.0, MoonPhase.fraction(utc(2000, 1, 6, 18, 14)), 0.001)
    }

    @Test
    fun `已知朔 2024-01-11 - 相位贴近 0 且照亮率近 0`() {
        val t = utc(2024, 1, 11, 11, 57)
        val f = MoonPhase.fraction(t)
        assertTrue("fraction=$f", min(f, 1 - f) < 0.035)
        assertTrue("illumination=${MoonPhase.illumination(t)}", MoonPhase.illumination(t) < 0.05)
    }

    @Test
    fun `已知望 2024-01-25 - 相位贴近 0_5 且照亮率近 1`() {
        val t = utc(2024, 1, 25, 17, 54)
        val f = MoonPhase.fraction(t)
        assertTrue("fraction=$f", abs(f - 0.5) < 0.035)
        assertTrue("illumination=${MoonPhase.illumination(t)}", MoonPhase.illumination(t) > 0.95)
    }

    @Test
    fun `朔望之间盈亏方向正确`() {
        // 朔后 7 天 ≈ 上弦（盈）；望后 7 天 ≈ 下弦（亏）。
        assertTrue(MoonPhase.isWaxing(utc(2024, 1, 18, 12, 0)))
        assertTrue(!MoonPhase.isWaxing(utc(2024, 2, 1, 12, 0)))
    }

    @Test
    fun `历元之前的时间 - 相位仍落在 0 到 1 区间`() {
        for (t in listOf(0L, utc(1990, 6, 15, 0, 0), utc(1970, 1, 1, 0, 0))) {
            val f = MoonPhase.fraction(t)
            assertTrue("t=$t fraction=$f", f >= 0.0 && f < 1.0)
        }
    }
}
