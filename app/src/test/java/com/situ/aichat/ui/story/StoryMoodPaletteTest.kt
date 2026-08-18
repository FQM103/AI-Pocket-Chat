package com.situ.aichat.ui.story

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * T1：故事阅读器**纸面** feature token（D8·契约 §6.4）的结构 / 落值 / 确定性看门。
 *
 * **2026-08-03 心情视觉层退役**：原 11 套电影心情 token 随 `[mood:]` 正文标签整族删除，纸面恒中性双档。
 * 断言从规格独立反推：浅 / 深两档各给三段不透明渐变；两档必不同（证明是各自精调、非同一份或盲降）；
 * **色值逐字锁定**（图纸 §9-① 明令 NEUTRAL_LIGHT/NEUTRAL_DARK 六色不许动）。
 * WCAG 正文对比另由 `ColorContrastTest.storyMoodPalette_meetsWcag_bothModes` 逐 stop 双模看门。
 */
class StoryMoodPaletteTest {

    @Test
    fun `both modes yield a three-stop opaque gradient`() {
        listOf(false, true).forEach { isDark ->
            val stops = StoryMoodPalette.colors(isDark)
            assertEquals("${if (isDark) "dark" else "light"} 应为三段渐变", 3, stops.size)
            stops.forEach { assertEquals("渐变 stop 应不透明", 1f, it.alpha) }
        }
    }

    @Test
    fun `neutral paper keeps its calibrated baseline values`() {
        // 用户一直看到的那张纸（沉浸氛围开关默认关 ⇒ 这六个色值就是现状）——图纸 §9-① 锁定，逐字节不许动。
        assertEquals(Color(0xFFFAF6EF), StoryMoodPalette.colors(false)[0])
        assertEquals(Color(0xFFF4ECDD), StoryMoodPalette.colors(false)[1])
        assertEquals(Color(0xFFEFE6D3), StoryMoodPalette.colors(false)[2])
        assertEquals(Color(0xFF1A1814), StoryMoodPalette.colors(true)[0])
        assertEquals(Color(0xFF211E18), StoryMoodPalette.colors(true)[1])
        assertEquals(Color(0xFF26221B), StoryMoodPalette.colors(true)[2])
    }

    @Test
    fun `dark paper is independently tuned not a dimmed light paper`() {
        assertNotEquals(StoryMoodPalette.colors(false), StoryMoodPalette.colors(true))
        // 深档 ≠ 浅档简单降透明（后者 alpha≈0.62）——确认没有误用统一降档逻辑。
        assertNotEquals(Color(0xFFFAF6EF).copy(alpha = 0.62f), StoryMoodPalette.colors(true).first())
    }

    @Test
    fun `lookup is deterministic`() {
        assertEquals(StoryMoodPalette.colors(false), StoryMoodPalette.colors(false))
        assertEquals(StoryMoodPalette.colors(true), StoryMoodPalette.colors(true))
    }
}
