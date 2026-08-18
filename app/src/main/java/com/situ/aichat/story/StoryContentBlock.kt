package com.situ.aichat.story

/**
 * 正文文字样式（1:1 iOS `StoryContentParser.swift` `StoryTextStyle` :3-12）。
 * raw 即标记 `[text:style]` 的 style 值；阅读器按此渲染（11.1i）。
 */
enum class StoryTextStyle(val raw: String) {
    NORMAL("normal"),
    WHISPER("whisper"),
    SHOUT("shout"),
    THOUGHT("thought"),
    TREMBLING("trembling"),
    ANGRY("angry"),
    EXCITED("excited"),
    EMPHASIS("emphasis"),
    ;

    companion object {
        /** 按 raw 查（未知样式返回 null，对齐 iOS `StoryTextStyle(rawValue:)`）。 */
        fun fromRaw(raw: String): StoryTextStyle? = entries.firstOrNull { it.raw == raw }
    }
}

/**
 * 正文解析后的一个块（源自 iOS `StoryContentParser.swift` `StoryContentBlock` :14-22）。
 * 阅读器据此渲染正文 / 场景切换 / 章末。
 *
 * **2026-08-03 格式块精简**：`MoodChange` / `Weather` / `Effect` / `Pause` 四类随氛围演出层整族退役
 * （标签在解析端已落入未知桶被剥净），只剩排版三类。
 */
sealed interface StoryContentBlock {
    /** 一段正文（带样式）。 */
    data class Text(val text: String, val style: StoryTextStyle) : StoryContentBlock
    /** 场景切换标记。 */
    data class SceneTransition(val text: String) : StoryContentBlock
    /** 章节结束标记。 */
    data object ChapterEnd : StoryContentBlock
}
