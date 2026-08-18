package com.situ.aichat.ui.chat

import com.situ.aichat.data.remote.llm.ToolDefinitionDto
import com.situ.aichat.tooling.ChatToolContext
import com.situ.aichat.tooling.chatToolRegistry

/**
 * 组装本轮聊天要下发的工具定义（①·C-5：遍历活跃工具盒子 [chatToolRegistry] 取各自 schema·替代硬编码各文件）。
 *
 * 行为与旧硬编码版逐字节等价（0-2 请求 JSON golden 看门·ChatToolDefinitionsTest 名单看门）：
 * - **日历**工具仅在 [includeCalendarTool]（=「日历集成」开）时下发（[com.situ.aichat.tooling.CalendarChatTool]）。
 * - **线下见面**工具按 [canInitiateOffline] 过滤 `suggest_offline_meeting`、保留 `end_offline_meeting`
 *   （[com.situ.aichat.tooling.OfflineChatTool]·H5 与日历开关解绑）。
 * - **约见面**工具恒下发（[com.situ.aichat.tooling.FutureMeetingChatTool]）。
 *
 * 遍历顺序 = registry 顺序（日历 → 线下 → 约见面）= 旧 buildList 顺序，故 tools 数组字节不变。
 */
internal fun buildChatToolDefinitions(
    includeCalendarTool: Boolean,
    canInitiateOffline: Boolean,
): List<ToolDefinitionDto> {
    // schema 不依赖 toolCallingEnabled（本函数仅工具路调用）；传 true 占位。
    val ctx = ChatToolContext(
        toolCallingEnabled = true,
        includeCalendarTool = includeCalendarTool,
        canInitiateOffline = canInitiateOffline,
    )
    return chatToolRegistry.flatMap { it.toolDefinitions(ctx) }
}
