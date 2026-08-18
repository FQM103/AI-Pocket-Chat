package com.situ.aichat.data.local.entity

import androidx.room.Embedded
import androidx.room.Relation

/**
 * A moment post together with its comments and likes, fetched in one shot (avoids N+1 when rendering
 * the feed/detail — iOS reads `post.comments` / `post.likes` directly). Use the DAO's `@Transaction`
 * queries that return this POJO.
 *
 * The comment tree is built from [comments] (the flat list) via `MomentCommentTreeBuilder`, never the
 * inverse `replies` array (iOS hit refresh-ordering bugs doing that across background contexts).
 */
data class MomentPostWithRelations(
    @Embedded val post: MomentPostEntity,
    @Relation(parentColumn = "uuid", entityColumn = "postUuid")
    val comments: List<MomentCommentEntity>,
    @Relation(parentColumn = "uuid", entityColumn = "postUuid")
    val likes: List<MomentLikeEntity>,
)
