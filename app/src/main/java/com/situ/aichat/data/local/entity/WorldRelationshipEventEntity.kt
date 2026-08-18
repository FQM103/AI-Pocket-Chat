package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 关系事件流水（契约 `FABLE5_WORLD_SYSTEM_PROPOSAL.md` §8.A 弧线/结痂 / W1 图纸 §3）：两人之间发生的事的
 * 存储面。[pairKey] = `WorldIds.pairKey(a,b)`（无向对键·聚合两人历史），[actorId]/[targetId] 保留方向。
 *
 * (pairKey, happenedAt) 复合索引 = 按对键取历史、按时间排序。[kindRaw] 的 taxonomy（含压缩事件 "compaction"）
 * 与 [arcId] 弧线分组、[summary] 模板产出都在 W4，本块只存。
 */
@Entity(
    tableName = "world_relationship_event",
    indices = [Index("pairKey", "happenedAt")],
)
data class WorldRelationshipEventEntity(
    @PrimaryKey val uuid: String,
    /** 排序对键（`WorldIds.pairKey(a,b)`）。 */
    val pairKey: String,
    /** 发起方（保留方向）。 */
    val actorId: String,
    /** 对象方（保留方向）。 */
    val targetId: String,
    /** taxonomy 值（W4 定；含 "compaction"）。 */
    val kindRaw: String,
    /** 事件弧线分组（null = 不属任何弧线）。 */
    val arcId: String? = null,
    /** 模板产出的一句话。 */
    val summary: String,
    /** 事件时刻（epoch ms）。 */
    val happenedAt: Long,
    /** 结算写入时刻（epoch ms）。 */
    val settledAt: Long,
)
