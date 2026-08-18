package com.situ.aichat.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.situ.aichat.data.local.entity.WorldRelationshipEntity
import com.situ.aichat.data.local.entity.WorldRelationshipEventEntity
import kotlinx.coroutines.flow.Flow

/**
 * 角色↔角色社交数据访问（契约 `FABLE5_WORLD_SYSTEM_PROPOSAL.md` §8.A / W1 图纸 §3）：有向关系边 + 关系事件流水。
 *
 * 两端是混合域（角色 uuid / 原住民 id），**表不设 FK**——删除清理走这里的 OR 条件事务方法
 * （[deleteEdgesFor]/[deleteEventsFor]），休眠走 [setDormantFor]。数值钳位/推演属 W4，本 DAO 只做 CRUD。
 */
@Dao
interface WorldSocialDao {

    // MARK: - 有向关系边（A→B 与 B→A 是两行）

    @Upsert
    suspend fun upsertEdge(edge: WorldRelationshipEntity)

    @Upsert
    suspend fun upsertEdges(edges: List<WorldRelationshipEntity>)

    @Query("SELECT * FROM world_relationship WHERE fromId = :fromId AND toId = :toId")
    suspend fun getEdge(fromId: String, toId: String): WorldRelationshipEntity?

    /** 某 id 出向的边（TA 对别人的关系）。 */
    @Query("SELECT * FROM world_relationship WHERE fromId = :id")
    suspend fun edgesFrom(id: String): List<WorldRelationshipEntity>

    /** 某 id 入向的边（别人对 TA 的关系·走 toId 索引）。 */
    @Query("SELECT * FROM world_relationship WHERE toId = :id")
    suspend fun edgesTo(id: String): List<WorldRelationshipEntity>

    @Query("SELECT * FROM world_relationship")
    suspend fun getAllEdges(): List<WorldRelationshipEntity>

    @Query("SELECT * FROM world_relationship")
    fun observeEdges(): Flow<List<WorldRelationshipEntity>>

    /** 删角色 → 清与该 id 相关的**两向**边（无 FK·走仓库层事务）。 */
    @Query("DELETE FROM world_relationship WHERE fromId = :id OR toId = :id")
    suspend fun deleteEdgesFor(id: String)

    /** 离开世界 → 把与该 id 相关的两向边置休眠（回来再点亮）。 */
    @Query("UPDATE world_relationship SET dormant = :dormant WHERE fromId = :id OR toId = :id")
    suspend fun setDormantFor(id: String, dormant: Boolean)

    // MARK: - 关系事件流水（pairKey 聚合两人历史·保留方向 actor/target）

    @Upsert
    suspend fun upsertEvent(event: WorldRelationshipEventEntity)

    @Upsert
    suspend fun upsertEvents(events: List<WorldRelationshipEventEntity>)

    /** 两人之间的事件历史（按对键·发生先后·走 pairKey+happenedAt 复合索引）。 */
    @Query("SELECT * FROM world_relationship_event WHERE pairKey = :pairKey ORDER BY happenedAt ASC")
    suspend fun eventsForPair(pairKey: String): List<WorldRelationshipEventEntity>

    /** 按 uuid 取单条事件（W4 幂等门：落任何事件前先查·已存在则整事件跳过·图纸 §3.4）。 */
    @Query("SELECT * FROM world_relationship_event WHERE uuid = :uuid")
    suspend fun eventByUuid(uuid: String): WorldRelationshipEventEntity?

    /** 某对最新一条事件（W4 弧线进行中判定 / 漂移间隔起点·按 happenedAt 倒序取首）。 */
    @Query("SELECT * FROM world_relationship_event WHERE pairKey = :pairKey ORDER BY happenedAt DESC LIMIT 1")
    suspend fun newestEventForPair(pairKey: String): WorldRelationshipEventEntity?

    /**
     * happenedAt ≥ [from] 的关系事件（升序）——W5 镜像重派生（[com.situ.aichat.world.link.WorldMirrorDeriver]）
     * 与记忆抄写（[com.situ.aichat.world.link.WorldMemoryScribe]）扫「离开期间」新事件用。
     */
    @Query("SELECT * FROM world_relationship_event WHERE happenedAt >= :from ORDER BY happenedAt ASC")
    suspend fun eventsSince(from: Long): List<WorldRelationshipEventEntity>

    /** 某对事件总条数（W4 结痂压缩触发判据 > 40·图纸 §3.6）。 */
    @Query("SELECT COUNT(*) FROM world_relationship_event WHERE pairKey = :pairKey")
    suspend fun countEventsForPair(pairKey: String): Int

    /** 某对最老的 [limit] 条事件（W4 结痂压缩取被压区间·保留最新若干·图纸 §3.6）。 */
    @Query("SELECT * FROM world_relationship_event WHERE pairKey = :pairKey ORDER BY happenedAt ASC LIMIT :limit")
    suspend fun oldestEventsForPair(pairKey: String, limit: Int): List<WorldRelationshipEventEntity>

    /** 批量删事件（W4 结痂压缩折叠后清被压流水·图纸 §3.6）。 */
    @Query("DELETE FROM world_relationship_event WHERE uuid IN (:uuids)")
    suspend fun deleteEventsByUuids(uuids: List<String>)

    @Query("SELECT * FROM world_relationship_event")
    suspend fun getAllEvents(): List<WorldRelationshipEventEntity>

    /** 删角色 → 清该 id 发起或作为对象的关系事件（无 FK·走仓库层事务）。 */
    @Query("DELETE FROM world_relationship_event WHERE actorId = :id OR targetId = :id")
    suspend fun deleteEventsFor(id: String)
}
