package com.situ.aichat.story

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `StoryScrollRestoreLogic` 测试，反推 iOS `StoryReadingProgressStore` 的 minimumOffsetToSave=200 阈值
 * （`:90,108`）映射到 Compose LazyColumn「首项内偏移 < 200 且仍在首项 → 视为接近顶部不保存」。
 */
class StoryScrollRestoreLogicTest {

    @Test fun first_item_below_threshold_not_saved() {
        assertFalse(StoryScrollRestoreLogic.shouldSave(0, 0))
        assertFalse(StoryScrollRestoreLogic.shouldSave(0, 199))
    }

    @Test fun first_item_at_or_above_threshold_saved() {
        assertTrue(StoryScrollRestoreLogic.shouldSave(0, 200))
        assertTrue(StoryScrollRestoreLogic.shouldSave(0, 5000))
    }

    @Test fun past_first_item_always_saved() {
        assertTrue(StoryScrollRestoreLogic.shouldSave(1, 0))
        assertTrue(StoryScrollRestoreLogic.shouldSave(3, 10))
    }

    @Test fun threshold_constant_matches_ios() {
        assertEquals(200, StoryScrollRestoreLogic.MIN_OFFSET_TO_SAVE)
    }
}
