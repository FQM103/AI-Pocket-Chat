package com.situ.aichat.story

import com.situ.aichat.data.local.dao.StoryChapterSummaryRow
import com.situ.aichat.data.local.entity.StoryChapterEntity
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.remote.llm.LlmClient
import com.situ.aichat.data.remote.llm.StreamToken
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.ApiFunctionRouter
import com.situ.aichat.data.repository.StoryRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test

/**
 * 压缩类任务模型路由行为测试（T2）：证明「摘要压缩 / 圣经压缩」走**创作模型**（[StoryGenerationService.creationConfig]），
 * 而非结构化槽（[StoryGenerationService.structuringConfig]）——即便结构化槽已被显式分配到一个**不同**的模型。
 *
 * 背景：这两件是质量敏感的总结活（保住人物/伏笔/感情线，输出回喂后续章节 prompt），有意配强模型；纯格式活
 * （L2/L3/修复，走 [StoryPayloadResolver]）仍留结构化槽。
 *
 * 手法：MockK 假掉全部协作者；创作槽=modelName "creation-pro"、结构化槽=modelName "structuring-flash"，
 * 用 `match { it.modelName == ... }` 钉死每次 LLM 调用实际拿到的配置。Log.* 走 returnDefaultValues。
 */
class StoryGenerationServiceCompressionRoutingTest {

    private lateinit var llmClient: LlmClient
    private lateinit var storyRepository: StoryRepository
    private lateinit var apiConfigRepository: ApiConfigRepository
    private lateinit var apiFunctionRouter: ApiFunctionRouter
    private lateinit var payloadResolver: StoryPayloadResolver
    private lateinit var materializer: StoryChapterMaterializer
    private lateinit var bibleCompressor: StoryBibleCompressor
    private lateinit var coordinator: StoryCompressionCoordinator
    private lateinit var service: StoryGenerationService

    private val creationPro = ApiConfigValues(
        providerType = ApiProviderType.OPENAI_COMPATIBLE, apiKey = "k",
        baseUrl = "https://example.test", modelName = "creation-pro",
    )
    private val structuringFlash = ApiConfigValues(
        providerType = ApiProviderType.OPENAI_COMPATIBLE, apiKey = "k",
        baseUrl = "https://example.test", modelName = "structuring-flash",
    )

    @Before
    fun setUp() {
        llmClient = mockk()
        storyRepository = mockk()
        apiConfigRepository = mockk()
        apiFunctionRouter = mockk()
        payloadResolver = mockk()
        materializer = mockk()
        bibleCompressor = mockk(relaxed = true)
        // 压缩域协作者用**真实例**（非 mock）：本组要看的正是它拿到哪一份配置去调 LLM / 传给圣经压缩器。
        coordinator = StoryCompressionCoordinator(
            llmClient = llmClient,
            storyRepository = storyRepository,
            storyBibleCompressor = bibleCompressor,
        )
        service = StoryGenerationService(
            llmClient = llmClient,
            contextLog = mockk(relaxed = true),
            storyRepository = storyRepository,
            apiConfigRepository = apiConfigRepository,
            apiFunctionRouter = apiFunctionRouter,
            storyChatInfluenceBuilder = mockk(),
            storyCharacterDataCollector = mockk(),
            storyChapterMaterializer = materializer,
            storyPayloadResolver = payloadResolver,
            storyWorldInfoService = mockk(),
            settingsRepository = mockk(),
            // 大纲编排外搬（卷二 C4 文件瘦身）：本组用例不碰大纲面，桩成**恒等直通**——
            // 与外搬前「无需生成大纲时 ensureOutline 原样返回入参」的行为一致，后续步骤照旧拿到同一个故事。
            storyOutlineOrchestrator = mockk<StoryOutlineOrchestrator>().also { orchestrator ->
                coEvery { orchestrator.ensureOutline(any(), any(), any(), any()) } answers { firstArg() }
            },
            storyCompressionCoordinator = coordinator,
        )
        // 结构化槽已分配到一个**不同**的模型（flash）；创作槽=pro。若压缩误走结构化槽会拿到 flash → 断言可捕获。
        coEvery { apiFunctionRouter.assignedId(ApiFunction.STORY_STRUCTURING) } returns "flash-id"
        coEvery { apiConfigRepository.resolveConfigValues(ApiFunction.STORY_CREATION) } returns creationPro
        coEvery { apiConfigRepository.resolveConfigValues(ApiFunction.STORY_STRUCTURING) } returns structuringFlash
    }

    @Test
    fun `摘要压缩走创作模型而非结构化槽`() = runBlocking {
        coEvery { storyRepository.getStory("s1") } returns
            StoryEntity(id = "s1", title = "书", genre = "言情", lastCompressedAtChapter = 0)
        coEvery { storyRepository.getChapterSummaries("s1") } returns listOf(
            StoryChapterSummaryRow(1, "第一章摘要内容"),
            StoryChapterSummaryRow(2, "第二章摘要内容"),
            StoryChapterSummaryRow(3, "第三章摘要内容"),
        )
        coEvery { llmClient.completion(any(), any(), any(), any(), any(), any(), any()) } returns "压缩后的全局摘要"
        coEvery { storyRepository.updateCompressedSummary(any(), any(), any()) } just Runs

        coordinator.compressSummaryChainIfNeeded("s1", currentChapter = 3) { service.creationConfig() }

        // 那次压缩 completion 拿到的是创作模型（pro），且从未拿到结构化槽（flash）。
        coVerify(exactly = 1) {
            llmClient.completion(any(), match { it.modelName == "creation-pro" }, any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) {
            llmClient.completion(any(), match { it.modelName == "structuring-flash" }, any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `圣经压缩收到创作模型配置而非结构化槽`() = runBlocking {
        val story = StoryEntity(id = "s1", title = "书", genre = "言情", lastCompressedAtChapter = 0)
        val request = StoryGenerationRequest(
            messages = listOf(ChatMessageDto(role = "system", content = "p")),
            maxTokens = 100, temperature = 0.7,
        )
        every { llmClient.streamChat(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            flowOf(StreamToken.Content("正文结尾。"))
        coEvery { payloadResolver.resolvePayload(any(), any(), any()) } returns
            StoryChapterPayload(title = "t", mood = "warm", content = "正文结尾。", hasChoice = false)
        coEvery { materializer.materializeChapter(any(), any(), any(), any()) } returns mockk<StoryChapterEntity>(relaxed = true)
        // 空摘要 → shouldCompressSummary=false，隔离掉摘要压缩，只留圣经压缩路径。
        coEvery { storyRepository.getChapterSummaries("s1") } returns emptyList()

        service.generateChapter(story, chapterNumber = 13, request = request, nowMillis = 1_000L)
        // 两压缩已后台化（卷一 chunk 2）：等待点取 job 真正的最后一步 joinCompression，
        // 绝不 await 任何中途可观测量（PITFALLS §1e 泄漏源形状）。
        coordinator.joinCompression("s1")

        // 圣经压缩器收到的是创作模型（pro）配置，绝非结构化槽（flash）。
        coVerify(exactly = 1) {
            bibleCompressor.compressIfNeeded("s1", 13, match { it.modelName == "creation-pro" })
        }
        coVerify(exactly = 0) {
            bibleCompressor.compressIfNeeded(any(), any(), match { it.modelName == "structuring-flash" })
        }
        // 分工的另一半：纯格式活（L2 轻量补全 / L3 元数据结构化）仍留结构化槽——generateChapter 传给 resolvePayload 的 config
        // 必为 structuring-flash（本改动未动这条接线，用断言把它钉死，防未来回归误伤分流边界）。
        coVerify(exactly = 1) {
            payloadResolver.resolvePayload(any(), any(), match { it.modelName == "structuring-flash" })
        }
    }

    @Test
    fun `思考型创作模型_摘要压缩额度按x3放大`() = runBlocking {
        // 创作槽标为思考模型 → 压缩第一次就该给 base 2400×3=7200 的余量（思考烧额度后摘要仍装得下、少截断）。
        coEvery { apiConfigRepository.resolveConfigValues(ApiFunction.STORY_CREATION) } returns
            creationPro.copy(isThinkingModel = true)
        coEvery { storyRepository.getStory("s1") } returns
            StoryEntity(id = "s1", title = "书", genre = "言情", lastCompressedAtChapter = 0)
        coEvery { storyRepository.getChapterSummaries("s1") } returns listOf(
            StoryChapterSummaryRow(1, "第一章摘要内容"),
            StoryChapterSummaryRow(2, "第二章摘要内容"),
            StoryChapterSummaryRow(3, "第三章摘要内容"),
        )
        coEvery { llmClient.completion(any(), any(), any(), any(), any(), any(), any()) } returns "压缩后的全局摘要"
        coEvery { storyRepository.updateCompressedSummary(any(), any(), any()) } just Runs

        coordinator.compressSummaryChainIfNeeded("s1", currentChapter = 3) { service.creationConfig() }

        // maxTokens（completion 第 4 个实参）= base 2400 × 3 = 7200。
        coVerify(exactly = 1) {
            llmClient.completion(any(), any(), any(), eq(7_200), any(), any(), any())
        }
    }
}
