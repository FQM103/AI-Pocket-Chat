package com.situ.aichat.prompt

import com.situ.aichat.data.calendar.CalendarAction
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `CalendarAction.buildAwarenessPrompt`（①搬家后·原 `buildCalendarAwarenessContent` 纯函数）双登广告修复
 * （H4·#1）：工具模式下**不再**注入 `[CALENDAR_ACTION]` 暗号教程
 * （避免与 `calendar_action` 工具同时存在 → 模型每轮随机挑一套 → 「有时走工具、有时走暗号」的间歇行为不一致）；
 * 感知段（事件列表 / `[#E]` 引用）两模式都在（工具的 update/delete 也要靠 `#E` 引用）。
 */
class PromptBuilderCalendarTest {

    private val events = "[#E1] 开会（5月31日 14:00~15:00 · A会议室）"

    private fun content(toolCalling: Boolean, integration: Boolean = true) =
        CalendarAction.buildAwarenessPrompt(
            calendarIntegrationEnabled = integration,
            upcomingEvents = events,
            userName = "小雨",
            toolCallingEnabled = toolCalling,
        )

    @Test fun tool_mode_keeps_awareness_drops_marker_howto() {
        val s = content(toolCalling = true)
        assertTrue(s.contains("近期日程")) // 感知段在
        assertTrue(s.contains("[#E1]")) // #E 引用在（工具 update/delete 需要）
        assertFalse(s.contains("【日历操作】")) // 写入指令段不注入
        assertFalse(s.contains("[CALENDAR_ACTION]")) // 暗号教程不注入
    }

    @Test fun marker_mode_emits_both_awareness_and_marker_howto() {
        val s = content(toolCalling = false)
        assertTrue(s.contains("近期日程"))
        assertTrue(s.contains("【日历操作】"))
        assertTrue(s.contains("[CALENDAR_ACTION]"))
    }

    @Test fun integration_off_is_empty_both_modes() {
        assertTrue(content(toolCalling = true, integration = false).isEmpty())
        assertTrue(content(toolCalling = false, integration = false).isEmpty())
    }

    @Test fun no_events_is_empty() {
        assertTrue(
            CalendarAction.buildAwarenessPrompt(
                calendarIntegrationEnabled = true,
                upcomingEvents = "   ",
                userName = "小雨",
                toolCallingEnabled = false,
            ).isEmpty(),
        )
    }
}
