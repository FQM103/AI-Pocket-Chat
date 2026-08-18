package com.situ.aichat.ui.story

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.entity.StoryChapterEntity
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.repository.StoryRepository
import com.situ.aichat.story.StoryGenerationTaskManager
import com.situ.aichat.story.StoryReadingProgressStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 章节列表（11.1h-3，1:1 iOS `StoryChapterListView`）。观察故事（响应式状态/标题）+ 在 cachedChapterCount 变化时
 * 重载章节（= iOS `.task(id: story.cachedChapterCount)`）。续读/待选择由 [StoryReadingProgressLogic] 在屏幕侧算。
 */
@HiltViewModel
class StoryChapterListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: StoryRepository,
    private val taskManager: StoryGenerationTaskManager,
    private val readingProgressStore: StoryReadingProgressStore,
) : ViewModel() {

    private val storyId: String = savedStateHandle.get<String>("storyId").orEmpty()

    val story: StateFlow<StoryEntity?> =
        repository.observeStory(storyId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 书已不存在（2026-08-04 删除撤闹钟卷·深链兜底）：解锁通知深链可能落到已删的书——进屏一次性查证，
     * 缺席即置位，屏幕 collect 后 Toast + 体面退回（不停在假「生成中」空态）。用一次性 [StoryRepository.getStory]
     * 而非再订 observeStory：[story] 流的初值 null 与「真没这本书」分不开，且本文件对 observeStory
     * 只订阅一次（纪律见 [chapters] KDoc）；「在栈中被别处删掉」已由书页 onStoryGone 弹回书架覆盖，此处只管深链。
     */
    private val _storyMissing = MutableStateFlow(false)
    val storyMissing: StateFlow<Boolean> = _storyMissing.asStateFlow()

    init {
        viewModelScope.launch {
            if (repository.getStory(storyId) == null) {
                Log.w(TAG, "深链落到已删书 story=$storyId，体面退回")
                _storyMissing.value = true
            }
        }
    }

    /**
     * 章节升序列表（**元数据投影不含正文**·图纸卷二 §3.2），仅在 cachedChapterCount 变化时重载
     * （= iOS .task(id: cachedChapterCount)）。上游从 [story] 流派生——本文件对 `observeStory` 只订阅一次。
     */
    val chapters: StateFlow<List<StoryChapterEntity>> =
        story
            .map { it?.cachedChapterCount ?: 0 }
            .distinctUntilChanged()
            .map { repository.getChapterMetas(storyId) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 活跃生成的当前阶段文案（空态副标题用）；null = 没在生成。
     * 由 Boolean 升级为文案：空态从恒定的「正在生成中」变成跟着真实阶段走（构思/撰写/整理/归档）。
     */
    val generatingPhase: StateFlow<String?> =
        taskManager.activeGenerations.map { it[storyId]?.phase }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val refreshTick = MutableStateFlow(0)

    /** 上次阅读章节 id（续读选择用）；store 非响应式，[refreshReadingProgress] 触发重读。 */
    val lastReadChapterId: StateFlow<String?> =
        refreshTick.map { readingProgressStore.lastReadChapterId(storyId) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 「推进起点」章号（续读落点前移一章的判据·同上非响应式，随 [refreshTick] 重读）。 */
    val advancedFromChapterNumber: StateFlow<Int?> =
        refreshTick.map { readingProgressStore.advancedFromChapterNumber(storyId) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun refreshReadingProgress() {
        refreshTick.value++
    }

    /** 重新生成（空态「重新生成」按钮，生成失败时）。 */
    fun retryGeneration() {
        story.value?.let { taskManager.retryGeneration(it) }
    }

    private companion object {
        const val TAG = "StoryChapterList"
    }
}
