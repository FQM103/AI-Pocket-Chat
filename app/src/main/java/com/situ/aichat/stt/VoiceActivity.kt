package com.situ.aichat.stt

import kotlin.math.sqrt

/**
 * Normalize a frame of 16 kHz mono float samples to a 0..1 audio level, 1:1 iOS
 * `VoiceCallSTT.normalizedAudioLevel` (VoiceCallSTT.swift:417-430): rms = √(Σx²/n), level = min(max(rms*5, 0), 1).
 * Drives the call waveform and the energy-based barge-in.
 */
internal fun normalizeAudioLevel(samples: FloatArray): Float {
    if (samples.isEmpty()) return 0f
    var sum = 0.0
    for (s in samples) sum += s.toDouble() * s.toDouble()
    val rms = sqrt(sum / samples.size).toFloat()
    return (rms * SttConstants.LEVEL_RMS_GAIN).coerceIn(0f, 1f)
}

/**
 * Trailing-silence endpointer, 1:1 iOS `VoiceCallSTT` `silenceWorkItem` semantics: the first non-empty
 * recognition flips "voice detected"; once speech has been heard and the recognized text stays unchanged
 * for [silenceTimeoutMs], the user is judged to have finished speaking. Time is injected (not read from a
 * clock) so the rule is unit-testable without real timers — the streaming caller (10.1e) feeds it
 * `decodedText()` plus a monotonic timestamp and polls [isFinished].
 */
internal class SilenceTracker(
    private val silenceTimeoutMs: Long = SttConstants.SILENCE_TIMEOUT_MS,
) {
    private var heardVoice = false
    private var lastText = ""
    private var lastChangeAtMs = 0L

    /**
     * Feed the latest (partial) recognized text at [nowMs]. Returns true only on the transition into
     * "voice detected" (first non-empty text), so the caller can fire its one-shot onVoiceDetected.
     */
    fun onText(rawText: String, nowMs: Long): Boolean {
        val text = rawText.trim()
        var voiceJustDetected = false
        if (text.isNotEmpty() && !heardVoice) {
            heardVoice = true
            voiceJustDetected = true
        }
        if (text != lastText) {
            lastText = text
            lastChangeAtMs = nowMs
        }
        return voiceJustDetected
    }

    /** True once speech has been heard and the recognized text has been stable for the silence timeout. */
    fun isFinished(nowMs: Long): Boolean =
        heardVoice && lastText.isNotEmpty() && (nowMs - lastChangeAtMs) >= silenceTimeoutMs

    fun text(): String = lastText

    val voiceDetected: Boolean get() = heardVoice

    fun reset() {
        heardVoice = false
        lastText = ""
        lastChangeAtMs = 0L
    }
}
