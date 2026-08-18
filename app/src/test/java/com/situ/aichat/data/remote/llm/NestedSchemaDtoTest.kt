package com.situ.aichat.data.remote.llm

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase A（④ 嵌套表单 schema）能力证明：[ParameterPropertyDto] 升递归后——
 * - A-1：最小递归往返（嵌套对象 / 对象数组）序列化-反序列化身份不变 + JSON 结构正确嵌套。
 * - A-2：构造「带对象数组 + 嵌套对象」的样例工具，序列化出的 JSON 符合 OpenAI function schema。
 * - 扁平不变：扁平 [ParameterPropertyDto] 绝不写出 items/properties/required 键（与 0-2 golden 同向看门）。
 *
 * 线材 Json 与 [com.situ.aichat.di.NetworkModule.provideJson] 同配置（explicitNulls=false / encodeDefaults=false）。
 * 当前 3 工具无一用嵌套——这是「就绪但休眠」的前置基建，本测证明能力可用。
 */
class NestedSchemaDtoTest {

    private val wireJson = Json {
        ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false; isLenient = true
    }

    // ── A-1 最小递归往返 ──

    @Test fun nested_object_and_object_array_round_trip_identity() {
        val schema = ParameterPropertyDto(
            type = "object",
            description = "一个人",
            properties = linkedMapOf(
                "name" to ParameterPropertyDto("string", "名字"),
                "tags" to ParameterPropertyDto(
                    type = "array",
                    description = "标签",
                    items = ParameterPropertyDto("string"),
                ),
                "address" to ParameterPropertyDto(
                    type = "object",
                    properties = linkedMapOf(
                        "city" to ParameterPropertyDto("string"),
                        "zip" to ParameterPropertyDto("string"),
                    ),
                    required = listOf("city"),
                ),
            ),
            required = listOf("name"),
        )

        val json = wireJson.encodeToString(ParameterPropertyDto.serializer(), schema)
        val back = wireJson.decodeFromString(ParameterPropertyDto.serializer(), json)
        assertEquals("递归 DTO 往返必须身份不变", schema, back)

        // JSON 结构正确嵌套（items / properties / required）
        assertTrue(json.contains("\"items\":{\"type\":\"string\"}"))
        assertTrue(json.contains("\"properties\":{\"city\":{\"type\":\"string\"},\"zip\":{\"type\":\"string\"}}"))
        assertTrue(json.contains("\"required\":[\"city\"]"))
        assertTrue(json.contains("\"required\":[\"name\"]"))
    }

    // ── A-2 能力证明：对象数组 + 嵌套对象的样例工具符合 OpenAI function schema ──

    @Test fun sample_tool_with_object_array_serializes_as_valid_openai_schema() {
        val tool = ToolDefinitionDto(
            type = "function",
            function = FunctionDefinitionDto(
                name = "demo_plan_trip",
                description = "demo",
                parameters = FunctionParametersDto(
                    type = "object",
                    properties = linkedMapOf(
                        "stops" to ParameterPropertyDto(
                            type = "array",
                            description = "途经点",
                            items = ParameterPropertyDto(
                                type = "object",
                                properties = linkedMapOf(
                                    "place" to ParameterPropertyDto("string", "地点"),
                                    "minute" to ParameterPropertyDto("integer", "停留分钟"),
                                ),
                                required = listOf("place"),
                            ),
                        ),
                        "owner" to ParameterPropertyDto(
                            type = "object",
                            properties = linkedMapOf("name" to ParameterPropertyDto("string")),
                        ),
                    ),
                    required = listOf("stops"),
                ),
            ),
        )

        val json = wireJson.encodeToString(ToolDefinitionDto.serializer(), tool)

        // 对象数组：stops.type=array → items.type=object → items.properties.{place,minute} + items.required
        assertTrue(json.contains("\"stops\":{\"type\":\"array\",\"description\":\"途经点\",\"items\":{\"type\":\"object\""))
        assertTrue(json.contains("\"place\":{\"type\":\"string\",\"description\":\"地点\"}"))
        assertTrue(json.contains("\"minute\":{\"type\":\"integer\",\"description\":\"停留分钟\"}"))
        assertTrue(json.contains("\"required\":[\"place\"]"))
        // 嵌套对象：owner.type=object → owner.properties.name
        assertTrue(json.contains("\"owner\":{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}"))
        // 顶层 required
        assertTrue(json.contains("\"required\":[\"stops\"]"))

        // 往返身份不变（证明能解回来）
        assertEquals(tool, wireJson.decodeFromString(ToolDefinitionDto.serializer(), json))
    }

    // ── 扁平不变：扁平属性绝不写出新键（与 0-2 golden 同向） ──

    @Test fun flat_property_omits_new_recursive_keys() {
        val flat = ParameterPropertyDto("string", "Action type", listOf("a", "b"))
        val json = wireJson.encodeToString(ParameterPropertyDto.serializer(), flat)
        assertEquals("{\"type\":\"string\",\"description\":\"Action type\",\"enum\":[\"a\",\"b\"]}", json)
        assertFalse(json.contains("items"))
        assertFalse(json.contains("properties"))
        assertFalse(json.contains("required"))
    }
}
