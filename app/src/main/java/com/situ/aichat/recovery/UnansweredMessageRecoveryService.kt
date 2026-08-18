package com.situ.aichat.recovery

import android.util.Log
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.notification.ActiveConversationStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 未答恢复后台扫描服务（10.2g，1:1 iOS `UnansweredMessageRecoveryService`）。冷启动 / 回前台时扫描所有「最后一条
 * 是用户消息」的对话，为未收到 AI 回复的对话**串行**补发请求（被国产 ROM 杀后台冲掉、或崩溃中断的回合）。
 *
 * **去重 / 幂等**（坑 16_offline §4#6）：
 *  1. `Mutex` 重入互斥（防回前台多次触发并发恢复）；`force=true` 排队等当前扫描结束后独占执行（供关忙碌开关时
 *     强制恢复，不被普通扫描饿死、也绝不与之并发——批3 3-3）。
 *  2. 跳过活跃（正在看的）对话——交给聊天页内的 [com.situ.aichat.ui.chat.ChatViewModel] autoRecoverUnansweredMessage。
 *  3. 串行恢复（一个完成再下一个，避免 API 并发）；生成前**复查** lastMessageRole 仍是 user（期间若有回复落库则跳过）。
 *  4. 生成前 [BusyReplyService.dropStaleHeldMessages] 清残留暂扣（防与新回复叠加成两组）。
 *  天然幂等：回复一旦落库，lastMessageRole 翻成 assistant，对话自然从 [conversationsAwaitingReply] 候选中消失。
 *
 * iOS 的 `ChatViewModelStore` 交接（半流式 VM 保活）安卓无对应也不需要：回复落库后聊天页 observeVisible 流自然呈现。
 */
@Singleton
class UnansweredMessageRecoveryService @Inject constructor(
    private val conversationRepo: ConversationRepository,
    private val characterRepo: CharacterRepository,
    private val settingsRepo: SettingsRepository,
    private val activeConversationStore: ActiveConversationStore,
    private val replyGenerator: RecoveryReplyGenerator,
    private val claimTracker: RecoveryClaimTracker,
) {

    private val scanMutex = Mutex()

    /**
     * 扫描并串行恢复未回复对话。
     * @param force true 时跳过所有 busy 检查（关「忙碌延迟」总开关时强制恢复，方案 A）。
     *
     * 批3 3-3：force 不再绕互斥并发跑——旧实现 force 与普通扫描并发、且先结束的一方清旗标可再放进第三路；
     * 改为 Mutex 排队：普通扫描 tryLock 失败即让位（原语义），force 等当前扫描结束后独占执行（「必须跑到」保留，双跑消除）。
     */
    suspend fun recoverIfNeeded(force: Boolean = false) {
        if (force) {
            scanMutex.withLock { scan(force = true) }
        } else {
            if (!scanMutex.tryLock()) return // 已在跑 → 跳过
            try {
                scan(force = false)
            } finally {
                scanMutex.unlock()
            }
        }
    }

    private suspend fun scan(force: Boolean) {
        val candidates = conversationRepo.conversationsAwaitingReply()
        if (candidates.isEmpty()) return
        val settings = settingsRepo.getAppSettings()
        Log.d(TAG, "未答恢复扫描：${candidates.size} 个候选 force=$force")
        for (convo in candidates) {
            // 用户正在看这个对话 → 由聊天页 autoRecoverUnansweredMessage 处理（1:1 iOS activeConversationID 跳过）。
            if (convo.uuid == activeConversationStore.activeConversationUuid) continue
            if (characterRepo.get(convo.characterUuid) == null) continue
            recoverConversation(convo.uuid)
        }
    }

    /** 恢复单个对话：复查仍待回复 → 清残留 held → 无头生成并落库（5min 超时兜底，对齐 iOS 300s deadline）。 */
    private suspend fun recoverConversation(conversationUuid: String) {
        // 复查最后一条仍是用户消息（期间可能已有回复落库 / 被活跃 VM 抢先），翻成 assistant 则跳过（1:1 iOS 二次确认）。
        val fresh = conversationRepo.get(conversationUuid) ?: return
        if (fresh.lastMessageRole != "user") return
        // 原子占坑：已被聊天页 autoRecover 占 → 让位，防同一条消息双答（复核 HIGH#1，补 iOS ChatViewModelStore 交接）。
        if (!claimTracker.tryBegin(conversationUuid)) return
        try {
            withTimeoutOrNull(RECOVERY_TIMEOUT_MS) {
                replyGenerator.generateAndPersist(conversationUuid)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "未答恢复单对话失败 conv=$conversationUuid: ${e.message}")
        } finally {
            claimTracker.end(conversationUuid)
        }
    }

    private companion object {
        private const val TAG = "UnansweredRecovery"
        private const val RECOVERY_TIMEOUT_MS = 5L * 60 * 1000 // 5 分钟（对齐 iOS deadline=300s）
    }
}
