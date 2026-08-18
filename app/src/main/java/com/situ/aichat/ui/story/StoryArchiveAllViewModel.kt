package com.situ.aichat.ui.story

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.repository.StoryRepository
import com.situ.aichat.story.StoryDeleter
import com.situ.aichat.story.StoryStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 结局档案全览 VM（ST8·契约 §5·「全部 ›」入口）：响应式取全部已完结故事（按 updatedAt 倒序），供封面网格；
 * 外加长按删除（2026-08-04 卷）。不碰金额。
 */
@HiltViewModel
class StoryArchiveAllViewModel @Inject constructor(
    private val repository: StoryRepository,
    private val deleter: StoryDeleter,
) : ViewModel() {

    /** 已完结故事（**轻列投影**·图纸卷二 §3.2）：封面网格只读标题/题材/配色/结局徽章等保留列。 */
    val archived: StateFlow<List<StoryEntity>> =
        repository.observeStoriesLite()
            .map { list -> list.filter { it.status == StoryStatus.COMPLETED } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 删除已归档故事（级联删章节/角色 + 撤解锁闹钟，共用件 [StoryDeleter]）。失败只打日志不闪退——封面还在网格上，用户可重试。 */
    fun deleteStory(storyId: String) {
        viewModelScope.launch {
            runCatching { deleter.delete(storyId) }
                .onFailure { Log.e(TAG, "删除故事失败", it) }
                .onSuccess { Log.i(TAG, "删除故事 $storyId") }
        }
    }

    private companion object {
        const val TAG = "StoryArchiveAll"
    }
}
