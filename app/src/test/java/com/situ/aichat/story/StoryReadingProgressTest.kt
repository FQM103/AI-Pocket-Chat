package com.situ.aichat.story

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1：阅读进度纯计算（ST7d·契约 §6.4·2026-08-03 视口底边模型）。断言从规格独立反推：
 * 到底（末项完整可见）恒 100、未到底 floor 绝不虚报 100、百分比随底边推进单调不减、边界钳位；
 * 已读块换算随起始下标平移并双端钳位；剩余分钟按 400 字/分向上取整、有字必 ≥1、读完为 0。
 */
class StoryReadingProgressTest {

    @Test fun `percent hits exactly hundred when last item fully visible`() {
        assertEquals(100, StoryReadingProgress.percent(lastVisibleIndex = 9, lastVisibleFraction = 1f, totalItems = 10))
        assertEquals(100, StoryReadingProgress.percent(lastVisibleIndex = 0, lastVisibleFraction = 1f, totalItems = 1))
    }

    @Test fun `percent never reports hundred before the very bottom`() {
        // floor 语义：差半项（9.5/10 = 95%）、差一丝（9.96/10 = 99.6%）都不许显示 100。
        assertEquals(95, StoryReadingProgress.percent(9, 0.5f, 10))
        assertEquals(99, StoryReadingProgress.percent(9, 0.96f, 10))
        assertEquals(99, StoryReadingProgress.percent(9, 0.999f, 10))
    }

    @Test fun `percent at top shows only the sliver actually seen`() {
        // 首屏只露出封面的 40%：0.4/10 = 4%。
        assertEquals(4, StoryReadingProgress.percent(0, 0.4f, 10))
        assertEquals(0, StoryReadingProgress.percent(0, 0f, 10))
    }

    @Test fun `percent degrades to zero for empty list`() {
        assertEquals(0, StoryReadingProgress.percent(0, 0.5f, 0))
        assertEquals(0, StoryReadingProgress.percent(3, 1f, -1))
    }

    @Test fun `percent clamps out-of-range index and fraction`() {
        assertEquals(0, StoryReadingProgress.percent(-3, -2f, 10))
        assertEquals(100, StoryReadingProgress.percent(99, 5f, 10))
        // NaN fraction（size=0 除法防御在桥层，这里纯函数自身也兜）→ 视为 1。
        assertEquals(100, StoryReadingProgress.percent(9, Float.NaN, 10))
    }

    @Test fun `percent is monotonic non-decreasing as bottom edge advances`() {
        val total = 20
        var prev = -1
        for (i in 0 until total) {
            for (f in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                val p = StoryReadingProgress.percent(i, f, total)
                assertTrue("下标 $i 比例 $f 百分比应不减", p >= prev)
                prev = p
            }
        }
        assertEquals(100, prev)
    }

    @Test fun `consumed body blocks shift by body start and clamp both ends`() {
        // 封面(0)未越过正文 → 0 块；越过 index1 = 第 1 块；recap 在场（起始 2）同下标少算一块。
        assertEquals(0, StoryReadingProgress.consumedBodyBlocks(lastFullyPassedIndex = 0, bodyStartIndex = 1, totalBodyBlocks = 9))
        assertEquals(1, StoryReadingProgress.consumedBodyBlocks(1, 1, 9))
        assertEquals(0, StoryReadingProgress.consumedBodyBlocks(1, 2, 9))
        assertEquals(2, StoryReadingProgress.consumedBodyBlocks(3, 2, 9))
        // 布局前（-1）→ 0；越过章末交互区（下标超正文段）→ 钳到总块数。
        assertEquals(0, StoryReadingProgress.consumedBodyBlocks(-1, 1, 9))
        assertEquals(9, StoryReadingProgress.consumedBodyBlocks(14, 1, 9))
    }

    @Test fun `remaining minutes rounds up and is at least one while unread`() {
        assertEquals(0, StoryReadingProgress.remainingMinutes(0))
        assertEquals(0, StoryReadingProgress.remainingMinutes(-50))
        assertEquals(1, StoryReadingProgress.remainingMinutes(1))
        assertEquals(1, StoryReadingProgress.remainingMinutes(StoryReadingProgress.CHARS_PER_MINUTE))
        assertEquals(2, StoryReadingProgress.remainingMinutes(StoryReadingProgress.CHARS_PER_MINUTE + 1))
        assertEquals(3, StoryReadingProgress.remainingMinutes(StoryReadingProgress.CHARS_PER_MINUTE * 2 + 40))
    }
}
