package com.situ.aichat.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.situ.aichat.data.local.entity.WorldBulletinEntity
import com.situ.aichat.data.local.entity.WorldLlmSpendEntity

/**
 * 开机小报 + LLM 预算台账数据访问（W5 图纸 §3.1 / §3.3）：小报 upsert / 单查 / 裁旧 + 花费台账读增裁旧。
 *
 * 小报按 epochDay 一天一行（[getByDay]/[upsert]），保留 7 天（[deleteBulletinsOlderThan]）；删角清空全部
 * （[deleteAllBulletins]，正文可能含其名）。花费台账（[spendCount]/[upsertSpend]）供 [com.situ.aichat.world.bulletin.WorldLlmBudget]
 * 事务化读增，30 天前旧行由预算器顺带清（[deleteSpendOlderThan]）。本 DAO 只做 CRUD。
 */
@Dao
interface WorldBulletinDao {

    // MARK: - 每日小报（一天一行·PK=epochDay）

    @Upsert
    suspend fun upsert(bulletin: WorldBulletinEntity)

    @Query("SELECT * FROM world_bulletin WHERE epochDay = :epochDay")
    suspend fun getByDay(epochDay: Long): WorldBulletinEntity?

    /** 裁旧：删早于 [threshold] 的小报（保留最近 7 天·图纸 §3.3）。 */
    @Query("DELETE FROM world_bulletin WHERE epochDay < :threshold")
    suspend fun deleteBulletinsOlderThan(threshold: Long)

    /** 删角：清空全部小报（正文可能含其名·下次结算重生成·图纸 §3.4）。 */
    @Query("DELETE FROM world_bulletin")
    suspend fun deleteAllBulletins()

    // MARK: - LLM 花费台账（每日各类目计数·预算硬顶）

    /** 当日某类目已消费次数（无行 → null·调用方按 0 处理）。 */
    @Query("SELECT count FROM world_llm_spend WHERE epochDay = :epochDay AND category = :category")
    suspend fun spendCount(epochDay: Long, category: String): Int?

    @Upsert
    suspend fun upsertSpend(spend: WorldLlmSpendEntity)

    /** 裁旧：删早于 [threshold] 的台账行（保留最近 30 天·图纸 §3.3）。 */
    @Query("DELETE FROM world_llm_spend WHERE epochDay < :threshold")
    suspend fun deleteSpendOlderThan(threshold: Long)
}
