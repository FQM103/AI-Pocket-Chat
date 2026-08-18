package com.situ.aichat.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationDeletionService
import com.situ.aichat.data.repository.ConversationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 归档会话页（13.5 chat-ui-11，对齐 iOS `ArchivedConversationsView`）。
 * 行视图复用 [ChatRow]；左滑取消归档、右滑删除（走确认）。排序按 creationDate 倒序（1:1 iOS archived descriptor）。
 */
@HiltViewModel
class ArchivedChatsViewModel @Inject constructor(
    private val conversationRepo: ConversationRepository,
    characterRepo: CharacterRepository,
    private val deletionService: ConversationDeletionService,
) : ViewModel() {

    val rows: StateFlow<List<ChatListViewModel.Row>> =
        combine(conversationRepo.observeArchived(), characterRepo.observeAll()) { convs, chars ->
            val byUuid = chars.associateBy { it.uuid }
            convs.sortedByDescending { it.creationDate }
                .map { ChatListViewModel.Row(it, byUuid[it.characterUuid]) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun unarchive(conversationUuid: String) {
        viewModelScope.launch { conversationRepo.setArchived(conversationUuid, false) }
    }

    /** 删会话：走 app 级 [ConversationDeletionService]（先清磁盘媒体再删库行），不随本页离屏中断。 */
    fun delete(conversationUuid: String) {
        deletionService.delete(conversationUuid)
    }
}
