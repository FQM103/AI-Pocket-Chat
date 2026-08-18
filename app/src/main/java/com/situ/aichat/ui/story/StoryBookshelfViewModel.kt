package com.situ.aichat.ui.story

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.repository.StoryRepository
import com.situ.aichat.story.StoryArchiver
import com.situ.aichat.story.StoryDeleter
import com.situ.aichat.story.StoryGenerationTaskManager
import com.situ.aichat.story.StoryReadingProgressLogic
import com.situ.aichat.story.StoryReadingProgressStore
import com.situ.aichat.story.StoryStateTransitions
import com.situ.aichat.story.StoryStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 书架续读导航目标（continueReading 异步算出 → 屏幕收事件后导航）。 */
sealed interface StoryResumeTarget {
    /** 直达阅读器某章。 */
    data class Reader(val chapterId: String) : StoryResumeTarget
    /** 无可读章 → 退回章节列表。 */
    data class ChapterList(val storyId: String) : StoryResumeTarget
}

/**
 * 书架（11.1h-2，1:1 iOS `StoryBookshelfView` 读侧 + 操作）。响应式观察全部故事（按 updatedAt 倒序），
 * 流式预览来自 [StoryGenerationTaskManager.activeGenerations]，续读章号来自 [StoryReadingProgressStore]。
 */
@HiltViewModel
class StoryBookshelfViewModel @Inject constructor(
    private val repository: StoryRepository,
    private val taskManager: StoryGenerationTaskManager,
    private val readingProgressStore: StoryReadingProgressStore,
    private val archiver: StoryArchiver,
    private val deleter: StoryDeleter,
) : ViewModel() {

    /** 全部故事（**轻列投影**·图纸卷二 §3.2）：卡片/菜单只读 29 保留列；写路径（生成/归档/续读）入口一律重读全列。 */
    val stories: StateFlow<List<StoryEntity>> =
        repository.observeStoriesLite().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 活跃生成进度（storyId → 进度），卡片据此显示流式预览 + 「生成中」。 */
    val activeGenerations: StateFlow<Map<String, StoryGenerationTaskManager.GenerationProgress>> =
        taskManager.activeGenerations

    /** 触发重读阅读进度（回前台 / 从阅读器返回时调用，因 SharedPreferences 非响应式）。 */
    private val refreshTick = MutableStateFlow(0)

    /** 各故事上次阅读章号（storyId → 章号），随 [stories] 或 [refreshTick] 重算。 */
    val lastReadChapterNumbers: StateFlow<Map<String, Int>> =
        combine(stories, refreshTick) { list, _ ->
            list.mapNotNull { story -> readingProgressStore.lastReadChapterNumber(story.id)?.let { story.id to it } }.toMap()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _resumeTarget = MutableSharedFlow<StoryResumeTarget>(extraBufferCapacity = 1)
    val resumeTarget = _resumeTarget.asSharedFlow()

    /** 从阅读器返回 / 回前台：重读续读章号（store 非响应式）。 */
    fun refreshReadingProgress() {
        refreshTick.value++
    }

    /** 暂停/恢复连载（1:1 iOS toggleStatus：仅 paused↔serializing 互切，其它状态不动）。定向写 status + updatedAt（D1 安全）。
     *  菜单入口已按状态隐藏无效项（[StoryCardLogic.menuActions]），else-return 只剩防御职责。 */
    fun togglePause(story: StoryEntity) {
        val newStatus = when (story.status) {
            StoryStatus.PAUSED -> StoryStatus.SERIALIZING
            StoryStatus.SERIALIZING -> StoryStatus.PAUSED
            else -> return
        }
        StoryStateTransitions.check(story.status, newStatus, "StoryBookshelfViewModel.togglePause")
        viewModelScope.launch { repository.updateStatus(story.id, newStatus, System.currentTimeMillis()) }
    }

    private val _toastEvents = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    /** 一次性提示事件（string res id），屏幕 collect 转 Toast（归档成功 / 生成中拒绝）。 */
    val toastEvents = _toastEvents.asSharedFlow()

    /**
     * 完结归档（ST10-4·ST11 起守卫与写库委托给共用的 [StoryArchiver]，本处只做「结果 → toast」映射）。
     * 语义零变：已完结幂等静默 / 生成中拒绝提示 / 其余标记完结 + 成功提示。
     * 与阅读器建议卡「就此完结」共用同一个 Archiver → 两个入口的守卫永远一致。
     */
    fun archiveStory(storyId: String) {
        viewModelScope.launch {
            when (archiver.archive(storyId, System.currentTimeMillis())) {
                StoryArchiver.Result.ARCHIVED -> _toastEvents.tryEmit(R.string.story_archived_toast)
                StoryArchiver.Result.BUSY -> _toastEvents.tryEmit(R.string.story_archive_busy_toast)
                StoryArchiver.Result.SKIPPED -> Unit
            }
        }
    }

    /** 删除故事（级联删章节/角色 + 撤解锁闹钟，共用件 [StoryDeleter]）。失败只打日志不闪退——卡片还在，用户可重试。 */
    fun deleteStory(storyId: String) {
        viewModelScope.launch {
            runCatching { deleter.delete(storyId) }
                .onFailure { Log.e(TAG, "删除故事失败", it) }
                .onSuccess { Log.i(TAG, "删除故事 $storyId") }
        }
    }

    /** 重新生成（生成失败的故事，= iOS 卡片「重新生成」）。 */
    fun retryGeneration(story: StoryEntity) = taskManager.retryGeneration(story)

    /**
     * 继续阅读（1:1 iOS onContinueReading）：算出首选续读章（待选 > 上次读 > 最新）→ 有则进阅读器、无则退回章节列表。
     * 异步拉章节，结果经 [resumeTarget] 发给屏幕导航。
     */
    fun continueReading(story: StoryEntity) {
        viewModelScope.launch {
            val chapters = repository.getChapterMetas(story.id)
            val resume = StoryReadingProgressLogic.preferredResumeChapter(
                sortedChapters = chapters,
                lastReadChapterId = readingProgressStore.lastReadChapterId(story.id),
                advancedFromChapterNumber = readingProgressStore.advancedFromChapterNumber(story.id),
            )
            _resumeTarget.emit(
                if (resume != null) StoryResumeTarget.Reader(resume.id) else StoryResumeTarget.ChapterList(story.id),
            )
        }
    }

    private companion object {
        const val TAG = "StoryBookshelf"
    }
}
