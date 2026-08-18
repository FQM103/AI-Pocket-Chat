package com.situ.aichat.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Barge-in pure logic, assertions reverse-derived from iOS: backchannel set (VoiceCallSTT.swift:55-59),
 * speaker threshold cap (VoiceCallManager.swift:65-67), sensitivity slider math (VoiceCallSettingsView.swift:18-22).
 */
class BargeInPolicyTest {

    @Test fun `backchannel set has the 23 iOS entries`() {
        // code-truth: 23 literal Set entries (memory's "22 words" is an approximation)
        assertEquals(23, BACKCHANNEL_WORDS.size)
    }

    @Test fun `every iOS backchannel word is recognized`() {
        val words = listOf(
            "嗯", "嗯嗯", "嗯哼", "好", "好的", "好好", "对", "对对", "对的",
            "哦", "噢", "啊", "哈", "是", "是的", "行", "行的",
            "ok", "OK", "Ok", "唔", "嗯好", "嗯对",
        )
        words.forEach { assertTrue(it, isBackchannel(it)) }
    }

    @Test fun `backchannel trims surrounding whitespace`() {
        assertTrue(isBackchannel("  好的 "))
    }

    @Test fun `real speech is not treated as backchannel`() {
        assertFalse(isBackchannel("你好"))
        assertFalse(isBackchannel("嗯你说"))   // not an exact entry
        assertFalse(isBackchannel(""))
        assertFalse(isBackchannel("ＯＫ"))      // full-width, not in the set
    }

    @Test fun `earpiece uses the configured threshold as-is`() {
        assertEquals(0.15f, effectiveInterruptThreshold(0.15f, isSpeaker = false), 0f)
        assertEquals(0.40f, effectiveInterruptThreshold(0.40f, isSpeaker = false), 0f)
    }

    @Test fun `speaker caps the threshold at 0_12`() {
        assertEquals(0.12f, effectiveInterruptThreshold(0.15f, isSpeaker = true), 0f)   // clamped down
        assertEquals(0.10f, effectiveInterruptThreshold(0.10f, isSpeaker = true), 0f)   // already below cap
        assertEquals(0.12f, effectiveInterruptThreshold(0.40f, isSpeaker = true), 0f)
    }

    @Test fun `slider maps to stored threshold and back (stored = 0_45 - slider)`() {
        assertEquals(0.40f, storedThresholdForSlider(0.05f), 1e-5f)  // least sensitive
        assertEquals(0.05f, storedThresholdForSlider(0.40f), 1e-5f)  // most sensitive
        assertEquals(0.05f, sliderForStoredThreshold(0.40f), 1e-5f)  // exact inverse
    }

    @Test fun `default threshold round-trips through the slider`() {
        val slider = sliderForStoredThreshold(SttConstants.DEFAULT_INTERRUPT_THRESHOLD)
        assertEquals(0.30f, slider, 1e-5f)
        assertEquals(SttConstants.DEFAULT_INTERRUPT_THRESHOLD, storedThresholdForSlider(slider), 1e-5f)
    }
}
