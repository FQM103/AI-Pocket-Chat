package com.situ.aichat.gift

import com.situ.aichat.data.model.MoodHistoryEntry
import com.situ.aichat.data.model.ProactiveGiftTrigger
import com.situ.aichat.data.model.ProactiveGiftTriggerType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * 主动送礼调度器纯函数单测（断言反推 iOS `ProactiveGiftSchedulerTests`）。覆盖 5 类触发检测 + 新角色保护 + 优先级排序
 * + senseLowMood metaId 固定 "current" + missingYou 硬底线/软概率/窗口外 + 幂等 key 格式 + 同日闸门豁免。
 *
 * **走 Robolectric**：[candidateTriggersFrom]/[checkFestival] 内会对全部 16 节日调 `matches`，含 5 个农历用真实
 * `android.icu.ChineseCalendar`（纯 JVM 不可用）。默认时区设上海，与 [FestivalCalendarTest] 一致、避午夜边界。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProactiveGiftSchedulerTest {

    private val shanghai: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")
    private var original: TimeZone = TimeZone.getDefault()

    @Before fun setUp() {
        original = TimeZone.getDefault()
        TimeZone.setDefault(shanghai)
    }

    @After fun tearDown() {
        TimeZone.setDefault(original)
    }

    /** 公历日期（month 1-based）正午上海。 */
    private fun gdate(year: Int, month: Int, day: Int): Long =
        GregorianCalendar(shanghai).apply { clear(); set(year, month - 1, day, 12, 0, 0) }.timeInMillis

    private fun daysAgo(now: Long, n: Int): Long = now - n * DAY_MILLIS

    // ── Birthday ──────────────────────────────────────────────────────

    @Test fun birthday_month_day_match_today() {
        val now = gdate(2026, 3, 15)
        val t = checkBirthday(userBirthday = gdate(1990, 3, 15), now = now)
        assertEquals(ProactiveGiftTriggerType.BIRTHDAY, t?.type)
        assertEquals("用户生日", t?.label)
        assertEquals("user", t?.metaId)
    }

    @Test fun birthday_mismatch_no_trigger() {
        assertNull(checkBirthday(userBirthday = gdate(1990, 3, 16), now = gdate(2026, 3, 15)))
    }

    @Test fun birthday_null_no_trigger() {
        assertNull(checkBirthday(userBirthday = null, now = gdate(2026, 3, 15)))
    }

    // ── Anniversary ───────────────────────────────────────────────────

    @Test fun anniversary_100_days_hits() {
        val now = gdate(2026, 5, 15)
        val t = checkAnniversary(firstMessageDate = daysAgo(now, 100), now = now)
        assertEquals(ProactiveGiftTriggerType.ANNIVERSARY, t?.type)
        assertEquals("相识 100 天", t?.label)
        assertEquals("100d", t?.metaId)
    }

    @Test fun anniversary_365_days_hits() {
        val now = gdate(2026, 5, 15)
        assertEquals("365d", checkAnniversary(daysAgo(now, 365), now)?.metaId)
    }

    @Test fun anniversary_non_milestone_no_trigger() {
        val now = gdate(2026, 5, 15)
        // 50 天不是里程碑（30/100/365/730/1095/1460/1825）
        assertNull(checkAnniversary(daysAgo(now, 50), now))
    }

    @Test fun anniversary_null_first_message_no_trigger() {
        assertNull(checkAnniversary(firstMessageDate = null, now = gdate(2026, 5, 15)))
    }

    // ── Festival ──────────────────────────────────────────────────────

    @Test fun valentines_day_triggers() {
        val t = checkFestival(gdate(2026, 2, 14))
        assertEquals(ProactiveGiftTriggerType.FESTIVAL, t?.type)
        assertEquals("情人节", t?.label)
        assertEquals("valentines_day", t?.metaId)
    }

    @Test fun non_festival_day_no_trigger() {
        assertNull(checkFestival(gdate(2026, 7, 15)))
    }

    // ── SenseLowMood ──────────────────────────────────────────────────

    @Test fun two_red_in_recent_three_triggers() {
        val t = checkSenseLowMood(listOf("red", "red", "yellow"), now = gdate(2026, 5, 15))
        assertEquals(ProactiveGiftTriggerType.SENSE_LOW_MOOD, t?.type)
        assertEquals("current", t?.metaId)
    }

    @Test fun one_red_in_recent_three_no_trigger() {
        assertNull(checkSenseLowMood(listOf("red", "yellow", "green"), now = gdate(2026, 5, 15)))
    }

    @Test fun empty_mood_no_trigger() {
        assertNull(checkSenseLowMood(emptyList(), now = gdate(2026, 5, 15)))
    }

    @Test fun senseLowMood_metaId_fixed_current_regardless_of_redCount() {
        val now = gdate(2026, 5, 15)
        val two = checkSenseLowMood(listOf("red", "red", "yellow"), now)
        val three = checkSenseLowMood(listOf("red", "red", "red"), now)
        assertEquals("current", two?.metaId)
        assertEquals("current", three?.metaId)
    }

    @Test fun only_first_three_mood_count() {
        // 第 4 条 red 不算（只看近 3 条）→ 仅 1 红 → 不触发
        assertNull(checkSenseLowMood(listOf("red", "yellow", "green", "red"), now = gdate(2026, 5, 15)))
    }

    @Test fun recentMoodColors_orders_newest_first() {
        // moodHistory 以 append 序存（最旧在前）；recentMoodColors 须按时间倒序成「最近在前」
        val history = listOf(
            MoodHistoryEntry(timestamp = 100, colorName = "green"),
            MoodHistoryEntry(timestamp = 200, colorName = "red"),
            MoodHistoryEntry(timestamp = 300, colorName = "red"),
        )
        assertEquals(listOf("red", "red", "green"), recentMoodColors(history))
    }

    @Test fun recentMoodColors_then_senseLowMood_uses_recent_not_oldest() {
        val now = gdate(2026, 5, 15)
        // 最旧 3 条全绿、最近 3 条含 2 红 → 必须按「最近」触发（坑3 旧 bug 读最旧 3 全绿 → 不触发）
        val history = listOf(
            MoodHistoryEntry(timestamp = 1, colorName = "green"),
            MoodHistoryEntry(timestamp = 2, colorName = "green"),
            MoodHistoryEntry(timestamp = 3, colorName = "green"),
            MoodHistoryEntry(timestamp = 4, colorName = "red"),
            MoodHistoryEntry(timestamp = 5, colorName = "yellow"),
            MoodHistoryEntry(timestamp = 6, colorName = "red"),
        )
        assertEquals(
            ProactiveGiftTriggerType.SENSE_LOW_MOOD,
            checkSenseLowMood(recentMoodColors(history), now)?.type,
        )
    }

    // ── MissingYou ────────────────────────────────────────────────────

    @Test fun missing_you_15_days_hard() {
        val now = gdate(2026, 5, 15)
        val t = checkMissingYou(creationDate = daysAgo(now, 30), lastProactiveGiftDate = daysAgo(now, 15), now = now, randomValue = null)
        assertEquals(ProactiveGiftTriggerType.MISSING_YOU, t?.type)
        assertEquals("hard", t?.metaId)
    }

    @Test fun missing_you_10_days_soft_when_random_hits() {
        val now = gdate(2026, 5, 15)
        // randomValue 0.01 < 0.05 → soft
        val t = checkMissingYou(daysAgo(now, 30), daysAgo(now, 10), now, randomValue = 0.01)
        assertEquals("soft", t?.metaId)
    }

    @Test fun missing_you_10_days_no_trigger_when_random_misses() {
        val now = gdate(2026, 5, 15)
        // randomValue 0.5 > 0.05 → 不触发
        assertNull(checkMissingYou(daysAgo(now, 30), daysAgo(now, 10), now, randomValue = 0.5))
    }

    @Test fun missing_you_5_days_outside_window() {
        val now = gdate(2026, 5, 15)
        // 即使 randomValue 最小也不触发（5 < 7）
        assertNull(checkMissingYou(daysAgo(now, 30), daysAgo(now, 5), now, randomValue = 0.0))
    }

    @Test fun never_sent_old_character_hard() {
        val now = gdate(2026, 5, 15)
        // lastProactiveGiftDate = null + 角色 30 天老 → 硬触发
        assertEquals("hard", checkMissingYou(daysAgo(now, 30), lastProactiveGiftDate = null, now = now, randomValue = null)?.metaId)
    }

    @Test fun new_character_within_3_days_no_missing_you() {
        val now = gdate(2026, 5, 15)
        // 创建 2 天 → 保护期内，即使 randomValue 最小也不触发
        assertNull(checkMissingYou(daysAgo(now, 2), lastProactiveGiftDate = null, now = now, randomValue = 0.0))
    }

    // ── 组合：优先级 + 新角色仍可触发时机型 ──────────────────────────────

    @Test fun new_character_still_triggers_birthday() {
        val now = gdate(2026, 3, 15)
        val triggers = candidateTriggersFrom(
            userBirthday = gdate(1990, 3, 15),
            firstMessageDate = null,
            creationDate = daysAgo(now, 1), // 1 天新角色
            moodColors = emptyList(),
            lastProactiveGiftDate = null,
            now = now,
            randomValue = 0.0,
        )
        assertTrue(triggers.any { it.type == ProactiveGiftTriggerType.BIRTHDAY })
        assertFalse(triggers.any { it.type == ProactiveGiftTriggerType.MISSING_YOU })
    }

    @Test fun multi_candidate_sorted_by_priority_desc() {
        val now = gdate(2026, 2, 14) // 情人节
        val triggers = candidateTriggersFrom(
            userBirthday = gdate(1990, 2, 14),
            firstMessageDate = null,
            creationDate = daysAgo(now, 30),
            moodColors = emptyList(),
            lastProactiveGiftDate = daysAgo(now, 20), // 硬触发 missingYou
            now = now,
            randomValue = null,
        )
        // 至少 birthday(100) / festival(60) / missingYou(20)
        assertTrue(triggers.size >= 3)
        assertEquals(ProactiveGiftTriggerType.BIRTHDAY, triggers[0].type)
        val priorities = triggers.map { it.type.priority }
        assertEquals(priorities.sortedDescending(), priorities)
    }

    // ── 幂等 key 格式 ─────────────────────────────────────────────────

    @Test fun related_entity_key_format() {
        val trigger = ProactiveGiftTrigger(ProactiveGiftTriggerType.FESTIVAL, "情人节", "valentines_day", gdate(2026, 2, 14))
        assertEquals("proactive_gift_ABC123_20260214_festival_valentines_day", trigger.relatedEntityKey("ABC123"))
    }

    @Test fun related_entity_key_empty_meta_uses_dash() {
        val trigger = ProactiveGiftTrigger(ProactiveGiftTriggerType.MISSING_YOU, "想你", "", gdate(2026, 5, 1))
        assertEquals("proactive_gift_X_20260501_missing_you_-", trigger.relatedEntityKey("X"))
    }

    @Test fun priority_numbers() {
        assertEquals(100, ProactiveGiftTriggerType.BIRTHDAY.priority)
        assertEquals(80, ProactiveGiftTriggerType.ANNIVERSARY.priority)
        assertEquals(60, ProactiveGiftTriggerType.FESTIVAL.priority)
        assertEquals(40, ProactiveGiftTriggerType.SENSE_LOW_MOOD.priority)
        assertEquals(20, ProactiveGiftTriggerType.MISSING_YOU.priority)
    }

    // ── 同日闸门豁免 ──────────────────────────────────────────────────

    @Test fun high_priority_triggers_bypass_daily_gate() {
        assertTrue(ProactiveGiftScheduler.shouldBypassDailyGate(ProactiveGiftTriggerType.BIRTHDAY))
        assertTrue(ProactiveGiftScheduler.shouldBypassDailyGate(ProactiveGiftTriggerType.ANNIVERSARY))
        assertTrue(ProactiveGiftScheduler.shouldBypassDailyGate(ProactiveGiftTriggerType.FESTIVAL))
    }

    @Test fun low_priority_triggers_do_not_bypass_daily_gate() {
        assertFalse(ProactiveGiftScheduler.shouldBypassDailyGate(ProactiveGiftTriggerType.SENSE_LOW_MOOD))
        assertFalse(ProactiveGiftScheduler.shouldBypassDailyGate(ProactiveGiftTriggerType.MISSING_YOU))
    }

    @Test fun day_of_week_in_month_calendar_field_sanity() {
        // 防御：确保 GregorianCalendar 的 DAY_OF_WEEK_IN_MONTH 语义未变（节日母亲节/父亲节依赖它，间接由 festival 覆盖）
        val cal = GregorianCalendar(shanghai).apply { timeInMillis = gdate(2026, 5, 15) }
        assertTrue(cal.get(Calendar.DAY_OF_WEEK_IN_MONTH) in 1..5)
    }
}
