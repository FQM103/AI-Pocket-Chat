package com.situ.aichat.share

import android.util.Log
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.quickreply.ListQuickReplyService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 分享给角色（Direct Share · C3，13.10a）的 App 级协调器（@Singleton）。
 *
 * **复用既有后台管线，不新建发送路径**：投到具体会话 = 复用 [ListQuickReplyService.send]（B5/B1 同款无头管线：
 * 落用户消息 → [com.situ.aichat.recovery.RecoveryClaimTracker] 占坑防双答 → 后台跑一轮角色 LLM 回复）。分享落地后
 * 由 [com.situ.aichat.notification.NotificationNavigator] 跳进会话，用户当场看角色回复（用户拍板「直接发出+角色马上回复」）。
 *
 * 通用分享（分享面板选了 App 自身而非某个角色行）暂存到 [pendingPickerText]：App 根（[com.situ.aichat.ui.AIChatApp]）
 * 观察到后跳联系人，[com.situ.aichat.ui.contacts.ContactsViewModel] 让用户点选收件角色再投递——**绝不静默丢弃**。
 */
@Singleton
class ShareTargetCoordinator @Inject constructor(
    private val listQuickReply: ListQuickReplyService,
    private val conversationRepo: ConversationRepository,
) {
    private val _pendingPickerText = MutableStateFlow<String?>(null)

    /** 通用分享待选收件角色的文本（非空 = 需跳联系人点选；点选/取消后置空）。 */
    val pendingPickerText: StateFlow<String?> = _pendingPickerText.asStateFlow()

    /**
     * Direct Share 命中具体角色（shortcut id = 会话 uuid）：后台落消息 + 跑一轮 LLM 回复（fire-and-forget）。
     *
     * **先校验会话仍存在再投递**（复核 MED）：`setLongLived(true)` 的快捷方式可能在会话删除后仍残留在系统分享面板缓存里
     * 被选中；直接 send 会让 [ListQuickReplyService] 落消息时撞 FK 约束、异常被吞 = 静默丢分享。返回是否真投出去——
     * 调用方据此决定跳会话（true）还是退回联系人点选（false，绝不静默丢弃）。
     */
    suspend fun deliverToConversation(conversationUuid: String, text: String): Boolean {
        if (conversationRepo.get(conversationUuid) == null) {
            Log.w(TAG, "投递失败：会话不存在 conv=$conversationUuid")
            return false
        }
        Log.d(TAG, "投会话 conv=$conversationUuid textLen=${text.length}")
        listQuickReply.send(conversationUuid, text)
        return true
    }

    /** 通用分享（未命中具体角色）：暂存文本，App 据此跳联系人点选收件角色。空白忽略。 */
    fun stashForPicker(text: String) {
        _pendingPickerText.value = text.trim().takeIf { it.isNotEmpty() }
        Log.d(TAG, "暂存待点选 textLen=${_pendingPickerText.value?.length ?: 0}")
    }

    /** 已点选收件角色 / 用户取消：清掉待选文本。 */
    fun consumePicker() {
        Log.d(TAG, "清待点选（已投递或用户取消）")
        _pendingPickerText.value = null
    }

    private companion object {
        const val TAG = "ShareTarget"
    }
}
