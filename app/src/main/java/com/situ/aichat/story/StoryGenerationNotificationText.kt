package com.situ.aichat.story

/**
 * 故事生成完成/失败通知的标题/正文（1:1 iOS `StoryGenerationTaskManager.sendNotification` :255-264）。
 *
 * 抽成纯 internal object 便于单测锁定 iOS 字面量（emoji+空格、《》、各分支文案）。与 iOS 一致硬编码中文：
 * iOS 这几条用 Swift 字符串插值字面量、**非 NSLocalizedString**，故安卓 1:1 也硬编码、不入 strings.xml
 * （渠道名/描述等系统层文案仍走 strings.xml）。
 */
internal object StoryGenerationNotificationText {

    /** 通知标题（iOS `"📖 \(storyTitle)"`，注意 emoji 后有一个空格）。 */
    fun title(storyTitle: String): String = "📖 $storyTitle"

    /**
     * 通知正文（iOS :256-264）：失败 → 「第N章生成失败，点击查看」；成功有章名 → 「第N章《名》已经写好了」；
     * 成功无章名 → 「第N章已经写好了」（iOS `if let chapterTitle` 仅判 nil，故空串仍走《》分支，1:1 保留）。
     */
    fun body(chapterNumber: Int, chapterTitle: String?, success: Boolean): String = when {
        !success -> "第${chapterNumber}章生成失败，点击查看"
        chapterTitle != null -> "第${chapterNumber}章《${chapterTitle}》已经写好了"
        else -> "第${chapterNumber}章已经写好了"
    }
}
