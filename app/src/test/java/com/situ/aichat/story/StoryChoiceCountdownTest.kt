package com.situ.aichat.story

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `StoryChoiceCountdown` 测试（反悔窗口 4 秒·ST7d/J2·向上取整剩余秒、到点提交）。
 * 窗口时长为唯一事实源常量，改档须同步本测。
 */
class StoryChoiceCountdownTest {

    private val deadline = 1_000_000L

    @Test fun window_constants_are_four_seconds() {
        assertEquals(4, StoryChoiceCountdown.WINDOW_SECONDS)
        assertEquals(4_000L, StoryChoiceCountdown.WINDOW_MS)
    }

    @Test fun remaining_rounds_up() {
        assertEquals(4, StoryChoiceCountdown.remainingSeconds(deadline, deadline - 4000)) // 整 4s
        assertEquals(4, StoryChoiceCountdown.remainingSeconds(deadline, deadline - 3999)) // 3.999→4
        assertEquals(4, StoryChoiceCountdown.remainingSeconds(deadline, deadline - 3001)) // 3.001→4
        assertEquals(3, StoryChoiceCountdown.remainingSeconds(deadline, deadline - 3000)) // 整 3s
        assertEquals(1, StoryChoiceCountdown.remainingSeconds(deadline, deadline - 1)) // 0.001→1
    }

    @Test fun remaining_clamps_to_zero_at_or_past_deadline() {
        assertEquals(0, StoryChoiceCountdown.remainingSeconds(deadline, deadline))
        assertEquals(0, StoryChoiceCountdown.remainingSeconds(deadline, deadline + 500))
    }

    @Test fun expiry_at_deadline() {
        assertFalse(StoryChoiceCountdown.isExpired(deadline, deadline - 1))
        assertTrue(StoryChoiceCountdown.isExpired(deadline, deadline))
        assertTrue(StoryChoiceCountdown.isExpired(deadline, deadline + 1))
    }
}
