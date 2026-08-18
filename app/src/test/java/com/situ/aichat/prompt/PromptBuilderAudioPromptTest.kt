package com.situ.aichat.prompt

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 锁定语音消息音频段「文字段」的拼装（P13.4b）。逐字反推自 iOS `PromptBuilder.buildAudioPrompt`（:715-723）：
 * 前缀 + 转写参考；转写为空/占位（`[语音消息]` / `[Voice Message]`）→ 用「未提供转写」标记。前缀/标记由调用方经
 * PromptStrings 本地化注入（这里用 iOS zh-Hans 实文案断言中文设备行为，再补一组 en）。
 */
class PromptBuilderAudioPromptTest {

    // iOS zh-Hans（中文设备实际发送的包装）。
    private val zhPrefix = "这是一条用户发送的语音消息。若你能解析后续音频内容，请优先依据音频理解；转写参考："
    private val zhNoTranscript = "（未提供转写）"

    // iOS en。
    private val enPrefix =
        "This is a voice message sent by the user. If you can parse the attached audio, " +
            "prioritize understanding it from the audio itself. Transcript reference: "
    private val enNoTranscript = "(No transcript provided)"

    private fun zh(transcript: String) = PromptBuilder.buildAudioPrompt(transcript, zhPrefix, zhNoTranscript)
    private fun en(transcript: String) = PromptBuilder.buildAudioPrompt(transcript, enPrefix, enNoTranscript)

    @Test fun `real transcript is referenced verbatim after the localized prefix`() {
        assertEquals(zhPrefix + "今天天气真好", zh("今天天气真好"))
        assertEquals(enPrefix + "hello there", en("hello there"))
    }

    @Test fun `transcript is trimmed`() {
        assertEquals(zhPrefix + "你好", zh("  你好  "))
    }

    @Test fun `empty transcript falls back to the localized no-transcript marker`() {
        assertEquals(zhPrefix + zhNoTranscript, zh(""))
        assertEquals(zhPrefix + zhNoTranscript, zh("   "))
        assertEquals(enPrefix + enNoTranscript, en(""))
    }

    @Test fun `placeholder transcripts fall back to the no-transcript marker`() {
        assertEquals(zhPrefix + zhNoTranscript, zh("[语音消息]"))
        assertEquals(zhPrefix + zhNoTranscript, zh("  [语音消息]  "))
        assertEquals(zhPrefix + zhNoTranscript, zh("[Voice Message]"))
        assertEquals(enPrefix + enNoTranscript, en("[语音消息]"))
    }
}
