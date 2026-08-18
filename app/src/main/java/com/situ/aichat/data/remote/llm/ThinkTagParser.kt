package com.situ.aichat.data.remote.llm

/**
 * 1:1 port of iOS `ThinkTagParser`（LLMService+ThinkingStream.swift）——有状态的 `<think>`/`<thinking>` 流式标签解析器。
 *
 * 处理 SSE 流中可能被切分的标签边界（例如 `<thi` + `nk>正文</think>`），同时支持多种思维链变体。
 * 思考内容输出为 [StreamToken.Reasoning]（不进可见气泡），正文输出为 [StreamToken.Content]。
 *
 * 非线程安全：每个流单独 new 一个实例，在收集协程内串行调用 [parse]/[flush]。
 */
class ThinkTagParser {

    private val buffer = StringBuilder()
    private var insideThink = false

    /** 解析增量文本，返回已确定的 token 列表（边界不确定的尾部会留在缓冲区，等下次输入）。 */
    fun parse(text: String): List<StreamToken> {
        buffer.append(text)
        val tokens = mutableListOf<StreamToken>()

        while (buffer.isNotEmpty()) {
            if (insideThink) {
                val close = findTag(buffer, CLOSE_TAGS)
                if (close != null) {
                    val thinking = buffer.substring(0, close.first)
                    if (thinking.isNotEmpty()) tokens.add(StreamToken.Reasoning(thinking))
                    buffer.delete(0, close.second)
                    insideThink = false
                } else if (couldContainPartialTag(buffer, CLOSE_TAGS)) {
                    break // 末尾可能是不完整的结束标签，等更多输入
                } else {
                    tokens.add(StreamToken.Reasoning(buffer.toString()))
                    buffer.setLength(0)
                }
            } else {
                val open = findTag(buffer, OPEN_TAGS)
                if (open != null) {
                    val content = buffer.substring(0, open.first)
                    if (content.isNotEmpty()) tokens.add(StreamToken.Content(content))
                    buffer.delete(0, open.second)
                    insideThink = true
                } else if (couldContainPartialTag(buffer, OPEN_TAGS)) {
                    // 末尾可能是不完整的开始标签：先输出确定安全的部分，尾部留待下次
                    val safeEnd = maxOf(0, buffer.length - MAX_TAG_LENGTH)
                    if (safeEnd > 0) {
                        tokens.add(StreamToken.Content(buffer.substring(0, safeEnd)))
                        buffer.delete(0, safeEnd)
                    }
                    break
                } else {
                    tokens.add(StreamToken.Content(buffer.toString()))
                    buffer.setLength(0)
                }
            }
        }
        return tokens
    }

    /** 流结束时清空缓冲区（剩余内容按当前状态归为 thinking 或 content）。 */
    fun flush(): List<StreamToken> {
        if (buffer.isEmpty()) return emptyList()
        val token = if (insideThink) StreamToken.Reasoning(buffer.toString()) else StreamToken.Content(buffer.toString())
        buffer.setLength(0)
        return listOf(token)
    }

    /** 查找第一个匹配标签（大小写不敏感），返回 (起始下标, 结束下标 exclusive)；无匹配返回 null。 */
    private fun findTag(text: CharSequence, tags: List<String>): Pair<Int, Int>? {
        val lower = text.toString().lowercase()
        var earliest: Pair<Int, Int>? = null
        for (tag in tags) {
            val idx = lower.indexOf(tag.lowercase())
            if (idx >= 0 && (earliest == null || idx < earliest.first)) {
                earliest = idx to (idx + tag.length)
            }
        }
        return earliest
    }

    /** 缓冲区末尾是否可能是不完整标签：取末 [MAX_TAG_LENGTH] 字符，找最后一个 `<`，看其后缀是否是某标签的前缀。 */
    private fun couldContainPartialTag(text: CharSequence, tags: List<String>): Boolean {
        val tail = text.substring(maxOf(0, text.length - MAX_TAG_LENGTH))
        val lt = tail.lastIndexOf('<')
        if (lt < 0) return false
        val partial = tail.substring(lt).lowercase()
        return tags.any { it.lowercase().startsWith(partial) }
    }

    private companion object {
        // 覆盖 2025-2026 已知思维链格式（对齐 iOS openTags/closeTags）。
        val OPEN_TAGS = listOf("<think>", "<thinking>", "<|think|>", "<thought>", "<reasoning>")
        val CLOSE_TAGS = listOf("</think>", "</thinking>", "<|/think|>", "</thought>", "</reasoning>")
        val MAX_TAG_LENGTH = "</reasoning>".length // 最长标签 12 字符
    }
}
