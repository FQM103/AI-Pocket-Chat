package com.situ.aichat.economy

import com.situ.aichat.data.local.entity.CurrencyTransactionEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * [WalletLedger] 单测——断言反推 iOS `WalletView`：本月收/支小计（按 kind·月界）、筛选保序、日期标签四档。
 */
class WalletLedgerTest {

    private val utc = ZoneOffset.UTC
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).toInstant(utc).toEpochMilli()

    private fun tx(kind: String, amount: Int, ts: Long, category: String = "other") =
        CurrencyTransactionEntity(timestamp = ts, ownerTypeRaw = "user", kindRaw = kind, categoryRaw = category, amount = amount, balanceAfter = 0)

    @Test fun monthlyStats_sumsOnlyThisMonthByKind() {
        val monthStart = WalletLedger.monthStartMillis(at(2026, 6, 15, 12, 0), utc)
        val txns = listOf(
            tx("earn", 50, at(2026, 6, 2, 9, 0)),   // 本月收入
            tx("spend", 20, at(2026, 6, 10, 9, 0)), // 本月支出
            tx("earn", 30, at(2026, 6, 14, 9, 0)),  // 本月收入
            tx("earn", 999, at(2026, 5, 31, 23, 0)), // 上月，不计
        )
        val s = WalletLedger.monthlyStats(txns, monthStart)
        assertEquals(80, s.earn)
        assertEquals(20, s.spend)
    }

    @Test fun monthlyStats_empty() {
        val monthStart = WalletLedger.monthStartMillis(at(2026, 6, 15, 12, 0), utc)
        assertEquals(WalletLedger.MonthlyStats.ZERO, WalletLedger.monthlyStats(emptyList(), monthStart))
    }

    @Test fun applyFilter_preservesOrderAndKind() {
        val a = tx("earn", 1, at(2026, 6, 3, 9, 0))
        val b = tx("spend", 2, at(2026, 6, 2, 9, 0))
        val c = tx("earn", 3, at(2026, 6, 1, 9, 0))
        val all = listOf(a, b, c)
        assertEquals(all, WalletLedger.applyFilter(all, WalletLedger.Filter.ALL))
        assertEquals(listOf(a, c), WalletLedger.applyFilter(all, WalletLedger.Filter.EARN))
        assertEquals(listOf(b), WalletLedger.applyFilter(all, WalletLedger.Filter.SPEND))
    }

    @Test fun monthStart_isFirstOfMonthMidnight() {
        val ms = WalletLedger.monthStartMillis(at(2026, 6, 15, 23, 30), utc)
        assertEquals(at(2026, 6, 1, 0, 0), ms)
    }

    @Test fun dateLabel_today_yesterday_thisYear_crossYear() {
        val now = at(2026, 6, 15, 14, 0)
        assertEquals("今天 09:05", WalletLedger.dateLabel(at(2026, 6, 15, 9, 5), now, utc))
        assertEquals("昨天 23:59", WalletLedger.dateLabel(at(2026, 6, 14, 23, 59), now, utc))
        assertEquals("3月8日 08:00", WalletLedger.dateLabel(at(2026, 3, 8, 8, 0), now, utc))
        // 跨年：无时分
        assertEquals("2025年12月31日", WalletLedger.dateLabel(at(2025, 12, 31, 20, 0), now, utc))
    }

    @Test fun dateLabel_yesterdayAcrossMonthBoundary() {
        val now = at(2026, 6, 1, 10, 0)
        assertEquals("昨天 22:00", WalletLedger.dateLabel(at(2026, 5, 31, 22, 0), now, utc))
        // 同年同昨日判定不依赖月内
        assertEquals(LocalDate.of(2026, 5, 31), LocalDate.of(2026, 6, 1).minusDays(1))
    }
}
