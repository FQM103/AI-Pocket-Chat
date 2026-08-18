package com.situ.aichat.story

import java.time.Instant
import java.time.ZoneId

/**
 * 追更自动连载检查的纯决策（11.1g-2，1:1 iOS `StoryScheduleService.shouldRunNow` :139-149 +
 * `StoryScheduleActor.checkAndGenerateStories` :24-46 的跳过判定）。
 *
 * 抽 internal object 便于单测反推 iOS：防抖间隔、free 跳过、今日已更跳过、本地同日判定。
 * 编排（互斥/拉取/生成/排解锁/落库）在 [StoryAutoSerializeService]，批真机集中验 + Logcat。
 */
internal object StoryAutoSerializePolicy {

    /** 防抖间隔（iOS `minimumCheckInterval = 10 * 60` 秒）。 */
    const val MINIMUM_CHECK_INTERVAL_MS = 10 * 60 * 1000L

    /**
     * 距上次检查是否已够间隔（1:1 iOS `shouldRunNow`：lastCheckAt>0 且未到间隔 → false；否则 true）。
     * lastCheckAt<=0（从未检查）→ 放行首跑。
     */
    fun isDebounceElapsed(lastCheckAtMillis: Long, nowMillis: Long): Boolean =
        lastCheckAtMillis <= 0L || (nowMillis - lastCheckAtMillis) >= MINIMUM_CHECK_INTERVAL_MS

    /**
     * 该故事此刻是否应自动生成下一章（1:1 iOS checkAndGenerateStories 循环体跳过判定）：
     * - 自由模式（[StoryUpdateMode.FREE]）→ 否（只追更模式自动生成）。
     * - 今天已生成过（最新章创建时间与现在同一本地日）→ 否（每天至多一章）。
     * - 其余 → 是。
     */
    fun shouldAutoGenerate(
        updateMode: String,
        latestChapterCreatedAt: Long?,
        nowMillis: Long,
        zoneId: ZoneId,
    ): Boolean {
        if (updateMode == StoryUpdateMode.FREE) return false
        if (latestChapterCreatedAt != null && isSameLocalDay(latestChapterCreatedAt, nowMillis, zoneId)) return false
        return true
    }

    /** 两毫秒时间戳是否落在 [zoneId] 的同一日历日（= iOS `Calendar.isDate(_, inSameDayAs:)`）。 */
    fun isSameLocalDay(aMillis: Long, bMillis: Long, zoneId: ZoneId): Boolean {
        val a = Instant.ofEpochMilli(aMillis).atZone(zoneId).toLocalDate()
        val b = Instant.ofEpochMilli(bMillis).atZone(zoneId).toLocalDate()
        return a == b
    }
}
