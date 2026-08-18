package com.situ.aichat.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Call state-machine guards, assertions reverse-derived from the iOS call-site guards
 * (VoiceCallManager.swift + VoiceCallManager+STT/+AudioSession.swift line refs in [VoiceCallTransitions]).
 */
class VoiceCallTransitionsTest {

    @Test fun `start only from idle`() {
        assertTrue(VoiceCallTransitions.canStart(CallState.IDLE))
        CallState.entries.filter { it != CallState.IDLE }.forEach {
            assertFalse(it.name, VoiceCallTransitions.canStart(it))
        }
    }

    @Test fun `end allowed from active states, blocked from idle ending or already-ended`() {
        // Active, not yet ended → can end.
        listOf(CallState.DIALING, CallState.LISTENING, CallState.USER_SPEAKING, CallState.PROCESSING, CallState.AI_SPEAKING)
            .forEach { assertTrue(it.name, VoiceCallTransitions.canEnd(it, hasEnded = false)) }
        // idle / ending never end.
        assertFalse(VoiceCallTransitions.canEnd(CallState.IDLE, hasEnded = false))
        assertFalse(VoiceCallTransitions.canEnd(CallState.ENDING, hasEnded = false))
        // A second endCall is a no-op (hasEndedCall guard).
        assertFalse(VoiceCallTransitions.canEnd(CallState.AI_SPEAKING, hasEnded = true))
    }

    @Test fun `dialing completes to listening only while still dialing`() {
        assertEquals(CallState.LISTENING, VoiceCallTransitions.dialingComplete(CallState.DIALING, hasEnded = false))
        // Hung up during the 1.5 s wait → no longer dialing → ignored.
        assertNull(VoiceCallTransitions.dialingComplete(CallState.ENDING, hasEnded = false))
        assertNull(VoiceCallTransitions.dialingComplete(CallState.LISTENING, hasEnded = false))
        // Ended during the wait → ignored even if still nominally dialing.
        assertNull(VoiceCallTransitions.dialingComplete(CallState.DIALING, hasEnded = true))
    }

    @Test fun `voice detected only flips listening to userSpeaking`() {
        assertEquals(CallState.USER_SPEAKING, VoiceCallTransitions.voiceDetected(CallState.LISTENING))
        // Any other state ignores it (e.g. already userSpeaking, or aiSpeaking).
        CallState.entries.filter { it != CallState.LISTENING }.forEach {
            assertNull(it.name, VoiceCallTransitions.voiceDetected(it))
        }
    }

    @Test fun `barge-in only fires while AI is speaking`() {
        assertEquals(CallState.USER_SPEAKING, VoiceCallTransitions.bargeIn(CallState.AI_SPEAKING))
        CallState.entries.filter { it != CallState.AI_SPEAKING }.forEach {
            assertNull(it.name, VoiceCallTransitions.bargeIn(it))
        }
    }

    @Test fun `thinking barge-in only fires while processing`() {
        // C2 思考中可打断（超越 iOS）：仅 PROCESSING 有效；AI_SPEAKING 归 bargeIn、其余一律忽略。
        assertEquals(CallState.USER_SPEAKING, VoiceCallTransitions.thinkingBargeIn(CallState.PROCESSING))
        CallState.entries.filter { it != CallState.PROCESSING }.forEach {
            assertNull(it.name, VoiceCallTransitions.thinkingBargeIn(it))
        }
    }

    @Test fun `interruption pauses active states but not idle ending or ended`() {
        listOf(CallState.DIALING, CallState.LISTENING, CallState.USER_SPEAKING, CallState.PROCESSING, CallState.AI_SPEAKING)
            .forEach { assertTrue(it.name, VoiceCallTransitions.shouldPauseForInterruption(it, hasEnded = false)) }
        assertFalse(VoiceCallTransitions.shouldPauseForInterruption(CallState.IDLE, hasEnded = false))
        assertFalse(VoiceCallTransitions.shouldPauseForInterruption(CallState.ENDING, hasEnded = false))
        assertFalse(VoiceCallTransitions.shouldPauseForInterruption(CallState.LISTENING, hasEnded = true))
    }
}
