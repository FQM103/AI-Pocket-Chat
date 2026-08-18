package com.situ.aichat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 反推 iOS `DateFormatters.relativeTimeString(from:style:.compact)` 的阈值与分支，断言取自 iOS 源码定义值
 * （非 Kotlin 输出），以抓移植 bug。关键：iOS「X 小时前 / 昨天」用**自然日**（`isDateInToday`/
 * `isDateInYesterday`）判定，非区间——昨天 23:00 在今天 00:30 看应是「昨天」而非「1 小时前」。
 */
class DateFormattersRelativeTimeTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val strings = DateFormatters.RelativeTimeStrings(
        justNow = "Just now",
        minutesAgo = "%1\$d minutes ago",
        hoursAgo = "%1\$d hours ago",
        yesterday = "Yesterday %1\$s",
    )

    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi, 0).atZone(zone).toInstant().toEpochMilli()

    private fun rel(postMillis: Long, nowMillis: Long): String =
        DateFormatters.relativeTimeString(postMillis, nowMillis, strings, zone)

    @Test
    fun `under one minute is just now`() {
        val now = ms(2024, 3, 15, 12, 0)
        assertEquals("Just now", rel(now - 30_000L, now))
        assertEquals("Just now", rel(now - 59_000L, now))
    }

    @Test
    fun `minutes branch under one hour`() {
        val now = ms(2024, 3, 15, 12, 0)
        assertEquals("1 minutes ago", rel(now - 60_000L, now))
        // 59m59s 仍属分钟分支（< 3600s），整除得 59。
        assertEquals("59 minutes ago", rel(now - (59L * 60 + 59) * 1000, now))
    }

    @Test
    fun `hours branch only within the same calendar day`() {
        val now = ms(2024, 3, 15, 14, 0)
        assertEquals("2 hours ago", rel(ms(2024, 3, 15, 12, 0), now))
    }

    @Test
    fun `yesterday late-night beats the hours branch`() {
        // 昨天 23:00 在今天 00:30 查看：区间 1.5h（≥3600s）但跨自然日且是昨天 → 「昨天 HH:mm」。
        val now = ms(2024, 3, 15, 0, 30)
        assertEquals("Yesterday 23:00", rel(ms(2024, 3, 14, 23, 0), now))
    }

    @Test
    fun `within seven days shows weekday and time`() {
        val now = ms(2024, 3, 15, 12, 0)
        // 3 天前（> 昨天、< 7 天）→ 周几 + HH:mm；HH:mm 部分确定（24 小时制）。
        val result = rel(ms(2024, 3, 12, 9, 5), now)
        assertTrue("expected weekday+time ending in 09:05, got: $result", result.endsWith("09:05"))
        assertTrue(result != "Yesterday 09:05")
    }

    @Test
    fun `beyond seven days same year shows month-day`() {
        val now = ms(2024, 3, 15, 12, 0)
        assertEquals("3/1", rel(ms(2024, 3, 1, 8, 0), now))
    }

    @Test
    fun `beyond seven days cross year shows year-month-day`() {
        val now = ms(2024, 1, 5, 12, 0)
        assertEquals("2023/12/20", rel(ms(2023, 12, 20, 8, 0), now))
    }

    @Test
    fun `future timestamp falls back to clock time`() {
        val now = ms(2024, 3, 15, 12, 0)
        assertEquals("12:05", rel(ms(2024, 3, 15, 12, 5), now))
    }
}

/**
 * P1-1（批1）：`.detailed` 档尾分支（=iOS MdHm/yMdHm 模板）——同年「M/d HH:mm」、跨年「yyyy/M/d HH:mm」；
 * 七天内/小时/分钟/昨天分支与 compact 完全一致。供气泡合并朗读句的时间部分。
 */
class DateFormattersRelativeTimeDetailedTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val strings = DateFormatters.RelativeTimeStrings(
        justNow = "Just now",
        minutesAgo = "%1\$d minutes ago",
        hoursAgo = "%1\$d hours ago",
        yesterday = "Yesterday %1\$s",
    )

    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi, 0).atZone(zone).toInstant().toEpochMilli()

    private fun relDetailed(postMillis: Long, nowMillis: Long): String =
        DateFormatters.relativeTimeString(postMillis, nowMillis, strings, zone, detailed = true)

    @Test
    fun `same year beyond seven days adds time - MdHm`() {
        val now = ms(2024, 3, 15, 12, 0)
        assertEquals("3/1 09:05", relDetailed(ms(2024, 3, 1, 9, 5), now))
    }

    @Test
    fun `cross year adds time - yMdHm`() {
        val now = ms(2024, 3, 15, 12, 0)
        assertEquals("2023/12/31 23:30", relDetailed(ms(2023, 12, 31, 23, 30), now))
    }

    @Test
    fun `near branches identical to compact`() {
        val now = ms(2024, 3, 15, 12, 0)
        assertEquals("Just now", relDetailed(now - 30_000L, now))
        assertEquals("5 minutes ago", relDetailed(now - 5 * 60_000L, now))
        assertEquals("Yesterday 23:00", relDetailed(ms(2024, 3, 14, 23, 0), now))
    }

    @Test
    fun `compact default unchanged by new parameter`() {
        val now = ms(2024, 3, 15, 12, 0)
        assertEquals("3/1", DateFormatters.relativeTimeString(ms(2024, 3, 1, 9, 5), now, strings, zone))
    }
}
