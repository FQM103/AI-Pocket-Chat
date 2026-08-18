package com.situ.aichat.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * relativeDay 单测（对齐 iOS DateFormatters.relativeDay）：今天/昨天/N天前/明天/后天/N天后，
 * 按设备时区的日历日做差。用固定时区（Asia/Shanghai，国行无夏令时）+ 固定日期保证确定性。
 */
class DateFormattersTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 某日在该时区当天 00:00 的 epoch millis。 */
    private fun day(y: Int, m: Int, d: Int): Long =
        LocalDate.of(y, m, d).atStartOfDay(zone).toInstant().toEpochMilli()

    @Test fun today() {
        assertEquals("今天", DateFormatters.relativeDay(day(2026, 3, 15), day(2026, 3, 15), zone))
    }

    @Test fun pastDays() {
        assertEquals("昨天", DateFormatters.relativeDay(day(2026, 3, 14), day(2026, 3, 15), zone))
        assertEquals("3天前", DateFormatters.relativeDay(day(2026, 3, 12), day(2026, 3, 15), zone))
        assertEquals("30天前", DateFormatters.relativeDay(day(2026, 2, 13), day(2026, 3, 15), zone))
    }

    @Test fun futureDays() {
        assertEquals("明天", DateFormatters.relativeDay(day(2026, 3, 16), day(2026, 3, 15), zone))
        assertEquals("后天", DateFormatters.relativeDay(day(2026, 3, 17), day(2026, 3, 15), zone))
        assertEquals("3天后", DateFormatters.relativeDay(day(2026, 3, 18), day(2026, 3, 15), zone))
    }

    @Test fun crossesMonthBoundary() {
        // 3/1 相对 3/15 = 14 天前（跨边界不出错）
        assertEquals("14天前", DateFormatters.relativeDay(day(2026, 3, 1), day(2026, 3, 15), zone))
    }

    @Test fun timeOfDayIgnoredWithinSameCalendarDay() {
        val midnight = day(2026, 3, 15)
        val almostNextDay = midnight + 23 * 3600_000L // 同一日历日晚些时候
        assertEquals("今天", DateFormatters.relativeDay(midnight, almostNextDay, zone))
    }

    // ---- momentTimeDescription（对齐 iOS threadSafeMomentTimeDescription）----

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        java.time.LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    /** 绝对时间「M月d日 HH:mm」+ 期望相对后缀。 */
    private fun abs(millis: Long) = DateFormatters.chineseMonthDayHourMinute(millis, zone)

    @Test fun momentTimeRelativeSuffixes() {
        val now = at(2026, 3, 15, 12, 0)
        val justNow = now - 30_000L
        assertEquals(abs(justNow) + " · 刚刚", DateFormatters.momentTimeDescription(justNow, now, zone))
        val fiveMin = now - 5 * 60_000L
        assertEquals(abs(fiveMin) + " · 5分钟前", DateFormatters.momentTimeDescription(fiveMin, now, zone))
        val twoHour = at(2026, 3, 15, 10, 0) // 同日 2 小时前
        assertEquals(abs(twoHour) + " · 2小时前", DateFormatters.momentTimeDescription(twoHour, now, zone))
        val yesterday = at(2026, 3, 14, 12, 0)
        assertEquals(abs(yesterday) + " · 昨天", DateFormatters.momentTimeDescription(yesterday, now, zone))
        val threeDays = at(2026, 3, 12, 12, 0)
        assertEquals(abs(threeDays) + " · 3天前", DateFormatters.momentTimeDescription(threeDays, now, zone))
    }

    @Test fun momentTimeNoSuffixBeyondSevenDaysOrFuture() {
        val now = at(2026, 3, 15, 12, 0)
        val eightDays = at(2026, 3, 7, 12, 0)  // >7 天 → 仅绝对时间
        assertEquals(abs(eightDays), DateFormatters.momentTimeDescription(eightDays, now, zone))
        val future = at(2026, 3, 16, 12, 0)    // 未来 → 仅绝对时间
        assertEquals(abs(future), DateFormatters.momentTimeDescription(future, now, zone))
    }
}
