package com.situ.aichat.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.situ.aichat.data.local.entity.NotificationWindowStatsEntity

/**
 * 「智能时机」窗口统计 DAO（P6.1e）。供 [com.situ.aichat.notification.NotificationLearningService] 读写、
 * [com.situ.aichat.notification.NotificationScheduler] 取来喂给 planner 打分。
 */
@Dao
interface NotificationWindowStatsDao {

    /** 某角色某分类的全部窗口统计（planner 打分 + isColdStart 判定用）。对齐 iOS chooseSchedule 的 fetch。 */
    @Query("SELECT * FROM notification_window_stats WHERE characterId = :characterId AND category = :category")
    suspend fun getForCategory(characterId: String, category: String): List<NotificationWindowStatsEntity>

    /** 精确取某窗口统计（fetchOrCreateStats）。 */
    @Query(
        "SELECT * FROM notification_window_stats WHERE characterId = :characterId AND category = :category AND windowId = :windowId LIMIT 1",
    )
    suspend fun getOne(characterId: String, category: String, windowId: String): NotificationWindowStatsEntity?

    @Upsert
    suspend fun upsert(stats: NotificationWindowStatsEntity)

    /** 删角色清理（1:1 iOS cleanupNotificationData 删 NotificationWindowStats by characterId）。 */
    @Query("DELETE FROM notification_window_stats WHERE characterId = :characterId")
    suspend fun deleteForCharacter(characterId: String)
}
