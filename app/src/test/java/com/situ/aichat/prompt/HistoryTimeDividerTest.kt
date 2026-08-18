package com.situ.aichat.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * HistoryTimeDivider 纯函数单测（Fable-5 时间感知优化 · chunk1）。
 * 规格独立反推：变化才标（间隔 ≥30 分钟 或 跨自然日 才插）、历史第一条总给起始锚、
 * 相对 now 的今天/昨天/更早措辞、横线包裹格式、手写星期映射。zone 注入保证断言确定性。
 */
class HistoryTimeDividerTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private fun inst(y: Int, mo: Int, d: Int, h: Int, mi: Int): Instant =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant()

    // MARK: - 历史第一条起始锚

    @Test
    fun firstMessage_olderThanToday_returnsAnchor() {
        // prev=null 且首条在往日 → 给起始锚（消除跨日歧义）；措辞相对 now（昨天下午）。
        val now = inst(2026, 6, 26, 0, 17)
        assertEquals(
            "【时间 · 昨天 14:50】",
            HistoryTimeDivider.lineFor(ms(2026, 6, 25, 14, 50), null, now, zone),
        )
    }

    @Test
    fun firstMessage_today_returnsNull() {
        // prev=null 但首条就在今天 → 省略起始锚（与 <time_context> 当前时间重复）。
        val now = inst(2026, 6, 26, 14, 0)
        assertNull(HistoryTimeDivider.lineFor(ms(2026, 6, 26, 9, 30), null, now, zone))
    }

    // MARK: - 间隔阈值（变化才标）

    @Test
    fun closeInterval_sameDay_returnsNull() {
        // 间隔 5 分钟、同日 → 不插。
        val now = inst(2026, 6, 25, 15, 0)
        assertNull(HistoryTimeDivider.lineFor(ms(2026, 6, 25, 14, 55), ms(2026, 6, 25, 14, 50), now, zone))
    }

    @Test
    fun justUnderThreshold_returnsNull() {
        // 29 分钟 < 30 → 不插。
        val now = inst(2026, 6, 25, 15, 0)
        assertNull(HistoryTimeDivider.lineFor(ms(2026, 6, 25, 14, 59), ms(2026, 6, 25, 14, 30), now, zone))
    }

    @Test
    fun exactlyThreshold_inserts() {
        // 恰 30 分钟 → 插。
        val now = inst(2026, 6, 25, 15, 0)
        assertEquals(
            "【时间 · 今天 15:00】",
            HistoryTimeDivider.lineFor(ms(2026, 6, 25, 15, 0), ms(2026, 6, 25, 14, 30), now, zone),
        )
    }

    @Test
    fun crossDay_shortGap_inserts() {
        // 间隔仅 10 分钟但跨自然日（23:55 → 次日 00:05）→ 插。
        val now = inst(2026, 6, 26, 1, 0)
        assertEquals(
            "【时间 · 今天 00:05】",
            HistoryTimeDivider.lineFor(ms(2026, 6, 26, 0, 5), ms(2026, 6, 25, 23, 55), now, zone),
        )
    }

    // MARK: - 相对 now 措辞

    @Test
    fun label_today_yesterday_older() {
        val now = inst(2026, 6, 26, 0, 17)
        assertEquals("今天 00:15", HistoryTimeDivider.formatLabel(ms(2026, 6, 26, 0, 15), now, zone))
        assertEquals("昨天 14:56", HistoryTimeDivider.formatLabel(ms(2026, 6, 25, 14, 56), now, zone))
        // 更早：6/23 = 周二（手写映射，不随 locale）。
        assertEquals("6月23日 周二 09:30", HistoryTimeDivider.formatLabel(ms(2026, 6, 23, 9, 30), now, zone))
    }

    // MARK: - 复刻 dump 真实穿帮场景

    @Test
    fun dumpCase_afternoonToMidnight_insertsDivider() {
        // 「快下午三点了」(6/25 14:56) → 「你看看几点了」(6/26 00:15)，now=6/26 00:17（深夜）。
        // 跨夜 → 插分割线，让 LLM 看到时间已跳到今天凌晨，不再把昨天下午当此刻。
        val now = inst(2026, 6, 26, 0, 17)
        assertEquals(
            "【时间 · 今天 00:15】",
            HistoryTimeDivider.lineFor(ms(2026, 6, 26, 0, 15), ms(2026, 6, 25, 14, 56), now, zone),
        )
    }
}
