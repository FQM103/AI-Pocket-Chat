package com.situ.aichat.story

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `StoryGenerationParsing` (11.1e-2) LLM 输出 JSON 弹性解析测试，反推 iOS `+Parsing.swift:277-492`：
 * stripThinkingTagsForJSON / cleanBufferForPreview / preprocessJSONText / normalizedJSONCandidates / decodePayload。
 */
class StoryGenerationParsingJsonTest {

    private val P = StoryGenerationParsing

    // ── stripThinkingTagsForJSON ──

    @Test fun strip_thinking_keeps_json_else_returns_original() {
        // 去 think 后仍含 JSON → 返回去标签结果
        assertEquals(
            """{"title":"T"}""",
            P.stripThinkingTagsForJSON("""<think>盘算一下</think>{"title":"T"}"""),
        )
        // 去 think 后为空 → 返回原文（整段可能本就是要保留的）
        assertEquals("<think>只是思考</think>", P.stripThinkingTagsForJSON("<think>只是思考</think>"))
        // 去 think 后不含花括号 → 返回原文
        assertEquals("<think>x</think>没有大括号", P.stripThinkingTagsForJSON("<think>x</think>没有大括号"))
        // 本就无 think 且含 JSON → 原样
        assertEquals("""{"a":1}""", P.stripThinkingTagsForJSON("""{"a":1}"""))
    }

    // ── preprocessJSONText ──

    @Test fun preprocess_strips_zero_width_chars() {
        // BOM(U+FEFF) 开头 + ZWSP(U+200B) + ZWNJ(U+200C) + ZWJ(U+200D) 全去除
        val input = "\uFEFF{\"a\":\u200B\"b\u200C\"}\u200D"
        assertEquals("""{"a":"b"}""", P.preprocessJSONText(input))
    }

    @Test fun preprocess_escapes_real_newlines_inside_string_values() {
        // JSON 串值内有真实换行 → 转义为字面 \n（断言串里 "\\n" = 反斜杠+n）
        val input = "{\"content\":\"第一行\n第二行\"}"
        assertEquals("{\"content\":\"第一行\\n第二行\"}", P.preprocessJSONText(input))

        // CRLF / CR 同样归一为 \n
        val crlf = "{\"content\":\"甲\r\n乙\r丙\"}"
        assertEquals("{\"content\":\"甲\\n乙\\n丙\"}", P.preprocessJSONText(crlf))
    }

    @Test fun preprocess_leaves_clean_values_and_escaped_quotes_untouched() {
        // 无真实换行 → 不改动（含已转义引号 \" 不被截断）
        val clean = "{\"a\":\"x\\\"y\",\"b\":\"普通值\"}"
        assertEquals(clean, P.preprocessJSONText(clean))
    }

    @Test fun preprocess_handles_consecutive_values_colon_and_whitespace() {
        // 回归：旧实现用变长后顾 (?<=:\s*") 在 Android ICU 引擎上编译期即崩溃；
        // 改「捕获组消费分隔符 + 回调重建」后，行为须与旧零宽断言版完全等价。
        // ① 连续多个键值对互不串味：消费结束引号后，下一对仍从其前导冒号正确起匹
        assertEquals(
            "{\"a\":\"x\\n1\",\"b\":\"y\\n2\"}",
            P.preprocessJSONText("{\"a\":\"x\n1\",\"b\":\"y\n2\"}"),
        )
        // ② 值内冒号原样保留，仅转义换行；键名（前导非 `:`）不被误伤
        assertEquals(
            "{\"time\":\"12:00\\n下午\"}",
            P.preprocessJSONText("{\"time\":\"12:00\n下午\"}"),
        )
        // ③ 冒号后多空白被捕获组 1 原样重建（旧版靠 `\s*` 落在后顾里）
        assertEquals(
            "{\"a\":   \"值\\n续\"}",
            P.preprocessJSONText("{\"a\":   \"值\n续\"}"),
        )
    }

    @Test fun preprocess_keeps_dollar_sign_literal_without_group_expansion() {
        // replace(input, transform) 回调返回值按字面插入，值内 `$` 不被当作 $N 组引用展开——
        // 含换行→走转义分支 / 无换行→走原样分支，两路都须字面保留 `$`。
        assertEquals(
            "{\"p\":\"\$5\\n off\"}",
            P.preprocessJSONText("{\"p\":\"\$5\n off\"}"),
        )
        assertEquals(
            "{\"p\":\"\$1.50\"}",
            P.preprocessJSONText("{\"p\":\"\$1.50\"}"),
        )
    }

    // ── normalizedJSONCandidates ──

    @Test fun candidates_empty_for_blank() {
        assertTrue(P.normalizedJSONCandidates("   \n  ").isEmpty())
    }

    @Test fun candidates_include_fence_stripped_and_brace_substring() {
        val fenced = "```json\n{\"title\":\"T\"}\n```"
        val cands = P.normalizedJSONCandidates(fenced)
        // 去围栏候选
        assertTrue(cands.any { it == """{"title":"T"}""" })
        // 取首尾花括号子串候选（从含散文的文本中）
        val prose = "这是结果：{\"title\":\"T\"} 完毕"
        assertTrue(P.normalizedJSONCandidates(prose).any { it == """{"title":"T"}""" })
    }

    @Test fun candidates_deduped_and_order_preserved() {
        // 纯 JSON：原文/花括号子串相同 → 去重后仅 1 个
        val cands = P.normalizedJSONCandidates("""{"title":"T"}""")
        assertEquals(1, cands.size)
        assertEquals("""{"title":"T"}""", cands.first())
    }

    // ── decodePayload ──

    @Test fun decode_clean_json() {
        val p = P.decodePayload("""{"title":"标题","mood":"warm","content":"正文","hasChoice":false}""")
        assertNotNull(p)
        assertEquals("标题", p!!.title)
        assertEquals("warm", p.mood)
        assertFalse(p.hasChoice)
    }

    @Test fun decode_fenced_json() {
        val p = P.decodePayload("```json\n{\"title\":\"T\",\"mood\":\"tense\",\"content\":\"C\",\"hasChoice\":true}\n```")
        assertNotNull(p)
        assertTrue(p!!.hasChoice)
    }

    @Test fun decode_recovers_via_newline_escaping() {
        // 串值内真实换行（标准 JSON 非法）→ 原候选失败，preprocess 候选成功
        val withNl = "{\"title\":\"T\",\"mood\":\"warm\",\"content\":\"行1\n行2\",\"hasChoice\":false}"
        val p = P.decodePayload(withNl)
        assertNotNull(p)
        assertEquals("行1\n行2", p!!.content) // 转义后解码回真实换行
    }

    @Test fun decode_ignores_unknown_keys() {
        val p = P.decodePayload("""{"title":"T","mood":"warm","content":"C","hasChoice":false,"未知字段":"x"}""")
        assertNotNull(p)
        assertEquals("T", p!!.title)
    }

    @Test fun decode_null_for_garbage_or_missing_required() {
        assertNull(P.decodePayload("根本不是 JSON"))
        assertNull(P.decodePayload("")) // 空
        assertNull(P.decodePayload("""{"title":"T","mood":"warm"}""")) // 缺 content/hasChoice
    }

    // ── cleanBufferForPreview ──

    @Test fun preview_cuts_at_metadata_case_insensitive() {
        val (preview, reached) = P.cleanBufferForPreview("正文内容---METADATA---\ntitle: x")
        assertEquals("正文内容", preview)
        assertTrue(reached)

        // 大小写不敏感
        assertEquals("正文", P.cleanBufferForPreview("正文---metadata---后面").first)
    }

    @Test fun preview_no_metadata_keeps_text() {
        val (preview, reached) = P.cleanBufferForPreview("just text")
        assertEquals("just text", preview)
        assertFalse(reached)
    }

    @Test fun preview_strips_think_and_markup_tags() {
        assertEquals("可见", P.cleanBufferForPreview("<think>隐藏</think>可见").first)
        assertEquals("她笑了", P.cleanBufferForPreview("[mood:warm]她笑了[pause:1.5]").first)
        // 未闭合 think 到尾
        assertEquals("前文", P.cleanBufferForPreview("前文<think>被截断的思考").first)
    }

    @Test fun preview_collapses_blank_lines_and_caps_at_200() {
        assertEquals("a\n\nb", P.cleanBufferForPreview("a\n\n\n\nb").first)
        assertEquals(200, P.cleanBufferForPreview("x".repeat(250)).first.length)
    }
}
