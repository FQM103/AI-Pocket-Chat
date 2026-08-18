package com.situ.aichat.data.backup

import com.situ.aichat.data.local.dao.OfflineMeetingMemoryDao
import com.situ.aichat.data.local.entity.OfflineMeetingMemoryEntity
import kotlinx.serialization.Serializable

/**
 * 线下见面回忆表备份（梦剧场 B 部·图纸 §3.2·照 world_memories 范式）：**顶层全局段**，整体恢复一次；
 * characterUuid 为幽灵（导入库中不存在的角色）→ 整行跳过（无 FK）；uuid 原样保留 → 再导入按 uuid REPLACE 幂等（E16）。
 * 旧版备份（无此段）导入后 rows 空 → 靠 blob 兜底（E2）。
 */
@Serializable
data class OfflineMeetingMemoryExport(
    val uuid: String = "",
    val characterUuid: String = "",
    val conversationUuid: String = "",
    val sessionId: String = "",
    val kindRaw: String = "meeting",
    val startedAtMillis: Long = 0L,
    val endedAtMillis: Long = 0L,
    val location: String = "",
    val activity: String = "",
    val moodRaw: String = "",
    val initiatedByUser: Boolean? = null,
    val messageCount: Int = 0,
    val summary: String = "",
    val highlightsJson: String = "[]",
    val promisesJson: String = "[]",
    val sourceRaw: String = "llm",
    val createdAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L,
    /** 降级前消化标记（记忆改造一期·图纸 §3.5-A）；老备份缺字段→null=未消化兜底。 */
    val digestedAtMillis: Long? = null,
)

internal fun OfflineMeetingMemoryEntity.toExport() = OfflineMeetingMemoryExport(
    uuid, characterUuid, conversationUuid, sessionId, kindRaw, startedAtMillis, endedAtMillis,
    location, activity, moodRaw, initiatedByUser, messageCount, summary, highlightsJson, promisesJson,
    sourceRaw, createdAtMillis, updatedAtMillis, digestedAtMillis,
)

internal fun OfflineMeetingMemoryExport.toEntity() = OfflineMeetingMemoryEntity(
    uuid, characterUuid, conversationUuid, sessionId, kindRaw, startedAtMillis, endedAtMillis,
    location, activity, moodRaw, initiatedByUser, messageCount, summary, highlightsJson, promisesJson,
    sourceRaw, createdAtMillis, updatedAtMillis, digestedAtMillis,
)

/** 导出采集（Exporter 全局段之一）。 */
internal suspend fun collectOfflineMeetingMemories(dao: OfflineMeetingMemoryDao): List<OfflineMeetingMemoryExport>? =
    dao.getAll().map { it.toExport() }.ifEmpty { null }

/** 恢复（Importer 事务内·restoreWorld 之后·幽灵 characterUuid 行跳过·uuid REPLACE 幂等）。 */
internal suspend fun restoreOfflineMeetingMemories(
    dao: OfflineMeetingMemoryDao,
    data: List<OfflineMeetingMemoryExport>?,
    existingCharacterUuids: Set<String>,
) {
    data
        ?.filter { it.characterUuid in existingCharacterUuids }
        ?.forEach { dao.upsert(it.toEntity()) }
}
