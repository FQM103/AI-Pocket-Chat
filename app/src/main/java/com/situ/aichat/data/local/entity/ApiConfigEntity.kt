package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Mirrors the iOS `APIConfiguration` @Model.
 * The actual API key is NOT stored here (iOS kept it in Keychain) — only `apiKeyId`,
 * which references the secret in Android EncryptedSharedPreferences.
 */
@Entity(
    tableName = "api_configurations",
    indices = [Index("isActive")],
)
data class ApiConfigEntity(
    @PrimaryKey val uuid: String,
    val providerName: String,
    val providerTypeRaw: String = "deepseek",
    val apiKeyId: String,
    val baseURL: String,
    val modelName: String,
    val isActive: Boolean = false,
    val creationDate: Long,

    // Tool calling
    val toolCallingModeRaw: String = "auto",
    val detectedToolSupportLevelRaw: String = "unknown",
    val detectedToolProtocolFamilyRaw: String = "deepseek",
    val detectedStreamingToolSupportRaw: String = "unknown",
    val detectedThinkingToolSupportRaw: String = "unknown",
    val toolDetectionSummary: String = "",
    val toolDetectionCheckedAt: Long? = null,

    // Vision
    val visionModeRaw: String = "auto",
    val detectedVisionSupport: Int = -1,

    // Audio input
    val audioInputModeRaw: String = "auto",
    val detectedAudioInputSupport: Int = -1,

    // Model type / thinking
    val thinkingModelModeRaw: String = "auto",
    val detectedThinkingModelType: Int = -1,
    val thinkingBudgetLevelRaw: String = "auto",
    val maxOutputLengthRaw: String = "auto",
)
