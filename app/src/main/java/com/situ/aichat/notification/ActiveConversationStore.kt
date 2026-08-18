package com.situ.aichat.notification

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 当前正在查看的会话 uuid（P6.1d）。1:1 对齐 iOS `NotificationService.activeConversationID`：
 * 物化通知时据此判断——若用户正看着该会话则 markRead（不计未读），否则未读数 +1。
 *
 * 由 [com.situ.aichat.ui.chat.ChatViewModel] 进入会话时写、离开时清。进程内单例、跨组件共享。
 */
@Singleton
class ActiveConversationStore @Inject constructor() {
    @Volatile
    var activeConversationUuid: String? = null
}
