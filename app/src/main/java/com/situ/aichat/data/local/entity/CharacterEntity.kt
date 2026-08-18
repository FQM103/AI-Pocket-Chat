package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Mirrors the iOS `AICharacter` @Model. Growth / structured-memory data is kept as JSON
 * strings (same as iOS), decoded by the domain layer via kotlinx.serialization.
 * Enums are stored as their iOS rawValue strings for forward backup compatibility.
 */
@Entity(
    tableName = "characters",
    indices = [Index("creationDate")],
)
data class CharacterEntity(
    @PrimaryKey val uuid: String,
    val name: String,
    val avatarPath: String? = null,
    // 聊天壁纸：per-角色全屏壁纸绝对路径（空=无壁纸→聊天纯色底 / 见面全局背景，零变化）。见 FABLE5_CHAT_WALLPAPER_PROPOSAL.md。
    val chatWallpaperPath: String? = null,
    val systemPrompt: String = "",
    val personalityDescription: String = "",
    val creationDate: Long,

    // Identity
    val gender: String = "",
    val birthday: Long? = null,
    val ageModeRaw: String = "growing",
    val fixedAge: Int = 0,
    val appearanceDescription: String = "",
    val occupation: String = "",
    val backstory: String = "",
    val speakingStyle: String = "",
    val catchphrases: String = "",
    val exampleDialogues: String = "",
    val initialInterests: String = "",

    // Memory summaries
    val memorySummary: String = "",
    val previousMemorySummary: String = "",
    val offlineMeetingMemorySummary: String = "",

    // Voice (remoteVoiceID / tts* used on Android; voiceIdentifier is iOS-only, kept for parity)
    val voiceIdentifier: String = "",
    val remoteVoiceID: String = "",
    val ttsEmotionRaw: String = "auto",
    val ttsSpeed: Double = 1.0,
    val ttsPitch: Int = 0,

    // Mood
    val lastMoodEmoji: String = "",
    val lastMoodText: String = "",
    val lastMoodColorName: String = "green",

    // Companion stats
    val firstMessageDate: Long? = null,
    val streakCount: Int = 0,
    val lastChatDate: Long? = null,

    // Growth & structured memory (JSON blobs, mirror iOS)
    val personalitySpectrumJSON: String = "",
    val relationshipQualityJSON: String = "",
    // 成长原型校准（图纸 docs/handoff/2026-07-11-成长原型校准.md D-2）：名分识别出的关系原型 id
    // （null = 无名分 / 词表未识别 / 存量未扫）。渲染侧据此三分支调度；写侧由校准器单点维护。
    val relationshipArchetypeId: String? = null,
    val moodHistoryJSON: String = "",
    val dynamicInterestsJSON: String = "",
    val growthLogJSON: String = "",
    val growthMetadataJSON: String = "",
    val structuredMemoryJSON: String = "",
    val structuredMemoryMetadataJSON: String = "",
    val previousStructuredMemoryJSON: String = "",

    // Affinity-sense cache
    val affinitySensePackageJSON: String = "",
    val affinitySensePackageGeneratedAt: Long? = null,

    // Relationship analysis counters
    val relationshipMessageCount: Int = 0,
    val lastRelationshipAnalysisDate: Long? = null,

    // Location
    val cityName: String? = null,
    val cityLatitude: Double? = null,
    val cityLongitude: Double? = null,

    // Offline meeting personalization
    val offlineThemeColorHex: String? = null,

    // World system (契约 FABLE5_WORLD_SYSTEM_PROPOSAL.md §6 / W1 图纸 §3)：角色的「加入世界」态 + 住址。
    // 默认「不加入」= 私密 1:1 陪伴、不进世界（旧角色迁移回填 joinedWorld=false / worldHomeCityId='city_yunye'）。
    // 加入/离开的世界事件、互斥校验、住址生效均属 W6/W13——本块只加列，不做任何校验或联动。
    val joinedWorld: Boolean = false,
    val worldHomeCityId: String = "city_yunye",
    val worldJoinedAt: Long? = null,

    /**
     * 朋友圈消化水位线（记忆改造一期·朋友圈消化·图纸 §3.5-B）：已消化进长期记忆的朋友圈动态时间戳上界
     * （epoch millis）。0 = 从未消化（新装 / 旧备份）→ 收集时视作 now−7 天起步，绝不深挖历史。
     */
    val momentsDigestedUntilMillis: Long = 0,
)
