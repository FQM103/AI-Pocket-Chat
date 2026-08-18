package com.situ.aichat.ui.offline

import com.situ.aichat.offline.OfflineContentBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D1 阅读驱动揭示节奏 T1（断言从规格独立反推：首块起手 250ms；第 i 块 = 前块延迟 + 前块阅读时长；
 * 台词块前多 250ms；阅读时长 = 300+字数×45 钳位 [500,2200]；过渡装饰恒 450ms）。
 */
class OfflineRevealPacingTest {

    @Test
    fun first_block_starts_at_fixed_lead_in() {
        val delays = OfflineRevealPacing.revealDelays(listOf(OfflineContentBlock.Narration("随便多长都一样")))
        assertEquals(listOf(250L), delays)
    }

    @Test
    fun next_block_waits_for_previous_reading_time() {
        // 前块 4 字：300+4×45=480 → 钳位下限 500；第二块 = 250+500。
        val delays = OfflineRevealPacing.revealDelays(
            listOf(OfflineContentBlock.Narration("四个字啊"), OfflineContentBlock.Environment("风")),
        )
        assertEquals(listOf(250L, 750L), delays)
    }

    @Test
    fun reading_time_clamped_between_min_and_max() {
        // 2 字 → 390 → 500（下限）；60 字 → 300+2700=3000 → 2200（上限）。
        assertEquals(500L, OfflineRevealPacing.readTimeMs(OfflineContentBlock.Narration("嗯嗯")))
        assertEquals(2200L, OfflineRevealPacing.readTimeMs(OfflineContentBlock.Narration("字".repeat(60))))
    }

    @Test
    fun dialogue_gets_extra_pause_but_not_when_first() {
        // 首块是台词：不加停顿（起手 250 已是呼吸拍）。
        val first = OfflineRevealPacing.revealDelays(listOf(OfflineContentBlock.CharacterDialogue("你来了")))
        assertEquals(listOf(250L), first)
        // 台词在后：250 + read(前块 10 字=300+450=750) + 台词停顿 250 = 1250。
        val delays = OfflineRevealPacing.revealDelays(
            listOf(OfflineContentBlock.Narration("正好十个字的一句叙述"), OfflineContentBlock.CharacterDialogue("走吧")),
        )
        assertEquals(listOf(250L, 1250L), delays)
    }

    @Test
    fun scene_transition_reads_fast_and_scene_header_uses_text() {
        assertEquals(450L, OfflineRevealPacing.readTimeMs(OfflineContentBlock.SceneTransition))
        // 场景标题：地点+时间共 5 字 → 300+225=525。
        assertEquals(525L, OfflineRevealPacing.readTimeMs(OfflineContentBlock.SceneHeader("便利店", "晚上")))
    }

    @Test
    fun delays_are_monotonic_and_prefix_stable_for_streaming() {
        val partial = listOf(
            OfflineContentBlock.Environment("很安静的一条街"),
            OfflineContentBlock.Narration("你们慢慢走着"),
        )
        val full = partial + listOf(OfflineContentBlock.CharacterDialogue("在想什么？"))
        val partialDelays = OfflineRevealPacing.revealDelays(partial)
        val fullDelays = OfflineRevealPacing.revealDelays(full)
        // 流式追加块后前缀延迟不变（块只依赖它前面的块）。
        assertEquals(partialDelays, fullDelays.subList(0, partialDelays.size))
        // 单调不减。
        assertTrue(fullDelays.zipWithNext().all { (a, b) -> b >= a })
    }
}
