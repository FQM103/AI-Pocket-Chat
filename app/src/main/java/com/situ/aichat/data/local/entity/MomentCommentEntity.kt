package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Mirrors the iOS `MomentComment` @Model (Models/MomentComment.swift). A comment on a 朋友圈 post,
 * authored by the user or an AI character. Unlike diary comments, moment comments form a tree
 * (replies), so iOS keeps a stable business-key `uuid` — reused here as the Room primary key (the
 * tree is built from the flat list via [parentCommentUuid], never the inverse `replies` array; see
 * `MomentCommentTreeBuilder`).
 *
 * Two cascading FKs (both nullable — null = no constraint, matching iOS optional relationships):
 * - [postUuid] → [MomentPostEntity].uuid: deleting a post removes its comments.
 * - [parentCommentUuid] → this table's uuid (self-ref): deleting a comment removes its replies,
 *   mirroring iOS `@Relationship(deleteRule: .cascade, inverse: \MomentComment.parentComment)`.
 *
 * Both FK columns are indexed (FK-perf; Room would otherwise warn).
 */
@Entity(
    tableName = "moment_comment",
    foreignKeys = [
        ForeignKey(
            entity = MomentPostEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["postUuid"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MomentCommentEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["parentCommentUuid"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("postUuid"), Index("parentCommentUuid")],
)
data class MomentCommentEntity(
    @PrimaryKey val uuid: String,
    val content: String = "",
    val timestamp: Long = System.currentTimeMillis(),

    /** iOS `authorTypeRaw`; "user"/"character". Decode via `MomentAuthorType.fromRaw`. */
    val authorTypeRaw: String = "user",
    /** Set when [authorTypeRaw] == "character": the commenting AI character's uuid. */
    val characterUuid: String? = null,

    /** "回复 @xxx" display name when this is a reply (iOS `replyToName`). */
    val replyToName: String? = null,

    /** Owning post (FK CASCADE). */
    val postUuid: String? = null,
    /** Parent comment uuid; null = top-level (iOS `parentComment == nil`). Self-ref FK CASCADE. */
    val parentCommentUuid: String? = null,
)
