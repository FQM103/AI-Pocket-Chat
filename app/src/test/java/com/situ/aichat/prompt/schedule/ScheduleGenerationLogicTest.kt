package com.situ.aichat.prompt.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * 日程生成纯函数单测。**断言从 iOS 真实逻辑反推**（ScheduleGenerationService composeSystemPrompt /
 * validateEvents / makeDate / minutes、+Parsing parseScheduleJSON），抓移植偏差，不依赖真机 / API key
 * （`./gradlew :app:testDebugUnitTest`）。
 */
class ScheduleGenerationLogicTest {

    private val jsonConstraint = "输出严格的 JSON 对象格式 {\"events\":[...]}, 不要包含任何其他文字。"
    private val defaultRole = ScheduleGenerationService.DEFAULT_GENERATION_SYSTEM_PROMPT

    private fun event(
        startHour: Int = 0,
        startMinute: Int = 0,
        endHour: Int = 7,
        endMinute: Int = 30,
        periodLabel: String = "凌晨",
        location: String = "家里",
        activity: String = "睡觉",
    ) = ScheduleEventData(
        startHour = startHour, startMinute = startMinute,
        endHour = endHour, endMinute = endMinute,
        periodLabel = periodLabel, location = location, activity = activity,
    )

    // MARK: - composeSystemPrompt（三层优先级 + extraRules + JSON 硬约束）

    @Test fun composeSystemPrompt_defaultOnly() {
        val result = ScheduleGenerationService.composeSystemPrompt(emptyMap(), "", defaultRole)
        assertEquals("$defaultRole\n$jsonConstraint", result)
    }

    @Test fun composeSystemPrompt_legacyBeatsDefault() {
        val result = ScheduleGenerationService.composeSystemPrompt(emptyMap(), "自定义定位", defaultRole)
        assertEquals("自定义定位\n$jsonConstraint", result)
    }

    @Test fun composeSystemPrompt_overrideBeatsLegacy() {
        val overrides = mapOf("characterRolePart" to "覆盖定位")
        val result = ScheduleGenerationService.composeSystemPrompt(overrides, "自定义定位", defaultRole)
        assertEquals("覆盖定位\n$jsonConstraint", result)
    }

    @Test fun composeSystemPrompt_extraRulesSplitFilteredPrefixedAndJsonLast() {
        // 空行 / 纯空白行过滤；每条 `- ` 前缀；JSON 硬约束恒在最后。
        val overrides = mapOf("extraRules" to "规则A\n\n  \n规则B")
        val result = ScheduleGenerationService.composeSystemPrompt(overrides, "", defaultRole)
        assertEquals("$defaultRole\n- 规则A\n- 规则B\n$jsonConstraint", result)
    }

    // MARK: - validateEvents（过滤 + 排序 + 最少 3 个）

    @Test fun validateEvents_sortsByStartMinute() {
        val events = listOf(
            event(startHour = 9, startMinute = 0, endHour = 12, endMinute = 0, periodLabel = "上午"),
            event(startHour = 0, startMinute = 0, endHour = 7, endMinute = 0, periodLabel = "凌晨"),
            event(startHour = 7, startMinute = 30, endHour = 9, endMinute = 0, periodLabel = "清晨"),
        )
        val sorted = ScheduleGenerationService.validateEvents(events)
        assertEquals(listOf(0, 7, 9), sorted.map { it.startHour })
    }

    @Test fun validateEvents_belowThreeThrowsInsufficient() {
        val ex = assertThrows(ScheduleGenerationException::class.java) {
            ScheduleGenerationService.validateEvents(listOf(event(), event()))
        }
        assertTrue(ex is ScheduleGenerationException.InsufficientEvents)
    }

    @Test fun validateEvents_filtersInvalidThenSorts() {
        val events = listOf(
            event(activity = "  "),                                  // 活动空白 → 剔除
            event(startHour = 24),                                   // 小时越界 → 剔除
            event(startHour = 10, endHour = 9),                      // 结束早于开始 → 剔除
            event(startHour = 0, periodLabel = "凌晨"),               // 有效
            event(startHour = 8, endHour = 11, periodLabel = "上午"), // 有效
            event(startHour = 20, endHour = 22, periodLabel = "晚上"),// 有效
        )
        val valid = ScheduleGenerationService.validateEvents(events)
        assertEquals(3, valid.size)
        assertEquals(listOf(0, 8, 20), valid.map { it.startHour })
    }

    // MARK: - minutes / makeDate

    @Test fun minutes_isHourTimesSixtyPlusMinute() {
        assertEquals(0, ScheduleGenerationService.minutes(0, 0))
        assertEquals(450, ScheduleGenerationService.minutes(7, 30))
        assertEquals(1439, ScheduleGenerationService.minutes(23, 59))
    }

    @Test fun makeDate_addsHourMinuteInZone_shanghaiNoDst() {
        val zone = ZoneId.of("Asia/Shanghai")   // 国行目标，无夏令时 → 线性偏移
        val day = LocalDate.of(2026, 5, 30).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(day, ScheduleGenerationService.makeDate(day, 0, 0, zone))
        assertEquals(day + 7 * 3_600_000L + 30 * 60_000L, ScheduleGenerationService.makeDate(day, 7, 30, zone))
        assertEquals(day + 23 * 3_600_000L + 59 * 60_000L, ScheduleGenerationService.makeDate(day, 23, 59, zone))
    }

    @Test fun makeDate_utc() {
        val zone = ZoneOffset.UTC
        val day = LocalDate.of(2026, 5, 30).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(day + 8 * 3_600_000L + 15 * 60_000L, ScheduleGenerationService.makeDate(day, 8, 15, zone))
    }

    // MARK: - parseScheduleJSON（对象包 / 裸数组 / 代码块+think / 失败）

    private val threeEvents = """
        {"startHour":0,"startMinute":0,"endHour":7,"endMinute":30,"periodLabel":"凌晨","location":"家","activity":"睡觉","isPhoneAvailable":false},
        {"startHour":7,"startMinute":30,"endHour":9,"endMinute":0,"periodLabel":"清晨","location":"家","activity":"煮咖啡"},
        {"startHour":9,"startMinute":0,"endHour":12,"endMinute":0,"periodLabel":"上午","location":"公司","activity":"工作"}
    """.trimIndent()

    @Test fun parseScheduleJSON_objectWrapper() {
        val parsed = ScheduleGenerationService.parseScheduleJSON("""{"events":[$threeEvents]}""")
        assertEquals(3, parsed.size)
        assertEquals("睡觉", parsed.first().activity)
    }

    @Test fun parseScheduleJSON_bareArray() {
        val parsed = ScheduleGenerationService.parseScheduleJSON("[$threeEvents]")
        assertEquals(3, parsed.size)
    }

    @Test fun parseScheduleJSON_codeBlockAndThinkTags() {
        val raw = "<think>先想想日程</think>\n```json\n{\"events\":[$threeEvents]}\n```"
        val parsed = ScheduleGenerationService.parseScheduleJSON(raw)
        assertEquals(3, parsed.size)
    }

    @Test fun parseScheduleJSON_insufficientEventsThrowsInvalidJson() {
        // <3 个事件：iOS 把 validateEvents 的 throw 吞进逐候选 catch，最终统一抛 InvalidJsonResponse。
        val ex = assertThrows(ScheduleGenerationException::class.java) {
            ScheduleGenerationService.parseScheduleJSON("""{"events":[{"startHour":0,"startMinute":0,"endHour":7,"endMinute":0,"periodLabel":"凌晨","location":"家","activity":"睡觉"}]}""")
        }
        assertTrue(ex is ScheduleGenerationException.InvalidJsonResponse)
    }

    @Test fun parseScheduleJSON_garbageThrowsInvalidJson() {
        val ex = assertThrows(ScheduleGenerationException::class.java) {
            ScheduleGenerationService.parseScheduleJSON("这不是 JSON，只是一段普通文字。")
        }
        assertTrue(ex is ScheduleGenerationException.InvalidJsonResponse)
    }
}
