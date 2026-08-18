package com.situ.aichat.prompt

import com.situ.aichat.data.model.MessageKind

/**
 * 助手文本消息落库时的「类型推断」**单一来源**。
 *
 * 唯一规则：一段普通在线聊天的 AI 文本，若含日历引用（[CalendarItemParser.containsCalendarRefs]，即 AI 照抄的
 * `[#E1]`/`[#R1]` 行）→ 标 [MessageKind.SCHEDULE_CARD]（渲染成日程卡）；否则 [MessageKind.PLAIN_TEXT]。
 * 线下见面叙事（isOfflineMode=true）一律不做日历卡识别（线下不渲染日程卡）。
 *
 * 收敛为单点后，前台聊天回复与各后台落库路径（忙碌延迟 / 未答恢复 / 通知物化）口径一致——不再出现
 * 「同样含 `[#E1]` 的回复，前台渲成日程卡、后台渲成裸文本」的不一致。
 *
 * 注：本函数只决定「渲染成哪种卡」。真正把日历事件放进「待确认队列」是另一条独立路径
 * （[com.situ.aichat.ui.chat.ChatViewModel] applyParsedCalendarActions·需用户在场逐条确认·前台专属）；
 * 后台路径无用户在场、无法确认，故只对齐渲染、不建 / 不排队日历事件。
 */
object MessageKindInference {
    fun forAssistantText(content: String, isOfflineMode: Boolean): MessageKind =
        if (!isOfflineMode && CalendarItemParser.containsCalendarRefs(content)) {
            MessageKind.SCHEDULE_CARD
        } else {
            MessageKind.PLAIN_TEXT
        }
}
