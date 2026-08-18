package com.situ.aichat.story

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `StoryTextSanitizer` 测试，反推 iOS `StoryTextSanitizer.sanitize`（`StoryReaderAnimatedBlocks.swift:339-353`）：
 * 删除残留方括号标签（`[x:y]`/`[x]`/`[/x]`）、连续 3 换行收成 2、首尾去空白；数字开头的非标签保留。
 */
class StoryTextSanitizerTest {

    @Test fun removes_value_tag() {
        assertEquals("hello  world", StoryTextSanitizer.sanitize("hello [mood:warm] world"))
    }

    @Test fun removes_paired_text_tags() {
        assertEquals("secret", StoryTextSanitizer.sanitize("[text:whisper]secret[/text]"))
    }

    @Test fun removes_bare_and_closing_tags() {
        assertEquals("", StoryTextSanitizer.sanitize("[pause]"))
        assertEquals("", StoryTextSanitizer.sanitize("[/text]"))
    }

    @Test fun collapses_triple_newline_to_double() {
        assertEquals("a\n\nb", StoryTextSanitizer.sanitize("a\n\n\nb"))
    }

    @Test fun trims_leading_and_trailing_whitespace() {
        assertEquals("hi", StoryTextSanitizer.sanitize("  \n hi \n  "))
    }

    @Test fun keeps_non_tag_bracket_starting_with_digit() {
        assertEquals("[123]", StoryTextSanitizer.sanitize("[123]"))
    }

    @Test fun value_tag_with_chinese_value_removed() {
        assertEquals("到了", StoryTextSanitizer.sanitize("[scene:黄昏的码头]到了"))
    }

    @Test fun plain_text_unchanged() {
        assertEquals("没有标签的正文。", StoryTextSanitizer.sanitize("没有标签的正文。"))
    }

    // ── 图纸一 C2 第 2 层：尾部元数据残渣剥离（显示层，DB 零写回） ──

    /**
     * E13 回归钉之一 · 纯正文（多段、含空行）：串接尾部剥离后输出与之前逐字节不变。
     */
    @Test fun regression_plain_multi_paragraph_body_byte_identical() {
        val raw = "第一段落，他推开门。\n\n第二段落，屋里没有人。"
        assertEquals(raw, StoryTextSanitizer.sanitize(raw))
    }

    /**
     * E13 回归钉之二 · 含方括号标签 + 三连换行：剥标签、压换行、去首尾空白，顺序与结果不变。
     */
    @Test fun regression_tagged_body_with_triple_newline_byte_identical() {
        assertEquals(
            "他走进来。\n\n她抬起头。",
            StoryTextSanitizer.sanitize("[scene:黄昏的走廊]他走进来。\n\n\n她抬起头。[mood:warm]\n"),
        )
    }

    /**
     * E13 回归钉之三 · 含对话冒号行（最怕被误剥的形态）：一字不动。
     */
    @Test fun regression_body_with_dialogue_colon_lines_byte_identical() {
        val raw = "他转过身。\n司徒：你确定。\n她点了点头。"
        assertEquals(raw, StoryTextSanitizer.sanitize(raw))
    }

    /** E14 · 历史坏章：分隔符 + 字段行残渣在显示层剥净（库里内容不动，本函数只管显示）。 */
    @Test fun strips_separator_and_trailing_fields_from_legacy_chapter() {
        assertEquals(
            "正文最后一句。",
            StoryTextSanitizer.sanitize("正文最后一句。\n---METADATA---\ntitle: 第七章\nmood: tense"),
        )
    }

    /** E14b · `--METADATA--` 近似分隔符行落在尾块里，同样剥净（疑似分隔符行可吸纳）。 */
    @Test fun strips_double_dash_metadata_separator_residue() {
        assertEquals(
            "正文最后一句。",
            StoryTextSanitizer.sanitize("正文最后一句。\n--METADATA--\ntitle: 第七章"),
        )
    }

    /** E14c · 无分隔符、只有尾部字段行的残渣，也剥净。 */
    @Test fun strips_bare_trailing_field_lines() {
        assertEquals(
            "正文最后一句。",
            StoryTextSanitizer.sanitize("正文最后一句。\ntitle: 第七章\nmood: tense\nsummary: 摘要"),
        )
    }

    /** E15 · 正文中段出现裸 METADATA 词、其后仍是正文：保守闸不满足 → 一个字都不剥。 */
    @Test fun keeps_body_when_metadata_word_appears_mid_text() {
        val raw = "「你说的 METADATA 到底是什么？」他问。\n她没有回答。"
        assertEquals(raw, StoryTextSanitizer.sanitize(raw))
    }

    /** E16 · 渲染单块语义（阅读器逐块调用）：中间块末尾的对话冒号行不误剥。 */
    @Test fun keeps_middle_block_ending_with_dialogue_colon_line() {
        val raw = "屋里安静得能听见钟摆。\n夏晴子：我等你很久了。"
        assertEquals(raw, StoryTextSanitizer.sanitize(raw))
    }

    /** 中文键残渣有意不剥（图纸 §0.3-1 / P-3 一致性豁免）：与解析层同一份白名单。 */
    @Test fun keeps_chinese_key_residue_by_design() {
        val raw = "正文最后一句。\n标题：第七章\n摘要：他终于开口。"
        assertEquals(raw, StoryTextSanitizer.sanitize(raw))
    }
}
