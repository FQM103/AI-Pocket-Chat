package com.situ.aichat.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日程提示词纯函数单测。**断言从 iOS 真实逻辑反推**（PromptBuilder+Schedule.swift：timeOfDayLabel
 * 时段边界、状态标签、ScheduleEvent.isSleepEvent），抓边界/移植偏差，不依赖真机。
 */
class PromptBuilderScheduleTest {

    // MARK: - timeOfDayLabel（iOS：5..<9 清晨 / 9..<12 上午 / 12..<14 中午 / 14..<18 下午 / 18..<22 晚上 / else 深夜）

    @Test fun timeOfDayLabel_boundaries() {
        assertEquals("深夜", scheduleTimeOfDayLabel(0))
        assertEquals("深夜", scheduleTimeOfDayLabel(4))
        assertEquals("清晨", scheduleTimeOfDayLabel(5))
        assertEquals("清晨", scheduleTimeOfDayLabel(8))
        assertEquals("上午", scheduleTimeOfDayLabel(9))
        assertEquals("上午", scheduleTimeOfDayLabel(11))
        assertEquals("中午", scheduleTimeOfDayLabel(12))
        assertEquals("中午", scheduleTimeOfDayLabel(13))
        assertEquals("下午", scheduleTimeOfDayLabel(14))
        assertEquals("下午", scheduleTimeOfDayLabel(17))
        assertEquals("晚上", scheduleTimeOfDayLabel(18))
        assertEquals("晚上", scheduleTimeOfDayLabel(21))
        assertEquals("深夜", scheduleTimeOfDayLabel(22))
        assertEquals("深夜", scheduleTimeOfDayLabel(23))
    }

    // MARK: - 状态标签（end<now=已发生 / start<=now<=end=正在 / start>now=未来）

    @Test fun statusTag_past() {
        assertEquals("[✓已发生]", scheduleEventStatusTag(startMillis = 100, endMillis = 200, nowMillis = 300))
    }

    @Test fun statusTag_currentIncludingBoundaries() {
        assertEquals("[▶️正在]", scheduleEventStatusTag(100, 300, 200))
        assertEquals("[▶️正在]", scheduleEventStatusTag(100, 300, 100)) // now == start
        assertEquals("[▶️正在]", scheduleEventStatusTag(100, 300, 300)) // now == end
    }

    @Test fun statusTag_future() {
        assertEquals("[⏳未来·尚未发生]", scheduleEventStatusTag(startMillis = 400, endMillis = 500, nowMillis = 300))
    }

    // MARK: - isSleepEvent（关键词 睡/休息/入睡/sleep；或 深夜 23-7 点且手机不可用）

    @Test fun isSleepEvent_keywordAlwaysTrue() {
        assertTrue(scheduleIsSleepEvent("睡觉", isPhoneAvailable = true, hour = 14))
        assertTrue(scheduleIsSleepEvent("休息一下", isPhoneAvailable = true, hour = 10))
        assertTrue(scheduleIsSleepEvent("准备入睡", isPhoneAvailable = true, hour = 23))
        assertTrue(scheduleIsSleepEvent("Sleep in", isPhoneAvailable = true, hour = 9)) // 小写后含 sleep
    }

    @Test fun isSleepEvent_deepNightNoPhone() {
        assertTrue(scheduleIsSleepEvent("发呆", isPhoneAvailable = false, hour = 2))   // 深夜 + 手机不可用
        assertTrue(scheduleIsSleepEvent("发呆", isPhoneAvailable = false, hour = 23))
    }

    @Test fun isSleepEvent_falseCases() {
        assertFalse(scheduleIsSleepEvent("工作", isPhoneAvailable = true, hour = 14))  // 白天无关键词
        assertFalse(scheduleIsSleepEvent("工作", isPhoneAvailable = true, hour = 2))   // 深夜但手机可用
        assertFalse(scheduleIsSleepEvent("发呆", isPhoneAvailable = false, hour = 14)) // 白天 + 无关键词（手机不可用也不算睡）
    }
}
