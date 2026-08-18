package com.situ.aichat.economy

import com.situ.aichat.economy.ScheduleEconomicEventExtractor.computeAmount
import com.situ.aichat.economy.ScheduleEconomicEventExtractor.composeNote
import com.situ.aichat.economy.ScheduleEconomicEventExtractor.containsAny
import com.situ.aichat.economy.ScheduleEconomicEventExtractor.isFreeScenario
import com.situ.aichat.economy.ScheduleEconomicEventExtractor.matchCategory
import com.situ.aichat.economy.ScheduleEconomicEventExtractor.occupationBias
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日程派生消费提取器纯函数单测（断言反推 iOS 词典/优先级/公式）。金额因 stableRate 哈希不断言具体值，
 * 改断言 minAmount 兜底（salary 0 确定）+ 各乘数单调性 + 职业 bias 精确值 + 免费/类别布尔逻辑 + note 格式。
 */
class ScheduleEconomicExtractorTest {

    // ── isFreeScenario：消费 location 优先于免费 activity ──
    @Test fun free_home_dining_is_free() = assertTrue(isFreeScenario(location = "在家", activity = "吃饭"))

    @Test fun free_office_meeting_is_free() = assertTrue(isFreeScenario(location = "公司", activity = "开会"))

    @Test fun free_activity_sleep_is_free() = assertTrue(isFreeScenario(location = "", activity = "睡觉"))

    @Test fun consumption_location_beats_free_activity() =
        assertFalse(isFreeScenario(location = "海底捞", activity = "工作谈事")) // 餐厅 location 命中 → 不免费

    @Test fun restaurant_with_work_activity_not_free() =
        assertFalse(isFreeScenario(location = "餐厅", activity = "上班"))

    // ── matchCategory：location 优先于 activity ──
    @Test fun hospital_location_beats_dining_activity() =
        assertEquals(ScheduleEconomicCategory.MEDICAL, matchCategory(location = "医院", activity = "吃饭"))

    @Test fun activity_movie_matches_entertainment() =
        assertEquals(ScheduleEconomicCategory.ENTERTAINMENT, matchCategory(location = "", activity = "看电影"))

    @Test fun ktv_case_insensitive() =
        assertEquals(ScheduleEconomicCategory.ENTERTAINMENT, matchCategory(location = "", activity = "去 KTV 唱歌"))

    @Test fun starbucks_location_drinks() =
        assertEquals(ScheduleEconomicCategory.DRINKS, matchCategory(location = "星巴克", activity = ""))

    @Test fun unknown_returns_null() = assertNull(matchCategory(location = "某处", activity = "发呆思考"))

    // ── containsAny 大小写无关 ──
    @Test fun contains_any_lowercases_keyword() {
        assertTrue(containsAny("去ktv".lowercase(), listOf("KTV")))
        assertTrue(containsAny("shopping mall".lowercase(), listOf("shopping")))
    }

    // ── occupationBias 精确值 ──
    @Test fun occupation_bias_values() {
        assertEquals(0.7, occupationBias("某大学学生"), 0.0)
        assertEquals(1.3, occupationBias("某公司CEO"), 0.0)
        assertEquals(1.0, occupationBias("程序员"), 0.0)
        assertEquals(1.0, occupationBias(""), 0.0)
    }

    // ── computeAmount：minAmount 兜底（salary 0 → minAmount，确定） ──
    @Test fun amount_floors_to_min_for_zero_salary() {
        assertEquals(5, computeAmount(ScheduleEconomicCategory.DINING, 0, false, false, false, "", "evt"))
        assertEquals(30, computeAmount(ScheduleEconomicCategory.MEDICAL, 0, false, false, false, "", "evt"))
        assertEquals(3, computeAmount(ScheduleEconomicCategory.DRINKS, 0, false, false, false, "", "evt"))
    }

    // ── computeAmount：各乘数单调性（同 eventId 同 rate，大月薪不触底） ──
    @Test fun weekend_increases_amount() {
        val base = computeAmount(ScheduleEconomicCategory.MEDICAL, 100000, false, false, false, "", "evt")
        val weekend = computeAmount(ScheduleEconomicCategory.MEDICAL, 100000, true, false, false, "", "evt")
        assertTrue("weekend $weekend >= base $base", weekend >= base)
    }

    @Test fun companion_increases_dining() {
        val solo = computeAmount(ScheduleEconomicCategory.DINING, 100000, false, false, false, "", "evt")
        val withFriend = computeAmount(ScheduleEconomicCategory.DINING, 100000, false, true, false, "", "evt")
        assertTrue("companion $withFriend >= solo $solo", withFriend >= solo)
    }

    @Test fun companion_does_not_affect_shopping() {
        // 同行 ×1.5 仅 dining/entertainment，对 shopping 无效
        val solo = computeAmount(ScheduleEconomicCategory.SHOPPING, 100000, false, false, false, "", "evt")
        val withFriend = computeAmount(ScheduleEconomicCategory.SHOPPING, 100000, false, true, false, "", "evt")
        assertEquals(solo, withFriend)
    }

    @Test fun brand_increases_amount() {
        val plain = computeAmount(ScheduleEconomicCategory.DINING, 100000, false, false, false, "", "evt")
        val brand = computeAmount(ScheduleEconomicCategory.DINING, 100000, false, false, true, "", "evt")
        assertTrue("brand $brand >= plain $plain", brand >= plain)
    }

    @Test fun student_bias_reduces_amount() {
        val normal = computeAmount(ScheduleEconomicCategory.MEDICAL, 100000, false, false, false, "程序员", "evt")
        val student = computeAmount(ScheduleEconomicCategory.MEDICAL, 100000, false, false, false, "大学学生", "evt")
        assertTrue("student $student <= normal $normal", student <= normal)
    }

    // ── composeNote ──
    @Test fun note_with_location() = assertEquals("🍲 餐饮 · 海底捞", composeNote(ScheduleEconomicCategory.DINING, "海底捞", "聚餐"))

    @Test fun note_falls_back_to_activity() = assertEquals("🍲 餐饮 · 聚餐", composeNote(ScheduleEconomicCategory.DINING, "", "聚餐"))

    @Test fun note_no_detail() = assertEquals("🍲 餐饮", composeNote(ScheduleEconomicCategory.DINING, "", ""))

    @Test fun note_truncates_long_detail() {
        val loc = "消".repeat(25)
        assertEquals("🍲 餐饮 · " + "消".repeat(18) + "…", composeNote(ScheduleEconomicCategory.DINING, loc, ""))
    }

    // ── scheduleEventKey ──
    @Test fun event_key_format() = assertEquals("schedule_event_abc-123", scheduleEventKey("abc-123"))
}
