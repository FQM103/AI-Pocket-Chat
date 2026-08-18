package com.situ.aichat.diagnostics

import com.situ.aichat.data.local.dao.LogDao
import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.remote.llm.LlmClient
import com.situ.aichat.data.remote.llm.ResponseFormatDto
import com.situ.aichat.data.remote.llm.StreamToken
import com.situ.aichat.data.remote.llm.ToolCallChunk
import com.situ.aichat.data.repository.SettingsRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * T2-1（图纸 2026-07-10 日程专项 §7）：[ContextLogService.streamedCompletion] 的收流语义与日志契约。
 * 断言从图纸 §3.1 规格独立反推：只缓冲 Content、参数透传（tools=null·idle=240·responseFormat 原样）、
 * IOException 记错误后重抛、成功落库、CancellationException 穿透不记错误。
 */
class ContextLogServiceStreamingTest {

    private lateinit var llmClient: LlmClient
    private lateinit var logDao: LogDao
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var service: ContextLogService

    private val config = ApiConfigValues(
        providerType = ApiProviderType.DEEPSEEK, apiKey = "k", baseUrl = "https://example.test",
        modelName = "deepseek-v4-pro",
    )
    private val messages = listOf(ChatMessageDto(role = "system", content = "s"), ChatMessageDto(role = "user", content = "u"))
    private val jsonFormat = ResponseFormatDto(type = "json_object")

    @Before
    fun setUp() {
        llmClient = mockk()
        logDao = mockk(relaxed = true)
        settingsRepository = mockk()
        every { settingsRepository.appSettings } returns flowOf(AppSettings())
        service = ContextLogService(llmClient, logDao, settingsRepository, Json)
    }

    private fun stubStream(vararg tokens: StreamToken) {
        every {
            llmClient.streamChat(any(), any(), any(), any(), any(), any(), any(), any())
        } returns flowOf(*tokens)
    }

    @Test
    fun `只缓冲Content_丢弃Reasoning与ToolCallDelta`() = runBlocking {
        stubStream(
            StreamToken.Reasoning("思考中"),
            StreamToken.Content("{\"events\""),
            StreamToken.Reasoning("再想想"),
            StreamToken.ToolCallDelta(ToolCallChunk(index = 0, argumentChunk = "{}")),
            StreamToken.Content(":[]}"),
        )
        val text = service.streamedCompletion("测试源", "夏晴子", config, messages, temperature = 0.9, responseFormat = jsonFormat)
        assertEquals("{\"events\":[]}", text)
    }

    @Test
    fun `参数透传_tools恒null_idle默认240_responseFormat原样`() = runBlocking {
        stubStream(StreamToken.Content("ok"))
        service.streamedCompletion("测试源", "夏晴子", config, messages, temperature = 0.9, responseFormat = jsonFormat)
        verify {
            llmClient.streamChat(
                messages = messages,
                config = config,
                temperature = 0.9,
                maxTokens = null,
                responseFormat = jsonFormat,
                tools = null,
                idleTimeoutSec = ContextLogService.BACKGROUND_SSE_IDLE_TIMEOUT_SEC,
                onUsage = any(),
            )
        }
        assertEquals(240L, ContextLogService.BACKGROUND_SSE_IDLE_TIMEOUT_SEC)
    }

    @Test
    fun `流中断IOException_原样重抛`() {
        every { llmClient.streamChat(any(), any(), any(), any(), any(), any(), any(), any()) } returns flow {
            emit(StreamToken.Content("部分"))
            throw IOException("connection abort")
        }
        try {
            runBlocking { service.streamedCompletion("测试源", "夏晴子", config, messages) }
            fail("应重抛 IOException")
        } catch (e: IOException) {
            assertEquals("connection abort", e.message)
        }
    }

    @Test
    fun `流中断_recordError落库为失败条`() = runBlocking {
        every { llmClient.streamChat(any(), any(), any(), any(), any(), any(), any(), any()) } returns flow {
            throw IOException("boom")
        }
        runCatching { service.streamedCompletion("测试源", "夏晴子", config, messages) }
        coVerify(timeout = 2000) { logDao.insert(match { !it.isSuccess && it.source == "测试源" && it.errorMessage == "boom" }) }
    }

    @Test
    fun `成功_recordSuccess落库为成功条`() = runBlocking {
        stubStream(StreamToken.Content("done"))
        service.streamedCompletion("测试源", "夏晴子", config, messages)
        coVerify(timeout = 2000) {
            logDao.insert(match { it.isSuccess && it.source == "测试源" && it.modelName == "deepseek-v4-pro" && it.messageCount == 2 })
        }
    }

    @Test
    fun `取消穿透_不记错误条`() {
        every { llmClient.streamChat(any(), any(), any(), any(), any(), any(), any(), any()) } returns flow {
            throw CancellationException("cancelled")
        }
        var cancelled = false
        try {
            runBlocking { service.streamedCompletion("测试源", "夏晴子", config, messages) }
        } catch (e: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        Thread.sleep(200) // recordError 是 fire-and-forget：留窗确认「没有」失败条被写入
        coVerify(exactly = 0) { logDao.insert(match { !it.isSuccess }) }
    }
}
