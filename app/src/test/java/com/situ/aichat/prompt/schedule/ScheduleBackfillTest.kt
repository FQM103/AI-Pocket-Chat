package com.situ.aichat.prompt.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 14.7a 历史日程补算的纯函数单测。**断言从 iOS `BackgroundTaskRunner.backfillDates(after:through:)` 反推**：
 * - latest >= yesterday（含 latest 在未来）→ 空
 * - 否则 latest+1..yesterday 闭区间
 * - count > 7 → `suffix(7)`（丢最旧、留最近 7 天）
 * 不依赖真机 / API key（`./gradlew :app:testDebugUnitTest`）。
 */
class ScheduleBackfillTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 某 LocalDate 在 [zone] 下的「当天 0 点」毫秒。 */
    private fun dayMillis(date: LocalDate): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun dates(latest: LocalDate, yesterday: LocalDate): List<LocalDate> =
        backfillDateMillis(dayMillis(latest), dayMillis(yesterday), zone)
            .map { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }

    private val today = LocalDate.of(2026, 6, 9)
    private val yesterday = today.minusDays(1) // 2026-06-08

    // MARK: - 无缺口

    @Test fun noGap_latestEqualsYesterday_empty() {
        // 昨天就是最新日程日 → 没有缺失日（iOS: normalizedLatest < normalizedYesterday 为 false → []）
        assertEquals(emptyList<LocalDate>(), dates(latest = yesterday, yesterday = yesterday))
    }

    @Test fun noGap_latestIsToday_empty() {
        // 最新日程是今天（如今天已生成）→ latest(今天) > yesterday → []，不会误补
        assertEquals(emptyList<LocalDate>(), dates(latest = today, yesterday = yesterday))
    }

    @Test fun futureShell_latestAfterYesterday_empty() {
        // 最新日程是未来空壳（如明天的线下见面）→ latest > yesterday → []（与 iOS 同：未来锚点抑制补算）
        assertEquals(emptyList<LocalDate>(), dates(latest = today.plusDays(3), yesterday = yesterday))
    }

    // MARK: - 有缺口

    @Test fun oneDayGap() {
        // 最新 = 前天，昨天缺 → 只补昨天一天
        val result = dates(latest = yesterday.minusDays(1), yesterday = yesterday)
        assertEquals(listOf(yesterday), result)
    }

    @Test fun fourDayGap_ascending() {
        // 最新 = 5 天前；缺 D-4..D-1（4 天），升序
        val latest = yesterday.minusDays(4) // 2026-06-04
        val result = dates(latest = latest, yesterday = yesterday)
        assertEquals(
            listOf(
                LocalDate.of(2026, 6, 5),
                LocalDate.of(2026, 6, 6),
                LocalDate.of(2026, 6, 7),
                LocalDate.of(2026, 6, 8),
            ),
            result,
        )
    }

    @Test fun exactlySevenDayGap_keepsAll() {
        // 缺正好 7 天 → 全保留（不触发截断）
        val latest = yesterday.minusDays(7) // 锚点；缺 latest+1 .. yesterday = 7 天
        val result = dates(latest = latest, yesterday = yesterday)
        assertEquals(7, result.size)
        assertEquals(latest.plusDays(1), result.first())
        assertEquals(yesterday, result.last())
    }

    @Test fun eightDayGap_capsToMostRecentSeven() {
        // 缺 8 天 → suffix(7)：丢最旧的一天，保留最近 7 天（含昨天）
        val latest = yesterday.minusDays(8)
        val result = dates(latest = latest, yesterday = yesterday)
        assertEquals(7, result.size)
        // 最旧的 latest+1 被丢，首项变成 latest+2
        assertEquals(latest.plusDays(2), result.first())
        assertEquals(yesterday, result.last())
        assertTrue("不应包含被截断的最旧一天", !result.contains(latest.plusDays(1)))
    }

    @Test fun longGap_alwaysAtMostSeven() {
        // 极长缺口（30 天）→ 仍只补最近 7 天
        val latest = yesterday.minusDays(30)
        val result = dates(latest = latest, yesterday = yesterday)
        assertEquals(7, result.size)
        assertEquals(yesterday, result.last())
        assertEquals(yesterday.minusDays(6), result.first())
    }

    @Test fun resultsAreStartOfDay() {
        // 每个返回值都是「当天 0 点」（与 iOS calendar.startOfDay 对齐）
        val raw = backfillDateMillis(
            dayMillis(yesterday.minusDays(3)),
            dayMillis(yesterday),
            zone,
        )
        for (millis in raw) {
            val zdt = java.time.Instant.ofEpochMilli(millis).atZone(zone)
            assertEquals(0, zdt.hour)
            assertEquals(0, zdt.minute)
            assertEquals(0, zdt.second)
        }
    }
}
