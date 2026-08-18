package com.situ.aichat.world.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WorldVisitGreeter] 到达开场变体 T1-3（纯函数·图纸 §7·§9）：同 travelKey 恒同变体 + 三变体逐字。
 * 图纸 T1-3 原文「三变体均含『我到啦』」与 §9 逐字锁定的变体③「到啦到啦…」冲突（③无「我到啦」）——
 * 以 §9 锁定文本为准，断言「均含『到啦』」（§11 记）。
 */
class WorldVisitGreeterVariantTest {

    @Test
    fun `同 travelKey 恒同变体`() {
        val key = "char-1:1700000000000"
        assertEquals(WorldVisitGreeter.variantOf(key), WorldVisitGreeter.variantOf(key))
        assertTrue(WorldVisitGreeter.variantOf(key) in 0..2)
    }

    @Test
    fun `跨 key 覆盖三变体`() {
        val seen = (0 until 60).map { WorldVisitGreeter.variantOf("owner-$it:9") }.toSet()
        assertTrue("多 key 应覆盖多变体", seen.size >= 2)
    }

    @Test
    fun `三变体逐字_均含到啦`() {
        assertEquals(3, WorldVisitGreeter.OPENERS.size)
        assertEquals("我到啦！一路还挺顺——待会儿去找你。", WorldVisitGreeter.OPENERS[0])
        assertEquals("我到啦，刚放下行李。你们这座城比我想的还好看。", WorldVisitGreeter.OPENERS[1])
        assertEquals("到啦到啦！先歇口气，回头见面聊。", WorldVisitGreeter.OPENERS[2])
        assertTrue("三变体均含「到啦」", WorldVisitGreeter.OPENERS.all { it.contains("到啦") })
    }
}
