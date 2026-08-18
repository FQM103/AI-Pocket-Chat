package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 日记角色点赞/贴表情（日记重设计 R3·契约 §2 F3）。评论调度时顺带按角色决定，**无额外 LLM 调用**；
 * emoji 从固定小集合选。同角色同条目唯一（unique index·插入用 IGNORE 防重，worker 重试安全）。
 * 删日记 FK 级联清理。
 */
@Entity(
    tableName = "diary_reactions",
    foreignKeys = [
        ForeignKey(
            entity = DiaryEntryEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["entryUuid"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("entryUuid"),
        Index(value = ["entryUuid", "characterUuid"], unique = true),
    ],
)
data class DiaryReactionEntity(
    @PrimaryKey val id: String,
    val entryUuid: String,
    val characterUuid: String,
    val emoji: String = "❤️",
    val timestamp: Long = System.currentTimeMillis(),
)
