package com.situ.aichat.ui.story

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.entity.StoryChapterEntity
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.repository.StoryRepository
import com.situ.aichat.story.StoryArchiveDigest
import com.situ.aichat.story.StoryArchiveDigestBuilder
import com.situ.aichat.story.StoryGenerationService
import com.situ.aichat.story.StoryGenerationTaskManager
import com.situ.aichat.story.StoryTxtExporter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 结局档案详情 UI 状态（故事 + 派生足迹/摘句 + 全量章节供 txt 导出）。 */
data class StoryArchiveUiState(
    val story: StoryEntity,
    val digest: StoryArchiveDigest,
    val chapters: List<StoryChapterEntity>,
)

/**
 * 结局档案卡 VM（ST8·契约 §5）：加载完结故事 + 全量章节 → 装配足迹/摘句；产出分享长图 / 全文 txt。
 *
 * 纯展示层：不改任何故事状态、不碰金额。分享/导出=用户显式点击才发生（隐私口径 §14）。
 */
@HiltViewModel
class StoryArchiveDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: StoryRepository,
    private val generationService: StoryGenerationService,
    private val taskManager: StoryGenerationTaskManager,
) : ViewModel() {

    private val storyId: String = savedStateHandle.get<String>("storyId").orEmpty()

    private val _uiState = MutableStateFlow<StoryArchiveUiState?>(null)
    val uiState: StateFlow<StoryArchiveUiState?> = _uiState.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    /** 「继续写」失败文案（断网/无 key 等·由屏幕呈现后 dismiss）。 */
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _continueWritingDone = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** 「继续写」成功事件 → 屏幕 toast + 退出档案详情（书已回在读区、后台正写下一章）。 */
    val continueWritingDone = _continueWritingDone.asSharedFlow()

    init {
        viewModelScope.launch {
            val story = repository.getStory(storyId) ?: return@launch
            val chapters = repository.getChapters(storyId)
            _uiState.value = StoryArchiveUiState(story, StoryArchiveDigestBuilder.build(story, chapters), chapters)
        }
    }

    /**
     * 渲染 + 落盘分享长图：Default 线程画 Bitmap，IO 线程写 png，返回 FileProvider content uri（失败 null）。
     * content 由屏幕用 stringResource 预解析后传入（本地化文案不进 VM）。
     */
    suspend fun renderShareImage(context: Context, content: StoryShareCardContent): Uri? =
        withContext(Dispatchers.Default) {
            val bitmap = StoryShareCardRenderer.render(context, content)
            withContext(Dispatchers.IO) { StoryShareImageWriter.write(context, bitmap) }
        }

    /** 装配全文 txt（章节已在内存·不二次拉库）；无数据 → null。落盘（SAF OutputStream）由屏幕在 IO 线程做。 */
    fun buildTxt(chapterHeaderFormat: String, choicePrefixFormat: String): String? {
        val s = _uiState.value ?: return null
        return StoryTxtExporter.build(s.story.title, s.chapters, chapterHeaderFormat, choicePrefixFormat)
    }

    /**
     * 「继续写这个故事」（ST11 §3.5·**存量已完结书的救济**）：判定链改动不回溯（状态是落库事实），
     * 那些被旧规则（AI 说完结就完结）误关进档案的书，从这儿一键请回在读区。
     *
     * 走 [StoryGenerationService.continueStory]（照 [StorySettingsViewModel.continueOrResume] 先例：
     * completed → serializing + 必要时扩上限 + 重置自动扩展次数）→ 后台起生成下一章 → 成功即 toast + 退出档案详情。
     */
    fun continueWriting() {
        val s = _uiState.value?.story ?: return
        viewModelScope.launch {
            runCatching {
                val now = System.currentTimeMillis()
                generationService.continueStory(s, now)
                val updated = repository.getStory(s.id) ?: s
                taskManager.startGeneration(updated)
                Log.i(TAG, "档案救济·继续写: ${s.id}")
            }
                .onSuccess { _continueWritingDone.tryEmit(Unit) }
                .onFailure { _error.value = it.message; Log.e(TAG, "档案救济·继续写失败", it) }
        }
    }

    fun dismissError() { _error.value = null }

    private companion object {
        const val TAG = "StoryArchiveDetail"
    }
}
