package com.situ.aichat.ui.story

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.entity.StoryChapterEntity
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.R
import com.situ.aichat.data.repository.StoryRepository
import com.situ.aichat.story.StoryArchiver
import com.situ.aichat.story.StoryChapterDraft
import com.situ.aichat.story.StoryChoiceClassifier
import com.situ.aichat.story.StoryChoiceCountdown
import com.situ.aichat.story.StoryDirectionEditor
import com.situ.aichat.story.StoryEndingType
import com.situ.aichat.story.StoryGenerationService
import com.situ.aichat.story.StoryGenerationTaskManager
import com.situ.aichat.story.StoryReadingProgressStore
import com.situ.aichat.story.StoryRecapLogic
import com.situ.aichat.story.StoryScrollPosition
import com.situ.aichat.story.StoryStateTransitions
import com.situ.aichat.story.StoryStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 阅读器错误弹窗的载荷（图纸一 C2b）：[retryable] 决定给不给「重试」键。只有**生成失败**（lastErrors 路）可重试；
 * 定收尾 / 取消收尾 / 续篇 / 推进 / 请求结局 / 重写 / 落选择这些**操作类**失败恒 false——否则点「重试」会去生成新章。
 */
data class StoryReaderError(val message: String, val retryable: Boolean)

/**
 * 故事阅读器 VM（1:1 iOS `StoryReaderView` 的状态 + 编排：章节导航、选择反悔窗口、续写/重写/结局触发、滚动持久化）。
 *
 * 入口为章节 id（路由 storyReader/{chapterId}），由它解析出 storyId 再观察故事/章节；章节切换不重新导航、只换
 * [currentChapterId]（= iOS 改 currentChapterID）。生成统一走 [StoryGenerationService] + [StoryGenerationTaskManager]
 * （别另起生成路径）。落选择/进度/结局请求走 [StoryRepository] 定向写（D1 安全）。
 *
 * **行数豁免声明（CLAUDE.md §2 🔴 逻辑层 600 硬上限）**：本文件 664 行、越线 64。已存走向卷（图纸 2026-08-06）
 * 只 +36 行两条**薄接线**（overwriteDirection / withdrawDirection·逻辑体全在 [StoryDirectionEditor]，本 VM 只许
 * +接线不许 +逻辑体）；拆分本 VM 属独立重构，已登记 FILE_SIZE_REFACTOR_BACKLOG 观察名单等专门的拆分卷处理。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StoryReaderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: StoryRepository,
    private val generationService: StoryGenerationService,
    private val taskManager: StoryGenerationTaskManager,
    private val readingProgressStore: StoryReadingProgressStore,
    private val archiver: StoryArchiver,
) : ViewModel() {

    private val initialChapterId: String = savedStateHandle.get<String>("chapterId").orEmpty()
    private val storyIdFlow = MutableStateFlow<String?>(null)

    private val _currentChapterId = MutableStateFlow(initialChapterId)
    val currentChapterId: StateFlow<String> = _currentChapterId.asStateFlow()

    private val _userRoleName = MutableStateFlow<String?>(null)
    val userRoleName: StateFlow<String?> = _userRoleName.asStateFlow()

    private val _readingAnimationsEnabled = MutableStateFlow(readingProgressStore.readingAnimationsEnabled())
    val readingAnimationsEnabled: StateFlow<Boolean> = _readingAnimationsEnabled.asStateFlow()

    /** 阅读字号档位下标（P1-6；播种自 store，默认档=iOS 原值）。 */
    private val _fontSizeIndex = MutableStateFlow(readingProgressStore.fontSizeIndex())
    val fontSizeIndex: StateFlow<Int> = _fontSizeIndex.asStateFlow()

    /** 章节列表显式刷新触发器：落选择只改章节行 + cachedHasPendingChoice，章数不变时靠它强制重载（见 [chapters]）。 */
    private val chapterRefresh = MutableStateFlow(0L)

    private val _toastEvents = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    /** 一次性提示事件（string res id），屏幕 collect 转 Toast（照 StoryBookshelfViewModel 同款）。 */
    val toastEvents = _toastEvents.asSharedFlow()

    val story: StateFlow<StoryEntity?> = storyIdFlow
        .filterNotNull()
        .flatMapLatest { repository.observeStory(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 章节列表（升序·**元数据投影**·卷二 §3.2：正文只经 [currentChapter]，禁读本列表的 content/previousDraftJson）。
     * 按 cachedChapterCount 变化（新增/删章，= iOS refreshChapterList）**或** [chapterRefresh] 手动触发重载
     * ——后者覆盖「非最新章落选择」等 count 不变但 userChoice 已改的情形。
     */
    val chapters: StateFlow<List<StoryChapterEntity>> = storyIdFlow
        .filterNotNull()
        .flatMapLatest { sid ->
            combine(
                repository.observeStory(sid).map { it?.cachedChapterCount ?: 0 }.distinctUntilChanged(),
                chapterRefresh,
            ) { _, _ -> sid }
                .map { repository.getChapterMetas(sid) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 当前章**全列**单查（卷二 §3.3）：正文渲染与「上一版」槽要整行；[chapters] 变化 = 刷新信号（保 refreshChapters 语义）。 */
    val currentChapter: StateFlow<StoryChapterEntity?> =
        combine(_currentChapterId, chapters) { id, _ -> id }
            .mapLatest { id -> repository.getChapter(id) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 当前章槽里的「上一版」（C3）：无槽/解码失败一律 null（E6）；菜单显隐与回翻弹层共用，UI 不碰存储 JSON。 */
    val previousDraft: StateFlow<StoryChapterDraft?> =
        currentChapter.map { StoryChapterDraft.decode(it?.previousDraftJson) }
            .flowOn(Dispatchers.Default) // 切章即解一整章正文的 JSON，别占主线程
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val activeGeneration: StateFlow<StoryGenerationTaskManager.GenerationProgress?> =
        combine(storyIdFlow, taskManager.activeGenerations) { sid, gens -> sid?.let { gens[it] } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ── 「上回说到」回访前情条（卷三 C3）──

    /** 进屏那一刻的「上次阅读时刻」与「此刻」快照（只在首个章节 id 落定时取一次，见 init）。 */
    private val _entryLastReadAtMillis = MutableStateFlow<Long?>(null)
    private val _entryNowMillis = MutableStateFlow(0L)
    private var recapSnapshotTaken = false

    /**
     * 该展开给读者看的「上回说到」正文（= **上一章既有的** `chapterSummary`）；不该出时为 null。
     *
     * 判定全交纯函数 [StoryRecapLogic.showRecap]（首章 / 无摘要 / 没隔够 [StoryRecapLogic.RECAP_THRESHOLD_MS] 都不出），
     * 时间侧用进屏快照。**零 LLM**：摘要拿不到就是不出，绝不现场生成。
     */
    val recapSummary: StateFlow<String?> = combine(
        currentChapter,
        chapters,
        _entryLastReadAtMillis,
        _entryNowMillis,
    ) { chapter, list, lastReadAt, now ->
        val ch = chapter ?: return@combine null
        val summary = list.firstOrNull { it.chapterNumber == ch.chapterNumber - 1 }?.chapterSummary?.trim().orEmpty()
        val show = StoryRecapLogic.showRecap(
            chapterNumber = ch.chapterNumber,
            lastReadAtMillis = lastReadAt,
            nowMillis = now,
            previousSummaryBlank = summary.isEmpty(),
        )
        if (show) summary else null
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ── 选择反悔窗口 ──
    private val _selectedChoiceText = MutableStateFlow<String?>(null)
    val selectedChoiceText: StateFlow<String?> = _selectedChoiceText.asStateFlow()
    private val _pendingActive = MutableStateFlow(false)
    val pendingActive: StateFlow<Boolean> = _pendingActive.asStateFlow()
    private val _pendingRemainingSeconds = MutableStateFlow(StoryChoiceCountdown.WINDOW_SECONDS)
    val pendingRemainingSeconds: StateFlow<Int> = _pendingRemainingSeconds.asStateFlow()
    private var pendingJob: Job? = null

    // ── 一次性对话框/事件状态 ──
    private val _askGenerateNextChapter = MutableStateFlow(false)
    val askGenerateNextChapter: StateFlow<Boolean> = _askGenerateNextChapter.asStateFlow()
    private val _error = MutableStateFlow<StoryReaderError?>(null)
    val error: StateFlow<StoryReaderError?> = _error.asStateFlow()
    /** 置错单口（message 为 null 时不弹窗，保留既有边角语义）。 */
    private fun postError(message: String?, retryable: Boolean) {
        _error.value = message?.let { StoryReaderError(it, retryable) }
    }

    init {
        // 解析 storyId + 用户角色名（从初始章节）。
        viewModelScope.launch {
            val chapter = repository.getChapter(initialChapterId)
            val sid = chapter?.storyId
            storyIdFlow.value = sid
            if (sid != null) {
                _userRoleName.value = repository.getRoles(sid).firstOrNull { it.isUserRole }?.roleName
            }
        }
        // 章节切换（仅 id 变化）→ 取消未提交反悔 + 同步已选 + 记进度。
        viewModelScope.launch {
            _currentChapterId.collect { id ->
                clearPending()
                val ch = repository.getChapter(id)
                _selectedChoiceText.value = ch?.userChoice
                if (ch != null) {
                    // 卷三 C3：「上回说到」取**进屏那一刻**的快照——必须赶在 saveProgress 覆盖时间戳之前读，
                    // 且只读一次（进屏后停留再久也不追弹·图纸 §5 E7）。
                    if (!recapSnapshotTaken) {
                        recapSnapshotTaken = true
                        _entryNowMillis.value = System.currentTimeMillis()
                        _entryLastReadAtMillis.value = readingProgressStore.lastReadAtMillis(ch.storyId)
                    }
                    readingProgressStore.saveProgress(ch.storyId, ch.id, ch.chapterNumber)
                    Log.i(TAG, "打开章节 #${ch.chapterNumber} (${ch.id})")
                }
            }
        }
        // 生成完成（活跃生成 非空→空）→ 跳到最新章（= iOS onChange progress nil 跳 latest）。
        viewModelScope.launch {
            var wasGenerating = false
            activeGeneration.collect { gen ->
                val nowGenerating = gen != null
                if (wasGenerating && !nowGenerating) {
                    val sid = storyIdFlow.value
                    if (sid != null) {
                        val latest = repository.getLatestChapterMeta(sid)
                        if (latest != null && latest.id != _currentChapterId.value) {
                            _currentChapterId.value = latest.id
                            Log.i(TAG, "生成完成，跳到最新章 #${latest.chapterNumber}")
                        }
                    }
                }
                wasGenerating = nowGenerating
            }
        }
        // 生成失败错误 → 弹提示（= iOS onChange lastErrors）。
        viewModelScope.launch {
            combine(storyIdFlow, taskManager.lastErrors) { sid, errs -> sid?.let { errs[it] } }
                .filterNotNull()
                .collect { msg ->
                    val sid = storyIdFlow.value ?: return@collect
                    postError(taskManager.consumeLastError(sid) ?: msg, retryable = true)
                }
        }
    }

    // ── 章节导航 ──

    fun goToChapter(id: String) {
        if (id != _currentChapterId.value) _currentChapterId.value = id
    }

    fun goPrevious() {
        val list = chapters.value
        val idx = list.indexOfFirst { it.id == _currentChapterId.value }
        if (idx > 0) goToChapter(list[idx - 1].id)
    }

    fun goNext() {
        val list = chapters.value
        val idx = list.indexOfFirst { it.id == _currentChapterId.value }
        if (idx in 0 until list.lastIndex) goToChapter(list[idx + 1].id)
    }

    // ── 选择反悔窗口 ──

    /** 用户做选择：进入反悔窗口（[StoryChoiceCountdown.WINDOW_SECONDS] 秒·当前 4·不立即落库），到期由 [commitPendingChoice] 提交。 */
    fun submitChoice(choice: String) {
        val trimmed = choice.trim()
        val ch = currentChapter.value ?: return
        if (trimmed.isEmpty() || ch.userChoice != null) return
        _selectedChoiceText.value = trimmed
        _pendingRemainingSeconds.value = StoryChoiceCountdown.WINDOW_SECONDS
        _pendingActive.value = true
        pendingJob?.cancel()
        pendingJob = viewModelScope.launch {
            val deadline = System.currentTimeMillis() + StoryChoiceCountdown.WINDOW_MS
            while (!StoryChoiceCountdown.isExpired(deadline, System.currentTimeMillis())) {
                _pendingRemainingSeconds.value = StoryChoiceCountdown.remainingSeconds(deadline, System.currentTimeMillis())
                delay(StoryChoiceCountdown.TICK_MS)
            }
            commitPendingChoice(trimmed)
        }
    }

    /** 撤销未提交的选择（反悔窗口内）。 */
    fun cancelPendingChoice() {
        clearPending()
        _selectedChoiceText.value = null
    }

    private fun clearPending() {
        pendingJob?.cancel()
        pendingJob = null
        _pendingActive.value = false
        _pendingRemainingSeconds.value = StoryChoiceCountdown.WINDOW_SECONDS
    }

    /** 强制重载章节列表（落选择/跳过后章数不变时刷新 userChoice 视图）。 */
    private fun refreshChapters() {
        chapterRefresh.value += 1
    }

    /**
     * 结局意图「用户覆盖」（ST11 §3.3 拍板③·图纸锁定**恰三个注入点**：[commitPendingChoice] /
     * [forceContinue] / [rewrite]，多一处少一处都算走样）。
     *
     * 场景：用户请求了结局但生成失败（拍板①起意图**不再被失败清掉**，留着给「重新生成」用）；此时用户若改主意、
     * 做了任一**其它**推进动作，就该把旧意图覆盖掉，否则下一章会莫名其妙又写成结局章。
     *
     * 两条铁律：
     * 1. **只在动作主写库成功之后调**（失败路不清——动作没成立，意图不动）；
     * 2. **必须排在 `startGeneration` 与它前面的 fresh 读之前**（E4 顺序锁）——生成读的是 story 快照，
     *    先读后清 = 快照里意图还在 = 照样写结局章，等于本函数白调。
     *
     * 清除本身对「无残留意图」的书是无害空写（只多刷一次 updatedAt），故不加前置判空读库。
     * 失败只记日志：主动作已经成功了，不该因为一次清理写库失败把整个动作报成失败。
     */
    private suspend fun clearEndingRequestAfterUserAction(storyId: String, nowMillis: Long) {
        runCatching { repository.clearEndingRequest(storyId, nowMillis) }
            .onFailure { Log.e(TAG, "清结局意图失败 story=${storyId.take(8)}", it) }
    }

    private suspend fun commitPendingChoice(choice: String) {
        _pendingActive.value = false
        val ch = currentChapter.value
        val sid = storyIdFlow.value
        if (ch == null || sid == null || ch.userChoice != null) return
        val now = System.currentTimeMillis()
        runCatching { repository.commitUserChoice(sid, ch.id, choice, now, fromStatus = story.value?.status) }
            .onSuccess {
                // 意图覆盖注入点 1/3（§3.3）：用户确认选择 = 一个新的推进动作，覆盖失败残留的旧「写结局」意图。
                // 必须在主写库成功之后（失败路不清：动作没成立，意图不动）。
                // E10 天然隔离：结局流的「跳过选择」走 requestEnding 内部的 repository.commitUserChoice 直调，
                // 不经本函数 → 不会误清自己刚提交的结局意图。
                clearEndingRequestAfterUserAction(sid, now)
                refreshChapters()
                _askGenerateNextChapter.value = true
                Log.i(TAG, "选择落库 #${ch.chapterNumber}: $choice")
            }
            .onFailure { e ->
                _selectedChoiceText.value = null
                postError(e.message, retryable = false)
                Log.e(TAG, "选择落库失败", e)
            }
    }

    // ── 生成触发（统一走 service + taskManager）──

    fun dismissAskNext() { _askGenerateNextChapter.value = false }

    /** 「立即生成」下一章。 */
    fun generateNext() {
        val s = story.value ?: return
        _askGenerateNextChapter.value = false
        taskManager.startGeneration(s)
        Log.i(TAG, "触发生成下一章: ${s.id}")
    }

    /**
     * 建议卡「就此完结」（ST11 §3.5）：用户给 AI 的完结建议盖章 → 走与书架长按「完结归档」**同一个**
     * [StoryArchiver]（守卫三态语义完全一致）。
     *
     * 完结后无需手工导航：story 是响应式流 → 状态变 COMPLETED → 建议卡/推进区按 §3.4 自然消失、
     * 顶栏 canContinue 变 false。
     */
    fun finishStory() {
        val sid = storyIdFlow.value ?: return
        viewModelScope.launch {
            when (archiver.archive(sid, System.currentTimeMillis())) {
                StoryArchiver.Result.ARCHIVED -> {
                    _toastEvents.tryEmit(R.string.story_archived_toast)
                    Log.i(TAG, "就此完结：$sid")
                }
                StoryArchiver.Result.BUSY -> _toastEvents.tryEmit(R.string.story_archive_busy_toast)
                StoryArchiver.Result.SKIPPED -> Unit
            }
        }
    }

    // ── 终章弧「从容收尾」（卷二 J1）──

    /**
     * 定下「从容收尾」计划：接下来 3-5 章慢慢收伏笔，末章自动写成加长大结局。
     *
     * 落库只此一条 UPDATE（[StoryRepository.updateFinalePlanStartingNewArc]）：写 finale 两列 + 清 storyOutline，
     * 于是下一次生成必然先造一条终章弧大纲。**有意不自动触发生成**——用户下一次任何推进动作
     * （答选择 / 让故事自然发展 / 立即生成）起，新章才属于终章弧。
     *
     * 落库前 fresh 读守卫（照 [StoryArchiver] 姿势·PITFALLS 1b）：正在生成中就拒绝并提示，
     * 免得与「倒数判定 + 转正」赛跑。反悔随时可撤（[cancelFinale]）。
     */
    fun planFinale(type: String, detail: String?) {
        val sid = storyIdFlow.value ?: return
        viewModelScope.launch {
            runCatching {
                val fresh = repository.getStory(sid) ?: return@runCatching
                if (fresh.status == StoryStatus.GENERATING || taskManager.activeGenerations.value.containsKey(sid)) {
                    _toastEvents.tryEmit(R.string.story_archive_busy_toast)
                    return@runCatching
                }
                val effectiveDetail = if (type == StoryEndingType.CUSTOM) detail else null
                repository.updateFinalePlanStartingNewArc(sid, type, effectiveDetail, System.currentTimeMillis())
                _toastEvents.tryEmit(R.string.story_finale_planned_toast)
                Log.i(TAG, "定下收尾计划 type=$type: $sid")
            }.onFailure { postError(it.message, retryable = false); Log.e(TAG, "定收尾计划失败", it) }
        }
    }

    /**
     * 取消收尾计划（J7）：一条 UPDATE 清 finale 两列 + 大纲 + 弧起点 → 下一次生成起一条新的普通弧
     * （弧线简史里已给被顶掉的旧弧留了名，不试图恢复它）。同样带生成中守卫。
     */
    fun cancelFinale() {
        val sid = storyIdFlow.value ?: return
        viewModelScope.launch {
            runCatching {
                val fresh = repository.getStory(sid) ?: return@runCatching
                if (fresh.status == StoryStatus.GENERATING || taskManager.activeGenerations.value.containsKey(sid)) {
                    _toastEvents.tryEmit(R.string.story_archive_busy_toast)
                    return@runCatching
                }
                repository.clearFinalePlanAndOutline(sid, System.currentTimeMillis())
                _toastEvents.tryEmit(R.string.story_finale_cancelled_toast)
                Log.i(TAG, "取消收尾计划: $sid")
            }.onFailure { postError(it.message, retryable = false); Log.e(TAG, "取消收尾计划失败", it) }
        }
    }

    /** 底部胶囊「开启续篇」（completed + 末章）。 */
    fun continueCompletedStory() {
        val s = story.value ?: return
        viewModelScope.launch {
            runCatching {
                generationService.continueStory(s, System.currentTimeMillis())
                val updated = repository.getStory(s.id) ?: s
                taskManager.startGeneration(updated)
                Log.i(TAG, "开启续篇: ${s.id}")
            }.onFailure { postError(it.message, retryable = false); Log.e(TAG, "开启续篇失败", it) }
        }
    }

    /** 菜单「继续推进」确认：处理待选/waitingChoice 后生成。 */
    fun forceContinue() {
        val s = story.value ?: return
        val ch = currentChapter.value
        viewModelScope.launch {
            runCatching {
                val now = System.currentTimeMillis()
                if (ch != null && ch.hasChoice && ch.userChoice == null) {
                    repository.commitUserChoice(s.id, ch.id, StoryChoiceClassifier.NATURAL_FLOW_CHOICE, now, setSerializing = true, fromStatus = s.status)
                    refreshChapters()
                } else if (s.status == StoryStatus.WAITING_CHOICE) {
                    StoryStateTransitions.check(s.status, StoryStatus.SERIALIZING, "StoryReaderViewModel.forceContinue")
                    repository.updateStatus(s.id, StoryStatus.SERIALIZING, now)
                }
                // 意图覆盖注入点 2/3（§3.3）：「让故事自然发展」= 新的推进动作，覆盖旧「写结局」意图。
                // **E4 顺序锁：clear 必须排在下面的 fresh 读 + startGeneration 之前**——startGeneration 吃的是
                // 重读出来的 story 快照，若先读后清，快照里的 requestedEndingType 仍在 → 这一章又写成结局章。
                clearEndingRequestAfterUserAction(s.id, now)
                val updated = repository.getStory(s.id) ?: s
                taskManager.startGeneration(updated)
                Log.i(TAG, "继续推进: ${s.id}")
            }.onFailure { postError(it.message, retryable = false); Log.e(TAG, "继续推进失败", it) }
        }
    }

    /**
     * 请求结局（type = open/ai/custom；custom 才带 detail）。
     *
     * [skipPendingChoice]=true 时同协程**先**把最新章未答选择标记为「跳过」（[StoryChoiceClassifier.SKIP_FOR_ENDING_CHOICE]·
     * 不改 status）再提交结局请求——跳过与结局绑定为一个动作（ST10-4 延迟提交）：用户在结局三选弹窗
     * 取消则两者都不发生，根治旧「先斩后奏」流的幽灵态（跳过已写库、结局却没请求，书卡在等待选择
     * 且再无选择可做）与两协程并发竞态。
     */
    fun requestEnding(type: String, detail: String?, skipPendingChoice: Boolean = false) {
        val s = story.value ?: return
        viewModelScope.launch {
            runCatching {
                val now = System.currentTimeMillis()
                if (skipPendingChoice) {
                    chapters.value.lastOrNull()
                        ?.takeIf { it.hasChoice && it.userChoice == null }
                        ?.let { latest ->
                            repository.commitUserChoice(s.id, latest.id, StoryChoiceClassifier.SKIP_FOR_ENDING_CHOICE, now, setSerializing = false, fromStatus = s.status)
                            refreshChapters()
                        }
                }
                val effectiveDetail = if (type == StoryEndingType.CUSTOM) detail else null
                StoryStateTransitions.check(s.status, StoryStatus.SERIALIZING, "StoryReaderViewModel.requestEnding")
                repository.updateEndingRequest(s.id, type, effectiveDetail, StoryStatus.SERIALIZING, now)
                val updated = repository.getStory(s.id) ?: s
                taskManager.startGeneration(updated)
                Log.i(TAG, "请求结局 type=$type skipChoice=$skipPendingChoice: ${s.id}")
            }.onFailure { postError(it.message, retryable = false); Log.e(TAG, "请求结局失败", it) }
        }
    }

    /** 重写本章：删最新章 + 回滚记忆 + 重新生成（统一走 prepareRewrite + startGeneration）。 */
    fun rewrite(instruction: String?) {
        val s = story.value ?: return
        val latest = chapters.value.lastOrNull() ?: return
        viewModelScope.launch {
            runCatching {
                val now = System.currentTimeMillis()
                // 卷二 D-1：prepareRewrite 把本章整段快照进「上一版」槽（含正文），而 [chapters] 已是元数据投影 ⇒ 必须重读全列。
                val fullLatest = repository.getChapter(latest.id) ?: return@runCatching
                generationService.prepareRewrite(s, fullLatest, instruction, now)
                // 意图覆盖注入点 3/3（§3.3）：重写末章 = 新的推进动作，覆盖旧「写结局」意图。
                // 非冗余：prepareRewrite 只经 updateRewriteState 写 bible/summary/rewriteInstruction/status/beats，
                // **不碰 requestedEndingType/Detail**（已读真码核实）。同样锁 E4 顺序：排在 fresh 读 + startGeneration 之前。
                clearEndingRequestAfterUserAction(s.id, now)
                // prepareRewrite 删了最新章 → 跳到新的最新章。
                repository.getLatestChapterMeta(s.id)?.let { _currentChapterId.value = it.id }
                val updated = repository.getStory(s.id) ?: s
                taskManager.startGeneration(updated)
                Log.i(TAG, "重写本章: ${s.id}")
            }.onFailure { postError(it.message, retryable = false); Log.e(TAG, "重写失败", it) }
        }
    }

    /**
     * 「换回上一版」（C3·图纸三 §3.1）：当前章内容字段与槽里的上一版**互换**，一条 13 列定向 UPDATE 写完
     * （取值全来自纯函数 [StoryChapterDraft.swapApplied]），随后刷新章节列表让阅读器重读。
     *
     * **绝不触发生成、绝不动故事级叙事字段**——摘要/伏笔/角色状态仍以最新一次生成为准（§0.2-5 裁决，
     * 确认弹窗文案已如实告知）。槽是空的或 JSON 损坏（E6）时静默不做事：入口本就不该出现在那种章上。
     */
    fun restorePreviousDraft() {
        val chapter = currentChapter.value ?: return
        val draft = StoryChapterDraft.decode(chapter.previousDraftJson) ?: return
        viewModelScope.launch {
            runCatching {
                repository.swapChapterDraft(StoryChapterDraft.swapApplied(chapter, draft))
                refreshChapters()
                _toastEvents.tryEmit(R.string.story_prev_draft_swapped_toast)
                Log.i(TAG, "换回上一版 #${chapter.chapterNumber}: ${chapter.id}")
            }.onFailure { postError(it.message, retryable = false); Log.e(TAG, "换回上一版失败", it) }
        }
    }

    /**
     * 保存本章小结（C4·图纸三 §3.2）：对**当前打开的那一章**列级定向写，空白输入落 null（= 回落「无小结」，
     * 续章前情滑窗的 mapNotNull 自然跳过该章）。改完下一章生成时自然生效——前情滑窗与压缩链读的就是
     * `getChapterSummaries` 这一列，零额外接线。不动 `story.storySummary`（首章镜像的短暂不同步属可接受漂移·§0.2-7）。
     */
    fun saveChapterSummary(text: String) {
        val chapter = currentChapter.value ?: return
        val summary = text.trim().ifBlank { null }
        viewModelScope.launch {
            runCatching {
                repository.updateChapterSummary(chapter.id, summary)
                refreshChapters()
                Log.i(TAG, "本章小结已保存 #${chapter.chapterNumber}（${summary?.length ?: 0} 字）")
            }.onFailure { postError(it.message, retryable = false); Log.e(TAG, "保存本章小结失败", it) }
        }
    }

    /**
     * 三档快评（卷三 §3.1·J3）：对**当前打开的那一章**列级定向写；[rating] 取 1/2/3，null = 取消（再点同档）。
     * **无反悔窗**（评分是可随时改写的元数据）；落库失败只提示不打断阅读（UI 值随热流回落原值·E2）。
     */
    fun rateChapter(rating: Int?) {
        val ch = currentChapter.value ?: return
        viewModelScope.launch {
            runCatching {
                repository.updateChapterRating(ch.id, rating)
                refreshChapters()
                Log.i(TAG, "本章快评 #${ch.chapterNumber} = ${rating ?: 0}")
            }.onFailure { postError(it.message, retryable = false); Log.e(TAG, "保存快评失败", it) }
        }
    }

    /**
     * 导演台节拍两写口的共用壳（卷三 §3.1）：走卷一建的**一条 UPDATE**（beats + 「用户改过」标志原子写）；
     * 与生成侧写 beats 是 last-write-wins，生成中途保存不影响进行中那一章（生成侧已取快照·卷一 §3.8）。
     */
    private fun writeChapterBeats(beats: String?, edited: Boolean, note: String) {
        val sid = storyIdFlow.value ?: return
        viewModelScope.launch {
            runCatching {
                repository.updateChapterBeatsUserEdited(sid, beats, edited)
                Log.i(TAG, "$note: $sid")
            }.onFailure { postError(it.message, retryable = false); Log.e(TAG, "写本章节拍失败", it) }
        }
    }

    /** 导演台编辑模式「覆盖保存」：直写不进反悔窗；成功后照 D-5 再问一次「立即生成/稍后」。 */
    fun overwriteDirection(text: String) {
        val s = story.value ?: return
        val ch = currentChapter.value ?: return
        viewModelScope.launch {
            runCatching {
                if (!StoryDirectionEditor.overwrite(repository, taskManager, s.id, ch.id, text, System.currentTimeMillis())) {
                    _toastEvents.tryEmit(R.string.story_archive_busy_toast)
                    return@runCatching
                }
                _selectedChoiceText.value = text.trim()
                refreshChapters()
                _askGenerateNextChapter.value = true
                Log.i(TAG, "覆盖走向 #${ch.chapterNumber}")
            }.onFailure { postError(it.message, retryable = false); Log.e(TAG, "覆盖走向失败", it) }
        }
    }

    /** 导演台「撤回走向」：清已答选择；有选项章连载态回转 waitingChoice（判定在 Editor 内 fresh 读）。 */
    fun withdrawDirection() {
        val s = story.value ?: return
        val ch = currentChapter.value ?: return
        viewModelScope.launch {
            runCatching {
                if (!StoryDirectionEditor.withdraw(repository, taskManager, s.id, ch.id, ch.hasChoice, System.currentTimeMillis())) {
                    _toastEvents.tryEmit(R.string.story_archive_busy_toast)
                    return@runCatching
                }
                _selectedChoiceText.value = null
                refreshChapters()
                Log.i(TAG, "撤回走向 #${ch.chapterNumber}")
            }.onFailure { postError(it.message, retryable = false); Log.e(TAG, "撤回走向失败", it) }
        }
    }

    /** 导演台「本章节拍」保存：空白落 null =「留白也是指定」（本章自由发挥·E6·卷一 §3.3 已实现该语义）。 */
    fun saveChapterBeats(text: String) = writeChapterBeats(text.trim().ifEmpty { null }, true, "本章节拍已保存")

    /** 导演台「恢复 AI 预排」：只把标志复位，节拍文本一个字不动（E7）。 */
    fun restoreAiBeats() {
        val s = story.value ?: return
        writeChapterBeats(s.pendingChapterBeats, false, "恢复 AI 预排")
    }

    fun dismissError() { _error.value = null }

    /**
     * 生成失败弹窗「重试」：复用书架 / 章节列表同款 [StoryGenerationTaskManager.retryGeneration]（清错误 → SERIALIZING
     * → startGeneration），状态此刻必为 GENERATION_FAILED、转移合法性同书架路；重试仍按原定走向写——ST11 意图保留
     * （失败不清 requestedEndingType / rewriteInstruction）已在既有失败路生效。
     */
    fun retryGeneration() {
        _error.value = null
        story.value?.let { taskManager.retryGeneration(it) }
    }

    fun setReadingAnimations(enabled: Boolean) {
        readingProgressStore.setReadingAnimationsEnabled(enabled)
        _readingAnimationsEnabled.value = enabled
    }

    fun setFontSizeIndex(index: Int) {
        readingProgressStore.setFontSizeIndex(index)
        _fontSizeIndex.value = index
    }

    // ── 章节内滚动位置（委托 store；Compose 映射见 StoryScrollRestoreLogic）──

    fun saveScroll(chapterId: String, index: Int, offset: Int) {
        val sid = storyIdFlow.value ?: return
        readingProgressStore.saveScrollPosition(sid, chapterId, index, offset)
    }

    fun loadScroll(chapterId: String): StoryScrollPosition? {
        val sid = storyIdFlow.value ?: return null
        return readingProgressStore.loadScrollPosition(sid, chapterId)
    }

    private companion object {
        const val TAG = "StoryReader"
        // LLM 面向的选择标记（NATURAL_FLOW / SKIP_FOR_ENDING）已迁 story 包单源 StoryChoiceClassifier（J8·分层铁律）。
    }
}
