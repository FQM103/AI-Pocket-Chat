package com.situ.aichat.data.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * 日历操作解析 + 日期解析纯函数单测（P5.3b）。**断言对齐 iOS `CalendarAction`**：
 * parseFromResponse 正则提取 + cleanText 去标签 + 单条解码失败/未知枚举跳过；parseDate 先 ISO8601(带时区)再本地格式兜底。
 */
class CalendarActionTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    // MARK: - parseFromResponse

    @Test fun parse_singleAction_stripsTagAndDecodes() {
        val resp = "好的，帮你记下来～[CALENDAR_ACTION]{\"action\":\"create_event\",\"title\":\"开会\",\"startDate\":\"2026-05-31T14:00\"}[/CALENDAR_ACTION]"
        val (clean, actions) = CalendarAction.parseFromResponse(resp)
        assertEquals("好的，帮你记下来～", clean)
        assertEquals(1, actions.size)
        assertEquals(CalendarActionType.CREATE_EVENT, actions[0].action)
        assertEquals("开会", actions[0].title)
    }

    @Test fun parse_multipleActions() {
        val resp = "[CALENDAR_ACTION]{\"action\":\"create_event\",\"title\":\"A\"}[/CALENDAR_ACTION]" +
            "[CALENDAR_ACTION]{\"action\":\"delete_event\",\"ref\":\"#E1\",\"title\":\"B\"}[/CALENDAR_ACTION]"
        val (clean, actions) = CalendarAction.parseFromResponse(resp)
        assertEquals("", clean)
        assertEquals(2, actions.size)
        assertEquals(CalendarActionType.CREATE_EVENT, actions[0].action)
        assertEquals(CalendarActionType.DELETE_EVENT, actions[1].action)
        assertEquals("#E1", actions[1].ref)
    }

    @Test fun parse_malformedJson_skippedButTagStripped() {
        val resp = "前面[CALENDAR_ACTION]{这不是json}[/CALENDAR_ACTION]后面"
        val (clean, actions) = CalendarAction.parseFromResponse(resp)
        assertEquals("前面后面", clean)
        assertTrue(actions.isEmpty())
    }

    @Test fun parse_unknownActionType_skipped() {
        val resp = "[CALENDAR_ACTION]{\"action\":\"fly_to_moon\",\"title\":\"x\"}[/CALENDAR_ACTION]"
        val (_, actions) = CalendarAction.parseFromResponse(resp)
        assertTrue(actions.isEmpty())
    }

    @Test fun parse_noTags_returnsOriginalAndEmpty() {
        val resp = "就是普通聊天内容，没有任何日历标签。"
        val (clean, actions) = CalendarAction.parseFromResponse(resp)
        assertEquals(resp, clean)
        assertTrue(actions.isEmpty())
    }

    // MARK: - isEventAction（提醒类安卓不执行）

    @Test fun isEventAction_eventsTrueRemindersFalse() {
        fun a(t: CalendarActionType) = CalendarAction(action = t, title = "x")
        assertTrue(a(CalendarActionType.CREATE_EVENT).isEventAction)
        assertTrue(a(CalendarActionType.UPDATE_EVENT).isEventAction)
        assertTrue(a(CalendarActionType.DELETE_EVENT).isEventAction)
        assertTrue(!a(CalendarActionType.CREATE_REMINDER).isEventAction)
        assertTrue(!a(CalendarActionType.COMPLETE_REMINDER).isEventAction)
    }

    // MARK: - 日期解析（对齐 iOS parseDate：ISO8601 带时区 → 本地格式兜底）

    @Test fun parseDate_iso8601WithZ_absoluteInstant() {
        val expected = OffsetDateTime.of(2026, 5, 31, 14, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals(expected, CalendarAction.parseDate("2026-05-31T14:00:00Z", zone))
    }

    @Test fun parseDate_iso8601WithOffset() {
        val expected = OffsetDateTime.of(2026, 5, 31, 14, 0, 0, 0, ZoneOffset.ofHours(8)).toInstant().toEpochMilli()
        assertEquals(expected, CalendarAction.parseDate("2026-05-31T14:00:00+08:00", zone))
    }

    @Test fun parseDate_localDateTimeNoZone_usesDeviceZone() {
        val expected = LocalDateTime.of(2026, 5, 31, 14, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, CalendarAction.parseDate("2026-05-31T14:00", zone))
        assertEquals(expected, CalendarAction.parseDate("2026-05-31 14:00", zone))
    }

    @Test fun parseDate_dateOnly_startOfDay() {
        val expected = LocalDate.of(2026, 5, 31).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(expected, CalendarAction.parseDate("2026-05-31", zone))
    }

    @Test fun parseDate_invalid_null() {
        assertNull(CalendarAction.parseDate("明天下午", zone))
        assertNull(CalendarAction.parseDate("", zone))
    }
}
