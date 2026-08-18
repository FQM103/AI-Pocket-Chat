package com.situ.aichat.story

import com.situ.aichat.data.local.entity.StoryChapterEntity
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 末句要点复述 T1（2026-07-27 用户拍板「只优化最后那句 user message」·微图纸
 * docs/handoff/2026-07-27-文风避让与末句要点复述.md）。
 *
 * 断言从规格独立反推，不照抄实现：
 * ① 四态各自该出现/不该出现什么；② **字数与 system 段逐字一致**（本卷命门·见 [末句字数与system段四档全等]）；
 * ③ 预设点选补集谓词 [StoryChoiceClassifier.presetChoiceForRecap] 的边界。
 */
class StoryCreationUserMessageTest {

    private val choiceHint = "结尾必须设置选择节点"

    private fun msg(
        chapterNumber: Int = 7,
        baseChapterLength: Int = 1_500,
        isEndingChapter: Boolean = false,
        freeformDirective: String? = null,
        userChoice: String? = null,
    ) = buildCreationUserMessage(chapterNumber, baseChapterLength, isEndingChapter, freeformDirective, userChoice)

    // ── 四态 ──

    @Test fun 首章_给字数与选择节点_不提上一章() {
        val text = msg(chapterNumber = 1, userChoice = "本不该被复述")
        assertEquals("请开始创作第一章。", text.lines().first())
        assertTrue(text.contains(CREATION_RECAP_HEADER))
        assertTrue("1500 档 = ±20%", text.contains("目标字数 1200-1800 字"))
        assertTrue(text.contains(choiceHint))
        assertFalse("第一章没有「上一章」", text.contains("上一章我选择了"))
    }

    @Test fun 骨架恒为_开场语_空行_标题_要点() {
        val text = msg(userChoice = "主动打招呼")
        val lines = text.lines()
        assertEquals("第二行必须是空行（开场语与要点之间要断开）", "", lines[1])
        assertEquals("第三行是要点标题", CREATION_RECAP_HEADER, lines[2])
        assertEquals("标题恰出现一次", 1, lines.count { it == CREATION_RECAP_HEADER })
        assertTrue("其后每行都是要点条目", lines.drop(3).all { it.startsWith("- ") })
    }

    @Test fun 要点标题是锁定文本() {
        // 独立重打一遍字面量与常量比对（照 StoryPacingPreferenceInjectionTest 的单源锁先例）——
        // 只写 contains(CREATION_RECAP_HEADER) 是同义反复，改了常量测试照样绿。
        assertEquals("本章要点（务必落实）：", CREATION_RECAP_HEADER)
    }

    @Test fun 首章强制忽略结局标记_绝不与system段自相矛盾() {
        // 首章的 system 段恒走 chapterRequirements（永不看 requestedEndingType），
        // 若末句说「不设选择节点」就会与之直接打架。首章调用点今天不传该参，此测试钉住「将来传了也不出事」。
        val text = msg(chapterNumber = 1, isEndingChapter = true)
        assertTrue("首章照常要选择节点", text.contains(choiceHint))
        assertFalse(text.contains("hasChoice 为 false"))
        assertFalse(text.contains("isEnding 为 true"))
        assertTrue("字数走普通章区间", text.contains("目标字数 1200-1800 字"))
    }

    @Test fun 续章预设点选_重申方向() {
        val text = msg(userChoice = "主动打招呼")
        assertEquals("请继续创作下一章。", text.lines().first())
        assertTrue(text.contains("- 上一章我选择了「主动打招呼」，本章要落实这个方向"))
        assertTrue(text.contains(choiceHint))
    }

    @Test fun 续章自由输入_保留强指令且不重复选择行() {
        // 自由输入时 freeformDirective 与 userChoice 是同一段文本（互为补集的谓词保证只命中一个），
        // 末句必须只出现一次，否则同一句话在注意力峰值位重复两遍。
        val directive = "让他们在雪夜里重逢"
        val text = msg(freeformDirective = directive, userChoice = directive)
        assertTrue(text.lines().first().startsWith("请继续创作下一章。我已亲笔指定本章的剧情走向：「$directive」"))
        assertFalse("不得再复述一次", text.contains("上一章我选择了"))
        assertEquals("走向原文恰出现一次", 1, Regex(Regex.escape(directive)).findAll(text).count())
        assertTrue(text.contains(choiceHint))
    }

    @Test fun 结局章_禁止出现选择节点要求() {
        val text = msg(isEndingChapter = true, userChoice = "去机场")
        assertFalse("system 段明写 hasChoice 必须为 false", text.contains(choiceHint))
        assertTrue(text.contains("hasChoice 为 false"))
        assertTrue(text.contains("isEnding 为 true"))
        assertTrue("结局章也顺着上一章的选择收", text.contains("上一章我选择了「去机场」"))
    }

    @Test fun 没有上一章选择时不给方向行() {
        val text = msg(userChoice = null)
        assertFalse(text.contains("上一章我选择了"))
        assertTrue("字数行照给", text.contains("目标字数"))
        assertTrue("选择节点行照给", text.contains(choiceHint))
    }

    @Test fun 脏档位不把字数说成胡话() {
        // 溢出闸（R1 复核 🔵）：Int.MAX_VALUE 旧实现只钳下限 → safe*6 溢出成负数 → 渲染「0--1 字」。
        // 这句话站在注意力峰值位，绝不能变成胡话。
        listOf(Int.MAX_VALUE, Int.MAX_VALUE / 2, 0, -7, 1).forEach { dirty ->
            val (min, max) = StoryWritingTechniques.chapterLengthRange(dirty)
            assertTrue("下界必须为正（$dirty → $min）", min > 0)
            assertTrue("min ≤ max（$dirty → $min-$max）", min <= max)
            val (eMin, eMax) = StoryWritingTechniques.endingChapterLengthRange(dirty)
            assertTrue("结局区间同样健全（$dirty → $eMin-$eMax）", eMin > 0 && eMin <= eMax)
        }
    }

    // ── 命门：末句字数必须与 system 段逐字一致 ──

    @Test fun 末句字数与system段四档全等() {
        // 普通章：system 段走 chapterRequirements 的「本章目标字数：min-max 字」
        listOf(500, 1_500, 3_000, 5_000).forEach { base ->
            val (min, max) = StoryWritingTechniques.chapterLengthRange(base)
            val system = StoryWritingTechniques.chapterRequirements(
                chapterNumber = 3, chapterLength = base, isFirstChapter = false,
            )
            assertTrue("system 段含 $min-$max", system.contains("本章目标字数：$min-$max 字"))
            assertTrue("末句同区间（$base 档）", msg(baseChapterLength = base).contains("目标字数 $min-$max 字"))
        }
    }

    @Test fun 结局章末句字数与system结局段四档全等() {
        listOf(500, 1_500, 3_000, 5_000).forEach { base ->
            val (min, max) = StoryWritingTechniques.endingChapterLengthRange(base)
            val system = StoryWritingTechniques.requestedEndingRequirements(
                endingType = "ai", endingDetail = null, chapterNumber = 20, chapterLength = base,
            )
            assertTrue("system 结局段含 $min-$max（$base 档）", system.contains("目标字数：$min-$max 字"))
            assertTrue(
                "末句同区间（$base 档）",
                msg(baseChapterLength = base, isEndingChapter = true).contains("目标字数 $min-$max 字"),
            )
        }
    }

    @Test fun 结局章区间恒为普通上限到其一点五倍() {
        // 独立反推口径（不照抄实现）：下界 = 普通章上界；上界 = 下界 × 1.5
        listOf(500, 1_500, 3_000, 5_000).forEach { base ->
            val (_, normalMax) = StoryWritingTechniques.chapterLengthRange(base)
            val (endMin, endMax) = StoryWritingTechniques.endingChapterLengthRange(base)
            assertEquals("下界 = 普通上界（$base）", normalMax, endMin)
            assertEquals("上界 = 下界 ×1.5（$base）", (normalMax * 1.5).toInt(), endMax)
        }
    }

    // ── 预设点选谓词（freeformDirective 的补集）──

    private fun chapter(
        hasChoice: Boolean = true,
        userChoice: String? = null,
        options: List<String> = listOf("主动打招呼", "假装没看见"),
    ) = StoryChapterEntity(
        storyId = "s", chapterNumber = 2, title = "t", content = "c",
        hasChoice = hasChoice, userChoice = userChoice,
        choiceOptions = Json.encodeToString(kotlinx.serialization.serializer(), options),
    )

    @Test fun 预设点选谓词_只认命中预设选项的那一种() {
        val C = StoryChoiceClassifier
        assertEquals("主动打招呼", C.presetChoiceForRecap(chapter(userChoice = "主动打招呼")))
        assertNull("自由输入归 freeformDirective", C.presetChoiceForRecap(chapter(userChoice = "让他们在雪夜里重逢")))
        assertNull("哨兵不是方向", C.presetChoiceForRecap(chapter(userChoice = StoryChoiceClassifier.NATURAL_FLOW_CHOICE)))
        assertNull("跳过收尾哨兵同理", C.presetChoiceForRecap(chapter(userChoice = StoryChoiceClassifier.SKIP_FOR_ENDING_CHOICE)))
        assertNull("没有选择节点", C.presetChoiceForRecap(chapter(hasChoice = false, userChoice = "主动打招呼")))
        assertNull("空选择", C.presetChoiceForRecap(chapter(userChoice = "  ")))
        assertNull("null 章", C.presetChoiceForRecap(null))
    }

    // ── 图纸二 D3：章末选项开关（验收 T1-3）──

    private fun msgWithToggle(
        chapterNumber: Int = 7,
        isEndingChapter: Boolean = false,
        freeformDirective: String? = null,
        userChoice: String? = null,
        choicesEnabled: Boolean,
    ) = buildCreationUserMessage(
        chapterNumber = chapterNumber,
        baseChapterLength = 1_500,
        isEndingChapter = isEndingChapter,
        freeformDirective = freeformDirective,
        userChoice = userChoice,
        choicesEnabled = choicesEnabled,
    )

    @Test fun D3_默认开关_末句与加参数前逐字节相同() {
        // 回归钉：新尾参默认值不许改变任何既有四态的输出。
        listOf(
            Triple(1, null, null),
            Triple(7, null, "主动打招呼"),
            Triple(7, "让他们在雪夜里重逢", null),
        ).forEach { (chapter, freeform, choice) ->
            assertEquals(
                buildCreationUserMessage(chapter, 1_500, isEndingChapter = false, freeformDirective = freeform, userChoice = choice),
                msgWithToggle(chapterNumber = chapter, freeformDirective = freeform, userChoice = choice, choicesEnabled = true),
            )
        }
        assertEquals(
            buildCreationUserMessage(7, 1_500, isEndingChapter = true, userChoice = "去机场"),
            msgWithToggle(isEndingChapter = true, userChoice = "去机场", choicesEnabled = true),
        )
    }

    @Test fun D3_关选项_非结局章换成关闭态一行() {
        val text = msgWithToggle(choicesEnabled = false)
        assertFalse("不许再要求给选项", text.contains(choiceHint))
        assertTrue(text.contains("- 本书不设章末选择，hasChoice 为 false，结尾留重钩子让人急着看下一章"))
        assertTrue("字数要点照常", text.contains("- 目标字数 1200-1800 字，在这个范围里找自然的场景结尾收束"))
    }

    @Test fun D3_关选项_首章同样换成关闭态一行() {
        val text = msgWithToggle(chapterNumber = 1, choicesEnabled = false)
        assertEquals("请开始创作第一章。", text.lines().first())
        assertFalse(text.contains(choiceHint))
        assertTrue(text.contains("- 本书不设章末选择，hasChoice 为 false，结尾留重钩子让人急着看下一章"))
    }

    @Test fun D3_关选项_结局章走原路不受影响_不出现两句hasChoice() {
        val text = msgWithToggle(isEndingChapter = true, userChoice = "去机场", choicesEnabled = false)
        assertTrue(text.contains("- 这是最终章：回收伏笔、给角色归宿，isEnding 为 true"))
        assertTrue(text.contains("- 不设选择节点，hasChoice 为 false"))
        assertFalse("M4 那行只发给非结局章", text.contains("- 本书不设章末选择，hasChoice 为 false，结尾留重钩子让人急着看下一章"))
        assertEquals("整条消息里 hasChoice 只说一次", 1, Regex("hasChoice 为 false").findAll(text).count())
    }

    @Test fun D3_关选项_自由走向的三明治强指令逐字不变() {
        val text = msgWithToggle(freeformDirective = "让他们在雪夜里重逢", choicesEnabled = false)
        assertTrue(
            text.lines().first().contains("我已亲笔指定本章的剧情走向：「让他们在雪夜里重逢」——这是本章的任务书，必须照此推进"),
        )
        assertTrue(text.contains("- 本书不设章末选择，hasChoice 为 false，结尾留重钩子让人急着看下一章"))
    }

    @Test fun 两个谓词互为补集_同一选择至多命中其一() {
        val C = StoryChoiceClassifier
        listOf(
            "主动打招呼",                                   // 预设
            "让他们在雪夜里重逢",                            // 自由
            StoryChoiceClassifier.NATURAL_FLOW_CHOICE,      // 哨兵
            StoryChoiceClassifier.SKIP_FOR_ENDING_CHOICE,   // 哨兵
        ).forEach { choice ->
            val ch = chapter(userChoice = choice)
            val hits = listOfNotNull(C.freeformDirective(ch), C.presetChoiceForRecap(ch))
            assertTrue("「$choice」至多命中一个谓词，实得 ${hits.size}", hits.size <= 1)
        }
    }
}
