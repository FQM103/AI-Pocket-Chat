package com.situ.aichat.stt

/**
 * Energy-based barge-in detector, 1:1 iOS `VoiceCallManager.startInterruptionDetection`
 * (VoiceCallManager+STT.swift:77-108). While the AI is speaking the call feeds it the mic level every
 * 0.05 s; the detector signals a barge-in once the user's voice rises clearly above the room noise for
 * long enough.
 *
 * Algorithm (exactly iOS):
 *  1. The first [baselineSampleCount] (10) levels establish a noise baseline = their running mean; the
 *     detector never fires during this warm-up. After 10 samples the baseline is frozen.
 *  2. After warm-up, a level is "loud" when `level > baseline + threshold`. The caller passes the
 *     *effective* threshold (earpiece = configured value; speaker = capped to 0.12 via
 *     [effectiveInterruptThreshold]) — keeping the speaker-cap in its already-tested helper.
 *  3. Loud must persist continuously for [interruptDurationMs] (0.35 s) to fire; any quiet level in
 *     between resets the timer (no accumulation).
 *
 * Time is injected (monotonic ms), so the 0.35 s persistence rule is unit-testable without real timers
 * (verification-process #2). Not thread-safe — the call drives it from one detection loop.
 */
internal class BargeInDetector(
    private val baselineSampleCount: Int = SttConstants.BASELINE_SAMPLE_COUNT,
    private val interruptDurationMs: Long = SttConstants.INTERRUPT_DURATION_MS,
) {
    private val baselineSamples = ArrayList<Float>(baselineSampleCount)
    private var baseline = 0f
    private var highLevelStartMs: Long? = null

    /** The frozen noise baseline (mean of the first [baselineSampleCount] levels). Exposed for logging/tests. */
    val baselineLevel: Float get() = baseline

    /** True once the warm-up samples have been collected and detection is live. */
    val isPrimed: Boolean get() = baselineSamples.size >= baselineSampleCount

    /**
     * Feed one mic [level] (0..1) measured at [nowMs] with the current effective [threshold]. Returns
     * true exactly once when a barge-in is confirmed; the caller then stops the detector and handles it.
     */
    fun onLevel(level: Float, threshold: Float, nowMs: Long): Boolean {
        if (baselineSamples.size < baselineSampleCount) {
            baselineSamples.add(level)
            baseline = baselineSamples.sum() / baselineSamples.size
            return false
        }
        val isLoud = level > baseline + threshold
        if (!isLoud) {
            highLevelStartMs = null
            return false
        }
        val start = highLevelStartMs
        if (start == null) {
            highLevelStartMs = nowMs
            return false
        }
        return nowMs - start >= interruptDurationMs
    }

    /** Clear baseline + timer, 1:1 iOS `stopInterruptionDetection` resetting samples/baseline/start. */
    fun reset() {
        baselineSamples.clear()
        baseline = 0f
        highLevelStartMs = null
    }
}
