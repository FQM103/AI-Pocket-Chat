package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * 角色 × 世界书绑定（多对多，用户拍板 2026-07-02 维持）：一本书可配多个角色（「世界观 × 角色」矩阵），
 * 一个角色可叠多本书（内嵌书 + 世界观大书，酒馆标准玩法）。全局书（[WorldBookEntity.isGlobal]）不走本表。
 *
 * 双向 FK 级联（照 character_pet / character_wallet 范式）：删角色或删书都自动清绑定——绑定不派生
 * 通知等外部资源，级联安全（对比 [MeetingAppointmentEntity] 刻意无 FK 的理由，这里不适用）。
 *
 * ⚠️ 与「世界系统”互斥（用户拍板 2026-07-02·契约 §11）：已「加入世界」的角色不得建绑定——
 * 校验在世界系统落地其开关时接入（`FABLE5_WORLD_SYSTEM_PROPOSAL.md` §22 决策 21），本表不管语义。
 */
@Entity(
    tableName = "world_book_bindings",
    primaryKeys = ["characterUuid", "bookUuid"],
    foreignKeys = [
        ForeignKey(
            entity = CharacterEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["characterUuid"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = WorldBookEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["bookUuid"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("bookUuid")],
)
data class WorldBookBindingEntity(
    val characterUuid: String,
    val bookUuid: String,
    /** 绑定时刻（UI 按绑定先后展示用）。 */
    val createdAt: Long = System.currentTimeMillis(),
)
