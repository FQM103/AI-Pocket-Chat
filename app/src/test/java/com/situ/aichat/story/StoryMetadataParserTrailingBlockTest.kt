package com.situ.aichat.story

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 尾部元数据块识别（图纸一 C2 第 1 层 · `docs/handoff/2026-07-31-故事解析保命与事故兜底.md` §3.1）测试。
 *
 * 断言从图纸 §3.1 的算法规格独立反推（自末行回溯 / 只认 directMap 英文键 / 空行与疑似分隔符行可吸纳 /
 * 至少一个字段行才命中 / 切完没正文即放弃），**不照抄实现输出**；覆盖图纸 §5 的 E1–E8 与
 * `rawMetadataText` 三态（分隔符路 = 分隔符之后整段 / 尾部路 = 尾块 / 未命中 = null）。
 */
class StoryMetadataParserTrailingBlockTest {

    private fun parse(text: String, diagnostics: MutableList<String>? = null) =
        StoryMetadataParser.parse(text, diagnostics)

    // ── 误伤边界组（E1–E4）：这些都**不许**命中，否则用户当场少半章 ──

    /** E1 · 对话冒号行：「司徒」不是白名单字段名，扫描立即终止。 */
    @Test fun e1_dialogue_colon_line_not_recognized() {
        val raw = "他推开门。\n司徒：你确定。"
        val result = parse(raw)
        assertEquals(raw, result.content)
        assertNull(result.rawMetadataText)
        assertNull(result.title)
    }

    /** E2 · 正文中段的字段词：自末行回溯先遇正文行即终止，永远扫不到它。 */
    @Test fun e2_field_line_in_middle_of_body_not_recognized() {
        val raw = "正文第一段。\ntitle: 假标题\n正文最后一段。"
        val result = parse(raw)
        assertEquals(raw, result.content)
        assertNull(result.rawMetadataText)
        assertNull(result.title)
    }

    /** E3 · 非白名单英文键（PS / 注）不命中。 */
    @Test fun e3_non_whitelisted_key_not_recognized() {
        val ps = "正文。\nPS: 后记随笔。"
        assertEquals(ps, parse(ps).content)
        assertNull(parse(ps).rawMetadataText)

        val note = "正文。\n注: 本章完。"
        assertEquals(note, parse(note).content)
        assertNull(parse(note).rawMetadataText)
    }

    /** E4 · 中文键有意不进白名单（图纸 §0.3-1）：「标题：」「摘要：」在正文里是常用词，不认。 */
    @Test fun e4_chinese_keys_not_in_whitelist() {
        val raw = "正文。\n标题：第七章\n摘要：他终于开口。"
        val result = parse(raw)
        assertEquals(raw, result.content)
        assertNull(result.rawMetadataText)
        assertNull(result.title)
    }

    // ── 命中组（E5–E7） ──

    /** E5 · 无分隔符但尾部是连续英文字段行 → 整块切走，必填字段齐备（无需任何 LLM 调用）。 */
    @Test fun e5_trailing_english_field_lines_recognized() {
        val body = "他终于开口，说了那句压了十年的话。"
        val raw = "$body\ntitle: 第七章\nmood: tense\nsummary: 旧事重提，两人不欢而散。"
        val diagnostics = mutableListOf<String>()
        val result = parse(raw, diagnostics)

        assertEquals(body, result.content)
        assertEquals("第七章", result.title)
        assertEquals("tense", result.mood)
        assertEquals("旧事重提，两人不欢而散。", result.summary)
        assertTrue("必填字段应已齐备", result.hasRequiredFields)
        assertEquals(
            "title: 第七章\nmood: tense\nsummary: 旧事重提，两人不欢而散。",
            result.rawMetadataText,
        )
        assertTrue("应记下 trailingMeta@parse 观测点", diagnostics.contains("trailingMeta@parse"))
    }

    /** E6 · 块内夹空行、行首列表符、块顶疑似分隔符行——三类都可吸纳，整块切走。 */
    @Test fun e6_blank_lines_list_markers_and_separator_like_line_absorbed() {
        val body = "夜里下了一场雨。"
        val raw = "$body\n\n--- META DATA ---\n- title: 第七章\n\nmood: tense"
        val result = parse(raw)

        assertEquals(body, result.content)
        assertEquals("第七章", result.title)
        assertEquals("tense", result.mood)
        assertEquals("--- META DATA ---\n- title: 第七章\n\nmood: tense", result.rawMetadataText)
    }

    /**
     * E6b · `--METADATA--` 近似分隔符行（图纸 E6 列的原始形态）走的是**既有裸 METADATA 分隔符路**
     * ——`splitByMetadataSeparator` 的第 7 变体先命中，尾部识别根本轮不到（施工日志 D-1）。
     * 元数据字段照样解析得到，正文侧残留分隔符前缀由渲染层 stripTrailingMetadata 收拾
     * （见 `StoryTextSanitizerTest.strips_double_dash_metadata_separator_residue`）。
     */
    @Test fun e6b_double_dash_metadata_goes_through_separator_path() {
        val result = parse("夜里下了一场雨。\n--METADATA--\ntitle: 第七章\nmood: tense")

        assertEquals("第七章", result.title)
        assertEquals("tense", result.mood)
        assertTrue("正文侧保留分隔符前缀属既有分隔符路行为", result.content.startsWith("夜里下了一场雨。"))
        assertEquals("--\ntitle: 第七章\nmood: tense", result.rawMetadataText)
    }

    /** E7 · 尾块缺 title/mood：仍切块（正文救回来了），但必填字段不齐 → 交给第三级用 rawMetadataText 补。 */
    @Test fun e7_trailing_block_without_title_and_mood_still_split() {
        val body = "她把信折好，放进抽屉最深处。"
        val raw = "$body\nsummary: 她收起了那封信。\nhasChoice: true"
        val result = parse(raw)

        assertEquals(body, result.content)
        assertNull(result.title)
        assertNull(result.mood)
        assertFalse("缺 title/mood ⇒ 必填字段不齐", result.hasRequiredFields)
        assertEquals(true, result.hasChoice)
        assertEquals("summary: 她收起了那封信。\nhasChoice: true", result.rawMetadataText)
    }

    /** E8 · 整篇全是字段行：切完没正文 → 保险闸放弃切分，维持现状让上层守卫处理。 */
    @Test fun e8_all_field_lines_gives_up_split() {
        val raw = "title: 第七章\nmood: tense"
        val result = parse(raw)

        assertEquals(raw, result.content)
        assertNull(result.rawMetadataText)
        assertNull(result.title)
    }

    // ── rawMetadataText 三态 ──

    /** 三态之一：分隔符路填「分隔符之后的整段」。 */
    @Test fun rawMetadataText_separator_path_holds_text_after_delimiter() {
        val result = parse("正文段。\n---METADATA---\ntitle: 第七章\nmood: tense")
        assertEquals("\ntitle: 第七章\nmood: tense", result.rawMetadataText)
    }

    /** 三态之二：尾部识别路填「尾块」。 */
    @Test fun rawMetadataText_trailing_path_holds_the_block() {
        val result = parse("正文段。\ntitle: 第七章\nmood: tense")
        assertEquals("title: 第七章\nmood: tense", result.rawMetadataText)
    }

    /** 三态之三：两路都未命中恒 null（空输入亦然）。 */
    @Test fun rawMetadataText_null_when_no_metadata_found() {
        assertNull(parse("只有正文，没有任何元数据。").rawMetadataText)
        assertNull(parse("   ").rawMetadataText)
    }

    // ── 既有分隔符路回归（图纸 §2.3-1：一级/二级路径字节级不变） ──

    /** 分隔符路的既有产物不受尾部识别影响，且不会误记 trailingMeta 观测点。 */
    @Test fun separator_path_unchanged_and_no_trailing_diagnostic() {
        val diagnostics = mutableListOf<String>()
        val result = parse(
            "正文段落。\n---METADATA---\ntitle: 第七章\nmood: tense\nsummary: 摘要\nhasChoice: false",
            diagnostics,
        )

        assertEquals("正文段落。", result.content)
        assertEquals("第七章", result.title)
        assertEquals("tense", result.mood)
        assertEquals("摘要", result.summary)
        assertEquals(false, result.hasChoice)
        assertFalse("分隔符路不该记尾部识别观测点", diagnostics.contains("trailingMeta@parse"))
    }
}
