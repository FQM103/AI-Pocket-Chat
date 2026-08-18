package com.situ.aichat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.situ.aichat.data.local.entity.WorldCityLoreEntity
import com.situ.aichat.data.local.entity.WorldDiscoveryEntity
import com.situ.aichat.data.local.entity.WorldEventEntity
import com.situ.aichat.data.local.entity.WorldStateEntity
import com.situ.aichat.data.local.entity.WorldTravelEntity
import kotlinx.coroutines.flow.Flow

/**
 * 世界核心数据访问（契约 `FABLE5_WORLD_SYSTEM_PROPOSAL.md` §5 / W1 图纸 §3）：单行状态 / 在途旅行 /
 * 世界事件 / 首访风物志 / 发现记录。关系边/事件另见 [WorldSocialDao]，原住民态见 [WorldNativeDao]。
 * 本 DAO 只做 CRUD，业务逻辑（结算/生成/通知）属 W2+。命名照 [WorldBookDao] 风格。
 */
@Dao
interface WorldDao {

    // MARK: - 世界状态（恒单行 id=1）

    @Upsert
    suspend fun upsertState(state: WorldStateEntity)

    /**
     * 建世盲写窗防覆盖（W14 图纸 §3.1·种子竞态修的地基）：**已存 id=1 行 → IGNORE 不覆盖**（返回 -1）。
     * 与 [upsertState]（覆盖语义·备份恢复本意）分工——[WorldBootstrap] 首建走此，令导入落的备份行不被本机新种子换掉。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStateIfAbsent(state: WorldStateEntity): Long

    @Query("SELECT * FROM world_state WHERE id = 1")
    suspend fun getState(): WorldStateEntity?

    @Query("SELECT * FROM world_state WHERE id = 1")
    fun observeState(): Flow<WorldStateEntity?>

    /**
     * 懒结算单调锚推进——**只进不退在 SQL 层保证**（`MAX(现值, :at)`）：设备时间往回调（:at < 现值）→ 锚不动，
     * 世界冻结（契约 §7 决策）；往前 → 快进。天然幂等可重放。
     */
    @Query("UPDATE world_state SET lastSettledAt = MAX(lastSettledAt, :at) WHERE id = 1")
    suspend fun advanceSettledAt(at: Long)

    /** 用户时区（W13 首启/设置写·null = 跟随设备·定点 UPDATE 不碰其余列）。 */
    @Query("UPDATE world_state SET userTimezoneId = :zoneId WHERE id = 1")
    suspend fun updateUserTimezone(zoneId: String?)

    // MARK: - 在途旅行（一 owner 一行·PK=ownerId）

    @Upsert
    suspend fun upsertTravel(travel: WorldTravelEntity)

    @Query("SELECT * FROM world_travel WHERE ownerId = :ownerId")
    suspend fun getTravel(ownerId: String): WorldTravelEntity?

    @Query("SELECT * FROM world_travel")
    suspend fun getAllTravels(): List<WorldTravelEntity>

    @Query("SELECT * FROM world_travel")
    fun observeTravels(): Flow<List<WorldTravelEntity>>

    /** 删角色 / 到达结束 → 清该 owner 在途行（删角色清理走仓库层事务）。 */
    @Query("DELETE FROM world_travel WHERE ownerId = :ownerId")
    suspend fun deleteTravel(ownerId: String)

    // MARK: - 世界事件（须被记住/通知/小报消费）

    @Upsert
    suspend fun upsertEvent(event: WorldEventEntity)

    @Upsert
    suspend fun upsertEvents(events: List<WorldEventEntity>)

    @Query("SELECT * FROM world_event ORDER BY happenedAt DESC")
    suspend fun getAllEvents(): List<WorldEventEntity>

    @Query("SELECT * FROM world_event ORDER BY happenedAt DESC")
    fun observeEvents(): Flow<List<WorldEventEntity>>

    /** 未被小报消费的事件（seenAt IS NULL），按发生先后。 */
    @Query("SELECT * FROM world_event WHERE seenAt IS NULL ORDER BY happenedAt ASC")
    suspend fun unseenEvents(): List<WorldEventEntity>

    /** [from, to] 闭区间内的世界事件（happenedAt 升序·W5 开机小报按窗取事件·图纸 §3.3）。 */
    @Query("SELECT * FROM world_event WHERE happenedAt >= :from AND happenedAt <= :to ORDER BY happenedAt ASC")
    suspend fun eventsBetween(from: Long, to: Long): List<WorldEventEntity>

    /** 按 uuid 单查（W7 visit 落事件「存在即跳过」守卫·防重写清 seenAt/notifiedAt）。 */
    @Query("SELECT * FROM world_event WHERE uuid = :uuid")
    suspend fun getEvent(uuid: String): WorldEventEntity?

    /** 标记已被小报消费——**幂等**：仅在 seenAt 仍为 NULL 时写（重标不改原始时刻）。 */
    @Query("UPDATE world_event SET seenAt = :at WHERE uuid = :uuid AND seenAt IS NULL")
    suspend fun markEventSeen(uuid: String, at: Long)

    /** 标记已通知（W8 去重用）——**幂等**：仅在 notifiedAt 仍为 NULL 时写。 */
    @Query("UPDATE world_event SET notifiedAt = :at WHERE uuid = :uuid AND notifiedAt IS NULL")
    suspend fun markEventNotified(uuid: String, at: Long)

    /**
     * 删角色 → 清所有提及该 id 的世界事件（involvedIdsJson 是 id 的 JSON 数组·uuid 全局唯一，LIKE 足够）。
     * 删角色清理走仓库层事务（无 FK）。
     */
    @Query("DELETE FROM world_event WHERE involvedIdsJson LIKE '%' || :id || '%'")
    suspend fun deleteEventsInvolving(id: String)

    // MARK: - 首访点亮风物志（一次定稿 canon 永久）

    /** 首访写入：已存在则 **IGNORE**（canon 永久·契约 §7.A / 图纸 §9 不许变）。仓库层 `canonizeLore()` 用此。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLore(lore: WorldCityLoreEntity)

    /** 备份恢复专用（幂等覆盖 upsert·图纸 §3）：与 canon 写路径的 IGNORE 分工，仅 `WorldBackup.restoreWorld` 调。 */
    @Upsert
    suspend fun upsertLore(lore: WorldCityLoreEntity)

    @Query("SELECT * FROM world_city_lore WHERE cityId = :cityId")
    suspend fun getLore(cityId: String): WorldCityLoreEntity?

    @Query("SELECT * FROM world_city_lore")
    suspend fun getAllLore(): List<WorldCityLoreEntity>

    // MARK: - 奇观/城市发现（IGNORE 幂等）

    /** 发现记录：重复发现 **IGNORE**（无害幂等·图纸 §9 不许变）。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDiscovery(discovery: WorldDiscoveryEntity)

    /** 备份恢复专用（幂等覆盖 upsert·图纸 §3）：仅 `WorldBackup.restoreWorld` 调。 */
    @Upsert
    suspend fun upsertDiscovery(discovery: WorldDiscoveryEntity)

    @Query("SELECT * FROM world_discovery WHERE placeId = :placeId")
    suspend fun getDiscovery(placeId: String): WorldDiscoveryEntity?

    @Query("SELECT * FROM world_discovery")
    suspend fun getAllDiscoveries(): List<WorldDiscoveryEntity>
}
