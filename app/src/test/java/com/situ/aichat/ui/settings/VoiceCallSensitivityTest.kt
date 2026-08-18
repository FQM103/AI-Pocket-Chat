package com.situ.aichat.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Asserts the barge-in sensitivity Slider ↔ threshold mapping against the iOS values
 * (`VoiceCallSettingsView`: slider `0.05…0.40` step `0.05`, stored threshold = `0.45 − slider`).
 * Higher slider (further right) = SMALLER threshold = easier to interrupt.
 */
class VoiceCallSensitivityTest {

    private val delta = 0.0001f

    @Test
    fun `default threshold 0_15 maps to slider 0_30`() {
        // iOS default userConfiguredThreshold = 0.15 → slider 0.45 − 0.15 = 0.30
        assertEquals(0.30f, VoiceCallSensitivity.sliderFromThreshold(0.15f), delta)
    }

    @Test
    fun `min threshold maps to max slider (easiest to interrupt)`() {
        // threshold 0.05 (smallest) → slider 0.40 (furthest right = 容易打断)
        assertEquals(0.40f, VoiceCallSensitivity.sliderFromThreshold(0.05f), delta)
    }

    @Test
    fun `max threshold maps to min slider (hardest to interrupt)`() {
        // threshold 0.40 (largest) → slider 0.05 (furthest left = 不易打断)
        assertEquals(0.05f, VoiceCallSensitivity.sliderFromThreshold(0.40f), delta)
    }

    @Test
    fun `slider 0_30 maps back to threshold 0_15`() {
        assertEquals(0.15f, VoiceCallSensitivity.thresholdFromSlider(0.30f), delta)
    }

    @Test
    fun `slider extremes map to threshold extremes`() {
        // furthest right (easy) → smallest stored threshold; furthest left (hard) → largest.
        assertEquals(0.05f, VoiceCallSensitivity.thresholdFromSlider(0.40f), delta)
        assertEquals(0.40f, VoiceCallSensitivity.thresholdFromSlider(0.05f), delta)
    }

    @Test
    fun `round trip is stable on every step`() {
        var s = VoiceCallSensitivity.SLIDER_MIN
        while (s <= VoiceCallSensitivity.SLIDER_MAX + delta) {
            val back = VoiceCallSensitivity.sliderFromThreshold(VoiceCallSensitivity.thresholdFromSlider(s))
            assertEquals(s, back, delta)
            s += VoiceCallSensitivity.SLIDER_STEP
        }
    }

    @Test
    fun `out-of-range threshold is clamped to slider bounds`() {
        // A stored value below the min (e.g. legacy 0.0) would map to slider 0.45 → clamp to 0.40.
        assertEquals(0.40f, VoiceCallSensitivity.sliderFromThreshold(0.0f), delta)
        // A stored value above the max (e.g. 0.6) → slider -0.15 → clamp to 0.05.
        assertEquals(0.05f, VoiceCallSensitivity.sliderFromThreshold(0.6f), delta)
    }
}
