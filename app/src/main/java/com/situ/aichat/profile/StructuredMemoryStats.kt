package com.situ.aichat.profile

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.max

/**
 * 共同记忆「档案统计」5 项（资料页·从消息时间戳实时算，不经大模型）。
 * 1:1 iOS `Utilities/StructuredMemoryStats.swift`。纯函数（注入 zone 便于单测）。
 *
 * - [firstMeetDate] 初次相识：character.firstMessageDate ?? 最早一条
 * - [busiestDay] 聊天最多的一天（按日历日分组取最多）
 * - [latestNightChat] 最晚夜聊（0:00–5:59 取 hour*60+minute 最大那条）
 * - [longestConversation] 最长一次连续对话的消息数（间隔 > 2 小时算断开）
 * - [longestStreak] 历史最长连续聊天天数
 */
object StructuredMemoryStats {

    /** 2 小时（毫秒）：消息间隔 ≤ 此值算同一次连续对话。 */
    private const val GAP_THRESHOLD_MILLIS = 7_200_000L

    data class BusiestDay(val dateMillis: Long, val count: Int)

    data class Result(
        val firstMeetDate: Long?,
        val busiestDay: BusiestDay?,
        val latestNightChat: Long?,
        val longestConversation: Int,
        val longestStreak: Int,
    ) {
        val hasAnyData: Boolean
            get() = firstMeetDate != null || busiestDay != null || latestNightChat != null ||
                longestConversation > 0 || longestStreak > 0
    }

    val EMPTY = Result(null, null, null, 0, 0)

    /** [timestampsAsc] 须为该角色「非空内容」消息时间戳的升序列表（DAO ORDER BY timestamp ASC）。 */
    fun compute(
        timestampsAsc: List<Long>,
        firstMessageDate: Long?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Result {
        if (timestampsAsc.isEmpty()) return EMPTY

        val firstMeetDate = firstMessageDate ?: timestampsAsc.first()

        // 聊天最多的一天：按日历日分组统计。
        val dayCounts = HashMap<LocalDate, Int>()
        for (t in timestampsAsc) {
            val day = Instant.ofEpochMilli(t).atZone(zone).toLocalDate()
            dayCounts[day] = (dayCounts[day] ?: 0) + 1
        }
        val busiestDay = dayCounts.maxByOrNull { it.value }?.let { (day, count) ->
            BusiestDay(day.atStartOfDay(zone).toInstant().toEpochMilli(), count)
        }

        // 最晚夜聊：0:00–5:59 取 hour*60+minute 最大的那条。
        var latestNightChat: Long? = null
        var latestMinuteOfDay = -1
        for (t in timestampsAsc) {
            val zdt = Instant.ofEpochMilli(t).atZone(zone)
            if (zdt.hour in 0..5) {
                val minuteOfDay = zdt.hour * 60 + zdt.minute
                if (minuteOfDay > latestMinuteOfDay) {
                    latestMinuteOfDay = minuteOfDay
                    latestNightChat = t
                }
            }
        }

        // 最长一次连续对话：间隔 > 2 小时算断开。
        var currentRun = 1
        var longestConversation = 1
        for (i in 1 until timestampsAsc.size) {
            if (timestampsAsc[i] - timestampsAsc[i - 1] <= GAP_THRESHOLD_MILLIS) {
                currentRun++
                longestConversation = max(longestConversation, currentRun)
            } else {
                currentRun = 1
            }
        }

        // 历史最长连续聊天天数：日期去重后求最长连续日期序列。
        val uniqueDays = timestampsAsc
            .map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
            .distinct()
            .sorted()
        var longestStreak = if (uniqueDays.isEmpty()) 0 else 1
        var currentDayRun = 1
        for (i in 1 until uniqueDays.size) {
            if (ChronoUnit.DAYS.between(uniqueDays[i - 1], uniqueDays[i]) == 1L) {
                currentDayRun++
                longestStreak = max(longestStreak, currentDayRun)
            } else {
                currentDayRun = 1
            }
        }

        return Result(firstMeetDate, busiestDay, latestNightChat, longestConversation, longestStreak)
    }
}
