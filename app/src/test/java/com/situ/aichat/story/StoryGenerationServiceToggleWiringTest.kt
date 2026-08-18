package com.situ.aichat.story

import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.CustomStoryPrompts
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
 * 章末选项开关的**接线命门测试**（T2·MockK·图纸二 §7 T2-1·结构照 [StoryGenerationServiceBannedWiringTest]；
 * 2026-08-03 格式块精简后另一个维度「沉浸氛围标记」整链退役，本文件只剩 choicesEnabled 一维）。
 *
 * 为什么必须有这一条：build* 的开关参是可选参（`= true`，为不炸既有调用点），
 * 可选参的历史教训就是「Service 忘传 → 静默丢功能」。纯函数层的 T1 证明不了接线活着——
 * 断言点因此钉在**离 LLM 最近的可观测量**：真实生成路径送进 `llmClient.streamChat` 的 messages 里
 * 还有没有那句哨兵。删掉 Service 里任一 `choicesEnabled=` 实参，本例当场红。
 */
class StoryGenerationServiceToggleWiringTest {

    private lateinit var llmClient: LlmClient
    private lateinit var storyRepository: StoryRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var collector: StoryCharacterDataCollector
    private lateinit var service: StoryGenerationService

    private val config = ApiConfigValues(
        providerType = ApiProviderType.OPENAI_COMPATIBLE, apiKey = "k",
        baseUrl = "https://example.test", modelName = "m",
    )

    /** 无限连载 + 已有弧线大纲 + 弧线起点近 → 隔离掉大纲那次 LLM 调用（同忌口接线用例）。 */
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
        coEvery { settingsRepository.appSettings } returns flowOf(AppSettings())
    }

    /** 按 [prompts] 配好本书的 customPromptsJson，跑一次生成，返回真正送进 LLM 的全部消息文本。 */
    private fun promptSentToLlm(prompts: CustomStoryPrompts?, firstChapter: Boolean): String {
        val json = prompts?.let { CustomStoryPrompts.encode(it) }
        val messagesSlot = slot<List<ChatMessageDto>>()
        every {
            llmClient.streamChat(capture(messagesSlot), any(), any(), any(), any(), any(), any(), any(), any())
        } returns flowOf(StreamToken.Content("正文写到这里就结尾了。"))
        runBlocking {
            if (firstChapter) {
                service.generateFirstChapter(
                    story.copy(cachedLatestChapterNumber = null, cachedChapterCount = 0, customPromptsJson = json),
                    nowMillis = 1_000L,
                )
            } else {
                service.generateNextChapter(story.copy(customPromptsJson = json), nowMillis = 1_000L)
            }
        }
        return messagesSlot.captured.joinToString("\n") { it.content.orEmpty() }
    }

    private val choicesOff = CustomStoryPrompts(chapterChoicesEnabled = false)
    private val choicesOn = CustomStoryPrompts(chapterChoicesEnabled = true)

    @Test
    fun 默认书_续章走关闭态_选项哨兵不在() {
        // 2026-08-05 拍板：默认关——没动过开关的书不再出选项。
        val prompt = promptSentToLlm(null, firstChapter = false)
        assertFalse(prompt.contains(CHOICE_SENTINEL))
        assertTrue("换发的是关闭态段", prompt.contains("### 选择分支（本书已关闭）"))
        assertTrue("标记段恒发（不随选项开关）", prompt.contains(MARKUP_SENTINEL))
    }

    @Test
    fun 默认书_首章走关闭态_选项哨兵不在() {
        val prompt = promptSentToLlm(null, firstChapter = true)
        assertFalse(prompt.contains(CHOICE_SENTINEL))
        assertTrue(prompt.contains("### 选择分支（本书已关闭）"))
        assertTrue(prompt.contains(MARKUP_SENTINEL))
    }

    @Test
    fun 显式开书_哨兵在续章请求里() {
        // 接线证明：Service 传的是谓词值而非硬编码——显式 true 必须把选项指令带回来。
        val prompt = promptSentToLlm(choicesOn, firstChapter = false)
        assertTrue(prompt.contains(CHOICE_SENTINEL))
        assertTrue(prompt.contains(MARKUP_SENTINEL))
    }

    @Test
    fun 显式开书_哨兵在首章请求里() {
        val prompt = promptSentToLlm(choicesOn, firstChapter = true)
        assertTrue(prompt.contains(CHOICE_SENTINEL))
        assertTrue(prompt.contains(MARKUP_SENTINEL))
    }

    @Test
    fun 关选项_续章请求里哨兵不见了() {
        val prompt = promptSentToLlm(choicesOff, firstChapter = false)
        assertFalse("选项接线断了就会红", prompt.contains(CHOICE_SENTINEL))
        assertTrue("换发的是关闭态段", prompt.contains("### 章节结尾（本书已关闭章末选择）"))
        assertTrue(prompt.contains("### 选择分支（本书已关闭）"))
        assertTrue("关选项不该顺手动标记段", prompt.contains("[scene:三小时后·卧室]"))
    }

    @Test
    fun 关选项_首章请求里哨兵不见了() {
        val prompt = promptSentToLlm(choicesOff, firstChapter = true)
        assertFalse(prompt.contains(CHOICE_SENTINEL))
        assertTrue(prompt.contains(MARKUP_SENTINEL))
    }

    @Test
    fun 关选项_末句要点也跟着换_system与末句不打架() {
        val prompt = promptSentToLlm(CustomStoryPrompts(chapterChoicesEnabled = false), firstChapter = false)
        assertTrue(prompt.contains("- 本书不设章末选择，hasChoice 为 false，结尾留重钩子让人急着看下一章"))
        assertFalse(prompt.contains("- 结尾必须设置选择节点，给出 2-3 个方向明显不同的选项"))
    }

    @Test
    fun 老书JSON无开关键_行为与默认书一致() {
        // E7 的接线版（2026-08-05 拍板：无键 = 关，存量书一并变关）：老 JSON 同默认书走关闭态。
        val legacy = CustomStoryPrompts(pacingPreference = "慢热，多写日常")
        val prompt = promptSentToLlm(legacy, firstChapter = false)
        assertFalse(prompt.contains(CHOICE_SENTINEL))
        assertTrue(prompt.contains("### 选择分支（本书已关闭）"))
        assertTrue(prompt.contains(MARKUP_SENTINEL))
    }

    private companion object {
        /** 反向哨兵：全库唯一注入源 = StoryWritingTechniques 的选择节点段（图纸 §4-M5）。 */
        const val CHOICE_SENTINEL = "每章结尾必须设置一个选择节点"
        /** 正向哨兵：标记段的段头（唯一注入源 = StoryFormatRules）——关选项不许把它一起关掉。 */
        const val MARKUP_SENTINEL = "## 内容标记（严格遵守以下规则）"
    }
}
