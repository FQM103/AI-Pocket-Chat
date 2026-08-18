package com.situ.aichat.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Recovery-action resolution, assertions reverse-derived 1:1 from the iOS
 * `VoiceCallManagerCoreTests` + `VoiceCallManagerRecoveryTests` cases (the iOS source is the baseline,
 * not the Kotlin output — verification-process #2).
 */
class ForegroundRecoveryActionTest {

    @Test fun `heard text has highest priority even with pending and stream`() {
        // iOS: recovery_heardText优先级最高即使有pending和stream
        val action = resolveForegroundRecoveryAction(
            callState = CallState.PROCESSING,
            hadActiveStream = true,
            pendingAiResponse = "还有内容",
            heardText = "已听到",
        )
        assertEquals(ForegroundRecoveryAction.RESUME_LISTENING, action)
    }

    @Test fun `partly-spoken response resumes listening, never re-plays`() {
        // iOS: resolveForegroundRecoveryAction已播出部分内容时回到监听
        val action = resolveForegroundRecoveryAction(
            callState = CallState.AI_SPEAKING,
            hadActiveStream = false,
            pendingAiResponse = "剩余内容不该重复补播",
            heardText = "前半句已经播给用户听了",
        )
        assertEquals(ForegroundRecoveryAction.RESUME_LISTENING, action)
    }

    @Test fun `processing with active stream restarts the LLM turn`() {
        // iOS: resolveForegroundRecoveryAction处理进行中的流式回复
        val action = resolveForegroundRecoveryAction(
            callState = CallState.PROCESSING,
            hadActiveStream = true,
            pendingAiResponse = null,
            heardText = "",
        )
        assertEquals(ForegroundRecoveryAction.RESTART_LLM_TURN, action)
    }

    @Test fun `processing without an active stream falls back to listening`() {
        // iOS: recovery_processing状态无活跃stream回到监听
        val action = resolveForegroundRecoveryAction(
            callState = CallState.PROCESSING,
            hadActiveStream = false,
            pendingAiResponse = null,
            heardText = "",
        )
        assertEquals(ForegroundRecoveryAction.RESUME_LISTENING, action)
    }

    @Test fun `pending complete response resumes playback`() {
        // iOS: resolveForegroundRecoveryAction处理待播放的完整回复
        val action = resolveForegroundRecoveryAction(
            callState = CallState.AI_SPEAKING,
            hadActiveStream = false,
            pendingAiResponse = "我还在这里，继续陪你说下去。",
            heardText = "",
        )
        assertEquals(ForegroundRecoveryAction.RESUME_PENDING_PLAYBACK, action)
    }

    @Test fun `pending response is checked even from a non-aiSpeaking state`() {
        // iOS: recovery_listening状态有pendingResponse恢复播放
        val action = resolveForegroundRecoveryAction(
            callState = CallState.LISTENING,
            hadActiveStream = false,
            pendingAiResponse = "完整回复",
            heardText = "",
        )
        assertEquals(ForegroundRecoveryAction.RESUME_PENDING_PLAYBACK, action)
    }

    @Test fun `blank heard text is not treated as spoken, falls through to pending`() {
        // iOS: recovery空白heardText不算已播出
        val action = resolveForegroundRecoveryAction(
            callState = CallState.AI_SPEAKING,
            hadActiveStream = false,
            pendingAiResponse = "待播放内容",
            heardText = "   \n  ",
        )
        assertEquals(ForegroundRecoveryAction.RESUME_PENDING_PLAYBACK, action)
    }

    @Test fun `blank pending response does not trigger playback`() {
        // iOS: recovery空白pendingAIResponse不触发恢复播放
        val action = resolveForegroundRecoveryAction(
            callState = CallState.AI_SPEAKING,
            hadActiveStream = false,
            pendingAiResponse = "   ",
            heardText = "",
        )
        assertEquals(ForegroundRecoveryAction.RESUME_LISTENING, action)
    }

    @Test fun `idle state defaults to listening`() {
        // iOS: recovery_idle状态默认回到监听
        val action = resolveForegroundRecoveryAction(
            callState = CallState.IDLE,
            hadActiveStream = false,
            pendingAiResponse = null,
            heardText = "",
        )
        assertEquals(ForegroundRecoveryAction.RESUME_LISTENING, action)
    }

    @Test fun `ending state defaults to listening`() {
        // iOS: recovery_ending状态默认回到监听
        val action = resolveForegroundRecoveryAction(
            callState = CallState.ENDING,
            hadActiveStream = false,
            pendingAiResponse = null,
            heardText = "",
        )
        assertEquals(ForegroundRecoveryAction.RESUME_LISTENING, action)
    }

    @Test fun `null pending response with nothing heard resumes listening`() {
        val action = resolveForegroundRecoveryAction(
            callState = CallState.AI_SPEAKING,
            hadActiveStream = false,
            pendingAiResponse = null,
            heardText = "",
        )
        assertEquals(ForegroundRecoveryAction.RESUME_LISTENING, action)
    }
}
