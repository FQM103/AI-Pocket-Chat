package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 1:1 移植 iOS `NotificationDeliveryRecord`（P6.1d）。一条「已投递的主动消息通知」的台账：
 * 记录它属于哪个角色 / 哪条会话、文案是什么、何时投递、是否已**物化**成会话里的 assistant 消息。
 *
 * **物化（materialize）= 把弹出的通知转成会话里真实的助手消息**（去重靠 [deliveryIdentifier] + [materializedAt]）。
 *
 * 安卓与 iOS 的平台差异（非偏离，是映射）：iOS 在「预登记」时就建本记录(state=scheduled)，靠系统的
 * 「已送达列表」回调；安卓无送达回调，故**发出即投递**——通知真正弹出时（[com.situ.aichat.notification.Notifier]）
 * 先落一条「待物化标记」（[com.situ.aichat.notification.PendingDeliveryStore]），App 回前台时
 * [com.situ.aichat.notification.StreakNotificationBridgeService] 据标记建本记录并物化。
 *
 * **列说明**：[windowId]/[windowStartMinute]/[windowEndMinute]、[respondedAt]/[responseLatency]、[statsApplied]
 * 是 6.1e 学习/评分/反馈才消费的列——6.1d **一次性把列建全**（一次 bump DB v4→v5），6.1e 不再为本表 bump。
 * 6.1d 自身只用到 deliveredAt / materializedAt / materializedMessageId / state，其余按 iOS 默认值或就近派生。
 */
@Entity(
    tableName = "notification_delivery_records",
    indices = [
        Index("deliveryIdentifier"),
        Index(value = ["characterId", "category"]),
        Index("scheduledAt"),
        Index("stateRaw"),
    ],
)
data class NotificationDeliveryRecordEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val characterId: String,
    val category: String,
    val deliveryIdentifier: String = "",
    val requestIdentifier: String,
    val conversationUuid: String,
    val notificationBody: String,
    val windowId: String,
    val windowStartMinute: Int,
    val windowEndMinute: Int,
    val scheduledAt: Long,
    val deliveredAt: Long? = null,
    val materializedAt: Long? = null,
    val materializedMessageId: String? = null,
    val respondedAt: Long? = null,
    /** iOS TimeInterval（秒）。6.1e 点击反馈用。 */
    val responseLatency: Double? = null,
    val stateRaw: String = NotificationDeliveryState.SCHEDULED.raw,
    val statsApplied: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val state: NotificationDeliveryState
        get() = NotificationDeliveryState.fromRaw(stateRaw)
}

/** 1:1 iOS `NotificationDeliveryState`。物化守卫只处理 [SCHEDULED]；其余状态由 6.1e 学习层写入。 */
enum class NotificationDeliveryState(val raw: String) {
    SCHEDULED("scheduled"),
    RESPONDED("responded"),
    EXPIRED("expired"),
    CANCELED("canceled");

    companion object {
        fun fromRaw(raw: String): NotificationDeliveryState =
            entries.firstOrNull { it.raw == raw } ?: SCHEDULED
    }
}
