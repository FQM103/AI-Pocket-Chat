package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Mirrors the iOS `DiaryComment` @Model (Models/DiaryComment.swift). All diary comments are written
 * by AI characters (the user cannot comment, only delete). iOS has no uuid on the comment (located
 * via the parent's `comments` array); Room needs a primary key, so [id] is a generated UUID.
 *
 * Deleting the parent diary entry cascades here (FK on [entryUuid]).
 */
@Entity(
    tableName = "diary_comments",
    foreignKeys = [
        ForeignKey(
            entity = DiaryEntryEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["entryUuid"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("entryUuid")],
)
data class DiaryCommentEntity(
    @PrimaryKey val id: String,
    val entryUuid: String,
    val content: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    /** Commenting AI character's uuid (iOS `characterUUID`); null + [isFromUser]=true = 用户回复。 */
    val characterUuid: String? = null,

    // 评论区活化（日记重设计 R3·契约 §2 F3）：一层回复线程 + 用户参与。
    /** 根评论 id（null = 顶层角色评论；非 null = 该根下的回复——用户回复或角色回应）。 */
    val parentCommentId: String? = null,
    /** true = 用户写的回复（每条根评论下限 1 轮：用户回复→角色回应各一条）。 */
    val isFromUser: Boolean = false,
)
