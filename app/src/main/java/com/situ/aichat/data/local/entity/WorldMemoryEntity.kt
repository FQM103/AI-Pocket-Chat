package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * per-角色**双视角世界记忆**（W5 图纸 §3.1 / 契约 §9 联动闭环【核心】）：世界里发生的「大事」（初识/拌嘴/
 * 和好/里程碑）按当事各方视角、用各自口吻写成一条持久记忆，注入聊天让角色真实记得世界（模板零 LLM）。
 *
 * [uuid] 种子派生 `world:mem:{关系事件uuid}:{本视角角色uuid}`（禁改·幂等门）；[characterUuid] 视角主体（索引）；
 * [otherIdsJson] 其余当事人 uuid 数组（[com.situ.aichat.util.StringListJson]·删角清理判据）；[embedding] 语义向量
 * （[com.situ.aichat.prompt.memory.VectorMemoryService] 同款 float32 小端编码·null = 待后台限流回填）。本块只存。
 */
@Entity(
    tableName = "world_memory",
    indices = [Index("characterUuid"), Index("happenedAt")],
)
data class WorldMemoryEntity(
    @PrimaryKey val uuid: String,
    /** 这条记忆属于谁（视角主体·索引）。 */
    val characterUuid: String,
    /** 事件其余当事人 uuid 数组（StringListJson·删角清理判据）。 */
    val otherIdsJson: String = "[]",
    /** 源关系事件 kind（rel_first_meet 等）。 */
    val kindRaw: String,
    /** 视角记忆正文（§4.2 模板产出）。 */
    val content: String,
    /** 源事件 happenedAt（索引·聊天注入近层按时间取）。 */
    val happenedAt: Long,
    /** 源关系事件 uuid（溯源）。 */
    val sourceUuid: String,
    /** 首次写入时刻（= 源事件 settledAt·禁真时钟）。 */
    val createdAt: Long,
    /** 语义向量（float32 小端字节；null = 待后台限流回填）。 */
    val embedding: ByteArray? = null,
)
