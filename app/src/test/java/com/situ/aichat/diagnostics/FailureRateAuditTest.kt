package com.situ.aichat.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 失败率审计纯函数单测（P15·P0-7）。**断言从 iOS `LogServiceFailureRateTests` 反推**
 * （阈值 0.5 / minFailures 3 / minTotal 3、24h 窗口含边界、来源升序、percent 截断），抓移植偏差。
 */
class FailureRateAuditTest {

    private val now = 1_700_000_000_000L
    private fun hoursAgo(h: Long) = now - h * 3_600_000L

    private fun rec(source: String, success: Boolean, t: Long) = CallLogRecord(source, success, t)

    @Test
    fun threeFailTwoSuccess_alertsAt60Percent() {
        val e = listOf(
            rec("对话", false, hoursAgo(1)), rec("对话", false, hoursAgo(2)), rec("对话", false, hoursAgo(3)),
            rec("对话", true, hoursAgo(4)), rec("对话", true, hoursAgo(5)),
        )
        val a = FailureRateAudit.computeFailureRateAlerts(e, now)
        assertEquals(1, a.size)
        assertEquals("对话", a[0].source)
        assertEquals(3, a[0].failures)
        assertEquals(5, a[0].total)
        assertEquals(60, a[0].percent)
    }

    @Test
    fun allFail_alertsAt100Percent() {
        val e = (1..3).map { rec("A", false, hoursAgo(it.toLong())) }
        val a = FailureRateAudit.computeFailureRateAlerts(e, now)
        assertEquals(1, a.size)
        assertEquals(100, a[0].percent)
    }

    @Test
    fun belowMinTotal_empty() {
        val e = listOf(rec("A", false, hoursAgo(1)), rec("A", false, hoursAgo(2)))
        assertTrue(FailureRateAudit.computeFailureRateAlerts(e, now).isEmpty())
    }

    @Test
    fun belowMinFailures_empty() {
        // 5 total, 2 fail
        val e = listOf(
            rec("A", false, hoursAgo(1)), rec("A", false, hoursAgo(2)),
            rec("A", true, hoursAgo(3)), rec("A", true, hoursAgo(4)), rec("A", true, hoursAgo(5)),
        )
        assertTrue(FailureRateAudit.computeFailureRateAlerts(e, now).isEmpty())
    }

    @Test
    fun belowFailureRate_empty() {
        // 10 total, 4 fail = 40% < 50%
        val e = (1..4).map { rec("A", false, hoursAgo(it.toLong())) } +
            (5..10).map { rec("A", true, hoursAgo(it.toLong())) }
        assertTrue(FailureRateAudit.computeFailureRateAlerts(e, now).isEmpty())
    }

    @Test
    fun outsideWindowExcluded() {
        // inside: 2 fail + 5 success；outside(>24h前): 10 fail → 应排除 → 只剩 2 fail < 3 → 空
        val inside = (1..2).map { rec("A", false, hoursAgo(it.toLong())) } +
            (3..7).map { rec("A", true, hoursAgo(it.toLong())) }
        val outside = (0..9).map { rec("A", false, now - (FailureRateAudit.WINDOW_MILLIS + it * 3_600_000L)) }
        assertTrue(FailureRateAudit.computeFailureRateAlerts(inside + outside, now).isEmpty())
    }

    @Test
    fun multiSource_onlyOverThresholdAlerts() {
        val e = (1..3).map { rec("A", false, hoursAgo(it.toLong())) } +
            (4..5).map { rec("A", true, hoursAgo(it.toLong())) } + // A=3/5=60%
            listOf(rec("B", false, hoursAgo(1))) +
            (2..5).map { rec("B", true, hoursAgo(it.toLong())) }   // B=1/5=20%
        val a = FailureRateAudit.computeFailureRateAlerts(e, now)
        assertEquals(1, a.size)
        assertEquals("A", a[0].source)
    }

    @Test
    fun alertsSortedBySourceAscending() {
        val e = listOf("B源", "A源", "C源").flatMap { s ->
            (1..3).map { rec(s, false, hoursAgo(it.toLong())) }
        }
        val a = FailureRateAudit.computeFailureRateAlerts(e, now)
        assertEquals(listOf("A源", "B源", "C源"), a.map { it.source })
    }

    @Test
    fun emptyEntries_empty() {
        assertTrue(FailureRateAudit.computeFailureRateAlerts(emptyList(), now).isEmpty())
    }

    @Test
    fun boundary_exactlyHalfAndExactlyMinFailures_alertsAt50() {
        // 3 fail / 6 total = 恰 0.5 且恰 minFailures → 告警，percent=50（证明 >= 含边界、截断）
        val e = (1..3).map { rec("A", false, hoursAgo(it.toLong())) } +
            (4..6).map { rec("A", true, hoursAgo(it.toLong())) }
        val a = FailureRateAudit.computeFailureRateAlerts(e, now)
        assertEquals(1, a.size)
        assertEquals(50, a[0].percent)
    }
}
