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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 末句要点复述**接线层** T2（R1 复核返工·MockK 驱动真路径）。
 *
 * 为什么非要这一条：纯函数层的「四档全等」单测挡不住**接线错**。复核方做过变异实证——把
 * `StoryGenerationService` 里的 `baseChapterLength = withOutline.chapterLengthPreference` 改成
 * `= effectiveLength`（结局章已 ×1.5 的那个值），**全量测试一条都不红**，而线上会变成
 * system 段说「1800-2700 字」、末句说「2700-4050 字」的静默打架。
 *
 * 故本测试的断言点不是「末句长什么样」，而是**同一次请求里 system 段与 user 末句的字数必须是同一对数字**——
 * 从 `llmClient.streamChat` 实收的 messages 上取，是这条通路上离模型最近的可观测量。
 */
class StoryCreationUserMessageWiringTest {

    private lateinit var llmClient: LlmClient
    private lateinit var storyRepository: StoryRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var collector: StoryCharacterDataCollector
    private lateinit var service: StoryGenerationService

    private val config = ApiConfigValues(
        providerType = ApiProviderType.OPENAI_COMPATIBLE, apiKey = "k",
        baseUrl = "https://example.test", modelName = "m",
    )

    /** 无限连载 + 已有弧线大纲 → 隔离掉大纲那次 LLM 调用；章节长度取 MEDIUM(1500) 便于手算期望值。 */
    private val story = StoryEntity(
        id = "s1", title = "书", genre = "言情",
        chapterLengthPreference = 1_500,
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

    /** 跑一次续章生成，返回 streamChat 实收的 messages。 */
    private fun messagesForNextChapter(target: StoryEntity): List<ChatMessageDto> {
        val slot = slot<List<ChatMessageDto>>()
        every {
            llmClient.streamChat(capture(slot), any(), any(), any(), any(), any(), any(), any(), any())
        } returns flowOf(StreamToken.Content("正文写到这里就结尾了。"))
        runBlocking { service.generateNextChapter(target, nowMillis = 1_000L) }
        return slot.captured
    }

    /** 从任意文本里抓「NNNN-NNNN」字数区间（system 段写「本章目标字数：X-Y 字」，末句写「目标字数 X-Y 字」）。 */
    private fun wordRangeOf(text: String): String =
        Regex("""目标字数[：\s](\d+-\d+)\s*字""").find(text)?.groupValues?.get(1)
            ?: error("没抓到字数区间：${text.take(120)}")

    @Test
    fun `普通续章_末句字数与system段是同一对数字`() {
        val messages = messagesForNextChapter(story)
        assertEquals("system + user 两条", 2, messages.size)
        val system = messages[0].content!!
        val userTail = messages[1].content!!
        assertEquals("1500 档 ±20%", "1200-1800", wordRangeOf(system))
        assertEquals("末句必须与 system 段同一对数字", wordRangeOf(system), wordRangeOf(userTail))
        assertTrue("末句带要点标题", userTail.contains(CREATION_RECAP_HEADER))
        // 2026-08-05 拍板章末选项默认关：没动过开关的书走 M4 关闭态行，不再喊选择节点。
        assertTrue("默认书末句 = 关闭态 M4 行", userTail.contains("本书不设章末选择，hasChoice 为 false，结尾留重钩子让人急着看下一章"))
        assertFalse(userTail.contains("结尾必须设置选择节点"))
    }

    @Test
    fun `结局章_末句字数与system结局段是同一对数字且不喊选择节点`() {
        // 这一例正是变异 M1（baseChapterLength 误传 effectiveLength = 2250）会红的地方：
        // 那时末句会算成 2700-4050，而 system 段仍是 1800-2700。
        val messages = messagesForNextChapter(story.copy(requestedEndingType = "ai"))
        val system = messages[0].content!!
        val userTail = messages[1].content!!
        assertEquals("结局章 = 普通上限 → 上限 ×1.5", "1800-2700", wordRangeOf(system))
        assertEquals("末句必须与 system 结局段同一对数字", wordRangeOf(system), wordRangeOf(userTail))
        assertTrue("结局章要点", userTail.contains("hasChoice 为 false"))
        assertFalse("结局章绝不能喊选择节点", userTail.contains("结尾必须设置选择节点"))
    }

    @Test
    fun `首章_末句字数与system段是同一对数字`() {
        val slot = slot<List<ChatMessageDto>>()
        every {
            llmClient.streamChat(capture(slot), any(), any(), any(), any(), any(), any(), any(), any())
        } returns flowOf(StreamToken.Content("第一章写到这里就结尾了。"))
        runBlocking {
            service.generateFirstChapter(
                story.copy(cachedLatestChapterNumber = null, cachedChapterCount = 0),
                nowMillis = 1_000L,
            )
        }
        val system = slot.captured[0].content!!
        val userTail = slot.captured[1].content!!
        assertEquals("首章两个字数参数同为基础档", wordRangeOf(system), wordRangeOf(userTail))
        assertEquals("请开始创作第一章。", userTail.lines().first())
    }
}
