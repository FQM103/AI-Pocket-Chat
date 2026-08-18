package com.situ.aichat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.situ.aichat.data.local.entity.LogEntryEntity
import com.situ.aichat.diagnostics.CallLogRecord
import com.situ.aichat.diagnostics.LogListRow
import com.situ.aichat.diagnostics.LogToolInfoRow
import kotlinx.coroutines.flow.Flow

/**
 * 上下文日志 DAO（批 D）。写入 + 列表读取 + 容量轮转 + 一键去隐私 + 失败率审计喂数。
 *
 * 轮转用「取分界 timestamp + 批量删」（[oldestTimestampAtOffset] + [deleteOlderThanInclusive]）而非逐条删，1:1 iOS。
 */
@Dao
interface LogDao {

    /** 插入一条记录，返回 rowId。 */
    @Insert
    suspend fun insert(entry: LogEntryEntity): Long

    /**
     * 列表页：最新 [limit] 条倒序（Flow 实时刷新）。**轻投影** [LogListRow]——绝不 SELECT *：
     * 全量记录后正文列单条可达数十万字，整实体进列表 Flow = 每次刷新搬几十 MB（见 [LogListRow] KDoc）。
     */
    @Query(
        "SELECT id, timestampMillis, characterName, modelName, isSuccess, source, messageCount, " +
            "durationMillis, errorMessage, promptTokens, completionTokens, cacheHitTokens, cacheMissTokens, " +
            "isTokenEstimated FROM log_entries ORDER BY timestampMillis DESC, id DESC LIMIT :limit",
    )
    fun recent(limit: Int): Flow<List<LogListRow>>

    /** 详情/分段页：按 id 取单条（D-3 UI；不存在返回 null）。 */
    @Query("SELECT * FROM log_entries WHERE id = :id")
    suspend fun getById(id: Long): LogEntryEntity?

    /** 总条数（轮转判断用，避免为计数加载全表）。 */
    @Query("SELECT COUNT(*) FROM log_entries")
    suspend fun count(): Int

    /** 升序第 [offset] 条的 timestamp（轮转分界；越界返回 null）。 */
    @Query("SELECT timestampMillis FROM log_entries ORDER BY timestampMillis ASC, id ASC LIMIT 1 OFFSET :offset")
    suspend fun oldestTimestampAtOffset(offset: Int): Long?

    /** 删除分界（含）之前的旧记录，返回删除条数。 */
    @Query("DELETE FROM log_entries WHERE timestampMillis <= :cutoffMillis")
    suspend fun deleteOlderThanInclusive(cutoffMillis: Long): Int

    /** 删除单条（详情页删除用）。 */
    @Query("DELETE FROM log_entries WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 清空全部（设置页「清空全部日志」）。 */
    @Query("DELETE FROM log_entries")
    suspend fun deleteAll()

    /**
     * 一键去隐私·SQL 步：清既有日志的正文（[LogEntryEntity.fullContext]/[LogEntryEntity.responseContent]/
     * [LogEntryEntity.contextSegmentsJson]），保留时间/角色/模型/耗时/token 等元数据，返回受影响条数。
     * **不动 toolInfoJson**——其内「名与计数=元数据恒存、参数预览=内容」只能代码级重消毒，
     * 完整入口 = [com.situ.aichat.diagnostics.ContextLogService.purgeSensitiveText]（复核 R1 补）。
     */
    @Query(
        "UPDATE log_entries SET fullContext = '', responseContent = NULL, contextSegmentsJson = '' " +
            "WHERE fullContext != '' OR responseContent IS NOT NULL OR contextSegmentsJson != ''",
    )
    suspend fun purgeFullText(): Int

    /** 一键去隐私·遥测子步（复核 R1）：取仍带工具遥测 JSON 的行（id+json 轻投影）。 */
    @Query("SELECT id, toolInfoJson FROM log_entries WHERE toolInfoJson != ''")
    suspend fun toolInfoRows(): List<LogToolInfoRow>

    /** 列级回写单行工具遥测 JSON（去隐私剥参数预览用；列级=不碰其他列）。 */
    @Query("UPDATE log_entries SET toolInfoJson = :encoded WHERE id = :id")
    suspend fun updateToolInfo(id: Long, encoded: String)

    /**
     * 失败率审计喂数（接 [com.situ.aichat.diagnostics.FailureRateAudit]）：取 [sinceMillis] 起的来源/成败/时间投影。
     * 列名对齐 [CallLogRecord] 构造参数，Room 直接映射。
     */
    @Query("SELECT source, isSuccess, timestampMillis FROM log_entries WHERE timestampMillis >= :sinceMillis")
    suspend fun recordsSince(sinceMillis: Long): List<CallLogRecord>
}
