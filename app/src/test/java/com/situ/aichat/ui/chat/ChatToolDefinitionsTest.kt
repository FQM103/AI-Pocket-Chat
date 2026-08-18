package com.situ.aichat.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `buildChatToolDefinitions`（H5·#7 解绑「日历集成」连坐）：日历工具只在集成开时下发；
 * 线下/约见面工具与日历无关、只要走工具路就下发（不再被日历开关连坐回暗号）。
 */
class ChatToolDefinitionsTest {

    private fun names(includeCalendar: Boolean, canInitiate: Boolean) =
        buildChatToolDefinitions(includeCalendar, canInitiate).map { it.function.name }

    @Test fun calendar_tool_only_when_integration_on() {
        assertTrue(names(includeCalendar = true, canInitiate = true).contains("calendar_action"))
        assertFalse(names(includeCalendar = false, canInitiate = true).contains("calendar_action"))
    }

    @Test fun offline_and_future_decoupled_from_calendar() {
        // 关了日历（includeCalendar=false），线下/约见面工具仍下发——这正是 H5 解绑的目的。
        val n = names(includeCalendar = false, canInitiate = true)
        assertTrue(n.contains("suggest_offline_meeting"))
        assertTrue(n.contains("end_offline_meeting"))
        assertTrue(n.contains("propose_future_meeting"))
    }

    @Test fun offline_suggest_filtered_when_cannot_initiate() {
        val n = names(includeCalendar = true, canInitiate = false)
        assertFalse("不能主动邀约 → 过滤 suggest", n.contains("suggest_offline_meeting"))
        assertTrue("保留 end（可结束已开始的见面）", n.contains("end_offline_meeting"))
        assertTrue(n.contains("propose_future_meeting"))
        assertTrue(n.contains("calendar_action"))
    }
}
