package com.situ.aichat.story

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「上回说到」判定 T1（卷三 C3·图纸 §5 E4/E5/E6）——断言从图纸规格**独立反推**：
 * 「隔 ≥12 小时回来、当前不是首章、上一章有既有梗概」三条同时成立才展开。
 */
class StoryRecapLogicTest {

    private val hour = 3_600_000L
    private val now = 1_700_000_000_000L

    private fun show(
        chapterNumber: Int = 5,
        lastReadAt: Long? = now - 13 * hour,
        nowMillis: Long = now,
        previousSummaryBlank: Boolean = false,
    ) = StoryRecapLogic.showRecap(chapterNumber, lastReadAt, nowMillis, previousSummaryBlank)

    @Test fun 阈值是12小时() {
        // 图纸 §9 锁定值：12 小时 = 43_200_000 毫秒（此处重新打字，不引用实现算式）。
        assertEquals(12L * 60 * 60 * 1000, StoryRecapLogic.RECAP_THRESHOLD_MS)
    }

    @Test fun 隔一夜回来_展开() {
        assertTrue(show(lastReadAt = now - 13 * hour))
    }

    @Test fun 恰12小时边界_取等展开_差1毫秒不展开() {
        val threshold = StoryRecapLogic.RECAP_THRESHOLD_MS
        assertTrue("恰 12h（取等）", show(lastReadAt = now - threshold))
        assertFalse("差 1ms", show(lastReadAt = now - threshold + 1))
        assertTrue("多 1ms", show(lastReadAt = now - threshold - 1))
    }

    @Test fun 刚刚才读过_不展开() {
        assertFalse(show(lastReadAt = now - 10 * 60 * 1000))
        assertFalse("同一刻", show(lastReadAt = now))
    }

    @Test fun 老用户首次_没有时间戳_不展开() {
        // E4：本次不弹，本次进入写入时间戳，下次回访才生效。
        assertFalse(show(lastReadAt = null))
    }

    @Test fun 首章没有上一章_恒不展开() {
        assertFalse("第 1 章", show(chapterNumber = 1))
        assertFalse("异常章号 0", show(chapterNumber = 0))
        assertFalse("负章号", show(chapterNumber = -3))
    }

    @Test fun 上一章摘要空_不展开() {
        // E6：只读既有 chapterSummary，没有就不出（绝不为它现场调 LLM）。
        assertFalse(show(previousSummaryBlank = true))
    }

    @Test fun 三条件互不代偿() {
        // 任一条不成立即 false，哪怕其余两条极端成立。
        assertFalse(show(chapterNumber = 1, lastReadAt = now - 300 * hour))
        assertFalse(show(previousSummaryBlank = true, lastReadAt = now - 300 * hour))
        assertFalse(show(lastReadAt = null, chapterNumber = 99))
    }

    @Test fun 系统时钟回拨_不误弹() {
        // now 早于 lastReadAt（用户改过系统时间）→ 差值为负，不达阈值。
        assertFalse(show(lastReadAt = now + 5 * hour))
    }
}
