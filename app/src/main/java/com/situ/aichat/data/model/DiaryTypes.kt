package com.situ.aichat.data.model

import com.situ.aichat.data.local.entity.DiaryEntryEntity
import com.situ.aichat.util.StringListJson

/**
 * 1:1 port of iOS `DiaryVisibility` (Models/DiaryEntry.swift). Persisted as [raw] (iOS rawValue);
 * unknown/legacy values fall back to [OPEN_TO_AI] (iOS `?? .openToAI`).
 *
 * - [PRIVATE]   仅自己可见 — AI 角色不会评论
 * - [OPEN_TO_AI] AI 好友可见 — 发布后可触发角色评论
 *
 * Display strings + icons live in the UI layer (string resources / Material icons), not here.
 */
enum class DiaryVisibility(val raw: String) {
    PRIVATE("private"),
    OPEN_TO_AI("openToAI"),
    ;

    companion object {
        fun fromRaw(raw: String): DiaryVisibility =
            entries.firstOrNull { it.raw == raw } ?: OPEN_TO_AI
    }
}

/**
 * Decoded image file paths for a diary entry. iOS stores `imageDataArray: [Data]?` in external
 * storage; the Android port stores the photos as JPEGs via
 * [com.situ.aichat.util.ContentImageStore] and keeps their absolute paths as a JSON list in
 * [DiaryEntryEntity.imagePathsJson] (project convention: JSON string column, no Room TypeConverter).
 */
val DiaryEntryEntity.imagePaths: List<String>
    get() = StringListJson.decode(imagePathsJson)
