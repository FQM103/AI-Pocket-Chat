package com.situ.aichat.util

/**
 * 1:1 port of iOS `JSONExtractor` (Utilities/JSONExtractor.swift)。从 LLM 回复文本中提取 JSON 字符串。
 * 支持三种格式：① ```json ... ``` 代码块；② ``` ... ``` 代码块；③ 第一个 `{` 到最后一个 `}`。
 */
object JSONExtractor {

    fun extract(text: String): String {
        val trimmed = text.trim()

        // ```json ... ``` 代码块
        val jsonFence = trimmed.indexOf("```json")
        if (jsonFence >= 0) {
            val afterFence = jsonFence + "```json".length
            val end = trimmed.indexOf("```", afterFence)
            if (end >= 0) {
                return trimmed.substring(afterFence, end).trim()
            }
        }

        // ``` ... ``` 代码块
        val fence = trimmed.indexOf("```")
        if (fence >= 0) {
            val afterFence = fence + "```".length
            val end = trimmed.indexOf("```", afterFence)
            if (end >= 0) {
                return trimmed.substring(afterFence, end).trim()
            }
        }

        // 第一个 { 到最后一个 }
        val firstBrace = trimmed.indexOf('{')
        val lastBrace = trimmed.lastIndexOf('}')
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1)
        }

        return trimmed
    }
}
