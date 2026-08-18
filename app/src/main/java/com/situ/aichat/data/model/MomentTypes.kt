package com.situ.aichat.data.model

import com.situ.aichat.data.local.entity.MomentPostEntity
import com.situ.aichat.util.StringListJson

/**
 * 1:1 port of iOS `MomentAuthorType` (Models/MomentPost.swift). Who authored a post / comment / like.
 * Persisted as [raw] (iOS rawValue); unknown/legacy values fall back to [USER] (iOS `?? .user`).
 *
 * ([com.situ.aichat.data.model.MomentTriggerType] — *why* a post was generated — lives in its own
 * file, shared with the diary module.)
 */
enum class MomentAuthorType(val raw: String) {
    USER("user"),
    CHARACTER("character"),
    ;

    companion object {
        fun fromRaw(raw: String): MomentAuthorType =
            entries.firstOrNull { it.raw == raw } ?: USER
    }
}

/**
 * 1:1 port of iOS `MomentNotificationType` (Models/MomentNotification.swift). Drives the unread red
 * dot + (on Android) the system notification routed via the COMPANION channel with a deep link into
 * the post detail — a deliberate divergence from iOS's in-app notification list (铁律#1: native
 * equivalent, decision ① for P7.2). Persisted as [raw]; unknown values fall back to
 * [COMMENT_ON_USER_POST] (iOS `?? .commentOnUserPost`).
 *
 * - [COMMENT_ON_USER_POST] 角色评论了用户的朋友圈
 * - [REPLY_TO_USER_COMMENT] 角色回复了用户的评论
 * - [LIKE_ON_USER_POST]     角色给用户的朋友圈点赞
 * - [CO_LIKE]               角色点赞了用户点赞过的朋友圈
 */
enum class MomentNotificationType(val raw: String) {
    COMMENT_ON_USER_POST("commentOnUserPost"),
    REPLY_TO_USER_COMMENT("replyToUserComment"),
    LIKE_ON_USER_POST("likeOnUserPost"),
    CO_LIKE("coLike"),
    ;

    companion object {
        fun fromRaw(raw: String): MomentNotificationType =
            entries.firstOrNull { it.raw == raw } ?: COMMENT_ON_USER_POST
    }
}

/**
 * Decoded image file paths for a moment post. iOS stores `imageDataArray: [Data]?` in external
 * storage; the Android port stores the photos as JPEGs via
 * [com.situ.aichat.util.ContentImageStore] and keeps their absolute paths as a JSON list in
 * [MomentPostEntity.imagePathsJson] (project convention: JSON string column, no Room TypeConverter —
 * same as the diary module's `DiaryEntryEntity.imagePaths`).
 */
val MomentPostEntity.imagePaths: List<String>
    get() = StringListJson.decode(imagePathsJson)
