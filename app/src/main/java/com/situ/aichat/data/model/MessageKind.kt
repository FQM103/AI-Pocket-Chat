package com.situ.aichat.data.model

/**
 * 1:1 port of iOS `MessageKind`. Persisted to `MessageEntity.messageKindRaw` (rawValue strings match iOS).
 * Most messages are [PLAIN_TEXT]; structured kinds (offline / call / system / gift / red packet) carry
 * JSON or marker text in `content` and are handled specially by the prompt pipeline.
 */
enum class MessageKind(val raw: String) {
    PLAIN_TEXT("plain_text"),
    OFFLINE_INVITE_CARD("offline_invite_card"),
    OFFLINE_END_CARD("offline_end_card"),
    CALL_RECORD_CARD("call_record_card"),
    OFFLINE_MARKER_START("offline_marker_start"),
    OFFLINE_MARKER_END("offline_marker_end"),
    SYSTEM_EVENT_CARD("system_event_card"),
    SYSTEM_HINT("system_hint"),
    GIFT_CARD("gift_card"),
    RED_PACKET("red_packet"),

    /** 日历条目卡片（P5.3b）：AI 原样照抄的 `[#E1] …` 行，渲染为日程卡片（对齐 iOS CalendarItemInlineBadge）。 */
    SCHEDULE_CARD("schedule_card"),

    /** 未来约定见面确认卡（JSON·待确认带答应/换时间/先不约·或已约定/已婉拒回执）。结构化卡：绝不喂 LLM 原文。 */
    FUTURE_MEETING_PROPOSAL_CARD("future_meeting_proposal"),

    /** 未来约定见面「变更确认卡」（JSON·识别到 AI 想改期/取消**已确认**约定时插·用户点头才动真理源）。结构化卡：绝不喂 LLM 原文。 */
    FUTURE_MEETING_CHANGE_CARD("future_meeting_change");

    /** 是否属于"线下见面事件流"的结构化卡片（PromptBuilder 构建历史时剥离）。穷举 when·新增类型编译器强制分类。 */
    val isOfflineEventCard: Boolean
        get() = when (this) {
            OFFLINE_INVITE_CARD, OFFLINE_END_CARD, OFFLINE_MARKER_START, OFFLINE_MARKER_END -> true
            PLAIN_TEXT, CALL_RECORD_CARD, SYSTEM_EVENT_CARD, SYSTEM_HINT, GIFT_CARD, RED_PACKET, SCHEDULE_CARD,
            FUTURE_MEETING_PROPOSAL_CARD, FUTURE_MEETING_CHANGE_CARD -> false
        }

    /**
     * 是否为「结构化消息」——content 是 JSON 或内部标记文本，**绝不能当普通文本直接示人 / 复制原文 / 喂模型原文**
     * （礼物 / 红包 / 通话 / 红包结算 / 线下邀约·结束卡 / 线下入场·离场标记）。穷举 when（无 else）：新增类型时
     * 编译器强制在此分类，从源头杜绝「忘了处理 → 原始 JSON / 标记泄漏给用户」这一类 bug。
     */
    val isStructuredCard: Boolean
        get() = when (this) {
            OFFLINE_INVITE_CARD, OFFLINE_END_CARD, CALL_RECORD_CARD, OFFLINE_MARKER_START,
            OFFLINE_MARKER_END, SYSTEM_EVENT_CARD, GIFT_CARD, RED_PACKET, FUTURE_MEETING_PROPOSAL_CARD,
            FUTURE_MEETING_CHANGE_CARD -> true
            // 文本类（可读·非 JSON）：纯文本 / 带 [#E1] 标签的日程文本 / 系统耳语旁白。
            PLAIN_TEXT, SCHEDULE_CARD, SYSTEM_HINT -> false
        }

    companion object {
        private val byRaw = entries.associateBy { it.raw }

        /** 从 rawValue 还原；未知值保守回退 [PLAIN_TEXT]。 */
        fun fromRaw(raw: String): MessageKind = byRaw[raw] ?: PLAIN_TEXT
    }
}
