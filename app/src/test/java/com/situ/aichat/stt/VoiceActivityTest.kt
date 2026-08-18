package com.situ.aichat.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure VAD logic, assertions reverse-derived from the iOS values (not the Kotlin output):
 * level = min(max(rms*5, 0), 1) (VoiceCallSTT.swift:417-430); silence timeout = 1.2 s (VoiceCallSTT.swift:23).
 */
class VoiceActivityTest {

    @Test fun `level of silence is zero`() {
        assertEquals(0f, normalizeAudioLevel(FloatArray(0)), 0f)
        assertEquals(0f, normalizeAudioLevel(FloatArray(100) { 0f }), 0f)
    }

    @Test fun `level scales rms by 5 then clamps to 1`() {
        // constant c → rms = |c| → level = min(5|c|, 1)
        assertEquals(0.5f, normalizeAudioLevel(FloatArray(50) { 0.1f }), 1e-4f)
        assertEquals(1.0f, normalizeAudioLevel(FloatArray(50) { 0.2f }), 1e-4f)   // 5*0.2 = 1.0 boundary
        assertEquals(1.0f, normalizeAudioLevel(FloatArray(50) { 0.5f }), 1e-4f)   // clamped
        assertEquals(0.25f, normalizeAudioLevel(FloatArray(50) { -0.05f }), 1e-4f) // sign-independent
    }

    @Test fun `voice detected only on the first non-empty text`() {
        val t = SilenceTracker()
        assertFalse(t.onText("", 0))
        assertFalse(t.voiceDetected)
        assertTrue(t.onText("你好", 100))      // transition → true
        assertTrue(t.voiceDetected)
        assertFalse(t.onText("你好吗", 200))    // already detected → false
    }

    @Test fun `finished after 1200ms of stable text`() {
        val t = SilenceTracker()
        t.onText("你好", 100)
        assertFalse(t.isFinished(100))
        assertFalse(t.isFinished(1299))        // 1199 ms since last change
        assertTrue(t.isFinished(1300))         // exactly 1200 ms since last change (100)
    }

    @Test fun `new text resets the silence window`() {
        val t = SilenceTracker()
        t.onText("你", 100)
        t.onText("你好", 900)                   // text grew → window restarts at 900
        assertFalse(t.isFinished(2099))        // 1199 ms since 900
        assertTrue(t.isFinished(2100))         // 1200 ms since 900
    }

    @Test fun `empty text never finishes`() {
        val t = SilenceTracker()
        t.onText("   ", 100)                    // whitespace trims to empty
        assertFalse(t.voiceDetected)
        assertFalse(t.isFinished(5000))
    }

    @Test fun `reset clears state`() {
        val t = SilenceTracker()
        t.onText("你好", 100)
        t.reset()
        assertFalse(t.voiceDetected)
        assertEquals("", t.text())
        assertFalse(t.isFinished(10_000))
    }
}
