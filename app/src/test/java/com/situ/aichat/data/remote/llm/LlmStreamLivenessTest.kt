package com.situ.aichat.data.remote.llm

import android.util.Log
import com.situ.aichat.data.model.ApiProviderType
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * T2（2026-08-25 通话看门狗「静默思考不误判」）：OkHttp 拦截器按序吐 SSE 固定报文（零真网络、零新依赖，
 * 手法同 [LlmCompletionEscalationTest]），验证 [LlmClient.streamChat] 新尾参 onSseLine——
 * 每读到一行原始 SSE（数据行 / keep-alive 注释行 / 空分隔行）回调恰一次、EOF 不回调；
 * token 流与错误行行为与「不传新参」时一致（尾参默认 null 零波及）。
 */
class LlmStreamLivenessTest {

    private val config = ApiConfigValues(
        providerType = ApiProviderType.OPENAI_COMPATIBLE,
        apiKey = "k",
        baseUrl = "https://example.test",
        modelName = "m",
    )

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
    }

    @After
    fun tearDown() = unmockkStatic(Log::class)

    private fun clientReturning(sseBody: String): LlmClient {
        val ok = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            Response.Builder()
                .request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(sseBody.toResponseBody("text/event-stream".toMediaType()))
                .build()
        }.build()
        return LlmClient(ok, Json { ignoreUnknownKeys = true })
    }

    /** 锁定报文（图纸 §7 T2-1）：2 个 keep-alive 注释行 + 3 个空分隔行 + 2 个 data 行 = 恰 7 行。 */
    private val lockedSse = buildString {
        appendLine(": OPENROUTER PROCESSING")
        appendLine()
        appendLine(": OPENROUTER PROCESSING")
        appendLine()
        appendLine("""data: {"choices":[{"delta":{"content":"喂"}}]}""")
        appendLine()
        append("data: [DONE]")
    }

    @Test
    fun `SSE每行活性回调恰7次且token流为单个内容token`() = runBlocking {
        val ticks = AtomicInteger(0)
        val tokens = clientReturning(lockedSse).streamChat(
            messages = listOf(ChatMessageDto(role = "user", content = "喂")),
            config = config,
            onSseLine = { ticks.incrementAndGet() },
        ).toList()
        assertEquals(listOf<StreamToken>(StreamToken.Content("喂")), tokens)
        assertEquals("7 行报文应恰回调 7 次", 7, ticks.get())
    }

    @Test
    fun `onSseLine缺省时token流与显式传回调完全一致`() = runBlocking {
        val withCallback = clientReturning(lockedSse).streamChat(
            messages = listOf(ChatMessageDto(role = "user", content = "喂")),
            config = config,
            onSseLine = {},
        ).toList()
        val withoutCallback = clientReturning(lockedSse).streamChat(
            messages = listOf(ChatMessageDto(role = "user", content = "喂")),
            config = config,
        ).toList()
        assertEquals(listOf<StreamToken>(StreamToken.Content("喂")), withCallback)
        assertEquals("缺省新参的 token 流必须与显式传回调字节级一致", withCallback, withoutCallback)
    }

    @Test
    fun `错误JSON行先tick后抛且活性计数为3`() = runBlocking {
        val ticks = AtomicInteger(0)
        try {
            clientReturning(": keep-alive\n\ndata: {\"error\":{\"message\":\"boom\"}}").streamChat(
                messages = listOf(ChatMessageDto(role = "user", content = "喂")),
                config = config,
                onSseLine = { ticks.incrementAndGet() },
            ).toList()
            fail("错误 JSON 行应抛 LlmError.Stream")
        } catch (e: LlmError.Stream) {
            assertEquals("boom", e.detail)
        }
        assertEquals("注释行+空行+错误行各 tick 一次（先 tick 后抛）", 3, ticks.get())
    }
}
