package com.situ.aichat.data.remote.llm

/** A single streamed delta: visible content, thinking-process text, or a tool-call fragment. */
sealed interface StreamToken {
    data class Content(val text: String) : StreamToken
    data class Reasoning(val text: String) : StreamToken

    /** 流式工具调用增量片段（1:1 iOS StreamToken.toolCallDelta）。 */
    data class ToolCallDelta(val chunk: ToolCallChunk) : StreamToken
}

/**
 * 流式工具调用增量片段（1:1 iOS `ToolCallChunk`）。同一调用的多个片段需累积成完整调用
 * （[ToolCallAccumulator]）：[id]/[functionName] 通常仅首片有值，[argumentChunk] 逐片拼接成完整 JSON 参数。
 * [index] 可空——OpenAI/DeepSeek 恒带，部分中转省略；缺失时累积器按 id / 最近调用兜底归并。
 */
data class ToolCallChunk(
    /** 工具调用索引（并行调用的可靠键；中转可能省略，故可空）。 */
    val index: Int? = null,
    /** 调用 ID（仅首片有值）。 */
    val id: String? = null,
    /** 函数名（仅首片有值）。 */
    val functionName: String? = null,
    /** 参数 JSON 增量片段。 */
    val argumentChunk: String? = null,
)

/** 累积完成的工具调用（1:1 iOS completedCalls 元素）。 */
data class CompletedToolCall(val id: String, val name: String, val arguments: String)

/**
 * 工具调用累积器（参照 iOS `ToolCallAccumulator` + RikkaHub 容错）：把流式 [ToolCallChunk] 拼接成完整调用。
 * 非线程安全——单流式协程内顺序 [process]。
 *
 * 归并键策略（治中转省略 index 导致的间歇丢调用）：
 * - 片段带 [ToolCallChunk.index] → 按 index 归并（OpenAI/DeepSeek 并行调用逐片的可靠键，保留原行为·
 *   故意比 RikkaHub 的"并入最近调用"更强：交错到达的并行参数片不会串台）；
 * - 缺 index 但带非空 id → 按 id 找已有调用，否则开一个新键；
 * - 缺 index 且无 id → 并入最近一次触碰的调用（单调用中转把参数拆多片却不带键的常见情形）。
 * 绝不因缺 index 丢片。
 */
class ToolCallAccumulator {
    private class MutableCall(var id: String, var name: String, val arguments: StringBuilder)

    private val calls = LinkedHashMap<Int, MutableCall>()
    private var lastKey: Int? = null

    /** 处理一个增量片段：解析归属键 → 首见建条目；id/name 非空则覆盖；argumentChunk 非 null 则拼接（含空串）。 */
    fun process(chunk: ToolCallChunk) {
        val key = resolveKey(chunk)
        val call = calls.getOrPut(key) {
            MutableCall(chunk.id ?: "", chunk.functionName ?: "", StringBuilder())
        }
        chunk.id?.takeIf { it.isNotEmpty() }?.let { call.id = it }
        chunk.functionName?.takeIf { it.isNotEmpty() }?.let { call.name = it }
        chunk.argumentChunk?.let { call.arguments.append(it) }
        lastKey = key
    }

    /** 解析片段归属的归并键（见类注释策略）。 */
    private fun resolveKey(chunk: ToolCallChunk): Int {
        chunk.index?.let { return it }
        chunk.id?.takeIf { it.isNotEmpty() }?.let { id ->
            calls.entries.firstOrNull { it.value.id == id }?.let { return it.key }
            return nextKey()
        }
        return lastKey ?: nextKey()
    }

    /** 下一个不与现有键冲突的合成键（给缺 index 的新调用用）。 */
    private fun nextKey(): Int = (calls.keys.maxOrNull() ?: -1) + 1

    val isEmpty: Boolean get() = calls.isEmpty()

    /**
     * 按 index 升序返回所有累积完成的调用。空 id 用稳定合成 id 兜底（"tool_call_$index"），
     * 避免下游（如 `fetchToolCallFollowUp` 按 call.id 建表）多个空 id 撞键互相覆盖。
     */
    fun completedCalls(): List<CompletedToolCall> =
        calls.entries.sortedBy { it.key }.map { (index, call) ->
            CompletedToolCall(
                id = call.id.ifEmpty { "tool_call_$index" },
                name = call.name,
                arguments = call.arguments.toString(),
            )
        }
}
