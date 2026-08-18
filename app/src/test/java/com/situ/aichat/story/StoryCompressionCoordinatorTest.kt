package com.situ.aichat.story

import com.situ.aichat.data.local.dao.StoryChapterSummaryRow
import com.situ.aichat.data.local.entity.StoryChapterEntity
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
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.Collections

/**
 * **压缩后台化 + join 不变式行为测试**（T2·MockK·2026-08-03 生成时序卷一 §7 T2-A/B/C/D）。
 *
 * 钉三件事：
 * 1. 章节落库后 [StoryGenerationService.generateChapter] **不再等压缩**——压缩 LLM 被 gate 挂住时生成照样返回；
 * 2. **join 不变式**：同一本书的下一次生成 / 重写在压缩写回**之前**绝不开工，且开工时读到的是压缩后的新快照；
 * 3. 用户取消生成不殃及压缩——压缩 job 照常跑完写回。
 *
 * 手法：MockK 假掉全部协作者，压缩的 `llmClient.completion` 用 [CompletableDeferred] 当闸（`compressionStarted`
 * 通知「压缩已真的开跑」，`gate` 决定何时放行），压缩域协作者用**真实例**。所有等待点一律取
 * `joinCompression` / `Job.join()`（= 协程真正的最后一步），绝不 await 中途量（PITFALLS §1e 泄漏源形状）。
 */
class StoryCompressionCoordinatorTest {

    /** 兜底闸：若 join 不变式写坏成死等，让这条用例超时失败，而不是把整个全量跑挂住。 */
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private lateinit var llmClient: LlmClient
    private lateinit var storyRepository: StoryRepository
    private lateinit var apiConfigRepository: ApiConfigRepository
    private lateinit var apiFunctionRouter: ApiFunctionRouter
    private lateinit var payloadResolver: StoryPayloadResolver
    private lateinit var materializer: StoryChapterMaterializer
    private lateinit var bibleCompressor: StoryBibleCompressor
    private lateinit var coordinator: StoryCompressionCoordinator
    private lateinit var service: StoryGenerationService

    private val config = ApiConfigValues(
        providerType = ApiProviderType.OPENAI_COMPATIBLE, apiKey = "k",
        baseUrl = "https://example.test", modelName = "m",
    )

    /** 压缩 LLM 已真的开跑（等它 = 「压缩 job 在飞」的确证，不是靠 sleep 猜）。 */
    private val compressionStarted = CompletableDeferred<Unit>()
    /** 放行压缩 LLM 的闸。 */
    private val gate = CompletableDeferred<String>()

    /** 关键事件流水（跨线程写 → 同步表）：断言「压缩写回」与「下一次创作」的先后。 */
    private val events: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())

    /** 模拟库里那一行故事：压缩写回会真的改到它，下一次生成的重读才有东西可读。 */
    private var storyInDb = StoryEntity(
        id = "s1", title = "书", genre = "言情",
        lastCompressedAtChapter = 0, cachedLatestChapterNumber = 8, cachedChapterCount = 8,
    )

    private val request = StoryGenerationRequest(
        messages = listOf(ChatMessageDto(role = "system", content = "p")),
        maxTokens = 100, temperature = 0.7,
    )

    private val materializedStories = mutableListOf<StoryEntity>()

    @Before
    fun setUp() {
        llmClient = mockk()
        storyRepository = mockk()
        apiConfigRepository = mockk()
        apiFunctionRouter = mockk()
        payloadResolver = mockk()
        materializer = mockk()
        bibleCompressor = mockk(relaxed = true)
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
            storyChatInfluenceBuilder = mockk(relaxed = true),
            storyCharacterDataCollector = mockk<StoryCharacterDataCollector>(relaxed = true).also {
                coEvery { it.collectProtagonistDynamicState(any()) } returns (null to null)
            },
            storyChapterMaterializer = materializer,
            storyPayloadResolver = payloadResolver,
            storyWorldInfoService = mockk(relaxed = true),
            settingsRepository = mockk<SettingsRepository>().also {
                every { it.appSettings } returns flowOf(AppSettings())
            },
            storyOutlineOrchestrator = mockk<StoryOutlineOrchestrator>().also { orchestrator ->
                coEvery { orchestrator.ensureOutline(any(), any(), any(), any()) } answers { firstArg() }
            },
            storyCompressionCoordinator = coordinator,
        )

        coEvery { apiFunctionRouter.assignedId(ApiFunction.STORY_STRUCTURING) } returns null
        coEvery { apiConfigRepository.resolveConfigValues(any()) } returns config
        coEvery { storyRepository.getStory("s1") } answers { storyInDb }
        coEvery { storyRepository.getRoles(any()) } returns emptyList()
        coEvery { storyRepository.getLatestChapter(any()) } returns null
        // 方向账本取数（图纸 2026-08-05 §3.1）：轻投影桩，空列表 = 账本 null = prompt 整段零注入
        coEvery { storyRepository.getChapterMetas(any()) } returns emptyList()
        // 第 8 章落库后：距上次压缩 8 章、未压缩摘要 > 3000 字 ⇒ shouldCompressSummary 成立。
        coEvery { storyRepository.getChapterSummaries("s1") } answers {
            (1..8).map { StoryChapterSummaryRow(it, "第${it}章摘要".repeat(200)) }
        }
        coEvery { storyRepository.updateCompressedSummary("s1", any(), any()) } answers {
            events += EVENT_COMPRESSED
            storyInDb = storyInDb.copy(storySummary = secondArg(), lastCompressedAtChapter = thirdArg())
        }

        every { llmClient.streamChat(any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            events += EVENT_CREATION
            flowOf(StreamToken.Content("正文结尾。"))
        }
        coEvery { payloadResolver.resolvePayload(any(), any(), any()) } returns
            StoryChapterPayload(title = "t", mood = "warm", content = "正文结尾。", hasChoice = false)
        coEvery { materializer.materializeChapter(any(), any(), capture(materializedStories), any()) } returns
            mockk<StoryChapterEntity>(relaxed = true)
        coEvery { materializer.prepareRewrite(any(), any(), any(), any()) } answers {
            events += EVENT_REWRITE
        }
        // 压缩 LLM：先报「我开跑了」，再挂在闸上等放行。
        coEvery { llmClient.completion(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            compressionStarted.complete(Unit)
            gate.await()
        }
    }

    /** 跑第 8 章生成（= 会触发摘要压缩的那一章），返回时压缩 job 已登记。 */
    private suspend fun generateChapterEight(): StoryChapterEntity =
        service.generateChapter(storyInDb, chapterNumber = 8, request = request, nowMillis = 1_000L)

    /** T2-A：章节落库即返回，DONE 不被压缩门控；放行后压缩才写回。 */
    @Test
    fun `压缩挂起时章节生成照常返回_放行后压缩才写回`() = runBlocking {
        val chapter = generateChapterEight()
        compressionStarted.await() // 压缩确已开跑，且此刻仍挂在闸上

        assertNotNull("章节实体已产出 = 生成没被压缩门控", chapter)
        coVerify(exactly = 0) { storyRepository.updateCompressedSummary(any(), any(), any()) }

        gate.complete(COMPRESSED_SUMMARY)
        coordinator.joinCompression("s1")

        coVerify(exactly = 1) { storyRepository.updateCompressedSummary("s1", COMPRESSED_SUMMARY, 8) }
        // 圣经压缩同在这条 job 里，拿到的是 job 内解析的创作配置。
        coVerify(exactly = 1) { bibleCompressor.compressIfNeeded("s1", 8, config) }
    }

    /** T2-B / E7 / E8：下一章生成先 join 再重读——压缩写回之前绝不开工，开工读到的是压缩后的新快照。 */
    @Test
    fun `下一章生成等压缩写回_并用重读后的新快照落库`() = runBlocking {
        generateChapterEight()
        compressionStarted.await()

        val next = launch(Dispatchers.Default) { service.generateNextChapter(storyInDb, nowMillis = 2_000L) }
        delay(BLOCKED_GRACE_MS) // 宽限窗：给下一章生成充分的开工机会，它仍必须卡在 joinCompression 上
        coVerify(exactly = 1) { llmClient.streamChat(any(), any(), any(), any(), any(), any(), any(), any(), any()) }

        gate.complete(COMPRESSED_SUMMARY)
        next.join()
        coordinator.joinCompression("s1")

        // 顺序不变式：第 8 章创作 → 压缩写回 → 第 9 章创作（join 被摘掉的话中间两项会调个个儿）。
        assertEquals(listOf(EVENT_CREATION, EVENT_COMPRESSED, EVENT_CREATION), events.toList())
        // 重读生效：第 9 章落库带的是压缩后的摘要，而不是调用方 join 前读的那份旧快照。
        assertEquals(2, materializedStories.size)
        assertEquals(null, materializedStories[0].storySummary)
        assertEquals(COMPRESSED_SUMMARY, materializedStories[1].storySummary)
    }

    /** T2-C / E1：用户取消生成 → 压缩 job 不受牵连，照常跑完写回。 */
    @Test
    fun `取消生成不殃及压缩_压缩照常写回`() = runBlocking {
        val generation = launch(Dispatchers.Default) {
            generateChapterEight()
            delay(Long.MAX_VALUE) // 模拟 TaskManager 里生成协程返回后仍在收尾时被取消
        }
        compressionStarted.await()
        generation.cancel()

        gate.complete(COMPRESSED_SUMMARY)
        coordinator.joinCompression("s1")

        coVerify(exactly = 1) { storyRepository.updateCompressedSummary("s1", COMPRESSED_SUMMARY, 8) }
    }

    /** T2-D / E3：重写本章同样先 join 再重读——回滚基于压缩后的状态，绝不与压缩写回互相踩踏。 */
    @Test
    fun `重写本章等压缩写回_并基于重读后的新快照回滚`() = runBlocking {
        coEvery { storyRepository.setPendingRewriteDraft(any(), any()) } just Runs
        generateChapterEight()
        compressionStarted.await()

        val latestChapter = mockk<StoryChapterEntity>(relaxed = true)
        val rewrite = launch(Dispatchers.Default) {
            service.prepareRewrite(storyInDb, latestChapter, instruction = "重写指令", nowMillis = 3_000L)
        }
        delay(BLOCKED_GRACE_MS)
        coVerify(exactly = 0) { materializer.prepareRewrite(any(), any(), any(), any()) }

        gate.complete(COMPRESSED_SUMMARY)
        rewrite.join()
        coordinator.joinCompression("s1")

        assertEquals(listOf(EVENT_CREATION, EVENT_COMPRESSED, EVENT_REWRITE), events.toList())
        coVerify(exactly = 1) {
            materializer.prepareRewrite(
                match { it.storySummary == COMPRESSED_SUMMARY }, latestChapter, "重写指令", 3_000L,
            )
        }
    }

    private companion object {
        const val COMPRESSED_SUMMARY = "压缩后的全局摘要"
        const val EVENT_CREATION = "创作"
        const val EVENT_COMPRESSED = "压缩写回"
        const val EVENT_REWRITE = "重写回滚"

        /** 「被 join 挡住」的宽限窗：这段时间里下一步动作若真能开工，早开工了。 */
        const val BLOCKED_GRACE_MS = 300L
    }
}
