package com.situ.aichat.pet

import com.situ.aichat.data.local.entity.CharacterPetEntity
import com.situ.aichat.data.repository.PetRepository
import com.situ.aichat.data.repository.PetWriteLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 宠物库存操作服务（1:1 iOS `Services/PetInventoryService.swift`）：消耗品使用 + 装扮佩戴/摘下。
 *
 * **不复用 [PetCareService] 的 feed/clean/play 路径**（1:1 iOS 设计）：避免消耗品重复触发性格加成（物品数值已 tuned）、
 * 不干扰 lastFedDate/lastCleanedDate/lastPlayedDate 语义、不和 UI 冷却耦合。消耗品**直接 clamp 0-100 写回状态值**，
 * 只共同推进 totalInteractions + lastInteractionDate（算一次互动）。
 *
 * Android 适配：iOS 可变 @Model 原地改 + save；这里 fresh 读宠物行 → 不可变 `copy` → upsert（Room 流回推刷新 UI）。
 * 库存变更日志统一 [PetGrowthEventType.SPECIAL_EVENT]。**聊天气泡反馈（[PetChatBubbleService]）→ 9.3c-2 接入**。
 * `now` 注入便于确定性单测。纯数值/钳位逻辑（[applyBoosts]/[clampStat]）放 [Companion] 且 internal。
 */
@Singleton
class PetInventoryService @Inject constructor(
    private val petRepository: PetRepository,
    private val petWriteLock: PetWriteLock,
    private val chatBubbleService: PetChatBubbleService,
) {

    /** 使用消耗品结果（1:1 iOS `PetInventoryConsumeResult` + 防御性错误态）。 */
    sealed interface ConsumeOutcome {
        /** 用完后还剩 [remaining] 份。 */
        data class Consumed(val remaining: Int) : ConsumeOutcome

        /** 这次用完了（库存清零）。 */
        data object ConsumedOut : ConsumeOutcome

        /** 该物品不是消耗品。 */
        data class NotConsumable(val itemId: String) : ConsumeOutcome

        /** 未拥有该物品。 */
        data class NotOwned(val itemId: String) : ConsumeOutcome

        /** 宠物行不存在。 */
        data object PetNotFound : ConsumeOutcome
    }

    /** 佩戴/摘下结果。 */
    sealed interface EquipOutcome {
        data object Done : EquipOutcome
        data class NotEquippable(val itemId: String) : EquipOutcome
        data class NotOwned(val itemId: String) : EquipOutcome
        data object PetNotFound : EquipOutcome
    }

    /**
     * 使用一件消耗品（1:1 iOS `useConsumable`）：①类型校验 ②拥有校验 ③应用 statBoosts（clamp 0-100）
     * ④扣库存（扣到 0 删 key）⑤totalInteractions+1、lastInteractionDate=now ⑥growth log「用了「{name}」」⑦写回。
     */
    suspend fun useConsumable(
        item: PetItem,
        petUuid: String,
        now: Long = System.currentTimeMillis(),
    ): ConsumeOutcome {
        if (item.kind != PetItemKind.CONSUMABLE) return ConsumeOutcome.NotConsumable(item.id)
        // D1d：锁内重读最新宠物→改数值/库存→写回，与维护/护理串行（按 pet uuid）。
        var bubbleCharacterUuid: String? = null
        val outcome = petWriteLock.withPetLock(petUuid) {
            val pet = petRepository.getByUuid(petUuid) ?: return@withPetLock ConsumeOutcome.PetNotFound
            val inventory = pet.metadata.petInventory
            if (!inventory.has(item.id)) return@withPetLock ConsumeOutcome.NotOwned(item.id)

            // 应用数值（clamp 0-100）
            val boosted = applyBoosts(
                PetStatSnapshot(pet.hunger, pet.cleanliness, pet.happiness, pet.health),
                item.statBoosts,
            )

            // 扣库存（removing 扣到 0 自动删 key + 摘除正佩戴装扮，但消耗品不涉及佩戴）
            val newInventory = inventory.removing(item.id, 1)
            val remaining = newInventory.quantity(item.id)
            val metadata = pet.metadata.copy(petInventory = newInventory)

            val updated = pet.copy(
                hunger = boosted.hunger,
                cleanliness = boosted.cleanliness,
                happiness = boosted.happiness,
                health = boosted.health,
                totalInteractions = pet.totalInteractions + 1,
                lastInteractionDate = now,
                petMetadataJson = PetJson.encodeMetadata(metadata),
                petGrowthLogJson = PetJson.encodeGrowthLog(appendInventoryLog(pet.growthLog, "用了「${item.name}」", now)),
            )
            petRepository.upsert(updated)
            bubbleCharacterUuid = pet.characterUuid
            if (remaining == 0) ConsumeOutcome.ConsumedOut else ConsumeOutcome.Consumed(remaining)
        }

        // 聊天气泡反馈（节流 3/天/会话，静默失败不影响本路径）。锁外触发：写聊天消息、不持宠物锁。
        bubbleCharacterUuid?.let { chatBubbleService.notifyConsumableUsed(item.name, it, now) }

        return outcome
    }

    /**
     * 佩戴一件装扮（1:1 iOS `equip`）：校验 equippable + 已拥有 → equipping（单槽，自动替换前一件）→ growth log
     *「戴上了「{name}」」→ 写回。
     */
    suspend fun equip(
        item: PetItem,
        petUuid: String,
        now: Long = System.currentTimeMillis(),
    ): EquipOutcome {
        if (item.kind != PetItemKind.EQUIPPABLE) return EquipOutcome.NotEquippable(item.id)
        // D1d：锁内重读最新→改库存佩戴→写回。
        var bubbleCharacterUuid: String? = null
        val outcome = petWriteLock.withPetLock(petUuid) {
            val pet = petRepository.getByUuid(petUuid) ?: return@withPetLock EquipOutcome.PetNotFound
            val inventory = pet.metadata.petInventory
            if (!inventory.has(item.id)) return@withPetLock EquipOutcome.NotOwned(item.id)

            val metadata = pet.metadata.copy(petInventory = inventory.equipping(item.id))
            petRepository.upsert(
                pet.copy(
                    petMetadataJson = PetJson.encodeMetadata(metadata),
                    petGrowthLogJson = PetJson.encodeGrowthLog(appendInventoryLog(pet.growthLog, "戴上了「${item.name}」", now)),
                ),
            )
            bubbleCharacterUuid = pet.characterUuid
            EquipOutcome.Done
        }

        // 聊天气泡反馈（节流 3/天/会话）。锁外触发。
        bubbleCharacterUuid?.let { chatBubbleService.notifyEquipped(item.name, it, now) }

        return outcome
    }

    /**
     * 摘下当前佩戴的装扮（1:1 iOS `unequip`）：unequipping；若之前确有佩戴 → growth log「摘下了「{name}」」。
     * 未佩戴时为无效操作（仍写回，幂等）。
     */
    suspend fun unequip(
        petUuid: String,
        now: Long = System.currentTimeMillis(),
    ): EquipOutcome {
        // D1d：锁内重读最新→摘除佩戴→写回。
        return petWriteLock.withPetLock(petUuid) {
            val pet = petRepository.getByUuid(petUuid) ?: return@withPetLock EquipOutcome.PetNotFound
            val inventory = pet.metadata.petInventory
            val previouslyEquipped = inventory.equippedItemId
            val metadata = pet.metadata.copy(petInventory = inventory.unequipping())

            val newLog = previouslyEquipped
                ?.let { PetItemCatalog.find(it) }
                ?.let { appendInventoryLog(pet.growthLog, "摘下了「${it.name}」", now) }
                ?: pet.growthLog
            petRepository.upsert(
                pet.copy(
                    petMetadataJson = PetJson.encodeMetadata(metadata),
                    petGrowthLogJson = PetJson.encodeGrowthLog(newLog),
                ),
            )
            EquipOutcome.Done
        }
    }

    companion object {
        /** 0-100 clamp（1:1 iOS `clamp`）。 */
        internal fun clampStat(value: Int): Int = value.coerceIn(0, 100)

        /** 应用 statBoosts（逐维 clamp 0-100；null 维度不变）。纯函数便于单测断言反推 iOS 加成数值。 */
        internal fun applyBoosts(current: PetStatSnapshot, boosts: PetStatBoosts?): PetStatSnapshot {
            if (boosts == null) return current
            return PetStatSnapshot(
                hunger = boosts.hunger?.let { clampStat(current.hunger + it) } ?: current.hunger,
                cleanliness = boosts.cleanliness?.let { clampStat(current.cleanliness + it) } ?: current.cleanliness,
                happiness = boosts.happiness?.let { clampStat(current.happiness + it) } ?: current.happiness,
                health = boosts.health?.let { clampStat(current.health + it) } ?: current.health,
            )
        }

        /** 追加库存 growth log（[PetGrowthEventType.SPECIAL_EVENT]，上限 50 取末尾），1:1 iOS `appendInventoryLog`。 */
        internal fun appendInventoryLog(
            existing: List<PetGrowthLogEntry>,
            summary: String,
            now: Long,
        ): List<PetGrowthLogEntry> {
            val entry = PetGrowthLogEntry(
                id = UUID.randomUUID().toString(),
                timestamp = now,
                type = PetGrowthEventType.SPECIAL_EVENT.raw,
                summary = summary,
            )
            return (existing + entry).let { if (it.size > 50) it.takeLast(50) else it }
        }
    }
}

/** 宠物 4 项状态快照（[PetInventoryService.applyBoosts] 纯函数 I/O）。 */
internal data class PetStatSnapshot(
    val hunger: Int,
    val cleanliness: Int,
    val happiness: Int,
    val health: Int,
)
