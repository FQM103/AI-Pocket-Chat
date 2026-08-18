package com.situ.aichat.ui.story

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.StoryChapterEntity
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.repository.StoryRepository
import com.situ.aichat.story.StoryArchiver
import com.situ.aichat.story.StoryGenerationService
import com.situ.aichat.story.StoryGenerationTaskManager
import com.situ.aichat.story.StoryReadingProgressStore
import com.situ.aichat.story.StoryStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 已存走向两条 VM 薄接线 T2（图纸 2026-08-06「已存走向推进区状态化」§7 T2-2·边界 E6/E7/E8/E10）。
 *
 * 断言从图纸 §3.6 的规格独立反推。**Editor 不打桩、跑真实现**（只假掉 repository / taskManager 两个依赖）——
 * 钉的是「接线把 Editor 的结论翻译成了什么 UI 态」：成功 → 占选择文本槽 + 再问一次「立即生成/稍后」；
 * 忙碌 → 只发 toast、绝不弹「立即生成」；抛异常 → 不可重试错误（重试键只属生成失败路）。
 *
 * 照 [StoryReaderViewModelBeatsTest] 的五依赖构造姿势。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoryReaderViewModelDirectionTest {

    private val repository = mockk<StoryRepository>(relaxed = true)
    private val generationService = mockk<StoryGenerationService>(relaxed = true)
    private val taskManager = mockk<StoryGenerationTaskManager>(relaxed = true)
    private val readingProgressStore = mockk<StoryReadingProgressStore>(relaxed = true)

    private val scope = CoroutineScope(Dispatchers.Main)
    private val jobs = mutableListOf<Job>()
    private val toasts = mutableListOf<Int>()

    private val chapter = StoryChapterEntity(
        id = "ch1",
        storyId = "s1",
        chapterNumber = 3,
        title = "第三章",
        content = "正文",
        mood = "peaceful",
        hasChoice = true,
        userChoice = "旧走向：让她先回家",
    )

    @Before
    fun setUp() {
        coEvery { repository.getRoles("s1") } returns emptyList()
        coEvery { repository.getChapter("ch1") } returns chapter
        coEvery { repository.getChapterMetas("s1") } returns listOf(chapter)
        every { repository.observeStory("s1") } returns flowOf(StoryEntity(id = "s1", cachedChapterCount = 1))
        // Editor 跑真实现 → 忙碌守卫读的是这一条 fresh 读（默认给「连载中、没在生成」）。
        coEvery { repository.getStory("s1") } returns StoryEntity(id = "s1", status = StoryStatus.SERIALIZING)
        every { taskManager.activeGenerations } returns MutableStateFlow(emptyMap())
        every { taskManager.lastErrors } returns MutableStateFlow(emptyMap())
    }

    @After
    fun tearDown() {
        scope.cancel()
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

    /** 起 VM 并等 story + currentChapter 双热流就绪（两个写口分别取这两条流的值），同时收 toast 与 error。 */
    private fun StoryReaderViewModel.activate() = also {
        jobs += scope.launch { currentChapter.collect {} }
        jobs += scope.launch { story.collect {} }
        jobs += scope.launch { error.collect {} }
        jobs += scope.launch { toastEvents.collect { toasts += it } }
        await("热流就绪") { currentChapter.value?.id == "ch1" && story.value != null }
    }

    // ── E6 覆盖成功 ──

    /**
     * E6：覆盖成功 → 选择文本槽换成**新文本**（前后空白已 trim）+ 再问一次「立即生成 / 稍后」。
     * 反向钉：**不进 4 秒反悔窗**（那是首次提交的防手滑；编辑已存走向直写）、不发忙碌 toast。
     */
    @Test
    fun E6_覆盖成功_占选择文本槽并再问立即生成() {
        val viewModel = vm().activate()

        viewModel.overwriteDirection("  让她留在旅馆过夜  ")
        await("askNext 已置") { viewModel.askGenerateNextChapter.value }

        assertEquals("让她留在旅馆过夜", viewModel.selectedChoiceText.value)
        assertFalse("覆盖走的是直写，不进反悔窗", viewModel.pendingActive.value)
        assertTrue("不该发忙碌提示", toasts.isEmpty())
        coVerify(exactly = 1) {
            repository.commitUserChoice("s1", "ch1", "让她留在旅馆过夜", any(), setSerializing = false, fromStatus = null)
        }
    }

    // ── E10 忙碌 ──

    /**
     * E10：生成中覆盖 → 只发忙碌 toast，**askNext 保持 false**（不许弹「立即生成」诱导用户再点一次），
     * 且一条库都不写。toast 值钉死到具体资源 id（不是「非空」——relaxed mock 的假绿教训 PITFALLS 1e）。
     */
    @Test
    fun E10_生成中覆盖_只发忙碌toast_不弹立即生成也不写库() {
        coEvery { repository.getStory("s1") } returns StoryEntity(id = "s1", status = StoryStatus.GENERATING)
        val viewModel = vm().activate()

        viewModel.overwriteDirection("生成中还想改")
        await("忙碌提示已发") { toasts.isNotEmpty() }

        assertEquals(listOf(R.string.story_archive_busy_toast), toasts)
        assertFalse("忙碌路绝不弹「立即生成」", viewModel.askGenerateNextChapter.value)
        coVerify(exactly = 0) { repository.commitUserChoice(any(), any(), any(), any(), any(), any()) }
    }

    /** E10 撤回侧同款：忙碌 → toast + 零写，且**已选文本槽不被清空**（动作没成立，UI 不许假装成功）。 */
    @Test
    fun E10_生成中撤回_只发忙碌toast_已选文本不被清空() {
        coEvery { repository.getStory("s1") } returns StoryEntity(id = "s1", status = StoryStatus.GENERATING)
        val viewModel = vm().activate()
        await("进屏已同步已选文本") { viewModel.selectedChoiceText.value == "旧走向：让她先回家" }

        viewModel.withdrawDirection()
        await("忙碌提示已发") { toasts.isNotEmpty() }

        assertEquals(listOf(R.string.story_archive_busy_toast), toasts)
        assertEquals("旧走向：让她先回家", viewModel.selectedChoiceText.value)
        coVerify(exactly = 0) { repository.withdrawUserChoice(any(), any(), any(), any(), any()) }
    }

    // ── E7/E8 撤回成功 ──

    /**
     * E8：撤回成功（有选项章 + 连载中）→ 选择文本槽清空（回态 A / 选择区解锁），
     * 且回转实参为 true（撤回后追更自动路不会对重新待答的选择裸跑生成）。
     */
    @Test
    fun E8_撤回成功_清空已选文本并回转等待选择() {
        val viewModel = vm().activate()
        await("进屏已同步已选文本") { viewModel.selectedChoiceText.value == "旧走向：让她先回家" }

        viewModel.withdrawDirection()
        await("已选文本已清") { viewModel.selectedChoiceText.value == null }

        assertNull(viewModel.selectedChoiceText.value)
        assertFalse("撤回不是推进动作，不许弹「立即生成」", viewModel.askGenerateNextChapter.value)
        coVerify(exactly = 1) {
            repository.withdrawUserChoice(
                storyId = "s1", chapterId = "ch1", revertToWaitingChoice = true,
                fromStatus = StoryStatus.SERIALIZING, nowMillis = any(),
            )
        }
        coVerify(exactly = 0) { taskManager.startGeneration(any()) }
    }

    /** E7：无选项章撤回 → 同样清空文本槽，但回转实参为 false（状态不动）。 */
    @Test
    fun E7_无选项章撤回_不回转状态() {
        val noChoice = chapter.copy(hasChoice = false)
        coEvery { repository.getChapter("ch1") } returns noChoice
        coEvery { repository.getChapterMetas("s1") } returns listOf(noChoice)
        val viewModel = vm().activate()

        viewModel.withdrawDirection()
        await("已选文本已清") { viewModel.selectedChoiceText.value == null }

        coVerify(exactly = 1) {
            repository.withdrawUserChoice(
                storyId = "s1", chapterId = "ch1", revertToWaitingChoice = false,
                fromStatus = StoryStatus.SERIALIZING, nowMillis = any(),
            )
        }
    }

    // ── 失败路 ──

    /**
     * 落库抛异常 → **不可重试**错误（「重试」键只属生成失败路，否则一点会去生成新章）。
     * 断言钉「文案已正确落定」而不是「非空」（PITFALLS 1e）。
     */
    @Test
    fun 覆盖落库失败_报不可重试错误且不弹立即生成() {
        coEvery {
            repository.commitUserChoice(any(), any(), any(), any(), any(), any())
        } throws IllegalStateException("写盘失败")
        val viewModel = vm().activate()

        viewModel.overwriteDirection("新走向")
        await("错误已置") { viewModel.error.value != null }

        assertEquals("写盘失败", viewModel.error.value?.message)
        assertEquals(false, viewModel.error.value?.retryable)
        assertFalse("失败路不许弹「立即生成」", viewModel.askGenerateNextChapter.value)
    }

    /** 撤回落库抛异常 → 同款不可重试错误。 */
    @Test
    fun 撤回落库失败_报不可重试错误() {
        coEvery {
            repository.withdrawUserChoice(any(), any(), any(), any(), any())
        } throws IllegalStateException("撤回写盘失败")
        val viewModel = vm().activate()

        viewModel.withdrawDirection()
        await("错误已置") { viewModel.error.value != null }

        assertEquals("撤回写盘失败", viewModel.error.value?.message)
        assertEquals(false, viewModel.error.value?.retryable)
    }

    // ── 契约反向钉 ──

    /**
     * 结局意图「恰三注入点」契约（commitPendingChoice / forceContinue / rewrite）：
     * 本卷两条新接线一个都不许碰它，也一律不触发生成。
     */
    @Test
    fun 两条接线都不清结局意图也不触发生成() {
        val viewModel = vm().activate()

        viewModel.overwriteDirection("新走向")
        await("覆盖已落") { viewModel.askGenerateNextChapter.value }
        viewModel.withdrawDirection()
        await("撤回已落") { viewModel.selectedChoiceText.value == null }

        coVerify(exactly = 0) { repository.clearEndingRequest(any(), any()) }
        coVerify(exactly = 0) { taskManager.startGeneration(any()) }
    }
}
