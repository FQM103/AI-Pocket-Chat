package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Mirrors the iOS `MomentLike` @Model (Models/MomentLike.swift). A like on a 朋友圈 post. iOS has no
 * uuid (dedup is by post+character), so Room uses a generated [id] primary key plus a
 * `(postUuid, characterUuid)` index for the existence check (`existsLike`) and to back the post FK.
 *
 * Deleting a post cascades here (FK on [postUuid]).
 */
@Entity(
    tableName = "moment_like",
    foreignKeys = [
        ForeignKey(
            entity = MomentPostEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["postUuid"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["postUuid", "characterUuid"])],
)
data class MomentLikeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),

    /** iOS `authorTypeRaw`; "user"/"character". Decode via `MomentAuthorType.fromRaw`. */
    val authorTypeRaw: String = "user",
    /** Set when [authorTypeRaw] == "character": the liking AI character's uuid (null = user like). */
    val characterUuid: String? = null,

    /** Owning post (FK CASCADE). */
    val postUuid: String? = null,
)
