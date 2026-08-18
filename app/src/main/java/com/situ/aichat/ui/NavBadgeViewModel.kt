package com.situ.aichat.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.SettingsPreferences
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MomentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * 底部导航 Tab 未读角标数据源（nav-shell-2，对齐 iOS MainTabView 的 chats/moments .badge）。
 *
 * - [chatsUnread]：未读消息总数（SUM(cachedUnreadCount)，非归档）= iOS chats UnreadCountSync 的 reduce 求和。
 * - [momentsUnread]：未读朋友圈通知条数 = iOS moments MomentUnreadCountSync 的 count。
 *
 * 两者口径有意不同（和 vs 计数），对齐 iOS，勿统一。
 */
@HiltViewModel
class NavBadgeViewModel @Inject constructor(
    conversationRepo: ConversationRepository,
    momentRepo: MomentRepository,
    settings: SettingsPreferences,
) : ViewModel() {
    val chatsUnread: StateFlow<Int> =
        conversationRepo.observeTotalUnread()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val momentsUnread: StateFlow<Int> =
        momentRepo.observeUnreadNotificationCount()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** 悬浮底栏背景不透明度（过渡丝滑化·A1）；外观设置可调、即时生效。0.88=默认隐隐透·1.0=实色。 */
    val bottomNavOpacity: StateFlow<Float> =
        settings.bottomNavOpacity
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0.88f)
}
