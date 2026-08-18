package com.situ.aichat.stt

/**
 * 附和词白名单 — 1:1 iOS `VoiceCallSTT.backchannelWords` (VoiceCallSTT.swift:55-59). These short
 * acknowledgements ("嗯" / "好的" / "对"…) must NOT count as a barge-in while the AI is speaking, otherwise
 * a listener's "uh-huh" cuts the AI off.
 *
 * Note: the iOS Set has **23** literal entries (the project memory's "22 words" is an approximation —
 * code is the source of truth). "ok" / "OK" / "Ok" are three distinct case-sensitive entries.
 */
internal val BACKCHANNEL_WORDS: Set<String> = setOf(
    "嗯", "嗯嗯", "嗯哼", "好", "好的", "好好", "对", "对对", "对的",
    "哦", "噢", "啊", "哈", "是", "是的", "行", "行的",
    "ok", "OK", "Ok", "唔", "嗯好", "嗯对",
)

/** True if the (trimmed) recognized text is exactly a backchannel word, 1:1 iOS `backchannelWords.contains`. */
internal fun isBackchannel(text: String): Boolean = BACKCHANNEL_WORDS.contains(text.trim())

/**
 * Effective barge-in energy threshold, 1:1 iOS `effectiveInterruptionThreshold` (VoiceCallManager.swift:65-67):
 * speaker mode clamps to ≤ [SttConstants.SPEAKER_INTERRUPT_CAP] (0.12, because `.default`/no-AEC speaker
 * output would otherwise self-trigger); earpiece uses the user's configured value as-is.
 */
internal fun effectiveInterruptThreshold(userThreshold: Float, isSpeaker: Boolean): Float =
    if (isSpeaker) minOf(userThreshold, SttConstants.SPEAKER_INTERRUPT_CAP) else userThreshold

/**
 * Sensitivity slider → stored threshold, 1:1 iOS `VoiceCallSettingsView` (swift:18-22): the slider reads
 * 0.05..0.40 where right = more sensitive, and the stored threshold = 0.45 − slider (bigger slider →
 * smaller threshold → easier to interrupt). [sliderForStoredThreshold] is the exact inverse for the UI.
 */
internal fun storedThresholdForSlider(slider: Float): Float = SttConstants.SENSITIVITY_STORE_BASE - slider

internal fun sliderForStoredThreshold(threshold: Float): Float = SttConstants.SENSITIVITY_STORE_BASE - threshold
