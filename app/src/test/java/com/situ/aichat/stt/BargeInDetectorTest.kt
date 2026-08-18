package com.situ.aichat.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Energy barge-in detector, assertions reverse-derived from iOS `startInterruptionDetection`
 * (VoiceCallManager+STT.swift:77-108): 10-sample baseline warm-up, `level > baseline + threshold`,
 * 0.35 s persistence, timer reset on any quiet sample.
 */
class BargeInDetectorTest {

    private val threshold = SttConstants.DEFAULT_INTERRUPT_THRESHOLD // 0.15

    /** Feed n levels during warm-up; returns the detector primed with the given baseline. */
    private fun primed(level: Float, n: Int = SttConstants.BASELINE_SAMPLE_COUNT): BargeInDetector {
        val d = BargeInDetector()
        repeat(n) { assertFalse("warm-up never fires", d.onLevel(level, threshold, nowMs = it.toLong())) }
        return d
    }

    @Test fun `baseline is the mean of the first ten samples and detection is off during warm-up`() {
        val d = BargeInDetector()
        // Even a deafening level during the first 10 samples must not fire.
        repeat(SttConstants.BASELINE_SAMPLE_COUNT) {
            assertFalse(d.onLevel(0.1f, threshold, nowMs = it.toLong()))
        }
        assertEquals(0.1f, d.baselineLevel, 1e-6f)
        assertTrue(d.isPrimed)
    }

    @Test fun `a single loud sample does not fire — duration not met`() {
        val d = primed(0.1f) // baseline 0.1, threshold 0.15 → loud above 0.25
        assertFalse(d.onLevel(0.5f, threshold, nowMs = 1000L))
    }

    @Test fun `loud sustained for the full 0_35s fires`() {
        val d = primed(0.1f)
        assertFalse(d.onLevel(0.5f, threshold, nowMs = 1000L))                 // start the timer
        assertFalse(d.onLevel(0.5f, threshold, nowMs = 1000L + 349L))          // not yet 350 ms
        assertTrue(d.onLevel(0.5f, threshold, nowMs = 1000L + 350L))           // exactly 350 ms → fire
    }

    @Test fun `a quiet sample mid-stream resets the persistence timer`() {
        val d = primed(0.1f)
        assertFalse(d.onLevel(0.5f, threshold, nowMs = 1000L))   // loud, timer starts
        assertFalse(d.onLevel(0.1f, threshold, nowMs = 1200L))   // quiet → reset
        assertFalse(d.onLevel(0.5f, threshold, nowMs = 1300L))   // loud again, timer restarts
        assertFalse(d.onLevel(0.5f, threshold, nowMs = 1300L + 349L))
        assertTrue(d.onLevel(0.5f, threshold, nowMs = 1300L + 350L)) // 350 ms from the RESTART
    }

    @Test fun `level exactly at baseline plus threshold is not loud (strict greater-than)`() {
        val d = primed(0.1f) // baseline 0.1
        // iOS: micLevel > baseline + threshold (strict). 0.1 + 0.15 = 0.25 must NOT count as loud.
        assertFalse(d.onLevel(0.25f, threshold, nowMs = 1000L))
        assertFalse(d.onLevel(0.25f, threshold, nowMs = 5000L))
    }

    @Test fun `speaker cap makes a quieter voice enough to interrupt`() {
        // Earpiece (0.15): 0.26 over a 0.1 baseline barely clears 0.25.
        val ear = primed(0.1f)
        assertFalse(ear.onLevel(0.26f, 0.15f, nowMs = 1000L))
        assertTrue(ear.onLevel(0.26f, 0.15f, nowMs = 1350L))
        // Speaker cap 0.12: threshold drops, so 0.23 (< 0.25) now clears 0.1 + 0.12 = 0.22.
        val spk = primed(0.1f)
        val capped = effectiveInterruptThreshold(0.15f, isSpeaker = true) // 0.12
        assertFalse(spk.onLevel(0.23f, capped, nowMs = 1000L))
        assertTrue(spk.onLevel(0.23f, capped, nowMs = 1350L))
    }

    @Test fun `reset returns the detector to warm-up`() {
        val d = primed(0.1f)
        assertTrue(d.isPrimed)
        d.reset()
        assertFalse(d.isPrimed)
        assertEquals(0f, d.baselineLevel, 0f)
        // After reset, the next 10 samples warm up again before any detection.
        assertFalse(d.onLevel(0.9f, threshold, nowMs = 9000L))
    }
}
