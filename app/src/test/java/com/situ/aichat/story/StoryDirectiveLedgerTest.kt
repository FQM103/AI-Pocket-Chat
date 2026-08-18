package com.situ.aichat.story

import com.situ.aichat.data.local.entity.StoryChapterEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 弧线大纲导演手记重构（图纸 `2026-08-05-弧线大纲导演手记重构.md` §7 T1-1 / T1-2）的纯函数矩阵：
 *
 * - **T1-1**：[StoryChoiceClassifier.freeformDirective] 删掉 `hasChoice` 门之后的判定面（E3/E4/E5/E10），
 *   外加 [StoryChoiceClassifier.presetChoiceForRecap] 的行为回归钉（它**没改**，只是别被顺手改坏）。
 * - **T1-2**：[StoryChoiceClassifier.buildDirectiveLedger] 的过滤 / 排除 / 行格式 / 边界（E7/E8/E9）。
 * 期望值一律从图纸规格独立反推、逐字重新打字（不引用实现常量拼装行格式）。
 */
class StoryDirectiveLedgerTest {

    private val C = StoryChoiceClassifier

    private fun chapter(
        number: Int = 9,
        hasChoice: Boolean = true,
        userChoice: String? = null,
        choiceOptions: String? = null,
    ) = StoryChapterEntity(
        chapterNumber = number,
        hasChoice = hasChoice,
        userChoice = userChoice,
        choiceOptions = choiceOptions,
    )

    private val options = """["主动打招呼","假装没看见"]"""

    // ══ T1-1：判定面（hasChoice 门已删）══

    @Test fun T1_1_关选项书亲笔走向也判freeform_E3() {
        // D3 关选项的书：新章恒 hasChoice=false、choiceOptions 为 null——旧实现在这里恒判 null（缺陷）
        val chapter = chapter(hasChoice = false, userChoice = "去码头找他", choiceOptions = null)
        assertEquals("去码头找他", C.freeformDirective(chapter))
    }

    @Test fun T1_1_开选项书亲笔走向照旧判freeform() {
        assertEquals("去码头找他", C.freeformDirective(chapter(userChoice = "去码头找他", choiceOptions = options)))
    }

    @Test fun T1_1_两哨兵与空值一律null_E4_E5() {
        assertNull("自然发展哨兵不是走向", C.freeformDirective(chapter(userChoice = "（让故事自然发展）")))
        assertNull("跳过收尾哨兵不是走向", C.freeformDirective(chapter(userChoice = "（跳过选择，直接进入结局）")))
        // 关选项书路径下两哨兵同样不许被门放宽后误判成走向
        assertNull(C.freeformDirective(chapter(hasChoice = false, userChoice = "（让故事自然发展）")))
        assertNull(C.freeformDirective(chapter(hasChoice = false, userChoice = "（跳过选择，直接进入结局）")))
        assertNull("追更未选", C.freeformDirective(chapter(userChoice = null)))
        assertNull("空白选择", C.freeformDirective(chapter(userChoice = "   ")))
        assertNull("整章缺席", C.freeformDirective(null))
        assertNull("命中预设选项", C.freeformDirective(chapter(userChoice = "主动打招呼", choiceOptions = options)))
        assertNull("命中预设选项", C.freeformDirective(chapter(userChoice = "假装没看见", choiceOptions = options)))
    }

    @Test fun T1_1_选项JSON损坏时非哨兵输入判freeform_E10() {
        val broken = chapter(userChoice = "主动打招呼", choiceOptions = "[不是合法 JSON")
        assertEquals("解码失败 → 空选项列表 → 归 freeform 处理", "主动打招呼", C.freeformDirective(broken))
    }

    @Test fun T1_1_presetChoiceForRecap行为回归钉() {
        // 命中选项 → 原文；自由输入 → null（两者互为补集，末句绝不双发）
        assertEquals("主动打招呼", C.presetChoiceForRecap(chapter(userChoice = "主动打招呼", choiceOptions = options)))
        assertNull(C.presetChoiceForRecap(chapter(userChoice = "去码头找他", choiceOptions = options)))
        assertNull(C.presetChoiceForRecap(chapter(userChoice = "（让故事自然发展）", choiceOptions = options)))
        assertNull(C.presetChoiceForRecap(chapter(userChoice = null)))
        assertNull(C.presetChoiceForRecap(null))
        // 本函数的 hasChoice 门有意保留（零碰）：预设点选路恒 hasChoice=true，行为不受 J2 影响
        assertNull(C.presetChoiceForRecap(chapter(hasChoice = false, userChoice = "主动打招呼", choiceOptions = options)))
    }

    // ══ T1-2：方向账本 ══

    @Test fun T1_2_空列表与无亲笔走向都返回null_E7() {
        assertNull(C.buildDirectiveLedger(emptyList(), arcStartChapter = 1, excludeChapterNumber = null))
        val noneFreeform = listOf(
            chapter(number = 1, userChoice = "主动打招呼", choiceOptions = options),
            chapter(number = 2, userChoice = "（让故事自然发展）"),
            chapter(number = 3, userChoice = null),
        )
        assertNull(C.buildDirectiveLedger(noneFreeform, arcStartChapter = 1, excludeChapterNumber = null))
    }

    @Test fun T1_2_行格式与升序逐字() {
        val chapters = listOf(
            chapter(number = 5, userChoice = "先去码头"),
            chapter(number = 6, userChoice = "主动打招呼", choiceOptions = options), // 预设点选不入账
            chapter(number = 7, userChoice = "把信烧掉"),
        )
        assertEquals(
            "- 第5章时指定：「先去码头」\n- 第7章时指定：「把信烧掉」",
            C.buildDirectiveLedger(chapters, arcStartChapter = 5, excludeChapterNumber = null),
        )
    }

    @Test fun T1_2_弧起点过滤正负一精度_E8() {
        val chapters = listOf(
            chapter(number = 4, userChoice = "弧起点前一章的走向"),
            chapter(number = 5, userChoice = "弧起点当章的走向"),
        )
        assertEquals(
            "arcStart-1 不入账本（换弧简史已吸收），arcStart 恰入",
            "- 第5章时指定：「弧起点当章的走向」",
            C.buildDirectiveLedger(chapters, arcStartChapter = 5, excludeChapterNumber = null),
        )
    }

    @Test fun T1_2_排除最新章() {
        val chapters = listOf(
            chapter(number = 5, userChoice = "先去码头"),
            chapter(number = 9, userChoice = "最新一章的走向"),
        )
        assertEquals(
            "最新一章有三明治专座，账本不重复登记",
            "- 第5章时指定：「先去码头」",
            C.buildDirectiveLedger(chapters, arcStartChapter = 5, excludeChapterNumber = 9),
        )
        // 排除章号为 null（无最新章）时全量入账
        assertEquals(
            "- 第5章时指定：「先去码头」\n- 第9章时指定：「最新一章的走向」",
            C.buildDirectiveLedger(chapters, arcStartChapter = 5, excludeChapterNumber = null),
        )
    }

    @Test fun T1_2_弧起点为null时视为第一章() {
        val chapters = listOf(
            chapter(number = 1, userChoice = "第一章的走向"),
            chapter(number = 2, userChoice = "第二章的走向"),
        )
        assertEquals(
            "- 第1章时指定：「第一章的走向」\n- 第2章时指定：「第二章的走向」",
            C.buildDirectiveLedger(chapters, arcStartChapter = null, excludeChapterNumber = null),
        )
    }

    @Test fun T1_2_超长走向原文直通不截断_E9() {
        val long = "他".repeat(500)
        val ledger = C.buildDirectiveLedger(
            listOf(chapter(number = 3, userChoice = long)),
            arcStartChapter = 1,
            excludeChapterNumber = null,
        )
        assertEquals("- 第3章时指定：「$long」", ledger)
    }

    @Test fun T1_2_关选项书的历史走向同样入账() {
        // J2 的连带效果：关选项书每一章都 hasChoice=false，账本必须照样收得到
        val chapters = listOf(
            chapter(number = 3, hasChoice = false, userChoice = "先去码头"),
            chapter(number = 4, hasChoice = false, userChoice = "把信烧掉"),
        )
        assertEquals(
            "- 第3章时指定：「先去码头」\n- 第4章时指定：「把信烧掉」",
            C.buildDirectiveLedger(chapters, arcStartChapter = 3, excludeChapterNumber = null),
        )
    }
}
