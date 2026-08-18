package com.situ.aichat.story

import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.remote.llm.LlmClient
import com.situ.aichat.data.remote.llm.StreamToken
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.ApiFunctionRouter
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.data.repository.StoryRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 全局文字忌口的**接线命门测试**（T2·MockK·图纸 §7 T2-3）。
 *
 * 为什么必须有这一条：两个 build* 的忌口参是**可选参**（`= null`，为不炸 30+ 处既有测试调用点），
 * 而可选参的历史教训就是「Service 忘传 → 静默丢功能」。纯函数层的测试（T1-2/T2-2）证明不了接线活着——
 * 所以断言点钉在**离 LLM 最近的可观测量**：真实生成路径送进 `llmClient.streamChat` 的 messages 里
 * 是否带着设置里的哨兵串。删掉 Service 那行实参，本例必红。
 *
 * 结构照既有 [StoryGenerationServiceTemperatureTest]（同一条通路的温度版）。
 */
class StoryGenerationServiceBannedWiringTest {

    private lateinit var llmClient: LlmClient
    private lateinit var storyRepository: StoryRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var collector: StoryCharacterDataCollector
    private lateinit var service: StoryGenerationService

    private val config = ApiConfigValues(
        providerType = ApiProviderType.OPENAI_COMPATIBLE, apiKey = "k",
        baseUrl = "https://example.test", modelName = "m",
    )

    /** 无限连载 + 已有弧线大纲 + 弧线起点近 → 隔离掉大纲那次 LLM 调用（同温度版用例）。 */
    private val story = StoryEntity(
        id = "s1", title = "书", genre = "言情",
        maxChapters = null, storyOutline = "弧线大纲", currentArcStartChapter = 5,
        cachedLatestChapterNumber = 5, cachedChapterCount = 5,
    )

    @Before
    fun setUp() {
        llmClient = mockk()
        storyRepository = mockk()
        settingsRepository = mockk()
        collector = mockk()
        val payloadResolver = mockk<StoryPayloadResolver>()
        val apiConfigRepository = mockk<ApiConfigRepository>()
        val apiFunctionRouter = mockk<ApiFunctionRouter>()
        val chatInfluenceBuilder = mockk<StoryChatInfluenceBuilder>()
        val worldInfoService = mockk<StoryWorldInfoService>()
        service = StoryGenerationService(
            llmClient = llmClient,
            contextLog = mockk(relaxed = true),
            storyRepository = storyRepository,
            apiConfigRepository = apiConfigRepository,
            apiFunctionRouter = apiFunctionRouter,
            storyChatInfluenceBuilder = chatInfluenceBuilder,
            storyCharacterDataCollector = collector,
            storyChapterMaterializer = mockk(relaxed = true),
            storyPayloadResolver = payloadResolver,
            storyWorldInfoService = worldInfoService,
            settingsRepository = settingsRepository,
            storyOutlineOrchestrator = mockk<StoryOutlineOrchestrator>().also { orchestrator ->
                coEvery { orchestrator.ensureOutline(any(), any(), any(), any()) } answers { firstArg() }
            },
            storyCompressionCoordinator = mockk(relaxed = true),
        )
        coEvery { apiConfigRepository.resolveConfigValues(ApiFunction.STORY_CREATION) } returns config
        coEvery { apiFunctionRouter.assignedId(ApiFunction.STORY_STRUCTURING) } returns null
        coEvery { storyRepository.getRoles("s1") } returns emptyList()
        coEvery { storyRepository.getLatestChapter("s1") } returns null
        coEvery { storyRepository.getChapterSummaries("s1") } returns emptyList()
        // 方向账本取数（图纸 2026-08-05 §3.1）：轻投影桩，空列表 = 账本 null = prompt 整段零注入
        coEvery { storyRepository.getChapterMetas("s1") } returns emptyList()
        // 生成入口的 join + 重读（卷一 chunk 2）：本组被测对象是调用方手头那个 story（含 .copy 覆盖），
        // 故意让重读落空走「回退入参快照」分支 —— 与改造前送进 prompt 装配的实体逐字等价。
        coEvery { storyRepository.getStory("s1") } returns null
        coEvery { collector.collectCharacterData(any(), any()) } returns emptyMap()
        coEvery { collector.collectVoiceCharacterData(any()) } returns emptyMap()
        coEvery { collector.collectProtagonistDynamicState(any()) } returns (null to null)
        coEvery { chatInfluenceBuilder.extractChatInfluence(any(), any()) } returns ""
        coEvery { worldInfoService.buildWorldInfoSection(any(), any(), any()) } returns null
        coEvery { payloadResolver.resolvePayload(any(), any(), any()) } returns
            StoryChapterPayload(title = "t", mood = "warm", content = "正文写到这里就结尾了。", hasChoice = false)
    }

    /** 把全局忌口设成 [setting]，跑一次生成，返回真正送进 LLM 的全部消息文本。 */
    private fun promptSentToLlm(setting: String?, firstChapter: Boolean): String {
        coEvery { settingsRepository.appSettings } returns flowOf(AppSettings(storyBannedExpressions = setting))
        val messagesSlot = slot<List<ChatMessageDto>>()
        every {
            llmClient.streamChat(capture(messagesSlot), any(), any(), any(), any(), any(), any(), any(), any())
        } returns flowOf(StreamToken.Content("正文写到这里就结尾了。"))
        runBlocking {
            if (firstChapter) {
                service.generateFirstChapter(
                    story.copy(cachedLatestChapterNumber = null, cachedChapterCount = 0),
                    nowMillis = 1_000L,
                )
            } else {
                service.generateNextChapter(story, nowMillis = 1_000L)
            }
        }
        return messagesSlot.captured.joinToString("\n") { it.content.orEmpty() }
    }

    @Test
    fun `全局忌口真的被送进续章创作请求`() {
        assertTrue(promptSentToLlm("SENTINEL_XYZ", firstChapter = false).contains("SENTINEL_XYZ"))
    }

    @Test
    fun `全局忌口真的被送进首章创作请求`() {
        assertTrue(promptSentToLlm("SENTINEL_XYZ", firstChapter = true).contains("SENTINEL_XYZ"))
    }

    @Test
    fun `未设置全局时送下去的是内置默认`() {
        val prompt = promptSentToLlm(null, firstChapter = false)
        assertTrue(prompt.contains("### 别写出 AI 味"))
        assertFalse(prompt.contains("SENTINEL_XYZ"))
    }

    @Test
    fun `全局清空后请求里没有忌口段`() {
        val prompt = promptSentToLlm("", firstChapter = false)
        assertFalse(prompt.contains("### 别写出 AI 味"))
        assertFalse(prompt.contains("映入眼帘"))
    }
}
