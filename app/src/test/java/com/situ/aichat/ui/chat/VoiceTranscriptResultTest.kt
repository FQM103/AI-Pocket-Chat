package com.situ.aichat.ui.chat

import com.situ.aichat.prompt.PromptBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P1-41/42 转写失败链纯函数：[classifyTranscriptOutcome] 三粒度分流（参照 iOS
 * SpeechRecognitionService.SpeechError:181-185 的 notAvailable/timedOut + sherpa 空串）与
 * [applyingTranscriptResult] 落草稿（成功刷转写 / 失败恢复占位记粒度，两路均复位 pending）。
 */
class VoiceTranscriptResultTest {

    @Test
    fun `engine unavailable classifies as UNAVAILABLE`() {
        val result = classifyTranscriptOutcome(available = false, engineReturnedNull = false, timedOut = false, text = null)
        assertEquals(VoiceTranscriptResult.Failure(VoiceTranscriptFailure.UNAVAILABLE), result)
    }

    @Test
    fun `engine returning null mid-run classifies as UNAVAILABLE`() {
        // transcribe 返回 null=引擎自查不可用的竞态防御（isAvailable 刚过、内部加载又失败）。
        val result = classifyTranscriptOutcome(available = true, engineReturnedNull = true, timedOut = false, text = null)
        assertEquals(VoiceTranscriptResult.Failure(VoiceTranscriptFailure.UNAVAILABLE), result)
    }

    @Test
    fun `internal timeout classifies as TIMEOUT`() {
        val result = classifyTranscriptOutcome(available = true, engineReturnedNull = false, timedOut = true, text = null)
        assertEquals(VoiceTranscriptResult.Failure(VoiceTranscriptFailure.TIMEOUT), result)
    }

    @Test
    fun `blank text classifies as EMPTY`() {
        val empty = classifyTranscriptOutcome(available = true, engineReturnedNull = false, timedOut = false, text = "")
        assertEquals(VoiceTranscriptResult.Failure(VoiceTranscriptFailure.EMPTY), empty)
        val whitespace = classifyTranscriptOutcome(available = true, engineReturnedNull = false, timedOut = false, text = "  ")
        assertEquals(VoiceTranscriptResult.Failure(VoiceTranscriptFailure.EMPTY), whitespace)
    }

    @Test
    fun `non-blank text classifies as Success`() {
        val result = classifyTranscriptOutcome(available = true, engineReturnedNull = false, timedOut = false, text = "你好")
        assertEquals(VoiceTranscriptResult.Success("你好"), result)
    }

    private val draft = VoiceDraftState(
        id = "voice_draft_test",
        audioPath = "voice/clip.wav",
        durationSec = 2.5,
        transcript = PromptBuilder.VOICE_MESSAGE_PLACEHOLDER,
        isTranscriptPending = true,
    )

    @Test
    fun `applying success refreshes transcript and clears pending and failure`() {
        val applied = draft.applyingTranscriptResult(VoiceTranscriptResult.Success("今晚一起散步吗"))
        assertEquals("今晚一起散步吗", applied.transcript)
        assertFalse(applied.isTranscriptPending)
        assertNull(applied.transcriptFailure)
        // 身份字段不变。
        assertEquals(draft.id, applied.id)
        assertEquals(draft.audioPath, applied.audioPath)
        assertEquals(draft.durationSec, applied.durationSec, 0.0)
    }

    @Test
    fun `applying failure restores placeholder and records kind`() {
        val applied = draft.copy(transcript = "残留旧转写", isTranscriptPending = true)
            .applyingTranscriptResult(VoiceTranscriptResult.Failure(VoiceTranscriptFailure.EMPTY))
        assertEquals(PromptBuilder.VOICE_MESSAGE_PLACEHOLDER, applied.transcript)
        assertFalse(applied.isTranscriptPending)
        assertEquals(VoiceTranscriptFailure.EMPTY, applied.transcriptFailure)
    }

    @Test
    fun `applying timeout failure records TIMEOUT kind`() {
        val applied = draft.applyingTranscriptResult(VoiceTranscriptResult.Failure(VoiceTranscriptFailure.TIMEOUT))
        assertEquals(VoiceTranscriptFailure.TIMEOUT, applied.transcriptFailure)
        assertEquals(PromptBuilder.VOICE_MESSAGE_PLACEHOLDER, applied.transcript)
    }
}
