package com.situ.aichat.offline

import com.situ.aichat.data.calendar.CalendarAction
import com.situ.aichat.data.calendar.CalendarActionType
import com.situ.aichat.data.remote.llm.ToolDefinitionDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structured tool-definition schema tests (S1) — `OfflineMeetingAction.toolDefinitions` /
 * `CalendarAction.toolDefinitions`, reverse-derived from iOS `OfflineMeetingActionTests` +
 * `CalendarActionTests`, plus a wire-serialization check (explicitNulls/encodeDefaults = production).
 */
class OfflineToolDefinitionsTest {

    // 与 NetworkModule.provideJson 同配置：null 字段省略、默认值不编码。
    private val wireJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }

    private fun tool(defs: List<ToolDefinitionDto>, name: String) = defs.first { it.function.name == name }

    // ── OfflineMeetingAction.toolDefinitions ──

    @Test fun offline_exposes_suggest_and_end_tools() {
        val defs = OfflineMeetingAction.toolDefinitions
        assertEquals(2, defs.size)

        val suggest = tool(defs, "suggest_offline_meeting")
        assertEquals("function", suggest.type)
        assertEquals(
            listOf("activity", "hidden_tension", "invitation", "location", "tension_hint"),
            suggest.function.parameters.required?.sorted(),
        )
        for (key in listOf("location", "activity", "invitation", "hidden_tension", "tension_hint")) {
            assertEquals("string", suggest.function.parameters.properties[key]?.type)
        }

        val end = tool(defs, "end_offline_meeting")
        assertEquals("function", end.type)
        assertEquals(listOf("final_mood"), end.function.parameters.required)
        assertEquals("string", end.function.parameters.properties["final_mood"]?.type)
        assertEquals(
            listOf("warm", "sweet", "melancholic", "awkward", "neutral"),
            end.function.parameters.properties["final_mood"]?.enumValues,
        )
        // farewell 不再是工具参数（iOS 已废弃）
        assertNull(end.function.parameters.properties["farewell"])
    }

    @Test fun offline_canInitiate_false_drops_suggest_keeps_end() {
        val defs = OfflineMeetingAction.toolDefinitions(canInitiate = false)
        assertEquals(1, defs.size)
        assertEquals("end_offline_meeting", defs[0].function.name)
        // 默认 / canInitiate=true 仍是完整两件套
        assertEquals(2, OfflineMeetingAction.toolDefinitions(canInitiate = true).size)
    }

    // ── CalendarAction.toolDefinitions ──

    @Test fun calendar_exposes_single_action_tool_with_full_enum() {
        val defs = CalendarAction.toolDefinitions
        assertEquals(1, defs.size)
        val t = defs[0]
        assertEquals("function", t.type)
        assertEquals("calendar_action", t.function.name)
        assertEquals(listOf("action", "title"), t.function.parameters.required)
        // action 枚举 = 全部 CalendarActionType raw
        assertEquals(
            CalendarActionType.entries.map { it.raw },
            t.function.parameters.properties["action"]?.enumValues,
        )
    }

    // ── wire serialization (null omission / enum key / type present) ──

    @Test fun serialization_omits_nulls_and_keeps_enum_and_type() {
        val json = wireJson.encodeToString(ListSerializer(ToolDefinitionDto.serializer()), OfflineMeetingAction.toolDefinitions)

        assertTrue(json.contains("\"type\":\"function\""))
        assertTrue(json.contains("\"type\":\"object\""))
        assertTrue(json.contains("\"name\":\"suggest_offline_meeting\""))
        assertTrue(json.contains("\"name\":\"end_offline_meeting\""))
        // enum 仅在有值的属性上出现
        assertTrue(json.contains("\"enum\":[\"warm\",\"sweet\",\"melancholic\",\"awkward\",\"neutral\"]"))
        // null 字段省略：不出现裸 null（required/enum/description 为 null 时不应写出）
        assertFalse(json.contains(":null"))
        assertFalse(json.contains("\"enum\":null"))
        assertFalse(json.contains("\"required\":null"))
    }
}
