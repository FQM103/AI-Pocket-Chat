package com.situ.aichat.stt

/**
 * STT / VAD constants reverse-derived from the iOS voice-call source — the iOS *value* (not the Kotlin
 * output) is the assertion baseline for the unit tests (see verification-process). The recorder / barge-in
 * constants are added alongside that logic in 10.1d-3.
 */
internal object SttConstants {
    /** 16 kHz mono — iOS `AudioRecorderService` PCM settings + sherpa feature sample rate. */
    const val SAMPLE_RATE = 16000

    /** RMS→level gain: iOS `VoiceCallSTT.normalizedAudioLevel` = min(max(rms*5, 0), 1) (VoiceCallSTT.swift:417-430). */
    const val LEVEL_RMS_GAIN = 5f

    /** Trailing-silence-to-final: iOS `silenceTimeout` = 1.2 s (VoiceCallSTT.swift:23). */
    const val SILENCE_TIMEOUT_MS = 1200L

    // ---- Recorder / barge-in (iOS VoiceCallSTT + VoiceCallManager + VoiceCallSettingsView) ----

    /** Mic frame fed per read, 1:1 iOS tap bufferSize = 1024 frames (VoiceCallSTT.swift:347). */
    const val FRAME_SAMPLES = 1024

    /** Default user-configured barge-in energy threshold = 0.15 (VoiceCallManager.swift:63). */
    const val DEFAULT_INTERRUPT_THRESHOLD = 0.15f

    /** Speaker mode caps the effective threshold to ≤ 0.12 — no AEC, avoid self-echo (VoiceCallManager.swift:65-67). */
    const val SPEAKER_INTERRUPT_CAP = 0.12f

    /** Barge-in energy must persist this long: iOS interruptionDuration = 0.35 s (VoiceCallManager.swift:68). */
    const val INTERRUPT_DURATION_MS = 350L

    /** Recognition-based barge-in needs ≥ 0.3 s of speech (VoiceCallSTT.swift:218-220). */
    const val MONITORING_MIN_SPEECH_MS = 300L

    /** AI-speaking → barge-in protection window = 0.5 s (VoiceCallManager+TTS.swift:19). */
    const val AI_SPEAK_PROTECT_MS = 500L

    /** Energy baseline = mean of the first 10 mic samples after AI starts (VoiceCallManager+STT.swift:88-93). */
    const val BASELINE_SAMPLE_COUNT = 10

    /** Sensitivity slider range/step + stored threshold = base − slider, 1:1 iOS (VoiceCallSettingsView.swift:18-22). */
    const val SENSITIVITY_SLIDER_MIN = 0.05f
    const val SENSITIVITY_SLIDER_MAX = 0.40f
    const val SENSITIVITY_SLIDER_STEP = 0.05f
    const val SENSITIVITY_STORE_BASE = 0.45f
}
