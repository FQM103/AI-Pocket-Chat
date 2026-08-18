package com.situ.aichat.notification

import android.util.Log
import com.situ.aichat.data.local.dao.NotificationDeliveryDao
import com.situ.aichat.data.local.dao.NotificationWindowStatsDao
import com.situ.aichat.data.local.entity.NotificationDeliveryRecordEntity
import com.situ.aichat.data.local.entity.NotificationDeliveryState
import com.situ.aichat.data.local.entity.NotificationWindowStatsEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 智能时机·学习反馈（P6.1e）。1:1 移植 iOS `NotificationLearningService` 的**持久化学习层**
 * （6.1c 已移植冷启动打分子集到 [NotificationTimePlanner]；本服务补上「窗口统计 + 送达/响应反馈」）。
 *
 * 闭环：
 * - **调度时** [recordScheduled]：插一条 scheduled 台账 + 更新该窗口的 `lastScheduledAt`（供重复惩罚）。
 * - **用户响应** [recordUserResponse]（= 用户主动发消息，对齐 iOS 在发送处调用）：找 2h 反馈窗内最近一条
 *   scheduled 台账 → 标 responded + 正反馈(smoothedScore↑)。
 * - **过期清算** [finalizeExpiredRecords]：超 2h 未响应 → 标 expired + 负反馈(smoothedScore↓)。
 * - **取消** [cancelScheduled]：随精确闹钟/Worker 撤销时把对应 scheduled 台账标 canceled（看会话撤 15min/重排/关角色）。
 *
 * 平滑分写回 [NotificationWindowStatsEntity.smoothedScore]，[NotificationScheduler] 取来喂给 planner 的
 * learnedScore 项，让发送时机随用户习惯自我调整。所有数值/平滑系数/反馈窗口逐字对齐 iOS。
 */
@Singleton
class NotificationLearningService @Inject constructor(
    private val deliveryDao: NotificationDeliveryDao,
    private val windowStatsDao: NotificationWindowStatsDao,
) {

    /**
     * 调度时记一条 scheduled 台账（对齐 iOS recordScheduledNotification）。deliveredAt 留空——发出后由
     * [StreakNotificationBridgeService] 据「待物化标记」回填。同时更新窗口的 lastScheduledAt（重复惩罚用）。
     */
    suspend fun recordScheduled(
        characterId: String,
        category: String,
        deliveryIdentifier: String,
        requestIdentifier: String,
        conversationUuid: String,
        body: String,
        windowId: String,
        windowStartMinute: Int,
        windowEndMinute: Int,
        scheduledAt: Long,
    ) {
        deliveryDao.upsert(
            NotificationDeliveryRecordEntity(
                characterId = characterId,
                category = category,
                deliveryIdentifier = deliveryIdentifier,
                requestIdentifier = requestIdentifier,
                conversationUuid = conversationUuid,
                notificationBody = body,
                windowId = windowId,
                windowStartMinute = windowStartMinute,
                windowEndMinute = windowEndMinute,
                scheduledAt = scheduledAt,
                stateRaw = NotificationDeliveryState.SCHEDULED.raw,
            ),
        )
        val stats = loadOrInitStats(characterId, category, windowId, windowStartMinute, windowEndMinute)
        windowStatsDao.upsert(stats.copy(lastScheduledAt = scheduledAt, updatedAt = now()))
    }

    /**
     * 随通知撤销把对应**未投递**(deliveredAt==null)的 scheduled 台账标 canceled（对齐 iOS
     * cancelScheduledNotifications）。requestIdentifier = `aichat_streak_{char}_{category}` 跨天稳定、非每次唯一，
     * 故必须用 deliveredAt==null 排除「已发出、仍在等 2h 响应/过期反馈」的台账——否则重排/撤回会误杀其反馈信号。
     * 等价 iOS「只取 pendingNotificationRequests 里的 id」（已发出的不在 pending 列表）。
     */
    suspend fun cancelScheduled(requestIdentifiers: List<String>) {
        if (requestIdentifiers.isEmpty()) return
        val idSet = requestIdentifiers.toSet()
        deliveryDao.scheduledRecords()
            .filter { shouldCancel(it, idSet) }
            .forEach { deliveryDao.update(it.copy(stateRaw = NotificationDeliveryState.CANCELED.raw)) }
    }

    /**
     * P1-25：删角色撤已弹通知用——该角色台账内全部 requestIdentifier（不限 state）。
     * 日历通知 key（aichat_calendar_<eventId>）不含 characterId，只能经台账 characterId 列定位。
     */
    suspend fun requestKeysFor(characterId: String): List<String> =
        deliveryDao.requestIdentifiersForCharacter(characterId)

    /**
     * 用户主动发消息 → 正反馈（对齐 iOS recordUserResponse）。先清算过期，再取 2h 反馈窗内、scheduledAt 最近的
     * 一条该角色 scheduled 台账 → 标 responded + applyOutcome(responded=true)。
     */
    suspend fun recordUserResponse(characterId: String, responseAt: Long) {
        finalizeExpiredRecords(responseAt)
        val record = selectResponseCandidate(deliveryDao.scheduledForCharacter(characterId), responseAt) ?: return
        applyOutcome(record, responded = true, outcomeAt = responseAt)
        Log.d(TAG, "用户响应反馈：char=$characterId window=${record.windowId} latency=${(responseAt - record.scheduledAt) / 1000}s")
    }

    /** 超 2h 未响应的 scheduled 台账 → expired + 负反馈（对齐 iOS finalizeExpiredRecords）。 */
    suspend fun finalizeExpiredRecords(now: Long = now()) {
        deliveryDao.scheduledRecords()
            .filter { now - it.scheduledAt > FEEDBACK_WINDOW_MS }
            .forEach { applyOutcome(it, responded = false, outcomeAt = now) }
    }

    /**
     * 写回一条台账的结局 + 窗口平滑分（对齐 iOS applyOutcome）。`statsApplied` 守卫防重复计入。
     * responded：smoothedScore = clamp(s·0.7 + 0.3)；expired：clamp(s·0.88)。clamp 到 [0.1, 0.95]。
     */
    private suspend fun applyOutcome(
        record: NotificationDeliveryRecordEntity,
        responded: Boolean,
        outcomeAt: Long,
    ) {
        if (record.statsApplied) return

        val stats = loadOrInitStats(
            record.characterId, record.category, record.windowId, record.windowStartMinute, record.windowEndMinute,
        )
        val updatedStats = if (responded) {
            stats.copy(
                scheduledCount = stats.scheduledCount + 1,
                responseCount = stats.responseCount + 1,
                lastRespondedAt = outcomeAt,
                smoothedScore = respondedScore(stats.smoothedScore),
                updatedAt = now(),
            )
        } else {
            stats.copy(
                scheduledCount = stats.scheduledCount + 1,
                smoothedScore = expiredScore(stats.smoothedScore),
                updatedAt = now(),
            )
        }
        windowStatsDao.upsert(updatedStats)

        val newState = if (responded) NotificationDeliveryState.RESPONDED else NotificationDeliveryState.EXPIRED
        deliveryDao.update(
            record.copy(
                stateRaw = newState.raw,
                respondedAt = if (responded) outcomeAt else record.respondedAt,
                responseLatency = if (responded) (outcomeAt - record.scheduledAt) / 1000.0 else record.responseLatency,
                statsApplied = true,
            ),
        )
    }

    /** 该角色该分类的窗口统计（供 [NotificationScheduler] 映射成 planner 的 WindowStat 喂打分）。 */
    suspend fun windowStatsFor(characterId: String, category: String): List<NotificationWindowStatsEntity> =
        windowStatsDao.getForCategory(characterId, category)

    private suspend fun loadOrInitStats(
        characterId: String,
        category: String,
        windowId: String,
        windowStartMinute: Int,
        windowEndMinute: Int,
    ): NotificationWindowStatsEntity =
        windowStatsDao.getOne(characterId, category, windowId)
            ?: NotificationWindowStatsEntity(
                characterId = characterId,
                category = category,
                windowId = windowId,
                windowStartMinute = windowStartMinute,
                windowEndMinute = windowEndMinute,
            )

    companion object {
        private const val TAG = "NotifLearning"

        /** 反馈窗口 = 2 小时（对齐 iOS feedbackWindow）。 */
        const val FEEDBACK_WINDOW_MS = 2L * 60 * 60 * 1000

        private const val RESPONSE_SMOOTHING = 0.3
        private const val EXPIRY_SMOOTHING = 0.12

        private fun now(): Long = System.currentTimeMillis()

        /** 平滑分钳到 [0.1, 0.95]（对齐 iOS clampScore）。纯函数，可测。 */
        internal fun clampScore(value: Double): Double = minOf(0.95, maxOf(0.1, value))

        /** 被响应 → 正反馈平滑（对齐 iOS：clamp(s·0.7 + 0.3)）。纯函数，可测。 */
        internal fun respondedScore(current: Double): Double =
            clampScore(current * (1 - RESPONSE_SMOOTHING) + RESPONSE_SMOOTHING)

        /** 过期未响应 → 负反馈平滑（对齐 iOS：clamp(s·0.88)）。纯函数，可测。 */
        internal fun expiredScore(current: Double): Double =
            clampScore(current * (1 - EXPIRY_SMOOTHING))

        /** 撤销时是否应把该 scheduled 台账标 canceled：requestIdentifier 命中 + 尚**未投递**。纯函数，可测。 */
        internal fun shouldCancel(record: NotificationDeliveryRecordEntity, requestIdSet: Set<String>): Boolean =
            record.requestIdentifier in requestIdSet && record.deliveredAt == null

        /**
         * 用户响应时选哪条 scheduled 台账（对齐 iOS recordUserResponse 候选）：scheduledAt ≤ 响应时刻、
         * 且在 2h 反馈窗内，取 scheduledAt 最近的一条。纯函数，可测。
         */
        internal fun selectResponseCandidate(
            records: List<NotificationDeliveryRecordEntity>,
            responseAt: Long,
        ): NotificationDeliveryRecordEntity? =
            records
                .filter { it.scheduledAt <= responseAt && responseAt - it.scheduledAt <= FEEDBACK_WINDOW_MS }
                .maxByOrNull { it.scheduledAt }
    }
}
