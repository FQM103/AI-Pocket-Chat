package com.situ.aichat.ui.offline

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * T1：回忆长廊纯函数（图纸 §5 SKY-5a）。断言从契约 §9 文案规格独立反推；时区固定 Asia/Shanghai 保证确定性。
 */
class MeetingGalleryTimelineTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun millis(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun `中文月份 - 十二个月全表`() {
        val expected = listOf("一月", "二月", "三月", "四月", "五月", "六月", "七月", "八月", "九月", "十月", "十一月", "十二月")
        for (m in 1..12) assertEquals(expected[m - 1], chineseMonthLabel(m))
    }

    @Test
    fun `月份刻度 - 同年不带年·跨年带年`() {
        val now = millis(2026, 7, 10, 12, 0)
        assertEquals("六月", galleryMonthLabel(millis(2026, 6, 25, 6, 12), now, zone))
        assertEquals("2025年 十二月", galleryMonthLabel(millis(2025, 12, 3, 20, 0), now, zone))
    }

    @Test
    fun `统计句 - 次数与首场日期时段`() {
        assertEquals(
            "一起出去过 6 次 · 第一次是 6 月 25 日的清晨",
            galleryStatLine(6, millis(2026, 6, 25, 6, 12), zone),
        )
    }

    @Test
    fun `落款 - 首场日期时段`() {
        assertEquals(
            "长廊的尽头——6 月 25 日清晨，你们第一次见面",
            galleryEndLine(millis(2026, 6, 25, 6, 12), zone),
        )
        assertEquals(
            "长廊的尽头——1 月 2 日深夜，你们第一次见面",
            galleryEndLine(millis(2026, 1, 2, 23, 30), zone),
        )
    }

    @Test
    fun `年月分组键 - 跨月跨年各不同·同月相同`() {
        assertEquals(galleryYearMonth(millis(2026, 7, 1, 0, 0), zone), galleryYearMonth(millis(2026, 7, 31, 23, 0), zone))
        assertEquals(202607, galleryYearMonth(millis(2026, 7, 15, 12, 0), zone))
        assertEquals(202512, galleryYearMonth(millis(2025, 12, 31, 12, 0), zone))
    }
}
