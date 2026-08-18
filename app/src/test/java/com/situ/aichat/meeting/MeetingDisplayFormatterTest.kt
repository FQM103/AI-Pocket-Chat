package com.situ.aichat.meeting

import com.situ.aichat.data.model.MeetingTimeGranularity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/**
 * 时间人话格式化单测。注入 Asia/Shanghai + 固定时刻，中英双路确定性。2026-06-27 = 周六；now = 2026-06-24 周三 15:30。
 */
class MeetingDisplayFormatterTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val zh = Locale.SIMPLIFIED_CHINESE
    private val en = Locale.ENGLISH

    private fun millis(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private val sat1500 = millis(2026, 6, 27, 15, 0)
    private val now = millis(2026, 6, 24, 15, 30)

    // ── whenDisplay ──

    @Test fun whenDisplay_exact_zh() {
        assertEquals("6月27日 周六 15:00", MeetingDisplayFormatter.whenDisplay(sat1500, MeetingTimeGranularity.EXACT, zone, zh))
    }

    @Test fun whenDisplay_dayOnly_zh() {
        assertEquals("6月27日 周六", MeetingDisplayFormatter.whenDisplay(sat1500, MeetingTimeGranularity.DAY_ONLY, zone, zh))
    }

    @Test fun whenDisplay_exact_en() {
        assertEquals("Sat, Jun 27 at 15:00", MeetingDisplayFormatter.whenDisplay(sat1500, MeetingTimeGranularity.EXACT, zone, en))
    }

    @Test fun whenDisplay_dayOnly_en() {
        assertEquals("Sat, Jun 27", MeetingDisplayFormatter.whenDisplay(sat1500, MeetingTimeGranularity.DAY_ONLY, zone, en))
    }

    // ── nowText（恒中文） ──

    @Test fun nowText_format() {
        assertEquals("2026-06-24 周三 15:30", MeetingDisplayFormatter.nowText(now, zone))
    }

    // ── countdownText ──

    @Test fun countdown_today_exact_zh() {
        val today1500 = millis(2026, 6, 24, 15, 0)
        assertEquals("今天 15:00", MeetingDisplayFormatter.countdownText(today1500, MeetingTimeGranularity.EXACT, now, zone, zh))
    }

    @Test fun countdown_today_dayOnly_zh() {
        val today = millis(2026, 6, 24, 19, 0)
        assertEquals("今天", MeetingDisplayFormatter.countdownText(today, MeetingTimeGranularity.DAY_ONLY, now, zone, zh))
    }

    @Test fun countdown_tomorrow_zh() {
        val tomorrow = millis(2026, 6, 25, 19, 0)
        assertEquals("明天", MeetingDisplayFormatter.countdownText(tomorrow, MeetingTimeGranularity.DAY_ONLY, now, zone, zh))
    }

    @Test fun countdown_threeDays_zh() {
        assertEquals("3天后", MeetingDisplayFormatter.countdownText(sat1500, MeetingTimeGranularity.EXACT, now, zone, zh))
    }

    @Test fun countdown_sevenPlus_absolute_zh() {
        val far = millis(2026, 7, 5, 15, 0) // 11 天后 → 绝对到天
        assertEquals("7月5日 周日", MeetingDisplayFormatter.countdownText(far, MeetingTimeGranularity.EXACT, now, zone, zh))
    }

    @Test fun countdown_tomorrow_en() {
        val tomorrow = millis(2026, 6, 25, 19, 0)
        assertEquals("Tomorrow", MeetingDisplayFormatter.countdownText(tomorrow, MeetingTimeGranularity.DAY_ONLY, now, zone, en))
    }

    @Test fun countdown_threeDays_en() {
        assertEquals("in 3 days", MeetingDisplayFormatter.countdownText(sat1500, MeetingTimeGranularity.EXACT, now, zone, en))
    }
}
