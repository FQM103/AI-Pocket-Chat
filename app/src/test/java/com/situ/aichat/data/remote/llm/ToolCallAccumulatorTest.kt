package com.situ.aichat.data.remote.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ToolCallAccumulator` tests (S2): streamed [ToolCallChunk] fragments merge by index — id/name only on
 * the first fragment, argument fragments concatenate, parallel calls keep separate, completed sorted by
 * index. Reverse-derived from iOS `ToolCallAccumulator`.
 */
class ToolCallAccumulatorTest {

    @Test fun empty_until_first_chunk() {
        val acc = ToolCallAccumulator()
        assertTrue(acc.isEmpty)
        acc.process(ToolCallChunk(index = 0, id = "c1", functionName = "t", argumentChunk = "{}"))
        assertFalse(acc.isEmpty)
    }

    @Test fun concatenates_argument_fragments_keeps_id_and_name_from_first() {
        val acc = ToolCallAccumulator()
        acc.process(ToolCallChunk(0, id = "call_1", functionName = "suggest_offline_meeting", argumentChunk = "{\"loc"))
        acc.process(ToolCallChunk(0, id = null, functionName = null, argumentChunk = "ation\":\"咖啡馆\"}"))

        val calls = acc.completedCalls()
        assertEquals(1, calls.size)
        assertEquals("call_1", calls[0].id)
        assertEquals("suggest_offline_meeting", calls[0].name)
        assertEquals("{\"location\":\"咖啡馆\"}", calls[0].arguments)
    }

    @Test fun parallel_calls_separated_by_index_and_sorted() {
        val acc = ToolCallAccumulator()
        // 乱序到达，但按 index 升序返回
        acc.process(ToolCallChunk(1, id = "b", functionName = "tool_b", argumentChunk = "{\"b\":1}"))
        acc.process(ToolCallChunk(0, id = "a", functionName = "tool_a", argumentChunk = "{\"a\":1}"))
        acc.process(ToolCallChunk(1, argumentChunk = ""))

        val calls = acc.completedCalls()
        assertEquals(listOf("a", "b"), calls.map { it.id })
        assertEquals(listOf("tool_a", "tool_b"), calls.map { it.name })
        assertEquals("{\"a\":1}", calls[0].arguments)
        assertEquals("{\"b\":1}", calls[1].arguments)
    }

    @Test fun later_nonempty_id_name_override_initial_blank() {
        val acc = ToolCallAccumulator()
        // 首片无 id/name（部分中转站把 id 放在第二片）
        acc.process(ToolCallChunk(0, id = null, functionName = null, argumentChunk = "{"))
        acc.process(ToolCallChunk(0, id = "late", functionName = "end_offline_meeting", argumentChunk = "}"))

        val c = acc.completedCalls().single()
        assertEquals("late", c.id)
        assertEquals("end_offline_meeting", c.name)
        assertEquals("{}", c.arguments)
    }

    // ── H1 容错：中转省略 index / 不带 id（治间歇丢调用、下游撞键） ──

    @Test fun missing_index_fragments_stitch_into_one_call() {
        // 中转省略 index：首片带 id，续片无 index 无 id（参数拆片）→ 仍并成同一调用。
        val acc = ToolCallAccumulator()
        acc.process(ToolCallChunk(index = null, id = "rc1", functionName = "calendar_action", argumentChunk = "{\"a"))
        acc.process(ToolCallChunk(index = null, id = null, functionName = null, argumentChunk = "ction\":\"query\"}"))

        val c = acc.completedCalls().single()
        assertEquals("rc1", c.id)
        assertEquals("calendar_action", c.name)
        assertEquals("{\"action\":\"query\"}", c.arguments)
    }

    @Test fun missing_index_two_calls_kept_separate_by_id() {
        // 两个调用都缺 index，但各带不同 id → 不串台，按到达序分列。
        val acc = ToolCallAccumulator()
        acc.process(ToolCallChunk(index = null, id = "x", functionName = "tool_x", argumentChunk = "{\"x\":1}"))
        acc.process(ToolCallChunk(index = null, id = "y", functionName = "tool_y", argumentChunk = "{\"y\":1}"))

        val calls = acc.completedCalls()
        assertEquals(listOf("x", "y"), calls.map { it.id })
        assertEquals(listOf("tool_x", "tool_y"), calls.map { it.name })
    }

    @Test fun blank_id_calls_get_distinct_stable_synthetic_ids() {
        // 个别中转并行调用全不带 id → 完成时各得稳定且互不相同的合成 id，下游按 id 建表不会撞键覆盖。
        val acc = ToolCallAccumulator()
        acc.process(ToolCallChunk(index = 0, id = null, functionName = "tool_a", argumentChunk = "{}"))
        acc.process(ToolCallChunk(index = 1, id = null, functionName = "tool_b", argumentChunk = "{}"))

        val ids = acc.completedCalls().map { it.id }
        assertEquals(listOf("tool_call_0", "tool_call_1"), ids)
        assertEquals(2, ids.toSet().size) // 不撞键
    }
}
