package com.situ.aichat.diagnostics

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 单条工具调用记录（[LogToolInfo.calls] 成员）。
 *
 * @param name 工具名（如 `suggest_offline_meeting`）。
 * @param argsPreview 参数 JSON 预览（≤ [LogToolInfo.ARGS_PREVIEW_MAX] 字符）；detail 关时被
 *   [LogToolInfo.sanitized] 剥为 null——参数可能含用户相关内容（地点/活动），隐私口径与
 *   `fullContext`/`responseContent` 一致：名与计数=元数据恒存，内容按 detail 开关。
 */
@Serializable
data class LogToolCall(
    val name: String,
    val argsPreview: String? = null,
)

/**
 * 一次聊天回合的工具调用遥测（2026-07-12·上下文日志工具可见性）：回答「本轮带了哪些工具、AI 调没调用、
 * 解析成了什么、有没有降级」——此前上下文日志只存提示词与回复正文，工具链路全程不可见，用户无从确认
 * 「提示词要求调用工具」之后链路是否真的走通。
 *
 * 存储：JSON 序列化进 [com.situ.aichat.data.local.entity.LogEntryEntity.toolInfoJson]（仅聊天管线非空；
 * 旧行/后台生成路恒空串 → 详情页整节隐藏，不误示「未调用」）。装配用 [toolTurn]/[marker] 工厂
 * （纯函数——`AssistantTurnEngine` 是⛔行数大户「只许+接线」，逻辑体收拢在此）。
 */
@Serializable
data class LogToolInfo(
    /** 本轮轨道：[MODE_TOOL]=结构化工具调用；[MODE_MARKER]=文本暗号（模型不支持工具或本轮降级装配）。 */
    val mode: String,
    /** 随请求下发的工具名清单（marker 轨恒空）。 */
    val sentTools: List<String> = emptyList(),
    /** 模型实际发起的调用（按流内顺序）；空 = 带了工具但模型本轮没调用。 */
    val calls: List<LogToolCall> = emptyList(),
    /** 解析产出计数：日历动作 / 线下见面动作 / 约见面候选。 */
    val parsedCalendarActions: Int = 0,
    val parsedOfflineActions: Int = 0,
    val parsedMeetingCandidates: Int = 0,
    /** 工具流异常、或调用全军覆没（有失败且零可用产出）→ 整轮已降级纯文本重发。 */
    val fellBackToPlainText: Boolean = false,
    /** 模型只回调用无正文 → 已回喂工具结果取回文字（fetchToolCallFollowUp）。 */
    val usedTextFollowUp: Boolean = false,
) {

    /** detail 关 → 剥参数预览（元数据恒存、内容按开关，与 fullContext 同一隐私口径）。detail 开原样返回。 */
    fun sanitized(detailEnabled: Boolean): LogToolInfo =
        if (detailEnabled || calls.none { it.argsPreview != null }) this
        else copy(calls = calls.map { it.copy(argsPreview = null) })

    /** 序列化（失败 → 空串=「无遥测」，日志绝不影响主流程）。 */
    fun encode(json: Json): String =
        runCatching { json.encodeToString(serializer(), this) }.getOrDefault("")

    companion object {
        const val MODE_TOOL = "tool"
        const val MODE_MARKER = "marker"

        /** 参数预览截断上限：预览非全文（超长 arguments 不撑爆日志行；要全文看完整上下文）。 */
        const val ARGS_PREVIEW_MAX = 200

        /** 文本暗号轨遥测（本轮未下发工具，线下/约见面指令以文字标记注入）。 */
        fun marker(): LogToolInfo = LogToolInfo(mode = MODE_MARKER)

        /**
         * 工具轨遥测装配（纯函数）：[calls] = (工具名, 原始 arguments JSON)，参数在此截断为预览；
         * 空 arguments → 无预览。引擎只传原料，不在引擎里写装配逻辑。
         */
        fun toolTurn(
            sentToolNames: List<String>,
            calls: List<Pair<String, String>>,
            parsedCalendarActions: Int = 0,
            parsedOfflineActions: Int = 0,
            parsedMeetingCandidates: Int = 0,
            fellBackToPlainText: Boolean = false,
            usedTextFollowUp: Boolean = false,
        ): LogToolInfo = LogToolInfo(
            mode = MODE_TOOL,
            sentTools = sentToolNames,
            calls = calls.map { (name, args) ->
                LogToolCall(name = name, argsPreview = args.take(ARGS_PREVIEW_MAX).takeIf { it.isNotEmpty() })
            },
            parsedCalendarActions = parsedCalendarActions,
            parsedOfflineActions = parsedOfflineActions,
            parsedMeetingCandidates = parsedMeetingCandidates,
            fellBackToPlainText = fellBackToPlainText,
            usedTextFollowUp = usedTextFollowUp,
        )

        /** 反序列化（空串=旧行/无遥测、损坏 → null，调用方整节隐藏，绝不崩）。 */
        fun decode(json: Json, encoded: String): LogToolInfo? {
            if (encoded.isEmpty()) return null
            return runCatching { json.decodeFromString(serializer(), encoded) }.getOrNull()
        }
    }
}
