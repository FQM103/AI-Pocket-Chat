package com.situ.aichat.tts.provider

import com.situ.aichat.tts.EmotionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MiniMax capability matrix + emotion clamp + the two-step MoodEmoji→emotion mapping.
 * Assertions reverse-derived from iOS `MiniMaxCatalog` / `MiniMaxEmotionSet` /
 * `MoodEmojiToMiniMaxEmotion` / `EmotionType` (verified 2026-04 on the iOS side).
 */
class MiniMaxEmotionTest {

    // MARK: - capability matrix

    @Test
    fun `speech-2_8 supports interpolation tags + fluent, no whisper`() {
        val cap = MiniMaxCatalog.capability("speech-2.8-hd")
        assertTrue(cap.supportsInterpolationTags)
        assertEquals(MiniMaxEmotionSet.SPEECH28, cap.supportedEmotions)
        assertTrue(cap.supportedEmotions.values.contains("fluent"))
        assertFalse(cap.supportedEmotions.values.contains("whisper"))
    }

    @Test
    fun `speech-2_6 supports fluent + whisper, no interpolation tags`() {
        val cap = MiniMaxCatalog.capability("speech-2.6-turbo")
        assertFalse(cap.supportsInterpolationTags)
        assertEquals(MiniMaxEmotionSet.SPEECH26, cap.supportedEmotions)
        assertTrue(cap.supportedEmotions.values.contains("fluent"))
        assertTrue(cap.supportedEmotions.values.contains("whisper"))
    }

    @Test
    fun `legacy 02 and 01 and unknown are base 7 only`() {
        for (model in listOf("speech-02-hd", "speech-01-turbo", "totally-unknown")) {
            val cap = MiniMaxCatalog.capability(model)
            assertFalse(cap.supportsInterpolationTags)
            assertEquals(MiniMaxEmotionSet.LEGACY, cap.supportedEmotions)
            assertEquals(7, cap.supportedEmotions.values.size)
            assertFalse(cap.supportedEmotions.values.contains("fluent"))
        }
    }

    @Test
    fun `default model id is speech-2_8-hd`() {
        assertEquals("speech-2.8-hd", MiniMaxCatalog.DEFAULT_MODEL_ID)
        assertEquals(8, MiniMaxCatalog.builtInModels.size)
        assertEquals(12, MiniMaxCatalog.fallbackVoices.size)
    }

    // MARK: - clamped

    @Test
    fun `clamped maps null empty auto neutral to null`() {
        assertNull(MiniMaxEmotionSet.SPEECH28.clamped(null))
        assertNull(MiniMaxEmotionSet.SPEECH28.clamped(""))
        assertNull(MiniMaxEmotionSet.SPEECH28.clamped("auto"))
        assertNull(MiniMaxEmotionSet.SPEECH28.clamped("neutral"))
    }

    @Test
    fun `clamped keeps supported, trims and lowercases`() {
        assertEquals("happy", MiniMaxEmotionSet.SPEECH28.clamped("happy"))
        assertEquals("happy", MiniMaxEmotionSet.SPEECH28.clamped("  Happy "))
        assertEquals("fluent", MiniMaxEmotionSet.SPEECH28.clamped("fluent"))
        assertEquals("whisper", MiniMaxEmotionSet.SPEECH26.clamped("whisper"))
    }

    @Test
    fun `clamped degrades unsupported to calm`() {
        // 2.8 has no whisper → calm; legacy has no fluent → calm
        assertEquals("calm", MiniMaxEmotionSet.SPEECH28.clamped("whisper"))
        assertEquals("calm", MiniMaxEmotionSet.LEGACY.clamped("fluent"))
    }

    // MARK: - EmotionType.from(emoji)

    @Test
    fun `emoji classifies to the iOS emotion type`() {
        assertEquals(EmotionType.HAPPY, EmotionType.from("😊"))
        assertEquals(EmotionType.HAPPY, EmotionType.from("☺️"))
        assertEquals(EmotionType.EXCITED, EmotionType.from("🤩"))
        assertEquals(EmotionType.ANGRY, EmotionType.from("😡"))
        assertEquals(EmotionType.SAD, EmotionType.from("😢"))
        assertEquals(EmotionType.SHOCKED, EmotionType.from("😮"))
        assertEquals(EmotionType.SHY, EmotionType.from("😳"))
        assertEquals(EmotionType.LOVE, EmotionType.from("❤️"))
        assertEquals(EmotionType.THINKING, EmotionType.from("🤔"))
        assertEquals(EmotionType.SCARED, EmotionType.from("😨"))
        assertEquals(EmotionType.PLAYFUL, EmotionType.from("😏"))
        assertEquals(EmotionType.SIGH, EmotionType.from("😩"))
        assertEquals(EmotionType.NEUTRAL, EmotionType.from("😐"))
        assertEquals(EmotionType.NEUTRAL, EmotionType.from("🙂"))
    }

    @Test
    fun `null empty and zwj sigh handled like iOS`() {
        assertEquals(EmotionType.NEUTRAL, EmotionType.from(null))
        assertEquals(EmotionType.NEUTRAL, EmotionType.from(""))
        // "😮‍💨" (ZWJ) is not exactly "😮" → prefix("😮") → SIGH
        assertEquals(EmotionType.SIGH, EmotionType.from("😮‍💨"))
        // an unknown emoji → neutral
        assertEquals(EmotionType.NEUTRAL, EmotionType.from("🍎"))
    }

    // MARK: - MoodEmojiToMiniMaxEmotion (two-step)

    @Test
    fun `mood emoji maps to minimax emotion`() {
        assertEquals("happy", MoodEmojiToMiniMaxEmotion.emotionFor("😊"))
        assertEquals("happy", MoodEmojiToMiniMaxEmotion.emotionFor("🤩")) // excited → happy
        assertEquals("angry", MoodEmojiToMiniMaxEmotion.emotionFor("😡"))
        assertEquals("sad", MoodEmojiToMiniMaxEmotion.emotionFor("😢"))
        assertEquals("surprised", MoodEmojiToMiniMaxEmotion.emotionFor("😮"))
        assertEquals("calm", MoodEmojiToMiniMaxEmotion.emotionFor("😳")) // shy → calm
        assertEquals("happy", MoodEmojiToMiniMaxEmotion.emotionFor("😍")) // love → happy
        assertEquals("fearful", MoodEmojiToMiniMaxEmotion.emotionFor("😨"))
        assertEquals("happy", MoodEmojiToMiniMaxEmotion.emotionFor("😏")) // playful → happy
        assertEquals("sad", MoodEmojiToMiniMaxEmotion.emotionFor("😩")) // sigh → sad
    }

    @Test
    fun `thinking neutral and null map to null (server default auto)`() {
        assertNull(MoodEmojiToMiniMaxEmotion.emotionFor("🤔")) // thinking → null
        assertNull(MoodEmojiToMiniMaxEmotion.emotionFor("😐")) // neutral → null
        assertNull(MoodEmojiToMiniMaxEmotion.emotionFor(null))
        assertNull(MoodEmojiToMiniMaxEmotion.emotionFor(""))
        assertNull(MoodEmojiToMiniMaxEmotion.emotionFor(EmotionType.THINKING))
        assertNull(MoodEmojiToMiniMaxEmotion.emotionFor(EmotionType.NEUTRAL))
    }
}
