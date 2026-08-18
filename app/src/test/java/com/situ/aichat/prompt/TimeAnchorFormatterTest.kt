package com.situ.aichat.prompt

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

/**
 * 时间锚（刀2 现在卡·间隔五档规格,2026-07-11 过审）测试。断言从过审表独立反推：
 * - 间隔行自带方向：「对方隔了约 X 才回你」；<10 分钟静默；≥1 年档句内用「一年多」；
 * - 五档：<2h 无附言 / 同日或跨日但 <6h=半日(几个小时) / 同日 ≥6h=半日(大半天) / 跨 1 日且 ≥6h=跨夜 /
 *   2–7 天=数日 / >7 天=久别；命中任一档追加「长期持续」保命附言；
 * - 深夜边界（23:00→01:30 跨日 2.5h）落半日档，不预设「睡过一觉」；
 * - 「（这段是给你看的…）」尾注已移至 currentMoment（本类输出不得再含）。
 * 时长分档表 formatDuration 规格未变。时区钉死 Asia/Shanghai 保证确定性。
 */
class TimeAnchorFormatterTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private lateinit var originalTz: TimeZone

    @Before
    fun pinTimeZone() {
        originalTz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(originalTz)
    }

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Instant =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant()

    private fun tier(now: Instant, last: Instant): String? =
        TimeAnchorFormatter.gapTierNote(now, last, Duration.between(last, now).seconds)

    // MARK: - formatDuration 分档边界（规格未变）

    @Test
    fun duration_buckets_fullTable() {
        assertEquals("约 25 分钟", TimeAnchorFormatter.formatDuration(23 * 60))
        assertEquals("约 1 小时", TimeAnchorFormatter.formatDuration(58 * 60)) // 凑到 60 分 → 进位 1 小时
        assertEquals("约 3 小时", TimeAnchorFormatter.formatDuration(3 * 3600))
        assertEquals("约 23 小时", TimeAnchorFormatter.formatDuration(23 * 3600))
        assertEquals("约 1 天", TimeAnchorFormatter.formatDuration(24 * 3600))
        assertEquals("约 1 周", TimeAnchorFormatter.formatDuration(7 * 86400))
        assertEquals("约 2 个月", TimeAnchorFormatter.formatDuration(70L * 86400))
        assertEquals("好久没联系了", TimeAnchorFormatter.formatDuration(400L * 86400))
    }

    // MARK: - 间隔行（方向化措辞）

    @Test
    fun sinceLast_underTenMinutes_silent() {
        val now = at(2026, 6, 13, 12, 9)
        assertNull(TimeAnchorFormatter.formatSinceLastAssistant(now, at(2026, 6, 13, 12, 0)))
    }

    @Test
    fun sinceLast_directionalWording_noCrossMarker() {
        val s = TimeAnchorFormatter.formatSinceLastAssistant(at(2026, 6, 13, 15, 0), at(2026, 6, 13, 12, 0))
        assertEquals("对方隔了约 3 小时才回你", s)
        // 旧「（跨夜）/（跨日）」后缀已废——跨夜语义由五档措辞承担。
        val overnight = TimeAnchorFormatter.formatSinceLastAssistant(at(2026, 6, 13, 8, 0), at(2026, 6, 12, 23, 0))
        assertEquals("对方隔了约 9 小时才回你", overnight)
    }

    @Test
    fun sinceLast_delayedGeneration_neutralWording() {
        // 延迟生成路(进程恢复):间隔是系统欠的 → 中性措辞,绝不说「对方隔了…才回你」(T5 复核🟡④)。
        val s = TimeAnchorFormatter.formatSinceLastAssistant(at(2026, 6, 13, 20, 0), at(2026, 6, 13, 12, 0), directional = false)
        assertEquals("距离你上条回复：约 8 小时", s)
        val anchor = TimeAnchorFormatter.buildTimeAnchor(at(2026, 6, 13, 20, 0), at(2026, 6, 13, 12, 0), directionalGapLine = false)
        assertEquals(false, anchor.contains("对方隔了"))
        assertTrue(anchor.contains("距离你上条回复"))
    }

    @Test
    fun sinceLast_overOneYear_sentenceCompatible() {
        val s = TimeAnchorFormatter.formatSinceLastAssistant(at(2027, 8, 1, 12, 0), at(2026, 6, 1, 12, 0))
        assertEquals("对方隔了一年多才回你", s)
    }

    // MARK: - 五档边界

    @Test
    fun tier_underTwoHours_noNote() {
        assertNull(tier(at(2026, 6, 13, 13, 59), at(2026, 6, 13, 12, 0)))
    }

    @Test
    fun tier_sameDayFewHours_halfDayFewHours() {
        val note = tier(at(2026, 6, 13, 15, 0), at(2026, 6, 13, 12, 0))!!
        assertTrue(note.startsWith(TimeAnchorFormatter.TIER_FEW_HOURS))
        assertTrue("命中档位必带保命附言", note.contains("长期、持续的事"))
    }

    @Test
    fun tier_sameDayOverSixHours_mostOfDay() {
        val note = tier(at(2026, 6, 13, 20, 0), at(2026, 6, 13, 7, 0))!!
        assertTrue(note.startsWith(TimeAnchorFormatter.TIER_MOST_OF_DAY))
    }

    @Test
    fun tier_overnightBigGap_overnightNote() {
        val note = tier(at(2026, 6, 13, 8, 0), at(2026, 6, 12, 23, 0))!!
        assertTrue(note.startsWith(TimeAnchorFormatter.TIER_OVERNIGHT))
    }

    @Test
    fun tier_lateNightShortCross_fallsToHalfDay_notOvernight() {
        // 23:00 → 次日 01:30（跨日但仅 2.5h）：熬夜快回,「睡过一觉」是错误预设 → 半日档。
        val note = tier(at(2026, 6, 13, 1, 30), at(2026, 6, 12, 23, 0))!!
        assertTrue(note.startsWith(TimeAnchorFormatter.TIER_FEW_HOURS))
    }

    @Test
    fun tier_doubleCalendarDayButOneActualDay_fallsToOvernight() {
        // T5 复核🟡修：前天 23:30 → 今天 00:30（跨 2 日历日但实际 25h）→ 跨夜档,
        // 与间隔行「约 1 天」一致,不再说「隔了好几天」。
        val note = tier(at(2026, 6, 13, 0, 30), at(2026, 6, 11, 23, 30))!!
        assertTrue(note.startsWith(TimeAnchorFormatter.TIER_OVERNIGHT))
    }

    @Test
    fun tier_fewDays_and_longGap() {
        val fewDays = tier(at(2026, 6, 15, 10, 0), at(2026, 6, 12, 10, 0))!!
        assertTrue(fewDays.startsWith(TimeAnchorFormatter.TIER_FEW_DAYS))
        val longGap = tier(at(2026, 6, 30, 10, 0), at(2026, 6, 12, 10, 0))!!
        assertTrue(longGap.startsWith(TimeAnchorFormatter.TIER_LONG_GAP))
    }

    // MARK: - 当前时刻与星期映射（未变）

    @Test
    fun currentMoment_knownMondayAndSunday() {
        assertEquals("现在：2024-01-01 周一 14:30（下午）", TimeAnchorFormatter.formatCurrentMoment(at(2024, 1, 1, 14, 30)))
        assertEquals("现在：2024-01-07 周日 08:05（清晨）", TimeAnchorFormatter.formatCurrentMoment(at(2024, 1, 7, 8, 5)))
    }

    // MARK: - buildTimeAnchor 整体结构

    @Test
    fun buildAnchor_firstConversation_marked() {
        val out = TimeAnchorFormatter.buildTimeAnchor(at(2026, 6, 13, 12, 0), null)
        assertTrue(out.contains("这是你们的第一次对话"))
        assertTrue(out.startsWith("<time_context>") && out.contains("</time_context>"))
        assertTrue("基础护栏始终在", out.contains("以上是此刻的真实时间"))
    }

    @Test
    fun buildAnchor_normalRhythm_onlyCurrentLine() {
        val out = TimeAnchorFormatter.buildTimeAnchor(at(2026, 6, 13, 12, 5), at(2026, 6, 13, 12, 0))
        assertTrue(out.contains("现在：2026-06-13"))
        assertEquals(false, out.contains("对方隔了"))
        assertEquals(false, out.contains("重新拿起手机"))
    }

    @Test
    fun buildAnchor_privateNoteMovedToCurrentMoment() {
        // 「（这段是给你看的…）」尾注已随现在卡合并移至 currentMoment 末尾——时间锚任何档位都不再输出。
        val out = TimeAnchorFormatter.buildTimeAnchor(at(2026, 6, 20, 12, 0), at(2026, 6, 12, 10, 0))
        assertEquals(false, out.contains("这段是给你看的"))
        assertTrue(out.contains(TimeAnchorFormatter.TIER_LONG_GAP))
    }

    @Test
    fun factsOnly_offlineVariant_onlyCurrentLineAndNote() {
        // 线下见面专版（前后置区审计 🟡-1b）：仅时刻事实 + 保真附言——无间隔行/五档/首次对话（短信框架措辞退场）。
        val out = TimeAnchorFormatter.buildTimeAnchorFactsOnly(at(2026, 7, 11, 13, 39))
        assertEquals(
            "<time_context>\n现在：2026-07-11 周六 13:39（中午）\n</time_context>\n↑ 以上是此刻的真实时间，以它为准。",
            out,
        )
    }
}
