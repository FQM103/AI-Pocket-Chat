package com.situ.aichat.story

import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.remote.llm.ApiConfigValues
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
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 故事创作温度端到端贯通行为测试（T2·MockK·图纸 §7 T2-2 / §3.1 通路）。
 *
 * 证明链：设置快照（[AppSettings.sanitizedStoryCreationTemperature]）→ `makeGenerationRequest` →
 * `StoryGenerationRequest.temperature` → `llmClient.streamChat(temperature=)`。断言点取**流式请求实收的温度**
 * （requestCreation 原样传 request.temperature），是这条通路上离 LLM 最近的可观测量——比直接读 request 更强。
 *
 * 注：思考模型「不发温度」由 [LlmClient.resolveEffectiveTemperature] 保险丝在**更下游**决定（既有
 * `LlmTemperaturePolicyTest` 看护·本卷零碰），故此处断言的是「服务确实把设置值原样交了下去」。
 */
class StoryGenerationServiceTemperatureTest {

    private lateinit var llmClient: LlmClient
    private lateinit var storyRepository: StoryRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var collector: StoryCharacterDataCollector
    private lateinit var service: StoryGenerationService

    private val config = ApiConfigValues(
        providerType = ApiProviderType.OPENAI_COMPATIBLE, apiKey = "k",
        baseUrl = "https://example.test", modelName = "m",
    )

    /** 无限连载 + 已有弧线大纲 + 弧线起点近 → decideOutlineAction=None（隔离掉大纲那次 LLM 调用）。 */
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
            // 大纲编排外搬（卷二 C4 文件瘦身）：本组用例不碰大纲面，桩成**恒等直通**——
            // 与外搬前「无需生成大纲时 ensureOutline 原样返回入参」的行为一致，后续步骤照旧拿到同一个故事。
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
        // 句末标点收尾 → isContentTruncated=false，隔离掉续写补丁路（那条路的温度由 C5b 的守卫测试单独看）。
        coEvery { payloadResolver.resolvePayload(any(), any(), any()) } returns
            StoryChapterPayload(title = "t", mood = "warm", content = "正文写到这里就结尾了。", hasChoice = false)
    }

    /** 把设置写成 [setting]，跑一次续章生成，返回 streamChat 实收的温度。 */
    private fun temperatureSentForNextChapter(setting: Double): Double? {
        coEvery { settingsRepository.appSettings } returns flowOf(AppSettings(storyCreationTemperature = setting))
        val tempSlot = slot<Double?>()
        every {
            llmClient.streamChat(any(), any(), captureNullable(tempSlot), any(), any(), any(), any(), any(), any())
        } returns flowOf(StreamToken.Content("正文写到这里就结尾了。"))
        runBlocking { service.generateNextChapter(story, nowMillis = 1_000L) }
        return tempSlot.captured
    }

    @Test
    fun `续章创作温度取设置快照_不再是硬编码0点7`() {
        assertEquals(1.0, temperatureSentForNextChapter(1.0)!!, 0.0)
    }

    @Test
    fun `改设置即改创作温度`() {
        assertEquals(0.3, temperatureSentForNextChapter(0.3)!!, 0.0)
        assertEquals(1.8, temperatureSentForNextChapter(1.8)!!, 0.0)
    }

    @Test
    fun `脏设置值走sanitized_绝不把NaN或越界值发给模型`() { // E2 端到端
        assertEquals(1.0, temperatureSentForNextChapter(Double.NaN)!!, 0.0)
        assertEquals(2.0, temperatureSentForNextChapter(9.0)!!, 0.0)
    }

    @Test
    fun `首章创作温度同样取设置快照`() {
        coEvery { settingsRepository.appSettings } returns flowOf(AppSettings(storyCreationTemperature = 0.6))
        val tempSlot = slot<Double?>()
        every {
            llmClient.streamChat(any(), any(), captureNullable(tempSlot), any(), any(), any(), any(), any(), any())
        } returns flowOf(StreamToken.Content("第一章写到这里就结尾了。"))
        runBlocking {
            service.generateFirstChapter(
                story.copy(cachedLatestChapterNumber = null, cachedChapterCount = 0),
                nowMillis = 1_000L,
            )
        }
        assertEquals(0.6, tempSlot.captured!!, 0.0)
    }
}
