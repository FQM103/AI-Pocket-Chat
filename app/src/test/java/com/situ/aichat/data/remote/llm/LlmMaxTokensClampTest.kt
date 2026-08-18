package com.situ.aichat.data.remote.llm

import android.util.Log
import com.situ.aichat.data.model.ApiProviderType
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * 首调撞服务商输出硬顶降额自愈 T2（2026-07-27 故事超长章 5000 档捆绑·微图纸 chunk 2）。
 *
 * 背景：超长章档 maxTokens=12000（思考 36000）首次超过部分服务商输出硬顶（deepseek-chat 类 8192），
 * 服务商对超顶值直接 400 拒收整个请求且旧版不自愈（connectWithRetry 对 400 `throw err`）。
 * 防线 = 三条件（400 ∧ 报文点名 max_tokens ∧ 我方传值 > 8192）→ clamp [LlmClient.SAFE_RETRY_MAX_TOKENS] 重试恰一次。
 *
 * 手法照搬 [LlmCompletionEscalationTest] 先例：OkHttp 拦截器按序吐编排响应并记录请求体（零真网络、零新依赖）。
 */
class LlmMaxTokensClampTest {

    private val config = ApiConfigValues(
        providerType = ApiProviderType.OPENAI_COMPATIBLE,
        apiKey = "k",
        baseUrl = "https://example.test",
        modelName = "m",
    )

    private val requests = mutableListOf<String>()

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        requests.clear()
    }

    @After
    fun tearDown() = unmockkStatic(Log::class)

    /** deepseek-chat 风格的超顶报文（关键词在 readErrorBody 的 240 字截取内）。 */
    private val maxTokensRejectionBody =
        """{"error":{"message":"Invalid max_tokens value, the valid range of max_tokens is [1, 8192]","type":"invalid_request_error"}}"""

    private fun completionJson(content: String, finishReason: String) =
        """{"choices":[{"message":{"content":"$content"},"finish_reason":"$finishReason"}]}"""

    private fun sseBody(content: String) =
        "data: {\"choices\":[{\"delta\":{\"content\":\"$content\"}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
            "data: [DONE]\n\n"

    /** 按序吐 (HTTP 状态码, 响应体)，同 [LlmCompletionEscalationTest.clientResponding] 打法。 */
    private fun clientResponding(vararg responses: Pair<Int, String>): LlmClient {
        val queue = responses.toMutableList()
        val ok = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            requests.add(Buffer().also { req.body?.writeTo(it) }.readUtf8())
            val (code, body) = queue.removeAt(0)
            Response.Builder()
                .request(req).protocol(Protocol.HTTP_1_1).code(code).message(if (code == 200) "OK" else "Bad Request")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        return LlmClient(ok, Json { ignoreUnknownKeys = true })
    }

    private fun msgs() = listOf(ChatMessageDto(role = "user", content = "写一章"))

    @Test
    fun `非流式_首调400点名maxTokens且超顶_clamp8192重试一次拿到结果`() = runBlocking {
        val client = clientResponding(
            400 to maxTokensRejectionBody,
            200 to completionJson("整章内容", "stop"),
        )
        val result = client.completion(messages = msgs(), config = config, maxTokens = 12_000)
        assertEquals("整章内容", result)
        assertEquals(2, requests.size)
        assertTrue(requests[0].contains("\"max_tokens\":12000"))
        assertTrue(requests[1].contains("\"max_tokens\":8192"))
    }

    @Test
    fun `非流式_400不点名maxTokens_不重试原样抛`() = runBlocking {
        val client = clientResponding(
            400 to """{"error":{"message":"Model Not Exist","type":"invalid_request_error"}}""",
        )
        try {
            client.completion(messages = msgs(), config = config, maxTokens = 12_000)
            fail("应抛 LlmError.Http")
        } catch (e: LlmError.Http) {
            assertEquals(400, e.statusCode)
        }
        assertEquals(1, requests.size)
    }

    @Test
    fun `非流式_传值未超8192_点名maxTokens的400也不重试`() = runBlocking {
        // 值本来不超顶 → 降额无意义（400 另有原因），保持旧行为原样抛。
        val client = clientResponding(400 to maxTokensRejectionBody)
        try {
            client.completion(messages = msgs(), config = config, maxTokens = 8_000)
            fail("应抛 LlmError.Http")
        } catch (e: LlmError.Http) {
            assertEquals(400, e.statusCode)
        }
        assertEquals(1, requests.size)
    }

    @Test
    fun `非流式_clamp后仍400_恰两次请求后抛出`() = runBlocking {
        val client = clientResponding(
            400 to maxTokensRejectionBody,
            400 to maxTokensRejectionBody,
        )
        try {
            client.completion(messages = msgs(), config = config, maxTokens = 12_000)
            fail("应抛 LlmError.Http")
        } catch (e: LlmError.Http) {
            assertEquals(400, e.statusCode)
        }
        assertEquals(2, requests.size)
    }

    @Test
    fun `非流式_clamp后撞限_升额基数用生效值8192而非超顶原值`() = runBlocking {
        // 闭环锁：clamp 轮 finish=length → 升额 = 8192×3=24576（绝不能乘回 12000×3 的超顶原值）；
        // 升额仍被 400 拒 → 既有兜底退回 clamp 轮截断内容。
        val client = clientResponding(
            400 to maxTokensRejectionBody,
            200 to completionJson("半截内容", "length"),
            400 to maxTokensRejectionBody,
        )
        val result = client.completion(messages = msgs(), config = config, maxTokens = 12_000)
        assertEquals("半截内容", result)
        assertEquals(3, requests.size)
        assertTrue(requests[2].contains("\"max_tokens\":24576"))
        assertFalse(requests[2].contains("\"max_tokens\":36000"))
    }

    @Test
    fun `流式_首调400点名maxTokens且超顶_clamp8192重试后正常收流`() = runBlocking {
        val client = clientResponding(
            400 to maxTokensRejectionBody,
            200 to sseBody("她推开门"),
        )
        val contents = client.streamChat(messages = msgs(), config = config, maxTokens = 12_000)
            .filterIsInstance<StreamToken.Content>().toList()
        assertEquals("她推开门", contents.joinToString("") { it.text })
        assertEquals(2, requests.size)
        assertTrue(requests[0].contains("\"max_tokens\":12000"))
        assertTrue(requests[1].contains("\"max_tokens\":8192"))
    }

    // ── 复核 R1 #10 补缺（流式对称锁 + 谓词边界 + 回调透传）──

    @Test
    fun `流式_400不点名maxTokens_不重试原样抛`() = runBlocking {
        val client = clientResponding(
            400 to """{"error":{"message":"Model Not Exist","type":"invalid_request_error"}}""",
        )
        try {
            client.streamChat(messages = msgs(), config = config, maxTokens = 12_000).collect { }
            fail("应抛 LlmError.Http")
        } catch (e: LlmError.Http) {
            assertEquals(400, e.statusCode)
        }
        assertEquals(1, requests.size)
    }

    @Test
    fun `流式_clamp后仍400_恰两次请求后抛出`() = runBlocking {
        val client = clientResponding(
            400 to maxTokensRejectionBody,
            400 to maxTokensRejectionBody,
        )
        try {
            client.streamChat(messages = msgs(), config = config, maxTokens = 12_000).collect { }
            fail("应抛 LlmError.Http")
        } catch (e: LlmError.Http) {
            assertEquals(400, e.statusCode)
        }
        assertEquals(2, requests.size)
    }

    @Test
    fun `非流式_未传maxTokens_点名400也原样抛`() = runBlocking {
        // 谓词 (requestedMaxTokens ?: 0) > 8192 的 null 分支：没传上限就没有可降的，恒不重试。
        val client = clientResponding(400 to maxTokensRejectionBody)
        try {
            client.completion(messages = msgs(), config = config, maxTokens = null)
            fail("应抛 LlmError.Http")
        } catch (e: LlmError.Http) {
            assertEquals(400, e.statusCode)
        }
        assertEquals(1, requests.size)
    }

    @Test
    fun `非流式_报文大写MAX_TOKENS也认_且finishReason透传成功轮`() = runBlocking {
        val upperBody = """{"error":{"message":"Invalid MAX_TOKENS: must be in [1, 8192]","type":"invalid_request_error"}}"""
        var finish: String? = "unset"
        val client = clientResponding(
            400 to upperBody,
            200 to completionJson("整章内容", "stop"),
        )
        val result = client.completion(
            messages = msgs(), config = config, maxTokens = 12_000,
            onFinishReason = { finish = it },
        )
        assertEquals("整章内容", result)
        assertEquals(2, requests.size)
        assertEquals("stop", finish)
    }
}
