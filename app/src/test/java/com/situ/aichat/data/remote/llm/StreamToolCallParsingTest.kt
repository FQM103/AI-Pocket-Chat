package com.situ.aichat.data.remote.llm

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 响应侧 SSE 块解析健壮性（H1·#4）：部分中转在 tool_calls 增量里**省略 `index`**。
 *
 * 修前 [StreamToolCallDeltaDto.index] 是必填 `Int` → 缺 index 时整个 [StreamChunkDto] 解码抛
 * MissingFieldException，在 `LlmClient.parseSseLine` 被吞成 `Skip` → 该块连带同块的正常 `content`
 * 一起被静默丢弃，回复无声变短（正是被排查的"间歇性小毛病"症状）。修后 index 可空 → 块正常解码、
 * content 与工具调用都保留，由 [ToolCallAccumulator] 兜底归并。
 */
class StreamToolCallParsingTest {

    // 与 NetworkModule.provideJson 同配置。
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
        isLenient = true
    }

    @Test fun tool_call_delta_without_index_still_decodes() {
        // 真实中转可能发出的、tool_calls 元素缺 index 的 SSE data 块。修前此处直接抛异常。
        val payload =
            """{"choices":[{"delta":{"tool_calls":[{"id":"call_1","type":"function","function":{"name":"calendar_action","arguments":"{}"}}]}}]}"""
        val chunk = json.decodeFromString(StreamChunkDto.serializer(), payload)
        val tc = chunk.choices.single().delta.toolCalls?.single()
        assertNotNull(tc)
        assertEquals(null, tc!!.index) // 缺 index → null，不再抛
        assertEquals("call_1", tc.id)
        assertEquals("calendar_action", tc.function?.name)
    }

    @Test fun content_in_same_chunk_survives_when_tool_call_lacks_index() {
        // 关键回归：缺 index 的工具调用不应连累同块的正常 content 被丢弃。
        val payload =
            """{"choices":[{"delta":{"content":"好的","tool_calls":[{"function":{"name":"calendar_action","arguments":"{}"}}]}}]}"""
        val chunk = json.decodeFromString(StreamChunkDto.serializer(), payload)
        val delta = chunk.choices.single().delta
        assertEquals("好的", delta.content) // content 保住
        assertEquals("calendar_action", delta.toolCalls?.single()?.function?.name)
    }
}
