package com.situ.aichat.ui.story

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.StoryChapterEntity
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.repository.StoryRepository
import com.situ.aichat.story.StoryArchiver
import com.situ.aichat.story.StoryChoiceClassifier
import com.situ.aichat.story.StoryEndingType
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 完结全链行为群 T2（图纸 §7 T2-3·用户点名「结束的机制要保证正常」）——阅读器 VM 这一侧。
 *
 * 覆盖：定收尾计划写列 + 清大纲（E4 生成中守卫）/ 取消收尾清列（E8·E9）/ 终章弧倒数期间答选择·自然发展·
 * 重写**绝不误清** finale 两列（E5·J8）/「立即结局」老路一字未改（回归）。
 * 服务侧的末章转正见 [com.situ.aichat.story.StoryFinaleArcServiceTest]。
 *
 * 手法照 [StoryReaderViewModelTest]：MockK 假掉依赖，viewModelScope 由 Robolectric 主循环驱动。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoryFinaleFlowTest {

    private val repository = mockk<StoryRepository>(relaxed = true)
    private val generationService = mockk<StoryGenerationService>(relaxed = true)
    private val taskManager = mockk<StoryGenerationTaskManager>(relaxed = true)
    private val readingProgressStore = mockk<StoryReadingProgressStore>(relaxed = true)

    private val activeGenerations = MutableStateFlow<Map<String, StoryGenerationTaskManager.GenerationProgress>>(emptyMap())

    private val scope = CoroutineScope(Dispatchers.Main)
    private val jobs = mutableListOf<Job>()

    /** 连载中的书：已定下收尾计划（终章弧倒数中），大纲已就位。 */
    private fun story(
        status: String = StoryStatus.SERIALIZING,
        finaleEndingType: String? = StoryEndingType.AI,
    ) = StoryEntity(
        id = "s1",
        status = status,
        storyOutline = "本弧预计章数：4\n弧线主题：收网",
        currentArcStartChapter = 40,
        finaleEndingType = finaleEndingType,
        cachedChapterCount = 1,
        cachedLatestChapterNumber = 41,
    )

    private fun chapter(userChoice: String? = null, hasChoice: Boolean = true) = StoryChapterEntity(
        id = "ch1",
        storyId = "s1",
        chapterNumber = 41,
        hasChoice = hasChoice,
        choiceOptions = """["A","B"]""",
        userChoice = userChoice,
        content = "正文",
    )

    /** 「生成真的起来了」的正向证据（await 的等待点落在协程最后一步·PITFALLS §1e）。 */
    private var generationStarted = false

    @Before
    fun setUp() {
        every { taskManager.activeGenerations } returns activeGenerations
        every { taskManager.lastErrors } returns MutableStateFlow(emptyMap())
        every { taskManager.startGeneration(any()) } answers { generationStarted = true }
        coEvery { repository.getRoles("s1") } returns emptyList()
    }

    @After
    fun tearDown() = scope.cancel()

    private fun give(s: StoryEntity, ch: StoryChapterEntity = chapter()) {
        every { repository.observeStory("s1") } returns flowOf(s)
        coEvery { repository.getStory("s1") } returns s
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

    private fun collectToasts(viewModel: StoryReaderViewModel, into: MutableList<Int>) {
        jobs += scope.launch { viewModel.toastEvents.collect { into += it } }
        idle()
    }

    private fun StoryReaderViewModel.activate() = also {
        jobs += scope.launch { story.collect {} }
        jobs += scope.launch { chapters.collect {} }
        jobs += scope.launch { currentChapter.collect {} }
        await("story/chapters 就绪") { story.value != null && chapters.value.isNotEmpty() }
    }

    // ── 计划：写 finale 两列 + 清大纲（同一条 UPDATE）──

    @Test
    fun 定收尾计划_写两列并清大纲_提示已定() {
        give(story(finaleEndingType = null))
        val toasts = mutableListOf<Int>()
        val vm = vm().activate()
        collectToasts(vm, toasts)

        vm.planFinale(StoryEndingType.AI, detail = null)
        await("收尾计划写库") { toasts.isNotEmpty() }

        coVerify(exactly = 1) {
            repository.updateFinalePlanStartingNewArc("s1", StoryEndingType.AI, null, any())
        }
        assertEquals(listOf(R.string.story_finale_planned_toast), toasts)
        // 计划本身**不触发生成**：用户下一次推进动作才起终章弧的第一章。
        coVerify(exactly = 0) { taskManager.startGeneration(any()) }
    }

    @Test
    fun 定收尾计划_非custom类型不带方向detail() {
        give(story(finaleEndingType = null))
        val toasts = mutableListOf<Int>()
        val vm = vm().activate()
        collectToasts(vm, toasts)

        vm.planFinale(StoryEndingType.OPEN, detail = "这段方向应被丢弃")
        await("收尾计划写库") { toasts.isNotEmpty() }

        coVerify(exactly = 1) { repository.updateFinalePlanStartingNewArc("s1", StoryEndingType.OPEN, null, any()) }
    }

    @Test
    fun 定收尾计划_custom类型带方向detail() {
        give(story(finaleEndingType = null))
        val toasts = mutableListOf<Int>()
        val vm = vm().activate()
        collectToasts(vm, toasts)

        vm.planFinale(StoryEndingType.CUSTOM, detail = "两个人在海边和解")
        await("收尾计划写库") { toasts.isNotEmpty() }

        coVerify(exactly = 1) {
            repository.updateFinalePlanStartingNewArc("s1", StoryEndingType.CUSTOM, "两个人在海边和解", any())
        }
    }

    // ── E4 生成中守卫（两个方向都设防）──

    @Test
    fun 生成中_拒绝定收尾计划并提示稍后再试() {
        give(story(status = StoryStatus.GENERATING, finaleEndingType = null))
        val toasts = mutableListOf<Int>()
        val vm = vm().activate()
        collectToasts(vm, toasts)

        vm.planFinale(StoryEndingType.AI, null)
        await("守卫提示") { toasts.isNotEmpty() }

        coVerify(exactly = 0) { repository.updateFinalePlanStartingNewArc(any(), any(), any(), any()) }
        assertEquals(listOf(R.string.story_archive_busy_toast), toasts)
    }

    @Test
    fun 任务活跃时_同样拒绝定收尾计划() {
        give(story(finaleEndingType = null))  // 状态不是 generating，但任务管理器里有活跃任务
        activeGenerations.value = mapOf("s1" to mockk(relaxed = true))
        val toasts = mutableListOf<Int>()
        val vm = vm().activate()
        collectToasts(vm, toasts)

        vm.planFinale(StoryEndingType.AI, null)
        await("守卫提示") { toasts.isNotEmpty() }

        coVerify(exactly = 0) { repository.updateFinalePlanStartingNewArc(any(), any(), any(), any()) }
        assertEquals(listOf(R.string.story_archive_busy_toast), toasts)
    }

    @Test
    fun 生成中_拒绝取消收尾计划() {
        give(story(status = StoryStatus.GENERATING))
        val toasts = mutableListOf<Int>()
        val vm = vm().activate()
        collectToasts(vm, toasts)

        vm.cancelFinale()
        await("守卫提示") { toasts.isNotEmpty() }

        coVerify(exactly = 0) { repository.clearFinalePlanAndOutline(any(), any()) }
        assertEquals(listOf(R.string.story_archive_busy_toast), toasts)
    }

    // ── E8/E9 取消 ──

    @Test
    fun 取消收尾计划_清列并提示_不触发生成() {
        give(story())
        val toasts = mutableListOf<Int>()
        val vm = vm().activate()
        collectToasts(vm, toasts)

        vm.cancelFinale()
        await("取消写库") { toasts.isNotEmpty() }

        coVerify(exactly = 1) { repository.clearFinalePlanAndOutline("s1", any()) }
        assertEquals(listOf(R.string.story_finale_cancelled_toast), toasts)
        coVerify(exactly = 0) { taskManager.startGeneration(any()) }
    }

    // ── E5 / J8：倒数期间的三条推进动作绝不误清 finale 两列 ──

    /**
     * ST11 的「意图覆盖」只清 requestedEnding 两列（[StoryRepository.clearEndingRequest]），**finale 两列不在其中**。
     * 三条注入点各验一遍：答选择 / 让故事自然发展 / 重写末章——都不许调到清收尾计划的那条 UPDATE。
     */
    @Test
    fun 终章弧倒数中_让故事自然发展_不清收尾计划() {
        give(story(), chapter(hasChoice = false))
        val vm = vm().activate()

        vm.forceContinue()
        await("推进动作走到起生成那一步") { generationStarted }

        coVerify(exactly = 0) { repository.clearFinalePlanAndOutline(any(), any()) }
        coVerify(exactly = 0) { repository.updateFinalePlanStartingNewArc(any(), any(), any(), any()) }
        // 旧「写结局」意图仍照 ST11 覆盖（这条是既有行为，须原样保留）
        coVerify(exactly = 1) { repository.clearEndingRequest("s1", any()) }
    }

    @Test
    fun 终章弧倒数中_重写末章_不清收尾计划() {
        give(story(), chapter(hasChoice = false))
        val vm = vm().activate()

        vm.rewrite(instruction = null)
        await("重写走到起生成那一步") { generationStarted }

        coVerify(exactly = 0) { repository.clearFinalePlanAndOutline(any(), any()) }
        coVerify(exactly = 1) { repository.clearEndingRequest("s1", any()) }
    }

    @Test
    fun 终章弧倒数中_跳过选择请求立即结局_老路一字未改() {
        // 「立即结局」= 原 requestEnding 路：跳过未答选择 + 写 requestedEnding + 起生成，全程不碰 finale 两列。
        give(story())
        val vm = vm().activate()

        vm.requestEnding(StoryEndingType.OPEN, null, skipPendingChoice = true)
        await("结局请求走到起生成那一步") { generationStarted }

        coVerify(exactly = 1) {
            repository.commitUserChoice("s1", "ch1", StoryChoiceClassifier.SKIP_FOR_ENDING_CHOICE, any(), false, any())
        }
        coVerify(exactly = 1) {
            repository.updateEndingRequest("s1", StoryEndingType.OPEN, null, StoryStatus.SERIALIZING, any())
        }
        coVerify(exactly = 0) { repository.updateFinalePlanStartingNewArc(any(), any(), any(), any()) }
        coVerify(exactly = 0) { repository.clearFinalePlanAndOutline(any(), any()) }
    }

    @Test
    fun 已完结的书_归档路不碰收尾计划两列() {
        give(story(status = StoryStatus.COMPLETED))
        val vm = vm().activate()

        vm.finishStory()
        idle()

        // 已完结 → archiver 幂等静默；无论如何都不许动 finale 两列。
        coVerify(exactly = 0) { repository.clearFinalePlanAndOutline(any(), any()) }
        coVerify(exactly = 0) { repository.updateFinalePlanStartingNewArc(any(), any(), any(), any()) }
    }
}
