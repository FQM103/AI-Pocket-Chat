package com.situ.aichat.ui.settings

import kotlin.math.roundToInt

/**
 * Pure mapping between the barge-in sensitivity Slider and the stored energy threshold —
 * 1:1 iOS `VoiceCallSettingsView` (`get: 0.45 - threshold`, `set: threshold = 0.45 - slider`,
 * range `0.05...0.40` step `0.05`).
 *
 * The relationship is INVERTED on purpose: the further RIGHT the slider (= easier to interrupt),
 * the SMALLER the stored energy threshold. The stored value is consumed by
 * [com.situ.aichat.voice.VoiceCallController] as `sanitizedVoiceCallInterruptThreshold`.
 *
 * `internal` so the mapping is unit-tested with assertions reverse-derived from the iOS values.
 */
internal object VoiceCallSensitivity {
    const val SLIDER_MIN = 0.05f
    const val SLIDER_MAX = 0.40f
    const val SLIDER_STEP = 0.05f

    /** Interior tick count for Compose `Slider(steps = …)`: 8 values (0.05…0.40) → 6 between the ends. */
    const val SLIDER_STEPS = 6

    /** iOS `0.45` offset; both `get` and `set` are reflections about half of it. */
    private const val OFFSET = 0.45f

    /** Stored energy threshold → slider position (higher slider = easier to interrupt). 1:1 iOS `get`. */
    fun sliderFromThreshold(threshold: Float): Float =
        snap(OFFSET - threshold).coerceIn(SLIDER_MIN, SLIDER_MAX)

    /** Slider position → stored energy threshold. 1:1 iOS `set`, snapped to 0.05 to kill float drift. */
    fun thresholdFromSlider(slider: Float): Float =
        snap(OFFSET - slider).coerceIn(SLIDER_MIN, SLIDER_MAX)

    /** Snap to the nearest 0.05 multiple (the Slider step), so `0.45f - 0.30f` lands exactly on `0.15`. */
    private fun snap(value: Float): Float = (value / SLIDER_STEP).roundToInt() * SLIDER_STEP
}
