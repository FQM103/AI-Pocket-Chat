package com.situ.aichat.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * [OfflineMeetingSessionExtractor.isCharacterInitiated] 纯决策单测（1:1 iOS isCharacterInitiated）：
 * 入场标记前、上一次见面之后是否有「已接受」邀约卡 → 角色发起；否则用户手动发起。
 * 覆盖：无卡 / 已接受 / 已拒绝 / 卡在 start 之后 / 卡在上一次见面之前（lowerBound 截断）/ 多卡取最近。
 */
class OfflineMeetingSessionExtractorTest {

    private fun decide(start: Long, markers: List<Long>, cards: List<Pair<Long, String?>>) =
        OfflineMeetingSessionExtractor.isCharacterInitiated(start, markers, cards)

    @Test fun noInviteCards_notCharacterInitiated() {
        // 无邀约卡 → 用户手动发起（isCharacterInitiated=false）。
        assertFalse(decide(100, listOf(100), emptyList()))
    }

    @Test fun acceptedInviteBeforeStart_characterInitiated() {
        // start=100，本会话仅此一次见面（lowerBound=MIN），50 有已接受邀约 → 角色发起。
        assertTrue(decide(100, listOf(100), listOf(50L to "accepted")))
    }

    @Test fun declinedInvite_notCharacterInitiated() {
        assertFalse(decide(100, listOf(100), listOf(50L to "declined")))
        assertFalse(decide(100, listOf(100), listOf(50L to null)))
    }

    @Test fun inviteAtOrAfterStart_skipped() {
        // 邀约卡时间 >= start → 跳过（不属于本次见面之前）。
        assertFalse(decide(100, listOf(100), listOf(100L to "accepted", 150L to "accepted")))
    }

    @Test fun acceptedInviteBeforePreviousSession_brokenByLowerBound() {
        // 两次见面：上一次 start=100，本次 start=200 → lowerBound=100。
        // 50 的已接受邀约属于「上一次见面之前」(<=lowerBound) → break，不算本次发起方。
        assertFalse(decide(200, listOf(100, 200), listOf(50L to "accepted")))
    }

    @Test fun acceptedInviteBetweenSessions_characterInitiated() {
        // 150 的已接受邀约落在 (lowerBound=100, start=200) 窗口内 → 角色发起。
        assertTrue(decide(200, listOf(100, 200), listOf(150L to "accepted")))
    }

    @Test fun multipleCards_latestInWindowDecides() {
        // 升序输入，倒序遍历：最近的 150 已接受 → true（不被更早的 declined 影响）。
        assertTrue(decide(200, listOf(200), listOf(120L to "declined", 150L to "accepted")))
        // 最近的 150 已拒绝 → false（即便更早 120 已接受，倒序先遇 150 不 accepted；120 仍会继续检查）。
        assertTrue(decide(200, listOf(200), listOf(120L to "accepted", 150L to "declined")))
    }

    // ===== 摘要正则 + 匹配 + 会话组装（10.2e-4，反推 iOS；Asia/Shanghai 固定时区）=====

    private val zone = ZoneId.of("Asia/Shanghai")
    private fun millis(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    @Test fun parseSummaries_sameLineBody() {
        val memory = "【见面 · 2026-04-18 15:30 · 公园】一次你主动约的见面,约1小时20分钟,共 42 轮对话,整体氛围温暖。"
        val entries = OfflineMeetingSessionExtractor.parseMeetingSummaries(memory)
        assertEquals(1, entries.size)
        assertEquals("2026-04-18 15:30", entries[0].dateString)
        assertEquals("公园", entries[0].location)
        assertEquals("一次你主动约的见面,约1小时20分钟,共 42 轮对话,整体氛围温暖。", entries[0].text)
    }

    @Test fun parseSummaries_multiParagraph_bodyUntilNextHeading() {
        val memory = "【见面 · 2026-04-08 15:30 · 公园】\n一起散步聊了很多。\n\n【见面 · 2026-04-10 20:00 · 咖啡馆】\n喝咖啡到很晚。"
        assertEquals(
            listOf(
                MeetingSummaryEntry("2026-04-08 15:30", "公园", "一起散步聊了很多。"),
                MeetingSummaryEntry("2026-04-10 20:00", "咖啡馆", "喝咖啡到很晚。"),
            ),
            OfflineMeetingSessionExtractor.parseMeetingSummaries(memory),
        )
    }

    @Test fun parseSummaries_skipsEmptyBodyAndEmptyInput() {
        assertTrue(OfflineMeetingSessionExtractor.parseMeetingSummaries("【见面 · 2026-04-08 15:30 · 公园】").isEmpty())
        assertTrue(OfflineMeetingSessionExtractor.parseMeetingSummaries("").isEmpty())
    }

    @Test fun findBestSummary_locationAndDatePreferredOverDateOnly() {
        val entries = listOf(
            MeetingSummaryEntry("2026-04-08 15:30", "公园", "公园的摘要"),
            MeetingSummaryEntry("2026-04-08 20:00", "咖啡馆", "咖啡馆的摘要"),
        )
        // 同一天两处见面 → 地点匹配优先。
        assertEquals("咖啡馆的摘要", OfflineMeetingSessionExtractor.findBestSummary(millis(2026, 4, 8, 20, 0), "咖啡馆", entries, zone))
    }

    @Test fun findBestSummary_dateOnlyFallbackWhenLocationDiffers() {
        val entries = listOf(MeetingSummaryEntry("2026-04-08 15:30", "公园", "公园的摘要"))
        // 地点被改过（"中央公园" != "公园"）→ 退到仅日期前缀匹配。
        assertEquals("公园的摘要", OfflineMeetingSessionExtractor.findBestSummary(millis(2026, 4, 8, 15, 30), "中央公园", entries, zone))
    }

    @Test fun findBestSummary_noDateMatch_null() {
        val entries = listOf(MeetingSummaryEntry("2026-04-08 15:30", "公园", "公园的摘要"))
        assertNull(OfflineMeetingSessionExtractor.findBestSummary(millis(2026, 4, 9, 15, 30), "公园", entries, zone))
    }

    @Test fun assembleSessions_firstStartWins_lastEndWins_sortDesc_flags() {
        val t1 = millis(2026, 4, 8, 15, 30)
        val t1dup = millis(2026, 4, 8, 16, 0)   // s1 的更晚一条入场标记 → first-wins 应丢弃
        val t2 = millis(2026, 4, 10, 20, 0)
        val starts = listOf(
            OfflineStartMarkerRow("s1", "conv1", t1, "公园", "散步"),
            OfflineStartMarkerRow("s1", "conv1", t1dup, "公园X", "散步X"),
            OfflineStartMarkerRow("s2", "conv1", t2, "咖啡馆", "喝咖啡"),
        )
        val ends = listOf(
            OfflineEndMarkerRow("s1", "约30分钟", t1 + 1000),
            OfflineEndMarkerRow("s1", "约1小时", t1 + 2000),   // last-wins
            OfflineEndMarkerRow("s2", "约20分钟", t2 + 1000),
        )
        val cards = listOf(
            OfflineEndCardRow("s1", "neutral", t1 + 500),
            OfflineEndCardRow("s1", "warm", t1 + 1500),        // last-wins
            OfflineEndCardRow("s2", "sweet", t2 + 500),
        )
        // (s1.start, s2.start) 间一张已接受邀约 → s2 由角色发起；s1 之前无邀约 → 用户发起。
        val invites = mapOf("conv1" to listOf((t1 + 3000) to "accepted"))
        val summaries = listOf(MeetingSummaryEntry("2026-04-08 15:30", "公园", "散步摘要"))
        val fallback = setOf("s1")

        val sessions = OfflineMeetingSessionExtractor.assembleSessions(starts, ends, cards, invites, summaries, fallback, zone)

        assertEquals(2, sessions.size)
        assertEquals("s2", sessions[0].id)   // 倒序：晚的在前
        assertEquals("s1", sessions[1].id)

        val s1 = sessions[1]
        assertEquals(t1, s1.startMillis)         // first-wins（最早）
        assertEquals("公园", s1.location)         // first-wins payload
        assertEquals("约1小时", s1.durationText)   // last-wins
        assertEquals("warm", s1.finalMood)        // last-wins
        assertEquals("散步摘要", s1.summaryText)   // 地点 + 日期匹配
        assertTrue(s1.initiatedByUser)
        assertTrue(s1.usedFallbackSummary)

        val s2 = sessions[0]
        assertFalse(s2.initiatedByUser)           // 角色发起
        assertFalse(s2.usedFallbackSummary)
        assertNull(s2.summaryText)                // 无 s2 摘要
    }
}
