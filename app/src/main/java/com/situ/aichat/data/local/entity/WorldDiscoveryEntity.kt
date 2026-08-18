package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 奇观/城市发现记录（契约 `FABLE5_WORLD_SYSTEM_PROPOSAL.md` §5 / W1 图纸 §3）。[placeId] = `wonder:<slug>`
 * 或 `city:<id>`；插入用 `OnConflictStrategy.IGNORE`（重复发现无害幂等）。发现逻辑属 W9+，本块只存。
 */
@Entity(tableName = "world_discovery")
data class WorldDiscoveryEntity(
    @PrimaryKey val placeId: String,
    val discoveredAt: Long,
)
