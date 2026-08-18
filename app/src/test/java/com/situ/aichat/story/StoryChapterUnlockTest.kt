package com.situ.aichat.story

import com.situ.aichat.data.local.entity.StoryChapterEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `isUnlocked` / `unlockRemainingMinutes`（11.1h-3）测试，反推 iOS `StoryChapter.isUnlocked`
 * （unlockAt==null || now>=unlockAt）+ 倒计时分钟。
 */
class StoryChapterUnlockTest {

    private val now = 1_700_000_000_000L

    private fun ch(unlockAt: Long?) = StoryChapterEntity(id = "c", storyId = "s", unlockAt = unlockAt)

    @Test fun null_unlock_is_always_unlocked() {
        assertTrue(ch(null).isUnlocked(now))
    }

    @Test fun past_unlock_is_unlocked() {
        assertTrue(ch(now - 1_000).isUnlocked(now))
        assertTrue(ch(now).isUnlocked(now)) // now>=unlockAt 边界含等于
    }

    @Test fun future_unlock_is_locked() {
        assertFalse(ch(now + 60_000).isUnlocked(now))
    }

    @Test fun remaining_minutes_floor() {
        assertEquals(0L, unlockRemainingMinutes(null, now))
        assertEquals(0L, unlockRemainingMinutes(now - 1, now)) // 已过 → 0
        assertEquals(2L, unlockRemainingMinutes(now + 2 * 60_000L + 59_000L, now)) // 2分59秒 → floor 2
        assertEquals(90L, unlockRemainingMinutes(now + 90 * 60_000L, now)) // 1.5h → 90 分
    }
}
