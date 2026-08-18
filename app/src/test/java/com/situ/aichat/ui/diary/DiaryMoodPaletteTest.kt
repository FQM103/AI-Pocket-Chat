package com.situ.aichat.ui.diary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1：日记 12 心情 → 5 情绪原型归并的完整性看门（契约 FABLE5_DIARY_REDESIGN_PROPOSAL §1 手法3）。
 * 断言从规格独立反推：12 个官方心情（[DIARY_MOODS]）一个都不许漏染；未知/空输入必须优雅返回 null（不染色）。
 */
class DiaryMoodPaletteTest {

    @Test
    fun `all twelve moods map to a tone`() {
        DIARY_MOODS.forEach { mood ->
            assertNotNull("心情 ${mood.emoji} 未映射到情绪原型（新增/改动心情须同步 diaryMoodTone）", diaryMoodTone(mood.emoji))
        }
    }

    @Test
    fun `every tone is reachable`() {
        val used = DIARY_MOODS.mapNotNull { diaryMoodTone(it.emoji) }.toSet()
        assertEquals("5 个情绪原型应全部被 12 心情覆盖", DiaryMoodTone.entries.toSet(), used)
    }

    @Test
    fun `spec anchors from valence-arousal semantics`() {
        // Palette.kt 注释口径的锚点抽查：喜悦/兴奋=JOY、平静/思考=CALM、悲伤=SAD、爱=SHY、怒/恐=ANGER。
        assertEquals(DiaryMoodTone.JOY, diaryMoodTone("😊"))
        assertEquals(DiaryMoodTone.JOY, diaryMoodTone("🎉"))
        assertEquals(DiaryMoodTone.CALM, diaryMoodTone("🤔"))
        assertEquals(DiaryMoodTone.SAD, diaryMoodTone("😢"))
        assertEquals(DiaryMoodTone.SHY, diaryMoodTone("🥰"))
        assertEquals(DiaryMoodTone.ANGER, diaryMoodTone("😤"))
    }

    @Test
    fun `unknown or blank input degrades to null`() {
        assertNull(diaryMoodTone(null))
        assertNull(diaryMoodTone(""))
        assertNull(diaryMoodTone("📝"))
        assertNull(diaryMoodTone("😊 "))
    }

    @Test
    fun `tint alpha stays within decorative band`() {
        // tint 只承载氛围：两档都必须是「可看穿」的半透明（0<α<1），且深色档更收敛。
        assertTrue(MoodTintAlphaLight in 0.05f..0.95f)
        assertTrue(MoodTintAlphaDark in 0.05f..0.95f)
        assertTrue(MoodTintAlphaDark < MoodTintAlphaLight)
        assertEquals(MoodTintAlphaDark, moodTintAlpha(isDark = true))
        assertEquals(MoodTintAlphaLight, moodTintAlpha(isDark = false))
    }
}
