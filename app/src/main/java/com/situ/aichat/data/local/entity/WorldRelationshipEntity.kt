package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * 角色↔角色**有向**关系边（契约 `FABLE5_WORLD_SYSTEM_PROPOSAL.md` §8.A 多维关系模型 / W1 图纸 §3）。
 *
 * **有向**：A→B 与 B→A 是两行（方向不对称——A 对 B 的信任 ≠ B 对 A 的信任）；主键 = (fromId, toId)，
 * 另建 toId 索引供「谁对我有关系」反查。[fromId]/[toId] 是**混合域**（角色 uuid 或原住民 id），
 * **故不设 FK**——删除清理走仓库层事务（`WorldSocialRepository`/`CharacterRepository`）。
 *
 * 数值轴（closeness/trust/tension）0–100 钳位、色彩/轨迹/纽带/渊源的推演都在 W4，本块只存。
 * [origin] = 关系事件「结痂」压缩后的沉淀地（细节淡去、结论留下·契约 §5）。
 */
@Entity(
    tableName = "world_relationship",
    primaryKeys = ["fromId", "toId"],
    indices = [Index("toId")],
)
data class WorldRelationshipEntity(
    val fromId: String,
    val toId: String,
    /** 可复合类型（JSON 数组字符串）。 */
    val typesJson: String = "[]",
    /** 亲密（0–100·钳位在 W4）。 */
    val closeness: Int = 0,
    /** 信任（0–100·钳位在 W4）。 */
    val trust: Int = 0,
    /** 张力（0–100·钳位在 W4）。 */
    val tension: Int = 0,
    /** 情感色彩标签。 */
    val colorRaw: String = "",
    /** 轨迹（warming/cooling/stable）。 */
    val trajectoryRaw: String = "stable",
    /** 纽带。 */
    val bond: String = "",
    /** 渊源（结痂压缩的沉淀地）。 */
    val origin: String = "",
    /** 离开世界 → 休眠。 */
    val dormant: Boolean = false,
    val updatedAt: Long = 0L,
)
