package com.situ.aichat.meeting

import com.situ.aichat.data.model.MeetingStatus
import com.situ.aichat.data.model.MeetingTimeGranularity
import java.time.Instant
import java.time.ZoneId

/**
 * 赴约 / 错过的宽限期纯判定（1:1 iOS `Services/MeetingArrivalPolicy.swift`）——到点后多久还能赴约、多久算爽约。
 *
 * 用户 2026-06-24 拍板：
 * - **精确时间**（exact）：过点 **3 小时**内仍可赴约，超过算错过。
 * - **只到天**（dayOnly / vague）：解析器给的是默认时段、本就不精确，宽到「那天结束（次日 0 点）」——
 *   凌晨补赴下午的约才算错过。
 *
 * 纯函数、可注入 now / zone，不依赖系统当前时间，可单测。
 */
object MeetingArrivalPolicy {

    /** 精确时间约定的宽限时长（小时）。 */
    const val EXACT_GRACE_HOURS = 3L

    /** 这条约定「算错过」的截止时刻（epoch millis）。 */
    fun missedDeadlineMillis(scheduledAtMillis: Long, granularity: MeetingTimeGranularity, zone: ZoneId): Long =
        when (granularity) {
            MeetingTimeGranularity.EXACT ->
                scheduledAtMillis + EXACT_GRACE_HOURS * 60 * 60 * 1000
            MeetingTimeGranularity.DAY_ONLY, MeetingTimeGranularity.VAGUE -> {
                // 那天结束 = 见面那天的次日 0 点
                val day = Instant.ofEpochMilli(scheduledAtMillis).atZone(zone).toLocalDate()
                day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            }
        }

    /** 现在是否还在「可赴约」窗口（到点了但没过截止）。点通知后据此决定是否进线下见面。 */
    fun isWithinArrivalWindow(
        scheduledAtMillis: Long,
        granularity: MeetingTimeGranularity,
        nowMillis: Long,
        zone: ZoneId,
    ): Boolean = nowMillis <= missedDeadlineMillis(scheduledAtMillis, granularity, zone)

    /** 现在是否已过截止、该判错过。打开会话时扫描 confirmed 约定据此置 missed。 */
    fun isMissed(
        scheduledAtMillis: Long,
        granularity: MeetingTimeGranularity,
        nowMillis: Long,
        zone: ZoneId,
    ): Boolean = nowMillis > missedDeadlineMillis(scheduledAtMillis, granularity, zone)

    // ── 聊天页顶「等待期倒数小条 ↔ 到点出发赴约按钮」就地变身的两态判定（Phase 10·10d）。两态互斥（scheduledAt
    //    >now 倒数 / <=now 到点），过宽限则皆 false（交 Phase 11 爽约扫描）。纯函数便于单测。──

    /** 倒数小条该显示这条约定吗：已确认且见面时刻仍在未来（等待期）。 */
    fun isCountdownState(status: String, scheduledAtMillis: Long, nowMillis: Long): Boolean =
        MeetingStatus.fromRaw(status) == MeetingStatus.CONFIRMED && scheduledAtMillis > nowMillis

    /** 「出发赴约」按钮该显示这条约定吗：已确认、已到点（见面时刻已过/正当时）、仍在宽限窗口内（到点变身）。 */
    fun isArrivalState(
        status: String,
        scheduledAtMillis: Long,
        granularity: MeetingTimeGranularity,
        nowMillis: Long,
        zone: ZoneId,
    ): Boolean = MeetingStatus.fromRaw(status) == MeetingStatus.CONFIRMED &&
        scheduledAtMillis <= nowMillis &&
        isWithinArrivalWindow(scheduledAtMillis, granularity, nowMillis, zone)
}
