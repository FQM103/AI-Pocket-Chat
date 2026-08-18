package com.situ.aichat.data.local.entity

import androidx.room.Entity

/**
 * 世界 LLM 每日花费台账（W5 图纸 §3.1 / §3.3 / 契约 §7.A 每日预算硬顶）：`(epochDay, category)` 复合主键，
 * [count] = 当日该类目已消费的 LLM 调用次数。开机小报润色先扣后调（防失败重试烧钱），到档位上限即退模板。
 *
 * **设备本地台账·不入备份**（W5 图纸 §3.4）。30 天前的旧行由 [com.situ.aichat.world.bulletin.WorldLlmBudget]
 * 顺带清理。本块只存。
 */
@Entity(
    tableName = "world_llm_spend",
    primaryKeys = ["epochDay", "category"],
)
data class WorldLlmSpendEntity(
    /** 本地 epochDay。 */
    val epochDay: Long,
    /** 花费类目（如 "bulletin"）。 */
    val category: String,
    /** 当日该类目已消费次数。 */
    val count: Int,
)
