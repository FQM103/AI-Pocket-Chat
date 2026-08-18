package com.situ.aichat.story

import com.situ.aichat.story.StoryArcPlanning.FinaleCountdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 弧线变速箱纯逻辑 T1（图纸 §7 T1-1 / E3·E10·E11）——断言全部从图纸 §0②-J4/J5、§3.2、§5 规格**重新打字**，
 * 不照抄实现输出。
 *
 * 三块：自报章数解析与钳位（B1）/ 弧线简史追加与掐尾（B2）/ 终章弧倒数（J1·±1 精度）。
 */
class StoryArcPlanningTest {

    private val P = StoryArcPlanning

    // ── B1：parseArcPlannedLength ──

    @Test fun parse_standard_first_line() {
        val outline = "本弧预计章数：10\n\n## 弧线设计要求\n弧线主题：雨夜追凶"
        assertEquals(10, P.parseArcPlannedLength(outline))
    }

    @Test fun parse_tolerates_leading_markup_and_spaces() {
        // LLM 常把指令原样带上加粗号/空格；前缀之后的 ASCII 空白允许。
        assertEquals(9, P.parseArcPlannedLength("**第一行必须输出**：本弧预计章数： 9"))
        assertEquals(13, P.parseArcPlannedLength("一些前言\n- 本弧预计章数：13\n后续内容"))
    }

    @Test fun parse_takes_first_occurrence() {
        assertEquals(8, P.parseArcPlannedLength("本弧预计章数：8\n……\n本弧预计章数：15"))
    }

    @Test fun parse_missing_or_malformed_returns_null() {
        assertNull("整段没有这一行", P.parseArcPlannedLength("## 弧线设计要求\n弧线主题：某某"))
        assertNull("null 大纲", P.parseArcPlannedLength(null))
        assertNull("空大纲", P.parseArcPlannedLength(""))
        assertNull("数字位是中文", P.parseArcPlannedLength("本弧预计章数：十二"))
        assertNull("数字位是字母", P.parseArcPlannedLength("本弧预计章数：abc"))
        assertNull("只有指令没有数字（模型照抄了 N）", P.parseArcPlannedLength("本弧预计章数：N（N 取 8-15）"))
    }

    @Test fun parse_returns_raw_value_without_clamping() {
        // 钳位是 effectiveArcLength 的职责——普通弧与终章弧区间不同，parse 不许提前钳。
        assertEquals(30, P.parseArcPlannedLength("本弧预计章数：30"))
        assertEquals(-5, P.parseArcPlannedLength("本弧预计章数：-5"))
        assertEquals(0, P.parseArcPlannedLength("本弧预计章数：0"))
    }

    @Test fun parse_skips_instruction_echo_and_finds_real_value() {
        // 模型把指令整句抄了一遍、真值在后面：前缀后不是数字的那处不算命中，继续往后找。
        val outline = "本弧预计章数：N（N 取 8-15 之间的一个整数）\n本弧预计章数：11\n弧线主题：归乡"
        assertEquals(11, P.parseArcPlannedLength(outline))
    }

    // ── B1：effectiveArcLength 钳位 / 回退 ──

    @Test fun effective_length_normal_arc_clamps_to_8_15() {
        assertEquals("界内原样", 8, P.effectiveArcLength(8))
        assertEquals("界内原样", 12, P.effectiveArcLength(12))
        assertEquals("界内原样", 15, P.effectiveArcLength(15))
        assertEquals("低于下限钳到 8", 8, P.effectiveArcLength(7))
        assertEquals("负数钳到 8", 8, P.effectiveArcLength(-5))
        assertEquals("超上限钳到 15", 15, P.effectiveArcLength(30))
    }

    @Test fun effective_length_normal_arc_falls_back_to_12() {
        assertEquals(12, P.effectiveArcLength(null))
    }

    @Test fun effective_length_finale_arc_clamps_to_3_5() {
        assertEquals(3, P.effectiveArcLength(3, isFinale = true))
        assertEquals(5, P.effectiveArcLength(5, isFinale = true))
        assertEquals("低于下限钳到 3", 3, P.effectiveArcLength(1, isFinale = true))
        assertEquals("超上限钳到 5", 5, P.effectiveArcLength(12, isFinale = true))
    }

    @Test fun effective_length_finale_arc_falls_back_to_4() {
        assertEquals(4, P.effectiveArcLength(null, isFinale = true))
    }

    // ── arcIndex ──

    @Test fun arc_index_counts_from_one() {
        assertEquals(1, P.arcIndex(arcStart = 13, chapterNumber = 13))
        assertEquals(8, P.arcIndex(arcStart = 13, chapterNumber = 20))
        assertEquals("arcStart null → 视作第 1 章起", 4, P.arcIndex(arcStart = null, chapterNumber = 4))
    }

    // ── B2：appendArcHistoryLine ──

    @Test fun history_line_uses_theme_from_previous_outline() {
        val line = P.appendArcHistoryLine(
            existingHistory = null,
            previousOutline = "本弧预计章数：12\n\n**弧线主题：** 雨夜追凶与旧案重启\n触发事件：……",
            previousArcSummary = "不该用到我",
            arcStart = 1,
            arcEnd = 12,
        )
        assertEquals("第1–12章·雨夜追凶与旧案重启", line)
    }

    @Test fun history_line_tolerates_markdown_around_theme_label() {
        // 模型排版三形态都要抽出同一个主题（加粗把冒号包进去 / 加粗只包标签 / 裸行）。
        val variants = listOf(
            "**弧线主题：** 归乡与和解",
            "- **弧线主题**：归乡与和解",
            "弧线主题：归乡与和解",
        )
        for (v in variants) {
            assertEquals("排版形态「$v」应抽出同一主题", "第1–8章·归乡与和解", P.appendArcHistoryLine(null, v, null, 1, 8))
        }
    }

    @Test fun history_line_falls_back_to_current_arc_first_40_chars() {
        val summary = "两" + "个".repeat(60)  // 61 字，取前 40
        val line = P.appendArcHistoryLine(
            existingHistory = null,
            previousOutline = "没有主题行的旧大纲",
            previousArcSummary = summary,
            arcStart = 5,
            arcEnd = 16,
        )
        assertEquals("第5–16章·" + summary.take(40), line)
        assertTrue("回退截断到 40 字", line.substringAfter("·").length == 40)
    }

    @Test fun history_line_falls_back_to_chapter_range_only() {
        val line = P.appendArcHistoryLine(
            existingHistory = null,
            previousOutline = null,
            previousArcSummary = null,
            arcStart = 3,
            arcEnd = 9,
        )
        assertEquals("第3–9章", line)
    }

    @Test fun history_line_blank_summary_is_treated_as_absent() {
        val line = P.appendArcHistoryLine(null, "无主题行", "   ", arcStart = 1, arcEnd = 6)
        assertEquals("第1–6章", line)
    }

    @Test fun history_line_null_arc_start_treated_as_one() {
        val line = P.appendArcHistoryLine(null, null, null, arcStart = null, arcEnd = 11)
        assertEquals("第1–11章", line)
    }

    @Test fun history_appends_below_existing_lines() {
        val out = P.appendArcHistoryLine(
            existingHistory = "第1–12章·开局\n第13–20章·转折",
            previousOutline = "弧线主题：收网",
            previousArcSummary = null,
            arcStart = 21,
            arcEnd = 30,
        )
        assertEquals("第1–12章·开局\n第13–20章·转折\n第21–30章·收网", out)
    }

    @Test fun history_drops_oldest_beyond_twenty_lines() {
        // 已有 20 行（第1–2章 … 第39–40章 形状不重要，只看条数与首尾）→ 追加第 21 行后掐掉最老的一行。
        val existing = (1..20).joinToString("\n") { "第${it}–${it}章·旧弧$it" }
        val out = P.appendArcHistoryLine(existing, "弧线主题：新弧", null, arcStart = 100, arcEnd = 110)
        val lines = out.lines()
        assertEquals("上限恒 20 行", 20, lines.size)
        assertEquals("最老的一行被掐掉", "第2–2章·旧弧2", lines.first())
        assertEquals("新行在最后", "第100–110章·新弧", lines.last())
    }

    @Test fun history_blank_lines_in_existing_are_dropped() {
        val out = P.appendArcHistoryLine("第1–8章·甲\n\n第9–18章·乙\n", "弧线主题：丙", null, 19, 28)
        assertEquals("第1–8章·甲\n第9–18章·乙\n第19–28章·丙", out)
    }

    // ── J1：finaleCountdown（±1 精度·L=3..5 全点位）──

    @Test fun finale_countdown_last_chapter_by_planned_length() {
        // L=4（arcStart=20）：第 20/21/22 章 RUNNING，第 23 章（本弧第 4 章）LAST。
        assertEquals(FinaleCountdown.RUNNING, P.finaleCountdown(20, 4, 20))
        assertEquals(FinaleCountdown.RUNNING, P.finaleCountdown(20, 4, 21))
        assertEquals(FinaleCountdown.RUNNING, P.finaleCountdown(20, 4, 22))
        assertEquals(FinaleCountdown.LAST, P.finaleCountdown(20, 4, 23))
    }

    @Test fun finale_countdown_covers_every_legal_length() {
        for (length in StoryArcPlanning.FINALE_LENGTH_MIN..StoryArcPlanning.FINALE_LENGTH_MAX) {
            val arcStart = 7
            val lastChapter = arcStart + length - 1
            assertEquals(
                "L=$length 的倒数第二章仍在走",
                FinaleCountdown.RUNNING,
                P.finaleCountdown(arcStart, length, lastChapter - 1),
            )
            assertEquals(
                "L=$length 的第 $length 章就是末章",
                FinaleCountdown.LAST,
                P.finaleCountdown(arcStart, length, lastChapter),
            )
        }
    }

    @Test fun finale_countdown_stays_last_when_overshooting() {
        // 越界（重试/脏数据把章号推过头）仍判 LAST，不会漏过转正时机。
        assertEquals(FinaleCountdown.LAST, P.finaleCountdown(20, 4, 30))
    }

    @Test fun finale_countdown_uses_finale_clamp_not_normal_clamp() {
        // 自报 12（超终章弧上限）→ 钳到 5：arcStart=1 时第 5 章即末章（若误用普通弧钳位 12 则要到第 12 章）。
        assertEquals(FinaleCountdown.RUNNING, P.finaleCountdown(1, 12, 4))
        assertEquals(FinaleCountdown.LAST, P.finaleCountdown(1, 12, 5))
    }

    @Test fun finale_countdown_null_planned_length_falls_back_to_four() {
        assertEquals(FinaleCountdown.RUNNING, P.finaleCountdown(1, null, 3))
        assertEquals(FinaleCountdown.LAST, P.finaleCountdown(1, null, 4))
    }

    /**
     * 终章弧大纲还没落库（arcStart 为 null）时**恒不判末章**：否则大纲生成失败那次会拿着上一条普通弧的
     * 起点算出一个很大的 arcIndex，把「从容收尾」直接缩成一章。
     */
    @Test fun finale_countdown_without_arc_start_never_reports_last() {
        assertEquals(FinaleCountdown.RUNNING, P.finaleCountdown(null, 4, 999))
        assertEquals(FinaleCountdown.RUNNING, P.finaleCountdown(null, null, 1))
    }

    // ── 强耦合单源锁（图纸 §6）──

    /**
     * 「本弧预计章数：」前缀是 prompt 输出指令与解析正则的**单源**：弧线大纲 prompt 里必须逐字出现本常量，
     * 且用该 prompt 指令的形状喂给解析器时能取回数字。改任一侧不同步 → 本例红。
     */
    @Test fun planned_length_prefix_is_single_sourced_between_prompt_and_parser() {
        val prompt = StoryGenerationPromptBuilder.buildOutlinePrompt(
            com.situ.aichat.data.local.entity.StoryEntity(genre = "悬疑"),
            roles = emptyList(),
            characterData = emptyMap(),
        )
        assertTrue(
            "弧线大纲 prompt 必须包含自报章数指令前缀（单源常量）",
            prompt.contains(StoryArcPlanning.ARC_PLANNED_LENGTH_PREFIX),
        )
        // 用 prompt 里那一行的真实形状造一条「模型照做」的输出，解析器必须认得。
        assertEquals(11, P.parseArcPlannedLength("${StoryArcPlanning.ARC_PLANNED_LENGTH_PREFIX}11"))
    }

    // ── 卷三 C4：弧线简史读端 arcSections（图纸 §5 E8/E9）──

    @Test fun 简史行读回章号区间与主题() {
        val sections = P.arcSections("第1–8章·归乡与和解\n第9–20章·旧案重启", null, null, latestChapterNumber = 20)
        assertEquals(2, sections.size)
        assertEquals(1, sections[0].start)
        assertEquals(8, sections[0].endInclusive)
        assertEquals("归乡与和解", sections[0].theme)
        assertFalse(sections[0].ongoing)
        assertEquals(9, sections[1].start)
        assertEquals(20, sections[1].endInclusive)
        assertEquals("旧案重启", sections[1].theme)
    }

    @Test fun 简史行无主题_只有区间() {
        val sections = P.arcSections("第1–8章", null, null, latestChapterNumber = 8)
        assertEquals(1, sections.size)
        assertEquals(1, sections[0].start)
        assertEquals(8, sections[0].endInclusive)
        assertNull("没有主题段 → null（文案回退交 UI）", sections[0].theme)
    }

    @Test fun 主题里含分隔符_只切第一处() {
        // E8：主题自身可能带「·」（「重逢·雨夜」），limit=2 保证不被误切。
        val sections = P.arcSections("第3–9章·重逢·雨夜·真相", null, null, latestChapterNumber = 9)
        assertEquals(1, sections.size)
        assertEquals("重逢·雨夜·真相", sections[0].theme)
    }

    @Test fun 畸形行整行跳过_不崩不吞好行() {
        val history = listOf(
            "第1–8章·好行甲",
            "",
            "  ",
            "乱七八糟一行",
            "第X–Y章·中文章号",
            "第9章·缺区间",       // 没有起止分隔符
            "第10–章·缺末章",
            "第–12章·缺起章",
            "第13-20章·ASCII连字符不是本格式",
            "第21–30章·好行乙",
        ).joinToString("\n")
        val sections = P.arcSections(history, null, null, latestChapterNumber = 30)
        assertEquals("只留两条合法行", 2, sections.size)
        assertEquals("好行甲", sections[0].theme)
        assertEquals("好行乙", sections[1].theme)
        assertEquals(21, sections[1].start)
    }

    @Test fun 空简史且无进行中弧_返回空列表() {
        // B5：章节列表与分组前完全一致。
        assertTrue(P.arcSections(null, null, null, latestChapterNumber = 12).isEmpty())
        assertTrue(P.arcSections("", null, null, latestChapterNumber = 12).isEmpty())
        assertTrue(P.arcSections("   \n  ", null, null, latestChapterNumber = 12).isEmpty())
    }

    @Test fun 进行中弧_末章为null且取currentArc首行前40字() {
        val currentArc = "本弧聚焦母女和解与旧宅拆迁的拉扯，" + "余".repeat(60) + "\n第二行不该出现"
        val sections = P.arcSections("第1–8章·甲", currentArcStartChapter = 9, currentArcTheme = currentArc, latestChapterNumber = 13)
        assertEquals(2, sections.size)
        val ongoing = sections.last()
        assertTrue(ongoing.ongoing)
        assertEquals(9, ongoing.start)
        assertNull("进行中弧没有末章", ongoing.endInclusive)
        val theme = ongoing.theme!!
        assertEquals("主题取首行前 40 字", 40, theme.length)
        assertFalse("不含第二行", theme.contains("第二行"))
    }

    @Test fun 进行中弧_主题空白时为null() {
        val sections = P.arcSections(null, currentArcStartChapter = 5, currentArcTheme = "   ", latestChapterNumber = 7)
        assertEquals(1, sections.size)
        assertTrue(sections[0].ongoing)
        assertNull(sections[0].theme)
    }

    @Test fun 进行中弧起点还没写出章节_不出分组头() {
        // 弧刚换挡、新章尚未落库时若照出头行，列表里会挂一个底下空无一物的分组。
        val onlyHistory = P.arcSections("第1–8章·甲", currentArcStartChapter = 9, currentArcTheme = "新弧", latestChapterNumber = 8)
        assertEquals(1, onlyHistory.size)
        assertFalse(onlyHistory[0].ongoing)
        // 起点 == 最新章 → 已经有一章，出头行。
        val withOngoing = P.arcSections("第1–8章·甲", currentArcStartChapter = 9, currentArcTheme = "新弧", latestChapterNumber = 9)
        assertEquals(2, withOngoing.size)
        assertTrue(withOngoing[1].ongoing)
    }

    @Test fun 区间与实际章号错位时忠实呈现_不做对账() {
        // E9：展示层不造真理——简史说第 1–99 章就渲染第 1–99 章，哪怕书只有 12 章。
        val sections = P.arcSections("第1–99章·错峰", null, null, latestChapterNumber = 12)
        assertEquals(1, sections.size)
        assertEquals(99, sections[0].endInclusive)
    }

    /**
     * **单源锁**（图纸 §6）：[StoryArcPlanning.appendArcHistoryLine] 写出的任意一行，
     * 必须能被 [StoryArcPlanning.arcSections] 逐字读回（区间与主题一致）。行格式任一端改动不同步 → 本例红。
     */
    @Test fun 简史行写端产物必能被读端逐字读回() {
        var history: String? = null
        val expected = mutableListOf<Triple<Int, Int, String?>>()
        // 20 行满史 + 各种主题来源（大纲主题行 / currentArc 回退 / 无主题）。
        repeat(20) { i ->
            val start = i * 10 + 1
            val end = start + 9
            val theme = when (i % 3) {
                0 -> "弧线主题：第${i}弧·带分隔符"
                1 -> null
                else -> null
            }
            val summary = if (i % 3 == 2) "回退主题${i}" else null
            history = P.appendArcHistoryLine(history, theme, summary, arcStart = start, arcEnd = end)
            expected += Triple(
                start,
                end,
                when (i % 3) {
                    0 -> "第${i}弧·带分隔符"
                    1 -> null
                    else -> "回退主题${i}"
                },
            )
        }
        val sections = P.arcSections(history, null, null, latestChapterNumber = 200)
        assertEquals("满史 20 行全部读回", expected.size, sections.size)
        expected.forEachIndexed { i, (start, end, theme) ->
            assertEquals("第 $i 行起章", start, sections[i].start)
            assertEquals("第 $i 行末章", end, sections[i].endInclusive)
            assertEquals("第 $i 行主题", theme, sections[i].theme)
        }
    }
}
