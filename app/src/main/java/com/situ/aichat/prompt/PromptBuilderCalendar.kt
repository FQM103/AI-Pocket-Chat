package com.situ.aichat.prompt

import com.situ.aichat.data.calendar.CalendarAction

/**
 * iOS `buildCalendarAwarenessContent`(PromptBuilder+Modules) 的「事件感知 + 写入」子集（P5.3a/P5.3b）的
 * **装配侧薄适配**。
 *
 * 提示词正文（感知段 + `[CALENDAR_ACTION]` 写入教程）已搬到 [CalendarAction.buildAwarenessPrompt]（①·与 schema /
 * `calendarActionRegex` co-located，治「提示词在 PromptBuilder、schema 在 CalendarAction」漂移）；此处仅留
 * `BuildContext → 纯参数` 的薄适配，由 [PromptBuilder] 的 USER_CALENDAR 感知模块（macroProducers）调用。
 */
internal fun buildCalendarAwarenessContent(ctx: PromptBuilder.BuildContext): String =
    CalendarAction.buildAwarenessPrompt(
        calendarIntegrationEnabled = ctx.appSettings.calendarIntegrationEnabled,
        upcomingEvents = ctx.calendarUpcomingEvents,
        userName = ctx.resolvedUserName,
        toolCallingEnabled = ctx.toolCallingEnabled,
    )
