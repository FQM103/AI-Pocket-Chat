package com.situ.aichat.story

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `StoryUnlockNotificationText`（11.1g-1）测试，断言从 iOS `StoryScheduleService.scheduleUnlockNotification`
 * :115-116 反推：通用标题 emoji+空格、正文「」框故事名+章名、全角！。
 */
class StoryUnlockNotificationTextTest {

    @Test fun title_is_generic_unlock_with_emoji_space() {
        assertEquals("📖 新章节已解锁", StoryUnlockNotificationText.title())
    }

    @Test fun body_wraps_story_and_chapter_in_corner_brackets() {
        assertEquals(
            "「我的故事」第3章「初遇」已解锁，快来阅读！",
            StoryUnlockNotificationText.body("我的故事", 3, "初遇"),
        )
    }
}
