package com.situ.aichat.ui.moments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MomentNotificationEntity
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.MomentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 朋友圈互动通知列表（M06 7.2.8，对齐 iOS `MomentNotificationListView`）的 ViewModel。响应式观察**未读**通知
 * (≤200,新→旧；读后从 Flow 移除自然消失)。点开→标记已读 + 按 `postTimestamp` 定位帖子；帖已删则上抛 null
 * 由 UI 提示「已删除」（iOS markAsReadAndNavigate）。
 */
@HiltViewModel
class MomentNotificationViewModel @Inject constructor(
    private val momentRepo: MomentRepository,
    characterRepo: CharacterRepository,
) : ViewModel() {

    val notifications: StateFlow<List<MomentNotificationEntity>> =
        momentRepo.observeUnreadNotifications()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val characters: StateFlow<Map<String, CharacterEntity>> =
        characterRepo.observeAll()
            .map { list -> list.associateBy { it.uuid } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun markRead(id: Long) {
        viewModelScope.launch { momentRepo.markNotificationRead(id) }
    }

    fun markAllRead() {
        viewModelScope.launch { momentRepo.markAllNotificationsRead() }
    }

    /** 点开通知：标记已读 + 定位帖子（按存储的 postTimestamp 秒→毫秒）。[onResult] 收到 postUuid（null=帖已删）。 */
    fun openNotification(notification: MomentNotificationEntity, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            momentRepo.markNotificationRead(notification.id)
            val targetMillis = (notification.postTimestamp * 1000).toLong()
            onResult(momentRepo.findPostUuidNear(targetMillis))
        }
    }
}
