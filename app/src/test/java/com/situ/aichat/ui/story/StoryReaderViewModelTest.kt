package com.situ.aichat.ui.story

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import com.situ.aichat.data.local.entity.StoryChapterEntity
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.repository.StoryRepository
import com.situ.aichat.R
import com.situ.aichat.story.StoryArchiver
import com.situ.aichat.story.StoryChoiceClassifier
import com.situ.aichat.story.StoryChoiceCountdown
import com.situ.aichat.story.StoryEndingType
import com.situ.aichat.story.StoryGenerationService
import com.situ.aichat.story.StoryGenerationTaskManager
import com.situ.aichat.story.StoryReadingProgressStore
import com.situ.aichat.story.StoryStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
 * 阅读器 VM T2（ST7d/J2·撤销条状态流）：验证「选中即时进入约 4s 反悔窗口、期间不落库、可撤销、边界忽略」。
 *
 * 覆盖底部撤销条读取的**可观察状态**（[StoryReaderViewModel.selectedChoiceText]/[pendingActive]/[pendingRemainingSeconds]）。
 * 「窗口到点→落库」这一步在 VM 内以 `System.currentTimeMillis()` 挂钟驱动（本次仅 UI 呈现改型·VM 逻辑复用不改），
 * 非虚拟时间可测；其「何时到点」的纯判定由 `StoryChoiceCountdownTest`（T1·4s）看门。
 * 依赖 MockK 假掉；viewModelScope 由 Robolectric 主循环驱动（照 StoryCreationViewModelTest 先例）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoryReaderViewModelTest {

    private val repository = mockk<StoryRepository>()
    private val generationService = mockk<StoryGenerationService>(relaxed = true)
    private val taskManager = mockk<StoryGenerationTaskManager>(relaxed = true)
    private val readingProgressStore = mockk<StoryReadingProgressStore>(relaxed = true)

    private val story = StoryEntity(id = "s1", cachedChapterCount = 1)

    private val scope = CoroutineScope(Dispatchers.Main)
    private val jobs = mutableListOf<Job>()

    private fun chapter(userChoice: String? = null) = StoryChapterEntity(
        id = "ch1",
        storyId = "s1",
        chapterNumber = 1,
        hasChoice = true,
        choiceOptions = """["A","B"]""",
        userChoice = userChoice,
        content = "正文",
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
        // 卷二 §3.2：列表流换元数据投影查询（currentChapter 另走 getChapter 全列单查·上面那条桩）；
        // 「跳最新章」（生成完成 / 重写后）换单行查询，桩返回原 lastOrNull 那一章。
        coEvery { repository.getChapterMetas("s1") } returns listOf(ch)
        coEvery { repository.getLatestChapterMeta("s1") } returns ch
    }

    private fun vm(): StoryReaderViewModel = StoryReaderViewModel(
        SavedStateHandle(mapOf("chapterId" to "ch1")),
        repository,
        generationService,
        taskManager,
        readingProgressStore,
        // 真 Archiver（吃同一批 mock）：finishStory 的守卫三态是共用实现，不假掉才测得到真语义。
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

    /** 订阅一次性 toast 事件（SharedFlow replay=0：必须先 idle 让收集者真订阅上，否则 tryEmit 直接丢·照书架 VM 测同款）。 */
    private fun collectToasts(viewModel: StoryReaderViewModel, into: MutableList<Int>) {
        jobs += scope.launch { viewModel.toastEvents.collect { into += it } }
        idle()
    }

    /** 订阅 currentChapter（WhileSubscribed 冷流·无订阅者恒空）→ 等章节就绪，模拟阅读屏的订阅。 */
    private fun StoryReaderViewModel.activate() = also {
        jobs += scope.launch { currentChapter.collect {} }
        await("currentChapter 就绪") { currentChapter.value?.id == "ch1" }
    }

    @Test
    fun 提交选择_进入约4秒反悔窗口_不立即落库() {
        giveChapter(chapter())
        val vm = vm().activate()

        vm.submitChoice("A")

        assertEquals("A", vm.selectedChoiceText.value)
        assertTrue("应进入反悔窗口", vm.pendingActive.value)
        assertEquals(StoryChoiceCountdown.WINDOW_SECONDS, vm.pendingRemainingSeconds.value)
        // 窗口内还没到点 → 绝不落库（撤销条可反悔的前提）。
        coVerify(exactly = 0) { repository.commitUserChoice(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun 撤销_清空反悔窗口与已选() {
        giveChapter(chapter())
        val vm = vm().activate()

        vm.submitChoice("A")
        vm.cancelPendingChoice()

        assertFalse("撤销后应退出反悔窗口", vm.pendingActive.value)
        assertNull("撤销后已选应清空", vm.selectedChoiceText.value)
        coVerify(exactly = 0) { repository.commitUserChoice(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun 空白选择被忽略() {
        giveChapter(chapter())
        val vm = vm().activate()

        vm.submitChoice("   ")

        assertFalse(vm.pendingActive.value)
        assertNull(vm.selectedChoiceText.value)
    }

    @Test
    fun 已落库章节再选被忽略() {
        giveChapter(chapter(userChoice = "X"))
        val vm = vm().activate()
        await("已落库选择同步到状态") { vm.selectedChoiceText.value == "X" }

        vm.submitChoice("A")

        assertFalse("已落库章节不应再进入反悔窗口", vm.pendingActive.value)
        assertEquals("X", vm.selectedChoiceText.value)
    }

    // ── requestEnding 延迟跳过提交（ST10-4·微图纸 2026-07-17）──
    // 期望独立反推自缺陷：旧流「先斩后奏」（skip 即时写库 + 结局另协程），三选取消 → 选择被吞的幽灵态。

    /** story/chapters 均 WhileSubscribed 冷流——requestEnding 读它们的 .value，须显式订阅驱动。 */
    private fun StoryReaderViewModel.activateStoryAndChapters() = also {
        jobs += scope.launch { story.collect {} }
        jobs += scope.launch { chapters.collect {} }
        await("story/chapters 就绪") { story.value != null && chapters.value.isNotEmpty() }
    }

    @Test
    fun 请求结局_带跳过_同协程先跳过后结局双写() {
        giveChapter(chapter())  // 最新章未答选择
        coEvery { repository.commitUserChoice(any(), any(), any(), any(), any(), any()) } returns Unit
        var endingWritten = false
        coEvery { repository.updateEndingRequest(any(), any(), any(), any(), any()) } answers { endingWritten = true }
        coEvery { repository.getStory("s1") } returns story
        val vm = vm().activateStoryAndChapters()

        vm.requestEnding(StoryEndingType.AI, null, skipPendingChoice = true)
        await("结局请求写库") { endingWritten }

        coVerifyOrder {
            repository.commitUserChoice("s1", "ch1", StoryChoiceClassifier.SKIP_FOR_ENDING_CHOICE, any(), false, any())
            repository.updateEndingRequest("s1", StoryEndingType.AI, null, StoryStatus.SERIALIZING, any())
        }
        coVerify(exactly = 1) { taskManager.startGeneration(any()) }
    }

    @Test
    fun 请求结局_不带跳过_零跳过写库() {
        giveChapter(chapter())
        var endingWritten = false
        coEvery { repository.updateEndingRequest(any(), any(), any(), any(), any()) } answers { endingWritten = true }
        coEvery { repository.getStory("s1") } returns story
        val vm = vm().activateStoryAndChapters()

        vm.requestEnding(StoryEndingType.OPEN, null)
        await("结局请求写库") { endingWritten }

        coVerify(exactly = 0) { repository.commitUserChoice(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun 请求结局_带跳过但选择已答_不重复跳过() {
        giveChapter(chapter(userChoice = "X"))  // 已答 → takeIf 守卫拦下
        var endingWritten = false
        coEvery { repository.updateEndingRequest(any(), any(), any(), any(), any()) } answers { endingWritten = true }
        coEvery { repository.getStory("s1") } returns story
        val vm = vm().activateStoryAndChapters()

        vm.requestEnding(StoryEndingType.CUSTOM, "圆满", skipPendingChoice = true)
        await("结局请求写库") { endingWritten }

        coVerify(exactly = 0) { repository.commitUserChoice(any(), any(), any(), any(), any(), any()) }
        coVerify { repository.updateEndingRequest("s1", StoryEndingType.CUSTOM, "圆满", StoryStatus.SERIALIZING, any()) }
    }

    // ── ST11 §3.3 意图覆盖（拍板③·注入点恰三处）+ E4 顺序锁 + E10 隔离 ──
    // 期望独立反推自图纸 §3.3/§5：失败残留的「写结局」意图，被用户的任一其它推进动作覆盖；
    // 且覆盖必须发生在 startGeneration **之前**，否则生成读到的旧快照仍会把这一章写成结局章。

    // 注入点 1/3（选择确认落库 → 清意图）在本层**无法断言**：它只能由反悔窗口到点触发，而窗口到点由
    // `System.currentTimeMillis()` 挂钟驱动（本类头部已载明「非虚拟时间可测」，故既有四例也只测窗口态、不测落库）。
    // 实测 Robolectric 暂停主循环的 `idleFor` 推不动该挂钟（commitUserChoice 恒不触发）→ 不留假绿测试。
    // 该点的覆盖去向：T4 装机 ④（DB 直插结局意图 → 失败态选一个选项 → DB 直读验意图已清）。见图纸 §11 D-4。

    /** 注入点 2/3 + **E4 顺序锁**：自然发展 → 先清意图、再起生成（顺序反了 = 白清）。 */
    @Test
    fun ST11_自然发展_清结局意图且必须排在起生成之前() {
        giveChapter(chapter(userChoice = "A"))  // 已答选择 → 走 forceContinue 的 else 分支
        val failedStory = story.copy(
            status = StoryStatus.GENERATION_FAILED,
            requestedEndingType = StoryEndingType.CUSTOM,
            requestedEndingDetail = "圆满",
        )
        every { repository.observeStory("s1") } returns flowOf(failedStory)
        var cleared = false
        coEvery { repository.clearEndingRequest(any(), any()) } answers { cleared = true }
        coEvery { repository.getStory("s1") } returns failedStory
        val vm = vm().activateStoryAndChapters()

        vm.forceContinue()
        await("结局意图被清") { cleared }

        coVerify(exactly = 1) { repository.clearEndingRequest("s1", any()) }
        // E4：clear 必须在 startGeneration 之前（生成吃的是 clear 之后重读的 fresh 快照）。
        coVerifyOrder {
            repository.clearEndingRequest("s1", any())
            repository.getStory("s1")
            taskManager.startGeneration(any())
        }
    }

    /** 注入点 3/3：重写末章 = 新动作 → 覆盖旧结局意图（prepareRewrite 自身不清，故此点非冗余）。 */
    @Test
    fun ST11_重写末章_清结局意图() {
        giveChapter(chapter(userChoice = "A"))
        val failedStory = story.copy(
            status = StoryStatus.GENERATION_FAILED,
            requestedEndingType = StoryEndingType.AI,
        )
        every { repository.observeStory("s1") } returns flowOf(failedStory)
        var cleared = false
        coEvery { repository.clearEndingRequest(any(), any()) } answers { cleared = true }
        coEvery { repository.getStory("s1") } returns failedStory
        val vm = vm().activateStoryAndChapters()

        vm.rewrite("换个写法")
        await("结局意图被清") { cleared }

        coVerify(exactly = 1) { repository.clearEndingRequest("s1", any()) }
        coVerifyOrder {
            generationService.prepareRewrite(any(), any(), any(), any())  // 主写库先成功
            repository.clearEndingRequest("s1", any())                    // 再覆盖意图
            taskManager.startGeneration(any())                            // 最后才起生成（E4）
        }
    }

    /**
     * 卷二 D-1 回归锁（复核 R1 补）：重写必须把「全列重读」的章喂给 prepareRewrite——[StoryReaderViewModel.chapters]
     * 已是元数据投影（content 恒空串），把列表行直接转手会让「上一版」槽把旧稿正文存成空串（用户「换回上一版」
     * 得到空白章，且只有真跑重写才看得见）。断言从图纸卷二 §0.2-6 反推：投影实体流出的动作链**入口重读全列**。
     * 谁把 rewrite 里那行 `repository.getChapter(latest.id)` 重读删掉，本例立刻红。
     */
    @Test
    fun 卷二D1_重写把全列重读的章喂给prepareRewrite_不转手投影行() {
        val projected = chapter().copy(content = "")            // 列表里的投影行：正文位恒空串
        coEvery { repository.getChapter("ch1") } returns chapter()   // 全列重读：拿到真正文
        coEvery { repository.getChapterMetas("s1") } returns listOf(projected)
        coEvery { repository.getLatestChapterMeta("s1") } returns projected
        coEvery { repository.clearEndingRequest(any(), any()) } returns Unit
        coEvery { repository.getStory("s1") } returns story
        var started = false
        every { taskManager.startGeneration(any()) } answers { started = true }
        val vm = vm().activateStoryAndChapters()

        vm.rewrite("换个写法")
        await("重写链跑完（末步 startGeneration）") { started }

        coVerify(exactly = 1) {
            generationService.prepareRewrite(any(), match { it.content == "正文" }, "换个写法", any())
        }
    }

    /**
     * **E10 隔离**：requestEnding 自身 = 重新表达意图（updateEndingRequest 整行覆盖），**一次 clear 都不许调**——
     * 它的 skip 分支走 repository.commitUserChoice 直调、不经 commitPendingChoice，天然不触发覆盖清除。
     * 若哪天有人把 clear 塞进 repository.commitUserChoice 内部，本例立刻变红（结局流会自己清掉自己刚写的意图）。
     */
    @Test
    fun ST11_请求结局路_零清除() {
        giveChapter(chapter())
        coEvery { repository.commitUserChoice(any(), any(), any(), any(), any(), any()) } returns Unit
        var endingWritten = false
        coEvery { repository.updateEndingRequest(any(), any(), any(), any(), any()) } answers { endingWritten = true }
        coEvery { repository.getStory("s1") } returns story
        val vm = vm().activateStoryAndChapters()

        vm.requestEnding(StoryEndingType.AI, null, skipPendingChoice = true)
        await("结局请求写库") { endingWritten }

        coVerify(exactly = 0) { repository.clearEndingRequest(any(), any()) }
    }

    // ── ST11 §3.5「就此完结」：走与书架同一个 StoryArchiver，三态各自映射 toast ──

    /** 建议卡「就此完结」→ 标记完结 + 成功 toast（书随响应式流变 COMPLETED，卡/推进区自然消失）。 */
    @Test
    fun ST11_就此完结_标记完结并发成功toast() {
        giveChapter(chapter(userChoice = "A"))
        coEvery { repository.getStory("s1") } returns story  // serializing
        coEvery { repository.updateStatus(any(), any(), any()) } returns Unit
        val vm = vm().activate()
        val toasts = mutableListOf<Int>()
        collectToasts(vm, toasts)

        vm.finishStory()
        await("完结 toast") { toasts.isNotEmpty() }

        coVerify(exactly = 1) { repository.updateStatus("s1", StoryStatus.COMPLETED, any()) }
        assertEquals(listOf(R.string.story_archived_toast), toasts)
    }

    /** 生成中点「就此完结」→ 拒绝 + 忙 toast + 零写库（与书架守卫同源，绝不与生成落库赛跑）。 */
    @Test
    fun ST11_就此完结_生成中拒绝且零写库() {
        giveChapter(chapter(userChoice = "A"))
        coEvery { repository.getStory("s1") } returns story.copy(status = StoryStatus.GENERATING)
        val vm = vm().activate()
        val toasts = mutableListOf<Int>()
        collectToasts(vm, toasts)

        vm.finishStory()
        await("忙 toast") { toasts.isNotEmpty() }

        coVerify(exactly = 0) { repository.updateStatus(any(), any(), any()) }
        assertEquals(listOf(R.string.story_archive_busy_toast), toasts)
    }

    /** 已完结再点 → 幂等静默：零写库、零 toast。 */
    @Test
    fun ST11_就此完结_已完结幂等静默() {
        giveChapter(chapter(userChoice = "A"))
        var lookedUp = false
        coEvery { repository.getStory("s1") } answers { lookedUp = true; story.copy(status = StoryStatus.COMPLETED) }
        val vm = vm().activate()
        val toasts = mutableListOf<Int>()
        collectToasts(vm, toasts)

        vm.finishStory()
        await("fresh 读完成") { lookedUp }
        idle()

        coVerify(exactly = 0) { repository.updateStatus(any(), any(), any()) }
        assertTrue("幂等路不许弹 toast", toasts.isEmpty())
    }

    /** 失败路不清：动作没成立，意图不动（clear 只许在主写库成功之后）。 */
    @Test
    fun ST11_自然发展写库失败_意图不动() {
        giveChapter(chapter(userChoice = "A"))
        val failedStory = story.copy(
            status = StoryStatus.WAITING_CHOICE,
            requestedEndingType = StoryEndingType.CUSTOM,
        )
        every { repository.observeStory("s1") } returns flowOf(failedStory)
        coEvery { repository.updateStatus(any(), any(), any()) } throws RuntimeException("写库炸了")
        coEvery { repository.clearEndingRequest(any(), any()) } returns Unit
        coEvery { repository.getStory("s1") } returns failedStory
        val vm = vm().activateStoryAndChapters()

        vm.forceContinue()
        await("失败已呈现") { vm.error.value != null }

        coVerify(exactly = 0) { repository.clearEndingRequest(any(), any()) }
        coVerify(exactly = 0) { taskManager.startGeneration(any()) }
    }

    // ── 卷三 C3：「上回说到」（图纸 §5 E4/E6/E7）──

    /** 两章的书：ch2 是当前章，ch1 带摘要。 */
    private fun giveTwoChapters(previousSummary: String?) {
        val ch1 = StoryChapterEntity(id = "ch0", storyId = "s1", chapterNumber = 1, content = "第一章", chapterSummary = previousSummary)
        val ch2 = StoryChapterEntity(id = "ch1", storyId = "s1", chapterNumber = 2, content = "第二章")
        coEvery { repository.getChapter("ch1") } returns ch2
        coEvery { repository.getChapterMetas("s1") } returns listOf(ch1, ch2)
        every { repository.observeStory("s1") } returns flowOf(story.copy(cachedChapterCount = 2))
    }

    private fun StoryReaderViewModel.activateRecap() = also {
        jobs += scope.launch { recapSummary.collect {} }
        activateStoryAndChapters()
    }

    @Test
    fun 上回说到_隔够12小时回来_展开上一章摘要() {
        giveTwoChapters(previousSummary = "你在旧仓库找到了那半张船票。")
        // 相对真实 now 构造（生产码内部直取 System.currentTimeMillis·PITFALLS §1e）。
        every { readingProgressStore.lastReadAtMillis("s1") } returns System.currentTimeMillis() - 13 * 3_600_000L
        val vm = vm().activateRecap()

        await("前情条落定") { vm.recapSummary.value != null }
        assertEquals("你在旧仓库找到了那半张船票。", vm.recapSummary.value)
    }

    @Test
    fun 上回说到_刚刚才读过_不出() {
        giveTwoChapters(previousSummary = "上一章梗概。")
        every { readingProgressStore.lastReadAtMillis("s1") } returns System.currentTimeMillis() - 60_000L
        val vm = vm().activateRecap()

        // 等待点落在「时间戳确已写入」这一真事件上，而不是「值仍是 null」的初始态（防假绿）。
        await("进度已记账") { runCatching { verifyProgressSaved() }.isSuccess }
        idle()
        assertNull(vm.recapSummary.value)
    }

    @Test
    fun 上回说到_老用户首次没有时间戳_不出且本次写入() {
        giveTwoChapters(previousSummary = "上一章梗概。")
        every { readingProgressStore.lastReadAtMillis("s1") } returns null
        val vm = vm().activateRecap()

        await("进度已记账") { runCatching { verifyProgressSaved() }.isSuccess }
        idle()
        assertNull("E4：本次不弹", vm.recapSummary.value)
        // 本次进入仍要写时间戳（下次回访才生效·自愈无迁移）。
        coVerify(atLeast = 1) { readingProgressStore.saveProgress("s1", "ch1", 2, any()) }
    }

    @Test
    fun 上回说到_上一章没有摘要_不出() {
        giveTwoChapters(previousSummary = "   ")
        every { readingProgressStore.lastReadAtMillis("s1") } returns System.currentTimeMillis() - 30 * 3_600_000L
        val vm = vm().activateRecap()

        await("进度已记账") { runCatching { verifyProgressSaved() }.isSuccess }
        idle()
        assertNull("E6：只读既有摘要，没有就不出（绝不现场生成）", vm.recapSummary.value)
    }

    @Test
    fun 上回说到_时间戳只在进屏那一刻读一次_不追弹() {
        giveTwoChapters(previousSummary = "上一章梗概。")
        every { readingProgressStore.lastReadAtMillis("s1") } returns System.currentTimeMillis() - 60_000L
        val vm = vm().activateRecap()

        await("进度已记账") { runCatching { verifyProgressSaved() }.isSuccess }
        repeat(30) { idle() }
        assertNull(vm.recapSummary.value)
        // E7 的机制证据：整个会话里只读一次快照——之后时间再怎么走都不会翻脸。
        verify(exactly = 1) { readingProgressStore.lastReadAtMillis("s1") }
    }

    private fun verifyProgressSaved() =
        coVerify(atLeast = 1) { readingProgressStore.saveProgress("s1", "ch1", 2, any()) }

    // ── 图纸一 C2b：错误弹窗按「可重试性」分流（§3.4-4 · E22/E23）──
    // 期望独立反推自规格：只有生成失败（lastErrors 路）才可重试；操作类失败点「重试」会去生成新章，语义全错。

    /**
     * 让 taskManager 携带一条生成失败错误（[消息]）。`consumeLastError` 必须显式打桩——relaxed mock 对
     * `String?` 返回空串而非 null，不打桩会让「取走的那份」变成空文案（弹窗内容为空的假绿）。
     */
    private fun giveGenerationFailure(消息: String) {
        every { taskManager.lastErrors } returns MutableStateFlow(mapOf("s1" to 消息))
        every { taskManager.consumeLastError("s1") } returns 消息
    }

    /** 生成失败（含服务商拒答）→ retryable=true，弹窗给重试键。 */
    @Test
    fun 生成失败弹窗_带可重试标记() {
        giveChapter(chapter())
        giveGenerationFailure("服务商拒绝了这次生成，本章内容未保存。")
        val vm = vm().activate()

        await("错误已呈现") { vm.error.value != null }

        assertEquals("服务商拒绝了这次生成，本章内容未保存。", vm.error.value?.message)
        assertTrue("生成失败必须给重试键", vm.error.value?.retryable == true)
    }

    /** E23 · 操作类失败（继续推进写库炸）→ retryable=false，维持只有确认键。 */
    @Test
    fun 操作类失败弹窗_不带可重试标记() {
        giveChapter(chapter(userChoice = "A"))
        val waitingStory = story.copy(status = StoryStatus.WAITING_CHOICE)
        every { repository.observeStory("s1") } returns flowOf(waitingStory)
        coEvery { repository.updateStatus(any(), any(), any()) } throws RuntimeException("写库炸了")
        coEvery { repository.getStory("s1") } returns waitingStory
        val vm = vm().activateStoryAndChapters()

        vm.forceContinue()
        await("失败已呈现") { vm.error.value != null }

        assertFalse("操作类失败点重试会去生成新章，绝不能给重试键", vm.error.value?.retryable == true)
    }

    /** E22 · 点重试：弹窗收起 + 走书架/章节列表同款 TaskManager 重试路（不另起生成路径）。 */
    @Test
    fun 点重试_清弹窗并走TaskManager重试路() {
        giveChapter(chapter())
        val failedStory = story.copy(status = StoryStatus.GENERATION_FAILED)
        every { repository.observeStory("s1") } returns flowOf(failedStory)
        giveGenerationFailure("生成失败了。")
        val vm = vm().activateStoryAndChapters()
        await("错误已呈现") { vm.error.value?.message == "生成失败了。" }

        vm.retryGeneration()

        assertNull("重试后弹窗必须收起", vm.error.value)
        verify(exactly = 1) { taskManager.retryGeneration(failedStory) }
        // 绝不另起生成路径：重试只经 TaskManager（意图保留由它那条既有路负责）。
        verify(exactly = 0) { taskManager.startGeneration(any()) }
    }
}
