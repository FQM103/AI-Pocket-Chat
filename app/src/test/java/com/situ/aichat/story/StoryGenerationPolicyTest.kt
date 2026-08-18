package com.situ.aichat.story

import com.situ.aichat.story.StoryGenerationPolicy.OutlineAction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `StoryGenerationPolicy.decideOutlineAction` (11.1e-3) 测试：
 * 无大纲 → 起首条弧线 / 本弧写满**自报章数** → 续接新弧 / 否则不生成。
 *
 * **卷二·单模式化**：原有限模式两族用例（「无大纲 → 里程碑大纲」「自动扩展后每 +10 章补续篇弧线」）随
 * 有限模式整体退役删除属预期——decideOutlineAction 已不再接收 maxChapters/autoExtendCount。
 * **卷二 B1 变速箱**：换挡点由弧线自报（8-15，回退 12），故新增 plannedLength 维度的边界矩阵。
 */
class StoryGenerationPolicyTest {

    private fun decide(outline: String?, arcStart: Int?, chapter: Int, plannedLength: Int? = null) =
        StoryGenerationPolicy.decideOutlineAction(outline, arcStart, chapter, plannedLength)

    @Test fun no_outline_generates_initial_arc() {
        assertEquals(OutlineAction.GenerateInitialArc, decide(outline = null, arcStart = null, chapter = 1))
    }

    @Test fun empty_outline_treated_as_missing() {
        assertEquals(OutlineAction.GenerateInitialArc, decide(outline = "", arcStart = null, chapter = 5))
    }

    @Test fun no_outline_mid_story_still_generates_initial_arc() {
        // 大纲被清（取消收尾 / 老数据）后在中途重建：仍走首次生成分支，不落进续弧分支。
        assertEquals(OutlineAction.GenerateInitialArc, decide(outline = null, arcStart = 13, chapter = 21))
    }

    @Test fun no_planned_length_falls_back_to_twelve() {
        // 未自报（解析失败）→ 回退 12：arcStart=1 时第 12 章仍是本弧第 12 章 → None；第 13 章越界 → 换弧。
        assertEquals(OutlineAction.None, decide(outline = "大纲", arcStart = 1, chapter = 12))
        assertEquals(OutlineAction.GenerateNewArc, decide(outline = "大纲", arcStart = 1, chapter = 13))
    }

    @Test fun null_arcstart_defaults_to_one() {
        assertEquals(OutlineAction.None, decide(outline = "大纲", arcStart = null, chapter = 12))
        assertEquals(OutlineAction.GenerateNewArc, decide(outline = "大纲", arcStart = null, chapter = 13))
    }

    @Test fun mid_arc_boundary_with_fallback_length() {
        // arcStart=10：本弧覆盖 10..21（12 章）→ 第 21 章 None、第 22 章换弧。
        assertEquals(OutlineAction.None, decide(outline = "大纲", arcStart = 10, chapter = 21))
        assertEquals(OutlineAction.GenerateNewArc, decide(outline = "大纲", arcStart = 10, chapter = 22))
    }

    @Test fun rewrite_of_arc_start_chapter_does_not_retrigger() {
        // 重写弧线起始章（本弧第 1 章）不重复触发。
        assertEquals(OutlineAction.None, decide(outline = "弧线", arcStart = 21, chapter = 21))
    }

    // ── 卷二 B1：自报章数决定换挡点（±1 精度）──

    @Test fun short_arc_switches_earlier() {
        // 自报 8（下限）：arcStart=1 → 覆盖 1..8，第 8 章 None、第 9 章换弧。
        assertEquals(OutlineAction.None, decide(outline = "大纲", arcStart = 1, chapter = 8, plannedLength = 8))
        assertEquals(OutlineAction.GenerateNewArc, decide(outline = "大纲", arcStart = 1, chapter = 9, plannedLength = 8))
    }

    @Test fun long_arc_switches_later() {
        // 自报 15（上限）：覆盖 1..15，第 15 章 None、第 16 章换弧。
        assertEquals(OutlineAction.None, decide(outline = "大纲", arcStart = 1, chapter = 15, plannedLength = 15))
        assertEquals(OutlineAction.GenerateNewArc, decide(outline = "大纲", arcStart = 1, chapter = 16, plannedLength = 15))
    }

    @Test fun out_of_range_planned_length_is_clamped_not_honoured() {
        // 自报 30 → 钳到 15：第 16 章就换弧（若不钳位要等到第 31 章）。
        assertEquals(OutlineAction.GenerateNewArc, decide(outline = "大纲", arcStart = 1, chapter = 16, plannedLength = 30))
        // 自报 2 → 钳到 8：第 8 章还不换（若不钳位第 3 章就换了）。
        assertEquals(OutlineAction.None, decide(outline = "大纲", arcStart = 1, chapter = 8, plannedLength = 2))
        assertEquals(OutlineAction.GenerateNewArc, decide(outline = "大纲", arcStart = 1, chapter = 9, plannedLength = 2))
    }

    @Test fun planned_length_applies_to_mid_story_arcs_too() {
        // arcStart=41、自报 10 → 覆盖 41..50：第 50 章 None、第 51 章换弧。
        assertEquals(OutlineAction.None, decide(outline = "大纲", arcStart = 41, chapter = 50, plannedLength = 10))
        assertEquals(OutlineAction.GenerateNewArc, decide(outline = "大纲", arcStart = 41, chapter = 51, plannedLength = 10))
    }
}
