package com.situ.aichat.voice

/**
 * Voice-call state machine, 1:1 iOS `VoiceCallManager.CallState` (VoiceCallManager.swift:16-24):
 * ```
 * idle → dialing → listening ⇄ userSpeaking → processing → aiSpeaking → (listening | ending)
 * ```
 * The orchestrator ([VoiceCallController]) holds the live state in a `StateFlow`; iOS uses an
 * `@Observable` view model, but the Android-idiomatic call lives in a `@Singleton` so it survives the
 * Compose UI being torn down (and, from 10.1g, is owned by the foreground service that keeps the call
 * alive under HyperOS background limits).
 *
 * The per-event guards — which transitions iOS actually allows from which state — are pulled out into
 * [VoiceCallTransitions] as pure functions so they unit-test without a device (verification-process #2),
 * exactly as iOS scatters the guards across its call sites.
 */
enum class CallState {
    /** Not started. `startCall()` only runs from here (VoiceCallManager.swift:133). */
    IDLE,

    /** Dialing: after a 1.5 s wait the call advances to [LISTENING] (VoiceCallManager.swift:178-182). */
    DIALING,

    /** Listening to the user. First non-empty STT text flips to [USER_SPEAKING]. */
    LISTENING,

    /** User is speaking. 1.2 s of trailing silence ends the turn → [PROCESSING]. */
    USER_SPEAKING,

    /** Thinking: STT stopped, user message saved, LLM streaming (the turn pipeline lands in 10.1f). */
    PROCESSING,

    /** AI is speaking. Barge-in monitoring starts after a 0.5 s protection window. */
    AI_SPEAKING,

    /** Hanging up. The UI dismisses 0.8 s after entering this state (10.1h). */
    ENDING,
}

/**
 * The legal state transitions, reverse-derived from the iOS call-site guards (not from a transition
 * table — iOS has none; each `transition(to:)` is gated where it is called). Each function returns the
 * next state, or `null` when the event must be ignored in the current state. Pure + `internal` so the
 * unit tests assert against the iOS guards directly.
 */
internal object VoiceCallTransitions {

    /** startCall runs only from idle (VoiceCallManager.swift:133). */
    fun canStart(state: CallState): Boolean = state == CallState.IDLE

    /**
     * endCall is allowed unless already idle/ending or a previous endCall already ran
     * (VoiceCallManager.swift:188-190: `guard state != .idle && state != .ending; guard !hasEndedCall`).
     */
    fun canEnd(state: CallState, hasEnded: Boolean): Boolean =
        !hasEnded && state != CallState.IDLE && state != CallState.ENDING

    /**
     * After the 1.5 s dial wait, advance to listening only if still dialing and not ended
     * (VoiceCallManager.swift:179-182). A hang-up during the wait leaves the call non-dialing → ignored.
     */
    fun dialingComplete(state: CallState, hasEnded: Boolean): CallState? =
        if (!hasEnded && state == CallState.DIALING) CallState.LISTENING else null

    /**
     * First non-empty recognition flips listening→userSpeaking; any other state ignores it
     * (VoiceCallManager+STT.swift:21-26 — `switch state { case .listening: … default: break }`).
     */
    fun voiceDetected(state: CallState): CallState? =
        if (state == CallState.LISTENING) CallState.USER_SPEAKING else null

    /**
     * A barge-in (energy or recognition) fires only while the AI is speaking; otherwise ignored
     * (VoiceCallManager+STT.swift:51 + 119: `guard state == .aiSpeaking`).
     */
    fun bargeIn(state: CallState): CallState? =
        if (state == CallState.AI_SPEAKING) CallState.USER_SPEAKING else null

    /**
     * Thinking-stage barge-in（C2 思考中可打断·2026-07-12·有意超越 iOS——iOS 在 processing 全程关麦，
     * 用户被锁在等待里）：PROCESSING 期间识别到非附和语音 → 作废当前轮、以新话开新轮。仅 PROCESSING 有效；
     * AI_SPEAKING 归 [bargeIn]，其余状态忽略。
     */
    fun thinkingBargeIn(state: CallState): CallState? =
        if (state == CallState.PROCESSING) CallState.USER_SPEAKING else null

    /**
     * Audio-focus loss (incoming call etc.) or the app being backgrounded pauses the current turn into
     * processing, unless the call is idle/ending or already ended
     * (VoiceCallManager+AudioSession.swift:216-217 background, :284-285 interruption).
     */
    fun shouldPauseForInterruption(state: CallState, hasEnded: Boolean): Boolean =
        !hasEnded && state != CallState.IDLE && state != CallState.ENDING
}
