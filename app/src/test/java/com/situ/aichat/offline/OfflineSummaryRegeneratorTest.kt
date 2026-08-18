package com.situ.aichat.offline

import com.situ.aichat.offline.OfflineSummaryRegenerator.MergeEntry
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * [OfflineSummaryRegenerator] 单测——byte-compare 反推 iOS `OfflineSummaryRegeneratorTests` /
 * `OfflineSummaryCompactionTests` 真实串：兜底段落（发起方/时长/活动/轮数/情绪分隔符 = **半角逗号**、
 * 句末 = **全角句号**、「共 N 轮对话」数字两侧空格）、finalMood 映射、合并行格式、软上限合并/dedup/排序。
 *
 * 固定时区 Asia/Shanghai（国行无夏令时）+ 固定时刻保证 yyyy-MM-dd HH:mm / M/d 确定性。
 */
class OfflineSummaryRegeneratorTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    // ---- buildFallbackParagraph ----

    @Test fun fallbackParagraph_full() {
        val s = OfflineSummaryRegenerator.buildFallbackParagraph(
            startMillis = at(2026, 4, 18, 15, 30),
            location = "公园",
            activity = "喝咖啡",
            durationText = "约1小时20分钟",
            messageCount = 42,
            finalMood = "warm",
            initiatedByUser = true,
            zone = zone,
        )
        assertEquals(
            "【见面 · 2026-04-18 15:30 · 公园】\n一次你主动约的见面,约1小时20分钟,主要是喝咖啡,共 42 轮对话,整体氛围温暖。",
            s,
        )
    }

    @Test fun fallbackParagraph_characterInitiated() {
        val s = OfflineSummaryRegenerator.buildFallbackParagraph(
            startMillis = at(2026, 4, 18, 15, 30), location = "咖啡馆", activity = "聊天",
            durationText = "约30分钟", messageCount = 10, finalMood = "sweet", initiatedByUser = false, zone = zone,
        )
        assertEquals(
            "【见面 · 2026-04-18 15:30 · 咖啡馆】\n一次 ta 主动约的见面,约30分钟,主要是聊天,共 10 轮对话,整体氛围甜蜜。",
            s,
        )
    }

    @Test fun fallbackParagraph_minimal_nullInitiator_emptyEverything() {
        // location 空→「某个地方」；activity 空跳过；messageCount 0 跳过；durationText 空跳过；finalMood null 跳过；
        // initiatedByUser null→「一次见面」。仅剩 opening。
        val s = OfflineSummaryRegenerator.buildFallbackParagraph(
            startMillis = at(2026, 1, 2, 9, 5), location = "", activity = "",
            durationText = "", messageCount = 0, finalMood = null, initiatedByUser = null, zone = zone,
        )
        assertEquals("【见面 · 2026-01-02 09:05 · 某个地方】\n一次见面。", s)
    }

    @Test fun fallbackParagraph_moodMappings() {
        fun mood(raw: String?): String = OfflineSummaryRegenerator.buildFallbackParagraph(
            startMillis = at(2026, 4, 18, 15, 30), location = "x", activity = "",
            durationText = "", messageCount = 0, finalMood = raw, initiatedByUser = null, zone = zone,
        ).substringAfter("\n")
        assertEquals("一次见面,整体氛围温暖。", mood("warm"))
        assertEquals("一次见面,整体氛围甜蜜。", mood("sweet"))
        assertEquals("一次见面,整体氛围微涩。", mood("melancholic"))
        assertEquals("一次见面,整体氛围微妙。", mood("awkward"))
        assertEquals("一次见面,整体氛围平淡。", mood("neutral"))
        // 大小写不敏感（iOS lowercased）。
        assertEquals("一次见面,整体氛围温暖。", mood("WARM"))
        // 未知/空 → 不输出情绪。
        assertEquals("一次见面。", mood("excited"))
        assertEquals("一次见面。", mood(""))
        assertEquals("一次见面。", mood(null))
    }

    // ---- buildFallbackBody（v2 行存兜底正文·= paragraph 去标题行） ----

    @Test fun fallbackBody_equalsParagraphBodyPart() {
        val body = OfflineSummaryRegenerator.buildFallbackBody(
            durationText = "约1小时20分钟", activity = "喝咖啡", messageCount = 42,
            finalMood = "warm", initiatedByUser = true,
        )
        assertEquals("一次你主动约的见面,约1小时20分钟,主要是喝咖啡,共 42 轮对话,整体氛围温暖。", body)
        // 与 buildFallbackParagraph 的正文部分逐字节一致（去标题行）。
        val paragraph = OfflineSummaryRegenerator.buildFallbackParagraph(
            startMillis = at(2026, 4, 18, 15, 30), location = "公园", activity = "喝咖啡",
            durationText = "约1小时20分钟", messageCount = 42, finalMood = "warm", initiatedByUser = true, zone = zone,
        )
        assertEquals(paragraph.substringAfter("\n"), body)
    }

    @Test fun fallbackBody_minimal_nullEverything() {
        assertEquals("一次见面。", OfflineSummaryRegenerator.buildFallbackBody("", "", 0, null, null))
    }

    // ---- buildMergedEarlyMeetingsLine ----

    @Test fun mergedLine_empty_returnsEmpty() {
        assertEquals("", OfflineSummaryRegenerator.buildMergedEarlyMeetingsLine(emptyList(), zone))
    }

    @Test fun mergedLine_format() {
        val entries = listOf(
            MergeEntry(at(2026, 4, 1, 10, 0), "公园", "散步"),
            MergeEntry(at(2026, 4, 5, 14, 0), "咖啡馆", "喝咖啡"),
            MergeEntry(at(2026, 4, 10, 16, 0), "商场", "购物"),
        )
        assertEquals(
            "【早期见面合并】共 3 次: 4/1 公园 · 散步; 4/5 咖啡馆 · 喝咖啡; 4/10 商场 · 购物",
            OfflineSummaryRegenerator.buildMergedEarlyMeetingsLine(entries, zone),
        )
    }

    @Test fun mergedLine_emptyLocationAndActivity() {
        val entries = listOf(
            MergeEntry(at(2026, 4, 1, 10, 0), "", ""),         // 地点空→某地；活动空→省略
            MergeEntry(at(2026, 4, 5, 14, 0), "家", ""),        // 活动空→省略
        )
        assertEquals(
            "【早期见面合并】共 2 次: 4/1 某地; 4/5 家",
            OfflineSummaryRegenerator.buildMergedEarlyMeetingsLine(entries, zone),
        )
    }

    // ---- paragraphTitle ----

    @Test fun paragraphTitle_format() {
        assertEquals(
            "【见面 · 2026-04-18 15:30 · 公园】",
            OfflineSummaryRegenerator.paragraphTitle(at(2026, 4, 18, 15, 30), "公园", zone),
        )
        assertEquals(
            "【见面 · 2026-04-18 15:30 · 某个地方】",
            OfflineSummaryRegenerator.paragraphTitle(at(2026, 4, 18, 15, 30), "", zone),
        )
    }

    // ---- removeParagraphs ----

    @Test fun removeParagraphs_emptyTitles_noop() {
        assertEquals("a\n\nb", OfflineSummaryRegenerator.removeParagraphs("a\n\nb", emptySet()))
    }

    @Test fun removeParagraphs_removesMatchingFirstLineWholeParagraph() {
        val p1 = "【见面 · 2026-04-01 10:00 · 公园】\n散步聊天。"
        val p2 = "【见面 · 2026-04-05 14:00 · 咖啡馆】\n喝咖啡。"
        val title1 = "【见面 · 2026-04-01 10:00 · 公园】"
        assertEquals(p2, OfflineSummaryRegenerator.removeParagraphs("$p1\n\n$p2", setOf(title1)))
    }
}
