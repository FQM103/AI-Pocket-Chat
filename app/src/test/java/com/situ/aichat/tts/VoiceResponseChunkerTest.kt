package com.situ.aichat.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Voice chunker parity. The per-character duration estimate is reverse-derived from the iOS constants
 * (chinese 0.30 / latin 0.07 / digit 0.09 / emoji 0.20 / strong-pause 0.58 / weak-pause 0.22 /
 * ×1.08 safety / min 1s). Robolectric because emoji detection uses ICU `EMOJI_PRESENTATION` (= iOS
 * `isEmojiPresentation`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceResponseChunkerTest {

    private val eps = 0.001

    @Test
    fun `chinese characters use 0_30 each`() {
        // 10 × 0.30 × 1.08 = 3.24
        assertEquals(3.24, VoiceResponseChunker.estimatedSpeechDuration("一二三四五六七八九十"), eps)
    }

    @Test
    fun `pauses add strong and weak durations`() {
        // 你好(0.6) ，(0.22) 世界(0.6) ！(0.58) = 2.0 × 1.08 = 2.16
        assertEquals(2.16, VoiceResponseChunker.estimatedSpeechDuration("你好，世界！"), eps)
    }

    @Test
    fun `digits use 0_09 each`() {
        // 20 × 0.09 × 1.08 = 1.944
        assertEquals(1.944, VoiceResponseChunker.estimatedSpeechDuration("12345678901234567890"), eps)
    }

    @Test
    fun `emoji use 0_20 each via ICU presentation`() {
        // 5 × 0.20 × 1.08 = 1.08
        assertEquals(1.08, VoiceResponseChunker.estimatedSpeechDuration("😀😀😀😀😀"), eps)
    }

    @Test
    fun `short text and min one second`() {
        // 你好 = 2 × 0.30 × 1.08 = 0.648 → clamped to min 1.0
        assertEquals(1.0, VoiceResponseChunker.estimatedSpeechDuration("你好"), eps)
        assertEquals(0.0, VoiceResponseChunker.estimatedSpeechDuration("   "), eps)
    }

    @Test
    fun `empty input chunks to nothing`() {
        assertTrue(VoiceResponseChunker.chunkForVoice("").isEmpty())
        assertTrue(VoiceResponseChunker.chunkForVoice("   \n  ").isEmpty())
    }

    @Test
    fun `short reply stays a single chunk`() {
        val chunks = VoiceResponseChunker.chunkForVoice("你好呀，今天过得怎么样？")
        assertEquals(1, chunks.size)
        assertEquals("你好呀，今天过得怎么样？", chunks.first())
    }

    @Test
    fun `long reply splits into multiple bounded chunks`() {
        // ~70 sentences of ~3.24s each ≫ 60s → must split; every chunk ≤ 60s hard cap.
        val sentence = "一二三四五六七八九十。"
        val long = sentence.repeat(70)
        val chunks = VoiceResponseChunker.chunkForVoice(long)
        assertTrue("expected multiple chunks, got ${chunks.size}", chunks.size > 1)
        chunks.forEach {
            assertTrue(
                "chunk over hard cap: ${VoiceResponseChunker.estimatedSpeechDuration(it)}s",
                VoiceResponseChunker.estimatedSpeechDuration(it) <= 60.0,
            )
        }
        // No content lost: concatenated chunks contain every sentence.
        assertEquals(70, chunks.sumOf { Regex("。").findAll(it).count() })
    }
}
