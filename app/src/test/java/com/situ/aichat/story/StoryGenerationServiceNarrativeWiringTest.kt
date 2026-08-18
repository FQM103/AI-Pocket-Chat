package com.situ.aichat.story

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
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 故事二期卷一三条接线的**命门测试**（T2·MockK·图纸 §7 T2-3）。
 *
 * 三个新参（`globalSceneBeats` / `globalTasteProfile` / `pendingBeatsUserEdited`）都是**带默认值的可选参**
 * ——历史教训：可选参 = 「Service 忘传 → 静默丢功能」的温床（PITFALLS 3.22）。纯函数层的装配测试证明不了
 * 接线活着，所以断言点钉在**离 LLM 最近的可观测量**：真实生成路径送进 `llmClient.streamChat` 的 messages。
 * 删掉 Service 里任一行实参，本文件对应用例必红（照 [StoryGenerationServiceBannedWiringTest] 先例）。
 */
class StoryGenerationServiceNarrativeWiringTest {

    private lateinit var llmClient: LlmClient
    private lateinit var storyRepository: StoryRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var collector: StoryCharacterDataCollector
    private lateinit var service: StoryGenerationService

    private val config = ApiConfigValues(
        providerType = ApiProviderType.OPENAI_COMPATIBLE, apiKey = "k",
        baseUrl = "https://example.test", modelName = "m",
    )

    /** 无限连载 + 已有弧线大纲 + 弧线起点近 → 隔离掉大纲那次 LLM 调用（同忌口版用例）。 */
    private val story = StoryEntity(
        id = "s1", title = "书", genre = "言情",
        maxChapters = null, storyOutline = "弧线大纲", currentArcStartChapter = 5,
        cachedLatestChapterNumber = 5, cachedChapterCount = 5,
        pendingChapterBeats = "AI 预排的方向",
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

    /** 跑一次真实生成，返回送进 LLM 的全部消息文本。 */
    private fun promptSentToLlm(
        settings: AppSettings,
        firstChapter: Boolean,
        storyOverride: StoryEntity = story,
        chapters: List<StoryChapterEntity> = emptyList(),
    ): String {
        coEvery { settingsRepository.appSettings } returns flowOf(settings)
        // 卷二 §3.2：末章取法换单行查询，桩返回原 lastOrNull 那一章（断言本体零改）。
        coEvery { storyRepository.getLatestChapter("s1") } returns chapters.lastOrNull()
        // 方向账本取数（图纸 2026-08-05 §3.1）：轻投影镜像同一份章节列表。
        coEvery { storyRepository.getChapterMetas("s1") } returns chapters
        val messagesSlot = slot<List<ChatMessageDto>>()
        every {
            llmClient.streamChat(capture(messagesSlot), any(), any(), any(), any(), any(), any(), any(), any())
        } returns flowOf(StreamToken.Content("正文写到这里就结尾了。"))
        runBlocking {
            if (firstChapter) {
                service.generateFirstChapter(
                    storyOverride.copy(cachedLatestChapterNumber = null, cachedChapterCount = 0),
                    nowMillis = 1_000L,
                )
            } else {
                service.generateNextChapter(storyOverride, nowMillis = 1_000L)
            }
        }
        return messagesSlot.captured.joinToString("\n") { it.content.orEmpty() }
    }

    // ── ① 全局场面节拍 ──

    @Test
    fun `全局场面节拍真的被送进首章与续章请求`() {
        val settings = AppSettings(storySceneBeats = "SENTINEL_BEATS_XYZ")
        assertTrue(promptSentToLlm(settings, firstChapter = true).contains("SENTINEL_BEATS_XYZ"))
        assertTrue(promptSentToLlm(settings, firstChapter = false).contains("SENTINEL_BEATS_XYZ"))
    }

    @Test
    fun `全局未设置时送下去的是出厂默认主节拍`() {
        val prompt = promptSentToLlm(AppSettings(), firstChapter = false)
        assertTrue(prompt.contains(StoryCraftSections.SCENE_BEATS_DEFAULT))
        assertFalse(prompt.contains("SENTINEL_BEATS_XYZ"))
    }

    @Test
    fun `全局清空后请求里没有主节拍段`() {
        val prompt = promptSentToLlm(AppSettings(storySceneBeats = ""), firstChapter = false)
        assertFalse(prompt.contains(StoryCraftSections.SCENE_BEATS_DEFAULT))
        assertFalse(prompt.contains("## 场面节拍"))
    }

    // ── ② 全局口味画像 ──

    @Test
    fun `全局口味画像真的被送进首章与续章请求`() {
        val settings = AppSettings(storyTasteProfile = "SENTINEL_TASTE_XYZ")
        assertTrue(promptSentToLlm(settings, firstChapter = true).contains("SENTINEL_TASTE_XYZ"))
        assertTrue(promptSentToLlm(settings, firstChapter = false).contains("SENTINEL_TASTE_XYZ"))
    }

    @Test
    fun `画像没填时请求里没有画像段`() {
        assertFalse(promptSentToLlm(AppSettings(), firstChapter = false).contains(StoryCraftSections.TASTE_PROFILE_HEADER))
    }

    // ── ③ 「节拍被用户改过」标志（列 → prompt 分派） ──

    @Test
    fun `节拍标志为真时送的是用户指定节拍段`() {
        val prompt = promptSentToLlm(
            AppSettings(),
            firstChapter = false,
            storyOverride = story.copy(pendingChapterBeats = "先冷场再爆发", pendingBeatsUserEdited = true),
        )
        assertTrue(prompt.contains(StoryCraftSections.USER_BEATS_HEADER))
        assertTrue(prompt.contains("「先冷场再爆发」"))
        assertFalse("用户改过就不该再走 AI 预排那段", prompt.contains(StoryCraftSections.DRAFT_HEADER))
    }

    @Test
    fun `节拍标志为假时送的是AI预排方向段`() {
        val prompt = promptSentToLlm(AppSettings(), firstChapter = false)
        assertTrue(prompt.contains(StoryCraftSections.DRAFT_HEADER))
        assertFalse(prompt.contains(StoryCraftSections.USER_BEATS_HEADER))
    }

    // ── ④ 上一章快评（章列 → prompt） ──

    @Test
    fun `上一章评分真的被送进续章请求`() {
        val rated = StoryChapterEntity(
            id = "c5", storyId = "s1", chapterNumber = 5, title = "第五章",
            content = "上一章正文", userRating = 1,
        )
        val prompt = promptSentToLlm(AppSettings(), firstChapter = false, chapters = listOf(rated))
        assertTrue(prompt.contains(StoryCraftSections.READER_FEEDBACK_HEADER))
        assertTrue(prompt.contains("读者对上一章的评价：不满意。"))
    }

    @Test
    fun `上一章未评时请求里没有读者反馈段`() {
        val unrated = StoryChapterEntity(
            id = "c5", storyId = "s1", chapterNumber = 5, title = "第五章",
            content = "上一章正文", userRating = null,
        )
        assertFalse(
            promptSentToLlm(AppSettings(), firstChapter = false, chapters = listOf(unrated))
                .contains(StoryCraftSections.READER_FEEDBACK_HEADER),
        )
    }

    // ── ⑤ 方向账本（章列 → 服务取轻投影 → prompt·图纸 2026-08-05 §3.1）──
    // 「可选尾参默认 null」= 静默丢功能的温床（PITFALLS §3.22），故接线本身要有命门测试。

    private fun freeformChapter(number: Int, choice: String?) = StoryChapterEntity(
        id = "c$number", storyId = "s1", chapterNumber = number, title = "第${number}章",
        content = "正文", hasChoice = false, userChoice = choice,
    )

    @Test
    fun `本弧历史亲笔走向真的被送进续章请求且排除最新一章`() {
        val prompt = promptSentToLlm(
            AppSettings(), firstChapter = false,
            chapters = listOf(
                freeformChapter(4, "弧起点之前的走向"),   // < arcStart(5) → 不入账
                freeformChapter(5, "先去码头"),
                freeformChapter(6, "把信烧掉"),           // 最新一章 → 有三明治专座，不重复登记
            ),
        )
        assertTrue(prompt.contains(StoryCraftSections.DIRECTIVE_LEDGER_HEADER))
        assertTrue(prompt.contains("- 第5章时指定：「先去码头」"))
        assertFalse("弧起点之前的走向由换弧简史吸收", prompt.contains("弧起点之前的走向"))
        assertEquals("最新一章的走向只出现在三明治段，不出现在账本行", -1, prompt.indexOf("- 第6章时指定："))
    }

    @Test
    fun `本弧没有亲笔走向时账本整段不出现`() {
        val prompt = promptSentToLlm(
            AppSettings(), firstChapter = false,
            chapters = listOf(freeformChapter(5, StoryChoiceClassifier.NATURAL_FLOW_CHOICE), freeformChapter(6, null)),
        )
        assertFalse(prompt.contains(StoryCraftSections.DIRECTIVE_LEDGER_HEADER))
        assertFalse(prompt.contains(StoryCraftSections.DIRECTIVE_LEDGER_INTRO))
    }
}
