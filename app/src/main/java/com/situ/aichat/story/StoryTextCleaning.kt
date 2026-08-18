package com.situ.aichat.story

import com.situ.aichat.util.ThinkTagStripper

/**
 * 清理 LLM 创作输出里的「思考」标签——转发 [ThinkTagStripper]（单源，三条规则见其 KDoc）。
 *
 * 语义升级（2026-07-11 用户拍板，取代原 1:1 iOS `cleanContentThinkingTags` 的「孤立标签只删标签」行为）：
 * 未闭合开标签 = 输出在思考中途被截断，删到串尾；孤闭合标签 = 前文全是思考，连前文一起删。
 * 剥空 = 纯思考响应，调用方须走失败/重试路径（创作流抛 EmptyResponse、压缩两路计失败重试、
 * 大纲 ifEmpty 跳过、落库口 [StoryChapterMaterializer] 空稿守卫）。
 */
internal object StoryTextCleaning {

    fun cleanContentThinkingTags(content: String): String = ThinkTagStripper.strip(content)
}
