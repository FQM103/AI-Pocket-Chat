package com.situ.aichat.voice

import com.situ.aichat.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The voice-call notification phase→status-line map is 1:1 iOS `VoiceCallLiveActivityPhase.statusText`
 * (连接中/在听你说/你正在说/正在思考/正在回答/通话结束). We assert the resource id each [CallState] maps to
 * (R constants resolve in plain unit tests, no Android runtime needed for an id-equality check).
 */
class VoiceCallNotificationTextTest {

    @Test
    fun phaseStatusMapping_matchesIos() {
        assertEquals(R.string.voice_call_status_connecting, VoiceCallNotification.statusTextRes(CallState.DIALING))
        assertEquals(R.string.voice_call_status_listening, VoiceCallNotification.statusTextRes(CallState.LISTENING))
        assertEquals(R.string.voice_call_status_user_speaking, VoiceCallNotification.statusTextRes(CallState.USER_SPEAKING))
        assertEquals(R.string.voice_call_status_thinking, VoiceCallNotification.statusTextRes(CallState.PROCESSING))
        assertEquals(R.string.voice_call_status_ai_speaking, VoiceCallNotification.statusTextRes(CallState.AI_SPEAKING))
        assertEquals(R.string.voice_call_status_ended, VoiceCallNotification.statusTextRes(CallState.ENDING))
        // IDLE has no live notification, but the map falls back to 通话结束 (never shown).
        assertEquals(R.string.voice_call_status_ended, VoiceCallNotification.statusTextRes(CallState.IDLE))
    }
}
