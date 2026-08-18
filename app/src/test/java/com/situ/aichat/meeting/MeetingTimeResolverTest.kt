package com.situ.aichat.meeting

import com.situ.aichat.data.model.MeetingTimeGranularity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 约定时间解析器纯函数单测。断言反推自 FUTURE_MEETUP_PORT_PLAN.md §6 规格（主路 ISO / 中文兜底 / vague 占位 /
 * AM-PM 不猜 / 补 19 点 / 365 天），非照搬实现。注入固定 now = 2026-06-24（**周三**）15:30 CST + Asia/Shanghai，确定性可重复。
 */
class MeetingTimeResolverTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    /** 2026-06-24 15:30:00 +08:00（周三）。 */
    private val now = Instant.parse("2026-06-24T07:30:00Z")

    private fun resolve(iso: String? = null, raw: String = "") =
        MeetingTimeResolver.resolve(isoDateTime = iso, rawWhen = raw, now = now, zone = zone)

    private fun MeetingTimeResolver.Resolution.local(): LocalDateTime =
        Instant.ofEpochMilli(scheduledAtMillis).atZone(zone).toLocalDateTime()

    // ── 主路：ISO ──

    @Test fun iso_withOffset_isExact() {
        val r = resolve(iso = "2026-06-27T15:00:00+08:00")
        assertEquals(LocalDateTime.of(2026, 6, 27, 15, 0), r.local())
        assertEquals(MeetingTimeGranularity.EXACT, r.granularity)
    }

    @Test fun iso_localDateTime_interpretedInZone_isExact() {
        val r = resolve(iso = "2026-06-27T09:00")
        assertEquals(LocalDateTime.of(2026, 6, 27, 9, 0), r.local())
        assertEquals(MeetingTimeGranularity.EXACT, r.granularity)
    }

    @Test fun iso_dateOnly_isDayOnly_at19() {
        val r = resolve(iso = "2026-06-30")
        assertEquals(LocalDateTime.of(2026, 6, 30, 19, 0), r.local())
        assertEquals(MeetingTimeGranularity.DAY_ONLY, r.granularity)
    }

    @Test fun iso_inPast_fallsThroughToVague() {
        // 过去时间校验失败、rawWhen 空 → vague 占位次日 19 点
        val r = resolve(iso = "2020-01-01T10:00:00+08:00")
        assertEquals(LocalDateTime.of(2026, 6, 25, 19, 0), r.local())
        assertEquals(MeetingTimeGranularity.VAGUE, r.granularity)
    }

    @Test fun iso_beyondHorizon_fallsThroughToVague() {
        val r = resolve(iso = "2030-01-01T10:00:00+08:00")
        assertEquals(MeetingTimeGranularity.VAGUE, r.granularity)
    }

    @Test fun isoTakesPrecedenceOverRaw() {
        // 主路 ISO 命中时不走中文兜底
        val r = resolve(iso = "2026-06-28T10:00:00+08:00", raw = "明天")
        assertEquals(LocalDateTime.of(2026, 6, 28, 10, 0), r.local())
    }

    // ── 兜底：中文相对短语·哪一天 ──

    @Test fun relative_tomorrow_dayOnly() {
        val r = resolve(raw = "明天")
        assertEquals(LocalDateTime.of(2026, 6, 25, 19, 0), r.local())
        assertEquals(MeetingTimeGranularity.DAY_ONLY, r.granularity)
    }

    @Test fun relative_dayAfter_and_bigDayAfter() {
        assertEquals(LocalDateTime.of(2026, 6, 26, 19, 0), resolve(raw = "后天").local())
        assertEquals(LocalDateTime.of(2026, 6, 27, 19, 0), resolve(raw = "大后天").local())
    }

    @Test fun relative_nDaysLater() {
        assertEquals(LocalDateTime.of(2026, 6, 27, 19, 0), resolve(raw = "3天后").local())
        assertEquals(LocalDateTime.of(2026, 6, 27, 19, 0), resolve(raw = "过3天").local())
    }

    @Test fun relative_comingSaturday_thisWeek() {
        // 今天周三 → 周六 = 本周六 6/27
        assertEquals(LocalDateTime.of(2026, 6, 27, 19, 0), resolve(raw = "周六").local())
    }

    @Test fun relative_comingWeekday_passedThisWeek_rollsToNext() {
        // 今天周三 → 周二（本周二已过）= 下周二 6/30
        assertEquals(LocalDateTime.of(2026, 6, 30, 19, 0), resolve(raw = "周二").local())
    }

    @Test fun relative_nextWeekday() {
        // 今天周三 → 下周三 = 7/1
        assertEquals(LocalDateTime.of(2026, 7, 1, 19, 0), resolve(raw = "下周三").local())
    }

    @Test fun relative_nextWeekday_simplifiedGe_resolvesToNextWeek() {
        // 回归守卫：含简体「个」的「下个星期X / 下个礼拜X」必须解析成**下周**，不是本周（旧实现漏「下个」→ 误判本周早 7 天）。
        // 今天周三 6/24 → 下个星期五 = 下周五 7/3（本周五是 6/26，差 7 天，证明没退化成 offsetToComing）。
        assertEquals(LocalDateTime.of(2026, 7, 3, 19, 0), resolve(raw = "下个星期五").local())
        // 下个礼拜三 = 下周三 7/1（与「下周三」同日，确认两种说法等价）。
        assertEquals(LocalDateTime.of(2026, 7, 1, 19, 0), resolve(raw = "下个礼拜三").local())
    }

    @Test fun relative_nextWeek_traditionalAndGeVariants() {
        // 回归守卫：繁体「個/週」与「下个星期末」也归到「下周」。今天周三 → 下周六 = 7/4。
        assertEquals(LocalDateTime.of(2026, 7, 4, 19, 0), resolve(raw = "下個周末").local())   // 繁体個 + 週末→周末
        assertEquals(LocalDateTime.of(2026, 7, 4, 19, 0), resolve(raw = "下个星期末").local()) // 简体个 + 星期末
        assertEquals(LocalDateTime.of(2026, 7, 4, 19, 0), resolve(raw = "下個星期六").local()) // 繁体個星期六
    }

    @Test fun relative_weekend_and_nextWeekend() {
        assertEquals(LocalDateTime.of(2026, 6, 27, 19, 0), resolve(raw = "周末").local()) // 本周六
        assertEquals(LocalDateTime.of(2026, 7, 4, 19, 0), resolve(raw = "下周末").local()) // 下周六
    }

    @Test fun relative_weekday_whenTodayIsSunday_mondayStartWeek() {
        // 回归守卫（复核对抗项）：周日 = 中式自然周（周一为首）的本周末尾，「下周X」指紧接着开始的下周（次日起）、
        // 非再下一周。确认卡兜底，此处锁中式语义：周日说「下周一」= 明天；「周一」与「下周一」同日（周日已是本周末尾）。
        val sunday = Instant.parse("2026-06-28T07:30:00Z") // 2026-06-28 15:30 CST = 周日
        fun onSunday(raw: String): LocalDateTime =
            MeetingTimeResolver.resolve(null, raw, sunday, zone).let {
                Instant.ofEpochMilli(it.scheduledAtMillis).atZone(zone).toLocalDateTime()
            }
        assertEquals(LocalDateTime.of(2026, 6, 29, 19, 0), onSunday("下周一")) // 周日的次日 = 下周一
        assertEquals(LocalDateTime.of(2026, 7, 1, 19, 0), onSunday("下周三"))
        assertEquals(LocalDateTime.of(2026, 6, 29, 19, 0), onSunday("周一"))   // 与「下周一」同日
    }

    // ── 时段 ──

    @Test fun relative_explicitPeriod_isExact() {
        val r = resolve(raw = "明天下午")
        assertEquals(LocalDateTime.of(2026, 6, 25, 15, 0), r.local())
        assertEquals(MeetingTimeGranularity.EXACT, r.granularity)
    }

    @Test fun relative_eveningClock_adds12() {
        assertEquals(LocalDateTime.of(2026, 6, 25, 19, 0), resolve(raw = "明天晚上7点").local())
    }

    @Test fun relative_bareClock_doesNotGuessPm() {
        // AM/PM 不猜：裸「7点」按字面取 7（上午）
        val r = resolve(raw = "明天7点")
        assertEquals(LocalDateTime.of(2026, 6, 25, 7, 0), r.local())
        assertEquals(MeetingTimeGranularity.EXACT, r.granularity)
    }

    @Test fun relative_midnight12_isZeroHourNotNoon() {
        // 「晚上/凌晨12点」= 午夜 0 点（旧实现误判成正午 12:00）；「中午12点」仍取正午。跨日界细节交确认卡。
        assertEquals(LocalDateTime.of(2026, 6, 27, 0, 0), resolve(raw = "周六晚上12点").local())
        assertEquals(LocalDateTime.of(2026, 6, 27, 0, 0), resolve(raw = "周六凌晨12点").local())
        assertEquals(LocalDateTime.of(2026, 6, 27, 12, 0), resolve(raw = "周六中午12点").local())
    }

    // ── vague ──

    @Test fun unrecognized_isVague_nextDay19() {
        val r = resolve(raw = "有空再说")
        assertEquals(LocalDateTime.of(2026, 6, 25, 19, 0), r.local())
        assertEquals(MeetingTimeGranularity.VAGUE, r.granularity)
    }

    @Test fun timeWithoutDay_isVague() {
        // 只有时段没有哪天 → 认不出 → vague
        assertEquals(MeetingTimeGranularity.VAGUE, resolve(raw = "下午3点").granularity)
    }
}
