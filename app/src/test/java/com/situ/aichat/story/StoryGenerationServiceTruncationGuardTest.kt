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
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test

/**
 * 流式截断守卫行为测试（T2·MockK·图纸 §7 T2-6/T2-7·E11/E12/E13·V8）。
 *
 * V8 新增的续写触发条件恰为：**finish_reason 撞限 ∧ 原始输出不含 `---METADATA---` 分隔符**。
 * 设计理由（J4）：撞限若发生在元数据区，正文其实已经写完，续写反而画蛇添足——那种情况仍交既有句末标点启发式。
 *
 * 手法：streamChat 的 `answers` 里主动回调 onFinishReason（第 9 个形参），模拟 provider 末帧信号；
 * 各例的 payload 内容一律以句末标点收尾，好让**启发式恒不触发** → 观察到的 requestContinuation 调用
 * 只可能来自 V8 这条新路（回退路径的断言取该路独有的特征值·PITFALLS §1e）。
 */
class StoryGenerationServiceTruncationGuardTest {

    private lateinit var llmClient: LlmClient
    private lateinit var payloadResolver: StoryPayloadResolver
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var service: StoryGenerationService

    private val config = ApiConfigValues(
        providerType = ApiProviderType.OPENAI_COMPATIBLE, apiKey = "k",
        baseUrl = "https://example.test", modelName = "m",
    )

    private val story = StoryEntity(id = "s1", title = "书", genre = "言情")

    @Before
    fun setUp() {
        llmClient = mockk()
        payloadResolver = mockk()
        settingsRepository = mockk()
        val apiConfigRepository = mockk<ApiConfigRepository>()
        val apiFunctionRouter = mockk<ApiFunctionRouter>()
        val storyRepository = mockk<StoryRepository>()
        service = StoryGenerationService(
            llmClient = llmClient,
            contextLog = mockk(relaxed = true),
            storyRepository = storyRepository,
            apiConfigRepository = apiConfigRepository,
            apiFunctionRouter = apiFunctionRouter,
            storyChatInfluenceBuilder = mockk(),
            storyCharacterDataCollector = mockk(),
            storyChapterMaterializer = mockk(relaxed = true),
            storyPayloadResolver = payloadResolver,
            storyWorldInfoService = mockk(),
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
        coEvery { storyRepository.getChapterSummaries("s1") } returns emptyList()
        coEvery { settingsRepository.appSettings } returns flowOf(AppSettings(storyCreationTemperature = 0.9))
        coEvery { payloadResolver.requestContinuation(any(), any(), any(), any()) } returns "补完的正文。"
    }

    /** 让 streamChat 吐 [raw] 并在末帧回调 [finishReason]；resolvePayload 的 content 恒以句末标点收尾。 */
    private fun runGeneration(raw: String, finishReason: String?) {
        every { llmClient.streamChat(any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            arg<((String?) -> Unit)?>(8)?.invoke(finishReason)
            flowOf(StreamToken.Content(raw))
        }
        coEvery { payloadResolver.resolvePayload(any(), any(), any()) } returns
            StoryChapterPayload(title = "t", mood = "warm", content = "正文这一句是完整收尾的。", hasChoice = false)
        val request = StoryGenerationRequest(messages = emptyList(), maxTokens = 5_000, temperature = 0.9)
        runBlocking { service.generateChapter(story, chapterNumber = 3, request = request, nowMillis = 1_000L) }
    }

    @Test
    fun `撞限但已吐出METADATA分隔符_不触发续写`() = runBlocking { // E11 / T2-6
        runGeneration(raw = "正文这一句是完整收尾的。\n\n---METADATA---\ntitle: t\nisEnding: false", finishReason = "length")
        coVerify(exactly = 0) { payloadResolver.requestContinuation(any(), any(), any(), any()) }
    }

    @Test
    fun `撞限且连分隔符都没吐出_触发续写并用故事创作温度`() = runBlocking { // E12 / T2-7
        runGeneration(raw = "正文这一句是完整收尾的。", finishReason = "length")
        coVerify(exactly = 1) { payloadResolver.requestContinuation(any(), any(), 0.9, any()) }
    }

    @Test
    fun `兼容层的max_tokens同样算撞限`() = runBlocking { // isLengthTruncated 单源判据的另一个值
        runGeneration(raw = "正文这一句是完整收尾的。", finishReason = "max_tokens")
        coVerify(exactly = 1) { payloadResolver.requestContinuation(any(), any(), 0.9, any()) }
    }

    @Test
    fun `正常收尾stop_不触发续写`() = runBlocking { // V8 不误触
        runGeneration(raw = "正文这一句是完整收尾的。", finishReason = "stop")
        coVerify(exactly = 0) { payloadResolver.requestContinuation(any(), any(), any(), any()) }
    }

    @Test
    fun `无finish_reason回调_退化为既有行为`() = runBlocking { // E14 断流：无回调 → null → 不误触
        runGeneration(raw = "正文这一句是完整收尾的。", finishReason = null)
        coVerify(exactly = 0) { payloadResolver.requestContinuation(any(), any(), any(), any()) }
    }

    @Test
    fun `stop但正文断在非句末字符_启发式照旧续写`() = runBlocking { // E13 既有路不劣化
        every { llmClient.streamChat(any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            arg<((String?) -> Unit)?>(8)?.invoke("stop")
            flowOf(StreamToken.Content("他伸手推开那扇"))
        }
        coEvery { payloadResolver.resolvePayload(any(), any(), any()) } returns
            StoryChapterPayload(title = "t", mood = "warm", content = "他伸手推开那扇", hasChoice = false)
        val request = StoryGenerationRequest(messages = emptyList(), maxTokens = 5_000, temperature = 0.9)
        service.generateChapter(story, chapterNumber = 3, request = request, nowMillis = 1_000L)
        coVerify(exactly = 1) { payloadResolver.requestContinuation(any(), any(), 0.9, any()) }
    }
}
