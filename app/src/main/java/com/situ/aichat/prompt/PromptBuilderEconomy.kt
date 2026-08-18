package com.situ.aichat.prompt

/**
 * 角色经济状态提示词模块（1:1 iOS `CharacterEconomicStateService.buildChatPromptBlock`）。从预计算的
 * `ctx.economicState` 渲染 `<economic_state>` 块：压力档位 + 近期花销 + 语言风格软引导。**不含具体数字**
 * （让 LLM 不主动念余额/月薪）。无经济状态（月薪 0 / 无钱包，调用方已判 null）→ 空串跳过。半角冒号对齐 iOS。
 */
internal fun buildCharacterEconomicStateContent(ctx: PromptBuilder.BuildContext): String {
    val state = ctx.economicState ?: return ""
    val lines = ArrayList<String>()
    lines.add("<economic_state>")
    lines.add("压力档位:${state.level.promptLabel}")
    if (state.recentEventSummaries.isNotEmpty()) {
        lines.add("最近花销:")
        state.recentEventSummaries.forEach { lines.add("- $it") }
    }
    lines.add(state.level.promptGuidance)
    lines.add("</economic_state>")
    return lines.joinToString("\n")
}
