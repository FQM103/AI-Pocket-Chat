package com.situ.aichat.data.remote.llm

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式 `finish_reason` 观测点的两条前提（卷一 V8·图纸 §3.2/§4.11）。
 *
 * 为什么必须钉住这两条：`LlmClient.streamChat` 的 onFinishReason 回调**有意放在 `delta ?: continue` 卫兵之前**——
 * 真实末帧几乎总是「delta 为空 + finish_reason 有值」，回调若放在卫兵之后就永远收不到撞限信号，
 * V8 的续写守卫会静默失效（且症状只在长章撞顶时偶现，极难察觉）。
 *
 * 端到端的「回调 → 是否续写」判定由 `StoryGenerationServiceTruncationGuardTest` 覆盖（本仓库无 MockWebServer，
 * 不为此新增测试依赖；真 SSE 收流已由既有 LLM 层测试与装机实跑兜）。
 */
class StreamFinishReasonParsingTest {

    // 与 NetworkModule.provideJson 同配置。
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
        isLenient = true
    }

    @Test fun final_frame_carries_finish_reason_with_empty_delta() {
        // OpenAI/DeepSeek 撞上限时的真实末帧形状：delta 空对象 + finish_reason=length
        val payload = """{"choices":[{"delta":{},"finish_reason":"length"}],"usage":{"completion_tokens":8000}}"""
        val chunk = json.decodeFromString(StreamChunkDto.serializer(), payload)
        val choice = chunk.choices.single()
        assertEquals("length", choice.finishReason)
        assertNull(choice.delta.content) // delta 无内容 → 回调必须在 delta 卫兵之前才收得到
        assertTrue(LlmClient.isLengthTruncated(choice.finishReason))
    }

    @Test fun normal_frames_have_null_finish_reason() {
        // 正文流动期：finish_reason 缺省为 null → 不回调（takeIf { isNotEmpty } 也挡掉空串）
        val payload = """{"choices":[{"delta":{"content":"她推开门"}}]}"""
        val chunk = json.decodeFromString(StreamChunkDto.serializer(), payload)
        assertNull(chunk.choices.single().finishReason)
        assertEquals("她推开门", chunk.choices.single().delta.content)
    }

    @Test fun choiceless_usage_frame_yields_no_finish_reason() {
        // 部分中转把 usage 单独发在无 choices 的帧里 → firstOrNull() = null，回调链安全短路
        val payload = """{"choices":[],"usage":{"completion_tokens":123}}"""
        val chunk = json.decodeFromString(StreamChunkDto.serializer(), payload)
        assertNull(chunk.choices.firstOrNull()?.finishReason)
        assertEquals(123, chunk.usage?.completionTokens)
    }

    @Test fun stop_is_not_length_truncation() {
        assertEquals(false, LlmClient.isLengthTruncated("stop"))
        assertEquals(false, LlmClient.isLengthTruncated(null))
        assertTrue(LlmClient.isLengthTruncated("max_tokens")) // 兼容层用词
    }
}
