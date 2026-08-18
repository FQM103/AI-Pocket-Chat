package com.situ.aichat.ui.chat

import com.situ.aichat.data.calendar.CalendarAction
import com.situ.aichat.data.calendar.CalendarActionType
import com.situ.aichat.data.calendar.CalendarWriter
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.notification.CalendarNotificationScheduler
import com.situ.aichat.notification.CalendarReminderMode
import com.situ.aichat.tooling.PendingCalendarFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 日历操作成功提示数据（对齐 iOS CalendarToastData）。[isDelete] 决定醒目色（删除=红/错误色）。 */
data class CalendarToast(val text: String, val isDelete: Boolean)

/**
 * 聊天内「日历写入」协作者——从 ChatViewModel 抽出（对齐 iOS ChatViewModel+Calendar），方法体字节级不变。
 * 持有日历待确认队列 / 提示 / #E{n} 事件映射；由 ChatViewModel 实例化并委托。
 * [scope] = VM 的 viewModelScope；[errorFlow] = VM 的 _error（写同一错误通道，行为不变）。
 */
internal class ChatCalendarActionHandler(
    private val scope: CoroutineScope,
    private val errorFlow: MutableStateFlow<String?>,
    private val settingsRepo: SettingsRepository,
    private val calendarWriter: CalendarWriter,
    private val calendarNotificationScheduler: CalendarNotificationScheduler,
    private val conversationRepo: ConversationRepository,
    private val characterRepo: CharacterRepository,
    private val conversationUuid: String,
) {
    /** 待确认的日历操作队列（确认开关开启时）。UI 弹确认卡片处理队首。对齐 iOS pendingCalendarActions。 */
    private val _pendingCalendarActions = MutableStateFlow<List<CalendarAction>>(emptyList())
    val pendingCalendarActions: StateFlow<List<CalendarAction>> = _pendingCalendarActions.asStateFlow()

    /** 日历操作成功提示（自动消失），对齐 iOS activeCalendarToast。 */
    private val _calendarToast = MutableStateFlow<CalendarToast?>(null)
    val calendarToast: StateFlow<CalendarToast?> = _calendarToast.asStateFlow()
    private var calendarToastDismissJob: Job? = null

    /**
     * 本轮 prompt 注入的 `#E{n}` → 事件 id 映射（每个助手回合在 ChatViewModel.runAssistantTurn 刷新），供 [resolveEventRef]
     * 把 AI 引用的 #E1 解析回真实事件 id。仅在 Main 调度协程读写。对齐 iOS calendarEventRefMap。
     */
    var calendarEventRefMap: Map<String, Long> = emptyMap()

    /**
     * ② 待告知的「日历真失败」（per-会话·内存·一次性·TTL）。仅 [executeCalendarAction] 真失败路写、
     * [consumePendingFailure] 读清。进程死亡丢失可接受（非钱路）。
     */
    private var pendingFailure: PendingCalendarFailure? = null

    /**
     * 处理解析出的日历操作：事件类按确认开关弹卡片或直接执行；提醒类（安卓平台缺口）回「不支持」。
     * 对齐 iOS applyParsedCalendarActions（替换待确认队列）。
     */
    fun applyParsedCalendarActions(actions: List<CalendarAction>, settings: AppSettings) {
        if (!settings.calendarIntegrationEnabled || actions.isEmpty()) return
        val (events, reminders) = actions.partition { it.isEventAction }
        if (reminders.isNotEmpty()) {
            // 安卓无系统「提醒事项」(EKReminder) → 平台缺口，提示不支持（提示词已不广告提醒类，此为防御兜底）。
            errorFlow.value = "当前版本暂不支持提醒事项操作"
        }
        if (events.isEmpty()) return
        if (settings.calendarActionConfirmation) {
            _pendingCalendarActions.value = events
        } else {
            events.forEach { executeCalendarAction(it) }
        }
    }

    /** 确认并执行待处理队列的第一个日历操作。 */
    fun confirmPendingCalendarAction() {
        val list = _pendingCalendarActions.value
        val action = list.firstOrNull() ?: return
        _pendingCalendarActions.value = list.drop(1)
        executeCalendarAction(action)
    }

    /** 取消当前待处理的日历操作（若队列还有则显示下一个）。 */
    fun cancelPendingCalendarAction() {
        val list = _pendingCalendarActions.value
        if (list.isEmpty()) return
        _pendingCalendarActions.value = list.drop(1)
    }

    /** 执行单个日历事件操作（创建/修改/删除）；提醒类已在上游过滤，此处兜底回不支持。 */
    private fun executeCalendarAction(action: CalendarAction) {
        scope.launch {
            try {
                when (action.action) {
                    CalendarActionType.CREATE_EVENT -> {
                        val start = action.parsedStartMillis()
                        if (start == null) {
                            errorFlow.value = "无法解析事件日期"
                            recordPendingFailure(action, REASON_BAD_TIME) // ②：没认出时间 → 下轮请角色自然再问
                            return@launch
                        }
                        // P6.3：「仅角色提醒」模式不写系统 15min 提醒，改由 app 30min 角色通知负责（decision②）。
                        val addSystemReminder = CalendarReminderMode
                            .fromRaw(settingsRepo.getAppSettings().calendarReminderMode).writesSystemReminder
                        calendarWriter.createEvent(
                            title = action.title,
                            startMillis = start,
                            endMillis = action.parsedEndMillis(),
                            notes = action.notes,
                            location = action.location,
                            addSystemReminder = addSystemReminder,
                        )
                    }
                    CalendarActionType.UPDATE_EVENT -> {
                        val eventId = resolveEventRef(action.ref) ?: run {
                            // 仅「ref 非空但查不到那条日程」算用户可感失败 → 记；「缺编号」(ref 空=AI 内部错)不记。
                            if (!action.ref.isNullOrEmpty()) recordPendingFailure(action, REASON_EVENT_NOT_FOUND)
                            return@launch
                        }
                        calendarWriter.updateEvent(
                            eventId = eventId,
                            title = action.title.ifEmpty { null },
                            startMillis = action.parsedStartMillis(),
                            endMillis = action.parsedEndMillis(),
                            notes = action.notes,
                            location = action.location,
                        )
                    }
                    CalendarActionType.DELETE_EVENT -> {
                        val eventId = resolveEventRef(action.ref) ?: run {
                            if (!action.ref.isNullOrEmpty()) recordPendingFailure(action, REASON_EVENT_NOT_FOUND)
                            return@launch
                        }
                        calendarWriter.deleteEvent(eventId)
                    }
                    else -> {
                        errorFlow.value = "当前版本暂不支持该日历操作"
                        return@launch
                    }
                }
                showCalendarToast(action)
                // P6.3：日历变动后刷新 app 侧「事件前 30min」角色通知（对齐 iOS refreshAllNotifications(character:)）。
                refreshCalendarNotifications()
            } catch (e: Exception) {
                errorFlow.value = e.message ?: "日历操作失败"
                recordPendingFailure(action, REASON_WRITE_FAILED) // ②：写入手机日历真出错 → 下轮据实告知角色
            }
        }
    }

    /** 日历操作成功后，用当前会话角色刷新事件通知（对齐 iOS ChatViewModel+Calendar 的 refreshAllNotifications）。 */
    private suspend fun refreshCalendarNotifications() {
        val characterUuid = conversationRepo.get(conversationUuid)?.characterUuid ?: return
        val character = characterRepo.get(characterUuid) ?: return
        calendarNotificationScheduler.refreshAllNotifications(character)
    }

    /** 解析事件编号（#E1）为事件 id；缺编号 / 找不到时弹错误并返回 null。对齐 iOS resolveEventRef。 */
    private fun resolveEventRef(ref: String?): Long? {
        if (ref.isNullOrEmpty()) {
            errorFlow.value = "缺少事件编号"
            return null
        }
        val id = calendarEventRefMap[ref]
        if (id == null) {
            errorFlow.value = "找不到编号 $ref 对应的日历事件，可能已被删除"
            return null
        }
        return id
    }

    /** 显示日历操作成功提示（4 秒自动消失）。对齐 iOS showCalendarToast。 */
    private fun showCalendarToast(action: CalendarAction) {
        _calendarToast.value = CalendarToast(action.toastDescription(), action.isDeleteAction)
        calendarToastDismissJob?.cancel()
        calendarToastDismissJob = scope.launch {
            delay(4000)
            _calendarToast.value = null
        }
    }

    fun dismissCalendarToast() {
        calendarToastDismissJob?.cancel()
        _calendarToast.value = null
    }

    // — ② 执行失败回流（陪伴改良版）：真失败记一条 → 下一轮该会话装配一次性消费 → 注入陪伴口吻提示让角色据实找补 —

    /** 记一条「日历真失败·待下轮告知角色」（覆盖旧的=只提最近一次·非「待确认」）。仅 [executeCalendarAction] 真失败路调。 */
    private fun recordPendingFailure(action: CalendarAction, reason: String) {
        pendingFailure = PendingCalendarFailure(
            verb = action.actionVerb,
            title = action.title,
            reason = reason,
            recordedAtMillis = System.currentTimeMillis(),
        )
    }

    /**
     * 一次性消费待告知失败：取出即清（不复读、不唠叨）；超 [ttlMillis] 视为陈旧 → 清掉并返回 null（过期不再提）。
     * 由 [AssistantTurnEngine] 下一轮装配前调用，结果注入陪伴口吻系统提示。
     */
    fun consumePendingFailure(nowMillis: Long, ttlMillis: Long = FAILURE_TTL_MILLIS): PendingCalendarFailure? {
        val failure = pendingFailure ?: return null
        pendingFailure = null // 一次性：无论新鲜与否都清，绝不复读
        return failure.takeIf { isFailureFresh(it.recordedAtMillis, nowMillis, ttlMillis) }
    }

    companion object {
        /** 失败回流 TTL（用户拍板·约 10 分钟=同一次聊天内有效，隔太久不提）。 */
        const val FAILURE_TTL_MILLIS: Long = 10 * 60 * 1000L

        // 给角色看的**人话**原因（绝不漏 e.message 等技术错误）。
        const val REASON_BAD_TIME = "没认出你说的时间"
        const val REASON_EVENT_NOT_FOUND = "没找到那条日程（可能已经被删掉了）"
        const val REASON_WRITE_FAILED = "手机日历那边没能写进去"

        /** TTL 新鲜度判定（纯函数·便于单测）：now 落在 [recordedAt, recordedAt+ttl] 内为新鲜（含未来钟漂防御=下界 0）。 */
        internal fun isFailureFresh(recordedAtMillis: Long, nowMillis: Long, ttlMillis: Long): Boolean =
            nowMillis - recordedAtMillis in 0..ttlMillis
    }
}
