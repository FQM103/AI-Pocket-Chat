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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 导演台两栏写路 T2（故事二期卷三·图纸 §7 T2-2·边界 E5/E6/E7/E9）。
 *
 * 断言从图纸 §3.1/J2 的规格独立反推：
 * - **走向栏 = 复用既有 `submitChoice`**（进 5 秒反悔窗，与「写一句」逐字节同路），**绝不新建写路**；
 * - **节拍栏 = 一条 `updateChapterBeatsUserEdited`**（beats + 标志原子写），空白落 null；
 * - 「恢复 AI 预排」只复位标志，文本一个字不动。
 *
 * 面板里「哪栏变了就发哪条」的分派是 UI 侧逻辑（[StoryDirectorSheet]），本测按分派结果**照四种组合
 * 驱动 VM 入口**，钉的是「每条组合下 VM 到底写了什么、没写什么」。四组合的**触发**本身走装机走查。
 *
 * 反悔窗到点落库那一步以 `System.currentTimeMillis()` 挂钟驱动、Robolectric 推不动（见
 * [StoryReaderViewModelTest] 同款说明），故走向栏的证据取「已进反悔窗 + 已占选择文本槽」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoryReaderViewModelBeatsTest {

    private val repository = mockk<StoryRepository>(relaxed = true)
    private val generationService = mockk<StoryGenerationService>(relaxed = true)
    private val taskManager = mockk<StoryGenerationTaskManager>(relaxed = true)
    private val readingProgressStore = mockk<StoryReadingProgressStore>(relaxed = true)

    private val aiBeats = "先写晚饭时的试探，重点场景放在后半章"

    private val scope = CoroutineScope(Dispatchers.Main)
    private val jobs = mutableListOf<Job>()

    private fun story(beats: String? = aiBeats, edited: Boolean = false) = StoryEntity(
        id = "s1",
        cachedChapterCount = 1,
        pendingChapterBeats = beats,
        pendingBeatsUserEdited = edited,
    )

    private val chapter = StoryChapterEntity(
        id = "ch1",
        storyId = "s1",
        chapterNumber = 3,
        title = "第三章",
        content = "正文",
        mood = "peaceful",
    )

    @Before
    fun setUp() {
        coEvery { repository.getRoles("s1") } returns emptyList()
        coEvery { repository.getChapter("ch1") } returns chapter
        coEvery { repository.getChapterMetas("s1") } returns listOf(chapter)
        every { repository.observeStory("s1") } returns flowOf(story())
        every { taskManager.activeGenerations } returns MutableStateFlow(emptyMap())
        every { taskManager.lastErrors } returns MutableStateFlow(emptyMap())
        coEvery { repository.updateChapterBeatsUserEdited(any(), any(), any()) } just Runs
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

    /** 起 VM 并等 story + currentChapter 双热流就绪（两个写口分别取这两条流的值）。 */
    private fun StoryReaderViewModel.activate() = also {
        jobs += scope.launch { currentChapter.collect {} }
        jobs += scope.launch { story.collect {} }
        await("热流就绪") { currentChapter.value?.id == "ch1" && story.value != null }
    }

    // ── E5 四组合（分派结果驱动） ──

    /** E5-①「只填走向」：走 submitChoice 进反悔窗；**节拍写路一次都不许发生**（图纸 §7 点名的反向断言）。 */
    @Test
    fun E5_只填走向_只走submitChoice_不碰节拍写路() {
        val viewModel = vm().activate()

        viewModel.submitChoice("让她在温泉旅馆偶遇两人")
        idle()

        assertTrue("已进反悔窗（= 与「写一句」同路的实锤）", viewModel.pendingActive.value)
        assertEquals("让她在温泉旅馆偶遇两人", viewModel.selectedChoiceText.value)
        coVerify(exactly = 0) { repository.updateChapterBeatsUserEdited(any(), any(), any()) }
    }

    /** E5-②「只改节拍」：一条定向 UPDATE 写 beats + 标志；**绝不占用选择链**（不进反悔窗、不落选择）。 */
    @Test
    fun E5_只改节拍_只发一条节拍写_不碰选择链() {
        val viewModel = vm().activate()

        viewModel.saveChapterBeats("重点场景放在房间，四拍走满")
        idle()

        coVerify(exactly = 1) { repository.updateChapterBeatsUserEdited("s1", "重点场景放在房间，四拍走满", true) }
        assertEquals("没有进反悔窗", false, viewModel.pendingActive.value)
        assertNull("没有占用选择文本槽", viewModel.selectedChoiceText.value)
        coVerify(exactly = 0) { repository.commitUserChoice(any(), any(), any(), any(), any(), any()) }
    }

    /** E5-③「都填」：两栏**各自独立提交**，互不阻塞——两条写各发一次。 */
    @Test
    fun E5_都填_两条写各发一次() {
        val viewModel = vm().activate()

        viewModel.submitChoice("让她主动提出留下")
        viewModel.saveChapterBeats("拖足铺垫再进重点场景")
        idle()

        coVerify(exactly = 1) { repository.updateChapterBeatsUserEdited("s1", "拖足铺垫再进重点场景", true) }
        assertTrue("走向仍照常进反悔窗（节拍写不阻塞它）", viewModel.pendingActive.value)
        assertEquals("让她主动提出留下", viewModel.selectedChoiceText.value)
    }

    /** E5-④「都不动」：面板不调任何 VM 写口 → 一条库都不写（这里钉的是「VM 侧没有隐式自动保存」）。 */
    @Test
    fun E5_都不动_一条库都不写() {
        vm().activate()
        idle()

        coVerify(exactly = 0) { repository.updateChapterBeatsUserEdited(any(), any(), any()) }
        coVerify(exactly = 0) { repository.commitUserChoice(any(), any(), any(), any(), any(), any()) }
    }

    // ── E6 清空归 null ──

    /** E6：节拍清空保存 → 存 **null 而不是空串**，标志仍置 true（= 「留白也是指定」·本章自由发挥）。 */
    @Test
    fun E6_清空保存_存null且标志仍为true() {
        val viewModel = vm().activate()

        viewModel.saveChapterBeats("   \n  ")
        idle()

        coVerify(exactly = 1) { repository.updateChapterBeatsUserEdited("s1", null, true) }
    }

    /** 前后空白照 trim（与「本章小结」同口径），中间的换行属正文不动。 */
    @Test
    fun 节拍保存_前后空白trim() {
        val viewModel = vm().activate()

        viewModel.saveChapterBeats("  第一拍：试探\n第二拍：靠近  ")
        idle()

        coVerify(exactly = 1) { repository.updateChapterBeatsUserEdited("s1", "第一拍：试探\n第二拍：靠近", true) }
    }

    // ── E7 恢复 AI 预排 ──

    /** E7：「恢复 AI 预排」= 只把标志复位 false，**节拍文本原样回写**（一个字不动）。 */
    @Test
    fun E7_恢复AI预排_只复位标志_文本不变() {
        every { repository.observeStory("s1") } returns flowOf(story(edited = true))
        val viewModel = vm().activate()

        viewModel.restoreAiBeats()
        idle()

        coVerify(exactly = 1) { repository.updateChapterBeatsUserEdited("s1", aiBeats, false) }
    }

    /** E7 边角：AI 从没预排过（beats 为 null）时恢复 = 写回 null + 标志 false，不凭空造文本。 */
    @Test
    fun E7_无AI预排时恢复_写回null不造文本() {
        every { repository.observeStory("s1") } returns flowOf(story(beats = null, edited = true))
        val viewModel = vm().activate()

        viewModel.restoreAiBeats()
        idle()

        coVerify(exactly = 1) { repository.updateChapterBeatsUserEdited("s1", null, false) }
    }

    // ── E9 反悔窗守卫 ──

    /**
     * E9：走向栏用的是既有 `submitChoice` 的守卫，**本卷不新增守卫**——
     * 章上已有 userChoice 时第二次提交被原样挡下（不进窗、不覆盖已选），而节拍栏照常可写（两栏正交）。
     */
    @Test
    fun E9_已答选择的章_走向被既有守卫挡下_节拍照常可写() {
        coEvery { repository.getChapter("ch1") } returns chapter.copy(userChoice = "已选A")
        coEvery { repository.getChapterMetas("s1") } returns listOf(chapter.copy(userChoice = "已选A"))
        val viewModel = vm().activate()

        viewModel.submitChoice("再改一次走向")
        viewModel.saveChapterBeats("节拍照改")
        idle()

        assertEquals("已答章不许再进反悔窗", false, viewModel.pendingActive.value)
        assertEquals("已选文本不被覆盖", "已选A", viewModel.selectedChoiceText.value)
        coVerify(exactly = 1) { repository.updateChapterBeatsUserEdited("s1", "节拍照改", true) }
    }

    // ── 失败路与反向钉 ──

    /** 节拍落库失败 → 不可重试错误（重试键只属生成失败路），不触发生成。 */
    @Test
    fun 节拍落库失败_报不可重试错误() {
        coEvery { repository.updateChapterBeatsUserEdited(any(), any(), any()) } throws IllegalStateException("写盘失败")
        val viewModel = vm().activate()
        jobs += scope.launch { viewModel.error.collect {} }

        viewModel.saveChapterBeats("随便什么")
        await("错误已置") { viewModel.error.value != null }

        assertEquals("写盘失败", viewModel.error.value?.message)
        assertEquals(false, viewModel.error.value?.retryable)
        coVerify(exactly = 0) { taskManager.startGeneration(any()) }
    }

    /**
     * 反向钉（J2 机制锁）：节拍写**不许**顺手触发生成、不许走整行 @Update、不许碰叙事状态整体写口——
     * 它就是一条列级 UPDATE，下一次生成时自然被读走。
     */
    @Test
    fun 节拍写不触发生成也不碰整行更新() {
        val viewModel = vm().activate()

        viewModel.saveChapterBeats("只改节拍")
        idle()

        coVerify(exactly = 0) { taskManager.startGeneration(any()) }
        coVerify(exactly = 0) { repository.updateStory(any()) }
        coVerify(exactly = 0) { repository.updateChapter(any()) }
    }
}
