package com.situ.aichat.story

/**
 * 故事创建目录（1:1 iOS `StoryCreationCatalog`，`StoryCreationView.swift:438-505`）。
 *
 * genres/writingStyles 是数据（存 story.genre/writingStyle + 注入 LLM prompt），中文不本地化；
 * coverColorScheme 映射封面配色 key（书架 [com.situ.aichat.ui.story] StoryCard 消费）；
 * 聊天影响档/连载模式/章节长度的**显示文案**走 strings.xml（UI 层映射），此处只持 raw 值。
 */
object StoryCreationCatalog {
    val genres = listOf("言情", "悬疑", "奇幻", "科幻", "都市", "恐怖", "校园", "历史", "末日", "日常")

    val writingStyles = listOf("轻松幽默", "严肃文学", "网文爽文", "日系轻小说", "哥特暗黑", "古风")

    /** 聊天影响档（顺序 none/light/medium/heavy；默认中度 = iOS dropFirst(2).first）。 */
    val chatInfluenceWeights = listOf(
        StoryChatInfluenceWeight.NONE,
        StoryChatInfluenceWeight.LIGHT,
        StoryChatInfluenceWeight.MEDIUM,
        StoryChatInfluenceWeight.HEAVY,
    )

    const val DEFAULT_CHAT_INFLUENCE = StoryChatInfluenceWeight.MEDIUM

    /** 类型 → 封面配色 key（1:1 iOS coverColorScheme）。 */
    fun coverColorScheme(genre: String): String = when (genre) {
        "言情" -> "rose"
        "悬疑" -> "amber"
        "奇幻" -> "violet"
        "科幻" -> "cyan"
        "都市" -> "slate"
        "恐怖" -> "crimson"
        "校园" -> "mint"
        "历史" -> "sepia"
        "末日" -> "rust"
        else -> "sky"
    }
}

/** 章节长度偏好（字数，1:1 iOS ChapterLengthOption；EXTRA_LONG=2026-07-27 安卓自研加档，iOS 无此档）。 */
enum class StoryChapterLength(val words: Int) {
    SHORT(500),
    MEDIUM(1500),
    LONG(3000),
    EXTRA_LONG(5000),
}

// 卷二·单模式化（用户拍板①）：`StorySerialMode`（无限/30/60/100/自定义）整个枚举已退役删除——
// 故事一律无限连载，收尾由终章弧承担（见 StoryArcPlanning）。别再加回来。
