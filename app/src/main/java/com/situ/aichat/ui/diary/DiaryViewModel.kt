package com.situ.aichat.ui.diary

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.DiaryEntryWithComments
import com.situ.aichat.data.model.DiaryVisibility
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.DiaryRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.prompt.diary.DiaryApiMissingFlag
import com.situ.aichat.prompt.diary.DiaryCommentService
import com.situ.aichat.data.local.entity.MonthlyReviewEntity
import com.situ.aichat.prompt.diary.DiaryExchangeService
import com.situ.aichat.prompt.diary.MonthlyReviewService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 日记列表 VM（M07 7.1.4）。响应式观察全部日记（含评论，新→旧）——异步落地的自动日记 / AI 评论自动刷新列表，
 * 优于 iOS 手动 onAppear reload + 分页（日记体量小，全量加载无压力，图片仍按卡懒解码）。
 */
@HiltViewModel
class DiaryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val diaryRepository: DiaryRepository,
    private val commentService: DiaryCommentService,
    private val settingsRepository: SettingsRepository,
    private val exchangeService: DiaryExchangeService,
    private val monthlyReviewService: MonthlyReviewService,
    characterRepository: CharacterRepository,
) : ViewModel() {

    val entries: StateFlow<List<DiaryEntryWithComments>> =
        diaryRepository.observeAllWithComments()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** characterUuid → 角色（信笺卡署名 / 信封位·R4）。 */
    val charactersByUuid: StateFlow<Map<String, CharacterEntity>> =
        characterRepository.observeAll()
            .map { list -> list.associateBy { it.uuid } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** 回顾与统计（R5·纯本地推导：streak/篇数/字数/心情分布·O4 发布才算）。 */
    internal val insights: StateFlow<DiaryInsights.Stats> =
        entries.map { DiaryInsights.stats(it, java.time.LocalDate.now()) }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                DiaryInsights.Stats(0, 0, 0, emptyList()),
            )

    /** 那年今天（R5·纯本地）：往年同月同日的已发布日记（新→旧）。 */
    val onThisDay: StateFlow<List<DiaryEntryWithComments>> =
        entries.map { DiaryInsights.onThisDay(it, java.time.LocalDate.now()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** monthStartMillis → 月度回顾（R5·月分节头部 chip + 面板）。 */
    val monthlyReviews: StateFlow<Map<Long, MonthlyReviewEntity>> =
        diaryRepository.observeMonthlyReviews()
            .map { list -> list.associateBy { it.monthStartMillis } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _reviewGeneratingMonth = MutableStateFlow<Long?>(null)

    /** 正在生成回顾的月（monthStart·null=空闲）。 */
    val reviewGeneratingMonth: StateFlow<Long?> = _reviewGeneratingMonth.asStateFlow()

    private val _reviewFailedMonth = MutableStateFlow<Long?>(null)

    /** 上次生成失败的月（chip 显示「生成失败·重试」）。 */
    val reviewFailedMonth: StateFlow<Long?> = _reviewFailedMonth.asStateFlow()

    /** 手动生成某月回顾（R5·幂等：已有 → 服务直接返回 Exists，响应式 chip 立即翻面）。 */
    fun generateMonthlyReview(monthStartMillis: Long) {
        if (_reviewGeneratingMonth.value != null) return
        viewModelScope.launch {
            _reviewGeneratingMonth.value = monthStartMillis
            _reviewFailedMonth.value = null
            val result = monthlyReviewService.generateForMonth(monthStartMillis)
            _reviewFailedMonth.value = when (result) {
                is MonthlyReviewService.Result.Success, is MonthlyReviewService.Result.Exists -> null
                else -> monthStartMillis
            }
            _reviewGeneratingMonth.value = null
        }
    }

    private val _exchangeUi = MutableStateFlow(DiaryExchangeUiState())

    /** 信封位状态（R4·进页 + 数据变化都重算：发布开闸 / 信落地收位）。 */
    val exchangeUi: StateFlow<DiaryExchangeUiState> = _exchangeUi.asStateFlow()

    private val _justUnlockedUuid = MutableStateFlow<String?>(null)

    /** 刚拆开的信 uuid（celebrate 揭晓入场的一次性目标·R4）。 */
    val justUnlockedUuid: StateFlow<String?> = _justUnlockedUuid.asStateFlow()

    init {
        viewModelScope.launch {
            entries.collect {
                _exchangeUi.value = _exchangeUi.value.copy(state = exchangeService.stateForToday())
            }
        }
    }

    /** 拆开今天的信（R4 懒生成）。失败置 failed 供信封位显示「再试一次」。 */
    fun unlockExchange() {
        if (_exchangeUi.value.unlocking) return
        viewModelScope.launch {
            _exchangeUi.value = _exchangeUi.value.copy(unlocking = true, failed = false)
            when (val result = exchangeService.unlockToday()) {
                is DiaryExchangeService.UnlockResult.Success -> {
                    _justUnlockedUuid.value = result.entry.uuid
                    _exchangeUi.value = _exchangeUi.value.copy(unlocking = false, failed = false)
                }
                else -> _exchangeUi.value = _exchangeUi.value.copy(unlocking = false, failed = true)
            }
            _exchangeUi.value = _exchangeUi.value.copy(state = exchangeService.stateForToday())
        }
    }

    /** 自动日记因未配置 API 暂停（对齐 iOS apiMissing 横幅）。SharedPreferences 非响应式，进页时刷新。 */
    private val _apiMissing = MutableStateFlow(DiaryApiMissingFlag.get(context))
    val apiMissing: StateFlow<Boolean> = _apiMissing.asStateFlow()

    fun refreshApiMissing() {
        _apiMissing.value = DiaryApiMissingFlag.get(context)
    }

    /**
     * 标记日记本已读（diary-1，对齐 iOS markDiaryAsRead）：写 lastViewedDiaryDate=now。
     * 进/出日记列表都调，使开页期间新到的 AI 评论离开时也被清未读。
     */
    fun markDiaryAsRead() {
        viewModelScope.launch {
            settingsRepository.setLastViewedDiaryDate(System.currentTimeMillis())
        }
    }

    /** 删除日记：级联删评论 + 清磁盘图（Repository）+ 取消待执行的角色评论任务（对齐 iOS cancelPendingComments）。 */
    fun delete(uuid: String) {
        viewModelScope.launch {
            diaryRepository.delete(uuid)
            commentService.cancelComments(uuid)
        }
    }

    /**
     * 草稿一键发布（R3 评论区活化·列表卡快捷钮）：isDraft→false；openToAI 时走既有评论调度
     * （与撰写页「草稿→发布」同口径：ComposeDiaryViewModel.save 的 isTransitionToPublish 分支）。
     */
    fun publishDraft(uuid: String) {
        viewModelScope.launch {
            val entry = diaryRepository.getEntry(uuid) ?: return@launch
            if (!entry.isDraft) return@launch
            diaryRepository.upsert(entry.copy(isDraft = false))
            if (DiaryVisibility.fromRaw(entry.visibilityRaw) == DiaryVisibility.OPEN_TO_AI) {
                commentService.scheduleComments(uuid, settingsRepository.getAppSettings().diaryCommentDelay)
            }
        }
    }
}
