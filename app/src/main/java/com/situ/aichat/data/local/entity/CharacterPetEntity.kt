package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 角色绑定的电子宠物（1:1 iOS `CharacterPet` @Model, Models/CharacterPet.swift）。1 角色 : 0~1 宠物，
 * 角色删除级联删宠物（FK CASCADE + characterUuid 唯一索引）。
 *
 * 状态初值对齐 iOS：饥饿 0（饱）/清洁 100/心情 80/健康 100。成长日志与扩展元数据走 JSON 列
 * （`PetJson` 解码；访问器在 `pet/PetAccessors.kt`），不入独立表——与项目 growth/diary/moment 一致。
 */
@Entity(
    tableName = "character_pet",
    foreignKeys = [
        ForeignKey(
            entity = CharacterEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["characterUuid"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("characterUuid", unique = true)],
)
data class CharacterPetEntity(
    @PrimaryKey val uuid: String = UUID.randomUUID().toString(),
    val name: String = "",
    val speciesRaw: String = "cat",
    val isHiddenSpecies: Boolean = false,
    val personalityTypeRaw: String = "lively",
    val adoptedDate: Long = System.currentTimeMillis(),
    val hunger: Int = 0,
    val cleanliness: Int = 100,
    val happiness: Int = 80,
    val health: Int = 100,
    val growthStageRaw: String = "baby",
    val growthPoints: Int = 0,
    val totalInteractions: Int = 0,
    val lastFedDate: Long? = null,
    val lastCleanedDate: Long? = null,
    val lastPlayedDate: Long? = null,
    val lastInteractionDate: Long? = null,
    val neglectPhaseRaw: String = "none",
    /** List<PetGrowthLogEntry> 的 JSON（`PetJson.decodeGrowthLog`）。"" = 空。 */
    val petGrowthLogJson: String = "",
    /** PetMetadata 的 JSON（`PetJson.decodeMetadata`，含 inventory/souvenirs/walk/trust）。"" = 空。 */
    val petMetadataJson: String = "",
    val characterUuid: String = "",
)
