package com.situ.aichat.data.repository

import com.situ.aichat.data.local.dao.OpenLoopDao
import com.situ.aichat.data.local.entity.OpenLoopEntity
import com.situ.aichat.data.local.entity.OpenLoopStatus
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「惦记的事」仓库（活人感一期 P2·图纸 §3.2）：[OpenLoopDao] 薄包装——open loops 查询、状态流转、级联删。
 * 不含扫描 / 选择 / 清理业务（那是 [com.situ.aichat.openloop.OpenLoopScanService] 纯函数 + Trigger / Messenger）。
 */
@Singleton
class OpenLoopRepository @Inject constructor(
    private val dao: OpenLoopDao,
) {
    /** 某角色全部 open loops（createdAt 升序）——扫描去重简报 / 过期清理 / 注入选择的单一数据源。 */
    suspend fun openLoopsForCharacter(characterUuid: String): List<OpenLoopEntity> = dao.openByCharacter(characterUuid)

    suspend fun byUuid(uuid: String): OpenLoopEntity? = dao.byUuid(uuid)

    suspend fun upsert(loop: OpenLoopEntity) = dao.upsert(loop)

    suspend fun upsertAll(loops: List<OpenLoopEntity>) = dao.upsertAll(loops)

    /** 置某 loop 为 resolved（+resolvedAt）：到期消息发出后 / 扫描判定已解决时调（幂等 PK upsert）。 */
    suspend fun markResolved(loop: OpenLoopEntity, resolvedAt: Long) =
        dao.upsert(loop.copy(statusRaw = OpenLoopStatus.RESOLVED, resolvedAt = resolvedAt))

    /**
     * 长线回访候选（活人感二期 M2·图纸 §3.2）：resolvedAt 距 [nowMillis] 落在 7–30 天窗（闭区间·E11）的
     * resolved user_event 行，resolvedAt 升序（取最旧优先）。窗口常量见 [OpenLoopScanService]。
     */
    suspend fun revisitCandidates(characterUuid: String, nowMillis: Long): List<OpenLoopEntity> =
        dao.revisitCandidates(
            characterUuid = characterUuid,
            fromMillis = nowMillis - com.situ.aichat.openloop.OpenLoopScanService.REVISIT_MAX_MS,
            toMillis = nowMillis - com.situ.aichat.openloop.OpenLoopScanService.REVISIT_MIN_MS,
        )

    /**
     * 置某 loop 为回访终态 revisited（活人感二期 M2·图纸 §3.2）：本轮已带该回访项注入并成功回合后调（一次为限·E12）。
     * **只改 statusRaw，[OpenLoopEntity.resolvedAt] 原值保留不覆盖**（E8）。幂等 PK upsert。
     * [markRevisitedAt] 按图纸 §3.2 规定的调用签名传入，但本图纸零迁移无「回访时间」列可落，故刻意不写库。
     */
    suspend fun markRevisited(loop: OpenLoopEntity, markRevisitedAt: Long) =
        dao.upsert(loop.copy(statusRaw = OpenLoopStatus.REVISITED))

    /** 会话级联删（无 FK·手动清·E11）。 */
    suspend fun deleteByConversation(conversationUuid: String) = dao.deleteByConversation(conversationUuid)

    /** 角色级联删（无 FK·手动清·E11）。 */
    suspend fun deleteByCharacter(characterUuid: String) = dao.deleteByCharacter(characterUuid)
}
