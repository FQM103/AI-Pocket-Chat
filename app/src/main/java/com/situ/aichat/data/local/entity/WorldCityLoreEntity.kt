package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 首访点亮风物志（契约 `FABLE5_WORLD_SYSTEM_PROPOSAL.md` §7.A / W1 图纸 §3）：程序城首访时写一次，
 * **一次定稿 = 永久 canon**。DAO 插入用 `OnConflictStrategy.IGNORE`、仓库层 `canonizeLore()` 已存在则不覆盖。
 *
 * [loreJson] 结构与生成（LLM 点亮）属 W9，本块只存。
 */
@Entity(tableName = "world_city_lore")
data class WorldCityLoreEntity(
    @PrimaryKey val cityId: String,
    /** 风物志 JSON（一次定稿）。 */
    val loreJson: String = "{}",
    val generatedAt: Long,
)
