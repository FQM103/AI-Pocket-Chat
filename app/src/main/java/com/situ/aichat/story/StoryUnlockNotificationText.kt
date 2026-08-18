package com.situ.aichat.story

/**
 * 故事章节「解锁」通知的标题/正文（1:1 iOS `StoryScheduleService.scheduleUnlockNotification` :115-116）。
 *
 * 区别于完成通知（[StoryGenerationNotificationText]）：解锁通知标题是通用「📖 新章节已解锁」、正文用「」框故事名+章名
 * （完成通知标题带故事名、正文用《》框章名）。抽纯 internal object 便于单测锁定 iOS 字面量（emoji+空格、「」、！全角）。
 * 与 iOS 一致硬编码中文（iOS 用 Swift 字面量、非 NSLocalizedString）。
 */
internal object StoryUnlockNotificationText {

    /** 解锁通知标题（iOS `"📖 新章节已解锁"`，emoji 后有一个空格）。 */
    fun title(): String = "📖 新章节已解锁"

    /** 解锁通知正文（iOS `"「\(storyTitle)」第\(chapterNumber)章「\(chapterTitle)」已解锁，快来阅读！"`）。 */
    fun body(storyTitle: String, chapterNumber: Int, chapterTitle: String): String =
        "「${storyTitle}」第${chapterNumber}章「${chapterTitle}」已解锁，快来阅读！"
}
