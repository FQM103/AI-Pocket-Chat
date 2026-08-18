package com.situ.aichat.pet

import kotlinx.serialization.Serializable

/**
 * 宠物扩展元数据（1:1 iOS `PetMetadata`，存 `CharacterPetEntity.petMetadataJson`）。
 *
 * Android 适配：① 日期用 epoch 毫秒 `Long?`（项目惯例，非 iOS Date 的 secondsSinceReferenceDate）；
 * ② 全字段带默认值 + [PetJson] 的 `ignoreUnknownKeys`/`coerceInputValues` = iOS `decodeIfPresent` 的
 * 向后兼容（旧数据缺字段→默认）；③ 不可变 data class + 函数式 copy 变更（替 iOS 值类型 mutating）。
 */
@Serializable
data class PetMetadata(
    val favoriteFood: String = "",
    val favoriteToy: String = "",
    val unlockSource: String = "",
    val lastDecayDate: Long? = null,
    // 看病/寻回
    val treatmentCount: Int = 0,
    val searchAttempts: Int = 0,
    val searchStartDate: Long? = null,
    val trustRecovery: Double = 0.0,
    // 训练/技能
    val playCount: Int = 0,
    val learnedTricks: List<String> = emptyList(),
    // 散步/外出
    val walkStartTime: Long? = null,
    val lastWalkDate: Long? = null,
    val souvenirs: List<PetSouvenir> = emptyList(),
    val dailyWalkCount: Int = 0,
    val lastWalkCountDate: Long? = null,
    // 趋势箭头快照
    val lastViewedHunger: Int? = null,
    val lastViewedCleanliness: Int? = null,
    val lastViewedHappiness: Int? = null,
    val lastViewedHealth: Int? = null,
    // 用品库存（朋友圈/日记联动 + 商店 P9 用）
    val petInventory: PetInventoryData = PetInventoryData.EMPTY,
    val recentExpensivePurchases: List<PetExpensivePurchaseRecord> = emptyList(),
    // P1-34 成就解锁基线（安卓超越字段，iOS 无）：null=从未计算→care() 首算静默 seed 不弹 toast；
    // 非 null=上次 care() 后的 achievedIDs 快照（存 sorted 保 JSON 稳定）。缺字段旧 JSON→null 免迁移。
    val lastComputedAchievedIds: List<String>? = null,
) {
    companion object {
        val EMPTY = PetMetadata()
    }
}

/** 成长日志条目（最多 50 条），1:1 iOS `PetGrowthLogEntry`。type 存 [PetGrowthEventType] rawValue。 */
@Serializable
data class PetGrowthLogEntry(
    val id: String = "",
    val timestamp: Long = 0L,
    val type: String = PetGrowthEventType.SPECIAL_EVENT.raw,
    val summary: String = "",
)

/** 散步纪念品（图鉴），1:1 iOS `PetSouvenir`。 */
@Serializable
data class PetSouvenir(
    val id: String = "",
    val name: String = "",
    val emoji: String = "",
    val obtainedDate: Long = 0L,
    val eventDescription: String = "",
)

/** 贵价购买记录（≥300 金币写入，朋友圈/日记联动用），1:1 iOS `PetExpensivePurchaseRecord`。 */
@Serializable
data class PetExpensivePurchaseRecord(
    val itemId: String = "",
    val purchasedAt: Long = 0L,
)

/**
 * 用品库存（1:1 iOS `PetInventoryData`）：消耗品存累计数量，永久品恒为 1；单装扮位 [equippedItemId]。
 * iOS 是值类型 `mutating`；Android 用不可变 + 函数式返回新实例（`adding`/`removing`/`equipping`）。
 */
@Serializable
data class PetInventoryData(
    val owned: Map<String, Int> = emptyMap(),
    val equippedItemId: String? = null,
) {
    fun has(itemId: String): Boolean = (owned[itemId] ?: 0) > 0
    fun quantity(itemId: String): Int = owned[itemId] ?: 0

    /** 加入 [count] 份（默认 1）；count<=0 不变。 */
    fun adding(itemId: String, count: Int = 1): PetInventoryData {
        if (count <= 0) return this
        return copy(owned = owned + (itemId to (quantity(itemId) + count)))
    }

    /** 扣减 [count] 份；扣到 0 删 key，且若摘除的正是当前佩戴装扮则自动 unequip（1:1 iOS remove）。 */
    fun removing(itemId: String, count: Int = 1): PetInventoryData {
        if (count <= 0) return this
        val current = owned[itemId] ?: return this
        val newCount = current - count
        return if (newCount <= 0) {
            copy(
                owned = owned - itemId,
                equippedItemId = if (equippedItemId == itemId) null else equippedItemId,
            )
        } else {
            copy(owned = owned + (itemId to newCount))
        }
    }

    /** 佩戴装扮（必须已拥有，否则不变）。 */
    fun equipping(itemId: String): PetInventoryData = if (has(itemId)) copy(equippedItemId = itemId) else this

    /** 摘下装扮。 */
    fun unequipping(): PetInventoryData = copy(equippedItemId = null)

    companion object {
        val EMPTY = PetInventoryData()
    }
}
