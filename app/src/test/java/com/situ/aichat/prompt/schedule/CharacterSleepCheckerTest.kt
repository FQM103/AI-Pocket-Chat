package com.situ.aichat.prompt.schedule

import com.situ.aichat.data.local.entity.ScheduleEventEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.time.Instant
import java.time.ZoneId
import org.junit.Test

/**
 * 1:1 校验 [CharacterSleepChecker.isSleepingFromEvents]（对齐 iOS `MomentGenerationService.isCharacterSleeping`）。
 * 覆盖：无日程深夜兜底 / 当前事件睡眠关键词 / 当前事件手机不可用 / 当前事件正常 / 间隙看最近结束事件 / 间隙深夜兜底。
 */
class CharacterSleepCheckerTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    // UTC 下：epoch 对应小时。
    private val noon = 12L * 3600 * 1000      // hour 12（非深夜）
    private val deepNight = 2L * 3600 * 1000  // hour 2（深夜 23–7）

    private fun event(activity: String, phone: Boolean, start: Long, end: Long) = ScheduleEventEntity(
        uuid = "e", scheduleUuid = "s", startTime = start, endTime = end,
        activity = activity, isPhoneAvailable = phone,
    )

    @Test fun `no schedule row — deep-night fallback`() {
        assertTrue(CharacterSleepChecker.isSleepingFromEvents(null, deepNight, zone))   // 23–7 视为睡
        assertFalse(CharacterSleepChecker.isSleepingFromEvents(null, noon, zone))       // 白天不睡
    }

    @Test fun `empty schedule (row exists, no events) — deep-night fallback`() {
        assertTrue(CharacterSleepChecker.isSleepingFromEvents(emptyList(), deepNight, zone))
        assertFalse(CharacterSleepChecker.isSleepingFromEvents(emptyList(), noon, zone))
    }

    @Test fun `current event with sleep keyword — sleeping regardless of hour`() {
        val events = listOf(event("午睡", phone = true, start = noon - 1000, end = noon + 1000))
        assertTrue(CharacterSleepChecker.isSleepingFromEvents(events, noon, zone))
    }

    @Test fun `current event phone unavailable — sleeping (busy)`() {
        val events = listOf(event("开会", phone = false, start = noon - 1000, end = noon + 1000))
        assertTrue(CharacterSleepChecker.isSleepingFromEvents(events, noon, zone))
    }

    @Test fun `current event normal and reachable — not sleeping`() {
        val events = listOf(event("工作", phone = true, start = noon - 1000, end = noon + 1000))
        assertFalse(CharacterSleepChecker.isSleepingFromEvents(events, noon, zone))
    }

    @Test fun `gap — last past event was sleep → still sleeping`() {
        val events = listOf(event("睡觉", phone = true, start = noon - 5000, end = noon - 1000))
        assertTrue(CharacterSleepChecker.isSleepingFromEvents(events, noon, zone))
    }

    @Test fun `gap daytime — last past event normal → not sleeping`() {
        val events = listOf(event("工作", phone = true, start = noon - 5000, end = noon - 1000))
        assertFalse(CharacterSleepChecker.isSleepingFromEvents(events, noon, zone))
    }

    @Test fun `gap deep night — fallback sleeping even with normal past event`() {
        val events = listOf(event("工作", phone = true, start = deepNight - 5000, end = deepNight - 1000))
        assertTrue(CharacterSleepChecker.isSleepingFromEvents(events, deepNight, zone))
    }
}
