package com.situ.aichat.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 一次送礼对角色 8 维 [RelationshipQuality] 的增量（1:1 iOS `GiftRelationshipImpact`）。
 *
 * 维度索引/值顺序严格对齐 `RelationshipQuality.DIMENSION_KEYS`：
 * `[familiarity, trust, closeness, rapport, respect, fun, tension, attachment]`（iOS 键 "fun"，Kotlin 关键字 → [funValue]）。
 *
 * 由 [com.situ.aichat.gift.GiftRelationshipImpactService.compute] 产出，apply 时累加到角色 relationshipQuality（每维
 * clamp[0,100]），同时序列化到 `GiftRecord.relationshipImpactJSON` 做审计 + 备份。
 */
@Serializable
data class GiftRelationshipImpact(
    val familiarity: Int = 0,                       // 熟悉度
    val trust: Int = 0,                             // 信任感
    val closeness: Int = 0,                         // 亲近感
    val rapport: Int = 0,                           // 默契度
    val respect: Int = 0,                           // 尊重感
    @SerialName("fun") val funValue: Int = 0,       // 趣味性
    val tension: Int = 0,                           // 张力值
    val attachment: Int = 0,                        // 依恋度
) {
    /** 空 impact（所有维度为 0），无需 apply。 */
    val isEmpty: Boolean
        get() = familiarity == 0 && trust == 0 && closeness == 0 && rapport == 0 &&
            respect == 0 && funValue == 0 && tension == 0 && attachment == 0

    /** 按维度索引读取（0-7，对应 DIMENSION_KEYS）。 */
    fun value(index: Int): Int = when (index) {
        0 -> familiarity
        1 -> trust
        2 -> closeness
        3 -> rapport
        4 -> respect
        5 -> funValue
        6 -> tension
        7 -> attachment
        else -> 0
    }

    /** 序列化为紧凑 JSON（存 GiftRecord.relationshipImpactJSON；编码全 8 键含 0 = iOS JSONEncoder）。 */
    fun toJson(): String = runCatching { json.encodeToString(this) }.getOrDefault("")

    companion object {
        private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

        /** 从 JSON 反序列化；空/损坏字符串返回 null（1:1 iOS `decode(from:)`）。 */
        fun decode(jsonStr: String): GiftRelationshipImpact? =
            if (jsonStr.isBlank()) null
            else runCatching { json.decodeFromString<GiftRelationshipImpact>(jsonStr) }.getOrNull()
    }
}
