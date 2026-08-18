package com.situ.aichat.ui.schedule

/** 全天日程视图日期头的相对日标记（1:1 iOS formattedDate：今天/昨天/明天，其余显星期）。 */
enum class ScheduleRelDay { TODAY, YESTERDAY, TOMORROW, OTHER }

/**
 * 全天日程视图的日期导航纯函数（P14.2b）。断言反推 iOS `ScheduleFullDayView`（导航边界 L254-275 /
 * formattedDate L226-252）。全部以「当天 0 点毫秒」为单位（调用方用设备时区算好 today/昨/明/earliest/latest）。
 */
internal object ScheduleNavLogic {

    /**
     * 相对日标记（1:1 iOS formattedDate 的特判顺序：今天 > 昨天 > 明天 > 星期）。today/yesterday/tomorrow
     * 均由调用方用同一时区算好的「当天 0 点」。
     */
    fun relativeDayToken(selected: Long, today: Long, yesterday: Long, tomorrow: Long): ScheduleRelDay =
        when (selected) {
            today -> ScheduleRelDay.TODAY
            yesterday -> ScheduleRelDay.YESTERDAY
            tomorrow -> ScheduleRelDay.TOMORROW
            else -> ScheduleRelDay.OTHER
        }

    /**
     * 可导航到的最晚一天（1:1 iOS latestAvailableDate L258-262）：默认今天；仅当「明天已有日程」才放行到明天。
     */
    fun latestAvailableDay(today: Long, tomorrow: Long, hasTomorrowSchedule: Boolean): Long =
        if (hasTomorrowSchedule) tomorrow else today

    /** 目标日是否在 [earliest, latest] 闭区间内（1:1 iOS canNavigateTo L272-275）。 */
    fun canNavigateTo(target: Long, earliest: Long, latest: Long): Boolean =
        target in earliest..latest

    /** 能否往后翻（1:1 iOS canNavigateForward L264-266：selectedDate < latest）。 */
    fun canNavigateForward(selected: Long, latest: Long): Boolean = selected < latest

    /** 能否往前翻（selected > earliest；iOS 用 canNavigateTo(previousDay) 守卫，等价）。 */
    fun canNavigateBackward(selected: Long, earliest: Long): Boolean = selected > earliest
}
