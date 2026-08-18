package com.situ.aichat.data.backup

import com.situ.aichat.data.local.dao.WorldDao
import com.situ.aichat.data.local.dao.WorldMemoryDao
import com.situ.aichat.data.local.dao.WorldNativeDao
import com.situ.aichat.data.local.dao.WorldSocialDao
import com.situ.aichat.data.local.dao.WorldUserResidentDao
import com.situ.aichat.data.local.entity.WorldCityLoreEntity
import com.situ.aichat.data.local.entity.WorldDiscoveryEntity
import com.situ.aichat.data.local.entity.WorldEventEntity
import com.situ.aichat.data.local.entity.WorldMemoryEntity
import com.situ.aichat.data.local.entity.WorldNativeStateEntity
import com.situ.aichat.data.local.entity.WorldRelationshipEntity
import com.situ.aichat.data.local.entity.WorldRelationshipEventEntity
import com.situ.aichat.data.local.entity.WorldStateEntity
import com.situ.aichat.data.local.entity.WorldTravelEntity
import com.situ.aichat.data.local.entity.WorldUserResidentEntity
import com.situ.aichat.util.StringListJson
import com.situ.aichat.world.WorldIds
import kotlinx.serialization.Serializable

/**
 * 世界系统备份段（W1 图纸 §3）：DTO + 双向映射 + 采集/恢复，一文件收口（逐字仿 [WorldBookBackup] 模式）。
 *
 * 口径：
 * - 备份必含世界状态（种子/眼缘/住址/加入态/关系边/事件），否则换机丢世界（契约 §5/§20）。全部段可空、空集导出 null。
 * - **世界从未初始化**（`world_state` 无行）→ [collectWorld] 返回 null，整段跳过（E8）。
 * - **旧备份无 world 段** → [restoreWorld] 收到 null 直接返回，世界表保持空、不崩（E6）。
 * - **幽灵 uuid 安全**（照 [WorldBookBackup] 先例）：关系边/关系事件/在途旅行中，参与者若**非** [WorldIds.USER_ID]、
 *   **非** `native:` 前缀、又**不在** existingCharacterUuids → 整行跳过（无 FK 无从级联，靠这里防悬挂·E7）；
 *   原住民 `recruitedCharacterUuid` 指向不存在角色 → 置 null、其余字段照恢复。世界事件 involvedIdsJson 是展示文本
 *   （无 FK），按 uuid 幂等覆盖恢复、不做参与者过滤（图纸未列入跳过域）。
 * - 恢复 = 幂等覆盖 upsert（cityLore/discovery 用 [WorldDao.upsertLore]/[WorldDao.upsertDiscovery]，与 canon 写的
 *   IGNORE 分工，令再导入行级一致）。
 * - **世界记忆（W5·[worldMemories]）**：导出**剥 embedding**（体积/隐私·恢复后由 EmbeddingBackfillWorker 重嵌）；
 *   恢复走幽灵过滤——[WorldMemoryExport.characterUuid] 或 [WorldMemoryExport.otherIdsJson] 含幽灵 id 的整行跳过。
 * - **不入备份**：`world_bulletin` / `world_llm_spend` 是设备本地缓存/台账（正文含角色名·跨设备无意义·下次结算重生成），
 *   本文件**不枚举**这两表（W5 图纸 §3.4）。
 * - **用户自建居民（战役 B·[userResidents]）**：静态人设**全字段往返**（DAO 级·slug 主键幂等覆盖）；无 FK、无参与者，
 *   恢复不做幽灵过滤。avatarPath 只往返路径串（不打包头像图片字节·与 world 段 path-only 模型一致·跨设备未招募居民
 *   头像缺失时 UI 优雅降字母彩圈·见 §11）；招募后头像随其角色行走既有角色备份（recruit 时同路径带入）。
 */
@Serializable
data class WorldBackupData(
    val state: WorldStateExport? = null,
    val natives: List<WorldNativeStateExport>? = null,
    val relationships: List<WorldRelationshipExport>? = null,
    val relationshipEvents: List<WorldRelationshipEventExport>? = null,
    val worldEvents: List<WorldEventExport>? = null,
    val cityLore: List<WorldCityLoreExport>? = null,
    val discoveries: List<WorldDiscoveryExport>? = null,
    val travels: List<WorldTravelExport>? = null,
    val worldMemories: List<WorldMemoryExport>? = null,
    val userResidents: List<WorldUserResidentExport>? = null,
)

@Serializable
data class WorldStateExport(
    val id: Int = 1,
    val seed: Long = 0L,
    val userTimezoneId: String? = null,
    val userHomeCityId: String = WorldIds.HOME_CITY_ID,
    val userCurrentCityId: String = WorldIds.HOME_CITY_ID,
    val lastSettledAt: Long = 0L,
    val createdAt: Long = 0L,
)

@Serializable
data class WorldNativeStateExport(
    val nativeId: String = "",
    val discovered: Boolean = false,
    val discoveredAt: Long? = null,
    val narrativeFuel: Int = 0,
    val giftFuel: Int = 0,
    val encounterCount: Int = 0,
    val lastEncounterAt: Long? = null,
    val recruitedCharacterUuid: String? = null,
    val currentCityId: String? = null,
)

@Serializable
data class WorldRelationshipExport(
    val fromId: String = "",
    val toId: String = "",
    val typesJson: String = "[]",
    val closeness: Int = 0,
    val trust: Int = 0,
    val tension: Int = 0,
    val colorRaw: String = "",
    val trajectoryRaw: String = "stable",
    val bond: String = "",
    val origin: String = "",
    val dormant: Boolean = false,
    val updatedAt: Long = 0L,
)

@Serializable
data class WorldRelationshipEventExport(
    val uuid: String = "",
    val pairKey: String = "",
    val actorId: String = "",
    val targetId: String = "",
    val kindRaw: String = "",
    val arcId: String? = null,
    val summary: String = "",
    val happenedAt: Long = 0L,
    val settledAt: Long = 0L,
)

@Serializable
data class WorldEventExport(
    val uuid: String = "",
    val kindRaw: String = "",
    val involvedIdsJson: String = "[]",
    val cityId: String? = null,
    val summary: String = "",
    val happenedAt: Long = 0L,
    val notifiedAt: Long? = null,
    val seenAt: Long? = null,
)

@Serializable
data class WorldCityLoreExport(
    val cityId: String = "",
    val loreJson: String = "{}",
    val generatedAt: Long = 0L,
)

@Serializable
data class WorldDiscoveryExport(
    val placeId: String = "",
    val discoveredAt: Long = 0L,
)

@Serializable
data class WorldTravelExport(
    val ownerId: String = "",
    val fromCityId: String = "",
    val toCityId: String = "",
    val departAt: Long = 0L,
    val arriveAt: Long = 0L,
    val modeRaw: String = WorldIds.TravelModes.WALK,
    val costGold: Long = 0L,
)

/** 世界记忆导出 DTO（**无 embedding**·恢复后 EmbeddingBackfillWorker 重嵌·W5 图纸 §3.4）。 */
@Serializable
data class WorldMemoryExport(
    val uuid: String = "",
    val characterUuid: String = "",
    val otherIdsJson: String = "[]",
    val kindRaw: String = "",
    val content: String = "",
    val happenedAt: Long = 0L,
    val sourceUuid: String = "",
    val createdAt: Long = 0L,
)

/** 用户自建居民导出 DTO（战役 B·全字段镜像 [WorldUserResidentEntity]·avatarPath 只往返路径串·§11）。 */
@Serializable
data class WorldUserResidentExport(
    val slug: String = "",
    val name: String = "",
    val gender: String = "",
    val age: Int = 0,
    val cityId: String = "",
    val occupation: String = "",
    val personaBrief: String = "",
    val traitsJson: String = "",
    val freeformLore: String = "",
    val initialRelationText: String = "",
    val fuelBias: String = "balanced",
    val avatarPath: String? = null,
    val createdAt: Long = 0L,
)

// ─────────────────────────────── Entity → Export ───────────────────────────────

internal fun WorldStateEntity.toExport() = WorldStateExport(
    id, seed, userTimezoneId, userHomeCityId, userCurrentCityId, lastSettledAt, createdAt,
)

internal fun WorldNativeStateEntity.toExport() = WorldNativeStateExport(
    nativeId, discovered, discoveredAt, narrativeFuel, giftFuel, encounterCount,
    lastEncounterAt, recruitedCharacterUuid, currentCityId,
)

internal fun WorldRelationshipEntity.toExport() = WorldRelationshipExport(
    fromId, toId, typesJson, closeness, trust, tension, colorRaw, trajectoryRaw, bond, origin, dormant, updatedAt,
)

internal fun WorldRelationshipEventEntity.toExport() = WorldRelationshipEventExport(
    uuid, pairKey, actorId, targetId, kindRaw, arcId, summary, happenedAt, settledAt,
)

internal fun WorldEventEntity.toExport() = WorldEventExport(
    uuid, kindRaw, involvedIdsJson, cityId, summary, happenedAt, notifiedAt, seenAt,
)

internal fun WorldCityLoreEntity.toExport() = WorldCityLoreExport(cityId, loreJson, generatedAt)

internal fun WorldDiscoveryEntity.toExport() = WorldDiscoveryExport(placeId, discoveredAt)

internal fun WorldTravelEntity.toExport() = WorldTravelExport(
    ownerId, fromCityId, toCityId, departAt, arriveAt, modeRaw, costGold,
)

/** 导出剥 embedding（W5 图纸 §3.4·体积/隐私·恢复后重嵌）。 */
internal fun WorldMemoryEntity.toExport() = WorldMemoryExport(
    uuid, characterUuid, otherIdsJson, kindRaw, content, happenedAt, sourceUuid, createdAt,
)

internal fun WorldUserResidentEntity.toExport() = WorldUserResidentExport(
    slug, name, gender, age, cityId, occupation, personaBrief, traitsJson, freeformLore,
    initialRelationText, fuelBias, avatarPath, createdAt,
)

// ─────────────────────────────── Export → Entity ───────────────────────────────

internal fun WorldStateExport.toEntity() = WorldStateEntity(
    id, seed, userTimezoneId, userHomeCityId, userCurrentCityId, lastSettledAt, createdAt,
)

/** [recruited] = 解析后的招募指针（幽灵角色 → null）；其余字段照恢复。 */
internal fun WorldNativeStateExport.toEntity(recruited: String?) = WorldNativeStateEntity(
    nativeId, discovered, discoveredAt, narrativeFuel, giftFuel, encounterCount,
    lastEncounterAt, recruited, currentCityId,
)

internal fun WorldRelationshipExport.toEntity() = WorldRelationshipEntity(
    fromId, toId, typesJson, closeness, trust, tension, colorRaw, trajectoryRaw, bond, origin, dormant, updatedAt,
)

internal fun WorldRelationshipEventExport.toEntity() = WorldRelationshipEventEntity(
    uuid, pairKey, actorId, targetId, kindRaw, arcId, summary, happenedAt, settledAt,
)

internal fun WorldEventExport.toEntity() = WorldEventEntity(
    uuid, kindRaw, involvedIdsJson, cityId, summary, happenedAt, notifiedAt, seenAt,
)

internal fun WorldCityLoreExport.toEntity() = WorldCityLoreEntity(cityId, loreJson, generatedAt)

internal fun WorldDiscoveryExport.toEntity() = WorldDiscoveryEntity(placeId, discoveredAt)

internal fun WorldTravelExport.toEntity() = WorldTravelEntity(
    ownerId, fromCityId, toCityId, departAt, arriveAt, modeRaw, costGold,
)

/** 恢复后 embedding = null（EmbeddingBackfillWorker 重嵌·W5 图纸 §3.4）。 */
internal fun WorldMemoryExport.toEntity() = WorldMemoryEntity(
    uuid, characterUuid, otherIdsJson, kindRaw, content, happenedAt, sourceUuid, createdAt, embedding = null,
)

internal fun WorldUserResidentExport.toEntity() = WorldUserResidentEntity(
    slug, name, gender, age, cityId, occupation, personaBrief, traitsJson, freeformLore,
    initialRelationText, fuelBias, avatarPath, createdAt,
)

/** 参与者是否为幽灵（既非用户、非原住民、又不在库中真实角色里）。 */
private fun isGhost(id: String, existing: Set<String>): Boolean =
    id != WorldIds.USER_ID && !WorldIds.isNative(id) && id !in existing

/** 导出采集（Exporter 全局段之一）：世界从未初始化（无 state 行）→ 返回 null，整段跳过。 */
internal suspend fun collectWorld(
    worldDao: WorldDao,
    socialDao: WorldSocialDao,
    nativeDao: WorldNativeDao,
    memoryDao: WorldMemoryDao,
    userResidentDao: WorldUserResidentDao,
): WorldBackupData? {
    val state = worldDao.getState() ?: return null
    return WorldBackupData(
        state = state.toExport(),
        natives = nativeDao.getAll().map { it.toExport() }.ifEmpty { null },
        relationships = socialDao.getAllEdges().map { it.toExport() }.ifEmpty { null },
        relationshipEvents = socialDao.getAllEvents().map { it.toExport() }.ifEmpty { null },
        worldEvents = worldDao.getAllEvents().map { it.toExport() }.ifEmpty { null },
        cityLore = worldDao.getAllLore().map { it.toExport() }.ifEmpty { null },
        discoveries = worldDao.getAllDiscoveries().map { it.toExport() }.ifEmpty { null },
        travels = worldDao.getAllTravels().map { it.toExport() }.ifEmpty { null },
        // 世界记忆导出剥 embedding（W5·恢复后重嵌）；bulletin/spend 是本地缓存/台账，不入备份。
        worldMemories = memoryDao.getAll().map { it.toExport() }.ifEmpty { null },
        // 战役 B：用户自建居民静态人设全字段往返。
        userResidents = userResidentDao.getAll().map { it.toExport() }.ifEmpty { null },
    )
}

/** 恢复（Importer 事务内、restoreWorldBooks 之后调用·幂等覆盖 upsert + 幽灵 uuid 安全·图纸 §3）。 */
internal suspend fun restoreWorld(
    worldDao: WorldDao,
    socialDao: WorldSocialDao,
    nativeDao: WorldNativeDao,
    memoryDao: WorldMemoryDao,
    userResidentDao: WorldUserResidentDao,
    data: WorldBackupData?,
    existingCharacterUuids: Set<String>,
) {
    data ?: return
    data.state?.let { worldDao.upsertState(it.toEntity()) }
    // 战役 B：用户自建居民静态人设幂等覆盖（slug 主键·无 FK 无参与者·不做幽灵过滤·恢复后 loadIntoRoster 由下次 bootstrap 生效·E10）。
    data.userResidents?.forEach { userResidentDao.upsert(it.toEntity()) }
    data.natives?.forEach { n ->
        // 招募指针指向不存在角色 → 置 null，其余字段照恢复（契约 §11 缘分归零同域·E7）。
        val recruited = n.recruitedCharacterUuid?.takeIf { it in existingCharacterUuids }
        nativeDao.upsert(n.toEntity(recruited))
    }
    data.relationships
        ?.filter { !isGhost(it.fromId, existingCharacterUuids) && !isGhost(it.toId, existingCharacterUuids) }
        ?.forEach { socialDao.upsertEdge(it.toEntity()) }
    data.relationshipEvents
        ?.filter { !isGhost(it.actorId, existingCharacterUuids) && !isGhost(it.targetId, existingCharacterUuids) }
        ?.forEach { socialDao.upsertEvent(it.toEntity()) }
    data.worldEvents?.forEach { worldDao.upsertEvent(it.toEntity()) }
    data.cityLore?.forEach { worldDao.upsertLore(it.toEntity()) }
    data.discoveries?.forEach { worldDao.upsertDiscovery(it.toEntity()) }
    data.travels
        ?.filter { !isGhost(it.ownerId, existingCharacterUuids) }
        ?.forEach { worldDao.upsertTravel(it.toEntity()) }
    // 世界记忆：视角主体或任一提及者为幽灵 → 整行跳过（无 FK·图纸 §3.4）；恢复后 embedding=null 由 worker 重嵌。
    data.worldMemories
        ?.filter { m ->
            !isGhost(m.characterUuid, existingCharacterUuids) &&
                StringListJson.decode(m.otherIdsJson).none { isGhost(it, existingCharacterUuids) }
        }
        ?.forEach { memoryDao.upsert(it.toEntity()) }
}
