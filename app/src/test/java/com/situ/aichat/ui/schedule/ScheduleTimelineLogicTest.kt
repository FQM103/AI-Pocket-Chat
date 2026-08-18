package com.situ.aichat.ui.schedule

import com.situ.aichat.data.local.entity.ScheduleEventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P14.2 日程 UI 纯函数单测。断言**从 iOS `ScheduleTimelineCard` / `ScheduleFullDayView` /
 * `ScheduleEventRow` 真实行为反推**（非照搬自身实现），覆盖排序键序陷阱、suffix(3)、三档过滤、
 * 时态边界、天气降级、同行角色文案。
 */
class ScheduleTimelineLogicTest {

    private fun event(
        uuid: String,
        start: Long,
        end: Long,
        sortOrder: Int = 0,
        type: String = "planned",
    ) = ScheduleEventEntity(
        uuid = uuid,
        scheduleUuid = "sched",
        startTime = start,
        endTime = end,
        sortOrder = sortOrder,
        eventTypeRaw = type,
    )

    // ── sortedEvents：startTime 主键、sortOrder tiebreak（与 DAO 入库序相反）──

    @Test
    fun sortedEvents_ordersByStartTimePrimary_notSortOrder() {
        // userInteraction 漏传 sortOrder=0 但 startTime 晚 → 应排在 startTime 早的正常事件之后（iOS 2026-04-24 加固）。
        val late = event("late", start = 200, end = 300, sortOrder = 0, type = "userInteraction")
        val early = event("early", start = 100, end = 150, sortOrder = 5)
        val sorted = ScheduleTimelineLogic.sortedEvents(listOf(late, early))
        assertEquals(listOf("early", "late"), sorted.map { it.uuid })
    }

    @Test
    fun sortedEvents_sameStartTime_breaksBySortOrderAscending() {
        val a = event("a", start = 100, end = 200, sortOrder = 2)
        val b = event("b", start = 100, end = 200, sortOrder = 1)
        val sorted = ScheduleTimelineLogic.sortedEvents(listOf(a, b))
        assertEquals(listOf("b", "a"), sorted.map { it.uuid })
    }

    // ── selectedEvents（今日卡）：仅已开始、最多 3 件取末尾 ──

    @Test
    fun selectedEvents_takesLastThreeOfStarted() {
        val now = 1_000L
        val events = (1..5).map { event("e$it", start = it * 100L, end = it * 100L + 50, sortOrder = it) }
        // 全部 startTime(100..500) <= now(1000) → 已开始 5 件 → 取末 3（按 startTime 序最近的 3）。
        val selected = ScheduleTimelineLogic.selectedEvents(events, now)
        assertEquals(listOf("e3", "e4", "e5"), selected.map { it.uuid })
    }

    @Test
    fun selectedEvents_threeOrFewer_returnsAll() {
        val now = 1_000L
        val events = listOf(
            event("a", start = 100, end = 200),
            event("b", start = 300, end = 400),
        )
        assertEquals(listOf("a", "b"), ScheduleTimelineLogic.selectedEvents(events, now).map { it.uuid })
    }

    @Test
    fun selectedEvents_excludesNotYetStarted() {
        val now = 250L
        val events = listOf(
            event("past", start = 100, end = 150),
            event("current", start = 200, end = 300),
            event("future", start = 400, end = 500),
        )
        // 只保留 startTime <= 250 的 past + current；future(400) 被剔除。
        assertEquals(listOf("past", "current"), ScheduleTimelineLogic.selectedEvents(events, now).map { it.uuid })
    }

    @Test
    fun selectedEvents_empty_whenNoneStarted() {
        val now = 50L
        val events = listOf(event("future", start = 100, end = 200))
        assertEquals(emptyList<String>(), ScheduleTimelineLogic.selectedEvents(events, now).map { it.uuid })
    }

    // ── timelineTimeState：边界敏感（先判 PAST）──

    @Test
    fun timelineTimeState_endAtNow_isPast() {
        val e = event("e", start = 100, end = 200)
        assertEquals(TimeState.PAST, ScheduleTimelineLogic.timelineTimeState(e, now = 200))
    }

    @Test
    fun timelineTimeState_startAtNow_isCurrent() {
        val e = event("e", start = 200, end = 300)
        assertEquals(TimeState.CURRENT, ScheduleTimelineLogic.timelineTimeState(e, now = 200))
    }

    @Test
    fun timelineTimeState_beforeStart_isFuture() {
        val e = event("e", start = 300, end = 400)
        assertEquals(TimeState.FUTURE, ScheduleTimelineLogic.timelineTimeState(e, now = 200))
    }

    // ── visibleEvents（全天）：过去日全 / 未来日空 / 今天仅已开始 ──

    private val day = 1_000_000L      // 任意「某天 0 点」基准
    private val oneDay = 86_400_000L

    @Test
    fun visibleEvents_pastDay_showsAll() {
        val events = listOf(
            event("a", start = day + 500, end = day + 600),
            event("b", start = day + 100, end = day + 200),
        )
        val visible = ScheduleTimelineLogic.visibleEvents(
            events, selectedDayStart = day, todayStart = day + oneDay, now = day + oneDay + 1,
        )
        // 过去日全部展示，且按 startTime 排序。
        assertEquals(listOf("b", "a"), visible.map { it.uuid })
    }

    @Test
    fun visibleEvents_futureDay_isEmpty() {
        val events = listOf(event("a", start = day + 100, end = day + 200))
        val visible = ScheduleTimelineLogic.visibleEvents(
            events, selectedDayStart = day + oneDay, todayStart = day, now = day + 50,
        )
        assertEquals(emptyList<String>(), visible.map { it.uuid })
    }

    @Test
    fun visibleEvents_today_onlyStarted() {
        val now = day + 250
        val events = listOf(
            event("past", start = day + 100, end = day + 150),
            event("future", start = day + 400, end = day + 500),
        )
        val visible = ScheduleTimelineLogic.visibleEvents(
            events, selectedDayStart = day, todayStart = day, now = now,
        )
        assertEquals(listOf("past"), visible.map { it.uuid })
    }

    // ── fullDayTimeState：历史日整日 PAST / 未来日整日 FUTURE / 今天细分 ──

    @Test
    fun fullDayTimeState_pastDay_alwaysPast_evenIfEventLooksFuture() {
        val e = event("e", start = day + 999, end = day + 9999)
        val state = ScheduleTimelineLogic.fullDayTimeState(
            e, selectedDayStart = day, todayStart = day + oneDay, now = day + oneDay,
        )
        assertEquals(TimeState.PAST, state)
    }

    @Test
    fun fullDayTimeState_futureDay_alwaysFuture() {
        val e = event("e", start = day + 1, end = day + 2)
        val state = ScheduleTimelineLogic.fullDayTimeState(
            e, selectedDayStart = day + oneDay, todayStart = day, now = day + 50,
        )
        assertEquals(TimeState.FUTURE, state)
    }

    @Test
    fun fullDayTimeState_today_delegatesToTimeline() {
        val e = event("e", start = day + 100, end = day + 300)
        val state = ScheduleTimelineLogic.fullDayTimeState(
            e, selectedDayStart = day, todayStart = day, now = day + 200,
        )
        assertEquals(TimeState.CURRENT, state)
    }

    // ── compactWeatherLabel：三路降级 ──

    @Test
    fun compactWeatherLabel_full_joinsCityAndWeather() {
        assertEquals("北京 ☀️晴", ScheduleTimelineLogic.compactWeatherLabel("北京", "☀️", "晴"))
    }

    @Test
    fun compactWeatherLabel_weatherWithoutCity_dropsCity() {
        assertEquals("☀️晴", ScheduleTimelineLogic.compactWeatherLabel(null, "☀️", "晴"))
    }

    @Test
    fun compactWeatherLabel_cityOnly_whenNoCondition() {
        assertEquals("上海", ScheduleTimelineLogic.compactWeatherLabel("上海", null, null))
    }

    @Test
    fun compactWeatherLabel_allNull_returnsNull() {
        assertNull(ScheduleTimelineLogic.compactWeatherLabel(null, null, null))
        // 当前安卓天气列恒 null、城市恒 null（P11）→ 标签整段不渲染。
        assertNull(ScheduleTimelineLogic.compactWeatherLabel(null, "☀️", null))
    }

    // ── activityText：同行角色名 ──

    @Test
    fun activityText_withRelatedNames_wrapsWithQuote() {
        assertEquals("和「小明」看电影", ScheduleTimelineLogic.activityText("看电影", "小明"))
    }

    @Test
    fun activityText_blankRelatedNames_plainActivity() {
        assertEquals("看电影", ScheduleTimelineLogic.activityText("看电影", "  "))
        assertEquals("看电影", ScheduleTimelineLogic.activityText("看电影", null))
    }
}
