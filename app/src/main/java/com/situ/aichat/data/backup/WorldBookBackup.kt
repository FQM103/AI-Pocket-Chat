package com.situ.aichat.data.backup

import com.situ.aichat.data.local.dao.WorldBookDao
import com.situ.aichat.data.local.entity.WorldBookBindingEntity
import com.situ.aichat.data.local.entity.WorldBookEntity
import com.situ.aichat.data.local.entity.WorldBookEntryEntity
import kotlinx.serialization.Serializable

/**
 * 世界书备份段（WB6b·契约 §9）：DTO + 双向映射 + 采集/恢复，一文件收口。
 *
 * 口径（对齐 13.6a 已签字的「全局段」语义）：
 * - **顶层全局段整体恢复一次**，不随逐角色策略走；书/条目**按原 uuid 覆盖 = 幂等**（重复导入不叠书），
 *   恢复前先清该书旧条目（防旧版本条目残留）；
 * - **绑定按导出时的角色 uuid 恢复**——覆盖/跳过/无冲突新导入三条主路径角色 uuid 均不变，直接可用；
 *   「创建副本」的新角色**不继承绑定**（与礼物/红包/流水不随副本重映射同档，已登记）；
 *   绑定仅接到**库中真实存在**的角色（手改备份里的幽灵 uuid 直接跳过，绝不触发 FK 崩溃）；
 * - **嵌入不进备份**（可再生派生数据·避免清单膨胀）：恢复后向量条目由 WB5 聊天触达时懒补；
 * - **时效状态（sticky/cooldown）不进备份**（会话运行时态）。
 */

@Serializable
data class WorldBookBackupData(
    val book: WorldBookExport,
    val entries: List<WorldBookEntryExport>? = null,
    /** 导出时绑定的角色 uuid 列表。 */
    val boundCharacterUuids: List<String>? = null,
)

@Serializable
data class WorldBookExport(
    val uuid: String = "",
    val name: String = "",
    val description: String = "",
    val scanDepth: Int? = null,
    val tokenBudget: Int? = null,
    val recursiveScanning: Boolean? = null,
    val isGlobal: Boolean = false,
    val enabled: Boolean = true,
    val extraJson: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

@Serializable
data class WorldBookEntryExport(
    val uuid: String = "",
    val uid: Int = 0,
    val displayIndex: Int = 0,
    val keysJson: String = "[]",
    val secondaryKeysJson: String = "[]",
    val selective: Boolean = true,
    val selectiveLogic: Int = 0,
    val constant: Boolean = false,
    val vectorized: Boolean = false,
    val comment: String = "",
    val content: String = "",
    val enabled: Boolean = true,
    val insertionOrder: Int = 100,
    val position: Int = 0,
    val depth: Int = 4,
    val role: Int = 0,
    val ignoreBudget: Boolean = false,
    val probability: Int = 100,
    val useProbability: Boolean = true,
    val scanDepth: Int? = null,
    val caseSensitive: Boolean? = null,
    val matchWholeWords: Boolean? = null,
    val useGroupScoring: Boolean? = null,
    val excludeRecursion: Boolean = false,
    val preventRecursion: Boolean = false,
    val delayUntilRecursion: Int = 0,
    val groupName: String = "",
    val groupOverride: Boolean = false,
    val groupWeight: Int = 100,
    val sticky: Int? = null,
    val cooldown: Int? = null,
    val delay: Int? = null,
    val extraJson: String = "",
)

internal fun WorldBookEntity.toExport() = WorldBookExport(
    uuid = uuid, name = name, description = description, scanDepth = scanDepth,
    tokenBudget = tokenBudget, recursiveScanning = recursiveScanning, isGlobal = isGlobal,
    enabled = enabled, extraJson = extraJson, createdAt = createdAt, updatedAt = updatedAt,
)

internal fun WorldBookExport.toEntity() = WorldBookEntity(
    uuid = uuid, name = name, description = description, scanDepth = scanDepth,
    tokenBudget = tokenBudget, recursiveScanning = recursiveScanning, isGlobal = isGlobal,
    enabled = enabled, extraJson = extraJson, createdAt = createdAt, updatedAt = updatedAt,
)

internal fun WorldBookEntryEntity.toExport() = WorldBookEntryExport(
    uuid = uuid, uid = uid, displayIndex = displayIndex, keysJson = keysJson,
    secondaryKeysJson = secondaryKeysJson, selective = selective, selectiveLogic = selectiveLogic,
    constant = constant, vectorized = vectorized, comment = comment, content = content,
    enabled = enabled, insertionOrder = insertionOrder, position = position, depth = depth,
    role = role, ignoreBudget = ignoreBudget, probability = probability, useProbability = useProbability,
    scanDepth = scanDepth, caseSensitive = caseSensitive, matchWholeWords = matchWholeWords,
    useGroupScoring = useGroupScoring, excludeRecursion = excludeRecursion,
    preventRecursion = preventRecursion, delayUntilRecursion = delayUntilRecursion,
    groupName = groupName, groupOverride = groupOverride, groupWeight = groupWeight,
    sticky = sticky, cooldown = cooldown, delay = delay, extraJson = extraJson,
)

/** 嵌入不进备份 → 恢复为 null（WB5 聊天触达时懒补）。 */
internal fun WorldBookEntryExport.toEntity(bookUuid: String) = WorldBookEntryEntity(
    uuid = uuid, bookUuid = bookUuid, uid = uid, displayIndex = displayIndex, keysJson = keysJson,
    secondaryKeysJson = secondaryKeysJson, selective = selective, selectiveLogic = selectiveLogic,
    constant = constant, vectorized = vectorized, comment = comment, content = content,
    enabled = enabled, insertionOrder = insertionOrder, position = position, depth = depth,
    role = role, ignoreBudget = ignoreBudget, probability = probability, useProbability = useProbability,
    scanDepth = scanDepth, caseSensitive = caseSensitive, matchWholeWords = matchWholeWords,
    useGroupScoring = useGroupScoring, excludeRecursion = excludeRecursion,
    preventRecursion = preventRecursion, delayUntilRecursion = delayUntilRecursion,
    groupName = groupName, groupOverride = groupOverride, groupWeight = groupWeight,
    sticky = sticky, cooldown = cooldown, delay = delay, extraJson = extraJson,
    embedding = null, embeddingSignature = null,
)

/** 导出采集（Exporter 全局段之一）。 */
internal suspend fun collectWorldBooks(dao: WorldBookDao): List<WorldBookBackupData>? =
    dao.getAllBooks().map { book ->
        WorldBookBackupData(
            book = book.toExport(),
            entries = dao.entriesForBook(book.uuid).map { it.toExport() }.ifEmpty { null },
            boundCharacterUuids = dao.boundCharacterUuids(book.uuid).ifEmpty { null },
        )
    }.ifEmpty { null }

/** 恢复（Importer 事务内调用·幂等）：书按 uuid 覆盖 + 先清旧条目再插 + 绑定过滤仅存在角色。 */
internal suspend fun restoreWorldBooks(
    dao: WorldBookDao,
    data: List<WorldBookBackupData>?,
    existingCharacterUuids: Set<String>,
) {
    data ?: return
    for (wb in data) {
        val book = wb.book.toEntity()
        if (book.uuid.isBlank()) continue
        dao.upsertBook(book)
        dao.deleteEntriesForBook(book.uuid)
        dao.upsertEntries(wb.entries.orEmpty().filter { it.uuid.isNotBlank() }.map { it.toEntity(book.uuid) })
        wb.boundCharacterUuids.orEmpty()
            .filter { it in existingCharacterUuids }
            .forEach { dao.bind(WorldBookBindingEntity(characterUuid = it, bookUuid = book.uuid)) }
    }
}
