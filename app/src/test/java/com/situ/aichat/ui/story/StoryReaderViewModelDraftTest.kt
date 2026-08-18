package com.situ.aichat.ui.story

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.StoryChapterEntity
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.repository.StoryRepository
import com.situ.aichat.story.StoryArchiver
import com.situ.aichat.story.StoryChapterDraft
import com.situ.aichat.story.StoryGenerationService
import com.situ.aichat.story.StoryGenerationTaskManager
import com.situ.aichat.story.StoryReadingProgressStore
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 阅读器 VM「上一版换回」T2（C3·图纸三 §7 T2-2）：互换真写库（13 列一条）、对合（换两次回到起点·E5）、
 * 空槽/损坏槽零动作（E6）、成功提示。
 *
 * 断言从图纸 §3.1「互换 = 单条定向 UPDATE、不触发生成、不动叙事场」独立反推：
 * 因此这里同时**反向钉**——互换绝不调 startGeneration、绝不调 updateNarrativeState / 档案字段的单列写。
 * 手法照 [StoryReaderViewModelTest]（MockK + Robolectric 主循环驱动 viewModelScope）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoryReaderViewModelDraftTest {

    private val repository = mockk<StoryRepository>(relaxed = true)
    private val generationService = mockk<StoryGenerationService>(relaxed = true)
    private val taskManager = mockk<StoryGenerationTaskManager>(relaxed = true)
    private val readingProgressStore = mockk<StoryReadingProgressStore>(relaxed = true)

    private val story = StoryEntity(id = "s1", cachedChapterCount = 1)

    private val scope = CoroutineScope(Dispatchers.Main)
    private val jobs = mutableListOf<Job>()

    /** 重写前的那一版（= 槽里装的东西）。 */
    private val oldDraft = StoryChapterDraft(
        title = "旧标题",
        teaser = "旧引子",
        content = "旧正文",
        mood = "peaceful",
        scenes = null,
        hasChoice = false,
        choicePrompt = null,
        choiceOptions = null,
        userChoice = null,
        choiceMadeAt = null,
        aiSuggestedEnding = false,
        chapterSummary = "旧小结",
    )

    private fun chapter(previousDraftJson: String? = StoryChapterDraft.encode(oldDraft)) = StoryChapterEntity(
        id = "ch1",
        storyId = "s1",
        chapterNumber = 1,
        title = "新标题",
        content = "新正文",
        mood = "tense",
        hasChoice = true,
        choicePrompt = "你决定",
        choiceOptions = """["A","B"]""",
        chapterSummary = "新小结",
        previousDraftJson = previousDraftJson,
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

    private fun collectToasts(viewModel: StoryReaderViewModel, into: MutableList<Int>) {
        jobs += scope.launch { viewModel.toastEvents.collect { into += it } }
        idle()
    }

    // ── 互换真写库 ──

    @Test
    fun 换回上一版_写入内容取旧稿_槽换成互换前的当前章() {
        giveChapter(chapter())
        val written = slot<StoryChapterEntity>()
        coEvery { repository.swapChapterDraft(capture(written)) } just Runs
        val viewModel = vm().activate()

        viewModel.restorePreviousDraft()
        await("互换写库") { written.isCaptured }

        val w = written.captured
        assertEquals("正文换成上一版", "旧正文", w.content)
        assertEquals("标题换成上一版", "旧标题", w.title)
        assertEquals("小结一并换回", "旧小结", w.chapterSummary)
        assertEquals("选项态一并换回", false, w.hasChoice)
        assertEquals(
            "槽里换成互换前的当前章",
            StoryChapterDraft.fromEntity(chapter()),
            StoryChapterDraft.decode(w.previousDraftJson),
        )
        assertEquals("轨道字段不动", 1, w.chapterNumber)
        assertEquals("轨道字段不动", "ch1", w.id)
    }

    /**
     * E5 对合（VM 层，走真刷新路）：互换写库后 VM 经 refreshChapters 重读到新章，再换一次逐字段回到最初那一版。
     * 这里的 repository 桩表现得像真存储（写进去什么，下次就读到什么），因此也顺带验了「换回后阅读器看到的确实变了」。
     */
    @Test
    fun E5_连换两次回到起点() {
        val original = chapter()
        var stored = original
        val written = mutableListOf<StoryChapterEntity>()
        coEvery { repository.getChapter("ch1") } answers { stored }
        coEvery { repository.getChapterMetas("s1") } answers { listOf(stored) }
        coEvery { repository.swapChapterDraft(any()) } answers {
            stored = firstArg()
            written += stored
        }
        val viewModel = vm().activate()

        viewModel.restorePreviousDraft()
        await("VM 读到互换后的章") { viewModel.currentChapter.value?.content == "旧正文" }

        viewModel.restorePreviousDraft()
        await("第二次互换") { written.size == 2 }
        await("VM 读回最初那一版") { viewModel.currentChapter.value?.content == "新正文" }

        assertEquals("换两次回到最初那一版", original, written[1])
    }

    @Test
    fun 换回成功_提示可再次切换() {
        giveChapter(chapter())
        coEvery { repository.swapChapterDraft(any()) } just Runs
        val viewModel = vm().activate()
        val toasts = mutableListOf<Int>()
        collectToasts(viewModel, toasts)

        viewModel.restorePreviousDraft()
        await("提示发出") { toasts.isNotEmpty() }

        assertEquals(listOf(R.string.story_prev_draft_swapped_toast), toasts)
    }

    /** 反向钉：互换是纯本地对调——绝不触发生成、绝不动故事级叙事字段（§0.2-5 裁决的直接看门狗）。 */
    @Test
    fun 换回不触发生成也不动叙事场() {
        giveChapter(chapter())
        coEvery { repository.swapChapterDraft(any()) } just Runs
        val viewModel = vm().activate()

        viewModel.restorePreviousDraft()
        await("互换写库") { true }
        idle()

        coVerify(exactly = 0) { taskManager.startGeneration(any()) }
        coVerify(exactly = 0) {
            repository.updateNarrativeState(
                id = any(), storySummary = any(), currentArc = any(), characterStates = any(), openThreads = any(),
                pendingChapterBeats = any(), storyBible = any(), status = any(), maxChapters = any(),
                autoExtendCount = any(), requestedEndingType = any(), requestedEndingDetail = any(),
                rewriteInstruction = any(), finalEndingType = any(), intimacyLedger = any(), sceneState = any(), sceneLedger = any(),
                updatedAt = any(),
            )
        }
        // 卷二 J3 起记忆四字段的写口是档案编辑页的单列定向写（updateStoryMemory 已退役），钉它们同样一次都不许发生
        coVerify(exactly = 0) { repository.updateStorySummaryUserEdit(any(), any(), any()) }
        coVerify(exactly = 0) { repository.updateCharacterStates(any(), any(), any()) }
        coVerify(exactly = 0) { repository.updateOpenThreads(any(), any(), any()) }
        coVerify(exactly = 0) { repository.updateChapter(any()) } // 整行 @Update 是禁区
    }

    // ── 无槽 / 坏槽 ──

    @Test
    fun 空槽_不写库() {
        giveChapter(chapter(previousDraftJson = null))
        val viewModel = vm().activate()

        viewModel.restorePreviousDraft()
        idle()

        coVerify(exactly = 0) { repository.swapChapterDraft(any()) }
    }

    /** E6：槽里是损坏 JSON → 解码 null → 静默不动（菜单项本就不该出现，这里是第二道闸）。 */
    @Test
    fun E6_损坏槽_不写库不崩() {
        giveChapter(chapter(previousDraftJson = "{\"title\":\"没闭合"))
        val viewModel = vm().activate()

        viewModel.restorePreviousDraft()
        idle()

        coVerify(exactly = 0) { repository.swapChapterDraft(any()) }
        assertTrue("不抛不崩", true)
    }

    /** 菜单项显隐的供血：槽非空 → previousDraft 有值；无槽/坏槽 → null（UI 据此隐藏「查看上一版」）。 */
    @Test
    fun previousDraft流_有槽出值_无槽出null() {
        giveChapter(chapter())
        val withSlot = vm().activate()
        jobs += scope.launch { withSlot.previousDraft.collect {} }
        await("槽解码就绪") { withSlot.previousDraft.value != null }
        assertEquals("旧正文", withSlot.previousDraft.value?.content)

        giveChapter(chapter(previousDraftJson = null))
        val without = vm().activate()
        jobs += scope.launch { without.previousDraft.collect {} }
        idle()
        assertNull("无槽 → null（菜单项隐藏）", without.previousDraft.value)
    }

    // ── C4 本章小结 ──

    @Test
    fun 保存小结_trim后列级定向写当前章() {
        giveChapter(chapter())
        val viewModel = vm().activate()

        viewModel.saveChapterSummary("  这一章两人在楼道初次搭话  ")
        idle()

        coVerify(exactly = 1) { repository.updateChapterSummary("ch1", "这一章两人在楼道初次搭话") }
    }

    /** E9：清空保存 → 存 null（回落「无小结」，续章前情滑窗的 mapNotNull 会跳过该章），不许存空串。 */
    @Test
    fun E9_空白输入_存null() {
        giveChapter(chapter())
        val viewModel = vm().activate()

        viewModel.saveChapterSummary("   \n  ")
        idle()

        coVerify(exactly = 1) { repository.updateChapterSummary("ch1", null) }
    }

    /** E10：超长小结照存不截——用户主权，prompt 侧滑窗自身有窗口控制。 */
    @Test
    fun E10_超长小结照存不截() {
        giveChapter(chapter())
        val viewModel = vm().activate()
        val long = "很".repeat(800)

        viewModel.saveChapterSummary(long)
        idle()

        coVerify(exactly = 1) { repository.updateChapterSummary("ch1", long) }
    }

    /** 反向钉：小结写路是列级定向写——绝不整行 @Update（会 clobber 生成链正在写的 content 等列），也不触发生成。 */
    @Test
    fun 保存小结_不走整行更新也不触发生成() {
        giveChapter(chapter())
        val viewModel = vm().activate()

        viewModel.saveChapterSummary("新的小结")
        idle()

        coVerify(exactly = 0) { repository.updateChapter(any()) }
        coVerify(exactly = 0) { taskManager.startGeneration(any()) }
        coVerify(exactly = 0) { repository.swapChapterDraft(any()) }
    }

    // ── D5 角色现状 ──

    /** D5 的数据面就是 story 级滚动值：有值原样透出（弹层只读显示）。 */
    @Test
    fun 角色现状_原样透出story级滚动值() {
        every { repository.observeStory("s1") } returns flowOf(story.copy(characterStates = "夏晴子（表面从容）"))
        giveChapter(chapter())
        val viewModel = vm().activate()
        jobs += scope.launch { viewModel.story.collect {} }
        await("story 就绪") { viewModel.story.value != null }

        assertEquals("夏晴子（表面从容）", viewModel.story.value?.characterStates)
    }

    /** E13：从没生成过章的书 characterStates 为 null → VM 如实给 null，弹层据此走空态文案（装机复验）。 */
    @Test
    fun E13_角色现状为空_VM如实给null() {
        giveChapter(chapter())
        val viewModel = vm().activate()
        jobs += scope.launch { viewModel.story.collect {} }
        await("story 就绪") { viewModel.story.value != null }

        assertNull("空态由 UI 兜文案，VM 不许自作主张填占位串", viewModel.story.value?.characterStates)
    }
}
