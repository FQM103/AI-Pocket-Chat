package com.situ.aichat.ui.chat

import android.content.Context
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.MeetingStatus
import com.situ.aichat.data.model.MeetingTimeGranularity
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.meeting.MeetingAppointmentStore
import com.situ.aichat.meeting.MeetingArrivalPolicy
import com.situ.aichat.offline.OfflineChatVisibility
import com.situ.aichat.offline.OfflineMarkerStartPayload
import com.situ.aichat.offline.OfflineMeetingService
import com.situ.aichat.offline.OfflineReturnPolicy
import com.situ.aichat.offline.OfflineSummaryRetryCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * 线下见面生命周期协作者——从 ChatViewModel 抽出（对齐 iOS ChatViewModel+ToolCalling/+Offline），方法体字节级不变。
 * 管用户面的见面动作：进页修脏状态/恢复弹窗、接受/拒绝邀约、主动发起/改成邀约、取消提示、续场、退出/异常恢复结束、退出后摘要重试。
 *
 * **引擎相关一律经回调注入**（保持本协作者不碰助手回合引擎内部）：
 * [runAssistantTurn] = VM runAssistantTurnForCurrentConversation；[serialize] = VM launchSerializedTurn（串行化防并发回合）；
 * [cancelActiveTurn] = VM `assistantTurnJob?.cancelAndJoin()`；[afterOfflineMemorySummary] = VM triggerMemorySummaryAfterOffline。
 * [recoveryPromptVisibleFlow]/[infoToastFlow] = VM 的 _offlineRecoveryPromptVisible/_infoToast（与日历/语音协作者同款 flow 注入）。
 * 自动恢复未答消息（autoRecoverUnansweredMessage）非线下专属（通用回合恢复），有意留 VM。
 */
internal class ChatOfflineController(
    private val scope: CoroutineScope,
    private val appContext: Context,
    private val conversationUuid: String,
    private val infoToastFlow: MutableStateFlow<String?>,
    private val recoveryPromptVisibleFlow: MutableStateFlow<Boolean>,
    private val messageRepo: MessageRepository,
    private val settingsRepo: SettingsRepository,
    private val offlineMeetingService: OfflineMeetingService,
    private val offlineSummaryRetryCoordinator: OfflineSummaryRetryCoordinator,
    private val meetingAppointmentStore: MeetingAppointmentStore,
    private val runAssistantTurn: suspend () -> Unit,
    private val serialize: (suspend () -> Unit) -> Unit,
    private val cancelActiveTurn: suspend () -> Unit,
    private val afterOfflineMemorySummary: suspend () -> Unit,
    // 见面结束成功分支排「余温消息」一次性 worker（§3.10·涟漪①）——VM 侧读 pending session + 排程（BackgroundScheduler 在 VM）。
    private val scheduleOfflineAfterglow: suspend () -> Unit,
) {

    // offline-1：点聊天流里的「线下见面结束」分隔条 → 只读见面回顾覆盖层（对齐 iOS OfflineMarkerCard onTapReview）。
    // 审计 S3 自 VM 只搬不改收编（本类=线下见面生命周期的家）。
    private val _offlineReviewInfo = MutableStateFlow<String?>(null)
    val offlineReviewInfo: StateFlow<String?> = _offlineReviewInfo.asStateFlow()
    private val _offlineReviewMessages = MutableStateFlow<List<MessageEntity>>(emptyList())
    val offlineReviewMessages: StateFlow<List<MessageEntity>> = _offlineReviewMessages.asStateFlow()

    // D3：恢复弹窗弹出时的离开时长（毫秒）——UI 按 OfflineReturnPolicy.isLongAbsence 切换文案；null=未知。
    private val _recoveryAwayMs = MutableStateFlow<Long?>(null)
    val recoveryAwayMs: StateFlow<Long?> = _recoveryAwayMs.asStateFlow()

    /** 打开某次见面的只读回顾（1:1 iOS OfflineReviewView.loadMessages 过滤，与 OfflineMeetingMemoryViewModel 同口径）。 */
    fun openOfflineReview(sessionId: String) {
        scope.launch {
            val all = messageRepo.offlineSessionMessages(conversationUuid, sessionId)
            val info = all.firstOrNull { MessageKind.fromRaw(it.messageKindRaw) == MessageKind.OFFLINE_MARKER_START }
                ?.let { OfflineMarkerStartPayload.parse(it.content) }
                ?.let { "${it.location} · ${it.activity}" }
                ?: ""
            _offlineReviewMessages.value = all.filter { m ->
                !OfflineChatVisibility.isHiddenFromReview(MessageKind.fromRaw(m.messageKindRaw)) // S8 单源
            }
            _offlineReviewInfo.value = info
        }
    }

    fun closeOfflineReview() {
        _offlineReviewInfo.value = null
        _offlineReviewMessages.value = emptyList()
    }

    /**
     * 进入会话时的线下处理（搬自 VM onChatAppear 的线下两段·字节级不变）：幂等修复线下脏状态 + 判定是否弹异常恢复提示；
     * 独立协程跑见面摘要重试链 ②重进对话层（前台兜底弹 Toast）。自动恢复未答消息由 VM 侧 autoRecoverUnansweredMessage 接着跑。
     */
    fun handleChatAppear() {
        scope.launch {
            offlineMeetingService.ensureStateConsistency(conversationUuid)
            if (offlineMeetingService.shouldShowRecoveryPrompt(conversationUuid)) {
                // D3：弹窗前记下离开时长——超长离开（>3h）弹窗文案引导「结束见面」；「继续见面」按它衔接时间流逝。
                _recoveryAwayMs.value = offlineMeetingService.offlineAwayMs(conversationUuid)
                recoveryPromptVisibleFlow.value = true
            }
        }
        // 见面摘要重试链 ②重进对话层（带退避判断，1:1 iOS retryPendingOfflineSummaryIfNeeded）。独立协程，
        // 不让 LLM 重试阻塞恢复弹窗判定；前台触发的兜底弹 Toast。
        scope.launch {
            if (offlineSummaryRetryCoordinator.retryOne(conversationUuid) == OfflineSummaryRetryCoordinator.RetryOutcome.FELL_BACK) {
                infoToastFlow.value = appContext.getString(R.string.offline_meeting_summary_fallback_notice)
            }
        }
    }

    /** 用户接受最近一张邀约卡（卡片「好呀」按钮）：置卡片 responded + 进入线下模式 + 触发 AI 开场。 */
    fun acceptOfflineInvite(messageUuid: String) {
        serialize {
            offlineMeetingService.markInviteResponded(messageUuid, "accepted")
            val sessionId = offlineMeetingService.acceptOfflineInvite(conversationUuid)
            if (sessionId != null) runAssistantTurn()
        }
    }

    /** 用户拒绝邀约卡（卡片「下次吧」按钮）：仅置卡片 responded=declined（不进入、不触发，无需串行化）。 */
    fun declineOfflineInvite(messageUuid: String) {
        scope.launch { offlineMeetingService.markInviteResponded(messageUuid, "declined") }
    }

    /** 用户在 + 菜单主动发起线下见面（填地点活动后）：进入线下模式 + 触发 AI 开场。 */
    fun startManualOfflineMeeting(location: String, activity: String) {
        serialize {
            val sessionId = offlineMeetingService.startManualOfflineMeeting(conversationUuid, location, activity)
            if (sessionId != null) runAssistantTurn()
        }
    }

    /**
     * 「未来约定见面」到点赴约（Phase 10·到点通知点击 / 在 App「出发赴约」按钮共用）：取约定真理源 →
     * **仍在宽限窗口内**才用其地点/活动/心事种子进入线下见面沉浸 → markHonored 链 sessionId → 触发 AI 开场
     * （同 [acceptOfflineInvite]）。过宽限 / 非 confirmed / 找不到 / 已在线下 → 不进（过宽限交 Phase 11 爽约扫描置
     * missed）。串行化防与在投递回合并发。
     */
    fun arriveAtAppointment(appointmentUuid: String) {
        scope.launch {
            val appt = meetingAppointmentStore.get(appointmentUuid) ?: return@launch
            if (MeetingStatus.fromRaw(appt.status) != MeetingStatus.CONFIRMED) return@launch
            val withinWindow = MeetingArrivalPolicy.isWithinArrivalWindow(
                appt.scheduledAt,
                MeetingTimeGranularity.fromRaw(appt.timeGranularity),
                System.currentTimeMillis(),
                ZoneId.systemDefault(),
            )
            if (!withinWindow) return@launch // 过宽限：不赴约，交 Phase 11 爽约扫描置 missed
            // 复核 MED：通知点击 / 按钮可能撞上在投递的回合——若用 serialize 会被「isSending 门」整体丢弃，
            // 用户已点赴约却没进沉浸、随后被 Phase 11 当爽约「怪你没来」。故**不经 serialize 的丢弃门**：先打断在投递
            // 的回合（同 finalizeOffline·防其残留落进刚进入的线下态），进入 + markHonored **必落**；开场回合再串行化。
            cancelActiveTurn()
            val sessionId =
                offlineMeetingService.startFromAppointment(conversationUuid, appt.location, appt.activity, appt.hiddenTensionSeed)
            if (sessionId != null) {
                meetingAppointmentStore.markHonored(appointmentUuid, sessionId)
                serialize { runAssistantTurn() } // 已打断·isSending 已清 → 开场回合不会被丢
            }
        }
    }

    /**
     * chat-ui-3「改成邀约」：把一条 AI 普通文本消息原地改写成线下邀约卡（1:1 iOS convertMessageToOfflineInvite）。
     * 纯 DB 改写、**不跑 LLM 一轮**（iOS 同样不跑；用户随后点卡片接受/拒绝走既有邀约流）。
     */
    fun convertMessageToOfflineInvite(messageUuid: String, location: String, activity: String) {
        scope.launch {
            offlineMeetingService.convertMessageToOfflineInvite(messageUuid, location, activity)
        }
    }

    /** 用户打开发起见面界面又取消：插用户不可见的取消提示 + 触发 AI 回复（1:1 iOS handleMeetingCancelHint）。 */
    fun handleMeetingCancelHint() {
        serialize {
            if (offlineMeetingService.insertMeetingCancelHint(conversationUuid)) runAssistantTurn()
        }
    }

    /** 用户点结束确认卡「再待一会儿」：置卡片 responded=continued + allow_end→false + 续场 hint + 触发 AI 回复。 */
    fun continueOfflineMeeting(endCardMessageUuid: String) {
        serialize {
            offlineMeetingService.markInviteResponded(endCardMessageUuid, "continued")
            if (offlineMeetingService.continueOfflineMeeting(conversationUuid)) runAssistantTurn()
        }
    }

    /** 用户主动结束见面（导航栏「结束」/结束确认卡「结束见面」）：打断在投递的回合 → finalize → 补常规记忆。 */
    fun exitOfflineMode() {
        scope.launch { finalizeOffline(OfflineMeetingService.ExitReason.USER_ENDED) }
    }

    /** 异常恢复弹窗「结束见面」：finalize(USER_ABORTED) + 隐藏弹窗。 */
    fun endMeetingFromRecovery() {
        scope.launch {
            finalizeOffline(OfflineMeetingService.ExitReason.USER_ABORTED)
            recoveryPromptVisibleFlow.value = false
        }
    }

    /** 点弹窗外 / 系统返回关闭恢复弹窗：仅隐藏、不触发（原「继续见面」的只 dismiss 语义留给被动关闭）。 */
    fun dismissOfflineRecoveryPrompt() {
        recoveryPromptVisibleFlow.value = false
    }

    /**
     * D3 时间感知重进（2026-07-07 拍板·取代旧「继续见面=只 dismiss」）：恢复弹窗点「继续见面」——
     * 插「归来」隐藏提示（带离开时长）+ 触发一拍让角色用 [时间：…] 级别的跳跃自然衔接。
     * 时长取不到（极端：弹窗期间见面被结束）→ 只关弹窗不触发。
     */
    fun continueMeetingFromRecovery() {
        recoveryPromptVisibleFlow.value = false
        serialize {
            val awayMs = offlineMeetingService.offlineAwayMs(conversationUuid) ?: return@serialize
            if (offlineMeetingService.insertReturnAfterAwayHint(
                    conversationUuid, OfflineReturnPolicy.awayMinutes(awayMs),
                )
            ) {
                runAssistantTurn()
            }
        }
    }

    /** 统一结束流程：打断在投递/流式的回合（= iOS finalize 前 cancel）→ 写离场标记/清状态 → 退出后补常规记忆。 */
    private suspend fun finalizeOffline(reason: OfflineMeetingService.ExitReason) {
        cancelActiveTurn() // 防 isInOfflineMode 清除后残留流式被当普通消息处理（1:1 iOS）
        if (offlineMeetingService.finalizeOfflineMode(conversationUuid, reason)) {
            // 线下期间常规摘要被 isInOfflineMode guard 跳过，退出后补一次让常规双轨判定接管（1:1 iOS）。
            afterOfflineMemorySummary()
            // 见面摘要重试链 ①前台即时层（1:1 iOS finalizeOfflineMode 末尾 async extractOfflineMeetingMemory）。
            triggerOfflineMeetingSummary()
            // 涟漪①：排「余温消息」一次性延迟 worker（recordOfflineExited 已设 pendingOfflineSummarySessionId·§3.10）。
            scheduleOfflineAfterglow()
        }
    }

    /**
     * 见面摘要重试链 ①前台即时层（退出后立即提取见面长期记忆，1:1 iOS finalizeOfflineMode 末尾的异步 extract）。
     * 独立协程不阻塞退出 UI；pending 已在 finalizeOfflineMode 落库，本协程即便被取消也由 ②③④⑤ 层兜底。前台兜底弹 Toast。
     */
    private fun triggerOfflineMeetingSummary() {
        scope.launch {
            if (offlineSummaryRetryCoordinator.retryOne(conversationUuid) == OfflineSummaryRetryCoordinator.RetryOutcome.FELL_BACK) {
                infoToastFlow.value = appContext.getString(R.string.offline_meeting_summary_fallback_notice)
            }
        }
    }
}
