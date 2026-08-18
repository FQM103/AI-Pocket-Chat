package com.situ.aichat.story

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `StoryMetadataParser` tests (P11.1c), reverse-derived from iOS `Services/StoryMetadataParser.swift`:
 * 7 分隔符变体(大小写不敏感) / 三级冒号 / 中英字段名归一 / choiceA-D 合并 / hasChoice 智能推断 /
 * mood 归一到 11 合法值(含中文) / parseBool / required+quality 字段 / 无分隔符回退提 mood。
 */
class StoryMetadataParserTest {

    @Test fun empty_input_all_null() {
        val r = StoryMetadataParser.parse("   \n  ")
        assertEquals("", r.content)
        assertNull(r.title)
        assertNull(r.mood)
        assertNull(r.hasChoice)
        assertFalse(r.hasRequiredFields)
    }

    @Test fun no_separator_keeps_content_and_extracts_first_mood() {
        val r = StoryMetadataParser.parse("[mood:warm]从前有座山。[mood:tense]")
        assertEquals("[mood:warm]从前有座山。[mood:tense]", r.content)
        assertEquals("warm", r.mood) // 取第一个 mood 标签
        assertNull(r.title)
        assertNull(r.hasChoice)
    }

    @Test fun no_separator_no_mood_tag() {
        val r = StoryMetadataParser.parse("纯文本无标签")
        assertEquals("纯文本无标签", r.content)
        assertNull(r.mood)
    }

    @Test fun english_metadata_full() {
        val raw = """
            正文内容
            ---METADATA---
            title: 第一章
            mood: warm
            summary: 这是摘要
            hasChoice: true
            choicePrompt: 你决定？
            choiceA: 选项一
            choiceB: 选项二
            isEnding: false
        """.trimIndent()
        val r = StoryMetadataParser.parse(raw)
        assertEquals("正文内容", r.content)
        assertEquals("第一章", r.title)
        assertEquals("warm", r.mood)
        assertEquals("这是摘要", r.summary)
        assertEquals(true, r.hasChoice)
        assertEquals("你决定？", r.choicePrompt)
        assertEquals(listOf("选项一", "选项二"), r.choiceOptions)
        assertEquals(false, r.isEnding)
        assertTrue(r.hasRequiredFields)
        assertTrue(r.hasQualityFields)
    }

    @Test fun chinese_field_names_and_fullwidth_colon() {
        val raw = """
            一段正文
            ---METADATA---
            标题：测试章
            氛围：紧张
            是否结局：是
        """.trimIndent()
        val r = StoryMetadataParser.parse(raw)
        assertEquals("测试章", r.title)
        assertEquals("tense", r.mood) // 紧张 → tense
        assertEquals(true, r.isEnding)
    }

    @Test fun mood_normalization() {
        // 中文映射
        assertEquals("warm", parseMood("温暖"))
        assertEquals("dreamy", parseMood("梦幻"))
        // 大小写归一
        assertEquals("horror", parseMood("HORROR"))
        // 合法英文
        assertEquals("mysterious", parseMood("mysterious"))
        // 非法 → null
        assertNull(parseMood("happy"))
    }

    @Test fun bool_variants() {
        assertEquals(true, parseEndingBool("yes"))
        assertEquals(true, parseEndingBool("是"))
        assertEquals(true, parseEndingBool("1"))
        assertEquals(true, parseEndingBool("TRUE"))
        assertEquals(false, parseEndingBool("否"))
        assertEquals(false, parseEndingBool("no"))
        assertEquals(false, parseEndingBool("0"))
        assertNull(parseEndingBool("maybe"))
    }

    @Test fun has_choice_inferred_from_options_when_field_absent() {
        val raw = """
            正文
            ---METADATA---
            title: 章
            mood: warm
            choiceA: 走左边
            choiceB: 走右边
        """.trimIndent()
        val r = StoryMetadataParser.parse(raw)
        assertEquals(true, r.hasChoice) // 有选项 → 推断 true
    }

    @Test fun has_choice_inferred_false_when_ending_and_no_options() {
        val raw = """
            正文
            ---METADATA---
            title: 终章
            mood: warm
            isEnding: true
        """.trimIndent()
        val r = StoryMetadataParser.parse(raw)
        assertEquals(false, r.hasChoice) // 结局且无选项 → 推断 false
        assertEquals(true, r.isEnding)
    }

    @Test fun explicit_has_choice_wins_over_inference() {
        val raw = """
            正文
            ---METADATA---
            title: 章
            mood: warm
            hasChoice: false
            choiceA: 选项
        """.trimIndent()
        val r = StoryMetadataParser.parse(raw)
        assertEquals(false, r.hasChoice) // 明确 false 优先于「有选项→true」
    }

    @Test fun choice_options_support_numbered_and_underscore_keys() {
        val raw = """
            正文
            ---METADATA---
            title: 章
            mood: warm
            选项1: A
            选项2: B
            choice_c: C
        """.trimIndent()
        val r = StoryMetadataParser.parse(raw)
        assertEquals(listOf("A", "B", "C"), r.choiceOptions)
    }

    @Test fun separator_variants_case_insensitive_and_equals_and_colon() {
        assertEquals("X", StoryMetadataParser.parse("X\n---metadata---\ntitle: 甲").content)
        assertEquals("甲", StoryMetadataParser.parse("X\n---metadata---\ntitle: 甲").title)
        assertEquals("乙", StoryMetadataParser.parse("Y\n===METADATA===\ntitle: 乙").title)
        assertEquals("丙", StoryMetadataParser.parse("Z\nMETADATA:\ntitle: 丙").title)
    }

    @Test fun colon_variants_space_plain_fullwidth() {
        assertEquals("空格", StoryMetadataParser.parse("c\n---METADATA---\ntitle: 空格").title)
        assertEquals("无空格", StoryMetadataParser.parse("c\n---METADATA---\ntitle:无空格").title)
        assertEquals("全角", StoryMetadataParser.parse("c\n---METADATA---\ntitle：全角").title)
    }

    @Test fun separator_at_start_falls_back_content_to_whole_trimmed() {
        val raw = "---METADATA---\ntitle: 标\nmood: warm"
        val r = StoryMetadataParser.parse(raw)
        // contentPart 为空 → content 回退为整段 trimmed
        assertEquals(raw, r.content)
        assertEquals("标", r.title)
    }

    @Test fun missing_quality_field_names() {
        val raw = """
            正文
            ---METADATA---
            title: 章
            mood: warm
        """.trimIndent()
        val r = StoryMetadataParser.parse(raw)
        // summary/currentArc/characterStates/openThreads/hasChoice/isEnding 全缺
        assertEquals(
            listOf("summary", "currentArc", "characterStates", "openThreads", "hasChoice", "isEnding"),
            r.missingQualityFieldNames,
        )
        assertFalse(r.hasQualityFields)
        assertTrue(r.hasRequiredFields) // content+title+mood 齐
    }

    @Test fun build_completion_prompt_lists_only_known_fields() {
        val out = StoryMetadataParser.buildCompletionPrompt(
            storyEndExcerpt = "……结尾摘录。",
            missingFields = listOf("summary", "hasChoice", "unknownField"),
        )
        assertTrue(out.contains("## 故事结尾摘录"))
        assertTrue(out.contains("……结尾摘录。"))
        assertTrue(out.contains("summary: 本章剧情摘要（50-100字）"))
        assertTrue(out.contains("hasChoice: 本章结尾是否需要用户做选择（true 或 false）"))
        assertFalse(out.contains("unknownField")) // 未知字段无描述 → 不输出
    }

    // ── 2026-07-02 加固用例（FABLE5_STORY_REDESIGN_PROPOSAL §7 E3/E6） ──

    @Test fun json_style_metadata_lines_rescued_at_level_one() {
        // JSON 式写法（围栏/花括号/键值引号/行尾逗号）在 0 token 的一级就救回，不再依赖二级补全
        val raw = """
            正文
            ---METADATA---
            ```json
            {
            "title": "第七章",
            "mood": "warm",
            "hasChoice": true,
            }
            ```
        """.trimIndent()
        val r = StoryMetadataParser.parse(raw)
        assertEquals("第七章", r.title)
        assertEquals("warm", r.mood)
        assertEquals(true, r.hasChoice)
    }

    @Test fun choice_field_with_json_array_value_expands() {
        val raw = "正文\n---METADATA---\ntitle: 章\nmood: warm\nchoiceA: [\"走左边\",\"走右边\"]"
        val r = StoryMetadataParser.parse(raw)
        assertEquals(listOf("走左边", "走右边"), r.choiceOptions)
    }

    @Test fun choice_options_field_json_array() {
        val raw = "正文\n---METADATA---\ntitle: 章\nmood: warm\nchoiceOptions: [\"A\",\"B\",\"C\"]"
        val r = StoryMetadataParser.parse(raw)
        assertEquals(listOf("A", "B", "C"), r.choiceOptions)
        assertEquals(true, r.hasChoice)
    }

    @Test fun unquoted_bracket_array_falls_back_to_delimiter_split() {
        val raw = "正文\n---METADATA---\ntitle: 章\nmood: warm\nchoiceA: [拨通电话，假装没看见]"
        val r = StoryMetadataParser.parse(raw)
        assertEquals(listOf("拨通电话", "假装没看见"), r.choiceOptions)
    }

    @Test fun choice_value_wrapping_quotes_stripped() {
        val raw = "正文\n---METADATA---\ntitle: 章\nmood: warm\nchoiceA: \"去追\"\nchoiceB: 留下"
        val r = StoryMetadataParser.parse(raw)
        assertEquals(listOf("去追", "留下"), r.choiceOptions)
    }

    @Test fun choice_options_capped_at_four() {
        val raw = "正文\n---METADATA---\ntitle: 章\nmood: warm\nchoiceOptions: [\"1\",\"2\",\"3\",\"4\",\"5\",\"6\"]"
        val r = StoryMetadataParser.parse(raw)
        assertEquals(listOf("1", "2", "3", "4"), r.choiceOptions)
    }

    @Test fun no_separator_mood_extraction_tolerates_case_and_chinese() {
        assertEquals("warm", StoryMetadataParser.parse("[MOOD:WARM]正文").mood)
        assertEquals("tense", StoryMetadataParser.parse("[mood:紧张]正文").mood)
        assertNull(StoryMetadataParser.parse("[mood:notreal]正文").mood)
    }

    // ── §7 剥离观测点：mood 归一失败记「mood@meta」，默认路径不变 ──

    @Test fun diagnostics_records_unknown_mood_and_stays_empty_on_valid() {
        val bad = mutableListOf<String>()
        val r1 = StoryMetadataParser.parse("正文\n---METADATA---\ntitle: 章\nmood: notreal", bad)
        assertEquals(listOf("mood@meta"), bad) // 归一失败被记（只记标签名，值 notreal 不进）
        assertNull(r1.mood)
        assertTrue("标签值不进日志", bad.none { it.contains("notreal") })

        val good = mutableListOf<String>()
        StoryMetadataParser.parse("正文\n---METADATA---\ntitle: 章\nmood: warm", good)
        assertEquals(emptyList<String>(), good) // 合法 mood 不记
    }

    @Test fun diagnostics_null_metadata_output_unchanged() {
        // 默认 null 路径与收集路径产出同一 ParseResult（字节级零变化）
        val raw = "正文\n---METADATA---\ntitle: 章\nmood: notreal"
        assertEquals(StoryMetadataParser.parse(raw), StoryMetadataParser.parse(raw, mutableListOf()))
    }

    // ── helpers：用解析器公开入口间接验内部归一 ──

    private fun parseMood(value: String): String? =
        StoryMetadataParser.parse("正文\n---METADATA---\nmood: $value").mood

    private fun parseEndingBool(value: String): Boolean? =
        StoryMetadataParser.parse("正文\n---METADATA---\nisEnding: $value").isEnding
}
