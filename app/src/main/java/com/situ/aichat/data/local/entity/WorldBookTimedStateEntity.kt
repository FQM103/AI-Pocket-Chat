package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * 世界书时效三件套的运行时状态（sticky 保持 / cooldown 冷却），**按会话隔离**——
 * ST 把它存在 chat metadata 里，我们等价落表（契约 §4.1）。delay（延迟解锁）只需比较会话消息总数、
 * 无状态可存，不进本表。
 *
 * 语义：条目在某会话触发时落一行（[effectType] = sticky/cooldown 各自独立），锚点 = 触发时刻的
 * 会话消息计数 [triggeredAtMessageCount]，时长 = 触发当时条目的 sticky/cooldown 值快照
 * [durationMessages]（快照而非实时读条目——触发后用户改条目数值不影响已生效的窗口，与 ST 一致）。
 * 引擎判定：当前消息计数 < 锚点 + 时长 ⇒ 窗口内；过期行由引擎在扫描时顺手清理。
 *
 * 双 FK 级联：删会话 / 删条目自动清状态（无派生外部资源，级联安全）。
 */
@Entity(
    tableName = "world_book_timed_states",
    primaryKeys = ["conversationUuid", "entryUuid", "effectType"],
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["conversationUuid"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = WorldBookEntryEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["entryUuid"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("entryUuid")],
)
data class WorldBookTimedStateEntity(
    val conversationUuid: String,
    val entryUuid: String,
    /** 时效类型 raw："sticky" | "cooldown"（各自独立成行，可同时存在）。 */
    val effectType: String,
    /** 触发时刻的会话消息计数（窗口起点锚）。 */
    val triggeredAtMessageCount: Int = 0,
    /** 触发当时的时长快照（条数）。 */
    val durationMessages: Int = 0,
)
