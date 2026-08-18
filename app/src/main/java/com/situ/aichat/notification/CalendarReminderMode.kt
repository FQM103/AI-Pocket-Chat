package com.situ.aichat.notification

/**
 * 日历事件提醒方式（P6.3，**安卓侧 decision② 的新增开关**，iOS 无此设置——iOS 总是「系统 15min 提醒 +
 * 角色 30min 通知」两者都发）。三态：
 * - [SYSTEM]「仅系统提醒」：只靠 [com.situ.aichat.data.calendar.CalendarWriter] 写入事件时附带的系统 15 分钟
 *   提醒，**不**额外发 app 侧角色通知。
 * - [CHARACTER]「仅角色提醒」：写入事件时**不**附带系统提醒，改由 [CalendarNotificationScheduler] 在事件前
 *   30 分钟用角色口吻发 app 通知（并落成聊天消息）。
 * - [BOTH]「两者都用」（默认）：写入事件附带系统 15min 提醒 + app 侧 30min 角色通知都发。= 1:1 iOS 行为。
 *
 * 影响两处：① [com.situ.aichat.data.calendar.CalendarWriter.createEvent] 是否写系统提醒（SYSTEM/BOTH 写，
 * CHARACTER 不写）；② [CalendarNotificationScheduler] 是否调度 app 30min 角色通知（CHARACTER/BOTH 调度，
 * SYSTEM 不调度）。
 */
enum class CalendarReminderMode(val raw: String) {
    SYSTEM("system"),
    CHARACTER("character"),
    BOTH("both");

    /** 是否写入事件自带的系统 15 分钟提醒（CalendarWriter）。 */
    val writesSystemReminder: Boolean get() = this != CHARACTER

    /** 是否调度 app 侧事件前 30 分钟角色通知（CalendarNotificationScheduler）。 */
    val schedulesCharacterNotification: Boolean get() = this != SYSTEM

    companion object {
        /** 未知 / null → 默认「两者都用」（= 1:1 iOS）。 */
        fun fromRaw(raw: String?): CalendarReminderMode =
            entries.firstOrNull { it.raw == raw } ?: BOTH
    }
}
