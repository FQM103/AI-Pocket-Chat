package com.situ.aichat.data.remote.llm.tooldetection

import com.situ.aichat.data.model.ApiProviderType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * H6 检测可靠性：
 * - #8 探针对齐运行时：运行时只发 OpenAI 形状到 /v1/chat/completions，故原生 Anthropic/Gemini 探针退役，
 *   ANTHROPIC/GEMINI 改走 `OpenAiCompatibleToolDetector`（DeepSeek 保留专属探针）。
 * - #2 瞬时失败重试：`requestWithRetry` 对 429/5xx/网络异常退避重试，2xx 及其它 4xx 立即返回不浪费重试。
 */
class ToolCallingDetectorTest {

    // ── #8 探针选择 ──

    @Test fun anthropic_and_gemini_route_through_openai_compatible_probe() {
        assertTrue(ToolCallingDetectorFactory.make(ApiProviderType.ANTHROPIC) is OpenAiCompatibleToolDetector)
        assertTrue(ToolCallingDetectorFactory.make(ApiProviderType.GEMINI) is OpenAiCompatibleToolDetector)
    }

    @Test fun deepseek_keeps_own_probe_others_openai_compatible() {
        assertTrue(ToolCallingDetectorFactory.make(ApiProviderType.DEEPSEEK) is DeepSeekToolDetector)
        assertTrue(ToolCallingDetectorFactory.make(ApiProviderType.OPENAI_COMPATIBLE) is OpenAiCompatibleToolDetector)
        assertTrue(ToolCallingDetectorFactory.make(ApiProviderType.OPENROUTER) is OpenAiCompatibleToolDetector)
        assertTrue(ToolCallingDetectorFactory.make(ApiProviderType.MINIMAX) is OpenAiCompatibleToolDetector)
    }

    // ── #2 瞬时失败重试 ──

    private fun resp(code: Int) = ToolDetectionHttpResponse(statusCode = code, bodyText = "")

    @Test fun retries_transient_then_returns_success() = runBlocking {
        val codes = ArrayDeque(listOf(429, 503, 200))
        var calls = 0
        val r = ToolDetectionHttp.requestWithRetry(maxAttempts = 3, backoff = {}) {
            calls++
            resp(codes.removeFirst())
        }
        assertEquals(200, r.statusCode)
        assertEquals(3, calls)
    }

    @Test fun retries_408_and_425_as_transient() = runBlocking {
        // 408 Request Timeout / 425 Too Early 本质瞬时（§8.4 backlog）：与 429/5xx 同样退避重试，
        // 不再被当确定性失败立即落 UNSUPPORTED。
        val codes = ArrayDeque(listOf(408, 425, 200))
        var calls = 0
        val r = ToolDetectionHttp.requestWithRetry(maxAttempts = 3, backoff = {}) {
            calls++
            resp(codes.removeFirst())
        }
        assertEquals(200, r.statusCode)
        assertEquals(3, calls) // 408 → 425 → 200：前两次都退避重试
    }

    @Test fun definitive_4xx_returns_immediately_no_retry() = runBlocking {
        var calls = 0
        val r = ToolDetectionHttp.requestWithRetry(maxAttempts = 3, backoff = {}) {
            calls++
            resp(401)
        }
        assertEquals(401, r.statusCode)
        assertEquals(1, calls) // 401 = 确定性（非 408/425/429），不重试
    }

    @Test fun exhausted_transient_returns_last_response() = runBlocking {
        var calls = 0
        val r = ToolDetectionHttp.requestWithRetry(maxAttempts = 2, backoff = {}) {
            calls++
            resp(500)
        }
        assertEquals(500, r.statusCode)
        assertEquals(2, calls)
    }

    @Test fun ioexception_retried_then_rethrown_after_exhaustion() = runBlocking {
        var calls = 0
        try {
            ToolDetectionHttp.requestWithRetry(maxAttempts = 2, backoff = {}) {
                calls++
                throw IOException("net")
            }
            fail("应在耗尽重试后抛 IOException")
        } catch (e: IOException) {
            assertEquals(2, calls)
        }
        Unit
    }
}
