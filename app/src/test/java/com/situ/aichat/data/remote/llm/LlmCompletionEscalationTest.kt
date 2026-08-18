package com.situ.aichat.data.remote.llm

import android.util.Log
import com.situ.aichat.data.model.ApiProviderType
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
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
import org.junit.Before
import org.junit.Test

/**
 * 非流式撞限升额重试 T2（2026-07-11 拍板「读 finish_reason + 撞限 ×3 重试一次」）。
 * 手法：OkHttp 拦截器按序吐编排响应并记录请求体（零真网络、零新测试依赖），验证：
 * length+有上限 → 恰两次请求且第二次 max_tokens ×3、返回第二次内容；stop → 单请求；
 * length+无上限（服务商侧截断，升不了）→ 单请求原样返回。
 */
class LlmCompletionEscalationTest {

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

    private fun responseJson(content: String, finishReason: String?) =
        """{"choices":[{"message":{"content":"$content"},"finish_reason":${finishReason?.let { "\"$it\"" } ?: "null"}}]}"""

    private fun clientReturning(vararg bodies: String): LlmClient =
        clientResponding(*bodies.map { 200 to it }.toTypedArray())

    /** 按序吐 (HTTP 状态码, 响应体)：可编排「首次 200 截断 → 升额被 400 拒」等序列。 */
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

    @Test
    fun `length且有上限_升额x3重试一次_返回第二次内容`() = runBlocking {
        val client = clientReturning(
            responseJson("被掐断的半截", "length"),
            responseJson("完整结果", "stop"),
        )
        val result = client.completion(
            messages = listOf(ChatMessageDto(role = "user", content = "压缩")),
            config = config,
            maxTokens = 800,
        )
        assertEquals("完整结果", result)
        assertEquals("恰两次请求", 2, requests.size)
        assertTrue("首次带原上限", requests[0].contains("\"max_tokens\":800"))
        assertTrue("重试上限 ×3", requests[1].contains("\"max_tokens\":2400"))
    }

    @Test
    fun `二次仍length_原样返回交下游守卫_不再升`() = runBlocking {
        val client = clientReturning(
            responseJson("半截一", "length"),
            responseJson("半截二", "length"),
        )
        val result = client.completion(
            messages = listOf(ChatMessageDto(role = "user", content = "压缩")),
            config = config,
            maxTokens = 800,
        )
        assertEquals("半截二", result)
        assertEquals("只升一次·恰两次请求", 2, requests.size)
    }

    @Test
    fun `stop自然写完_单请求不升额`() = runBlocking {
        val client = clientReturning(responseJson("正常结果", "stop"))
        val result = client.completion(
            messages = listOf(ChatMessageDto(role = "user", content = "压缩")),
            config = config,
            maxTokens = 800,
        )
        assertEquals("正常结果", result)
        assertEquals(1, requests.size)
    }

    @Test
    fun `length但未设上限_服务商侧截断升不了_单请求原样返回`() = runBlocking {
        val client = clientReturning(responseJson("服务商截断的内容", "length"))
        val result = client.completion(
            messages = listOf(ChatMessageDto(role = "user", content = "总结")),
            config = config,
            maxTokens = null,
        )
        assertEquals("服务商截断的内容", result)
        assertEquals(1, requests.size)
    }

    /** 🔵2 合并语义（升额重试 × 记忆护栏 G2）：回调必须回报**最终一次**尝试——升额后成功须收 "stop"，护栏才不误拒好结果。 */
    @Test
    fun `升额后第二次成功_onFinishReason回报最终一次stop`() = runBlocking {
        val client = clientReturning(
            responseJson("被掐断的半截", "length"),
            responseJson("完整结果", "stop"),
        )
        val reported = mutableListOf<String?>()
        val result = client.completion(
            messages = listOf(ChatMessageDto(role = "user", content = "压缩")),
            config = config,
            maxTokens = 800,
            onFinishReason = { reported.add(it) },
        )
        assertEquals("完整结果", result)
        assertEquals("回调恰一次·报最终尝试", listOf<String?>("stop"), reported)
    }

    /** 🔵1 升额可能超服务商输出硬顶被 400 拒：退回首轮截断结果（半份不白丢），finish_reason 如实保持撞限信号。 */
    @Test
    fun `升额被服务商400拒_退回首轮截断结果_回调仍length`() = runBlocking {
        val client = clientResponding(
            200 to responseJson("被掐断的半截", "length"),
            400 to """{"error":{"message":"max_tokens too large"}}""",
        )
        val reported = mutableListOf<String?>()
        val result = client.completion(
            messages = listOf(ChatMessageDto(role = "user", content = "压缩")),
            config = config,
            maxTokens = 800,
            onFinishReason = { reported.add(it) },
        )
        assertEquals("半份到手不白丢", "被掐断的半截", result)
        assertEquals("恰两次请求（400 不走退避重试）", 2, requests.size)
        assertEquals("如实上报截断·调用方守卫拒收", listOf<String?>("length"), reported)
    }

    @Test
    fun `finishReason判定_标准与兼容拼法_大小写不敏感`() {
        assertTrue(LlmClient.isLengthTruncated("length"))
        assertTrue(LlmClient.isLengthTruncated("max_tokens"))
        assertTrue(LlmClient.isLengthTruncated("LENGTH"))
        assertFalse(LlmClient.isLengthTruncated("stop"))
        assertFalse(LlmClient.isLengthTruncated(null))
    }
}
