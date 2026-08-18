package com.situ.aichat.notification

import com.situ.aichat.data.local.entity.ScheduleEventEntity
import java.time.Instant
import java.time.ZoneId

/**
 * 通知调度的纯函数规则（P6.1c）：睡眠时段判断 + 当前事件定位。
 *
 * 1:1 对齐 iOS `ScheduleEvent.isSleepEvent(at:)` 与 `CharacterDailySchedule.currentEvent(at:)`，
 * 供调度器在「避免睡觉时段打扰」时调用（`shouldSkipNotificationWhileSleeping`）。纯函数便于单测。
 */
object NotificationScheduleRules {

    /** 睡眠关键词（逐字对齐 iOS `ScheduleEvent.isSleepEvent`：["睡","休息","入睡","sleep"]）。 */
    private val SLEEP_KEYWORDS = listOf("睡", "休息", "入睡", "sleep")

    /**
     * 是否为睡眠事件：活动含睡眠关键词，或「深夜(23:00–07:00) + 手机不可用」。对齐 iOS `isSleepEvent(at:)`。
     * @param at 判定时刻（epoch millis），深夜判断按该时刻的本地小时。
     */
    fun isSleepEvent(event: ScheduleEventEntity, at: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val activityLower = event.activity.lowercase()
        if (SLEEP_KEYWORDS.any { activityLower.contains(it) }) return true
        val hour = Instant.ofEpochMilli(at).atZone(zone).hour
        return (hour >= 23 || hour < 7) && !event.isPhoneAvailable
    }

    /** 返回覆盖 [at] 时刻的事件（[startTime, endTime] 含 at），无则 null。对齐 iOS `currentEvent(at:)`（闭区间）。 */
    fun currentEvent(events: List<ScheduleEventEntity>, at: Long): ScheduleEventEntity? =
        events.firstOrNull { at in it.startTime..it.endTime }

    /**
     * 角色在 [scheduledAt] 时刻是否应跳过通知（处于睡眠事件）。日程系统关闭 / 无覆盖事件 → 不跳过。
     * 对齐 iOS `SmartNotificationScheduler.shouldSkipNotificationWhileSleeping`（纯计算部分；DAO 读取在调用方）。
     */
    fun shouldSkipWhileSleeping(
        scheduleSystemEnabled: Boolean,
        dayEvents: List<ScheduleEventEntity>,
        scheduledAt: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        if (!scheduleSystemEnabled) return false
        val event = currentEvent(dayEvents, scheduledAt) ?: return false
        return isSleepEvent(event, scheduledAt, zone)
    }

    /** 免打扰窗命中判定。[startMinute] > [endMinute] 表示跨午夜窗（如默认 1380→450 即 23:00→次日 07:30）。 */
    fun isInQuietHours(minuteOfDay: Int, startMinute: Int, endMinute: Int): Boolean =
        if (startMinute > endMinute) minuteOfDay >= startMinute || minuteOfDay < endMinute
        else minuteOfDay in startMinute until endMinute

    /**
     * 从日程事件筛选适合发通知的（最多 5 个）：手机可用 + 未过期太久（>now-30min）+ 非睡眠；
     * 评分（内心独白+5 / 心情 emoji+3 / 活动名>5 字+2 / 首尾事件各+10），取分值前 5，再按时间正序。
     * 纯函数（对齐 iOS selectNotificationEvents）。
     *
     * 主动通知真实感改造 C6a：自 `DynamicNotificationContentService` 逐字迁入（原件整文件删除），
     * 内部睡眠关键词改用本 object 既有的 [SLEEP_KEYWORDS]（两处列表逐字相同，原副本随文件消亡）。
     */
    fun selectNotificationEvents(allEvents: List<ScheduleEventEntity>, now: Long): List<ScheduleEventEntity> {
        val thirtyMinAgo = now - 30 * 60 * 1000L
        val candidates = allEvents.filter { event ->
            event.isPhoneAvailable &&
                event.endTime > thirtyMinAgo &&
                SLEEP_KEYWORDS.none { event.activity.lowercase().contains(it) }
        }
        if (candidates.isEmpty()) return emptyList()

        val sorted = candidates.sortedBy { it.startTime }
        val scored = sorted.map { event ->
            var score = 0
            if (!event.innerThought.isNullOrEmpty()) score += 5
            if (event.moodEmoji.isNotEmpty()) score += 3
            if (event.activity.length > 5) score += 2
            event to score
        }.toMutableList()

        // 第一个和最后一个事件加分（≈ 早安和晚安）
        scored[0] = scored[0].first to (scored[0].second + 10)
        if (scored.size > 1) {
            val lastIdx = scored.size - 1
            scored[lastIdx] = scored[lastIdx].first to (scored[lastIdx].second + 10)
        }

        return scored.sortedByDescending { it.second }.take(5).map { it.first }.sortedBy { it.startTime }
    }
}
