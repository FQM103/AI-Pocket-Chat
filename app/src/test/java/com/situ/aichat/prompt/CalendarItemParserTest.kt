package com.situ.aichat.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日历卡片行解析纯函数单测（P5.3b）。**断言对齐 iOS `CalendarItemParserTests`**（前 4 例逐字镜像），
 * 另加 2 例锁定真实 zh 卡片行（formatEventsBlock 输出）的 title/date 拆分——拆分逻辑 1:1 iOS，含其「相对日当标题」的特性。
 */
class CalendarItemParserTest {

    @Test fun containsCalendarRefs_detectsEventAndReminderTags() {
        assertTrue(CalendarItemParser.containsCalendarRefs("See [#E1] Meeting"))
        assertTrue(CalendarItemParser.containsCalendarRefs("Remember [#R2] Buy milk"))
        assertFalse(CalendarItemParser.containsCalendarRefs("Plain text only"))
    }

    @Test fun parse_buildsMixedTextAndCalendarSegments() {
        val content = "We should plan this.\n[#E1] Team sync (2025-01-01 09:30)\nBring the notes."
        val segments = CalendarItemParser.parse(content)

        assertEquals(3, segments.size)
        assertEquals(CalendarItemParser.Segment.Text("We should plan this."), segments[0])
        val item = (segments[1] as CalendarItemParser.Segment.Item).item
        assertEquals(CalendarItemParser.ItemType.EVENT, item.type)
        assertEquals(1, item.index)
        assertEquals("Team sync", item.title)
        assertEquals("2025-01-01 09:30", item.dateInfo)
        assertEquals(CalendarItemParser.Segment.Text("Bring the notes."), segments[2])
    }

    @Test fun stripCalendarRefs_removesOnlyTags() {
        assertEquals("Team sync\nBuy milk", CalendarItemParser.stripCalendarRefs("[#E1] Team sync\n[#R2] Buy milk"))
    }

    @Test fun parse_malformedBareTag_fallsBackToPlainText() {
        val content = "[#E1]\nFollow up later"
        val segments = CalendarItemParser.parse(content)
        assertEquals(1, segments.size)
        assertEquals(CalendarItemParser.Segment.Text("[#E1]\nFollow up later"), segments[0])
    }

    // 真实 zh 卡片行（CalendarReader.formatEventsBlock 产出，AI 照抄）。**P12.6 D7 后格式 = `标题（时间 · 地点）`**：
    // 括号即日期 → 括号前作 title=事件名、括号内作 dateInfo=时间(·地点)，标题/日期归位（修 iOS 把「时间+标题」当标题、
    // 「地点」当日期的错配；splitTitleAndDate 逻辑不变，靠格式器改对齐）。
    @Test fun parse_realChineseCardLineWithLocation() {
        val item = (CalendarItemParser.parse("[#E1] 开会（今天 5月31日 14:00~15:00 · A会议室）")[0]
            as CalendarItemParser.Segment.Item).item
        assertEquals("开会", item.title)
        assertEquals("今天 5月31日 14:00~15:00 · A会议室", item.dateInfo)
    }

    @Test fun parse_realChineseCardLineNoLocation() {
        val item = (CalendarItemParser.parse("[#E2] 体检（明天 6月1日 09:00~10:00）")[0]
            as CalendarItemParser.Segment.Item).item
        assertEquals(2, item.index)
        assertEquals("体检", item.title)
        assertEquals("明天 6月1日 09:00~10:00", item.dateInfo)
    }
}
