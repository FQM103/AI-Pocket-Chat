package com.situ.aichat.ui.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P14.2b 全天日程日期导航纯函数单测。断言反推 iOS `ScheduleFullDayView`（formattedDate 特判顺序 +
 * latestAvailableDate / canNavigateForward / canNavigateTo 边界）。
 */
class ScheduleNavLogicTest {

    private val day = 1_700_000_000_000L  // 任意「当天 0 点」基准
    private val oneDay = 86_400_000L
    private val today = day
    private val yesterday = day - oneDay
    private val tomorrow = day + oneDay

    // ── relativeDayToken：今天 > 昨天 > 明天 > 其余 ──

    @Test
    fun relativeDayToken_today() {
        assertEquals(ScheduleRelDay.TODAY, ScheduleNavLogic.relativeDayToken(today, today, yesterday, tomorrow))
    }

    @Test
    fun relativeDayToken_yesterday() {
        assertEquals(ScheduleRelDay.YESTERDAY, ScheduleNavLogic.relativeDayToken(yesterday, today, yesterday, tomorrow))
    }

    @Test
    fun relativeDayToken_tomorrow() {
        assertEquals(ScheduleRelDay.TOMORROW, ScheduleNavLogic.relativeDayToken(tomorrow, today, yesterday, tomorrow))
    }

    @Test
    fun relativeDayToken_other_forFarDay() {
        val farPast = day - 5 * oneDay
        assertEquals(ScheduleRelDay.OTHER, ScheduleNavLogic.relativeDayToken(farPast, today, yesterday, tomorrow))
    }

    // ── latestAvailableDay：明天有日程才放行到明天，否则止于今天 ──

    @Test
    fun latestAvailableDay_tomorrowWithSchedule_returnsTomorrow() {
        assertEquals(tomorrow, ScheduleNavLogic.latestAvailableDay(today, tomorrow, hasTomorrowSchedule = true))
    }

    @Test
    fun latestAvailableDay_noTomorrowSchedule_returnsToday() {
        assertEquals(today, ScheduleNavLogic.latestAvailableDay(today, tomorrow, hasTomorrowSchedule = false))
    }

    // ── canNavigateTo：闭区间 [earliest, latest] ──

    @Test
    fun canNavigateTo_withinRange() {
        assertTrue(ScheduleNavLogic.canNavigateTo(today, earliest = yesterday, latest = tomorrow))
    }

    @Test
    fun canNavigateTo_atBoundaries_inclusive() {
        assertTrue(ScheduleNavLogic.canNavigateTo(yesterday, earliest = yesterday, latest = tomorrow))
        assertTrue(ScheduleNavLogic.canNavigateTo(tomorrow, earliest = yesterday, latest = tomorrow))
    }

    @Test
    fun canNavigateTo_outsideRange() {
        assertFalse(ScheduleNavLogic.canNavigateTo(yesterday - oneDay, earliest = yesterday, latest = tomorrow))
        assertFalse(ScheduleNavLogic.canNavigateTo(tomorrow + oneDay, earliest = yesterday, latest = tomorrow))
    }

    // ── canNavigateForward / Backward：严格小于 latest / 严格大于 earliest ──

    @Test
    fun canNavigateForward_strictlyBeforeLatest() {
        assertTrue(ScheduleNavLogic.canNavigateForward(today, latest = tomorrow))
        assertFalse(ScheduleNavLogic.canNavigateForward(tomorrow, latest = tomorrow))   // 已在最晚
        assertFalse(ScheduleNavLogic.canNavigateForward(tomorrow + oneDay, latest = tomorrow))
    }

    @Test
    fun canNavigateBackward_strictlyAfterEarliest() {
        assertTrue(ScheduleNavLogic.canNavigateBackward(today, earliest = yesterday))
        assertFalse(ScheduleNavLogic.canNavigateBackward(yesterday, earliest = yesterday))   // 已在最早
        assertFalse(ScheduleNavLogic.canNavigateBackward(yesterday - oneDay, earliest = yesterday))
    }
}
