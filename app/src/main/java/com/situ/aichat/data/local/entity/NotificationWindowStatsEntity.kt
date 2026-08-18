package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 1:1 移植 iOS `NotificationWindowStats`（P6.1e）。某角色某分类某时间窗的「智能时机」学习统计：
 * 累计调度/被响应次数 + 平滑分 [smoothedScore]（0.1–0.95），喂给 [com.situ.aichat.notification.NotificationTimePlanner]
 * 的窗口打分（learnedScore 项），让发送时机随「用户是否在该窗口附近主动聊天」自我调整。
 *
 * 反馈来源：用户在通知后 2 小时内主动发消息 → 该窗口正反馈(smoothedScore↑)；过期未响应 → 负反馈(↓)。
 * 由 [com.situ.aichat.notification.NotificationLearningService] 维护。
 */
@Entity(
    tableName = "notification_window_stats",
    indices = [Index(value = ["characterId", "category", "windowId"])],
)
data class NotificationWindowStatsEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val characterId: String,
    val category: String,
    val windowId: String,
    val windowStartMinute: Int,
    val windowEndMinute: Int,
    val scheduledCount: Int = 0,
    val responseCount: Int = 0,
    val smoothedScore: Double = 0.5,
    val lastScheduledAt: Long? = null,
    val lastRespondedAt: Long? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)
