package com.situ.aichat.world.bulletin

import androidx.room.withTransaction
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.WorldLlmSpendEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 世界 LLM **每日预算台账**（W5 图纸 §3.3 / 契约 §7.A 每日预算硬顶·决策 31）：事务化「读现值→判上限→写增量」，
 * 保证同日某类目 LLM 调用不超档位上限。**先扣后调**（调用方先 [tryConsume] 拿到许可再调 LLM）——即便 LLM 失败，
 * 额度已扣，下次重试再扣新额度：**宁少润一次不多花一分**，防失败重试烧钱。
 *
 * W8 起也承接**世界通知每日计数**（类目 `notif` / `notifOverflow`·图纸 §3.3 门 8）——复用同一台账 = 零迁移；
 * [spentCount] 为其溢出摘要提供只读计数。
 */
@Singleton
class WorldLlmBudget @Inject constructor(
    private val db: AppDatabase,
) {

    /**
     * 尝试为 [category] 在 [epochDay] 消费一次额度（上限 [cap]）：未达上限 → 扣 1 返回 true；已达 → false。
     * [cap] ≤ 0 恒 false（省档）。同事务顺带清理 30 天前的旧台账行。
     */
    suspend fun tryConsume(category: String, epochDay: Long, cap: Int): Boolean {
        if (cap <= 0) return false
        return db.withTransaction {
            val dao = db.worldBulletinDao()
            val current = dao.spendCount(epochDay, category) ?: 0
            if (current >= cap) {
                false
            } else {
                dao.upsertSpend(WorldLlmSpendEntity(epochDay = epochDay, category = category, count = current + 1))
                dao.deleteSpendOlderThan(epochDay - SPEND_RETENTION_DAYS) // 顺带裁旧
                true
            }
        }
    }

    /** 只读：[category] 在 [epochDay] 已消费次数（无行 → 0）。W8 门 8 溢出摘要读 `notifOverflow` 计数（图纸 §3.3）。 */
    suspend fun spentCount(category: String, epochDay: Long): Int =
        db.worldBulletinDao().spendCount(epochDay, category) ?: 0

    companion object {
        /** 台账保留天数（§9 禁改）。 */
        private const val SPEND_RETENTION_DAYS = 30
    }
}
