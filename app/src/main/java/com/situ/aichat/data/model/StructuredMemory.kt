package com.situ.aichat.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 1:1 port of iOS `StructuredMemory` (the 10 relationship "key facts"). Field names match iOS for
 * future backup compatibility. Populated by the memory system (M05); decoded from
 * `CharacterEntity.structuredMemoryJSON`.
 */
@Serializable
data class StructuredMemory(
    val nicknameFromChar: String = "",   // TA 对你的称呼
    val nicknameToChar: String = "",     // 你对 TA 的称呼
    val insideJoke: String = "",         // 内部梗
    val deepestChat: String = "",        // 最深刻的聊天
    val impressionOfUser: String = "",   // TA 眼中的你
    val sharedLikes: String = "",        // 共同喜欢的东西
    val learnedPhrase: String = "",      // TA 学会的口头禅
    val importantPromise: String = "",   // 最重要的约定
    val firstConflict: String = "",      // 第一次闹矛盾
    val comfortStyle: String = "",       // TA 的安慰方式
) {
    val hasAnyData: Boolean
        get() = nicknameFromChar.isNotEmpty() || nicknameToChar.isNotEmpty() ||
            insideJoke.isNotEmpty() || deepestChat.isNotEmpty() ||
            impressionOfUser.isNotEmpty() || sharedLikes.isNotEmpty() ||
            learnedPhrase.isNotEmpty() || importantPromise.isNotEmpty() ||
            firstConflict.isNotEmpty() || comfortStyle.isNotEmpty()

    /** 序列化为 JSON（camelCase 键，与 iOS StructuredMemory.CodingKeys 一致，含全部 10 键）。 */
    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        val EMPTY = StructuredMemory()

        // encodeDefaults=true → 输出含全部 10 个键（对齐 iOS 无条件 encode 每个字段）。
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun decode(jsonStr: String): StructuredMemory {
            if (jsonStr.isEmpty()) return EMPTY
            return runCatching { json.decodeFromString(serializer(), jsonStr) }.getOrDefault(EMPTY)
        }
    }
}

/**
 * 1:1 port of iOS `StructuredMemoryMetadata`（CharacterGrowthTypes.swift）——结构化记忆的抽取调度元数据。
 * 仅本地调度用：[lastExtractionDate] 用 epoch millis（iOS 存 Date，跨端备份不互通，恢复时降级为默认即可，无副作用）。
 */
@Serializable
data class StructuredMemoryMetadata(
    val lastExtractionDate: Long? = null,
    val roundsSinceLastExtraction: Int = 0,
    val totalExtractionCount: Int = 0,
) {
    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        val EMPTY = StructuredMemoryMetadata()

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun decode(jsonStr: String): StructuredMemoryMetadata {
            if (jsonStr.isEmpty()) return EMPTY
            return runCatching { json.decodeFromString(serializer(), jsonStr) }.getOrDefault(EMPTY)
        }
    }
}
