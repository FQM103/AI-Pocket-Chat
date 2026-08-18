package com.situ.aichat.notification

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/**
 * 通知发送时机规划（P6.1c 冷启动子集 + P6.1e 学习层）。移植自 iOS `NotificationLearningService`：
 * 按分类给出候选时间窗 + 打分选最优 + 选窗内分钟，避开撞车。纯函数，无 DB——学习数据由调用方
 * （[NotificationScheduler]）从 [NotificationLearningService] 取来、以 [WindowStat] 形式喂入。
 *
 * **打分** = activity·0.45 + learned·0.45 + categoryBias − repetition − collision。
 * - learned：该窗口的 [WindowStat.smoothedScore]（无学习数据 → 0.5，等价 iOS 冷启动）。
 * - repetition：同窗口上次调度距今 <12h 罚 0.16 / <24h 罚 0.08（[repetitionPenalty]）。
 * **选窗内分钟**：冷启动（无该窗口数据）用 djb2 确定性散列；已有数据则取窗口中心（对齐 iOS resolvedMinute 双分支）。
 */
object NotificationTimePlanner {

    /** 同角色相邻通知的期望最小间隔（错峰用）。对齐 iOS `preferredSpacing` = 75 分钟。 */
    private const val PREFERRED_SPACING_MS = 75L * 60 * 1000

    /** 喂给打分的窗口学习数据（来自 [com.situ.aichat.data.local.entity.NotificationWindowStatsEntity]）。 */
    data class WindowStat(
        val smoothedScore: Double,
        val lastScheduledAt: Long?,
        val scheduledCount: Int,
    )

    /** 一个 30 分钟级别的候选时间窗（分钟为「当天 0 点起的分钟数」）。 */
    data class TimeWindow(val id: String, val startMinute: Int, val endMinute: Int) {
        val centerMinute: Int get() = startMinute + maxOf(0, (endMinute - startMinute) / 2)
    }

    /** 选中的窗口 + 绝对触发时刻（epoch millis）。 */
    data class WindowSelection(
        val windowId: String,
        val startMinute: Int,
        val endMinute: Int,
        val scheduledAt: Long,
    )

    /**
     * 为某分类在 [baseDateMillis] 所在那天选一个最佳触发时刻。对齐 iOS `chooseSchedule`。
     * @param statsByWindowId 该角色该分类各窗口的学习数据（windowId → [WindowStat]）；空 = 冷启动等价。
     * @param isColdStart 该分类总调度次数为 0（影响选窗内分钟：冷启动散列 / 否则取中心）。
     */
    fun chooseSchedule(
        characterId: String,
        category: String,
        baseDateMillis: Long,
        activityBucketMinutes: List<Int>,
        reservedDates: List<Long>,
        zone: ZoneId = ZoneId.systemDefault(),
        statsByWindowId: Map<String, WindowStat> = emptyMap(),
        isColdStart: Boolean = true,
    ): WindowSelection? {
        val dayStart = startOfDayMillis(baseDateMillis, zone)
        val daySeed = daySeedString(baseDateMillis, zone)
        val windows = candidateWindows(category, activityBucketMinutes)
        return windows
            .map { window ->
                val stat = statsByWindowId[window.id]
                val scheduledAt = scheduledDate(window, dayStart, characterId, category, daySeed, reservedDates, stat, isColdStart)
                val score = windowScore(window, scheduledAt, activityBucketMinutes, reservedDates, category, stat)
                WindowSelection(window.id, window.startMinute, window.endMinute, scheduledAt) to score
            }
            .maxByOrNull { it.second }
            ?.first
    }

    /**
     * 决定一条通知落在哪一天（对齐 iOS `resolvedTargetDate`）：返回目标日的 0 点 millis。
     * 仅在「首次调度 + 倾向今天 + daysFromNow==0」时，若今天可选时刻距现在 >20 分钟则用今天，否则顺延到明天。
     */
    fun resolvedTargetDate(
        now: Long,
        daysFromNow: Int,
        category: String,
        characterId: String,
        prefersTodayForFirstSchedule: Boolean,
        isFirstSchedule: Boolean,
        activityBucketMinutes: List<Int>,
        reservedDates: List<Long>,
        zone: ZoneId = ZoneId.systemDefault(),
        statsByWindowId: Map<String, WindowStat> = emptyMap(),
        isColdStart: Boolean = true,
    ): Long {
        val baseDay = startOfDayPlusDays(now, daysFromNow.toLong(), zone)
        if (!(isFirstSchedule && prefersTodayForFirstSchedule && daysFromNow == 0)) return baseDay

        val todaySelection = chooseSchedule(
            characterId, category, baseDay, activityBucketMinutes, reservedDates, zone, statsByWindowId, isColdStart,
        )
        if (todaySelection != null && todaySelection.scheduledAt > now + 20L * 60 * 1000) return baseDay
        return startOfDayPlusDays(now, (daysFromNow + 1).toLong(), zone)
    }

    // MARK: - 打分

    private fun windowScore(
        window: TimeWindow,
        scheduledAt: Long,
        activityBucketMinutes: List<Int>,
        reservedDates: List<Long>,
        category: String,
        stat: WindowStat?,
    ): Double {
        val activityScore = activityScore(window, activityBucketMinutes)
        val learnedScore = stat?.smoothedScore ?: 0.5
        val repetitionPenalty = repetitionPenalty(stat?.lastScheduledAt, scheduledAt)
        val collisionPenalty = collisionPenalty(scheduledAt, reservedDates)
        val categoryBias = categoryBias(category, window)
        return (activityScore * 0.45) + (learnedScore * 0.45) + categoryBias - repetitionPenalty - collisionPenalty
    }

    internal fun activityScore(window: TimeWindow, activityBucketMinutes: List<Int>): Double {
        if (activityBucketMinutes.isEmpty()) return 0.45
        val center = window.centerMinute
        var best = 0.0
        activityBucketMinutes.take(4).forEachIndexed { index, minute ->
            val distance = abs(center - minute)
            val closeness = maxOf(0.0, 1.0 - (distance / 180.0))
            val weight = maxOf(0.2, 1.0 - (index * 0.18))
            best = maxOf(best, closeness * weight)
        }
        return maxOf(0.2, best)
    }

    /** 同窗口上次调度距本次 <12h 罚 0.16 / <24h 罚 0.08（对齐 iOS repetitionPenalty）。纯函数。 */
    internal fun repetitionPenalty(lastScheduledAt: Long?, scheduledAt: Long): Double {
        if (lastScheduledAt == null) return 0.0
        val interval = scheduledAt - lastScheduledAt
        return when {
            interval < 12L * 60 * 60 * 1000 -> 0.16
            interval < 24L * 60 * 60 * 1000 -> 0.08
            else -> 0.0
        }
    }

    private fun collisionPenalty(scheduledAt: Long, reservedDates: List<Long>): Double {
        for (reserved in reservedDates) {
            val distance = abs(scheduledAt - reserved)
            if (distance < 60L * 60 * 1000) return 0.25
            if (distance < 90L * 60 * 1000) return 0.12
        }
        return 0.0
    }

    internal fun categoryBias(category: String, window: TimeWindow): Double = when (category) {
        "morning" -> bias(window.centerMinute, 8 * 60..9 * 60)
        "evening" -> bias(window.centerMinute, 20 * 60..(21 * 60 + 30))
        "streak_broken" -> bias(window.centerMinute, 9 * 60..(10 * 60 + 30))
        else -> 0.0
    }

    private fun bias(minute: Int, preferredRange: IntRange): Double =
        if (minute in preferredRange) 0.05 else -0.03

    // MARK: - 候选窗口（对齐 iOS candidateWindows）

    internal fun candidateWindows(category: String, activityBucketMinutes: List<Int>): List<TimeWindow> = when (category) {
        "streak_remind" -> activityWindows(activityBucketMinutes, fallbackMinutes = listOf(12 * 60, 21 * 60))
        "random" -> activityWindows(
            activityBucketMinutes.drop(1),
            fallbackMinutes = listOf(15 * 60, 18 * 60 + 30, 21 * 60),
        )
        "morning" -> fixedWindows(listOf(7 * 60 + 30, 8 * 60, 8 * 60 + 30))
        "evening" -> fixedWindows(listOf(20 * 60, 20 * 60 + 30, 21 * 60))
        "streak_broken" -> fixedWindows(listOf(9 * 60, 9 * 60 + 30, 10 * 60))
        else -> fixedWindows(listOf(12 * 60))
    }

    private fun activityWindows(bucketMinutes: List<Int>, fallbackMinutes: List<Int>): List<TimeWindow> {
        val minutes = bucketMinutes.ifEmpty { fallbackMinutes }
        val uniqueMinutes = minutes.distinct().ifEmpty { fallbackMinutes }
        return fixedWindows(uniqueMinutes.take(3))
    }

    private fun fixedWindows(startMinutes: List<Int>): List<TimeWindow> = startMinutes.map { minute ->
        val normalizedMinute = minute.coerceIn(0, (23 * 60) + 30)
        TimeWindow(
            id = "window_$normalizedMinute",
            startMinute = normalizedMinute,
            endMinute = minOf((24 * 60) - 1, normalizedMinute + 29),
        )
    }

    // MARK: - 选窗内分钟 + 错峰（对齐 iOS scheduledDate/resolvedMinute/staggeredDate）

    private fun scheduledDate(
        window: TimeWindow,
        dayStartMillis: Long,
        characterId: String,
        category: String,
        daySeed: String,
        reservedDates: List<Long>,
        stat: WindowStat?,
        isColdStart: Boolean,
    ): Long {
        val minute = resolvedMinute(window, characterId, category, daySeed, stat, isColdStart)
        val initial = dayStartMillis + minute * 60L * 1000
        return staggeredDate(initial, window, dayStartMillis, reservedDates)
    }

    /** 选窗内分钟（对齐 iOS resolvedMinute 双分支）：冷启动/该窗口无数据 → djb2 散列；否则取窗口中心。 */
    private fun resolvedMinute(
        window: TimeWindow,
        characterId: String,
        category: String,
        daySeed: String,
        stat: WindowStat?,
        isColdStart: Boolean,
    ): Int {
        if (isColdStart || (stat?.scheduledCount ?: 0) == 0) {
            return resolvedMinuteColdStart(window, characterId, category, daySeed)
        }
        val lowerBound = minOf(window.startMinute + 4, window.endMinute)
        val upperBound = maxOf(lowerBound, window.endMinute - 4)
        return minOf(upperBound, maxOf(lowerBound, window.centerMinute))
    }

    /** 冷启动确定性选分钟：djb2 哈希 → 窗内偏移，同输入同输出。对齐 iOS resolvedMinute 的冷启动分支。 */
    internal fun resolvedMinuteColdStart(
        window: TimeWindow,
        characterId: String,
        category: String,
        daySeed: String,
    ): Int {
        val lowerBound = minOf(window.startMinute + 4, window.endMinute)
        val upperBound = maxOf(lowerBound, window.endMinute - 4)
        val offsetRange = maxOf(1, upperBound - lowerBound + 1)
        val hashed = stableHash("$characterId|$category|${window.id}|$daySeed")
        val nonNegativeHash = hashed and Long.MAX_VALUE // 清符号位，避免负数取模
        return lowerBound + (nonNegativeHash % offsetRange).toInt()
    }

    private fun staggeredDate(
        date: Long,
        window: TimeWindow,
        dayStartMillis: Long,
        reservedDates: List<Long>,
    ): Long {
        if (reservedDates.isEmpty()) return date
        val candidateOffsets = listOf(0, 10, -10, 20, -20)
        val currentMinute = ((date - dayStartMillis) / (60L * 1000)).toInt()
        val lowerBound = window.startMinute + 2
        val upperBound = window.endMinute - 2
        for (offset in candidateOffsets) {
            val adjustedMinute = currentMinute + offset
            if (adjustedMinute < lowerBound || adjustedMinute > upperBound) continue
            val adjustedDate = dayStartMillis + adjustedMinute * 60L * 1000
            if (isWellSpaced(adjustedDate, reservedDates)) return adjustedDate
        }
        return date
    }

    private fun isWellSpaced(date: Long, reservedDates: List<Long>): Boolean =
        reservedDates.all { abs(date - it) >= PREFERRED_SPACING_MS }

    /** djb2 哈希（用 Long 镜像 Swift 64 位 Int 的溢出语义；ASCII 输入下与 iOS 逐位一致）。 */
    internal fun stableHash(input: String): Long {
        var hash = 5381L
        for (ch in input) {
            hash = (hash shl 5) + hash + ch.code.toLong() // ((hash << 5) + hash) + c
        }
        return hash
    }

    // MARK: - 日期工具

    private fun startOfDayMillis(millis: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    private fun startOfDayPlusDays(nowMillis: Long, days: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().plusDays(days)
            .atStartOfDay(zone).toInstant().toEpochMilli()

    private fun daySeedString(millis: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toString() // yyyy-MM-dd
}
