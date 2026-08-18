package com.situ.aichat.world.cast

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WorldFirstMeetService.fallbackOpener] T1-4（纯函数·图纸 §7·§9）：初遇兜底开场非空、含原住民名（招募绝不被 LLM 阻塞·E5）。
 */
class WorldFirstMeetOpenerTest {

    @Test
    fun `兜底开场非空且含名`() {
        val opener = WorldFirstMeetService.fallbackOpener("苏晚")
        assertTrue("非空", opener.isNotBlank())
        assertTrue("含原住民名", opener.contains("苏晚"))
    }

    @Test
    fun `兜底开场逐字_§9`() {
        assertTrue(WorldFirstMeetService.fallbackOpener("阿哲") == "你好呀，我是阿哲。早就注意到你了——今天总算说上话。")
    }
}
