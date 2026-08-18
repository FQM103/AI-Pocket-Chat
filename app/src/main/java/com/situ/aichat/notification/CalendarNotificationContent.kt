package com.situ.aichat.notification

import android.content.Context
import com.situ.aichat.R

/**
 * 日历事件通知文案（P6.3）。1:1 移植 iOS `CalendarNotificationService.generateNotificationMessage`：
 * 从 5 条静态模板里随机取一条，插值「事件标题 + 提前分钟数」。模板存 `R.array.notif_calendar_templates`
 * （zh 逐字对齐 iOS Localizable.xcstrings，en 逐字对齐 Swift 源；占位 `%1$s`=标题，`%2$d`=分钟数）。
 *
 * 与续火花 / 主动消息不同，日历通知文案是**纯静态模板**（无 LLM），所以两种触发模式都用「可靠优先」精确闹钟
 * 预登记即可，无需到点现写。
 */
object CalendarNotificationContent {

    /** 取一条随机模板并插值。空标题由调用方先兜底（[R.string.calendar_notif_untitled_event]）。 */
    fun generate(context: Context, eventTitle: String, minutesBefore: Int): String {
        val templates = context.resources.getStringArray(R.array.notif_calendar_templates)
        val template = templates.randomOrNull() ?: return ""
        return formatBody(template, eventTitle, minutesBefore)
    }

    /** 模板插值（`%1$s`=标题，`%2$d`=分钟数）。纯函数，单测从 iOS 真实 zh 文案反推。 */
    internal fun formatBody(template: String, eventTitle: String, minutesBefore: Int): String =
        String.format(template, eventTitle, minutesBefore)
}
