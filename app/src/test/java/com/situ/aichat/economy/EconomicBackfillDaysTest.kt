package com.situ.aichat.economy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * R1 经济补扫缺日纯函数单测（[missedEconomicScanDays]）：断言「(lastScan 次日 … 昨天]，封顶最近 7 天」边界，
 * 与日程 backfillDateMillis 同语义。用固定无 DST 时区（Asia/Shanghai）+ 固定锚日构造 0 点毫秒。
 */
class EconomicBackfillDaysTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private fun dayStart(d: LocalDate): Long = d.atStartOfDay(zone).toInstant().toEpochMilli()

    // 锚：今天 = 2026-06-12，昨天 = 06-11
    private val today = LocalDate.of(2026, 6, 12)
    private val yesterday = today.minusDays(1)
    private val yStart = dayStart(yesterday)

    @Test fun lastScan_dayBeforeYesterday_returnsOnlyYesterday() {
        val days = missedEconomicScanDays(dayStart(today.minusDays(2)), yStart, zone)
        assertEquals(listOf(yStart), days)
    }

    @Test fun lastScan_equalsYesterday_returnsEmpty() {
        // 已扫到昨天 → 无缺日
        assertTrue(missedEconomicScanDays(yStart, yStart, zone).isEmpty())
    }

    @Test fun lastScan_today_returnsEmpty() {
        // 已扫到今天（理论游标越过昨天）→ 无缺日，不倒扫
        assertTrue(missedEconomicScanDays(dayStart(today), yStart, zone).isEmpty())
    }

    @Test fun lastScan_future_returnsEmpty() {
        assertTrue(missedEconomicScanDays(dayStart(today.plusDays(3)), yStart, zone).isEmpty())
    }

    @Test fun lastScan_fiveDaysAgo_returnsFiveAscendingDays() {
        // lastScan=06-06 → 补 06-07,08,09,10,11（昨天），升序
        val days = missedEconomicScanDays(dayStart(today.minusDays(6)), yStart, zone)
        val expected = (5 downTo 1).map { dayStart(today.minusDays(it.toLong())) } // 06-07..06-11
        assertEquals(expected, days)
        // 升序校验
        assertEquals(days.sorted(), days)
    }

    @Test fun lastScan_tenDaysAgo_cappedToLastSeven() {
        // gap 远超 7 天 → 只留最近 7 天（丢最旧），末位=昨天
        val days = missedEconomicScanDays(dayStart(today.minusDays(11)), yStart, zone)
        assertEquals(SCAN_BACKFILL_CAP_DAYS, days.size)
        assertEquals(yStart, days.last())
        // 最早一天 = 昨天前 6 天（共 7 天）
        assertEquals(dayStart(yesterday.minusDays(6)), days.first())
    }
}
