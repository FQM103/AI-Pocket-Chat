package com.situ.aichat.data.calendar

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 日历事件格式化纯函数单测（P5.3a / P5.3b / P12.6 D7）。格式 **D7 后**：`[#E{n}] 标题（相对日 M月d日 HH:mm~HH:mm · 地点）`
 * ——标题在前、时间(+地点)入括号，使卡片解析器 splitTitleAndDate（括号即日期）正确得 标题/时间·地点（修 iOS 格式器↔解析器
 * 错配，有意偏离 iOS）。相对日来自 DateFormatters.relativeDay；P5.3b 增 `#E{n}` → 事件 id 映射断言。
 */
class CalendarReaderTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    @Test fun formatEventsBlock_refsRelativeTimeTitleLocation() {
        val now = ms(2026, 5, 31, 10, 0)
        val events = listOf(
            CalendarReader.CalEvent("开会", ms(2026, 5, 31, 14, 0), ms(2026, 5, 31, 15, 0), "A会议室"),
            CalendarReader.CalEvent("体检", ms(2026, 6, 1, 9, 0), ms(2026, 6, 1, 10, 0), null),
        )
        val block = CalendarReader.formatEventsBlock(events, now, zone)
        assertEquals(
            "[#E1] 开会（今天 5月31日 14:00~15:00 · A会议室）\n" +
                "[#E2] 体检（明天 6月1日 09:00~10:00）",
            block.text,
        )
    }

    @Test fun formatEventsBlock_blankTitleFallbackAndBlankLocationDropped() {
        val now = ms(2026, 5, 31, 10, 0)
        val events = listOf(
            CalendarReader.CalEvent("  ", ms(2026, 5, 31, 14, 0), ms(2026, 5, 31, 15, 0), "   "),
        )
        assertEquals(
            "[#E1] 无标题（今天 5月31日 14:00~15:00）",
            CalendarReader.formatEventsBlock(events, now, zone).text,
        )
    }

    @Test fun formatEventsBlock_buildsRefMapToEventIds() {
        val now = ms(2026, 5, 31, 10, 0)
        val events = listOf(
            CalendarReader.CalEvent("开会", ms(2026, 5, 31, 14, 0), ms(2026, 5, 31, 15, 0), null, eventId = 42L),
            CalendarReader.CalEvent("体检", ms(2026, 6, 1, 9, 0), ms(2026, 6, 1, 10, 0), null, eventId = 99L),
        )
        assertEquals(mapOf("#E1" to 42L, "#E2" to 99L), CalendarReader.formatEventsBlock(events, now, zone).eventRefMap)
    }
}
