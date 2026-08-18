package com.situ.aichat.prompt.schedule

import com.situ.aichat.data.local.dao.ScheduleDao
import com.situ.aichat.data.local.entity.ScheduleEventEntity
import com.situ.aichat.notification.NotificationScheduleRules
import com.situ.aichat.util.DateFormatters
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 1:1 移植 iOS `MomentGenerationService.isCharacterSleeping`（虽挂在朋友圈服务名下，实为通用「角色此刻是否在睡」
 * 判定，日记 M07 + 朋友圈 M06 共用）。比 [NotificationScheduleRules.shouldSkipWhileSleeping]（仅看当前事件）更全：
 * 含「无日程→深夜 23:00–07:00 兜底」「间隙→看最近结束事件 + 深夜兜底」。
 *
 * 决策核心 [isSleepingFromEvents] 为纯函数（internal）便于单测；DB 取数在 [isSleeping]。
 */
@Singleton
class CharacterSleepChecker @Inject constructor(
    private val scheduleDao: ScheduleDao,
) {
    /**
     * 角色在 [nowMillis] 是否在睡觉。[scheduleSystemEnabled] 关闭时直接返回 false（不阻拦，对齐 iOS guard）。
     */
    suspend fun isSleeping(
        characterUuid: String,
        scheduleSystemEnabled: Boolean,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        if (!scheduleSystemEnabled) return false
        val today = DateFormatters.startOfDayMillis(nowMillis, zone)
        val schedule = scheduleDao.scheduleFor(characterUuid, today)
        // schedule 行不存在 → events 传 null（深夜兜底）；存在 → 取其事件列表（可能为空）。
        val events = schedule?.let { scheduleDao.eventsForSchedule(it.uuid) }
        return isSleepingFromEvents(events, nowMillis, zone)
    }

    companion object {
        /**
         * 纯判定（1:1 iOS）：
         * - [events] == null（无当日日程行）→ 深夜兜底（23:00–07:00 视为睡）。
         * - 有覆盖当前事件 → 睡眠事件 或 手机不可用。
         * - 间隙（无覆盖事件）→ 最近结束的事件若是睡眠事件则睡；若手机不可用且深夜则睡；否则深夜兜底。
         */
        internal fun isSleepingFromEvents(
            events: List<ScheduleEventEntity>?,
            nowMillis: Long,
            zone: ZoneId,
        ): Boolean {
            val hour = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone).hour
            val isDeepNight = hour >= 23 || hour < 7
            if (events == null) return isDeepNight

            val current = NotificationScheduleRules.currentEvent(events, nowMillis)
            if (current != null) {
                return NotificationScheduleRules.isSleepEvent(current, nowMillis, zone) || !current.isPhoneAvailable
            }
            // 间隙：最近刚结束的事件（endTime < now 中 endTime 最大者）。
            val lastPast = events.filter { it.endTime < nowMillis }.maxByOrNull { it.endTime }
            if (lastPast != null) {
                if (NotificationScheduleRules.isSleepEvent(lastPast, nowMillis, zone)) return true
                if (!lastPast.isPhoneAvailable && isDeepNight) return true
            }
            return isDeepNight
        }
    }
}
