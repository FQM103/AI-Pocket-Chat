package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Mirrors the iOS `MomentNotification` @Model (Models/MomentNotification.swift). Drives the unread
 * red dot and (on Android, decision ①) a system notification deep-linking into the post.
 *
 * Deliberately loose-coupled to the post: iOS locates the post via [postTimestamp]
 * (`timeIntervalSince1970`, **seconds**) rather than a relationship, so a deleted post just yields a
 * "已删除" toast. The Android port keeps the same field (repository writes `post.timestamp / 1000.0`).
 * iOS has no uuid → Room uses a generated [id]. [contentPreview] is truncated to 100 chars at insert
 * time by the repository (iOS does it in the model init). iOS indexes `[\.isRead], [\.timestamp]`.
 */
@Entity(
    tableName = "moment_notification",
    indices = [Index("isRead"), Index("timestamp")],
)
data class MomentNotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** iOS `typeRaw`. Decode via `MomentNotificationType.fromRaw`. */
    val typeRaw: String = "commentOnUserPost",
    val timestamp: Long = System.currentTimeMillis(),
    val isRead: Boolean = false,

    /** The character that performed the interaction (iOS `characterUUID`). */
    val characterUuid: String = "",
    /** Comment/reply preview ("" for like-type notifications); truncated to 100 chars at insert. */
    val contentPreview: String = "",
    /** Owning post's `timestamp / 1000.0` (seconds) — loose-coupled locator, per iOS. */
    val postTimestamp: Double = 0.0,
)
