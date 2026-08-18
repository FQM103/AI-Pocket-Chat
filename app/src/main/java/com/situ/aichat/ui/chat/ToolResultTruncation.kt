package com.situ.aichat.ui.chat

/**
 * 工具结果文本大输出安全阀（③·参照 RikkaHub `GenerationHandler.maybeTruncateToolOutput`，但**裁剪成陪伴所需**：
 * 我们无 shell、不落盘存文件，仅「截断 + 预览」，更简）。
 *
 * **前置基建·当前永不触发**：当前 3 工具的 `role=tool` 回喂文案都是短状态串（[toolFollowUpResultText]，几十字），
 * 远不及阈值 → 原样返回（Phase 0-3 golden 看门：包裹后字节不变）。将来若加「内容返回型工具」（结果含大段正文）
 * 经同一回喂单点 [com.situ.aichat.ui.chat.AssistantTurnEngine] 流过此阀 → 自动截断，防撑爆对话 / 烧 token。
 *
 * 纯函数·无副作用·可单测（边界见 ToolResultTruncationTest）。钱路无关。
 */

/** 工具结果文本上限（字符）。超过则截断为预览。参照 RikkaHub 32KB（我们按字符计·中文更宽松）。 */
internal const val MAX_TOOL_RESULT_CHARS: Int = 32 * 1024

/** 截断后保留的预览长度（字符）。参照 RikkaHub 4KB。 */
internal const val TOOL_RESULT_PREVIEW_CHARS: Int = 4 * 1024

/**
 * 超 [maxChars] → 返回「截断说明（含原始字符数）+ 前 [previewChars] 字符预览」；未超原样返回。
 * 边界：长度恰好 == [maxChars] 不截断；超 1 字符即截断；空串原样返回。
 */
internal fun truncateToolResultText(
    text: String,
    maxChars: Int = MAX_TOOL_RESULT_CHARS,
    previewChars: Int = TOOL_RESULT_PREVIEW_CHARS,
): String {
    if (text.length <= maxChars) return text
    val preview = text.take(previewChars)
    return "[工具输出过长已截断：原 ${text.length} 字符，仅保留前 $previewChars 字符]\n$preview"
}
