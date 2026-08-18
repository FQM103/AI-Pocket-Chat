package com.situ.aichat.tooling

import com.situ.aichat.data.calendar.CalendarAction
import com.situ.aichat.data.remote.llm.ToolDefinitionDto
import com.situ.aichat.meeting.FutureMeetingTool
import com.situ.aichat.offline.OfflineMeetingAction

/**
 * 聊天工具「自包含盒子」（①·借鉴 RikkaHub `Tool.systemPrompt`，裁剪成陪伴双轨所需）：把每个工具的
 * 「结构化定义 + 双模式提示词贡献」收到工具自己身上，治「提示词散在 `PromptBuilder*`、schema 在 `XxxAction`」
 * 两处漂移。
 *
 * **有意裁剪（与 RikkaHub 的差异）**：
 * - 不含 execute 闭包——执行仍走现有 `ChatReplyDeliverer` / `ChatCalendarActionHandler`（避免大爆炸）。
 * - marker 解析器保持分离（`CalendarAction.parseFromResponse` / 各 regex / `DirtyMessageDetector` 不收编）——
 *   两套并存的固有结构，强行收编只会扩大 §5 触碰面（本期建议：否）。
 * - 日历工具的提示词经其**感知模块**注入（[CalendarAction.buildAwarenessPrompt]），不是装配末尾的 step5 守卫，
 *   故 [stepFiveGuardPrompt] 默认 null——line-up 里日历只贡献 schema。
 */
internal data class ChatToolContext(
    /** 本轮是否走结构化工具路（决定 step5 守卫提示词选工具版/暗号版；不影响 schema）。 */
    val toolCallingEnabled: Boolean,
    /** 是否下发日历工具（= 日历集成开关）。 */
    val includeCalendarTool: Boolean,
    /**
     * 角色是否可主动发起线下见面（= `characterCanInitiateOfflineMeeting`）：既过滤 `suggest_offline_meeting`
     * schema，又 gate 线下 step5 守卫提示词（两者本同源·见 AssistantTurnEngine canInitiateOffline）。
     */
    val canInitiateOffline: Boolean,
)

internal interface ChatTool {
    /** 本工具下发的结构化定义（按 [ctx] 开关过滤）；空 = 本上下文不下发。供 [buildChatToolDefinitions] 遍历。 */
    fun toolDefinitions(ctx: ChatToolContext): List<ToolDefinitionDto>

    /**
     * 装配末尾（`PromptBuilder` 第 5 步·非线下分支）本工具贡献的「守卫」系统提示词段；null = 不贡献（默认）。
     * 注：日历工具的提示词经其感知模块注入、不在此 → 用默认 null。
     */
    fun stepFiveGuardPrompt(ctx: ChatToolContext): String? = null
}

/** 日历工具盒子：schema 来自 [CalendarAction]；提示词经感知模块（[CalendarAction.buildAwarenessPrompt]）非 step5。 */
internal object CalendarChatTool : ChatTool {
    override fun toolDefinitions(ctx: ChatToolContext): List<ToolDefinitionDto> =
        if (ctx.includeCalendarTool) CalendarAction.toolDefinitions else emptyList()
}

/** 线下见面工具盒子：schema + 双模式 step5 守卫提示词均来自 [OfflineMeetingAction]（co-located）。 */
internal object OfflineChatTool : ChatTool {
    override fun toolDefinitions(ctx: ChatToolContext): List<ToolDefinitionDto> =
        OfflineMeetingAction.toolDefinitions(ctx.canInitiateOffline)

    /** 仅「角色可主动发起线下见面」时贡献：工具路→工具版、暗号路→降级版（1:1 旧 PromptBuilder 第 5 步）。 */
    override fun stepFiveGuardPrompt(ctx: ChatToolContext): String? =
        if (!ctx.canInitiateOffline) null
        else if (ctx.toolCallingEnabled) OfflineMeetingAction.TOOL_CALLING_PROMPT
        else OfflineMeetingAction.FALLBACK_PROMPT
}

/** 约定未来见面工具盒子：schema + 暗号降级规则均已 co-located 在 [FutureMeetingTool]。 */
internal object FutureMeetingChatTool : ChatTool {
    override fun toolDefinitions(ctx: ChatToolContext): List<ToolDefinitionDto> =
        listOf(FutureMeetingTool.toolDefinition)

    /** 仅暗号路贡献降级文本规则；工具路靠 tools 数组里的 schema，无需 prompt（1:1 旧 PromptBuilder 第 5 步）。 */
    override fun stepFiveGuardPrompt(ctx: ChatToolContext): String? =
        if (!ctx.toolCallingEnabled) FutureMeetingTool.FALLBACK_MARKER_RULE else null
}

/**
 * 活跃工具清单（顺序 = 现行装配顺序：日历 → 线下 → 约定未来见面）。
 * [buildChatToolDefinitions] 遍历它取 schema；`PromptBuilder` 第 5 步遍历它取 step5 守卫提示词。
 */
internal val chatToolRegistry: List<ChatTool> = listOf(CalendarChatTool, OfflineChatTool, FutureMeetingChatTool)
