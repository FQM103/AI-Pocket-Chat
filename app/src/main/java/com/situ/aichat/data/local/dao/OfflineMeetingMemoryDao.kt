package com.situ.aichat.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.situ.aichat.data.local.entity.OfflineMeetingMemoryEntity
import kotlinx.coroutines.flow.Flow

/**
 * 线下见面回忆行数据访问（梦剧场 B 部·图纸 §3.3）。CRUD 只做数据；懒播种/渲染/摘要写回业务在
 * [com.situ.aichat.data.repository.OfflineMeetingMemoryRepository]。
 */
@Dao
interface OfflineMeetingMemoryDao {

    /** 某角色全部行（startedAtMillis 升序·legacy 行 startedAt=0 排最前）。 */
    @Query("SELECT * FROM offline_meeting_memories WHERE characterUuid = :characterUuid ORDER BY startedAtMillis ASC")
    suspend fun byCharacter(characterUuid: String): List<OfflineMeetingMemoryEntity>

    /** 按 session 查（upsertMeeting 保 uuid/createdAt 更新其余的判据）。 */
    @Query("SELECT * FROM offline_meeting_memories WHERE sessionId = :sessionId LIMIT 1")
    suspend fun findBySessionId(sessionId: String): OfflineMeetingMemoryEntity?

    /** 按行 uuid 查（回忆屏手动编辑 updateEdited 用）。 */
    @Query("SELECT * FROM offline_meeting_memories WHERE uuid = :uuid LIMIT 1")
    suspend fun findByUuid(uuid: String): OfflineMeetingMemoryEntity?

    /** PK upsert（懒播种幂等 + 摘要写回·图纸 §3.3）。 */
    @Upsert
    suspend fun upsert(row: OfflineMeetingMemoryEntity)

    @Upsert
    suspend fun upsertAll(rows: List<OfflineMeetingMemoryEntity>)

    /** 某角色行数（懒播种「先 count 再插」门）。 */
    @Query("SELECT COUNT(*) FROM offline_meeting_memories WHERE characterUuid = :characterUuid")
    suspend fun countByCharacter(characterUuid: String): Int

    /** 全部角色回忆行总数（响应式·「我」页陪伴统计「共同回忆」的一半·PROFILE 契约 §9.3）。 */
    @Query("SELECT COUNT(*) FROM offline_meeting_memories")
    fun observeCountAll(): Flow<Int>

    /** 删角连坐清理（与 conversations/messages 同处调）。 */
    @Query("DELETE FROM offline_meeting_memories WHERE characterUuid = :characterUuid")
    suspend fun deleteByCharacter(characterUuid: String)

    /** 24h 自愈：挑 source='fallback' 最老一行（按 startedAt 升序）升级 LLM 版。 */
    @Query("SELECT * FROM offline_meeting_memories WHERE characterUuid = :characterUuid AND sourceRaw = 'fallback' ORDER BY startedAtMillis ASC LIMIT 1")
    suspend fun oldestFallback(characterUuid: String): OfflineMeetingMemoryEntity?

    /** 全部行（备份导出用）。 */
    @Query("SELECT * FROM offline_meeting_memories")
    suspend fun getAll(): List<OfflineMeetingMemoryEntity>

    /** 当日全部角色的见面行（日记提及·§3.9）：kind='meeting' 且 startedAt ∈ [start, end)·升序。 */
    @Query("SELECT * FROM offline_meeting_memories WHERE kindRaw = 'meeting' AND startedAtMillis >= :startMillis AND startedAtMillis < :endMillis ORDER BY startedAtMillis ASC")
    suspend fun meetingsInRange(startMillis: Long, endMillis: Long): List<OfflineMeetingMemoryEntity>

    /** 近期见面行（联系人「最近纪事」·图纸一 #5）：结构化行且晚于 since，全角色一把观察。 */
    @Query("SELECT * FROM offline_meeting_memories WHERE kindRaw = 'meeting' AND startedAtMillis >= :since")
    fun observeMeetingsSince(since: Long): Flow<List<OfflineMeetingMemoryEntity>>

    /** 降级前消化标记（记忆改造一期·部件③·图纸 §3.5-A·列级 UPDATE·消化作业成功后写）。 */
    @Query("UPDATE offline_meeting_memories SET digestedAtMillis = :now WHERE uuid = :uuid")
    suspend fun markDigested(uuid: String, now: Long)

    // ── 见面档案向量索引（记忆改造四期·部件⑥·图纸 §3.1·照 WorldMemoryDao 同款三件） ──

    /** 缺嵌入的 meeting 行（回填批·legacy 行恒注入原文不建向量·summary 空行无嵌入价值）。 */
    @Query("SELECT * FROM offline_meeting_memories WHERE kindRaw = 'meeting' AND embedding IS NULL AND summary != '' LIMIT :limit")
    suspend fun missingEmbedding(limit: Int): List<OfflineMeetingMemoryEntity>

    /** 列级写嵌入（照 MessageDao.updateEmbedding 惯例·绝不整行 upsert）。 */
    @Query("UPDATE offline_meeting_memories SET embedding = :embedding WHERE uuid = :uuid")
    suspend fun updateEmbedding(uuid: String, embedding: ByteArray)

    /** 模型签名变更双清（照 MessageDao.clearAllEmbeddings 惯例）。 */
    @Query("UPDATE offline_meeting_memories SET embedding = NULL WHERE embedding IS NOT NULL")
    suspend fun clearAllEmbeddings(): Int
}
