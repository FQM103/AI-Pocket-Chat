package com.situ.aichat.ui.story

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.CustomStoryPrompts
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.ApiFunctionRouter
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.data.repository.StoryRepository
import com.situ.aichat.story.StoryEndingType
import com.situ.aichat.story.StoryGenPhase
import com.situ.aichat.story.StoryGenerationService
import com.situ.aichat.story.StoryGenerationTaskManager
import com.situ.aichat.story.StoryOutlineOrchestrator
import com.situ.aichat.story.StoryReadingProgressStore
import com.situ.aichat.story.StoryStatus
import com.situ.aichat.story.StoryWorldInfoService
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 故事设定 VM T2（ST7c·设置页两处即时列写）：世界观开关直写、自定义提示词事后编辑（trim 后空→null·全空清 JSON）
 * 与节奏偏好 merge 保存（卷三 C1·第四字段单改·三旧字段原样留）。
 * 温度/段序/全局忌口的写口与思考旗标已随卷四迁家：前三者归 `StoryGlobalSettingsViewModel` 与统一编辑页全局分支，
 * 谓词测试见 `StoryGlobalSettingsViewModelTest`（本文件对应七例随之退役·R1 复核 D-9 清理）。
 * MockK 假仓库/服务；viewModelScope 由 Robolectric 主循环驱动（照 StoryCreationViewModelTest 先例）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StorySettingsViewModelTest {

    private val repo = mockk<StoryRepository>()
    private val generationService = mockk<StoryGenerationService>()
    private val taskManager = mockk<StoryGenerationTaskManager>(relaxed = true)
    private val worldInfoService = mockk<StoryWorldInfoService>()
    private val readingProgressStore = mockk<StoryReadingProgressStore>(relaxed = true)
    private val functionRouter = mockk<ApiFunctionRouter>()
    private val apiConfigs = mockk<ApiConfigRepository>()
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)
    private val orchestrator = mockk<StoryOutlineOrchestrator>()

    private fun vm(): StorySettingsViewModel {
        every { repo.observeStory(any()) } returns flowOf(null)
        every { settingsRepository.appSettings } returns flowOf(AppSettings())
        every { functionRouter.assignments } returns flowOf(emptyMap())
        every { apiConfigs.observeAll() } returns flowOf(emptyList())
        every { apiConfigs.observeActive() } returns flowOf(null)
        return StorySettingsViewModel(
            SavedStateHandle(mapOf("storyId" to "s1")),
            repo,
            generationService,
            taskManager,
            worldInfoService,
            readingProgressStore,
            mockk(relaxed = true), // StoryArchiver（本组用例不碰归档）
            mockk(relaxed = true), // StoryDeleter（本组用例不碰删除）
            mockk(relaxed = true), // StoryUnlockNotificationScheduler（本组用例不碰闹钟）
            mockk(relaxed = true), // StoryPersonaDrafter（本组用例不碰起草）
            orchestrator,
            functionRouter,
            apiConfigs,
            settingsRepository,
            mockk(relaxed = true),
        )
    }

    private fun await(message: String, condition: () -> Boolean) {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        error("等待超时：$message")
    }

    @Test
    fun 自定义提示词_trim后空字段归null_编码非空写库() {
        coEvery { repo.getStory("s1") } returns StoryEntity(id = "s1", customPromptsJson = null)
        var captured: String? = SENTINEL
        coEvery { repo.updateCustomPrompts(any(), any()) } answers { captured = secondArg(); Unit }

        vm().saveCustomPrompts(
            genreTechniques = "  修仙核心技法  ", writerIdentity = "", writingRules = "   ", bannedExpressions = "  少写雨  ",
        )
        await("写库") { captured != SENTINEL }

        val decoded = CustomStoryPrompts.decode(captured)!!
        assertEquals("trim 后落库", "修仙核心技法", decoded.genreTechniques)
        assertNull("空串归 null", decoded.writerIdentity)
        assertNull("纯空白归 null", decoded.writingRules)
        assertEquals("忌口同样 trim 后落库", "少写雨", decoded.bannedExpressions)
    }

    @Test
    fun 自定义提示词_四字段全空_清JSON为null() {
        coEvery { repo.getStory("s1") } returns StoryEntity(id = "s1", customPromptsJson = null)
        var captured: String? = SENTINEL
        coEvery { repo.updateCustomPrompts(any(), any()) } answers { captured = secondArg(); Unit }

        vm().saveCustomPrompts("", "  ", "", "")
        await("写库") { captured != SENTINEL }

        assertNull("全空 → 清 JSON 走预设默认", captured)
    }

    // ── 文字忌口（2026-07-30）：E9 丢字段真 bug 的防复发钉 + E11 + 全局 setter ──

    @Test
    fun 自定义提示词_保存不再清掉用户已填的节奏偏好() {
        // E9 回归（修复前必红）：旧实现直接新构造三字段对象，把不在本 sheet 里的 pacingPreference 写成 null——
        // 用户「填节奏偏好 → 再存一次自定义提示词 → 偏好没了」。现在走 copy 合并，第五字段也一并保留。
        val existing = CustomStoryPrompts.encode(
            CustomStoryPrompts(writerIdentity = "旧身份", pacingPreference = "慢热，多写日常"),
        )
        coEvery { repo.getStory("s1") } returns StoryEntity(id = "s1", customPromptsJson = existing)
        var captured: String? = SENTINEL
        coEvery { repo.updateCustomPrompts(any(), any()) } answers { captured = secondArg(); Unit }

        vm().saveCustomPrompts("新技法", "新身份", "", "")
        await("写库") { captured != SENTINEL }

        val decoded = CustomStoryPrompts.decode(captured)!!
        assertEquals("节奏偏好必须原样保留", "慢热，多写日常", decoded.pacingPreference)
        assertEquals("本 sheet 的字段照常更新", "新技法", decoded.genreTechniques)
        assertEquals("新身份", decoded.writerIdentity)
    }

    @Test
    fun 自定义提示词_四字段全空但有节奏偏好_JSON保留不清空() {
        // E11 另一半：清空的是本 sheet 的四栏，不是整份 customPrompts——节奏偏好还在就不许清 JSON。
        coEvery { repo.getStory("s1") } returns
            StoryEntity(id = "s1", customPromptsJson = CustomStoryPrompts.encode(CustomStoryPrompts(pacingPreference = "快节奏")))
        var captured: String? = SENTINEL
        coEvery { repo.updateCustomPrompts(any(), any()) } answers { captured = secondArg(); Unit }

        vm().saveCustomPrompts("", "", "", "")
        await("写库") { captured != SENTINEL }

        assertEquals("快节奏", CustomStoryPrompts.decode(captured)!!.pacingPreference)
    }

    @Test
    fun 世界观开关_直接列写() {
        coEvery { repo.setWorldInfoEnabled("s1", false) } just Runs
        val vm = vm()
        vm.setWorldInfoEnabled(false)
        await("开关写库") { runCatching { coVerify { repo.setWorldInfoEnabled("s1", false) } }.isSuccess }
        coVerify(exactly = 1) { repo.setWorldInfoEnabled("s1", false) }
    }

    // ── 卷三 V2：节奏偏好 merge 保存 ──

    @Test
    fun 节奏偏好_merge进现有JSON_三旧字段原样保留() {
        val existing = CustomStoryPrompts.encode(
            CustomStoryPrompts(genreTechniques = "技法", writerIdentity = "身份", writingRules = "规则"),
        )
        coEvery { repo.getStory("s1") } returns StoryEntity(id = "s1", customPromptsJson = existing)
        var captured: String? = SENTINEL
        coEvery { repo.updateCustomPrompts(any(), any()) } answers { captured = secondArg(); Unit }

        vm().savePacing("  慢热，多写日常  ")
        await("写库") { captured != SENTINEL }

        val decoded = CustomStoryPrompts.decode(captured)!!
        assertEquals("trim 后落第四字段", "慢热，多写日常", decoded.pacingPreference)
        assertEquals("旧字段不动", "技法", decoded.genreTechniques)
        assertEquals("旧字段不动", "身份", decoded.writerIdentity)
        assertEquals("旧字段不动", "规则", decoded.writingRules)
    }

    @Test
    fun 节奏偏好_超300字截断_预设题材空JSON也能落() {
        coEvery { repo.getStory("s1") } returns StoryEntity(id = "s1", customPromptsJson = null)
        var captured: String? = SENTINEL
        coEvery { repo.updateCustomPrompts(any(), any()) } answers { captured = secondArg(); Unit }

        // 故事二期 D-8：上限 100 → 300（380 字截成 300）
        vm().savePacing("节".repeat(380))
        await("写库") { captured != SENTINEL }

        val decoded = CustomStoryPrompts.decode(captured)!!
        assertEquals("钳到 300 字", 300, decoded.pacingPreference!!.length)
        assertNull("预设题材故事只带第四字段", decoded.genreTechniques)
    }

    @Test
    fun 节奏偏好_清空且无其他字段_清JSON为null() {
        coEvery { repo.getStory("s1") } returns
            StoryEntity(id = "s1", customPromptsJson = CustomStoryPrompts.encode(CustomStoryPrompts(pacingPreference = "旧值")))
        var captured: String? = SENTINEL
        coEvery { repo.updateCustomPrompts(any(), any()) } answers { captured = secondArg(); Unit }

        vm().savePacing("   ")
        await("写库") { captured != SENTINEL }

        assertNull("四字段全空 → 清 JSON", captured)
    }

    // ── T2-7 弧线大纲手动重排（图纸 2026-08-05 §3.4·E14–E19）──

    /** 纯轮询等待（不 idle 主循环）：被等的协程跑在 [Dispatchers.Default] 上，与主 Looper 无关。 */
    private fun awaitBlocking(message: String, condition: () -> Boolean) {
        repeat(400) {
            if (condition()) return
            Thread.sleep(5)
        }
        error("等待超时：$message")
    }

    /** 重排四例共用的桩：故事 fresh 读 + 任务表空 + toast 收集。 */
    private fun regenFixture(
        story: StoryEntity,
        busyStoryIds: List<String> = emptyList(),
    ): Pair<StorySettingsViewModel, MutableList<Int>> {
        coEvery { repo.getStory("s1") } returns story
        every { taskManager.activeGenerations } returns MutableStateFlow(
            busyStoryIds.associateWith {
                StoryGenerationTaskManager.GenerationProgress(
                    progress = 0.3, genPhase = StoryGenPhase.WRITING, phase = "撰写中",
                    storyTitle = "书", chapterNumber = 10,
                )
            },
        )
        val vm = vm()
        val toasts = mutableListOf<Int>()
        CoroutineScope(Dispatchers.Unconfined).launch { vm.toastEvents.collect { toasts += it } }
        return vm to toasts
    }

    @Test
    fun 重排_生成中守卫_不发调用只弹忙碌() { // E14
        val (vm, toasts) = regenFixture(
            StoryEntity(id = "s1", status = StoryStatus.GENERATING, cachedLatestChapterNumber = 9),
        )
        runBlocking { vm.regenerateOutline() }

        assertEquals(listOf(R.string.story_archive_busy_toast), toasts)
        coVerify(exactly = 0) { orchestrator.regenerateArc(any(), any(), any(), any()) }
        assertEquals("守卫返回后执行态必须已复位", false, vm.regenerating.value)
    }

    @Test
    fun 重排_任务表里有本书也算生成中() { // E14 第二路
        val (vm, toasts) = regenFixture(
            StoryEntity(id = "s1", cachedLatestChapterNumber = 9),
            busyStoryIds = listOf("s1"),
        )
        runBlocking { vm.regenerateOutline() }

        assertEquals(listOf(R.string.story_archive_busy_toast), toasts)
        coVerify(exactly = 0) { orchestrator.regenerateArc(any(), any(), any(), any()) }
    }

    @Test
    fun 重排_成功_章号取最新加一并弹成功提示() { // E16
        val fresh = StoryEntity(id = "s1", storyOutline = "旧大纲", cachedLatestChapterNumber = 9)
        val (vm, toasts) = regenFixture(fresh)
        coEvery { orchestrator.regenerateArc(any(), any(), any(), any()) } returns fresh.copy(storyOutline = "新大纲")

        runBlocking { vm.regenerateOutline() }

        coVerify(exactly = 1) { orchestrator.regenerateArc(fresh, 10, any(), any()) }
        assertEquals(listOf(R.string.story_outline_regen_done), toasts)
        assertEquals(false, vm.regenerating.value)
    }

    @Test
    fun 重排_原样返回视为失败_逐字相同也算() { // E15
        val fresh = StoryEntity(id = "s1", storyOutline = "旧大纲", cachedLatestChapterNumber = 3)
        val (vm, toasts) = regenFixture(fresh)
        // orchestrator 生成失败/截断/与旧大纲逐字相同 → 原样返回入参
        coEvery { orchestrator.regenerateArc(any(), any(), any(), any()) } returns fresh

        runBlocking { vm.regenerateOutline() }

        assertEquals(listOf(R.string.story_outline_regen_failed), toasts)
        assertEquals(false, vm.regenerating.value)
    }

    @Test
    fun 重排_执行中重入短路_只发一次调用() { // E18
        val fresh = StoryEntity(id = "s1", storyOutline = "旧大纲", cachedLatestChapterNumber = 5)
        val (vm, _) = regenFixture(fresh)
        val gate = CompletableDeferred<Unit>()
        coEvery { orchestrator.regenerateArc(any(), any(), any(), any()) } coAnswers {
            gate.await()
            fresh.copy(storyOutline = "新大纲")
        }

        // 首次调用放到真后台线程：本测试的等待点是 `regenerating` 真的置位（runBlocking 单线程会把它饿死）
        val first = CoroutineScope(Dispatchers.Default).launch { vm.regenerateOutline() }
        awaitBlocking("首次进入执行态") { vm.regenerating.value }
        runBlocking { vm.regenerateOutline() } // 第二次点：应被 _regenerating 短路，立即返回
        gate.complete(Unit)
        runBlocking { first.join() }

        coVerify(exactly = 1) { orchestrator.regenerateArc(any(), any(), any(), any()) }
        assertEquals(false, vm.regenerating.value)
    }

    @Test
    fun 重排_终章弧期间照样重排_收尾计划不丢() { // E19
        // isFinale 的分派在 orchestrator 内部按 finaleEndingType 判（VM 不传旗标）——这里钉「原样把带
        // finaleEndingType 的 fresh 快照交下去」，分派前提不被 VM 抹掉。
        val fresh = StoryEntity(
            id = "s1", storyOutline = "旧大纲", cachedLatestChapterNumber = 7, finaleEndingType = StoryEndingType.AI,
        )
        val (vm, toasts) = regenFixture(fresh)
        val handed = slot<StoryEntity>()
        coEvery { orchestrator.regenerateArc(capture(handed), any(), any(), any()) } returns
            fresh.copy(storyOutline = "终章弧大纲")

        runBlocking { vm.regenerateOutline() }

        assertEquals("收尾计划必须原样交给编排层", StoryEndingType.AI, handed.captured.finaleEndingType)
        assertEquals(listOf(R.string.story_outline_regen_done), toasts)
    }

    private companion object {
        /** 「尚未捕获」哨兵（真值可能是 null，不能用 null 判定是否已写库）。 */
        const val SENTINEL = "__uncaptured__"
    }
}
