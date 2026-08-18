package com.situ.aichat.data.model

/**
 * 1:1 port of iOS `MomentPromptContext` (Services/MomentGenerationService.swift:142-152) — 朋友圈互动
 * 注入**聊天**系统提示词的上下文（区别于 [com.situ.aichat.moments.MomentPromptContext]，那是发帖/评论
 * 生成的「此刻时间 / 日程」段）。两段预格式化摘要：
 * - [characterPostsSummary]：角色自己最近 7 天发的帖（含用户的点赞/评论反应），最多 3 条。
 * - [userPostsSummary]：用户最近 7 天发的、该角色有互动（赞/评）的帖，最多 3 条。
 *
 * 放在 `data/model`（中立层）而非 moments 包，避免 prompt 包（[com.situ.aichat.prompt.PromptBuilder]
 * 的 BuildContext 持有它）↔ moments 包（[com.situ.aichat.moments.MomentChatContextService] 构建它）
 * 的双向引用。由 [com.situ.aichat.moments.MomentChatContextService] 构建、PromptBuilder MOMENTS_CONTEXT
 * 模块渲染。
 */
data class MomentChatContext(
    val characterPostsSummary: String,
    val userPostsSummary: String,
) {
    /** 两段都空 = 无可注入内容（iOS `isEmpty`，调用方据此跳过模块）。 */
    val isEmpty: Boolean
        get() = characterPostsSummary.isEmpty() && userPostsSummary.isEmpty()
}
