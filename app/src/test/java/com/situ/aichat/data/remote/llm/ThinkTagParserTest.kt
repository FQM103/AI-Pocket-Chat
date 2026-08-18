package com.situ.aichat.data.remote.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H3#1 测试网 · ThinkTagParser（SSE 流式思考标签状态机）。
 * 规格：思考内容→Reasoning 不进可见气泡、正文→Content；标签可被 chunk 任意切分（开/闭、任意位置）；
 * 五种变体+大小写不敏感；未闭合标签 flush 时按当前态归类；尾部疑似不完整开标签先吐安全前缀、
 * 留 MAX_TAG_LENGTH 尾巴等下一段。
 */
class ThinkTagParserTest {

    private fun collectAll(chunks: List<String>): List<StreamToken> {
        val p = ThinkTagParser()
        val out = mutableListOf<StreamToken>()
        for (c in chunks) out += p.parse(c)
        out += p.flush()
        return out
    }

    private fun reasoningText(tokens: List<StreamToken>) =
        tokens.filterIsInstance<StreamToken.Reasoning>().joinToString("") { it.text }

    private fun contentText(tokens: List<StreamToken>) =
        tokens.filterIsInstance<StreamToken.Content>().joinToString("") { it.text }

    @Test
    fun singleChunk_thinkBlockSeparated() {
        val t = collectAll(listOf("<think>推理中</think>你好呀"))
        assertEquals("推理中", reasoningText(t))
        assertEquals("你好呀", contentText(t))
    }

    @Test
    fun tagSplitAcrossChunks_openTagBoundary() {
        // 开标签被切成 "<thi" + "nk>"——状态机必须缝合，不把残片当正文吐出。
        val t = collectAll(listOf("<thi", "nk>内部</think>正文"))
        assertEquals("内部", reasoningText(t))
        assertEquals("正文", contentText(t))
    }

    @Test
    fun tagSplitAcrossChunks_closeTagBoundary() {
        val t = collectAll(listOf("<think>思考</thi", "nk>回答"))
        assertEquals("思考", reasoningText(t))
        assertEquals("回答", contentText(t))
    }

    @Test
    fun everyCharStreamedSeparately_stillCorrect() {
        // 极端：逐字符流入（SSE 最碎情形）。
        val t = collectAll("<thinking>abc</thinking>def".map { it.toString() })
        assertEquals("abc", reasoningText(t))
        assertEquals("def", contentText(t))
    }

    @Test
    fun caseInsensitive_andAllVariants() {
        val cases = listOf(
            "<THINK>x</THINK>y" to "x",
            "<Thinking>x</Thinking>y" to "x",
            "<|think|>x<|/think|>y" to "x",
            "<thought>x</thought>y" to "x",
            "<reasoning>x</reasoning>y" to "x",
        )
        for ((input, expectReasoning) in cases) {
            val t = collectAll(listOf(input))
            assertEquals("input=$input", expectReasoning, reasoningText(t))
            assertEquals("input=$input", "y", contentText(t))
        }
    }

    @Test
    fun unclosedThink_flushClassifiesAsReasoning() {
        // 未闭合 → 剩余按思考态归类（绝不让 CoT 漏进气泡）。
        val t = collectAll(listOf("<think>永远没有闭合的推理"))
        assertEquals("永远没有闭合的推理", reasoningText(t))
        assertEquals("", contentText(t))
    }

    @Test
    fun plainContentNoTags_passthrough() {
        val t = collectAll(listOf("纯正文，", "没有任何标签"))
        assertEquals("", reasoningText(t))
        assertEquals("纯正文，没有任何标签", contentText(t))
    }

    @Test
    fun trailingPartialOpenTag_safePrefixEmittedEagerly() {
        // 第一段尾部 "<thi" 疑似开标签：MAX_TAG_LENGTH(12) 之前的安全前缀应当场吐出
        // （流式低延迟），尾巴留缓冲；第二段证明它确实是标签。
        val p = ThinkTagParser()
        val first = p.parse("这是一大段足够长的正文内容<thi")
        assertTrue(first.isNotEmpty())
        // 17 字符缓冲 − 最长标签 12 → 前 5 字符是确定安全的，立即吐出。
        assertEquals("这是一大段", (first[0] as StreamToken.Content).text)
        val rest = p.parse("nk>秘密</think>好")
        val all = first + rest + p.flush()
        assertEquals("秘密", reasoningText(all))
        assertEquals("这是一大段足够长的正文内容好", contentText(all))
    }

    @Test
    fun lessThanSign_inPlainMath_notSwallowed() {
        // "<" 后接非标签字符 → 不是标签前缀，整段照常输出。
        val t = collectAll(listOf("1 < 2 而且 3 > 2"))
        assertEquals("1 < 2 而且 3 > 2", contentText(t))
    }

    @Test
    fun multipleThinkBlocks_interleaved() {
        val t = collectAll(listOf("<think>a</think>一<think>b</think>二"))
        assertEquals("ab", reasoningText(t))
        assertEquals("一二", contentText(t))
    }

    @Test
    fun flushOnEmptyBuffer_returnsNothing() {
        assertEquals(emptyList<StreamToken>(), ThinkTagParser().flush())
    }
}
