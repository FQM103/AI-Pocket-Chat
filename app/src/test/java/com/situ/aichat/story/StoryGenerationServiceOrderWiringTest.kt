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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * **B 序固化的落位命门测试**（T2·MockK·2026-08-03 图纸 §5 E1·结构照 [StoryGenerationServiceToggleWiringTest]）。
 *
 * 前身是段序 A/B 实验的接线测试；实验定胜负为 B 序、开关整链拆除后，这里改钉**唯一的段序事实**：
 * 「内容标记 + 输出格式」两段恒发在声音档案段之后（= 续写规则/章节要求**之前**），不再排在 system 末位。
 * 断言点仍钉在离 LLM 最近的可观测量：真实生成路径送进 `llmClient.streamChat` 的消息文本本身——
 * 谁把两段挪回末位，本例当场红。
 */
class StoryGenerationServiceOrderWiringTest {

    private lateinit var llmClient: LlmClient
    private lateinit var storyRepository: StoryRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var collector: StoryCharacterDataCollector
    private lateinit var service: StoryGenerationService

    private val config = ApiConfigValues(
        providerType = ApiProviderType.OPENAI_COMPATIBLE, apiKey = "k",
        baseUrl = "https://example.test", modelName = "m",
    )

    /** 无限连载 + 已有弧线大纲 + 弧线起点近 → 隔离掉大纲那次 LLM 调用（同两开关接线用例）。 */
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

    /** 跑一次生成，返回真正送进 LLM 的全部消息文本。 */
    private fun promptSentToLlm(firstChapter: Boolean): String {
        coEvery { settingsRepository.appSettings } returns flowOf(AppSettings())
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
    fun 续章请求里两段恒在续写规则之前() {
        val prompt = promptSentToLlm(firstChapter = false)
        assertTrue("两段必须在场", prompt.contains(MARKUP_SENTINEL) && prompt.contains(OUTPUT_FORMAT_SENTINEL))
        assertTrue("B 序固化：挪回末位就会红", prompt.indexOf(MARKUP_SENTINEL) < prompt.indexOf(CONTINUATION_SENTINEL))
        assertTrue(prompt.indexOf(OUTPUT_FORMAT_SENTINEL) < prompt.indexOf(CONTINUATION_SENTINEL))
    }

    @Test
    fun 首章请求里两段恒在章节要求之前() {
        val prompt = promptSentToLlm(firstChapter = true)
        assertTrue("两段必须在场", prompt.contains(MARKUP_SENTINEL) && prompt.contains(OUTPUT_FORMAT_SENTINEL))
        assertTrue("首章 B 序固化：挪回末位就会红", prompt.indexOf(MARKUP_SENTINEL) < prompt.indexOf(CHAPTER_REQ_SENTINEL))
        assertTrue(prompt.indexOf(OUTPUT_FORMAT_SENTINEL) < prompt.indexOf(CHAPTER_REQ_SENTINEL))
    }

    @Test
    fun 两段各恰出现一次_首章与续章都不许重发() {
        // 固化时删的是 A 序末位那次调用：漏删就会两处都发，这条当场红。
        listOf(true, false).forEach { firstChapter ->
            val prompt = promptSentToLlm(firstChapter = firstChapter)
            listOf(MARKUP_SENTINEL, OUTPUT_FORMAT_SENTINEL).forEach { needle ->
                assertEquals(
                    "firstChapter=$firstChapter 必须恰出现一次：$needle",
                    prompt.indexOf(needle), prompt.lastIndexOf(needle),
                )
            }
        }
    }

    private companion object {
        /** 被固化到 B 位的两段的段头（唯一注入源 = StoryFormatRules）。 */
        const val MARKUP_SENTINEL = "## 内容标记（严格遵守以下规则）"
        const val OUTPUT_FORMAT_SENTINEL = "## 输出格式"
        /** 续章「结尾规则区」的段头（唯一注入源 = StoryWritingTechniques.continuationRules）。 */
        const val CONTINUATION_SENTINEL = "### 续写规则"
        /** 首章里排在被固化两段之后的锚（唯一注入源 = StoryWritingTechniques.chapterRequirements）。 */
        const val CHAPTER_REQ_SENTINEL = "## 章节要求"
    }
}
