package com.situ.aichat.story

import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.repository.StoryRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 终章弧生成侧行为测试（T2·图纸 §7 T2-3 的服务层半边 / E6·E7·E15·E16）。
 *
 * 钉住三件事：
 * 1. **末章转正原子化**：倒数到末章时经 `promoteFinaleToEndingRequest` 一条 UPDATE 搬列，转正后**重读**故事
 *    （后续 prompt/字数/状态机必须吃搬完之后的快照）；
 * 2. **三重门**：无收尾计划 / 终章弧大纲还没落库 / 倒数未到，一律不转正；
 * 3. 幂等：finale 两列已空（上一次已转正）的书再走一遍不会二次转正。
 *
 * 手法：MockK 假掉 StoryRepository，命名实参 coVerify 钉死定向写；转正前置步设 `internal` 便于直调
 * （CLAUDE.md §3），换弧分支判定直接测纯函数 `decideOutlineAction`。
 * （用户可见的完整链路走 VM 侧 [com.situ.aichat.ui.story.StoryFinaleFlowTest]。）
 */
class StoryFinaleArcServiceTest {

    private lateinit var storyRepository: StoryRepository
    private lateinit var service: StoryGenerationService

    @Before
    fun setUp() {
        storyRepository = mockk()
        service = StoryGenerationService(
            llmClient = mockk(),
            contextLog = mockk(relaxed = true),
            storyRepository = storyRepository,
            apiConfigRepository = mockk(),
            apiFunctionRouter = mockk(),
            storyChatInfluenceBuilder = mockk(),
            storyCharacterDataCollector = mockk(),
            storyChapterMaterializer = mockk(),
            storyPayloadResolver = mockk(),
            storyWorldInfoService = mockk(),
            settingsRepository = mockk(),
            // 大纲编排外搬（卷二 C4 文件瘦身）：本组用例不碰大纲面，桩成**恒等直通**——
            // 与外搬前「无需生成大纲时 ensureOutline 原样返回入参」的行为一致，后续步骤照旧拿到同一个故事。
            storyOutlineOrchestrator = mockk<StoryOutlineOrchestrator>().also { orchestrator ->
                coEvery { orchestrator.ensureOutline(any(), any(), any(), any()) } answers { firstArg() }
            },
            storyCompressionCoordinator = mockk(relaxed = true),
        )
        coEvery { storyRepository.updateOutline(any(), any(), any()) } just Runs
        coEvery { storyRepository.updateOutlineAndArcHistory(any(), any(), any(), any()) } just Runs
        coEvery { storyRepository.promoteFinaleToEndingRequest(any(), any()) } just Runs
    }

    private fun finaleStory(
        arcStart: Int? = 40,
        plannedLength: Int? = 4,
        finaleType: String? = StoryEndingType.AI,
        outlinePresent: Boolean = true,
    ) = StoryEntity(
        id = "s1",
        title = "书",
        storyOutline = if (outlinePresent) {
            "${StoryArcPlanning.ARC_PLANNED_LENGTH_PREFIX}${plannedLength ?: 4}\n弧线主题：收网"
        } else {
            null
        },
        currentArcStartChapter = arcStart,
        finaleEndingType = finaleType,
        finaleEndingDetail = if (finaleType == StoryEndingType.CUSTOM) "海边和解" else null,
        cachedLatestChapterNumber = 42,
    )

    // ── ensureOutline：终章弧分支 ──

    @Test
    fun `有收尾计划且大纲为空_不生成普通弧而是走终章弧分支`() = runBlocking {
        // 定收尾计划把 storyOutline 清空 → 判定必然落到「大纲空 + finalePlanned」。此处只验决策，不跑 LLM。
        val action = StoryGenerationPolicy.decideOutlineAction(
            storyOutline = null,
            currentArcStartChapter = 30,
            chapterNumber = 40,
            plannedLength = null,
            finalePlanned = true,
        )
        assertEquals(StoryGenerationPolicy.OutlineAction.GenerateFinaleArc, action)
    }

    @Test
    fun `无收尾计划且大纲为空_走普通首弧`() {
        val action = StoryGenerationPolicy.decideOutlineAction(
            storyOutline = null, currentArcStartChapter = 30, chapterNumber = 40,
            plannedLength = null, finalePlanned = false,
        )
        assertEquals(StoryGenerationPolicy.OutlineAction.GenerateInitialArc, action)
    }

    @Test
    fun `终章弧写满自报章数_续接的仍是终章弧不是普通弧`() {
        // 终章弧 arcStart=40、自报 4 → 覆盖 40..43；第 44 章越界时若真发生换弧，也必须还是终章弧。
        val action = StoryGenerationPolicy.decideOutlineAction(
            storyOutline = "${StoryArcPlanning.ARC_PLANNED_LENGTH_PREFIX}4",
            currentArcStartChapter = 40, chapterNumber = 44, plannedLength = 4, finalePlanned = true,
        )
        assertEquals(StoryGenerationPolicy.OutlineAction.GenerateFinaleArc, action)
    }

    @Test
    fun `终章弧弧长按终章区间钳位_不用普通弧的8到15`() {
        // 自报 12：普通弧钳 12、终章弧钳 5 → 第 45 章在普通口径下还没满，在终章口径下早已越界。
        assertEquals(
            StoryGenerationPolicy.OutlineAction.None,
            StoryGenerationPolicy.decideOutlineAction("大纲", 40, 45, plannedLength = 12, finalePlanned = false),
        )
        assertEquals(
            StoryGenerationPolicy.OutlineAction.GenerateFinaleArc,
            StoryGenerationPolicy.decideOutlineAction("大纲", 40, 45, plannedLength = 12, finalePlanned = true),
        )
    }

    // ── 末章转正（经 generateNextChapter 的前置步·唯一可观测出口 = 定向写调用与否）──

    /** 转正后**必须重读**故事：不重读的话后续 prompt 吃的还是旧快照（requestedEnding 还没进来）→ 写不成结局章。 */
    @Test
    fun `倒数到末章_转正并重读故事`() = runBlocking {
        val story = finaleStory(arcStart = 40, plannedLength = 4)  // 覆盖 40..43，第 43 章 = 末章
        val promoted = story.copy(
            requestedEndingType = StoryEndingType.AI,
            finaleEndingType = null,
            finaleEndingDetail = null,
        )
        coEvery { storyRepository.getStory("s1") } returns promoted

        val result = invokePromote(story, chapterNumber = 43)

        coVerify(exactly = 1) { storyRepository.promoteFinaleToEndingRequest("s1", 9_000L) }
        assertEquals("重读后的快照里结局请求已就位", StoryEndingType.AI, result.requestedEndingType)
        assertNull("重读后的快照里收尾计划已清", result.finaleEndingType)
    }

    @Test
    fun `倒数未到末章_不转正`() = runBlocking {
        val story = finaleStory(arcStart = 40, plannedLength = 4)
        val result = invokePromote(story, chapterNumber = 42)  // 本弧第 3 章

        coVerify(exactly = 0) { storyRepository.promoteFinaleToEndingRequest(any(), any()) }
        assertEquals("没转正 → 收尾计划原样在", StoryEndingType.AI, result.finaleEndingType)
        assertNull(result.requestedEndingType)
    }

    /** E7 反面：终章弧大纲生成失败（storyOutline 仍空）时**绝不**转正——否则会拿上一条普通弧的起点算出 LAST。 */
    @Test
    fun `终章弧大纲还没落库_即便章号很大也不转正`() = runBlocking {
        val story = finaleStory(arcStart = 5, plannedLength = null, outlinePresent = false)
        invokePromote(story, chapterNumber = 99)
        coVerify(exactly = 0) { storyRepository.promoteFinaleToEndingRequest(any(), any()) }
    }

    /** 幂等 / E6：finale 两列已空（上一次已转正）→ 同一章重试不会二次转正。 */
    @Test
    fun `没有收尾计划的书_零成本直通不转正`() = runBlocking {
        val story = finaleStory(finaleType = null).copy(requestedEndingType = StoryEndingType.AI)
        val result = invokePromote(story, chapterNumber = 43)
        coVerify(exactly = 0) { storyRepository.promoteFinaleToEndingRequest(any(), any()) }
        assertEquals("已在的结局请求原样保留（ST11 意图保留）", StoryEndingType.AI, result.requestedEndingType)
    }

    /**
     * 直接调转正前置步（`internal` 便于测·CLAUDE.md §3）：经 `generateNextChapter` 触发会拖进整条 LLM 管线，
     * 此处只测「判定 + 定向写 + 重读」这一段（管线其余部分有各自的测试）。
     */
    private suspend fun invokePromote(story: StoryEntity, chapterNumber: Int): StoryEntity =
        service.promoteFinaleIfLastChapter(story, chapterNumber, nowMillis = 9_000L)
}
