package com.situ.aichat.ui.world.quickchat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.dao.ScheduleDao
import com.situ.aichat.data.local.dao.WorldDao
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.ui.chat.MessagePreviewText
import com.situ.aichat.notification.NotificationScheduleRules
import com.situ.aichat.quickreply.ListQuickReplyService
import com.situ.aichat.world.WorldClock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/** 快聊目标（§3）：Known = 已认识角色（同一条正式会话）；Meet = 初遇（C7 落地·内存缓冲）。 */
sealed interface QuickChatTarget {
    data class Known(val characterUuid: String, val name: String, val statusLine: String, val conversationUuid: String) : QuickChatTarget
    data class Meet(val nativeId: String, val name: String, val placeName: String) : QuickChatTarget // C7
}

/** 初遇态（C7·§3）：开场/回应计数/确认卡/已认识。 */
data class FirstMeetState(val opening: Boolean, val replyCount: Int, val meetcardVisible: Boolean, val met: Boolean)

/**
 * 一条快聊消息（打开时刻之前的为 [isHistory]·历史尾巴灰淡）。[text] 恒为**可示人文本**——结构化卡（红包/礼物/
 * 通话/离场标记…）已经 [com.situ.aichat.ui.chat.MessagePreviewText] 转人话，绝不含 content 原始 JSON / LLM 指令。
 */
data class QcMessage(val id: String, val role: String, val text: String, val timestamp: Long, val isHistory: Boolean, val kind: MessageKind = MessageKind.PLAIN_TEXT)

/** 快聊弹窗状态（§3 全字段）。 */
data class WorldQuickChatUiState(
    val target: QuickChatTarget? = null, // null = 关闭
    val messages: List<QcMessage> = emptyList(),
    val typing: Boolean = false, // 回合在飞
    val failed: Boolean = false, // sendAndAwait/retryReply 返 false
    val busy: Boolean = false, // 当刻日程 isPhoneAvailable=false
    val firstMeet: FirstMeetState? = null, // C7
)

/**
 * 快聊弹窗状态机（W12 图纸 §2/§3·契约 §10）：**只骑现有会话管线**——绝不自建 LLM 管线。Known 态 = 同一条正式会话
 * （[ConversationRepository.getOrCreateForCharacter] 唯一源）的轻量入口：订阅会话尾部消息流 + `markRead`（未读单源天然去重·E16）；
 * 发送经 [ListQuickReplyService.sendAndAwait]（失败置 [WorldQuickChatUiState.failed]）；重试经 `retryReply`（**绝不重插用户消息**·E2）。
 * 忙碌感确定性部分（图纸自决②）= 忙碌条 + 回合前 [BUSY_DELAY_MS] 延迟（§3·R1）：**忙碌时用户消息即时落库**
 * （气泡经消息流即时回显·`insertUserMessage`），只有 TA 的回复延迟 [BUSY_DELAY_MS] 后才起——退屏丢消息窗口关闭。
 *
 * **异常防线（R1 🔴-2）**：`sendAndAwait`/`retryReply`/`confirmMeet` 一律 `runCatching` 包裹——底层 LLM completion
 * 无 try/catch，断网异常本会穿透 `viewModelScope` 崩溃；此处按 false/null 处理转失败态两行/no-op。
 *
 * Meet（初遇）态 + [WorldFirstMeetService] 桥接归 C7。
 */
@HiltViewModel
class WorldQuickChatViewModel @Inject constructor(
    private val quickReply: ListQuickReplyService,
    private val conversationRepo: ConversationRepository,
    private val messageRepo: MessageRepository,
    private val scheduleDao: ScheduleDao,
    private val worldDao: WorldDao,
    private val firstMeetService: com.situ.aichat.world.cast.WorldFirstMeetService,
) : ViewModel() {

    private val _ui = MutableStateFlow(WorldQuickChatUiState())
    val ui: StateFlow<WorldQuickChatUiState> = _ui.asStateFlow()

    private val _leaveToast = MutableSharedFlow<String>(extraBufferCapacity = 1)
    /** 初遇未确认关闭 → 早退 toast（发原住民名·宿主格式化 world_meet_leave_toast·E6）。 */
    val leaveToast: SharedFlow<String> = _leaveToast.asSharedFlow()

    private var meetMsgSeq = 0

    /** 协程分发器（默认 IO·测试可注 Unconfined 令回合同步）。 */
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    /** 忙碌回合前的延迟门（默认真 [BUSY_DELAY_MS] 延迟·测试注可控 suspend 精确验「延迟满才起回合」·§9 5000ms 锁死）。 */
    internal var busyGate: suspend () -> Unit = { delay(BUSY_DELAY_MS) }

    /**
     * 忙碌回合在途标（§3 在途去重）：回合在飞时后续 send 只 `insertUserMessage`、不起第二个回合。send 于主线程检查/置位，
     * 回合结束在 IO 分发器 finally 复位 → `@Volatile` 保跨线程可见（否则后一回合可能读到陈旧 true 而永不应答）。
     */
    @Volatile
    private var busyTurnInFlight = false

    private var messageJob: Job? = null

    /**
     * 打开就地快聊（Known·§3）：`getOrCreateForCharacter`（幂等·从未聊过先建·E17）→ `markRead`（E16）→ 判忙碌 →
     * 订阅会话尾部 30 条消息流（打开时刻之前的标 [QcMessage.isHistory]）。
     */
    fun openKnown(characterUuid: String, name: String, statusLine: String) = viewModelScope.launch(ioDispatcher) {
        val convUuid = conversationRepo.getOrCreateForCharacter(characterUuid, name)
        conversationRepo.markRead(convUuid)
        val busy = busyNow(characterUuid, System.currentTimeMillis())
        _ui.value = WorldQuickChatUiState(
            target = QuickChatTarget.Known(characterUuid, name, statusLine, convUuid),
            busy = busy,
        )
        subscribeMessages(convUuid, openedAtMs = System.currentTimeMillis())
    }

    private fun subscribeMessages(conversationUuid: String, openedAtMs: Long) {
        messageJob?.cancel()
        messageJob = viewModelScope.launch(ioDispatcher) {
            messageRepo.observeVisibleWindowed(conversationUuid, HISTORY_WINDOW).collect { rows ->
                _ui.update { s ->
                    // content 原文可能是结构化卡 JSON / 离场标记的 LLM 指令（isStructuredCard 类）——恒经
                    // MessagePreviewText 单源转人话；空串（耳语/入场标记类内部消息）整条不上屏（SQL 已滤大半·此为渲染兜底）。
                    s.copy(messages = rows.mapNotNull { row ->
                        val text = MessagePreviewText.forMessage(row)
                        if (text.isEmpty()) return@mapNotNull null
                        QcMessage(row.messageUUID, row.roleRaw, text, row.timestamp, isHistory = row.timestamp < openedAtMs, kind = MessageKind.fromRaw(row.messageKindRaw))
                    })
                }
                conversationRepo.markRead(conversationUuid) // E16：弹窗开着新回复落库 → 即时再 markRead
            }
        }
    }

    /**
     * 发送（Known·§3/E1/E3·R1 修订）：
     * - **非忙碌**：[ListQuickReplyService.sendAndAwait]（落用户消息 + 占坑 + 跑一轮）整体照旧。
     * - **忙碌**：**立即** [ListQuickReplyService.insertUserMessage]（用户气泡经消息流即时回显·退屏也不丢）→ [busyGate]
     *   （[BUSY_DELAY_MS]·期间不显 typing·忙碌条常显）→ typing → [ListQuickReplyService.retryReply]（只补 TA 的回复慢半拍）。
     *   **在途去重**：忙碌回合在飞时后续 send 只落消息、不起第二个回合（[busyTurnInFlight] 主线程标）。
     *
     * 失败判定见 [failedAfter]；三处 LLM 调用一律 `runCatching`（异常防线·R1 🔴-2）。
     */
    fun send(text: String) {
        if (text.isBlank()) return
        when (val target = _ui.value.target) {
            is QuickChatTarget.Known -> {
                val conv = target.conversationUuid
                if (_ui.value.busy) {
                    // 在途去重（主线程同步读写·无并发）：仅首发起回合，其余只落消息。
                    val startTurn = !busyTurnInFlight
                    if (startTurn) busyTurnInFlight = true
                    viewModelScope.launch(ioDispatcher) {
                        runCatching { quickReply.insertUserMessage(conv, text) } // 立即落用户消息（气泡即时回显·退屏不丢）
                            .onFailure { Log.w(TAG, "quick chat busy insert failed", it) }
                        if (!startTurn) return@launch // 已有回合在飞 → 只落消息（在途回合上下文天然覆盖后到消息）
                        try {
                            busyGate() // +5000ms（期间 typing=false·忙碌条常显）
                            _ui.update { it.copy(typing = true, failed = false) }
                            val ok = runCatching { quickReply.retryReply(conv) }
                                .getOrElse { Log.w(TAG, "quick chat busy reply failed", it); false }
                            _ui.update { it.copy(typing = false, failed = failedAfter(conv, ok)) }
                        } finally {
                            busyTurnInFlight = false
                        }
                    }
                } else {
                    viewModelScope.launch(ioDispatcher) {
                        _ui.update { it.copy(typing = true, failed = false) }
                        val ok = runCatching { quickReply.sendAndAwait(conv, text) }
                            .getOrElse { Log.w(TAG, "quick chat send failed", it); false }
                        _ui.update { it.copy(typing = false, failed = failedAfter(conv, ok)) }
                    }
                }
            }
            is QuickChatTarget.Meet -> viewModelScope.launch(ioDispatcher) {
                _ui.update { it.copy(messages = it.messages + meetMsg("user", text), typing = true) }
                val reply = firstMeetService.respond(target.nativeId, text)
                _ui.update { s ->
                    val msgs = if (reply != null) s.messages + meetMsg("assistant", reply) else s.messages
                    val fm = s.firstMeet
                    // meetcard 显示时机（§3 锁死）：任一 respond 完成即满足「三条件 / 达上限2 / 失败」之一 → 恒显（招募不被阻塞·E5）。
                    s.copy(messages = msgs, typing = false, firstMeet = fm?.copy(replyCount = (fm.replyCount) + (if (reply != null) 1 else 0), meetcardVisible = true))
                }
            }
            null -> Unit
        }
    }

    /**
     * 打开初遇（Meet·§3）：内存缓冲开场（服务生成/缓存·失败退兜底·E5）→ 显示开场气泡。未确认态有意易失（进程死=什么都没发生·E6/E8）。
     */
    fun openMeet(nativeId: String, name: String, placeName: String) {
        messageJob?.cancel(); messageJob = null
        _ui.value = WorldQuickChatUiState(
            target = QuickChatTarget.Meet(nativeId, name, placeName),
            firstMeet = FirstMeetState(opening = true, replyCount = 0, meetcardVisible = false, met = false),
        )
        viewModelScope.launch(ioDispatcher) {
            val opening = firstMeetService.startMeet(nativeId, name, placeName, System.currentTimeMillis())
            _ui.update { s ->
                if (s.target !is QuickChatTarget.Meet) s
                else s.copy(messages = listOf(meetMsg("assistant", opening)), firstMeet = s.firstMeet?.copy(opening = false))
            }
        }
    }

    /**
     * 确认认识（§3·E7/E8）：单事务 recruit + 建会话 + flush 缓冲消息（服务层）→ 转 Known 态 + 订阅真实会话。
     * 返 null（双击/不愿意）→ 幂等 no-op（met 态不回退）。
     */
    fun confirmMeet() {
        val target = _ui.value.target as? QuickChatTarget.Meet ?: return
        viewModelScope.launch(ioDispatcher) {
            // 异常防线（R1 🔴-2）：确认异常 = null（met 不置位·弹窗不崩·用户可重按）。
            val result = runCatching { firstMeetService.confirmMeet(target.nativeId, target.name, System.currentTimeMillis()) }
                .onFailure { Log.w(TAG, "quick chat confirm meet failed", it) }
                .getOrNull() ?: return@launch
            conversationRepo.markRead(result.conversationUuid)
            // 🔵-R2-2：转 Known 态时状态行用初遇地点（target.placeName·与 Meet 态 QcHead 状态行同源 → 头部两分支一并显示地点·
            // 无缝过渡·消除「只剩一颗金点无文字」）。placeName 恒非空（=站点 placeName）；理论空值降级由头部（QcHead）负责，不在本三文件改动范围。
            _ui.update { it.copy(target = QuickChatTarget.Known(result.characterUuid, target.name, target.placeName, result.conversationUuid), firstMeet = it.firstMeet?.copy(met = true)) }
            subscribeMessages(result.conversationUuid, openedAtMs = System.currentTimeMillis())
        }
    }

    private fun meetMsg(role: String, text: String) = QcMessage("meet-${meetMsgSeq++}", role, text, System.currentTimeMillis(), isHistory = false)

    /** 重试（Known·§3/E2）：`retryReply` 只重跑生成、**绝不重插用户消息**；成功清 [WorldQuickChatUiState.failed]（异常防线 R1 🔴-2）。 */
    fun retry() {
        val target = _ui.value.target as? QuickChatTarget.Known ?: return
        viewModelScope.launch(ioDispatcher) {
            _ui.update { it.copy(typing = true, failed = false) }
            val ok = runCatching { quickReply.retryReply(target.conversationUuid) }
                .getOrElse { Log.w(TAG, "quick chat retry failed", it); false }
            _ui.update { it.copy(typing = false, failed = failedAfter(target.conversationUuid, ok)) }
        }
    }

    /**
     * failed 判定（§3·R1 🔴-1 两路共用）：仅当回合 !ok **且**会话末条仍是 user 才算失败——末条已是 assistant =
     * 已有人应答（双发竞态/在途去重下另一回合已答），不误报失败条。
     */
    private suspend fun failedAfter(conversationUuid: String, ok: Boolean): Boolean =
        !ok && conversationRepo.get(conversationUuid)?.lastMessageRole == "user"

    /** 关闭（清订阅 + 复位）：初遇未确认关闭 → 放弃缓冲 + 发早退 toast（E6·零持久化·眼缘不变·重开重来）。 */
    fun close() {
        messageJob?.cancel(); messageJob = null
        val target = _ui.value.target
        val fm = _ui.value.firstMeet
        if (target is QuickChatTarget.Meet && fm != null && !fm.met) {
            viewModelScope.launch(ioDispatcher) { firstMeetService.abandon(target.nativeId) }
            _leaveToast.tryEmit(target.name)
        }
        _ui.value = WorldQuickChatUiState()
    }

    /** 当刻忙碌（§3·复用 9d 站位同源日程 + isPhoneAvailable·世界时区·无当前事件/无日程 → false）。 */
    private suspend fun busyNow(characterUuid: String, nowMs: Long): Boolean {
        val zone = WorldClock.resolveZone(worldDao.getState()?.userTimezoneId)
        val dayStart = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        val schedule = scheduleDao.scheduleFor(characterUuid, dayStart) ?: return false
        val event = NotificationScheduleRules.currentEvent(scheduleDao.eventsForSchedule(schedule.uuid), nowMs) ?: return false
        return !event.isPhoneAvailable
    }

    override fun onCleared() { messageJob?.cancel() }

    companion object {
        private const val TAG = "WorldQuickChat"

        /** 会话尾部窗口条数（§3）。 */
        private const val HISTORY_WINDOW = 30

        /** 忙碌回合延迟（图纸自决②·§3/§9 锁死 5000ms）。 */
        internal const val BUSY_DELAY_MS = 5000L
    }
}
