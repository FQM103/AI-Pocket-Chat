package com.situ.aichat.data.local.entity

import androidx.room.Embedded
import androidx.room.Relation

/**
 * A diary entry together with its comments, fetched in one shot (avoids N+1 when rendering the
 * timeline/list, where each card shows a comment count + last-2 preview — iOS reads `entry.comments`
 * directly). Use the DAO's `@Transaction` queries that return this POJO.
 */
data class DiaryEntryWithComments(
    @Embedded val entry: DiaryEntryEntity,
    @Relation(parentColumn = "uuid", entityColumn = "entryUuid")
    val comments: List<DiaryCommentEntity>,
    /** 角色点赞（R3 评论区活化）：列表卡计数 + 详情 reaction 行，同批查询免 N+1。 */
    @Relation(parentColumn = "uuid", entityColumn = "entryUuid")
    val reactions: List<DiaryReactionEntity> = emptyList(),
)
