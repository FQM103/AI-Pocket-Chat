package com.situ.aichat.story

import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.repository.StoryRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test

/**
 * R1 🟡-2 续篇结局徽章清列行为测试（T2·例①与暂停分支）：`continueStory` 从已完结开启续篇时清
 * `finalEndingType = null`（续篇将重新走向完结，上一次结局类型不再代表本书）；非完结起步（暂停）等值重写不改。
 * 配套 materializer 快照/保留（例②③）见 [StoryChapterMaterializerTest]。
 *
 * 手法：MockK 假掉 StoryRepository，命名实参 coVerify 钉死定向写参数；Log.* 走 returnDefaultValues。
 */
class StoryGenerationServiceContinueTest {

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
        coEvery {
            storyRepository.updateContinueState(any(), any(), any(), any(), any(), any())
        } just Runs
    }

    @Test
    fun `完结开启续篇_清finalEndingType为null`() = runBlocking {
        // 用户请求结局完结 → finalEndingType 定格 custom；此刻「开启续篇」→ 必须清 null（本次修正的错径）
        val s = StoryEntity(
            id = "s1", title = "书",
            status = StoryStatus.COMPLETED,
            finalEndingType = StoryEndingType.CUSTOM,
            maxChapters = 10, cachedLatestChapterNumber = 10, autoExtendCount = 0,
        )
        service.continueStory(s, nowMillis = 5_000L)
        coVerify {
            storyRepository.updateContinueState(
                id = "s1",
                status = StoryStatus.SERIALIZING,
                maxChapters = any(),      // completed 满章 → +扩展上限（不锁具体常量）
                autoExtendCount = 0,
                finalEndingType = null,   // ← 清列（本次修正）
                updatedAt = 5_000L,
            )
        }
    }

    @Test
    fun `暂停续写_finalEndingType等值重写不改`() = runBlocking {
        // 暂停起步（非完结）：finalEndingType 保持原值（暂停故事本就 null）——等值重写、不误清亦不越权
        val s = StoryEntity(
            id = "s2", title = "书",
            status = StoryStatus.PAUSED,
            finalEndingType = null,
            maxChapters = 10, cachedLatestChapterNumber = 4, autoExtendCount = 1,
        )
        service.continueStory(s, nowMillis = 6_000L)
        coVerify {
            storyRepository.updateContinueState(
                id = "s2",
                status = StoryStatus.SERIALIZING,
                maxChapters = 10,
                autoExtendCount = 1,
                finalEndingType = null,   // 等值重写（暂停本无结局徽章）
                updatedAt = 6_000L,
            )
        }
    }
}
