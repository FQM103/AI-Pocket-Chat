package com.situ.aichat.notification

import com.situ.aichat.data.local.entity.ScheduleEventEntity
import com.situ.aichat.data.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Test

/**
 * NotificationScheduleRules 单测（1:1 对齐 iOS `ScheduleEvent.isSleepEvent`：关键词 ["睡","休息","入睡","sleep"]
 * + 深夜 23:00–07:00 且手机不可用；`currentEvent` 闭区间覆盖）。固定 UTC 保证确定性。
 */
class NotificationScheduleRulesTest {

    private val zone: ZoneId = ZoneOffset.UTC

    private fun millis(hour: Int, minute: Int): Long =
        LocalDateTime.of(2026, 1, 15, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun event(
        activity: String,
        startHour: Int = 9,
        endHour: Int = 10,
        isPhoneAvailable: Boolean = true,
    ) = ScheduleEventEntity(
        uuid = "e-$activity-$startHour",
        scheduleUuid = "s1",
        startTime = millis(startHour, 0),
        endTime = millis(endHour, 0),
        activity = activity,
        isPhoneAvailable = isPhoneAvailable,
    )

    // MARK: - isSleepEvent

    @Test fun keywordSleep_isSleepEvenAtNoon() {
        assertTrue(NotificationScheduleRules.isSleepEvent(event("睡觉"), millis(12, 0), zone))
        assertTrue(NotificationScheduleRules.isSleepEvent(event("sleep in"), millis(12, 0), zone))
        assertTrue(NotificationScheduleRules.isSleepEvent(event("午休休息"), millis(12, 0), zone))
    }

    @Test fun englishRest_notAKeyword_matchesIos() {
        // iOS 关键词不含英文 "rest"（只有中文 "休息"）→ 白天手机可用的 "resting" 不算睡眠
        assertFalse(NotificationScheduleRules.isSleepEvent(event("resting"), millis(12, 0), zone))
    }

    @Test fun lateNightUnavailable_isSleep() {
        assertTrue(NotificationScheduleRules.isSleepEvent(event("加班", isPhoneAvailable = false), millis(23, 30), zone))
        assertTrue(NotificationScheduleRules.isSleepEvent(event("加班", isPhoneAvailable = false), millis(3, 0), zone))
    }

    @Test fun lateNightButPhoneAvailable_notSleep() {
        assertFalse(NotificationScheduleRules.isSleepEvent(event("加班", isPhoneAvailable = true), millis(23, 30), zone))
    }

    @Test fun daytimeUnavailable_notSleep() {
        // 07:00 起就不是深夜（iOS hour < 7）
        assertFalse(NotificationScheduleRules.isSleepEvent(event("开会", isPhoneAvailable = false), millis(14, 0), zone))
        assertFalse(NotificationScheduleRules.isSleepEvent(event("开会", isPhoneAvailable = false), millis(7, 0), zone))
    }

    // MARK: - currentEvent（闭区间覆盖）

    @Test fun currentEvent_findsCoveringEventOrNull() {
        val e1 = event("早餐", startHour = 8, endHour = 9)
        val e2 = event("通勤", startHour = 9, endHour = 10)
        val events = listOf(e1, e2)
        assertEquals(e1, NotificationScheduleRules.currentEvent(events, millis(8, 30)))
        assertEquals(e2, NotificationScheduleRules.currentEvent(events, millis(9, 30)))
        assertNull(NotificationScheduleRules.currentEvent(events, millis(7, 0)))
        assertNull(NotificationScheduleRules.currentEvent(events, millis(11, 0)))
    }

    // MARK: - shouldSkipWhileSleeping

    @Test fun shouldSkip_systemDisabled_false() {
        val events = listOf(event("睡觉", startHour = 0, endHour = 23))
        assertFalse(NotificationScheduleRules.shouldSkipWhileSleeping(false, events, millis(2, 0), zone))
    }

    @Test fun shouldSkip_noCoveringEvent_false() {
        val events = listOf(event("工作", startHour = 9, endHour = 18))
        assertFalse(NotificationScheduleRules.shouldSkipWhileSleeping(true, events, millis(22, 0), zone))
    }

    @Test fun shouldSkip_coveringSleepEvent_true() {
        val events = listOf(event("睡觉", startHour = 23, endHour = 23).copy(endTime = millis(23, 59)))
        assertTrue(NotificationScheduleRules.shouldSkipWhileSleeping(true, events, millis(23, 30), zone))
    }

    @Test fun shouldSkip_coveringDaytimeEvent_false() {
        val events = listOf(event("工作", startHour = 9, endHour = 18))
        assertFalse(NotificationScheduleRules.shouldSkipWhileSleeping(true, events, millis(14, 0), zone))
    }

    // MARK: - isInQuietHours（主动通知改造 T1-2；断言从图纸 §3.7 规格独立反推）

    /** 默认窗 23:00→次日 07:30 的跨午夜判定：窗内命中、窗外不命中。 */
    @Test fun quietHours_overnightWindow_matchesInsideOnly() {
        val start = 1380 // 23:00
        val end = 450 // 07:30
        // 窗内：午夜前后两侧
        assertTrue(NotificationScheduleRules.isInQuietHours(23 * 60 + 30, start, end))
        assertTrue(NotificationScheduleRules.isInQuietHours(0, start, end))
        assertTrue(NotificationScheduleRules.isInQuietHours(3 * 60, start, end))
        assertTrue(NotificationScheduleRules.isInQuietHours(7 * 60 + 29, start, end))
        // 窗外：白天与傍晚
        assertFalse(NotificationScheduleRules.isInQuietHours(12 * 60, start, end))
        assertFalse(NotificationScheduleRules.isInQuietHours(22 * 60 + 59, start, end))
    }

    /** E4 边界：恰 start 命中（闭），恰 end 不命中（开）——end 端点须与 morning 候选窗 07:30 起点无缝衔接。 */
    @Test fun quietHours_boundaries_startInclusiveEndExclusive() {
        val start = 1380 // 23:00
        val end = 450 // 07:30
        assertTrue(NotificationScheduleRules.isInQuietHours(1380, start, end))
        assertFalse(NotificationScheduleRules.isInQuietHours(450, start, end))
        assertTrue(NotificationScheduleRules.isInQuietHours(449, start, end))
        assertFalse(NotificationScheduleRules.isInQuietHours(1379, start, end))
    }

    /** 同日窗（start < end，UI 不产出但函数须写全两分支）：同样左闭右开。 */
    @Test fun quietHours_sameDayWindow_closedOpen() {
        val start = 60 // 01:00
        val end = 300 // 05:00
        assertTrue(NotificationScheduleRules.isInQuietHours(60, start, end))
        assertTrue(NotificationScheduleRules.isInQuietHours(299, start, end))
        assertFalse(NotificationScheduleRules.isInQuietHours(300, start, end))
        assertFalse(NotificationScheduleRules.isInQuietHours(59, start, end))
        assertFalse(NotificationScheduleRules.isInQuietHours(23 * 60, start, end))
    }

    /** E20 最窄窗：滑条两极 start=23:30 & end=05:00（5.5 小时）。 */
    @Test fun quietHours_narrowestSliderWindow() {
        val start = 1410 // 23:30
        val end = 300 // 05:00
        assertTrue(NotificationScheduleRules.isInQuietHours(1410, start, end))
        assertTrue(NotificationScheduleRules.isInQuietHours(2 * 60, start, end))
        assertTrue(NotificationScheduleRules.isInQuietHours(299, start, end))
        assertFalse(NotificationScheduleRules.isInQuietHours(1409, start, end)) // 23:29 窗外
        assertFalse(NotificationScheduleRules.isInQuietHours(300, start, end)) // 05:00 窗外
    }

    /** E20 最宽窗：滑条两极 start=20:00 & end=11:00（15 小时）。 */
    @Test fun quietHours_widestSliderWindow() {
        val start = 1200 // 20:00
        val end = 660 // 11:00
        assertTrue(NotificationScheduleRules.isInQuietHours(1200, start, end))
        assertTrue(NotificationScheduleRules.isInQuietHours(23 * 60, start, end))
        assertTrue(NotificationScheduleRules.isInQuietHours(0, start, end))
        assertTrue(NotificationScheduleRules.isInQuietHours(659, start, end))
        assertFalse(NotificationScheduleRules.isInQuietHours(660, start, end)) // 11:00 窗外
        assertFalse(NotificationScheduleRules.isInQuietHours(12 * 60, start, end)) // 正午窗外
    }

    /** E16：免打扰三设置默认值 = 开 + 23:00（1380）→ 07:30（450）。 */
    @Test fun quietHours_settingsDefaults() {
        val defaults = AppSettings()
        assertTrue(defaults.quietHoursEnabled)
        assertEquals(1380, defaults.quietHoursStartMinute)
        assertEquals(450, defaults.quietHoursEndMinute)
        // 默认窗须真的跨午夜（start > end），否则跨午夜分支失效
        assertTrue(defaults.quietHoursStartMinute > defaults.quietHoursEndMinute)
    }

    // MARK: - selectNotificationEvents（C6a 自 DynamicNotificationContentServiceTest 迁入·断言原样保留）

    private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun ms(hour: Int, minute: Int): Long =
        LocalDate.of(2026, 3, 15).atStartOfDay(shanghai)
            .plusHours(hour.toLong()).plusMinutes(minute.toLong())
            .toInstant().toEpochMilli()

    private fun selectEvent(
        uuid: String,
        startHour: Int,
        endHour: Int,
        activity: String,
        phone: Boolean = true,
        mood: String = "",
        inner: String? = null,
        location: String = "",
    ): ScheduleEventEntity = ScheduleEventEntity(
        uuid = uuid,
        scheduleUuid = "s",
        startTime = ms(startHour, 0),
        endTime = ms(endHour, 0),
        activity = activity,
        moodEmoji = mood,
        innerThought = inner,
        isPhoneAvailable = phone,
        location = location,
    )

    @Test fun filtersPhoneOffSleepAndExpired() {
        val now = ms(8, 0) // now-30min = 07:30
        val events = listOf(
            selectEvent("e1", 9, 10, "晨间慢跑训练"),
            selectEvent("sleep", 23, 24, "睡觉休息"), // 睡眠关键词 → 排除
            selectEvent("phoneoff", 14, 15, "开会", phone = false), // 手机不可用 → 排除
            selectEvent("expired", 6, 7, "早饭"), // endTime 07:00 < 07:30 → 排除
            selectEvent("e2", 18, 19, "看电影"),
        )
        val result = NotificationScheduleRules.selectNotificationEvents(events, now)
        assertEquals(listOf("e1", "e2"), result.map { it.uuid })
    }

    @Test fun returnsChronologicalOrderAfterScoring() {
        val now = ms(8, 0)
        val events = listOf(
            selectEvent("e3", 18, 19, "看电影"),
            selectEvent("e1", 9, 10, "晨间慢跑", inner = "今天状态不错"),
            selectEvent("e2", 12, 13, "午饭"),
        )
        val result = NotificationScheduleRules.selectNotificationEvents(events, now)
        // 最终按 startTime 正序：e1(9) → e2(12) → e3(18)
        assertEquals(listOf("e1", "e2", "e3"), result.map { it.uuid })
    }

    @Test fun capsAtFiveEvents() {
        val now = ms(0, 0)
        val events = (1..8).map { selectEvent("e$it", it, it + 1, "活动安排第${it}项") }
        val result = NotificationScheduleRules.selectNotificationEvents(events, now)
        assertEquals(5, result.size)
    }

    @Test fun emptyWhenNoCandidates() {
        val now = ms(8, 0)
        val events = listOf(selectEvent("x", 6, 7, "早饭")) // 已过期
        assertTrue(NotificationScheduleRules.selectNotificationEvents(events, now).isEmpty())
    }
}
