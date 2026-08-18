package com.situ.aichat.notification

import android.content.Context
import android.util.Log
import com.situ.aichat.R
import com.situ.aichat.data.calendar.CalendarReader
import com.situ.aichat.data.local.dao.ConversationDao
import com.situ.aichat.data.local.dao.NotificationDeliveryDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.NotificationDeliveryRecordEntity
import com.situ.aichat.data.local.entity.NotificationDeliveryState
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 日历事件通知调度器（P6.3）。1:1 移植 iOS `CalendarNotificationService`：为设备日历近期事件注册「事件前
 * 30 分钟」的**角色口吻**本地通知，并落成聊天里的 assistant 消息。这是 iOS 在事件自带「系统 15 分钟提醒」
 * 之外**额外**发的一条提醒（与系统提醒并存）。
 *
 * **安卓平台映射**（非偏离）：
 * - 无 GMS/FCM → 用 6.1 的 [NotificationAlarmScheduler] 精确闹钟在「事件前 30min」触发（对齐 iOS
 *   `UNCalendarNotificationTrigger` 预登记即触发，App 被杀也弹）；文案是纯静态模板，无需到点现写。
 * - 落成聊天消息 → 直接复用 6.1d 基建：[NotificationPayload.deliveryIdentifier] 透传 → 发出即
 *   [PendingDeliveryStore] 标记 → 回前台 [StreakNotificationBridgeService] 据 deliveryIdentifier 物化
 *   （category="calendar"）。点击跳转复用 [Notifier] 深链 + [NotificationNavigator]。
 * - 无「列出待发闹钟」API → 用投递台账表当「待发列表」：刷新前查 [NotificationDeliveryDao.pendingScheduledForCategory]
 *   取尚未触发的旧日历通知，取消其闹钟并标 canceled（对齐 iOS removePendingNotificationRequests(prefix) +
 *   cancelStaleCalendarRecords）。
 *
 * **decision②（安卓新增）**：受「日历提醒方式」[CalendarReminderMode] 三态开关约束——仅 character/both 时调度
 * app 通知；system 模式（或日历集成关 / 无通知权限 / 无角色 / 无日历读权限）下只清旧不排新。
 *
 * **角色 / 会话选择**（对齐 iOS）：日历是全局的、非角色专属——iOS 用「连续聊天天数最高」的角色发
 * （[refreshForForeground]），落到其首选会话；聊天里 AI 操作日历后则用当前会话角色刷新（[refreshAllNotifications]）。
 */
@Singleton
class CalendarNotificationScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val calendarReader: CalendarReader,
    private val alarmScheduler: NotificationAlarmScheduler,
    private val deliveryDao: NotificationDeliveryDao,
    private val conversationDao: ConversationDao,
    private val characterRepository: CharacterRepository,
    private val settingsRepository: SettingsRepository,
) {

    /**
     * 前台 / 后台 / 开机通用入口：选「连续聊天天数最高」的角色刷新日历通知
     * （对齐 iOS `BackgroundTaskRunner.runCalendarNotificationRefresh`：streakCount 降序取第一）。
     * 无角色时仍以 null 走核心 → 清掉可能遗留的旧日历通知。
     */
    suspend fun refreshForForeground() {
        val character = characterRepository.observeAll().first().maxByOrNull { it.streakCount }
        refreshAllNotifications(character)
    }

    /**
     * 核心：刷新近期日历事件的「事件前 30min」角色通知（对齐 iOS `scheduleNotifications`）。
     * 先清旧（无条件），再按门控决定是否排新；[character] 为空 → 仅清旧。
     */
    suspend fun refreshAllNotifications(character: CharacterEntity?) {
        // 1. 清旧：取消未触发的日历闹钟 + 把对应未投递台账标 canceled（无论门控如何都执行，
        //    这样切到「仅系统提醒」/ 关日历集成时能撤掉已排的 app 通知）。
        cancelPendingCalendarNotifications()

        // 2. 门控（任一不满足 → 不排新）：日历集成总开关 / 提醒方式含角色 / 通知权限 / 有角色 / 日历读权限。
        val settings = settingsRepository.getAppSettings()
        if (!settings.calendarIntegrationEnabled) {
            Log.i(TAG, "日历提醒跳过·集成未开")
            return
        }
        if (!CalendarReminderMode.fromRaw(settings.calendarReminderMode).schedulesCharacterNotification) {
            Log.i(TAG, "日历提醒跳过·提醒方式仅系统")
            return
        }
        if (!NotificationPermission.isGranted(context)) {
            Log.w(TAG, "日历提醒跳过·无通知权限")
            return // 对齐 iOS authorizationStatus == .authorized 守卫
        }
        if (character == null) {
            Log.i(TAG, "日历提醒跳过·无角色")
            return
        }
        if (!calendarReader.hasPermission()) {
            Log.w(TAG, "日历提醒跳过·无日历读取权限")
            return
        }

        // 3. 读近期事件，筛出「触发时刻仍在未来」的，按上限截断。
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val events = calendarReader.upcomingRawEvents(now, zone)
        val due = upcomingNotifications(events, now, MINUTES_BEFORE).take(MAX_CALENDAR_NOTIFICATIONS)
        if (due.isEmpty()) {
            Log.d(TAG, "日历提醒跳过·无近期可排事件")
            return
        }

        // 4. 选承载会话（点击跳转 / 物化落点）：复用首选会话；无则留空，由 bridge 物化时据 characterId 兜底建预留会话
        //    （避免每次刷新都新建空预留会话）。
        val conversationUuid = StreakNotificationBridgeService
            .selectPreferredConversation(conversationDao.getByCharacter(character.uuid))?.uuid
        val baseUnread = conversationDao.totalUnread()

        due.forEachIndexed { index, (event, notifyAt) ->
            scheduleOne(character, event, notifyAt, conversationUuid, baseUnread + index + 1)
        }
        Log.i(TAG, "日历事件通知已排：${character.name}（${due.size} 条，前 $MINUTES_BEFORE 分钟）")
    }

    // MARK: - 单条注册

    private suspend fun scheduleOne(
        character: CharacterEntity,
        event: CalendarReader.CalEvent,
        notifyAt: Long,
        conversationUuid: String?,
        badgeCount: Int,
    ) {
        val title = event.title.trim().ifEmpty { context.getString(R.string.calendar_notif_untitled_event) }
        val body = CalendarNotificationContent.generate(context, title, MINUTES_BEFORE)
        val requestKey = "$CALENDAR_PREFIX${event.eventId}"
        val deliveryId = UUID.randomUUID().toString()

        val payload = NotificationPayload(
            notificationId = requestKey.hashCode(),
            title = character.name,
            body = body,
            conversationUuid = conversationUuid,
            characterId = character.uuid,
            deliveryIdentifier = deliveryId,
            category = CATEGORY,
            requestKey = requestKey,
            scheduledAtMillis = notifyAt,
            badgeCount = badgeCount,
            avatarPath = character.avatarPath, // 13.8·B3 MessagingStyle 头像气泡
        )
        alarmScheduler.scheduleExact(requestKey, notifyAt, payload)

        // 建台账（对齐 iOS NotificationDeliveryRecord：category="calendar"/windowID="calendar"/window 0..0）。
        // 发出时 PendingDeliveryStore 标记 → 回前台 bridge 据 deliveryIdentifier 回填 deliveredAt 并物化成助手消息。
        deliveryDao.upsert(
            NotificationDeliveryRecordEntity(
                characterId = character.uuid,
                category = CATEGORY,
                deliveryIdentifier = deliveryId,
                requestIdentifier = requestKey,
                conversationUuid = conversationUuid.orEmpty(),
                notificationBody = body,
                windowId = "calendar",
                windowStartMinute = 0,
                windowEndMinute = 0,
                scheduledAt = notifyAt,
                stateRaw = NotificationDeliveryState.SCHEDULED.raw,
            ),
        )
    }

    /**
     * 取消尚未触发的旧日历通知（对齐 iOS removePendingNotificationRequests(prefix) + cancelStaleCalendarRecords）：
     * 台账表里 category="calendar" 且 scheduled / 未投递 / 未物化 的记录 = 「还在待发列表里」的通知——
     * 取消其闹钟 + 标 canceled（已投递未物化的不动，仍会物化）。
     */
    private suspend fun cancelPendingCalendarNotifications() {
        val stale = deliveryDao.pendingScheduledForCategory(CATEGORY)
        for (record in stale) {
            alarmScheduler.cancel(record.requestIdentifier)
            deliveryDao.update(record.copy(stateRaw = NotificationDeliveryState.CANCELED.raw))
        }
    }

    companion object {
        private const val TAG = "CalendarNotifScheduler"

        /** 通知 / 台账标识前缀（对齐 iOS "aichat_calendar_"）。 */
        const val CALENDAR_PREFIX = "aichat_calendar_"

        /** 投递台账分类（bridge 物化 / 刷新清旧据此筛选）。 */
        const val CATEGORY = "calendar"

        /** 提前量（分钟）。对齐 iOS scheduleNotifications(minutesBefore: Int = 30)。 */
        const val MINUTES_BEFORE = 30

        /** app 侧最多同时挂多少条日历通知（窗口仅 ≤2 天，天然有限；对齐 iOS 64 条总上限的保守预留）。 */
        private const val MAX_CALENDAR_NOTIFICATIONS = 32

        /** 事件前 [minutesBefore] 分钟的触发时刻。纯函数。 */
        internal fun notifyTimeMillis(eventStartMillis: Long, minutesBefore: Int): Long =
            eventStartMillis - minutesBefore * 60_000L

        /**
         * 筛出「触发时刻仍在未来」的事件 + 其触发时刻（对齐 iOS `notifyDate > now` 守卫；保持事件原顺序 = 按开始时间升序）。
         * 纯函数，单测。
         */
        internal fun upcomingNotifications(
            events: List<CalendarReader.CalEvent>,
            now: Long,
            minutesBefore: Int,
        ): List<Pair<CalendarReader.CalEvent, Long>> =
            events.mapNotNull { e ->
                val notifyAt = notifyTimeMillis(e.begin, minutesBefore)
                if (notifyAt > now) e to notifyAt else null
            }
    }
}
