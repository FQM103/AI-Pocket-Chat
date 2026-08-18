package com.situ.aichat.voice

/**
 * Why the call is currently paused. A real incoming phone call typically raises BOTH: the telephony app
 * grabs audio focus ([FOCUS_LOSS]) *and* its full-screen UI stops our activity ([BACKGROUND]).
 */
internal enum class CallPauseReason { FOCUS_LOSS, BACKGROUND }

/**
 * Bookkeeping for call interruptions — the single mechanism behind both the audio-focus and the
 * app-background pause/resume paths (2026-07-11 修缮; previously two independent flags, which made an
 * incoming phone call tear down twice and resume twice, and made the focus path drop the in-flight turn).
 *
 * Contract:
 *  - The FIRST reason triggers the actual teardown exactly once ([beginPause] returns true); further
 *    reasons are only remembered. The controller resolves the [pendingAction] right after tearing down.
 *  - Recovery dispatches exactly once, when the LAST reason clears ([endPause] returns the action).
 *    A resume event with no matching pause (e.g. a spurious focus GAIN) is a no-op.
 *  - Self-heal: a permanent `AUDIOFOCUS_LOSS` never delivers a GAIN callback, so when the app returns to
 *    the foreground while [CallPauseReason.FOCUS_LOSS] is still pending, [endPause] probes
 *    [tryReacquireFocus] — if focus is granted again the loss is considered over and recovery dispatches.
 *
 * Pure Kotlin (no Android), main-thread confined like its owning controller; unit-tested (T1).
 */
internal class VoiceCallInterruption {
    private val reasons = linkedSetOf<CallPauseReason>()

    /** How to resume the interrupted turn; resolved by the controller at teardown time. */
    var pendingAction: ForegroundRecoveryAction = ForegroundRecoveryAction.RESUME_LISTENING

    val isPaused: Boolean get() = reasons.isNotEmpty()

    /** Record [reason]. Returns true when it is the first (→ caller performs the one-time teardown). */
    fun beginPause(reason: CallPauseReason): Boolean {
        val first = reasons.isEmpty()
        reasons.add(reason)
        return first
    }

    /**
     * Clear [reason]; returns the recovery action to dispatch now, or null to keep waiting (other reason
     * still pending, or no matching pause). See the class contract for the [tryReacquireFocus] self-heal.
     */
    fun endPause(reason: CallPauseReason, tryReacquireFocus: () -> Boolean): ForegroundRecoveryAction? {
        if (!reasons.remove(reason)) return null
        if (reasons.isNotEmpty()) {
            val focusStillPending = CallPauseReason.FOCUS_LOSS in reasons
            val healed = reason == CallPauseReason.BACKGROUND && focusStillPending && tryReacquireFocus()
            if (!healed) return null
            reasons.remove(CallPauseReason.FOCUS_LOSS)
        }
        val action = pendingAction
        pendingAction = ForegroundRecoveryAction.RESUME_LISTENING
        return action
    }

    /** Drop all pause bookkeeping (hang-up / call reset). */
    fun clear() {
        reasons.clear()
        pendingAction = ForegroundRecoveryAction.RESUME_LISTENING
    }
}
