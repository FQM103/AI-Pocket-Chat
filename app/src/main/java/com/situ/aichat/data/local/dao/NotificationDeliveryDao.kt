package com.situ.aichat.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.situ.aichat.data.local.entity.NotificationDeliveryRecordEntity

/**
 * 通知投递台账 DAO（P6.1d）。供 [com.situ.aichat.notification.StreakNotificationBridgeService] 物化使用。
 */
@Dao
interface NotificationDeliveryDao {

    @Upsert
    suspend fun upsert(record: NotificationDeliveryRecordEntity)

    @Update
    suspend fun update(record: NotificationDeliveryRecordEntity)

    /** 按投递标识精确查询（物化去重 + 点击命中）。对齐 iOS `deliveryIdentifier == id` 谓词。 */
    @Query("SELECT * FROM notification_delivery_records WHERE deliveryIdentifier = :deliveryIdentifier LIMIT 1")
    suspend fun getByDeliveryIdentifier(deliveryIdentifier: String): NotificationDeliveryRecordEntity?

    /**
     * 待物化记录：已投递（[NotificationDeliveryRecordEntity.deliveredAt] 非空）且尚未物化（materializedAt 为空）。
     * 按 scheduledAt 升序（对齐 iOS materializeDeliveredNotifications 的 sortedRecords）。
     *
     * E2#1 修：**去掉 `stateRaw='scheduled'` 过滤**——物化资格（"通知已弹给用户但还没落进会话"）必须与学习
     * 结局（responded/expired）解耦。后台 finalizeExpiredRecords 可能先于回前台物化把已弹台账标 expired，
     * 旧查询按 state 过滤会让它永不物化（点击也只导航不补），属功能性数据丢失。物化只看 deliveredAt+materializedAt，
     * 故 expired/responded 的已弹未物化条同样应被捞起；materializedAt 在物化后置位，天然防重复物化。
     */
    @Query(
        """
        SELECT * FROM notification_delivery_records
        WHERE materializedAt IS NULL
          AND deliveredAt IS NOT NULL
        ORDER BY scheduledAt ASC
        """,
    )
    suspend fun pendingForMaterialization(): List<NotificationDeliveryRecordEntity>

    /** 全部仍处于 scheduled 状态的记录（P6.1e：过期清算用，对齐 iOS finalizeExpiredRecords 的 fetch）。 */
    @Query("SELECT * FROM notification_delivery_records WHERE stateRaw = 'scheduled'")
    suspend fun scheduledRecords(): List<NotificationDeliveryRecordEntity>

    /** 某角色仍处于 scheduled 的记录（P6.1e：用户响应反馈候选，对齐 iOS recordUserResponse 的 fetch）。 */
    @Query("SELECT * FROM notification_delivery_records WHERE characterId = :characterId AND stateRaw = 'scheduled'")
    suspend fun scheduledForCharacter(characterId: String): List<NotificationDeliveryRecordEntity>

    /** 删角色清理（1:1 iOS cleanupNotificationData 删 NotificationDeliveryRecord by characterId）。 */
    @Query("DELETE FROM notification_delivery_records WHERE characterId = :characterId")
    suspend fun deleteForCharacter(characterId: String)

    /**
     * P1-25：某角色台账全部 requestIdentifier（撤已弹通知用）。不限 state——已被 finalizeExpiredRecords
     * 标 expired 的已弹通知仍可能挂在通知栏（勿复用 [scheduledForCharacter]，它筛 scheduled 会漏）。
     */
    @Query("SELECT requestIdentifier FROM notification_delivery_records WHERE characterId = :characterId")
    suspend fun requestIdentifiersForCharacter(characterId: String): List<String>

    /**
     * 某分类「仍待触发」的台账（P6.3 日历刷新用）：state=scheduled 且**未投递**(deliveredAt 为空)、**未物化**。
     * 等价于 iOS 用 `pendingNotificationRequests()` 按前缀筛出的「尚未弹出」集合——刷新时取消其闹钟并标 canceled，
     * 而**已投递未物化**(deliveredAt 非空)的记录不在此列，仍会在回前台物化（对齐 iOS cancelStaleCalendarRecords
     * 只动还在 pending 列表里的请求）。
     */
    @Query(
        """
        SELECT * FROM notification_delivery_records
        WHERE category = :category
          AND stateRaw = 'scheduled'
          AND deliveredAt IS NULL
          AND materializedAt IS NULL
        """,
    )
    suspend fun pendingScheduledForCategory(category: String): List<NotificationDeliveryRecordEntity>

    /** 该角色在 [since] 之后已投递的排程主动通知条数（连发闸/降频闸共用；回连/余温不落本表=天然不计）。 */
    @Query("SELECT COUNT(*) FROM notification_delivery_records WHERE characterId = :characterId AND deliveredAt IS NOT NULL AND deliveredAt > :since")
    suspend fun countDeliveredSince(characterId: String, since: Long): Int

    /** 全表通知台账条数（性能采集规模数 `notificationRecords`·图纸 §3.2·只在 flush 时取一次）。 */
    @Query("SELECT COUNT(*) FROM notification_delivery_records")
    suspend fun countAll(): Int

    /** 该角色最近 [limit] 条已投递通知的正文（查重闸）。 */
    @Query("SELECT notificationBody FROM notification_delivery_records WHERE characterId = :characterId AND deliveredAt IS NOT NULL ORDER BY deliveredAt DESC LIMIT :limit")
    suspend fun recentDeliveredBodies(characterId: String, limit: Int): List<String>

    /** 后台投递成功即置位（R1 🔴-1）：三闸（连发/降频/查重）读数的数据源；守卫防覆盖既有值。 */
    @Query("UPDATE notification_delivery_records SET deliveredAt = :deliveredAt, notificationBody = :body WHERE deliveryIdentifier = :deliveryIdentifier AND deliveredAt IS NULL")
    suspend fun markDelivered(deliveryIdentifier: String, deliveredAt: Long, body: String)
}
