package com.situ.aichat.data.backup

import com.situ.aichat.data.local.dao.PromiseDao
import com.situ.aichat.data.local.entity.PromiseEntity
import kotlinx.serialization.Serializable

/**
 * 承诺账本表备份（记忆改造一期·部件①·图纸 §3.1·逐字照 [OfflineMeetingMemoryExport] 范式）：**顶层全局段**，
 * 整体恢复一次；characterUuid 为幽灵（导入库中不存在的角色）→ 整行跳过（无 FK·E15）；uuid 原样保留 →
 * 再导入按 uuid REPLACE 幂等。旧版备份（无此段）导入后 rows 空 → 无约定（E12），账本靠回填 / 对账重建。
 * [openLoopUuid] 原样往返；若对应 open_loops 行未随备份带回（惦记表设备本地不入备份），账本状态变更时
 * 「loop 非 open → no-op」自防（E16），不产生悬挂错误。
 */
@Serializable
data class PromiseExport(
    val uuid: String = "",
    val characterUuid: String = "",
    val conversationUuid: String = "",
    val content: String = "",
    val statusRaw: String = "open",
    val dueAtMillis: Long? = null,
    val sourceRaw: String = "chat",
    val sourceSessionId: String = "",
    val openLoopUuid: String? = null,
    val resolvedAtMillis: Long? = null,
    val resolutionEvidence: String = "",
    val createdAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L,
)

internal fun PromiseEntity.toExport() = PromiseExport(
    uuid, characterUuid, conversationUuid, content, statusRaw, dueAtMillis, sourceRaw, sourceSessionId,
    openLoopUuid, resolvedAtMillis, resolutionEvidence, createdAtMillis, updatedAtMillis,
)

internal fun PromiseExport.toEntity() = PromiseEntity(
    uuid, characterUuid, conversationUuid, content, statusRaw, dueAtMillis, sourceRaw, sourceSessionId,
    openLoopUuid, resolvedAtMillis, resolutionEvidence, createdAtMillis, updatedAtMillis,
)

/** 导出采集（Exporter 全局段之一）。 */
internal suspend fun collectPromises(dao: PromiseDao): List<PromiseExport>? =
    dao.getAll().map { it.toExport() }.ifEmpty { null }

/** 恢复（Importer 事务内·幽灵 characterUuid 行跳过·uuid REPLACE 幂等）。 */
internal suspend fun restorePromises(
    dao: PromiseDao,
    data: List<PromiseExport>?,
    existingCharacterUuids: Set<String>,
) {
    data
        ?.filter { it.characterUuid in existingCharacterUuids }
        ?.forEach { dao.upsert(it.toEntity()) }
}
