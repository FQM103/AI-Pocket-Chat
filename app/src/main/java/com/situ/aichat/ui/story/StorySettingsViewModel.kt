package com.situ.aichat.ui.story

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.R
import com.situ.aichat.data.local.dao.UserStoryTemplateDao
import com.situ.aichat.data.local.entity.StoryCharacterRoleEntity
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.local.entity.UserStoryTemplateEntity
import com.situ.aichat.data.local.entity.resolvedConfigOrNull
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.CustomStoryPrompts
import com.situ.aichat.data.model.UserStoryTemplatePayload
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.ApiFunctionRouter
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.data.repository.StoryRepository
import com.situ.aichat.story.StoryArchiver
import com.situ.aichat.story.StoryDeleter
import com.situ.aichat.story.StoryGenerationService
import com.situ.aichat.story.StoryGenerationTaskManager
import com.situ.aichat.story.StoryOutlineOrchestrator
import com.situ.aichat.story.StoryPersonaDrafter
import com.situ.aichat.story.StoryReadingProgressStore
import com.situ.aichat.story.StoryStateTransitions
import com.situ.aichat.story.StoryStatus
import com.situ.aichat.story.StoryUnlockNotificationScheduler
import com.situ.aichat.story.StoryUpdateMode
import com.situ.aichat.story.StoryWorldInfoService
import com.situ.aichat.story.sortedStoryRoles
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * 书页可编辑字段草稿（1:1 iOS StorySettingsSheet 可写部分 + 图纸二 D2 的创作设定七字段
 * [genre]…[plotDirection]）：「离开书页即 [StorySettingsViewModel.persist]」的草稿心智，
 * 落库走 [StoryRepository.updateCreativeSettings]。
 *
 * 卷二 J3：**记忆四字段**（摘要/弧线/角色状态/伏笔）已移出草稿——改由档案 Tab 的编辑页即改即存单列写，
 * 同一功能不留两条写路（`updateStoryMemory` 已随之删除）。
 */
data class StorySettingsDraft(
    val updateMode: String,
    val unlockHour: Int,
    val unlockMinute: Int,
    val genre: String,
    val writingStyle: String,
    val narrativePerson: String,
    val chapterLengthPreference: Int,
    val chatInfluenceWeight: String,
    val worldSetting: String,
    val plotDirection: String,
)

/**
 * 书页 VM（故事二期卷二起续任·原故事设定 VM 原地改造）。只读基础信息 / 档案八节读值来自 [story] 热流；
 * 更新模式+解锁时间+创作设定七字段编进 [draft]，关闭时一次性 [persist] 定向写回（D1 安全）。
 * 续写/重开/恢复统一走 [StoryGenerationService] + [StoryGenerationTaskManager]；归档复用共用件 [StoryArchiver]。
 *
 * **保存时机**：persist/continueOrResume/restart 设计为 **suspend**，由屏幕在其协程内 await 完成后再导航返回，
 * 避免 viewModelScope 随路由 pop 取消而截断写入（项目无可注入 app-scope）。
 */
@HiltViewModel
class StorySettingsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: StoryRepository,
    private val generationService: StoryGenerationService,
    private val taskManager: StoryGenerationTaskManager,
    private val worldInfoService: StoryWorldInfoService,
    private val readingProgressStore: StoryReadingProgressStore,
    private val archiver: StoryArchiver,
    private val deleter: StoryDeleter,
    private val unlockScheduler: StoryUnlockNotificationScheduler,
    private val personaDrafter: StoryPersonaDrafter,
    private val outlineOrchestrator: StoryOutlineOrchestrator,
    functionRouter: ApiFunctionRouter,
    apiConfigs: ApiConfigRepository,
    private val settingsRepository: SettingsRepository,
    private val userStoryTemplateDao: UserStoryTemplateDao,
) : ViewModel() {

    private val storyId: String = savedStateHandle.get<String>("storyId").orEmpty()

    /** 故事创作现在解析得到配置吗（卷二「AI 起草」钮显隐·三上游同一条回退链，思考谓词同款现住 `StoryGlobalSettingsViewModel`）。 */
    val hasCreationConfig: StateFlow<Boolean> = combine(
        functionRouter.assignments,
        apiConfigs.observeAll(),
        apiConfigs.observeActive(),
    ) { assignments, all, active ->
        resolvedConfigOrNull(assignments[ApiFunction.STORY_CREATION], all, active) != null
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 全局设置（卷三 V1 只用 `sanitizedStoryCreationTemperature` 一项显示滑条当前值）。 */
    val appSettings: StateFlow<AppSettings> = settingsRepository.appSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    /** 「更新提醒」总闸（per-story SharedPrefs·默认开·ST7c）；关 = 不排该故事解锁通知。 */
    private val _reminderEnabled = MutableStateFlow(readingProgressStore.unlockReminderEnabled(storyId))
    val reminderEnabled: StateFlow<Boolean> = _reminderEnabled.asStateFlow()

    val story: StateFlow<StoryEntity?> =
        repository.observeStory(storyId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 参演角色（图纸二 D1：设定页可增删改 ⇒ 必须**可刷新**）。
     * 原实现是一次性 `flow { emit(...) }`，加人/删人后不重拉；改 [MutableStateFlow] + [reloadRoles]。
     */
    private val _roles = MutableStateFlow<List<StoryCharacterRoleEntity>>(emptyList())
    val roles: StateFlow<List<StoryCharacterRoleEntity>> = _roles.asStateFlow()

    /** 「世界观设定参与生成」开关行显隐：绑定角色都没挂书时隐藏（契约 §4）。 */
    val hasWorldBooks: StateFlow<Boolean> =
        roles.map { worldInfoService.hasWorldBooks(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _draft = MutableStateFlow<StorySettingsDraft?>(null)
    val draft: StateFlow<StorySettingsDraft?> = _draft.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var initialized = false
    private var originalUpdateMode = StoryUpdateMode.FREE

    /** 进屏时的题材快照：编辑成空白时回退它，绝不把空题材落库（题材是提示词多处引用的锚·图纸二 §3.1）。 */
    private var originalGenre = ""

    init {
        reloadRoles()
        // 故事首次加载时初始化草稿（只一次，之后独立于 story 更新，避免编辑被回流覆盖）。
        viewModelScope.launch {
            story.filterNotNull().collect { s ->
                if (!initialized) {
                    initialized = true
                    originalUpdateMode = s.updateMode
                    originalGenre = s.genre
                    _draft.value = StorySettingsDraft(
                        updateMode = s.updateMode,
                        unlockHour = s.unlockHour,
                        unlockMinute = s.unlockMinute,
                        genre = s.genre,
                        writingStyle = s.writingStyle,
                        narrativePerson = s.narrativePerson,
                        chapterLengthPreference = s.chapterLengthPreference,
                        chatInfluenceWeight = s.chatInfluenceWeight,
                        worldSetting = s.worldSetting.orEmpty(),
                        plotDirection = s.plotDirection.orEmpty(),
                    )
                }
            }
        }
    }

    fun updateDraft(transform: (StorySettingsDraft) -> StorySettingsDraft) = _draft.update { it?.let(transform) }

    /** 「世界观设定参与生成」开关：直接定向列写（≠草稿·即时生效·§4·ST7c）。 */
    fun setWorldInfoEnabled(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { repository.setWorldInfoEnabled(storyId, enabled) }
                .onFailure { _error.value = it.message; Log.e(TAG, "世界观开关写入失败", it) }
        }
    }

    /** 「更新提醒」总闸切换（ST7c·§6.5·per-story pref 即时落·下次排程/重排即生效）。 */
    fun setReminderEnabled(enabled: Boolean) {
        readingProgressStore.setUnlockReminderEnabled(storyId, enabled)
        _reminderEnabled.value = enabled
    }

    /**
     * 自定义提示词事后编辑（ST7c）：四字段 trim 后空→null，全空则清 JSON（走预设默认）；即时列写。
     * **必须 copy 合并，禁止新构造对象**（2026-07-30 修真 bug：新构造会把不在本面里的 `pacingPreference`
     * 一并写成 null）——照 [savePacing] 的「fresh 读现值 → copy 只替换本面字段」范式。
     */
    fun saveCustomPrompts(genreTechniques: String, writerIdentity: String, writingRules: String, bannedExpressions: String) {
        viewModelScope.launch {
            runCatching {
                val current = CustomStoryPrompts.decode(repository.getStory(storyId)?.customPromptsJson) ?: CustomStoryPrompts()
                val merged = current.copy(
                    genreTechniques = genreTechniques.trim().ifBlank { null },
                    writerIdentity = writerIdentity.trim().ifBlank { null },
                    writingRules = writingRules.trim().ifBlank { null },
                    bannedExpressions = bannedExpressions.trim().ifBlank { null },
                ) // ← pacingPreference 经 copy 保留
                val json = if (merged.hasAnyValue) CustomStoryPrompts.encode(merged) else null
                repository.updateCustomPrompts(storyId, json)
            }.onFailure { _error.value = it.message; Log.e(TAG, "自定义提示词写入失败", it) }
        }
    }

    /**
     * 节奏偏好事后编辑（卷三 V2）：**merge** 进现有 customPromptsJson——落库前 fresh 读一次只替换第四字段，
     * trim + 钳 [CustomStoryPrompts.PACING_MAX_CHARS] 字、空→null，全空清 JSON。与自定义提示词同一条写路。
     */
    fun savePacing(text: String) {
        viewModelScope.launch {
            runCatching {
                val current = CustomStoryPrompts.decode(repository.getStory(storyId)?.customPromptsJson)
                    ?: CustomStoryPrompts()
                val merged = current.copy(pacingPreference = CustomStoryPrompts.normalizedPacing(text))
                val json = if (merged.hasAnyValue) CustomStoryPrompts.encode(merged) else null
                repository.updateCustomPrompts(storyId, json)
            }.onFailure { _error.value = it.message; Log.e(TAG, "节奏偏好写入失败", it) }
        }
    }

    // ── 参演角色增删改（图纸二 D1）──

    /** 重拉角色列表（排序复用既有 [sortedStoryRoles]，与初始化同一条路径）。 */
    private fun reloadRoles() {
        viewModelScope.launch {
            runCatching { _roles.value = sortedStoryRoles(repository.getRoles(storyId)) }
                .onFailure { _error.value = it.message; Log.e(TAG, "读取参演角色失败", it) }
        }
    }

    /**
     * 新建或更新一个角色行（图纸二 D1）：走既有 `insertRoles` 的 REPLACE——**按主键覆盖即「更新单角色」**，
     * 新建则带默认 UUID 的新实体。写完重拉，列表即时刷新（下一章生成时 `getRoles` 现读，故改动下一章生效）。
     */
    fun saveRole(role: StoryCharacterRoleEntity) {
        viewModelScope.launch {
            runCatching { repository.insertRoles(listOf(role)) }
                .onFailure { _error.value = it.message; Log.e(TAG, "保存参演角色失败", it) }
                .onSuccess { reloadRoles() }
        }
    }

    /** 「私下反差」AI 起草（卷二·提案 §6.3）：拿弹层当前的名字与人设起草填进草稿栏，**不落库**；失败返回 null。 */
    suspend fun draftPersona(role: StoryCharacterRoleEntity, name: String, description: String): String? =
        personaDrafter.draft(storyId, role.characterId, name, description, System.currentTimeMillis())

    /** 从本书移出一个角色行（确认弹窗在 UI 侧）。已生成的章节不受影响，只是后续章节不再采集到 TA。 */
    fun deleteRole(roleId: String) {
        viewModelScope.launch {
            runCatching { repository.deleteRole(roleId) }
                .onFailure { _error.value = it.message; Log.e(TAG, "移出参演角色失败", it) }
                .onSuccess { reloadRoles() }
        }
    }

    // ── 两个格式开关（图纸二 D3·即时列写·下一章生效）──

    /**
     * 「章末给选项」开关（图纸二 D3）。写路**逐字照 [savePacing] 的 copy-merge 范式**：
     * fresh 读现值 → copy 只换本字段 → hasAnyValue 判空清 JSON，绝不新构造对象（否则丢忌口/节奏，2026-07-30 真 bug）。
     *
     * **关 = 写 null 而不是 false**（2026-08-05 默认翻转为关后，默认态=「JSON 里没这个键」，老书不被无谓改写）；
     * UI 的显示态一律读谓词 `CustomStoryPrompts.effectiveChapterChoices`（null 显示为关，显式 true 才显示为开）。
     */
    fun setChapterChoicesEnabled(enabled: Boolean) =
        mergeCustomPrompts("章末选项开关") { it.copy(chapterChoicesEnabled = if (enabled) true else null) }

    /** 「场景状态快照」开关（卷一第十字段·卷二补入口；口径与写路同 [setChapterChoicesEnabled]）。 */
    fun setSceneSnapshotEnabled(enabled: Boolean) =
        mergeCustomPrompts("场景快照开关") { it.copy(sceneSnapshotEnabled = if (enabled) null else false) }

    /** 两个开关共用的 copy-merge 写路（范式逐条同 [savePacing]）。既有两条写路有意不改走这里——本卷零碰。 */
    private fun mergeCustomPrompts(tag: String, transform: (CustomStoryPrompts) -> CustomStoryPrompts) {
        viewModelScope.launch {
            runCatching {
                val current = CustomStoryPrompts.decode(repository.getStory(storyId)?.customPromptsJson)
                    ?: CustomStoryPrompts()
                val merged = transform(current)
                repository.updateCustomPrompts(storyId, if (merged.hasAnyValue) CustomStoryPrompts.encode(merged) else null)
            }.onFailure { _error.value = it.message; Log.e(TAG, "$tag 写入失败", it) }
        }
    }

    fun dismissError() { _error.value = null }

    /** 头部「继续阅读」的目标章（§3.4）：点的那刻现查、不缓存；无章 → null 不导航；不做进度记忆跳转（§0.③）。 */
    suspend fun latestChapterId(): String? = runCatching {
        // 图纸卷二 §3.2：换元数据单行查询（章号倒序 LIMIT 1 已保证「最新」，原 maxByOrNull 随全表拉取一起删）。
        repository.getLatestChapterMeta(storyId)?.id
    }.onFailure { Log.e(TAG, "取最新章失败", it) }.getOrNull()

    // ── 我的模板（图纸四 §3.3）──

    private val _toastEvents = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    /** 一次性提示事件（string res id），屏幕 collect 转 Toast（照 `StoryBookshelfViewModel` 先例）。 */
    val toastEvents = _toastEvents.asSharedFlow()

    /** 已存的模板数——存入行据此在到顶时直接弹上限提示，不再打开命名弹窗（画面②）。 */
    val userTemplateCount: StateFlow<Int> = userStoryTemplateDao.observeAll()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * 存为「我的模板」（图纸四 §3.3）：**先落一次草稿**再 fresh 读整本书抽设定。
     *
     * 为什么先 persist：创作设定组的七个字段是「离开设定页才落库」的草稿，用户刚在这一页把题材/文风改了
     * 就点存模板，不先落库就会存进改动**之前**的设定，与弹窗文案「存下这本书的整套创作设定」直接矛盾
     * （图纸 §11 D-3）。persist 本就是幂等的定向列写，提前调一次无副作用。
     *
     * 上限在落库瞬间**再查一次**（[userTemplateCount] 是订阅流，可能陈旧；并发另一处存入也可能刚顶格）。
     */
    fun saveAsTemplate(name: String) {
        viewModelScope.launch {
            if (!persist()) return@launch // 草稿都没存住就别存模板（错误已由 persist 置好）
            runCatching {
                val story = repository.getStory(storyId) ?: return@runCatching
                if (userStoryTemplateDao.count() >= UserStoryTemplatePayload.MAX_USER_TEMPLATES) {
                    _toastEvents.tryEmit(R.string.story_save_template_limit)
                    return@runCatching
                }
                userStoryTemplateDao.insert(
                    UserStoryTemplateEntity(
                        uuid = UUID.randomUUID().toString(),
                        name = name.trim(),
                        createdAt = System.currentTimeMillis(),
                        payloadJson = UserStoryTemplatePayload.encode(UserStoryTemplatePayload.fromStory(story)),
                    ),
                )
                _toastEvents.tryEmit(R.string.story_template_saved_toast)
                Log.i(TAG, "存为我的模板 story=$storyId")
            }.onFailure { _error.value = it.message; Log.e(TAG, "存模板失败", it) }
        }
    }

    /**
     * 落库草稿（更新模式+解锁时间 + 追更→自由清解锁 + 创作设定七字段）。卷二 J3 起**只剩两条列级 UPDATE**
     * （记忆四字段已移出草稿，改由档案编辑页即改即存）；两条写不包事务，取舍见图纸二 §3.1。
     * @return 是否成功（失败置错误并返回 false，调用方据此决定是否仍返回，= iOS 保存失败不关闭）。
     */
    suspend fun persist(): Boolean {
        val d = _draft.value ?: return true
        return runCatching {
            repository.updateStorySettings(storyId, d.updateMode, d.unlockHour, d.unlockMinute)
            if (originalUpdateMode == StoryUpdateMode.CHASE && d.updateMode == StoryUpdateMode.FREE) {
                repository.clearChapterUnlocks(storyId)
                // 清列必须连撤已排闹钟（2026-08-04 相邻缺口）：否则到点仍发「已解锁」——全书早已解锁，纯冗余误导。
                unlockScheduler.cancelUnlocksForStory(storyId)
            }
            repository.updateCreativeSettings(
                storyId,
                // 题材空白 → 回退原值（绝不落空题材）；世界观/剧情方向空白归 null，照记忆四字段口径。
                d.genre.trim().ifBlank { originalGenre },
                d.writingStyle,
                d.narrativePerson,
                d.chapterLengthPreference,
                d.chatInfluenceWeight,
                d.worldSetting.ifBlank { null },
                d.plotDirection.ifBlank { null },
            )
            Log.i(TAG, "保存故事设定 $storyId updateMode=${d.updateMode}")
        }.onFailure { _error.value = it.message; Log.e(TAG, "保存设定失败", it) }.isSuccess
    }

    // ── 弧线大纲手动重排（图纸 2026-08-05 §3.4·书页档案卡「按最新剧情重排」）──

    private val _regenerating = MutableStateFlow(false)

    /** 重排执行中（行文案换「正在重排…」+ 菊花 + 禁点·照 `StoryFieldEditorViewModel._saving` 防重入范式）。 */
    val regenerating: StateFlow<Boolean> = _regenerating.asStateFlow()

    /**
     * 按最新剧情重排整份弧线大纲：**suspend·由屏幕在其协程 await**（persist 房规 KDoc :73-75）——
     * 不走 [StoryGenerationTaskManager]（那的语义 = 写一章：改 status、发通知、推灵动岛，重排一个都不适用）。
     *
     * 离屏 = 协程随屏幕作用域取消，[StoryOutlineOrchestrator] 的生成失败/取消一律不落库，
     * 下一章 `ensureOutline` 天然兜底 ⇒ **取消安全**（无半成品）。CancellationException 由 finally 复位态后如实上抛。
     */
    suspend fun regenerateOutline() {
        if (_regenerating.value) return
        _regenerating.value = true
        try {
            val fresh = repository.getStory(storyId) ?: return
            // 生成中不许重排（照 `StoryReaderViewModel.planFinale` 守卫范式·两路都查：库里状态 + 内存任务表）
            if (fresh.status == StoryStatus.GENERATING || taskManager.activeGenerations.value.containsKey(storyId)) {
                _toastEvents.tryEmit(R.string.story_archive_busy_toast)
                return
            }
            // 新弧从下一章开始（与 ensureOutline 换弧同一语义）；守卫与取值同一次 fresh 读。
            val chapterNumber = (fresh.cachedLatestChapterNumber ?: 0) + 1
            val result = outlineOrchestrator.regenerateArc(fresh, chapterNumber, System.currentTimeMillis()) {
                generationService.creationConfig()
            }
            val changed = result.storyOutline != fresh.storyOutline
            _toastEvents.tryEmit(
                if (changed) R.string.story_outline_regen_done else R.string.story_outline_regen_failed,
            )
            Log.i(TAG, "大纲重排 $storyId ch=$chapterNumber changed=$changed")
        } finally {
            _regenerating.value = false
        }
    }

    /** completed →「续写这个故事」（立即生成）；paused →「恢复连载」（仅复位 serializing，由自动更新接管）。 */
    suspend fun continueOrResume() {
        val s = story.value ?: return
        runCatching {
            val now = System.currentTimeMillis()
            when (s.status) {
                StoryStatus.COMPLETED -> {
                    generationService.continueStory(s, now)
                    val updated = repository.getStory(s.id) ?: s
                    taskManager.startGeneration(updated)
                    Log.i(TAG, "续写故事 ${s.id}")
                }
                StoryStatus.PAUSED -> {
                    StoryStateTransitions.check(s.status, StoryStatus.SERIALIZING, "StorySettingsViewModel.continueOrResume")
                    repository.updateStatus(s.id, StoryStatus.SERIALIZING, now)
                    Log.i(TAG, "恢复连载 ${s.id}")
                }
            }
        }.onFailure { _error.value = it.message; Log.e(TAG, "续写/恢复失败", it) }
    }

    // ── 归档 / 删除（卷二 J6·书页「连载与管理」组；书架长按菜单的既有两口零碰，双入口并存）──

    /** 「这本书没了」事件：屏幕 collect 后弹回书架（归档成功 / 删除成功各发一次）。 */
    private val _exitEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val exitEvents = _exitEvents.asSharedFlow()

    /** 归档本书（守卫与写库复用共用件 [StoryArchiver]，与书架长按「完结归档」同一个动作，两处绝不各写一遍）。 */
    fun archiveStory() {
        viewModelScope.launch {
            when (archiver.archive(storyId, System.currentTimeMillis())) {
                StoryArchiver.Result.ARCHIVED -> {
                    _toastEvents.tryEmit(R.string.story_archived_toast)
                    _exitEvents.tryEmit(Unit)
                }
                StoryArchiver.Result.BUSY -> _toastEvents.tryEmit(R.string.story_archive_busy_toast)
                StoryArchiver.Result.SKIPPED -> Unit
            }
        }
    }

    /** 删除本书（确认弹窗在 UI 侧）：级联删章节/角色 + 撤解锁闹钟（共用件 [StoryDeleter]），然后弹回书架。 */
    fun deleteStory() {
        viewModelScope.launch {
            runCatching { deleter.delete(storyId) }
                .onFailure { _error.value = it.message; Log.e(TAG, "删除故事失败", it) }
                .onSuccess { Log.i(TAG, "删除故事 $storyId"); _exitEvents.tryEmit(Unit) }
        }
    }

    /** completed →「重新开始新故事」：复制设定建新故事并生成首章，原故事保留。 */
    suspend fun restartStory() {
        val s = story.value ?: return
        runCatching {
            val newStory = generationService.restartStory(s, System.currentTimeMillis())
            taskManager.startGeneration(newStory)
            Log.i(TAG, "重开故事 ${s.id} → ${newStory.id}")
        }.onFailure { _error.value = it.message; Log.e(TAG, "重开失败", it) }
    }

    private companion object {
        const val TAG = "StorySettings"
    }
}
