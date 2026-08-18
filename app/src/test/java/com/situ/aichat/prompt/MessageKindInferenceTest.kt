package com.situ.aichat.prompt

import com.situ.aichat.data.model.MessageKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [MessageKindInference] 规格锁定：含 [#E1]/[#R1] 的在线 AI 文本 → SCHEDULE_CARD；其余 → PLAIN_TEXT；
 * 线下叙事一律 PLAIN_TEXT。前台与忙碌/恢复/通知三后台路共用此单点，口径一致由本测试钉死。
 */
class MessageKindInferenceTest {

    @Test
    fun `含日历引用的在线文本标日程卡`() {
        assertEquals(MessageKind.SCHEDULE_CARD, MessageKindInference.forAssistantText("[#E1] 周六聚餐", isOfflineMode = false))
        assertEquals(MessageKind.SCHEDULE_CARD, MessageKindInference.forAssistantText("好呀～\n[#R1] 记得带伞", isOfflineMode = false))
    }

    @Test
    fun `普通文本标纯文本`() {
        assertEquals(MessageKind.PLAIN_TEXT, MessageKindInference.forAssistantText("今天天气不错", isOfflineMode = false))
        assertEquals(MessageKind.PLAIN_TEXT, MessageKindInference.forAssistantText("", isOfflineMode = false))
    }

    @Test
    fun `线下叙事即使含日历引用也不标日程卡`() {
        // 线下见面叙事不渲染日程卡（offlineSessionId != null → isOfflineMode=true）。
        assertEquals(MessageKind.PLAIN_TEXT, MessageKindInference.forAssistantText("[#E1] 周六聚餐", isOfflineMode = true))
    }
}
