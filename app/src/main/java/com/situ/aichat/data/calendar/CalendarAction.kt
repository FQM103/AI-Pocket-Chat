package com.situ.aichat.data.calendar

import com.situ.aichat.data.remote.llm.FunctionDefinitionDto
import com.situ.aichat.data.remote.llm.FunctionParametersDto
import com.situ.aichat.data.remote.llm.ParameterPropertyDto
import com.situ.aichat.data.remote.llm.ToolDefinitionDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 1:1 port of iOS `CalendarAction` (Models/CalendarAction.swift) — AI 从回复里解析出的日历操作指令。
 *
 * **安卓平台缺口**：iOS 有独立「提醒事项」(EKReminder)，安卓没有 → 7 个动作类型按 iOS 全量保留（解析忠实、防御
 * 未广告的提醒动作），但**只执行 create/update/delete_event 三个事件类**；提醒类 [isEventAction]=false，
 * 上层（[com.situ.aichat.ui.chat.ChatViewModel]）回「暂不支持」。提示词也只广告事件类（[buildAwarenessPrompt]）。
 */
enum class CalendarActionType(val raw: String) {
    @SerialName("create_event") CREATE_EVENT("create_event"),
    @SerialName("create_reminder") CREATE_REMINDER("create_reminder"),
    @SerialName("update_event") UPDATE_EVENT("update_event"),
    @SerialName("delete_event") DELETE_EVENT("delete_event"),
    @SerialName("update_reminder") UPDATE_REMINDER("update_reminder"),
    @SerialName("delete_reminder") DELETE_REMINDER("delete_reminder"),
    @SerialName("complete_reminder") COMPLETE_REMINDER("complete_reminder"),
}

@Serializable
data class CalendarAction(
    val action: CalendarActionType,
    val title: String = "",
    /** 引用编号，如 "#E1"（修改/删除时必需）。 */
    val ref: String? = null,
    /** ISO 8601 或常见日期格式。 */
    val startDate: String? = null,
    val endDate: String? = null,
    /** 提醒事项用（安卓不执行）。 */
    val dueDate: String? = null,
    val notes: String? = null,
    val location: String? = null,
) {
    /** 是否为事件类操作（vs 提醒类）。安卓只执行事件类。 */
    val isEventAction: Boolean
        get() = when (action) {
            CalendarActionType.CREATE_EVENT,
            CalendarActionType.UPDATE_EVENT,
            CalendarActionType.DELETE_EVENT,
            -> true
            else -> false
        }

    // MARK: - 日期解析（返回设备时区 epoch 毫秒，供 CalendarContract 写入）

    fun parsedStartMillis(zone: ZoneId = ZoneId.systemDefault()): Long? =
        startDate?.let { parseDate(it, zone) }

    fun parsedEndMillis(zone: ZoneId = ZoneId.systemDefault()): Long? =
        endDate?.let { parseDate(it, zone) }

    // MARK: - 展示文案（用于确认卡片 / toast）

    /** 类型显示名（名词部分）。安卓只剩事件类，提醒类兜底「提醒事项」用于不支持提示。 */
    val typeDisplayName: String
        get() = if (isEventAction) "日历事件" else "提醒事项"

    /** 操作动词。 */
    val actionVerb: String
        get() = when (action) {
            CalendarActionType.CREATE_EVENT, CalendarActionType.CREATE_REMINDER -> "创建"
            CalendarActionType.UPDATE_EVENT, CalendarActionType.UPDATE_REMINDER -> "修改"
            CalendarActionType.DELETE_EVENT, CalendarActionType.DELETE_REMINDER -> "删除"
            CalendarActionType.COMPLETE_REMINDER -> "完成"
        }

    /** 确认按钮文案。 */
    val confirmButtonText: String get() = "确认$actionVerb"

    /** 是否为删除操作（确认卡片显示「不可撤销」警告）。 */
    val isDeleteAction: Boolean
        get() = action == CalendarActionType.DELETE_EVENT || action == CalendarActionType.DELETE_REMINDER

    /** 格式化日期描述（确认卡片 / toast 展示），对齐 iOS displayDateDescription。 */
    fun displayDateDescription(zone: ZoneId = ZoneId.systemDefault()): String {
        return when (action) {
            CalendarActionType.CREATE_EVENT, CalendarActionType.UPDATE_EVENT -> {
                val start = parsedStartMillis(zone) ?: return ""
                val startStr = formatMillis(start, zone)
                val end = parsedEndMillis(zone)
                if (end != null) "$startStr - ${formatMillis(end, zone)}" else startStr
            }
            else -> ""
        }
    }

    /** Toast 显示文案，对齐 iOS toastDescription：动词+类型(+：标题)(+（日期）)。 */
    fun toastDescription(zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        append(actionVerb)
        append(typeDisplayName)
        if (title.isNotEmpty()) append("：$title")
        if (action == CalendarActionType.CREATE_EVENT || action == CalendarActionType.UPDATE_EVENT) {
            val dateDesc = displayDateDescription(zone)
            if (dateDesc.isNotEmpty()) append("（$dateDesc）")
        }
    }

    companion object {
        // coerceInputValues：`"title": null` → 默认 ""（等价 iOS decodeIfPresent ?? ""）；未知 action 枚举仍抛错 → 上层跳过。
        private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

        /** 结构化工具调用定义（1:1 iOS CalendarAction.toolDefinitions）：单个 calendar_action 工具。 */
        val toolDefinitions: List<ToolDefinitionDto> = listOf(
            ToolDefinitionDto(
                type = "function",
                function = FunctionDefinitionDto(
                    name = "calendar_action",
                    description = "Manage the user's calendar events and reminders. Supports creating, updating, and deleting events and reminders, as well as completing reminders. Update, delete, and complete actions require a ref ID such as #E1 or #R1.",
                    parameters = FunctionParametersDto(
                        type = "object",
                        properties = linkedMapOf(
                            "action" to ParameterPropertyDto("string", "Action type", CalendarActionType.entries.map { it.raw }),
                            "title" to ParameterPropertyDto("string", "The title of the event or reminder"),
                            "ref" to ParameterPropertyDto("string", "Reference ID, such as #E1 or #R1 (required for update, delete, and complete)"),
                            "startDate" to ParameterPropertyDto("string", "Event start time in ISO 8601 format"),
                            "endDate" to ParameterPropertyDto("string", "Event end time in ISO 8601 format"),
                            "dueDate" to ParameterPropertyDto("string", "Reminder due time in ISO 8601 format"),
                            "notes" to ParameterPropertyDto("string", "Notes"),
                            "location" to ParameterPropertyDto("string", "Location (events only)"),
                        ),
                        required = listOf("action", "title"),
                    ),
                ),
            ),
        )

        // — 日历感知 + 写入提示词（①·从 PromptBuilderCalendar 搬来·与 schema/calendarActionRegex co-located·逐字不变） —
        // 暗号版的 [CALENDAR_ACTION]{...} 写入教程格式与下面的 [calendarActionRegex] 强耦合（§5），搬到同文件后
        // 改任一处即看见另一处。由 PromptBuilder 的 USER_CALENDAR 感知模块经 buildCalendarAwarenessContent(ctx) 调用。

        /**
         * **感知段**（事件列表 + [#E] 引用 + 卡片格式）两种模式都注入；**[CALENDAR_ACTION] 写入指令段**仅文本暗号
         * 模式（!toolCallingEnabled）注入——工具模式走 calendar_action 工具、绝不再同时登暗号教程，否则模型同时看到
         * 「工具」+「暗号」两套、每轮随机挑一套 →「有时走工具、有时走暗号」的间歇行为不一致（H4·治 #1）。纯函数便于单测。
         */
        fun buildAwarenessPrompt(
            calendarIntegrationEnabled: Boolean,
            upcomingEvents: String?,
            userName: String,
            toolCallingEnabled: Boolean,
        ): String {
            if (!calendarIntegrationEnabled) return ""
            val events = upcomingEvents?.trim()?.takeIf { it.isNotEmpty() } ?: return ""
            val user = userName
            return buildString {
                append("【${user}的日程】\n")
                append("以下是${user}近期的日程安排，方括号里的 [#E1] 是引用编号。你可以在合适的时机自然地提及")
                append("（比如关心对方是否忙、提醒即将到来的事），但不要每次都刻意提。\n")
                // 卡片渲染（5.3b ②）：照抄 [#E1] 行 → 系统渲染成卡片。对齐 iOS text-mode「copy the full entry exactly」。
                append("当${user}问起日程、或你主动提到某个具体安排时，把上面列表里对应的那一行**原样照抄**")
                append("（含 [#E1] 编号、标题、时间、地点），一行一条；系统会把这些行渲染成卡片，${user}看不到方括号编号。\n")
                append("卡片行格式（严格）：每个 [#E…] 行必须独占一行；卡片行（或连续多张卡片）的前后各空一行，与聊天文字分开；")
                append("不要把聊天文字和卡片标签写在同一行，也不要让卡片行紧贴下一句聊天。示例：\n")
                append("我看看啊…\n\n[#E1] 开会（5月31日 14:00~15:00 · A会议室）\n\n就这些啦。\n\n")
                append("近期日程：\n")
                append(events)
                // [日历操作] 写入指令：仅文本暗号模式注入（对齐 iOS !toolCallingEnabled 分支，本次真正接上 gate）。
                if (!toolCallingEnabled) {
                    append("\n\n")
                    append("【日历操作】\n")
                    append("当${user}需要你帮忙管理日历事件时，在回复末尾、[mood:…] 标签之前，追加一条操作指令。用上面列表里的编号（如 #E1）引用已有事件。\n")
                    append("支持的操作：\n")
                    append("[CALENDAR_ACTION]{\"action\":\"create_event\",\"title\":\"标题\",\"startDate\":\"ISO日期\",\"endDate\":\"ISO日期\",\"notes\":\"备注\",\"location\":\"地点\"}[/CALENDAR_ACTION]\n")
                    append("[CALENDAR_ACTION]{\"action\":\"update_event\",\"ref\":\"#E1\",\"title\":\"新标题\",\"startDate\":\"新时间\"}[/CALENDAR_ACTION]\n")
                    append("[CALENDAR_ACTION]{\"action\":\"delete_event\",\"ref\":\"#E1\",\"title\":\"事件标题\"}[/CALENDAR_ACTION]\n")
                    append("规则：修改、删除操作必须包含 ref 编号；修改操作只需写要改动的字段；只在${user}明确要求时才操作，不要主动操作；日期用 ISO 8601 格式。这些标签由系统解析，${user}看不到原始格式。")
                }
            }
        }

        /** 缓存正则：匹配 [CALENDAR_ACTION]{...}[/CALENDAR_ACTION]（对齐 iOS calendarActionRegex）。 */
        private val calendarActionRegex =
            Regex("""\[CALENDAR_ACTION\]\s*([\s\S]*?)\s*\[/CALENDAR_ACTION\]""")

        /**
         * 从 LLM 回复中解析日历操作指令，返回 (清理后文本, 操作列表)。对齐 iOS `parseFromResponse`：
         * 逐个匹配解码，单条解码失败（含未知 action 枚举）静默跳过；最后移除全部标签并 trim。
         */
        /** 从结构化工具调用 arguments JSON 解析（1:1 iOS fromToolCallArguments = JSONDecoder.decode）；非法 JSON/未知 action 枚举抛异常。 */
        fun fromToolCallArguments(jsonArgs: String): CalendarAction =
            json.decodeFromString(serializer(), jsonArgs)

        fun parseFromResponse(response: String): Pair<String, List<CalendarAction>> {
            val matches = calendarActionRegex.findAll(response).toList()
            if (matches.isEmpty()) return response to emptyList()

            val actions = matches.mapNotNull { match ->
                val jsonString = match.groupValues[1].trim()
                if (jsonString.isEmpty()) return@mapNotNull null
                runCatching { json.decodeFromString(CalendarAction.serializer(), jsonString) }.getOrNull()
            }
            val cleanText = calendarActionRegex.replace(response, "").trim()
            return cleanText to actions
        }

        // MARK: - 日期解析（对齐 iOS parseDate：先 ISO8601 带时区，再本地时间格式兜底）

        private val MD_HM: DateTimeFormatter = DateTimeFormatter.ofPattern("M'月'd'日' HH:mm")

        /** 本地时间兜底格式（设备时区），对齐 iOS posixDateParsers（autoupdatingCurrent 时区）。 */
        private val localDateTimePatterns: List<DateTimeFormatter> = listOf(
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
        ).map { DateTimeFormatter.ofPattern(it) }

        private val dateOnlyPattern: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        /** 解析日期字符串为 epoch 毫秒；解析失败返回 null。 */
        internal fun parseDate(string: String, zone: ZoneId = ZoneId.systemDefault()): Long? {
            val trimmed = string.trim()
            if (trimmed.isEmpty()) return null
            // 1) ISO 8601 带时区/偏移（绝对时刻），等价 iOS ISO8601DateFormatter(.withInternetDateTime)
            runCatching { return OffsetDateTime.parse(trimmed).toInstant().toEpochMilli() }
            runCatching { return Instant.parse(trimmed).toEpochMilli() }
            // 2) 本地时间格式兜底（设备时区）
            for (fmt in localDateTimePatterns) {
                runCatching { return LocalDateTime.parse(trimmed, fmt).atZone(zone).toInstant().toEpochMilli() }
            }
            // 3) 仅日期 → 当天 0 点
            runCatching { return LocalDate.parse(trimmed, dateOnlyPattern).atStartOfDay(zone).toInstant().toEpochMilli() }
            return null
        }

        private fun formatMillis(millis: Long, zone: ZoneId): String =
            MD_HM.withZone(zone).format(Instant.ofEpochMilli(millis))
    }
}
