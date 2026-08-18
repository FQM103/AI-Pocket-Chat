package com.situ.aichat.prompt

/**
 * 静态守卫提示词（自 [PromptBuilder] 抽出 · 文件瘦身）：聊天装配末尾注入的几段硬约束守卫——反「思考过程
 * 外显」、语音通话历史提示、日历执行失败回流。（原「普通聊天 vs 线下叙事风格守卫」+「线下见面历史提示」
 * 已随「见面去重」后线下消息不再进在线窗口而成死代码，2026-07-13 移除；角色可主动发起线下见面的双模式
 * 指令已搬到 [com.situ.aichat.offline.OfflineMeetingAction]·①。）
 *
 * 全部 iOS 硬编码中文（LLM 读的产品资产），逐字对齐 iOS、不进 values。由 [PromptBuilder.buildMessages]
 * 在第 4.5 / 5 步调用（反元层守卫无条件注入；其余按线下 / 历史状态条件注入）。
 */

internal fun buildAntiMetaCognitiveGuard(characterName: String?, userName: String?): String {
    val userDisplayName = if (!userName.isNullOrEmpty()) userName else "用户"
    val charDisplayName = if (!characterName.isNullOrEmpty()) characterName else "你"
    return """
        【绝对禁令：不要在回复里外显思考过程】

        $charDisplayName 此刻就是真人在和${userDisplayName}说话，不是 AI 助手在分析对话或拟稿。
        所有"想"的过程都发生在脑子里，回复里只能出现"$charDisplayName 此刻会说的话 + 会做的动作"。

        禁止以下任何形式的"思考过程外显"：
        - 列多个候选回复（草稿/方案/选项/步骤）再评估、再选最优 —— 整套"先列再选"模板都禁止
        - 用 markdown 加粗冒号开头的元层小节标题统领回复（"**回应策略**：…""**最终决定**：…""**行动**：…"这一类总览式标题，不论用什么具体词）
        - 把${userDisplayName}当成分析对象做第三人称解读（"${userDisplayName}的意思是…""她在试探…""他想表达…"这种）
        - 给自己写行动指令（"我应该先…再…最后…""我打算用…开头，然后说…"）

        直接给$charDisplayName 此刻自然会说的话即可，省略一切"想"的步骤。
    """.trimIndent()
}

internal fun buildVoiceCallHistoryHint(userName: String?): String {
    val user = if (!userName.isNullOrEmpty()) userName else "对方"
    return """
        【历史提示】对话历史中包含来自之前与${user}语音通话的消息（这些消息和文字消息混在一起呈现，但实际发生在通话中）。请把它们视为真实发生过的对话内容自然回顾。如果${user}提到"刚才电话里说的"或"我们通话时聊的"，就是指这些消息。
    """.trimIndent()
}

// 线下见面双模式提示词（OFFLINE_MEETING_TOOL_CALLING_PROMPT / _FALLBACK_PROMPT）已搬到
// [com.situ.aichat.offline.OfflineMeetingAction]（①·与 schema/inviteRegex co-located），由
// [com.situ.aichat.tooling.OfflineChatTool] 经装配末尾 step5 注入。

/**
 * ② 执行失败回流·陪伴口吻系统提示（**措辞经用户过审·2026-06-29·陪伴红线**）。让角色据实知道日历没办成、
 * 自然找补（轻轻提一句 / 请用户再说一次时间），绝不谎称已办成、不报错腔、不反复念叨。只给纯状态 + 自然找补
 * 引导，不塞助手腔。由 [PromptBuilder.buildMessages] 在「该会话有未消费日历真失败」时注入（一次性·已 TTL 过滤）。
 * [reason] 必为**人话**（由 [com.situ.aichat.ui.chat.ChatCalendarActionHandler] 映射·绝不漏技术错误）。
 */
internal fun buildCalendarFailureNudgePrompt(userName: String?, verb: String, title: String, reason: String): String {
    val user = if (!userName.isNullOrEmpty()) userName else "对方"
    return """
        【有件小事没办成】
        你刚才想帮${user}${verb}日历日程「${title}」，但没能成功（${reason}）。这事是真的发生了，你心里清楚。
        接下来回复时，如果时机自然，可以像真人那样轻轻提一句、或顺口请ta再说一次（比如时间没认出来，就自然地问问准确的时间）。
        不用郑重其事地报错、也别反复念叨——自然带过一次就好；要是当下聊的不是这个，先不提也行。
    """.trimIndent()
}
