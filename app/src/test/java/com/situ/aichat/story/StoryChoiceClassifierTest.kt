package com.situ.aichat.story

import com.situ.aichat.data.local.entity.StoryChapterEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1-1 判定矩阵：[StoryChoiceClassifier].freeformDirective / decodeChoiceOptions
 * （图纸 2026-07-13「故事走向遵循与题材锚定」§7 · E1/E3/E4/E5/E6/E14）。
 * 断言从 §3 规格独立反推（自由输入 = userChoice 非哨兵、非解码选项、非空/非 blank）。
 */
class StoryChoiceClassifierTest {

    private fun chapter(
        hasChoice: Boolean = true,
        userChoice: String? = null,
        choiceOptions: String? = null,
    ) = StoryChapterEntity(
        chapterNumber = 1, hasChoice = hasChoice, userChoice = userChoice, choiceOptions = choiceOptions,
    )

    // ── decodeChoiceOptions ──

    @Test fun decode_null_and_empty_return_empty() {
        assertEquals(emptyList<String>(), StoryChoiceClassifier.decodeChoiceOptions(null))
        assertEquals(emptyList<String>(), StoryChoiceClassifier.decodeChoiceOptions(""))
    }

    @Test fun decode_valid_array() {
        assertEquals(listOf("去找他", "留下来"), StoryChoiceClassifier.decodeChoiceOptions("[\"去找他\",\"留下来\"]"))
    }

    @Test fun decode_broken_json_returns_empty() {
        assertEquals(emptyList<String>(), StoryChoiceClassifier.decodeChoiceOptions("{不是数组"))
        assertEquals(emptyList<String>(), StoryChoiceClassifier.decodeChoiceOptions("["))
    }

    @Test fun decode_empty_array() {
        assertEquals(emptyList<String>(), StoryChoiceClassifier.decodeChoiceOptions("[]"))
    }

    // ── freeformDirective：null 分支（判预设/无自由输入）──

    @Test fun freeform_null_when_no_chapter() { // E14
        assertNull(StoryChoiceClassifier.freeformDirective(null))
    }

    /**
     * **翻案**（图纸 `2026-08-05-弧线大纲导演手记重构.md` J2·E3）：原期望是「!hasChoice → null」，那道门让
     * D3 关选项书（新章恒 hasChoice=false）的亲笔走向静默失效。门已删——判定只看「非空 + 非哨兵 + 不在选项列表」。
     */
    @Test fun freeform_ignores_hasChoice_flag() { // E3
        assertEquals("随便写", StoryChoiceClassifier.freeformDirective(chapter(hasChoice = false, userChoice = "随便写")))
    }

    @Test fun freeform_null_when_userChoice_null_or_blank() { // E6
        assertNull(StoryChoiceClassifier.freeformDirective(chapter(userChoice = null)))
        assertNull(StoryChoiceClassifier.freeformDirective(chapter(userChoice = "   ")))
    }

    @Test fun freeform_null_for_sentinels() { // E3
        assertNull(StoryChoiceClassifier.freeformDirective(chapter(userChoice = StoryChoiceClassifier.NATURAL_FLOW_CHOICE)))
        assertNull(StoryChoiceClassifier.freeformDirective(chapter(userChoice = StoryChoiceClassifier.SKIP_FOR_ENDING_CHOICE)))
    }

    @Test fun freeform_null_when_choice_matches_an_option_verbatim() { // E1/E5
        val ch = chapter(userChoice = "去找他", choiceOptions = "[\"去找他\",\"留下来\"]")
        assertNull(StoryChoiceClassifier.freeformDirective(ch))
    }

    // ── freeformDirective：自由输入分支（返回原文）──

    @Test fun freeform_returns_text_when_not_matching_options() { // E2
        val ch = chapter(userChoice = "让他们私奔去边关", choiceOptions = "[\"去找他\",\"留下来\"]")
        assertEquals("让他们私奔去边关", StoryChoiceClassifier.freeformDirective(ch))
    }

    @Test fun freeform_returns_text_when_options_null_broken_or_empty() { // E4 / J2
        assertEquals("我要的走向", StoryChoiceClassifier.freeformDirective(chapter(userChoice = "我要的走向", choiceOptions = null)))
        assertEquals("我要的走向", StoryChoiceClassifier.freeformDirective(chapter(userChoice = "我要的走向", choiceOptions = "{坏掉的")))
        assertEquals("我要的走向", StoryChoiceClassifier.freeformDirective(chapter(userChoice = "我要的走向", choiceOptions = "[]")))
    }

    // ── isSentinel（图纸 2026-08-06「已存走向」§3.1·T1-1）──
    // 规格：只有那两个系统自造的标记算哨兵；用户写的任何字、点的任何选项、以及「什么都没答」一律不算。
    // 哨兵的下游意义 = 导演台不给它当预填文本、不给撤回按钮（撤 SKIP 会造出「选择重开 + 结局意图仍挂」的幽灵组合）。

    @Test fun isSentinel_true_for_the_two_markers() {
        // 期望值在此**重新打字**（不引用实现常量），另配一条与常量的一致性钉在下面——双保险。
        assertTrue(StoryChoiceClassifier.isSentinel("（让故事自然发展）"))
        assertTrue(StoryChoiceClassifier.isSentinel("（跳过选择，直接进入结局）"))
    }

    /** 双保险 pin：字面量必须与实现暴露的两个常量逐字节相同（谁改了常量值，这里立刻红）。 */
    @Test fun isSentinel_literals_match_the_exposed_constants() {
        assertEquals("（让故事自然发展）", StoryChoiceClassifier.NATURAL_FLOW_CHOICE)
        assertEquals("（跳过选择，直接进入结局）", StoryChoiceClassifier.SKIP_FOR_ENDING_CHOICE)
    }

    @Test fun isSentinel_false_for_null_blank_and_plain_text() {
        assertFalse(StoryChoiceClassifier.isSentinel(null))
        assertFalse(StoryChoiceClassifier.isSentinel(""))
        assertFalse(StoryChoiceClassifier.isSentinel("   "))
        assertFalse(StoryChoiceClassifier.isSentinel("让她在温泉旅馆偶遇两人"))
    }

    /** 预设选项文本不是哨兵——它走 NEXT_CHAPTER 模式（有已答选择、但不是亲笔走向）。 */
    @Test fun isSentinel_false_for_preset_option_text() {
        assertFalse(StoryChoiceClassifier.isSentinel("去找他"))
    }

    /** 近似形（半角括号 / 多一字 / 前后带空白）都不算——判定是全等匹配，不做模糊。 */
    @Test fun isSentinel_false_for_near_misses() {
        assertFalse(StoryChoiceClassifier.isSentinel("(让故事自然发展)"))
        assertFalse(StoryChoiceClassifier.isSentinel("让故事自然发展"))
        assertFalse(StoryChoiceClassifier.isSentinel(" （让故事自然发展） "))
    }
}
