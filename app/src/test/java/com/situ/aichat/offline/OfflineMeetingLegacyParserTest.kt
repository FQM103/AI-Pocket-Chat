package com.situ.aichat.offline

import com.situ.aichat.util.DateFormatters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/**
 * 旧 blob → 结构化行解析看门（图纸 §3.3 / T1-2·E1）：规范段→meeting 行（时间/地点/正文正确）+ 散句/合并行→legacy 行
 * （原文逐字）+ 畸形标题→legacy + 空 blob→空表 + 确定性 uuid（重解析幂等）。
 */
class OfflineMeetingLegacyParserTest {

    private val zone = ZoneOffset.UTC
    private val cid = "char-1"

    private val blob = buildString {
        append("【见面 · 2026-04-18 15:30 · 公园】\n")
        append("一次你主动约的见面,约1小时20分钟,主要是喝咖啡,共 42 轮对话,整体氛围温暖。\n\n")
        append("【见面 · 2026-04-20 10:00 · 咖啡馆】\n")
        append("第二次见面的摘要正文。\n\n")
        append("【见面 · 2026-04-25 14:00 · 商场】\n")
        append("第三次见面的摘要正文。\n\n")
        append("【早期见面合并】共 2 次: 4/1 公园 · 散步; 4/5 咖啡馆 · 喝咖啡\n\n")
        append("散落的一句话。")
    }

    @Test
    fun parse_regularSegments_yieldMeetingRows() {
        val rows = OfflineMeetingLegacyParser.parse(cid, blob, zone)
        val meetings = rows.filter { it.kindRaw == "meeting" }
        assertEquals(3, meetings.size)
        // 时间/地点/正文正确（时间经渲染同款 formatter 反格式回原串验证）。
        val first = meetings.first { it.location == "公园" }
        assertEquals("2026-04-18 15:30", DateFormatters.yearMonthDayHourMinute(first.startedAtMillis, zone))
        assertTrue(first.summary.startsWith("一次你主动约的见面"))
        assertEquals("legacy", first.sourceRaw)
        assertEquals(listOf("公园", "咖啡馆", "商场"), meetings.sortedBy { it.startedAtMillis }.map { it.location })
    }

    @Test
    fun parse_mergedLineAndStrayText_yieldLegacyRows() {
        val rows = OfflineMeetingLegacyParser.parse(cid, blob, zone)
        val legacy = rows.filter { it.kindRaw == "legacy" }
        assertTrue("至少 1 行 legacy", legacy.isNotEmpty())
        // 原文逐字：合并行与散句都在，startedAt=0。
        assertTrue(legacy.any { it.summary.startsWith("【早期见面合并】共 2 次") })
        assertTrue(legacy.any { it.summary == "散落的一句话。" })
        assertTrue(legacy.all { it.startedAtMillis == 0L })
    }

    @Test
    fun parse_isDeterministic_sameUuidsOnReparse() {
        val a = OfflineMeetingLegacyParser.parse(cid, blob, zone).map { it.uuid }
        val b = OfflineMeetingLegacyParser.parse(cid, blob, zone).map { it.uuid }
        assertEquals(a, b)
        assertEquals(a.size, a.toSet().size) // 无重复 uuid
    }

    @Test
    fun parse_malformedTitle_unparseableDate_goesLegacy() {
        // 日期不可解析（"某天"）→ 整段归 legacy 逐字。
        val bad = "【见面 · 某天 · 公园】\n无法解析日期的一段。"
        val rows = OfflineMeetingLegacyParser.parse(cid, bad, zone)
        assertEquals(1, rows.size)
        assertEquals("legacy", rows.first().kindRaw)
        assertTrue(rows.first().summary.startsWith("【见面 · 某天 · 公园】"))
    }

    @Test
    fun parse_noMeetingTitle_wholeBlobLegacyByParagraph() {
        val plain = "第一段散文。\n\n第二段散文。"
        val rows = OfflineMeetingLegacyParser.parse(cid, plain, zone)
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.kindRaw == "legacy" })
        assertEquals(listOf("第一段散文。", "第二段散文。"), rows.map { it.summary })
    }

    @Test
    fun parse_emptyBlob_yieldsEmpty() {
        assertTrue(OfflineMeetingLegacyParser.parse(cid, "", zone).isEmpty())
        assertTrue(OfflineMeetingLegacyParser.parse(cid, "   \n\n  ", zone).isEmpty())
    }
}
