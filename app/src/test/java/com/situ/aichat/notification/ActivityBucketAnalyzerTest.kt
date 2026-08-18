package com.situ.aichat.notification

import org.junit.Assert.assertEquals
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Test

/**
 * 活跃时段桶纯函数单测。**自 `NotificationSchedulerLogicTest` 随实现只搬不改迁入**（R1 🟡-1）——
 * 断言逐字保持原样（迁移前后行为字节级不变的证据）。
 * 桶逻辑断言从 iOS analyzeActivityBucketMinutes 反推（30 分钟桶 + 近因加权 + 前 4 + 总权重<5 回退默认）。
 */
class ActivityBucketAnalyzerTest {

    private val zone: ZoneId = ZoneOffset.UTC

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

    // MARK: - computeActivityBuckets

    @Test fun activityBuckets_emptyReturnsDefault() {
        assertEquals(listOf(720, 1260), ActivityBucketAnalyzer.computeActivityBuckets(emptyList(), at(2026, 1, 15, 23, 0), zone))
    }

    @Test fun activityBuckets_belowMinScoreReturnsDefault() {
        // 4 条今天的消息（每条权重 1.0，总 4 < 5）→ 回退默认
        val now = at(2026, 1, 15, 23, 0)
        val ts = List(4) { at(2026, 1, 15, 14, 10) }
        assertEquals(listOf(720, 1260), ActivityBucketAnalyzer.computeActivityBuckets(ts, now, zone))
    }

    @Test fun activityBuckets_topBucketsByWeightThenKey() {
        // 5 条 @14:10 → 桶 840；5 条 @21:40 → 桶 1290。权重各 5.0，并列按 key 升序
        val now = at(2026, 1, 15, 23, 0)
        val ts = List(5) { at(2026, 1, 15, 14, 10) } + List(5) { at(2026, 1, 15, 21, 40) }
        assertEquals(listOf(840, 1290), ActivityBucketAnalyzer.computeActivityBuckets(ts, now, zone))
    }

    @Test fun activityBuckets_recencyWeightingOrders() {
        // 6 条今天 @14:10（权重 1.0 → 6.0，桶 840）vs 6 条 10 天前 @09:10（权重 0.5 → 3.0，桶 540）
        val now = at(2026, 1, 15, 23, 0)
        val recent = List(6) { at(2026, 1, 15, 14, 10) }
        val old = List(6) { at(2026, 1, 5, 9, 10) }
        assertEquals(listOf(840, 540), ActivityBucketAnalyzer.computeActivityBuckets(recent + old, now, zone))
    }
}
