package com.situ.aichat.voice

/**
 * How to resume the current turn after an interruption ends (audio focus regained) or the app returns to
 * the foreground — 1:1 iOS `VoiceCallManager.ForegroundRecoveryAction` (VoiceCallManager.swift:26-30).
 */
enum class ForegroundRecoveryAction {
    /** Go back to listening for the user (default, and whenever some AI speech was already heard). */
    RESUME_LISTENING,

    /** The LLM was mid-stream when interrupted with nothing spoken yet → re-run the whole turn. */
    RESTART_LLM_TURN,

    /** A full AI response was ready but never played → play it now. */
    RESUME_PENDING_PLAYBACK,
}

/**
 * Decide how to recover the turn, 1:1 iOS `resolveForegroundRecoveryAction`
 * (VoiceCallManager+AudioSession.swift:8-26). Priority, highest first:
 *  1. Any AI speech already reached the user ([heardText] non-blank) → just listen again — never re-play
 *     or restart, or the user would hear the first half twice.
 *  2. We were [CallState.PROCESSING] with an in-flight LLM stream → restart the turn (nothing was spoken).
 *  3. A complete [pendingAiResponse] is queued → play it.
 *  4. Otherwise → listen again.
 *
 * Pure + `internal`; assertions reverse-derived from the iOS `VoiceCallManager*Tests` cases.
 */
internal fun resolveForegroundRecoveryAction(
    callState: CallState,
    hadActiveStream: Boolean,
    pendingAiResponse: String?,
    heardText: String,
): ForegroundRecoveryAction {
    if (heardText.trim().isNotEmpty()) return ForegroundRecoveryAction.RESUME_LISTENING
    if (callState == CallState.PROCESSING && hadActiveStream) return ForegroundRecoveryAction.RESTART_LLM_TURN
    if ((pendingAiResponse ?: "").trim().isNotEmpty()) return ForegroundRecoveryAction.RESUME_PENDING_PLAYBACK
    return ForegroundRecoveryAction.RESUME_LISTENING
}
