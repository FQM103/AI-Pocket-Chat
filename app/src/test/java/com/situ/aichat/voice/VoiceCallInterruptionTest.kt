package com.situ.aichat.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1 for [VoiceCallInterruption] — the unified pause/resume bookkeeping (微图纸 2026-07-11-语音通话逻辑修缮).
 * Assertions reverse-derived from the contract: one teardown per pause episode, one dispatch per recovery,
 * spurious resumes are no-ops, permanent focus loss self-heals via the foreground probe.
 */
class VoiceCallInterruptionTest {

    private val interruption = VoiceCallInterruption()
    private val neverProbed: () -> Boolean = { throw AssertionError("focus probe must not run here") }

    @Test
    fun `first reason asks for teardown, second does not`() {
        assertTrue(interruption.beginPause(CallPauseReason.FOCUS_LOSS))
        assertFalse(interruption.beginPause(CallPauseReason.BACKGROUND))
        assertTrue(interruption.isPaused)
    }

    @Test
    fun `resume without matching pause is a no-op`() {
        assertNull(interruption.endPause(CallPauseReason.FOCUS_LOSS, neverProbed))
        // Paused by BACKGROUND only → a spurious focus GAIN must not dispatch.
        interruption.beginPause(CallPauseReason.BACKGROUND)
        interruption.pendingAction = ForegroundRecoveryAction.RESTART_LLM_TURN
        assertNull(interruption.endPause(CallPauseReason.FOCUS_LOSS, neverProbed))
        assertTrue(interruption.isPaused)
    }

    @Test
    fun `single reason round trip returns stored action and resets it`() {
        interruption.beginPause(CallPauseReason.FOCUS_LOSS)
        interruption.pendingAction = ForegroundRecoveryAction.RESTART_LLM_TURN
        assertEquals(
            ForegroundRecoveryAction.RESTART_LLM_TURN,
            interruption.endPause(CallPauseReason.FOCUS_LOSS, neverProbed),
        )
        assertFalse(interruption.isPaused)
        assertEquals(ForegroundRecoveryAction.RESUME_LISTENING, interruption.pendingAction)
        // A second resume event (the other path firing late) must not dispatch again.
        assertNull(interruption.endPause(CallPauseReason.FOCUS_LOSS, neverProbed))
    }

    @Test
    fun `both reasons - focus clears first, dispatch waits for foreground`() {
        interruption.beginPause(CallPauseReason.FOCUS_LOSS)
        interruption.beginPause(CallPauseReason.BACKGROUND)
        interruption.pendingAction = ForegroundRecoveryAction.RESUME_PENDING_PLAYBACK
        assertNull(interruption.endPause(CallPauseReason.FOCUS_LOSS, neverProbed))
        assertEquals(
            ForegroundRecoveryAction.RESUME_PENDING_PLAYBACK,
            interruption.endPause(CallPauseReason.BACKGROUND, neverProbed),
        )
        assertFalse(interruption.isPaused)
    }

    @Test
    fun `both reasons - foreground first with focus probe granted self-heals and dispatches once`() {
        interruption.beginPause(CallPauseReason.FOCUS_LOSS)
        interruption.beginPause(CallPauseReason.BACKGROUND)
        interruption.pendingAction = ForegroundRecoveryAction.RESTART_LLM_TURN
        assertEquals(
            ForegroundRecoveryAction.RESTART_LLM_TURN,
            interruption.endPause(CallPauseReason.BACKGROUND) { true },
        )
        assertFalse(interruption.isPaused)
        // The real focus GAIN arriving later must be a no-op.
        assertNull(interruption.endPause(CallPauseReason.FOCUS_LOSS, neverProbed))
    }

    @Test
    fun `both reasons - foreground first with focus probe denied keeps waiting for GAIN`() {
        interruption.beginPause(CallPauseReason.FOCUS_LOSS)
        interruption.beginPause(CallPauseReason.BACKGROUND)
        interruption.pendingAction = ForegroundRecoveryAction.RESTART_LLM_TURN
        assertNull(interruption.endPause(CallPauseReason.BACKGROUND) { false })
        assertTrue(interruption.isPaused)
        assertEquals(
            ForegroundRecoveryAction.RESTART_LLM_TURN,
            interruption.endPause(CallPauseReason.FOCUS_LOSS, neverProbed),
        )
    }

    @Test
    fun `clear drops reasons and action`() {
        interruption.beginPause(CallPauseReason.FOCUS_LOSS)
        interruption.pendingAction = ForegroundRecoveryAction.RESUME_PENDING_PLAYBACK
        interruption.clear()
        assertFalse(interruption.isPaused)
        assertEquals(ForegroundRecoveryAction.RESUME_LISTENING, interruption.pendingAction)
        assertNull(interruption.endPause(CallPauseReason.FOCUS_LOSS, neverProbed))
    }
}
