package com.situ.aichat.story

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `StoryContentParser` tests：基础行为反推 iOS `Services/StoryContentParser.swift`（P11.1c）；
 * 2026-07-02 起按「零生肉」原则扩充（FABLE5_STORY_REDESIGN_PROPOSAL §7 E1/E2/E5/E7）——
 * 三处旧断言有意翻案（空值标签 / 未知样式 / 双开修复误伤原是「1:1 iOS 既定行为」，现改为剥离不漏显）。
 *
 * **2026-08-03 格式块精简**：词表收缩为 text|scene|chapter_end。原 mood/weather/effect/pause 的成块用例
 * 全部改写成**反向断言**——老章里的这些标签必须落进未知桶被剥净（图纸 §5-E2），一个字符都不许漏显给用户。
 */
class StoryContentParserTest {

    private fun parse(s: String) = StoryContentParser.parse(s)

    // ── 基础行为（iOS 反推，不变） ──

    @Test fun plain_text_single_block() {
        assertEquals(
            listOf(StoryContentBlock.Text("从前有座山。", StoryTextStyle.NORMAL)),
            parse("从前有座山。"),
        )
    }

    @Test fun standalone_scene_with_surrounding_text() {
        assertEquals(
            listOf(
                StoryContentBlock.Text("之前", StoryTextStyle.NORMAL),
                StoryContentBlock.SceneTransition("公园"),
                StoryContentBlock.Text("之后", StoryTextStyle.NORMAL),
            ),
            parse("之前[scene:公园]之后"),
        )
    }

    @Test fun styled_text_preserves_inner_without_trim() {
        assertEquals(
            listOf(StoryContentBlock.Text("喊！", StoryTextStyle.SHOUT)),
            parse("[text:shout]喊！[/text]"),
        )
    }

    @Test fun scene_and_chapter_end_are_the_only_standalone_tags() {
        assertEquals(listOf(StoryContentBlock.SceneTransition("公园")), parse("[scene:公园]"))
        assertEquals(listOf(StoryContentBlock.ChapterEnd), parse("[chapter_end]"))
    }

    // ── E2（2026-08-03）：老章里的已删标签一律剥净，不成块也不漏显 ──

    @Test fun retired_tags_are_stripped_not_rendered() {
        listOf("[mood:warm]", "[weather:rain]", "[effect:shake]", "[pause:2.5]", "[pause:abc]", "[mood:]").forEach {
            assertEquals("$it 必须被剥净", emptyList<StoryContentBlock>(), parse(it))
        }
    }

    @Test fun retired_tags_inside_old_chapter_stripped_while_kept_tags_render() {
        // 真实旧章形态：氛围/天气/特效/停顿标签夹在正文里，text/scene/chapter_end 照常渲染。
        val old = """
            [mood:tense]雨点砸在铁皮棚上。

            [weather:rain]

            [scene:半小时后·车里]

            [text:whisper]"你冷不冷。"[/text]

            [effect:shake]

            [pause:1.5]

            他没有回答。

            [chapter_end]
        """.trimIndent()
        val blocks = parse(old)
        assertEquals(
            listOf(
                StoryContentBlock.Text("雨点砸在铁皮棚上。", StoryTextStyle.NORMAL),
                StoryContentBlock.SceneTransition("半小时后·车里"),
                StoryContentBlock.Text("\"你冷不冷。\"", StoryTextStyle.WHISPER),
                StoryContentBlock.Text("他没有回答。", StoryTextStyle.NORMAL),
                StoryContentBlock.ChapterEnd,
            ),
            blocks,
        )
        val leaked = blocks.filterIsInstance<StoryContentBlock.Text>().filter { it.text.contains("[") || it.text.contains("]") }
        assertEquals("正文块里泄漏了原始标签", emptyList<StoryContentBlock.Text>(), leaked)
    }

    @Test fun fullwidth_brackets_and_colon_normalized() {
        assertEquals(listOf(StoryContentBlock.SceneTransition("公园")), parse("【scene：公园】"))
        // 全角形态的已删标签同样落未知桶被剥
        assertEquals(emptyList<StoryContentBlock>(), parse("【mood：warm】"))
    }

    @Test fun doubled_open_tag_fixed_as_close() {
        // LLM 用第二个开标签当闭合：[text:shout]喊[text:shout] → [text:shout]喊[/text]
        assertEquals(
            listOf(StoryContentBlock.Text("喊", StoryTextStyle.SHOUT)),
            parse("[text:shout]喊[text:shout]"),
        )
    }

    @Test fun bare_text_close_fixed() {
        // 裸 [text] 当闭合：[text:angry]怒[text] → [text:angry]怒[/text]
        assertEquals(
            listOf(StoryContentBlock.Text("怒", StoryTextStyle.ANGRY)),
            parse("[text:angry]怒[text]"),
        )
    }

    @Test fun mixed_sequence_text_tags_text() {
        assertEquals(
            listOf(
                StoryContentBlock.Text("开场。", StoryTextStyle.NORMAL),
                StoryContentBlock.SceneTransition("夜里"),
                StoryContentBlock.Text("中段。", StoryTextStyle.NORMAL),
                StoryContentBlock.ChapterEnd,
            ),
            parse("开场。[scene:夜里]中段。[chapter_end]"),
        )
    }

    // ── E2：双开修复不再误伤相邻良构块（旧断言翻案：曾产出 "[/text]B[/text]" 生肉） ──

    @Test fun two_well_formed_styled_blocks_stay_intact() {
        assertEquals(
            listOf(StoryContentBlock.Text("A\nB", StoryTextStyle.WHISPER)),
            parse("[text:whisper]A[/text][text:whisper]B[/text]"),
        )
    }

    @Test fun doubled_fix_still_applies_between_well_formed_neighbors() {
        // 真双开（首块漏闭合）夹在良构环境里仍要修：[text:shout]喊[text:shout] 修复 + 后续良构块不受扰
        assertEquals(
            listOf(
                StoryContentBlock.Text("喊", StoryTextStyle.SHOUT),
                StoryContentBlock.Text("静", StoryTextStyle.WHISPER),
            ),
            parse("[text:shout]喊[text:shout][text:whisper]静[/text]"),
        )
    }

    // ── E1：大小写全容错（旧行为=原样漏显） ──

    @Test fun uppercase_and_mixed_case_tags_recognized() {
        assertEquals(listOf(StoryContentBlock.SceneTransition("公园")), parse("[SCENE:公园]"))
        assertEquals(
            listOf(StoryContentBlock.Text("喊", StoryTextStyle.SHOUT)),
            parse("[Text:Shout]喊[/TEXT]"),
        )
        assertEquals(listOf(StoryContentBlock.ChapterEnd), parse("[CHAPTER_END]"))
        // 已删标签的大小写变体同样落未知桶
        assertEquals(emptyList<StoryContentBlock>(), parse("[MOOD:WARM]"))
        assertEquals(emptyList<StoryContentBlock>(), parse("[Weather:Rain]"))
    }

    @Test fun retired_tag_stripped_without_breaking_sentence() {
        // 未知桶走的是**归一化阶段的纯文本替换**（不产生块边界）：标签整体消失，它两侧的换行原样留着，
        // 故独占一行的标签剥完是「之前 + 空行 + 之后」的同一个正文块——用户看不到任何标签残迹。
        assertEquals(
            listOf(StoryContentBlock.Text("之前\n\n之后", StoryTextStyle.NORMAL)),
            parse("之前\n[mood:notreal]\n之后"),
        )
    }

    // ── E5：空值标签 / 未知标签 / 孤儿残片 / 未知样式，一律剥离不漏显（旧断言翻案） ──

    @Test fun empty_value_scene_becomes_blank_divider_not_raw_text() {
        // scene 从来没有「空值即剥离」的守卫（那是已退役的 mood/weather 分支才有的）——空描述照样成块，
        // 渲染出的是一条没有文字的转场分隔线。关键是**标签原文一个字符都不漏显**。本卷不改这条既有语义。
        assertEquals(listOf(StoryContentBlock.SceneTransition("")), parse("[scene:]"))
    }

    @Test fun unknown_tag_inline_stripped_without_breaking_sentence() {
        // 归一化阶段纯文本剥离 → 句子保持连续，不产生换行边界
        assertEquals(
            listOf(StoryContentBlock.Text("你好啊", StoryTextStyle.NORMAL)),
            parse("你好[foo:bar]啊"),
        )
        assertEquals(
            listOf(StoryContentBlock.Text("心跳声起。", StoryTextStyle.NORMAL)),
            parse("[sound:heartbeat]心跳声起。"),
        )
    }

    @Test fun unknown_close_tag_and_orphan_close_stripped() {
        assertEquals(listOf(StoryContentBlock.Text("残", StoryTextStyle.NORMAL)), parse("[/foo]残"))
        assertEquals(listOf(StoryContentBlock.Text("孤\n儿", StoryTextStyle.NORMAL)), parse("孤[/text]儿"))
    }

    @Test fun unpaired_open_style_tag_stripped_content_kept() {
        assertEquals(
            listOf(StoryContentBlock.Text("后文照常。", StoryTextStyle.NORMAL)),
            parse("[text:whisper]后文照常。"),
        )
    }

    @Test fun unknown_style_keeps_inner_as_plain_text() {
        // 旧行为（1:1 iOS）：整段 "[text:unknown]X[/text]" 原样漏显；现剥标签留内文。
        assertEquals(
            listOf(StoryContentBlock.Text("X", StoryTextStyle.NORMAL)),
            parse("[text:unknown]X[/text]"),
        )
    }

    @Test fun standalone_tag_inside_styled_span_stripped_from_inner() {
        // 旧行为（1:1 iOS）：块内标签原样可见（"想[mood:dark]法"）；现剥离（块内标签不改变氛围，只清理）。
        assertEquals(
            listOf(StoryContentBlock.Text("想法", StoryTextStyle.THOUGHT)),
            parse("[text:thought]想[scene:夜]法[/text]"),
        )
    }

    @Test fun chinese_brackets_in_prose_untouched() {
        // 中文名/注记形态的方括号不是标签，不受未知标签剥离影响
        assertEquals(
            listOf(StoryContentBlock.Text("他翻开[注]那一页。", StoryTextStyle.NORMAL)),
            parse("他翻开[注]那一页。"),
        )
    }

    @Test fun known_name_prefix_not_false_released() {
        // texture 以 text 开头但不是已知标签（负向前瞻带 [:\]] 锚）→ 按未知标签剥离
        assertEquals(
            listOf(StoryContentBlock.Text("墙面。", StoryTextStyle.NORMAL)),
            parse("[texture:rough]墙面。"),
        )
    }

    // ── E7：长章节规模压力（解析干净 + 宽松时限） ──

    @Test fun long_chapter_parses_clean_and_fast() {
        val sb = StringBuilder()
        repeat(200) { i ->
            sb.append("[mood:warm]\n") // 老章残留标签：一并压测「剥离」路径
            sb.append("第${i}段。这是一个足够长的段落，用来模拟三千字章节在真实标签密度下的解析压力。\n")
            if (i % 3 == 0) sb.append("[scene:第${i}处]\n")
            if (i % 5 == 0) sb.append("[text:whisper]低声细语${i}[/text]\n")
            if (i % 7 == 0) sb.append("[pause:0.5]\n")
        }
        sb.append("[chapter_end]")

        val start = System.nanoTime()
        val blocks = parse(sb.toString())
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertTrue("解析耗时 ${elapsedMs}ms 超过宽松上界", elapsedMs < 2000)
        // 已删标签走纯文本剥离、不成块，被它们隔开的正文段会合并 → 块数比精简前少（旧口径 >300）。
        assertTrue("块数异常：${blocks.size}", blocks.size > 150)
        val leaked = blocks.filterIsInstance<StoryContentBlock.Text>()
            .filter { it.text.contains("[mood") || it.text.contains("[text") || it.text.contains("[/text") }
        assertEquals("正文块中泄漏了原始标签", emptyList<StoryContentBlock.Text>(), leaked)
    }

    // ── §7/§11 剥离观测点：diagnostics 尾参只收「标签名@位置」，默认路径字节级零变化 ──

    @Test fun diagnostics_null_output_identical_to_collecting() {
        // 硬约束：收集诊断不得改变解析输出——非 null 路径与默认 null 路径逐块相等（字节级零变化锁）
        val input = "开场[foo:bar][text:whisper]悄[mood:qqq]语[/text]之后[mood:zzz]收[/text]尾"
        val diag = mutableListOf<String>()
        val withoutDiag = StoryContentParser.parse(input)
        val withDiag = StoryContentParser.parse(input, diag)
        assertEquals("收集诊断改变了解析输出（违反字节级零变化）", withoutDiag, withDiag)
        assertTrue("有剥离时须收集到条目", diag.isNotEmpty())
    }

    @Test fun diagnostics_collects_stripped_tag_names_without_values() {
        // 收集正确：未知标签 [foo:bar] + 已删标签 [mood:zzz]（同走未知桶）+ 孤儿 [/text] 三处
        val diag = mutableListOf<String>()
        StoryContentParser.parse("你好[foo:bar]啊[mood:zzz]中[/text]尾", diag)
        assertEquals(3, diag.size)
        assertEquals(listOf("foo", "mood", "/text"), diag.map { it.substringBefore("@") })
        assertTrue("每条须带 @数字位置", diag.all { it.substringAfter("@").toIntOrNull() != null })
        assertTrue("标签值（bar/zzz）绝不进日志", diag.none { it.contains("bar") || it.contains("zzz") })
    }

    @Test fun diagnostics_empty_when_nothing_stripped() {
        // 空集：全为合法标签，无任何剥离
        val diag = mutableListOf<String>()
        StoryContentParser.parse("开场。[scene:夜里]中段。[text:shout]喊[/text][chapter_end]", diag)
        assertEquals(emptyList<String>(), diag)
    }
}
