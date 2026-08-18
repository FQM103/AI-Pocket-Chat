package com.situ.aichat.ui.diary

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.DiaryEntryWithComments
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.DiaryRepository
import com.situ.aichat.prompt.diary.DiaryCommentService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 日记详情 VM（M07 7.1.4）。uuid 经 nav 参数 → [SavedStateHandle]。响应式观察单条日记（含评论），
 * 异步落地的 AI 评论会自动刷新打开的详情页。角色名/头像用于评论行显示。
 */
@HiltViewModel
class DiaryDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val diaryRepository: DiaryRepository,
    private val commentService: DiaryCommentService,
    characterRepository: CharacterRepository,
) : ViewModel() {

    private val uuid: String = savedStateHandle.get<String>(ARG_UUID).orEmpty()

    val entry: StateFlow<DiaryEntryWithComments?> =
        diaryRepository.observeEntryWithComments(uuid)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** characterUuid → 角色（评论行取名/头像）。 */
    val charactersByUuid: StateFlow<Map<String, CharacterEntity>> =
        characterRepository.observeAll()
            .map { list -> list.associateBy { it.uuid } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            diaryRepository.delete(uuid)
            commentService.cancelComments(uuid)
            onDone()
        }
    }

    /** 删单条评论（P0-14）。响应式 entry Flow 自动刷新列表+评论数，无需手动通知。 */
    fun deleteComment(id: String) {
        viewModelScope.launch { diaryRepository.deleteComment(id) }
    }

    /**
     * 回复某条角色评论（R3 评论区活化·每根限 1 轮）：落用户回复（isFromUser=true）→ 调度该角色短延迟回应
     * （WorkManager·响应式 Flow 自动把回应刷进打开的详情页）。1 轮上限 UI 由 canReply 门控、服务端幂等兜底。
     */
    fun replyToComment(rootCommentId: String, text: String) {
        val content = text.trim()
        if (content.isEmpty()) return
        viewModelScope.launch {
            diaryRepository.addComment(
                entryUuid = uuid,
                content = content,
                characterUuid = null,
                timestamp = System.currentTimeMillis(),
                parentCommentId = rootCommentId,
                isFromUser = true,
            )
            commentService.scheduleReply(uuid, rootCommentId)
        }
    }

    /**
     * 在 TA 的交换日记下给作者留言（R6-1·O5 翻案）：落用户顶层评论（用户根）→ 调度**信的作者**短延迟
     * 回应（复用 R3 scheduleReply 管线·worker 端按「用户根 × 交换日记」分派作者视角提示词·每条留言限 1 轮）。
     * 入口仅交换日记露出（UI 门控）；作者已删则服务端优雅早退。
     */
    fun commentOnEntry(text: String) {
        val content = text.trim()
        if (content.isEmpty()) return
        viewModelScope.launch {
            val note = diaryRepository.addComment(
                entryUuid = uuid,
                content = content,
                characterUuid = null,
                timestamp = System.currentTimeMillis(),
                parentCommentId = null,
                isFromUser = true,
            )
            commentService.scheduleReply(uuid, note.id)
        }
    }

    companion object {
        const val ARG_UUID = "uuid"
    }
}
