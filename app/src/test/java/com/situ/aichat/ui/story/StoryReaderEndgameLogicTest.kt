package com.situ.aichat.ui.story

import com.situ.aichat.story.StoryStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StoryReaderEndgameLogic T1（ST11·图纸 §3.4 三条布尔式全矩阵）。
 *
 * 期望**从图纸 §3.4 的规格文字独立反推**，不照抄实现：每例先问「用户此刻该不该看到这个东西」，再落断言。
 * 矩阵轴：书状态（连载/等选/暂停/失败/完结）× 是否末章 × 有无选项 × 是否已答 × 有无 AI 印。
 */
class StoryReaderEndgameLogicTest {

    private val allStatuses = listOf(
        StoryStatus.SERIALIZING,
        StoryStatus.WAITING_CHOICE,
        StoryStatus.PAUSED,
        StoryStatus.GENERATION_FAILED,
        StoryStatus.GENERATING,
        StoryStatus.COMPLETED,
    )

    // ── showContinueZone ──

    @Test
    fun 推进区_末章无选项非完结_显示() {
        // 本卷要治的断头路：AI 没给选项的末章，正文后一片空白、只能翻顶栏菜单。
        assertTrue(
            StoryReaderEndgameLogic.showContinueZone(
                isLatestChapter = true, storyStatus = StoryStatus.SERIALIZING,
                hasChoice = false, userChoice = null,
            ),
        )
    }

    @Test
    fun 推进区_末章选择已答_显示() {
        // 已答 → 方向盘不在选择区手里了 → 推进区接管（用户可以再补一句自由输入）。
        assertTrue(
            StoryReaderEndgameLogic.showContinueZone(
                isLatestChapter = true, storyStatus = StoryStatus.SERIALIZING,
                hasChoice = true, userChoice = "选项A",
            ),
        )
    }

    @Test
    fun 推进区_末章有未答选择_避让不显示() {
        // 此刻方向盘在选择区手里，两个推进入口并排会让用户懵。
        assertFalse(
            StoryReaderEndgameLogic.showContinueZone(
                isLatestChapter = true, storyStatus = StoryStatus.WAITING_CHOICE,
                hasChoice = true, userChoice = null,
            ),
        )
    }

    @Test
    fun 推进区_非末章_一律不显示() {
        // 翻回旧章不给方向盘：那些章后面已经有下文了。四种组合全扫。
        for (hasChoice in listOf(false, true)) {
            for (userChoice in listOf(null, "选项A")) {
                assertFalse(
                    "非末章不许出推进区（hasChoice=$hasChoice userChoice=$userChoice）",
                    StoryReaderEndgameLogic.showContinueZone(
                        isLatestChapter = false, storyStatus = StoryStatus.SERIALIZING,
                        hasChoice = hasChoice, userChoice = userChoice,
                    ),
                )
            }
        }
    }

    @Test
    fun 推进区_已完结书_一律不显示() {
        // 完结的书没有「接下来」（要续写走档案详情/设置页的续写入口）。
        for (hasChoice in listOf(false, true)) {
            for (userChoice in listOf(null, "选项A")) {
                assertFalse(
                    "完结书不许出推进区（hasChoice=$hasChoice userChoice=$userChoice）",
                    StoryReaderEndgameLogic.showContinueZone(
                        isLatestChapter = true, storyStatus = StoryStatus.COMPLETED,
                        hasChoice = hasChoice, userChoice = userChoice,
                    ),
                )
            }
        }
    }

    @Test
    fun 推进区_暂停与失败态照显_书没死就有方向盘() {
        // E1/E5：暂停书上的推进动作会自动复活连载；失败态更需要方向盘（不然只能干瞪眼）。
        for (status in listOf(StoryStatus.PAUSED, StoryStatus.GENERATION_FAILED)) {
            assertTrue(
                "$status 的末章应显示推进区",
                StoryReaderEndgameLogic.showContinueZone(
                    isLatestChapter = true, storyStatus = status,
                    hasChoice = false, userChoice = null,
                ),
            )
        }
    }

    @Test
    fun 推进区_非完结全状态_末章无待答选择均显示() {
        // 「非完结」是唯一的状态门（生成中由既有 GenerationOverlay 遮罩盖住，不在本函数加条件·E3）。
        for (status in allStatuses.filter { it != StoryStatus.COMPLETED }) {
            assertTrue(
                "$status 应显示推进区",
                StoryReaderEndgameLogic.showContinueZone(
                    isLatestChapter = true, storyStatus = status,
                    hasChoice = false, userChoice = null,
                ),
            )
        }
    }

    // ── showEndingSuggestCard ──

    @Test
    fun 建议卡_末章有印非完结_显示() {
        assertTrue(
            StoryReaderEndgameLogic.showEndingSuggestCard(
                isLatestChapter = true, storyStatus = StoryStatus.SERIALIZING, aiSuggestedEnding = true,
            ),
        )
    }

    @Test
    fun 建议卡_无印_不显示() {
        // AI 没说完结就别替它说。
        assertFalse(
            StoryReaderEndgameLogic.showEndingSuggestCard(
                isLatestChapter = true, storyStatus = StoryStatus.SERIALIZING, aiSuggestedEnding = false,
            ),
        )
    }

    @Test
    fun 建议卡_非末章有印_不显示_卡自然消失机制() {
        // §3.2 不做 dismiss 存储：用户继续写出新章 → 旧章非末章 → 卡自然不见。本例即该机制的看门狗。
        assertFalse(
            StoryReaderEndgameLogic.showEndingSuggestCard(
                isLatestChapter = false, storyStatus = StoryStatus.SERIALIZING, aiSuggestedEnding = true,
            ),
        )
    }

    @Test
    fun 建议卡_已完结书有印_不显示() {
        // 已经盖过章了，不该再问一次「要不要完结」。
        assertFalse(
            StoryReaderEndgameLogic.showEndingSuggestCard(
                isLatestChapter = true, storyStatus = StoryStatus.COMPLETED, aiSuggestedEnding = true,
            ),
        )
    }

    @Test
    fun 建议卡_非完结全状态_末章有印均显示() {
        for (status in allStatuses.filter { it != StoryStatus.COMPLETED }) {
            assertTrue(
                "$status 的末章有印应显示建议卡",
                StoryReaderEndgameLogic.showEndingSuggestCard(
                    isLatestChapter = true, storyStatus = status, aiSuggestedEnding = true,
                ),
            )
        }
    }

    // ── showChoiceSection（既有 ST10-4 完结门的镜像·回归钉） ──

    @Test
    fun 选择区_有选项未答且未完结_显示() {
        assertTrue(
            StoryReaderEndgameLogic.showChoiceSection(
                hasChoice = true, userChoice = null, storyStatus = StoryStatus.WAITING_CHOICE,
            ),
        )
    }

    @Test
    fun 选择区_完结书未答选择_不显示_幽灵选择防御() {
        // ST10-4 完结门：历史脏数据里的幽灵选择可点，一点会把书从已完结拉回连载中。
        assertFalse(
            StoryReaderEndgameLogic.showChoiceSection(
                hasChoice = true, userChoice = null, storyStatus = StoryStatus.COMPLETED,
            ),
        )
    }

    @Test
    fun 选择区_完结书已答选择_仍显示_保留阅读回顾() {
        // 已答的选择是历史记录，完结后仍该看得到「当时你选了什么」。
        assertTrue(
            StoryReaderEndgameLogic.showChoiceSection(
                hasChoice = true, userChoice = "选项A", storyStatus = StoryStatus.COMPLETED,
            ),
        )
    }

    @Test
    fun 选择区_无选项_一律不显示() {
        for (status in allStatuses) {
            assertFalse(
                "$status 无选项不该出选择区",
                StoryReaderEndgameLogic.showChoiceSection(
                    hasChoice = false, userChoice = null, storyStatus = status,
                ),
            )
        }
    }

    // ── 三者的咬合（同屏共存关系） ──

    @Test
    fun 矛盾输出_建议卡与选择区并存且推进区避让() {
        // §3.4/E8：AI 既说完结又给选项 → 书 waitingChoice、选项保留、章上有印。
        // 建议卡 + 选择区同屏各自工作；推进区因有未答选择而避让。
        val latest = true
        val status = StoryStatus.WAITING_CHOICE
        assertTrue(
            "建议卡应显示",
            StoryReaderEndgameLogic.showEndingSuggestCard(latest, status, aiSuggestedEnding = true),
        )
        assertTrue(
            "选择区应显示",
            StoryReaderEndgameLogic.showChoiceSection(hasChoice = true, userChoice = null, storyStatus = status),
        )
        assertFalse(
            "推进区应避让",
            StoryReaderEndgameLogic.showContinueZone(latest, status, hasChoice = true, userChoice = null),
        )
    }

    @Test
    fun 用户答完矛盾输出的选择_推进区接管_建议卡仍在() {
        val latest = true
        val status = StoryStatus.SERIALIZING
        assertTrue(
            "答完后推进区接管",
            StoryReaderEndgameLogic.showContinueZone(latest, status, hasChoice = true, userChoice = "选项A"),
        )
        assertTrue(
            "建议卡不因答选择而消失（只随「不再是末章」消失）",
            StoryReaderEndgameLogic.showEndingSuggestCard(latest, status, aiSuggestedEnding = true),
        )
    }

    @Test
    fun 盖章完结后_三者全灭() {
        // §3.5：完结成功 → story flow 变 COMPLETED → 卡/推进区自然消失，零手工导航。
        val status = StoryStatus.COMPLETED
        assertEquals(false, StoryReaderEndgameLogic.showContinueZone(true, status, hasChoice = false, userChoice = null))
        assertEquals(false, StoryReaderEndgameLogic.showEndingSuggestCard(true, status, aiSuggestedEnding = true))
        assertEquals(
            false,
            StoryReaderEndgameLogic.showChoiceSection(hasChoice = true, userChoice = null, storyStatus = status),
        )
    }

    // ── 卷二 §4.5：「准备收尾」金胶囊 ↔「收尾中」chip 的同槽位互斥 ──

    /**
     * 期望从图纸 §4.5 的两条布尔式独立反推：两者都是**推进区槽位内容**，可见性完全跟随
     * [StoryReaderEndgameLogic.showContinueZone]，只按 finalePlanned 二选一——**恒不同时出现，也恒不同时缺席**
     * （只要推进区在）。全输入空间扫一遍。
     */
    @Test
    fun 收尾胶囊与状态chip_同槽位恒互斥且跟随推进区() {
        for (isLatest in listOf(false, true)) {
            for (status in listOf(StoryStatus.SERIALIZING, StoryStatus.PAUSED, StoryStatus.GENERATION_FAILED, StoryStatus.COMPLETED)) {
                for (hasChoice in listOf(false, true)) {
                    for (userChoice in listOf(null, "选项A")) {
                        for (planned in listOf(false, true)) {
                            val zone = StoryReaderEndgameLogic.showContinueZone(isLatest, status, hasChoice, userChoice)
                            val pill = StoryReaderEndgameLogic.showFinalePill(isLatest, status, hasChoice, userChoice, planned)
                            val chip = StoryReaderEndgameLogic.showFinaleChip(isLatest, status, hasChoice, userChoice, planned)
                            val ctx = "latest=$isLatest status=$status hasChoice=$hasChoice choice=$userChoice planned=$planned"
                            assertEquals("胶囊与 chip 绝不同时出现（$ctx）", false, pill && chip)
                            assertEquals("推进区在就必有其一（$ctx）", zone, pill || chip)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun 未计划收尾时显示金胶囊_已计划时换成chip() {
        val latest = true
        val status = StoryStatus.SERIALIZING
        assertTrue(StoryReaderEndgameLogic.showFinalePill(latest, status, hasChoice = false, userChoice = null, finalePlanned = false))
        assertEquals(false, StoryReaderEndgameLogic.showFinaleChip(latest, status, hasChoice = false, userChoice = null, finalePlanned = false))

        assertTrue(StoryReaderEndgameLogic.showFinaleChip(latest, status, hasChoice = false, userChoice = null, finalePlanned = true))
        assertEquals(false, StoryReaderEndgameLogic.showFinalePill(latest, status, hasChoice = false, userChoice = null, finalePlanned = true))
    }

    /** E12：已完结的书没有收尾入口——胶囊与 chip 都灭（由推进区的 COMPLETED 门天然兜住，不另写判据）。 */
    @Test
    fun 已完结的书_收尾入口两态皆灭() {
        val status = StoryStatus.COMPLETED
        for (planned in listOf(false, true)) {
            assertEquals(false, StoryReaderEndgameLogic.showFinalePill(true, status, hasChoice = false, userChoice = null, finalePlanned = planned))
            assertEquals(false, StoryReaderEndgameLogic.showFinaleChip(true, status, hasChoice = false, userChoice = null, finalePlanned = planned))
        }
    }

    /** 有未答选择时方向盘在选择区手里——收尾入口同样避让（跟随推进区的避让口径）。 */
    @Test
    fun 有未答选择时_收尾入口一并避让() {
        val status = StoryStatus.SERIALIZING
        for (planned in listOf(false, true)) {
            assertEquals(false, StoryReaderEndgameLogic.showFinalePill(true, status, hasChoice = true, userChoice = null, finalePlanned = planned))
            assertEquals(false, StoryReaderEndgameLogic.showFinaleChip(true, status, hasChoice = true, userChoice = null, finalePlanned = planned))
        }
    }

    /** 翻回旧章不给收尾入口（跟随推进区的末章门）。 */
    @Test
    fun 非末章_收尾入口两态皆灭() {
        val status = StoryStatus.SERIALIZING
        for (planned in listOf(false, true)) {
            assertEquals(false, StoryReaderEndgameLogic.showFinalePill(false, status, hasChoice = false, userChoice = null, finalePlanned = planned))
            assertEquals(false, StoryReaderEndgameLogic.showFinaleChip(false, status, hasChoice = false, userChoice = null, finalePlanned = planned))
        }
    }

    // ── 卷三 §3.3：快评行（showChapterRating） ──

    /**
     * 期望从卷三 §3.3/§3.2 的规格文字独立反推：「评的是**刚读完的这一章**的观感」——
     * 故只在末章、且真有一章可评时出现；书完不完结与「这一章好不好看」无关，照常可评。
     */
    @Test
    fun 快评行_末章且有章_显示() {
        assertTrue(StoryReaderEndgameLogic.showChapterRating(isLatestChapter = true, chapterExists = true))
    }

    /** E3：翻回历史章不出快评——本卷有意不做历史章回填（§0.③）。 */
    @Test
    fun E3_快评行_非末章_不显示() {
        for (exists in listOf(false, true)) {
            assertEquals(
                "非末章不许出快评行（chapterExists=$exists）",
                false,
                StoryReaderEndgameLogic.showChapterRating(isLatestChapter = false, chapterExists = exists),
            )
        }
    }

    /** E3：没有章（生成中占位 / 空书）就没有可评对象。 */
    @Test
    fun E3_快评行_无章_不显示() {
        assertEquals(false, StoryReaderEndgameLogic.showChapterRating(isLatestChapter = true, chapterExists = false))
    }

    /**
     * 与推进区/建议卡的**分野钉**：完结的书三者全灭（见 [盖章完结后_三者全灭]），但快评**照常出现**——
     * 它不是「接下来怎么写」的入口，而是对已读内容的观感反馈。写成对照断言防日后被顺手加上 COMPLETED 门。
     */
    @Test
    fun 快评行_已完结的书照常可评_与推进区分野() {
        assertTrue(
            "完结书的末章仍可评",
            StoryReaderEndgameLogic.showChapterRating(isLatestChapter = true, chapterExists = true),
        )
        assertEquals(
            "同一情形下推进区不出（对照）",
            false,
            StoryReaderEndgameLogic.showContinueZone(true, StoryStatus.COMPLETED, hasChoice = false, userChoice = null),
        )
    }

    // ── 卷三 §3.3：本章操作行（showChapterActions） ──

    /** E4：三个动作一个都不可用时整行不出（不留空壳发丝线）。 */
    @Test
    fun E4_本章操作行_三动作全不可用_不显示() {
        assertEquals(
            false,
            StoryReaderEndgameLogic.showChapterActions(
                canRewrite = false, canViewPreviousDraft = false, canEditSummary = false,
            ),
        )
    }

    /** 只要任一动作可用就出行——八组合全扫（「至少一个」的真值表）。 */
    @Test
    fun 本章操作行_任一动作可用即显示_全组合() {
        for (rewrite in listOf(false, true)) {
            for (prev in listOf(false, true)) {
                for (summary in listOf(false, true)) {
                    val expected = rewrite || prev || summary
                    assertEquals(
                        "rewrite=$rewrite prev=$prev summary=$summary",
                        expected,
                        StoryReaderEndgameLogic.showChapterActions(rewrite, prev, summary),
                    )
                }
            }
        }
    }

    /**
     * 现状取值口径的回归钉：`canEditSummary` 恒 true（§3.3 明写「现状恒 true」·历史章也能改小结）
     * ⇒ 只要它还是 true，本行在任何章上都出得来。日后若把小结编辑也加门，本例会红，提醒同步复核 E4。
     */
    @Test
    fun 本章操作行_编辑小结恒可用时_行恒显示() {
        for (rewrite in listOf(false, true)) {
            for (prev in listOf(false, true)) {
                assertTrue(
                    "canEditSummary=true 时行恒显示（rewrite=$rewrite prev=$prev）",
                    StoryReaderEndgameLogic.showChapterActions(rewrite, prev, canEditSummary = true),
                )
            }
        }
    }

    // ── continueZoneMode（图纸 2026-08-06「已存走向」§3.2·T1-2）──
    //
    // 期望从 §3.2 规格独立反推：先问「用户此刻在末章看到的应该是哪一套」，再落断言。
    // - 什么都没答 → 现状那一套（「让故事自然发展」不说反话，因为确实还没定走向）；
    // - 已答但不是亲笔（点了选项 / 生成失败留下的哨兵）→ 方向早定了，按钮该说「继续写下一章」；
    // - 亲笔走向 → 该把那条走向摆出来 + 按钮说「按走向继续写」。

    @Test
    fun 推进区模式_没答过_自然发展() { // E1
        assertEquals(
            ContinueZoneMode.NATURAL_FLOW,
            StoryReaderEndgameLogic.continueZoneMode(userChoice = null, freeformDirective = null),
        )
    }

    /** 空串 / 纯空白 = 没答（历史脏数据也按「没答」看，不该冒出个空走向卡）。 */
    @Test
    fun 推进区模式_空串与纯空白_按没答算() { // E1
        for (blank in listOf("", "   ", "\n\t ")) {
            assertEquals(
                "userChoice=<$blank> 应视作没答",
                ContinueZoneMode.NATURAL_FLOW,
                StoryReaderEndgameLogic.continueZoneMode(userChoice = blank, freeformDirective = null),
            )
        }
    }

    /**
     * 哨兵残留（「让故事自然发展」提交后生成失败）：freeform 判定对哨兵返 null ⇒ 落 NEXT_CHAPTER。
     * 这两个值在此**重新打字**，不引用实现常量——防「实现改了常量、测试跟着改、规格没人守」。
     */
    @Test
    fun 推进区模式_哨兵已存_继续写下一章() { // E5
        for (sentinel in listOf("（让故事自然发展）", "（跳过选择，直接进入结局）")) {
            assertEquals(
                "哨兵=<$sentinel>",
                ContinueZoneMode.NEXT_CHAPTER,
                StoryReaderEndgameLogic.continueZoneMode(userChoice = sentinel, freeformDirective = null),
            )
        }
    }

    /** 选项点选：userChoice 是选项原文 ⇒ freeform 为 null ⇒ NEXT_CHAPTER（走向卡不出，选择区已有「已选择：」行）。 */
    @Test
    fun 推进区模式_选项点选_继续写下一章() { // E4
        assertEquals(
            ContinueZoneMode.NEXT_CHAPTER,
            StoryReaderEndgameLogic.continueZoneMode(userChoice = "去找他", freeformDirective = null),
        )
    }

    /** 亲笔走向：freeform 非空 ⇒ BY_DIRECTION（走向卡 + 「按走向继续写」实底胶囊 + 输入卡让位）。 */
    @Test
    fun 推进区模式_亲笔走向_按走向继续写() { // E2
        assertEquals(
            ContinueZoneMode.BY_DIRECTION,
            StoryReaderEndgameLogic.continueZoneMode(
                userChoice = "让她在温泉旅馆偶遇两人",
                freeformDirective = "让她在温泉旅馆偶遇两人",
            ),
        )
    }

    /** 优先序钉：freeform 非空时压过 userChoice 的一切形态（含理论上不可能的 userChoice=null 组合）。 */
    @Test
    fun 推进区模式_freeform非空恒压过userChoice() {
        for (choice in listOf(null, "", "   ", "去找他", "（让故事自然发展）")) {
            assertEquals(
                "userChoice=<$choice> 时 freeform 仍该胜出",
                ContinueZoneMode.BY_DIRECTION,
                StoryReaderEndgameLogic.continueZoneMode(userChoice = choice, freeformDirective = "我要的走向"),
            )
        }
    }
}
