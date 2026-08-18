package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Mirrors the iOS `RelationshipMilestone` @Model. iOS had no explicit id; we add a `uuid`
 * primary key. triggerTypeRaw: "aiAutomatic" | "userAdvance".
 */
@Entity(
    tableName = "relationship_milestones",
    foreignKeys = [
        ForeignKey(
            entity = CharacterEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["characterUuid"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("characterUuid")],
)
data class MilestoneEntity(
    @PrimaryKey val uuid: String,
    val characterUuid: String,
    val relationshipName: String,
    val establishedDate: Long,
    val reason: String = "初始设定",
    val triggerTypeRaw: String = "userAdvance",
    val phase: String? = null,
)
