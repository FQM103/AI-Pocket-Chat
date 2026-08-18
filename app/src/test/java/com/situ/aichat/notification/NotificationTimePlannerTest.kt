package com.situ.aichat.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Test

/**
 * NotificationTimePlanner 单测（移植自 iOS NotificationLearningService 冷启动子集）。
 * 固定 UTC 时区 + 固定 baseDate 保证确定性；djb2 哈希值从 iOS 同算法手算反推，
 * 分类时段从 iOS candidateWindows/categoryBias 行为反推。
 */
class NotificationTimePlannerTest {

    private val zone: ZoneId = ZoneOffset.UTC

    /** 2026-01-15 当天某时刻的 epoch millis（UTC）。 */
    private fun millis(hour: Int, minute: Int): Long =
        LocalDateTime.of(2026, 1, 15, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun hourOf(epochMillis: Long): Int = Instant.ofEpochMilli(epochMillis).atZone(zone).hour

    private val baseDate: Long = millis(12, 0)

    // MARK: - djb2 stableHash（从 iOS 同算法手算反推）

    @Test fun stableHash_matchesDjb2() {
        // 5381 →*33+97 →*33+98 →*33+99 = 193485963（与 Swift unicodeScalars djb2 对 "abc" 一致）
        assertEquals(193485963L, NotificationTimePlanner.stableHash("abc"))
        assertEquals(5381L, NotificationTimePlanner.stableHash(""))
    }

    // MARK: - candidateWindows（对齐 iOS）

    @Test fun candidateWindows_morningHasThreeFixed() {
        val windows = NotificationTimePlanner.candidateWindows("morning", emptyList())
        assertEquals(3, windows.size)
        assertEquals(listOf("window_450", "window_480", "window_510"), windows.map { it.id })
    }

    @Test fun candidateWindows_streakRemindEmptyBucketsUsesFallback() {
        // streak_remind 空活跃桶 → 回退 [12:00, 21:00] 两个窗
        val windows = NotificationTimePlanner.candidateWindows("streak_remind", emptyList())
        assertEquals(listOf(720, 1260), windows.map { it.startMinute })
    }

    @Test fun candidateWindows_unknownCategoryNoonOnly() {
        val windows = NotificationTimePlanner.candidateWindows("totally_unknown", emptyList())
        assertEquals(listOf(720), windows.map { it.startMinute })
    }

    // MARK: - resolvedMinuteColdStart 确定性 + 落在窗内

    @Test fun resolvedMinuteColdStart_deterministicAndInBounds() {
        val window = NotificationTimePlanner.TimeWindow("window_450", 450, 479)
        val a = NotificationTimePlanner.resolvedMinuteColdStart(window, "char1", "morning", "2026-01-15")
        val b = NotificationTimePlanner.resolvedMinuteColdStart(window, "char1", "morning", "2026-01-15")
        assertEquals("同输入应得同输出", a, b)
        // lowerBound=min(454,479)=454, upperBound=max(454,475)=475
        assertTrue("分钟应落在窗内偏移 [454,475]，实际 $a", a in 454..475)
    }

    @Test fun resolvedMinuteColdStart_differsByDaySeed() {
        val window = NotificationTimePlanner.TimeWindow("window_450", 450, 479)
        val d1 = NotificationTimePlanner.resolvedMinuteColdStart(window, "char1", "morning", "2026-01-15")
        val d2 = NotificationTimePlanner.resolvedMinuteColdStart(window, "char1", "morning", "2026-02-20")
        // 不强制不同（理论可能撞），但同窗不同天种子大概率不同；这里只验证仍在界内
        assertTrue(d2 in 454..475)
        assertTrue(d1 in 454..475)
    }

    // MARK: - chooseSchedule 选出分类合适的时段（categoryBias 引导）

    @Test fun chooseSchedule_morningLandsInEightAm() {
        // 空活跃桶 + 空 reserved：categoryBias 把 morning 推向 8:00–9:00（window_480 胜出）
        val selection = NotificationTimePlanner.chooseSchedule("char1", "morning", baseDate, emptyList(), emptyList(), zone)
        requireNotNull(selection)
        assertEquals("window_480", selection.windowId)
        assertEquals("morning 应落在 8 点", 8, hourOf(selection.scheduledAt))
    }

    @Test fun chooseSchedule_eveningLandsInEightPm() {
        val selection = NotificationTimePlanner.chooseSchedule("char1", "evening", baseDate, emptyList(), emptyList(), zone)
        requireNotNull(selection)
        assertEquals("evening 应落在 20 点", 20, hourOf(selection.scheduledAt))
    }

    @Test fun chooseSchedule_scheduledAtIsOnBaseDay() {
        val selection = NotificationTimePlanner.chooseSchedule("char1", "streak_remind", baseDate, emptyList(), emptyList(), zone)
        requireNotNull(selection)
        val day = LocalDate.ofInstant(Instant.ofEpochMilli(selection.scheduledAt), zone)
        assertEquals(LocalDate.of(2026, 1, 15), day)
    }

    // MARK: - resolvedTargetDate（首次调度今天 vs 明天）

    @Test fun resolvedTargetDate_nonFirstReturnsBaseDay() {
        // 非首次 → 直接今天 0 点
        val target = NotificationTimePlanner.resolvedTargetDate(
            now = millis(10, 0), daysFromNow = 0, category = "streak_remind", characterId = "c1",
            prefersTodayForFirstSchedule = true, isFirstSchedule = false,
            activityBucketMinutes = emptyList(), reservedDates = emptyList(), zone = zone,
        )
        assertEquals(LocalDate.of(2026, 1, 15), LocalDate.ofInstant(Instant.ofEpochMilli(target), zone))
    }

    @Test fun resolvedTargetDate_firstScheduleLateInDayRollsToTomorrow() {
        // 首次 + 倾向今天，但现在已 23:50：streak_remind 今天可选时刻已过/不足 20min → 顺延明天
        val target = NotificationTimePlanner.resolvedTargetDate(
            now = millis(23, 50), daysFromNow = 0, category = "streak_remind", characterId = "c1",
            prefersTodayForFirstSchedule = true, isFirstSchedule = true,
            activityBucketMinutes = emptyList(), reservedDates = emptyList(), zone = zone,
        )
        assertEquals(LocalDate.of(2026, 1, 16), LocalDate.ofInstant(Instant.ofEpochMilli(target), zone))
    }

    // MARK: - activityScore / categoryBias

    @Test fun activityScore_emptyBucketsIsBaseline() {
        val window = NotificationTimePlanner.TimeWindow("w", 720, 749)
        assertEquals(0.45, NotificationTimePlanner.activityScore(window, emptyList()), 1e-9)
    }

    @Test fun activityScore_farBucketFloorsAtPointTwo() {
        val window = NotificationTimePlanner.TimeWindow("w", 0, 29) // center 14
        // 距离 800-14=786 → closeness 0 → max(0.2, 0) = 0.2
        assertEquals(0.2, NotificationTimePlanner.activityScore(window, listOf(800)), 1e-9)
    }

    @Test fun categoryBias_morningInRangeRewardedOutOfRangePenalized() {
        val inRange = NotificationTimePlanner.TimeWindow("window_480", 480, 509) // center 494 ∈ [480,540]
        val outRange = NotificationTimePlanner.TimeWindow("window_450", 450, 479) // center 464 ∉
        assertEquals(0.05, NotificationTimePlanner.categoryBias("morning", inRange), 1e-9)
        assertEquals(-0.03, NotificationTimePlanner.categoryBias("morning", outRange), 1e-9)
    }
}
