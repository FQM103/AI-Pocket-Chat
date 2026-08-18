package com.situ.aichat.moments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parity with iOS `checkAndGeneratePosts` guards. Assertions reverse-derived from iOS: cooldown is
 * exactly 4 hours and strictly `<` (boundary not cooling); daily cap trips at `count >= frequency`.
 */
class MomentPostGuardsTest {

    private val now = 1_700_000_000_000L
    private val hour = 3600_000L

    @Test fun `cooldown constant is four hours`() {
        assertEquals(4L * 3600 * 1000, MomentPostGuards.POST_COOLDOWN_MS)
        assertEquals(14_400_000L, MomentPostGuards.POST_COOLDOWN_MS)
    }

    @Test fun `no previous post means not cooling`() {
        assertFalse(MomentPostGuards.isCooldownActive(null, now))
    }

    @Test fun `within four hours is cooling, at or beyond is not`() {
        assertTrue(MomentPostGuards.isCooldownActive(now - 3 * hour, now))      // 3h < 4h → cooling
        assertTrue(MomentPostGuards.isCooldownActive(now - (4 * hour - 1), now)) // just under 4h → cooling
        assertFalse(MomentPostGuards.isCooldownActive(now - 4 * hour, now))     // exactly 4h → not (strict <)
        assertFalse(MomentPostGuards.isCooldownActive(now - 5 * hour, now))     // 5h → not cooling
    }

    @Test fun `daily cap trips at count greater-or-equal frequency`() {
        assertFalse(MomentPostGuards.isDailyCapReached(0, 2))
        assertFalse(MomentPostGuards.isDailyCapReached(1, 2))
        assertTrue(MomentPostGuards.isDailyCapReached(2, 2))   // == frequency → reached
        assertTrue(MomentPostGuards.isDailyCapReached(3, 2))
    }
}
