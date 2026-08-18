package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 原住民状态（契约 `FABLE5_WORLD_SYSTEM_PROPOSAL.md` §5 / §8 / W1 图纸 §3）。花名册（slug→人设）在 W3
 * 代码内定义，本表只记**运行态**：是否已发现 + 双燃料眼缘 + 偶遇计数 + 招募指针 + 当前所在城。
 *
 * [narrativeFuel]（叙事燃料·眼缘）契约「不衰减」；[giftFuel]（心意燃料）。招募成正式角色后
 * [recruitedCharacterUuid] 指向新角色 uuid——删该角色时仓库层把此指针连同眼缘归零（契约 §11「缘分归零」）。
 * 阈值 / 招募逻辑属 W6，本块只存。
 */
@Entity(tableName = "world_native_state")
data class WorldNativeStateEntity(
    @PrimaryKey val nativeId: String,
    /** 是否已发现。 */
    val discovered: Boolean = false,
    val discoveredAt: Long? = null,
    /** 叙事燃料（眼缘·不衰减）。 */
    val narrativeFuel: Int = 0,
    /** 心意燃料。 */
    val giftFuel: Int = 0,
    /** 偶遇次数。 */
    val encounterCount: Int = 0,
    val lastEncounterAt: Long? = null,
    /** 招募成正式角色后的指针（null = 未招募）。 */
    val recruitedCharacterUuid: String? = null,
    /** 当前所在城（null = 按花名册家城）。 */
    val currentCityId: String? = null,
)
