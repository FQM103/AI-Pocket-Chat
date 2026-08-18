package com.situ.aichat.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.situ.aichat.data.local.entity.WorldMemoryEntity
import kotlinx.coroutines.flow.Flow

/**
 * 世界记忆数据访问（W5 图纸 §3.1 / §3.3）：双视角世界记忆增查删 + 待嵌入分页 + 删角连坐清理。
 *
 * 幂等门 [getByUuid]（[com.situ.aichat.world.link.WorldMemoryScribe] 落任何记忆前先查）；嵌入回填走
 * [missingEmbedding]/[updateEmbedding]；聊天注入近层 [recentForCharacter] + 向量层 [embeddedForCharacter]。
 * 本 DAO 只做 CRUD，业务（抄写/嵌入/检索）在 world/link。
 */
@Dao
interface WorldMemoryDao {

    @Upsert
    suspend fun upsert(memory: WorldMemoryEntity)

    @Upsert
    suspend fun upsertAll(memories: List<WorldMemoryEntity>)

    /** 幂等门：落任何记忆前先查（已存在则整条跳过·图纸 §3.3）。 */
    @Query("SELECT * FROM world_memory WHERE uuid = :uuid")
    suspend fun getByUuid(uuid: String): WorldMemoryEntity?

    /** 某角色近 [since] 起的记忆（happenedAt ≥ since·降序·聊天注入「近 3 天」层）。 */
    @Query("SELECT * FROM world_memory WHERE characterUuid = :characterUuid AND happenedAt >= :since ORDER BY happenedAt DESC")
    suspend fun recentForCharacter(characterUuid: String, since: Long): List<WorldMemoryEntity>

    /** 某角色已建向量索引的记忆（余弦检索候选·embedding 非 null）。 */
    @Query("SELECT * FROM world_memory WHERE characterUuid = :characterUuid AND embedding IS NOT NULL")
    suspend fun embeddedForCharacter(characterUuid: String): List<WorldMemoryEntity>

    /** 待嵌入回填（embedding IS NULL·分页·后台限流）。 */
    @Query("SELECT * FROM world_memory WHERE embedding IS NULL ORDER BY happenedAt ASC LIMIT :limit")
    suspend fun missingEmbedding(limit: Int): List<WorldMemoryEntity>

    /** 回填向量索引（单条更新 embedding 一列）。 */
    @Query("UPDATE world_memory SET embedding = :embedding WHERE uuid = :uuid")
    suspend fun updateEmbedding(uuid: String, embedding: ByteArray)

    /** 全部记忆（备份导出用·导出侧剥 embedding）。 */
    @Query("SELECT * FROM world_memory")
    suspend fun getAll(): List<WorldMemoryEntity>

    /** 全部世界记忆总数（响应式·「我」页陪伴统计「共同回忆」的另一半·PROFILE 契约 §9.3）。 */
    @Query("SELECT COUNT(*) FROM world_memory")
    fun observeCountAll(): Flow<Int>

    /**
     * 删角（决策 27 彻底遗忘）→ 清本人记忆 + 他人记忆中提及该 id 的行（无 FK·走仓库层事务·图纸 §3.4）。
     */
    @Query("DELETE FROM world_memory WHERE characterUuid = :id OR otherIdsJson LIKE '%' || :id || '%'")
    suspend fun deleteInvolving(id: String)
}
