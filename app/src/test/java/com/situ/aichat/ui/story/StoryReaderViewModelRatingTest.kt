package com.situ.aichat.ui.story

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import com.situ.aichat.data.local.entity.StoryChapterEntity
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.repository.StoryRepository
import com.situ.aichat.story.StoryArchiver
import com.situ.aichat.story.StoryGenerationService
import com.situ.aichat.story.StoryGenerationTaskManager
import com.situ.aichat.story.StoryReadingProgressStore
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 三档快评 T2（故事二期卷三·图纸 §7 T2-1·边界 E1/E2）。
 *
 * 断言从图纸 §3.1/§3.2/J3 的规格独立反推：**列级定向写 + 立刻刷新章节列表 + 无反悔窗 + 失败不打断阅读**。
 * 因此这里同时反向钉：快评绝不进 5 秒反悔窗（pendingActive 恒 false）、绝不触发生成、绝不碰节拍写路。
 * 手法照 [StoryReaderViewModelDraftTest]（MockK + Robolectric 主循环驱动 viewModelScope）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoryReaderViewModelRatingTest {

    private val repository = mockk<StoryRepository>(relaxed = true)
    private val generationService = mockk<StoryGenerationService>(relaxed = true)
    private val taskManager = mockk<StoryGenerationTaskManager>(relaxed = true)
    private val readingProgressStore = mockk<StoryReadingProgressStore>(relaxed = true)

    private val story = StoryEntity(id = "s1", cachedChapterCount = 1)

    private val scope = CoroutineScope(Dispatchers.Main)
    private val jobs = mutableListOf<Job>()

    private fun chapter(userRating: Int? = null) = StoryChapterEntity(
        id = "ch1",
        storyId = "s1",
        chapterNumber = 7,
        title = "第七章",
        content = "正文",
        mood = "peaceful",
        userRating = userRating,
    )

    @Before
    fun setUp() {
        coEvery { repository.getRoles("s1") } returns emptyList()
        every { repository.observeStory("s1") } returns flowOf(story)
        every { taskManager.activeGenerations } returns MutableStateFlow(emptyMap())
        every { taskManager.lastErrors } returns MutableStateFlow(emptyMap())
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun giveChapter(ch: StoryChapterEntity) {
        coEvery { repository.getChapter("ch1") } returns ch
        coEvery { repository.getChapterMetas("s1") } returns listOf(ch)
    }

    private fun vm(): StoryReaderViewModel = StoryReaderViewModel(
        SavedStateHandle(mapOf("chapterId" to "ch1")),
        repository,
        generationService,
        taskManager,
        readingProgressStore,
        StoryArchiver(repository, taskManager),
    )

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun await(message: String, condition: () -> Boolean) {
        repeat(200) {
            idle()
            if (condition()) return
            Thread.sleep(5)
        }
        error("等待超时：$message")
    }

    private fun StoryReaderViewModel.activate() = also {
        jobs += scope.launch { currentChapter.collect {} }
        await("currentChapter 就绪") { currentChapter.value?.id == "ch1" }
    }

    // ── E1：三档 / 改评 / 取消 ──

    /**
     * E1 完整路径（未评 → 评 3 → 改评 1 → 再点 1 取消）在**同一条 VM 上顺序走完**，
     * repository 桩表现得像真存储（写进去什么下次就读到什么），于是也顺带验了「屏上看到的档位随之切换」。
     */
    @Test
    fun E1_未评到评3到改评1到取消_落库序为3_1_null() {
        var stored = chapter()
        val writes = mutableListOf<Int?>()
        coEvery { repository.getChapter("ch1") } answers { stored }
        coEvery { repository.getChapterMetas("s1") } answers { listOf(stored) }
        coEvery { repository.updateChapterRating("ch1", any()) } answers {
            val rating = secondArg<Int?>()
            writes += rating
            stored = stored.copy(userRating = rating)
        }
        val viewModel = vm().activate()
        assertNull("起手未评", viewModel.currentChapter.value?.userRating)

        viewModel.rateChapter(3)
        await("评 3 回流") { viewModel.currentChapter.value?.userRating == 3 }

        viewModel.rateChapter(1)
        await("改评 1 回流") { viewModel.currentChapter.value?.userRating == 1 }

        // 再点已选中的那一档 = 取消（屏侧把 null 传进来，VM 照写·§3.2）。
        viewModel.rateChapter(null)
        await("取消回流") { viewModel.currentChapter.value?.userRating == null }

        assertEquals("落库序", listOf<Int?>(3, 1, null), writes)
    }

    /** 中间档「还行」= 2（三档映射 爽3/还行2/不行1·图纸 §4.3），VM 原样透传不做任何折算。 */
    @Test
    fun 还行档_原样写2() {
        giveChapter(chapter())
        val viewModel = vm().activate()

        viewModel.rateChapter(2)
        idle()

        coVerify(exactly = 1) { repository.updateChapterRating("ch1", 2) }
    }

    /** 评的是**当前打开的那一章**（不是 chapters 里的最后一章）——章 id 取自 currentChapter。 */
    @Test
    fun 写的是当前打开的那一章() {
        giveChapter(chapter())
        val viewModel = vm().activate()

        viewModel.rateChapter(3)
        idle()

        coVerify(exactly = 1) { repository.updateChapterRating("ch1", 3) }
    }

    /** 写库后必须刷新章节列表——否则 currentChapter 停在旧值，快评行的提示语/选中态不会变（§3.1 惯例）。 */
    @Test
    fun 落库后刷新章节列表_UI值才会变() {
        var stored = chapter()
        coEvery { repository.getChapter("ch1") } answers { stored }
        coEvery { repository.getChapterMetas("s1") } answers { listOf(stored) }
        coEvery { repository.updateChapterRating("ch1", any()) } answers {
            stored = stored.copy(userRating = secondArg())
        }
        val viewModel = vm().activate()

        viewModel.rateChapter(3)
        await("章节列表被重载 → 新值回流") { viewModel.currentChapter.value?.userRating == 3 }

        // 重载确实发生过：cachedChapterCount 一直没变，唯一能触发重读的就是 refreshChapters。
        coVerify(atLeast = 2) { repository.getChapterMetas("s1") }
    }

    // ── E2：失败路 ──

    /** E2：落库失败 → 弹**不可重试**的错误（重试键只属生成失败路），且 UI 值仍是热流里的原值。 */
    @Test
    fun E2_落库失败_报不可重试错误且不改UI值() {
        giveChapter(chapter(userRating = 2))
        coEvery { repository.updateChapterRating("ch1", any()) } throws IllegalStateException("磁盘满了")
        val viewModel = vm().activate()
        jobs += scope.launch { viewModel.error.collect {} }

        viewModel.rateChapter(3)
        await("错误已置") { viewModel.error.value != null }

        assertEquals("磁盘满了", viewModel.error.value?.message)
        assertEquals("操作类失败不给重试键", false, viewModel.error.value?.retryable)
        assertEquals("UI 值随热流留在原档", 2, viewModel.currentChapter.value?.userRating)
    }

    /** E2 续：失败不该把用户从阅读里踢出去——反悔窗、生成、章节切换一概不动。 */
    @Test
    fun E2_落库失败_不打断阅读() {
        giveChapter(chapter())
        coEvery { repository.updateChapterRating("ch1", any()) } throws IllegalStateException("boom")
        val viewModel = vm().activate()

        viewModel.rateChapter(1)
        idle()

        assertEquals("仍停在同一章", "ch1", viewModel.currentChapterId.value)
        assertEquals("没有进反悔窗", false, viewModel.pendingActive.value)
        coVerify(exactly = 0) { taskManager.startGeneration(any()) }
    }

    // ── 反向钉（J3：快评 ≠ 选择） ──

    /**
     * J3 的核心：快评**不套 5 秒反悔窗**——写即落库、无待提交态、无撤销条，
     * 也绝不经 `commitUserChoice` 那条会决定下一章走向的路。
     */
    @Test
    fun J3_快评无反悔窗_不碰选择链() {
        giveChapter(chapter())
        coEvery { repository.updateChapterRating("ch1", any()) } just Runs
        val viewModel = vm().activate()

        viewModel.rateChapter(3)
        idle()

        assertEquals("无待提交态", false, viewModel.pendingActive.value)
        assertNull("不占用选择文本槽", viewModel.selectedChoiceText.value)
        coVerify(exactly = 0) { repository.commitUserChoice(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { taskManager.startGeneration(any()) }
    }

    /** 快评是章级元数据：绝不走整行 @Update（会 clobber 生成链正在写的列），也不碰节拍写路。 */
    @Test
    fun 快评只走列级定向写() {
        giveChapter(chapter())
        coEvery { repository.updateChapterRating("ch1", any()) } just Runs
        val viewModel = vm().activate()

        viewModel.rateChapter(3)
        idle()

        coVerify(exactly = 0) { repository.updateChapter(any()) }
        coVerify(exactly = 0) { repository.updateChapterBeatsUserEdited(any(), any(), any()) }
        coVerify(exactly = 0) { repository.updateChapterSummary(any(), any()) }
    }

    /** 无章可评（空书 / 生成中占位）时静默不动：显示门（showChapterRating）本就不该让入口出现，这是第二道闸。 */
    @Test
    fun 无当前章_不写库() {
        coEvery { repository.getChapter("ch1") } returns null
        coEvery { repository.getChapterMetas("s1") } returns emptyList()
        val viewModel = vm()
        jobs += scope.launch { viewModel.currentChapter.collect {} }
        idle()

        viewModel.rateChapter(3)
        idle()

        coVerify(exactly = 0) { repository.updateChapterRating(any(), any()) }
    }
}
