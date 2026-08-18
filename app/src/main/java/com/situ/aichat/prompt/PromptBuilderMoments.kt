package com.situ.aichat.prompt

import com.situ.aichat.util.DateFormatters

/**
 * 朋友圈互动上下文模块内容（M06 7.2.6）。1:1 移植 iOS `PromptBuilder+Modules.buildMomentsContextContent`
 * （:718-747）。把近 7 天角色与用户的朋友圈互动摘要（由 `MomentChatContextService` 装配好、经
 * [PromptBuilder.BuildContext.momentChatContext] 传入）渲染成聊天 system prompt 块。
 *
 * 模块内独立注入当前时刻作为相对时间锚点（即使用户关 timeAwareness 也自洽），用 `ctx.now` 与其他模块同
 * 一个「现在」避免错位。文案硬编码中文（LLM 产品资产，同 schedule/currentMoment 模块约定，非双语）。
 * 无互动（context 为 null / 空）→ 返回 ""（模块跳过）。
 */
internal fun buildMomentsContextContent(ctx: PromptBuilder.BuildContext): String {
    val moment = ctx.momentChatContext ?: return ""
    if (moment.isEmpty) return ""

    val nowStr = DateFormatters.yearMonthDayHourMinute(ctx.now.toEpochMilli())
    val parts = mutableListOf<String>()
    parts.add("[最近的朋友圈互动]")
    parts.add(
        "当前时刻：$nowStr。以下是你和${ctx.resolvedUserName}最近 7 天在朋友圈的互动记录，" +
            "每条都标注了发布时间（如「4月19日 09:00 · 3天前」）：",
    )
    parts.add("- 请根据每条的时间标签判断新旧，不要把几天前的当成刚刚发生的")
    parts.add("- 可以在聊天中自然提起，但不要每次都提，也不要反复重提同一条")
    parts.add("- 超过 2 天的内容用「前几天」「上周」等措辞，不要用「刚刚」「今天」")

    if (moment.characterPostsSummary.isNotEmpty()) {
        parts.add("")
        parts.add("[你（${ctx.resolvedCharacterName}）发的朋友圈动态]：")
        parts.add(moment.characterPostsSummary)
    }
    if (moment.userPostsSummary.isNotEmpty()) {
        parts.add("")
        parts.add("[${ctx.resolvedUserName}发的朋友圈动态（你有互动的）]：")
        parts.add(moment.userPostsSummary)
    }

    return parts.joinToString("\n")
}
