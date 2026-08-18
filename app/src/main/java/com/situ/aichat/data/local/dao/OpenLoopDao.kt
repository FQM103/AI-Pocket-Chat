package com.situ.aichat.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.situ.aichat.data.local.entity.OpenLoopEntity

/**
 * 「惦记的事」数据访问（活人感一期 P2·图纸 §3.2）。只做 CRUD；扫描 / 注入选择 / 过期清理纯逻辑在
 * [com.situ.aichat.openloop.OpenLoopScanService]，调度 / 落库业务在
 * [com.situ.aichat.data.repository.OpenLoopRepository] / OpenLoopDetectionTrigger / OpenLoopDueMessenger。
 */
@Dao
interface OpenLoopDao {

    /** PK upsert（落库新 loop / 状态流转 resolved·expired 幂等）。 */
    @Upsert
    suspend fun upsert(loop: OpenLoopEntity)

    @Upsert
    suspend fun upsertAll(loops: List<OpenLoopEntity>)

    /** 按 uuid 查（到期消息 messenger 守卫 loadLoop 用）。 */
    @Query("SELECT * FROM open_loops WHERE uuid = :uuid LIMIT 1")
    suspend fun byUuid(uuid: String): OpenLoopEntity?

    /** 某角色全部 open 状态行（createdAt 升序）——扫描去重简报 + 过期清理 + 注入选择单一数据源。 */
    @Query("SELECT * FROM open_loops WHERE characterUuid = :characterUuid AND statusRaw = 'open' ORDER BY createdAt ASC")
    suspend fun openByCharacter(characterUuid: String): List<OpenLoopEntity>

    /**
     * 长线回访候选（活人感二期 M2·图纸 §3.2）：某角色 resolved 的「用户提到的事」(user_event)，其 resolvedAt
     * 落在 [fromMillis, toMillis] 闭区间内（= 距今 7–30 天窗·由 Repository 换算）、resolvedAt 升序（取最旧优先）。
     * 只 resolved + user_event（角色答应/开放话题不回访）；revisited 终态不入（statusRaw 严格等 'resolved'·E12）。
     */
    @Query(
        "SELECT * FROM open_loops WHERE characterUuid = :characterUuid AND statusRaw = 'resolved' " +
            "AND typeRaw = 'user_event' AND resolvedAt IS NOT NULL AND resolvedAt BETWEEN :fromMillis AND :toMillis " +
            "ORDER BY resolvedAt ASC",
    )
    suspend fun revisitCandidates(characterUuid: String, fromMillis: Long, toMillis: Long): List<OpenLoopEntity>

    /** 删会话连坐清理（无 FK·手动清·图纸 §3.2·E11）。 */
    @Query("DELETE FROM open_loops WHERE conversationUuid = :conversationUuid")
    suspend fun deleteByConversation(conversationUuid: String)

    /** 删角色连坐清理（无 FK·手动清·图纸 §3.2·E11）。 */
    @Query("DELETE FROM open_loops WHERE characterUuid = :characterUuid")
    suspend fun deleteByCharacter(characterUuid: String)
}
