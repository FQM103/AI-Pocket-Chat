package com.situ.aichat.prompt.schedule

/**
 * 日程生成的活人感素材包（图纸 2026-07-10 日程专项 C5）：由 [ScheduleLivenessContextCollector] 查库组装、
 * 经 [ScheduleGenerationRequest.liveness] 进 [ScheduleGenerationService.buildPrompt] 渲染成提示词段。
 * **generateOne（今日正式生成）才满配；backfill 恒 null = 精简**（用户拍板⑤）。全字段默认空 = 对应段缺席。
 */
data class ScheduleLivenessContext(
    /** 今天已确认的见面约定（meeting_appointments·confirmed·当日）——【今天的约定】硬锚点第一来源。 */
    val todayMeetings: List<MeetingLine> = emptyList(),
    /** 今天到期的进行中约定（promises·open·dueAt 当日）content——硬锚点第二来源；纯日期 09:00 默认落点不标时刻。 */
    val todayPromises: List<String> = emptyList(),
    /** 未来已定约定 ≤3（open·dueAt≥明天）——【近期已定的约定】背景组，只准期待不准提前排。 */
    val upcomingPromises: List<UpcomingPromise> = emptyList(),
    /** 惦记 ≤3（open_loops·open·已剔除被约定桥接的行）——只进 innerThought。 */
    val openLoops: List<String> = emptyList(),
    /** 48h 内最近一次线下见面（结构化 meeting 行）——余温一行。 */
    val recentMeetingAfterglow: AfterglowLine? = null,
    /** 最近几天粗摘要（D-2..D-5·近在前·每日 ≤3 项非睡眠活动）——反撞车 + 跨日小事件线。 */
    val recentDaysDigest: List<String> = emptyList(),
) {
    /** 一条今日见面约定：[timeText]=精确 HH:mm 或模型原话（rawWhenText 空时为空串——渲染模板自带「今天」前缀·图纸 D-1）。 */
    data class MeetingLine(val timeText: String, val location: String, val activity: String)

    /** 一条未来已定约定：[dueDateText] = 「M月d日」。 */
    data class UpcomingPromise(val content: String, val dueDateText: String)

    /** 余温行：[dayWord] ∈ 昨天/前天；location/activity 可空串（渲染侧省略分句）。 */
    data class AfterglowLine(val dayWord: String, val location: String, val activity: String)
}
