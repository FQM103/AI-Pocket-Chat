package com.situ.aichat.story

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `StoryGenerationNotificationText`（11.1f-2）测试，断言从 iOS `StoryGenerationTaskManager.sendNotification`
 * :255-264 反推：emoji+空格标题、成功《》/无章名、失败文案、空串仍走《》（iOS if-let 仅判 nil）。
 */
class StoryGenerationNotificationTextTest {

    @Test fun title_has_book_emoji_space_and_name() {
        assertEquals("📖 我的故事", StoryGenerationNotificationText.title("我的故事"))
    }

    @Test fun success_with_chapter_title_uses_book_quotes() {
        assertEquals("第3章《初遇》已经写好了", StoryGenerationNotificationText.body(3, "初遇", success = true))
    }

    @Test fun success_without_chapter_title_omits_quotes() {
        assertEquals("第3章已经写好了", StoryGenerationNotificationText.body(3, null, success = true))
    }

    @Test fun failure_uses_failed_text_regardless_of_title() {
        assertEquals("第5章生成失败，点击查看", StoryGenerationNotificationText.body(5, "忽略", success = false))
        assertEquals("第5章生成失败，点击查看", StoryGenerationNotificationText.body(5, null, success = false))
    }

    @Test fun success_empty_title_still_uses_quotes_like_ios_if_let() {
        // iOS `if let chapterTitle` 仅判 nil，空串仍走《》分支
        assertEquals("第1章《》已经写好了", StoryGenerationNotificationText.body(1, "", success = true))
    }
}
