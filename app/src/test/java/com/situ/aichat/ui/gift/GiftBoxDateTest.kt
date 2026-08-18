package com.situ.aichat.ui.gift

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 收礼盒紧凑日期标签单测（d-5，断言反推 iOS `HistoryCard.compactDateLabel` 规则 + diyContentPreview）。
 * 用本地时区把「天」边界算成日历日（非 24h），与 iOS `Calendar.isDateInToday/isDateInYesterday` + day-component 一致。
 */
class GiftBoxDateTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    /** 锚定 now = 2026-06-15 12:00 本地，便于按日历日反推。 */
    private val now: Long = LocalDate.of(2026, 6, 15).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private fun atNoon(date: LocalDate): Long = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    @Test fun today() {
        assertEquals("今天", compactGiftDate(atNoon(LocalDate.of(2026, 6, 15)), now))
    }

    @Test fun yesterday() {
        assertEquals("昨天", compactGiftDate(atNoon(LocalDate.of(2026, 6, 14)), now))
    }

    @Test fun daysAgo_under7() {
        // 6/15 - 6/12 = 3 个日历日
        assertEquals("3天前", compactGiftDate(atNoon(LocalDate.of(2026, 6, 12)), now))
        // 6 天前（仍 <7）
        assertEquals("6天前", compactGiftDate(atNoon(LocalDate.of(2026, 6, 9)), now))
    }

    @Test fun sameYear_monthDay() {
        // ≥7 天且同年 → M月d日
        assertEquals("6月8日", compactGiftDate(atNoon(LocalDate.of(2026, 6, 8)), now))
        assertEquals("1月1日", compactGiftDate(atNoon(LocalDate.of(2026, 1, 1)), now))
    }

    @Test fun priorYear_yearMonth() {
        // 跨年 → yyyy年M月
        assertEquals("2025年12月", compactGiftDate(atNoon(LocalDate.of(2025, 12, 25)), now))
    }

    @Test fun diyContentPreview_truncatesAt14() {
        assertEquals("短内容", diyContentPreview("短内容"))
        assertEquals("一二三四五六七八九十一二三四", diyContentPreview("一二三四五六七八九十一二三四")) // 恰 14 字不截
        assertEquals("一二三四五六七八九十一二三四…", diyContentPreview("一二三四五六七八九十一二三四五")) // 15 字 → 截 14 + …
        assertEquals("有空白", diyContentPreview("  有空白  ")) // 先 trim
    }
}
