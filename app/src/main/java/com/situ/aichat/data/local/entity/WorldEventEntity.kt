package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 世界事件（契约 `FABLE5_WORLD_SYSTEM_PROPOSAL.md` §5 / §6 / W1 图纸 §3）：**须被记住/通知/小报消费的**事件
 * （能派生的不入库）。join/leave/move/encounter/recruit/arrival/relationship… 的取值域在 W2+ 定。
 *
 * [involvedIdsJson] = 参与者 id JSON 数组（混合域）；[notifiedAt] null = 未通知（W8 去重用）、
 * [seenAt] null = 未被小报消费。happenedAt 索引供按时间取近期事件。本块只存，不做任何通知/小报逻辑。
 */
@Entity(
    tableName = "world_event",
    indices = [Index("happenedAt")],
)
data class WorldEventEntity(
    @PrimaryKey val uuid: String,
    /** join/leave/move/encounter/recruit/arrival/relationship…（W2+ 定值）。 */
    val kindRaw: String,
    /** 参与者 id JSON 数组。 */
    val involvedIdsJson: String = "[]",
    /** 发生地（null = 无特定城）。 */
    val cityId: String? = null,
    /** 模板文案。 */
    val summary: String,
    val happenedAt: Long,
    /** null = 未通知（W8 去重用）。 */
    val notifiedAt: Long? = null,
    /** null = 未被小报消费。 */
    val seenAt: Long? = null,
)
