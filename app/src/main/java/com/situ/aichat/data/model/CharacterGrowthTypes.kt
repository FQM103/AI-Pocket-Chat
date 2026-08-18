package com.situ.aichat.data.model

import com.situ.aichat.data.local.entity.CharacterEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * 1:1 port of iOS `CharacterGrowthTypes.swift` — the value types behind the 成长/关系 system (M14):
 * 8-dim 性格光谱 [PersonalitySpectrum], 8-dim 关系质感 [RelationshipQuality], 动态兴趣 [DynamicInterest],
 * 成长日志 [GrowthLogEntry], 成长元数据 [GrowthAnalysisMetadata], 情绪历史 [MoodHistoryEntry].
 *
 * Each is stored as a JSON string column on [CharacterEntity] (mirroring iOS's `@Transient` JSON-backed
 * properties); decode/encode goes through [GrowthJson]. JSON keys (`@SerialName`) match iOS exactly so
 * backups (M18) round-trip. **Dates are epoch millis (`Long`)** rather than iOS's reference-date `Double`
 * — these fields are only read by the local analysis pipeline, and the M05 precedent
 * ([StructuredMemoryMetadata]) already uses epoch millis (cross-platform backup of these blobs degrades
 * gracefully to defaults, no functional impact).
 *
 * Initial values are NOT all zero — they replicate iOS exactly (性格全 50；关系 8 维各有非零起点)。
 */

// MARK: - 性格光谱（8 个维度，每个 0~100，全部初始 50）

@Serializable
data class PersonalitySpectrum(
    val extroversion: Int = 50,      // 外向性
    val emotionality: Int = 50,      // 情绪化
    val adventurousness: Int = 50,   // 冒险性
    val warmth: Int = 50,            // 温暖度
    val humor: Int = 50,             // 幽默感
    val independence: Int = 50,      // 独立性
    val curiosity: Int = 50,         // 好奇心
    val openness: Int = 50,          // 坦诚度
) {
    /** 按维度顺序返回所有数值（顺序对应 [DIMENSION_KEYS]）。 */
    val values: List<Int>
        get() = listOf(extroversion, emotionality, adventurousness, warmth, humor, independence, curiosity, openness)

    /** 通过索引设置维度值（clamp [0,100]），返回新副本（iOS `setValue` 的不可变等价）。 */
    fun setValue(index: Int, value: Int): PersonalitySpectrum {
        val c = value.coerceIn(0, 100)
        return when (index) {
            0 -> copy(extroversion = c)
            1 -> copy(emotionality = c)
            2 -> copy(adventurousness = c)
            3 -> copy(warmth = c)
            4 -> copy(humor = c)
            5 -> copy(independence = c)
            6 -> copy(curiosity = c)
            7 -> copy(openness = c)
            else -> this
        }
    }

    companion object {
        /** 中性默认值（新角色起点，全部 50）。 */
        val NEUTRAL = PersonalitySpectrum()

        val DIMENSION_NAMES = listOf("外向性", "情绪化", "冒险性", "温暖度", "幽默感", "独立性", "好奇心", "坦诚度")
        val DIMENSION_KEYS = listOf("extroversion", "emotionality", "adventurousness", "warmth", "humor", "independence", "curiosity", "openness")
    }
}

// MARK: - 关系质感（8 个维度，每个 0~100，初始值非全 0）

@Serializable
data class RelationshipQuality(
    val familiarity: Int = 10,       // 熟悉度
    val trust: Int = 20,             // 信任感
    val closeness: Int = 10,         // 亲近感
    val rapport: Int = 10,           // 默契度
    val respect: Int = 35,           // 尊重感（礼貌性尊重，随深入了解增长）
    @SerialName("fun") val funValue: Int = 20, // 趣味性（iOS 键名 "fun"，Kotlin 关键字 → 属性名 funValue）
    val tension: Int = 5,            // 张力值
    val attachment: Int = 5,         // 依恋度
) {
    /** 按维度顺序返回所有数值（顺序对应 [DIMENSION_KEYS]）。 */
    val values: List<Int>
        get() = listOf(familiarity, trust, closeness, rapport, respect, funValue, tension, attachment)

    /** 通过索引设置维度值（clamp [0,100]），返回新副本。 */
    fun setValue(index: Int, value: Int): RelationshipQuality {
        val c = value.coerceIn(0, 100)
        return when (index) {
            0 -> copy(familiarity = c)
            1 -> copy(trust = c)
            2 -> copy(closeness = c)
            3 -> copy(rapport = c)
            4 -> copy(respect = c)
            5 -> copy(funValue = c)
            6 -> copy(tension = c)
            7 -> copy(attachment = c)
            else -> this
        }
    }

    companion object {
        /** 初始陌生人状态（新关系起点）。 */
        val INITIAL = RelationshipQuality()

        val DIMENSION_NAMES = listOf("熟悉度", "信任感", "亲近感", "默契度", "尊重感", "趣味性", "张力值", "依恋度")
        val DIMENSION_KEYS = listOf("familiarity", "trust", "closeness", "rapport", "respect", "fun", "tension", "attachment")
    }
}

// MARK: - 情绪历史条目（记录角色每次回复时的情绪状态）

@Serializable
data class MoodHistoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = 0L,        // epoch millis
    val emoji: String = "",          // 如 "😊"
    val colorName: String = "",      // "green" / "yellow" / "red"
    val text: String = "",           // 简短描述，如 "开心"
)

/**
 * 追加一条情绪历史并按上限截断（保留**最新** [maxCount] 条）。纯函数便于单测。
 *
 * append 在尾 → 列表「最旧在前、最新在后」（对齐 iOS `history.append` + `suffix(maxCount)`）。
 * 由 [com.situ.aichat.data.repository.CharacterRepository.appendMoodHistory] 在轻锁内调用；读「近 N 条」的消费者
 * （主动送礼 senseLowMood）须自行按时间倒序取最近 N（见 `ProactiveGiftScheduler.recentMoodColors`），不可直接取列表前 N。
 */
internal fun appendMoodEntry(
    existing: List<MoodHistoryEntry>,
    entry: MoodHistoryEntry,
    maxCount: Int,
): List<MoodHistoryEntry> {
    val cap = maxCount.coerceAtLeast(1)
    val appended = existing + entry
    return if (appended.size > cap) appended.takeLast(cap) else appended
}

// MARK: - 动态兴趣（热度随对话变化）

@Serializable
data class DynamicInterest(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val heat: Int = 50,                  // 热度 0~100（0=已冷却，100=非常热衷）
    val discoveredDate: Long = 0L,       // 首次发现时间，epoch millis
    val lastMentionedDate: Long = 0L,    // 最后一次提到的时间，epoch millis
    val isFromInitial: Boolean = false,  // 是否来自编辑页的 initialInterests
)

// MARK: - 成长日志条目

@Serializable
enum class GrowthEventType {
    @SerialName("personalityShift") PERSONALITY_SHIFT,     // 性格变化
    @SerialName("relationshipChange") RELATIONSHIP_CHANGE, // 关系变化
    @SerialName("interestDiscovered") INTEREST_DISCOVERED, // 发现新兴趣
    @SerialName("interestCooled") INTEREST_COOLED,         // 兴趣冷却
    @SerialName("majorEvent") MAJOR_EVENT,                 // 重大事件
    @SerialName("giftReceived") GIFT_RECEIVED,             // 收到重要礼物
    @SerialName("giftSent") GIFT_SENT,                     // 主动送出重要礼物
    ;

    companion object {
        /** 由 iOS rawValue 字符串映射枚举（LLM 输出的 type 字段），未知→[MAJOR_EVENT]（对齐 iOS `?? .majorEvent`）。 */
        fun fromRaw(raw: String): GrowthEventType = when (raw) {
            "personalityShift" -> PERSONALITY_SHIFT
            "relationshipChange" -> RELATIONSHIP_CHANGE
            "interestDiscovered" -> INTEREST_DISCOVERED
            "interestCooled" -> INTEREST_COOLED
            "majorEvent" -> MAJOR_EVENT
            "giftReceived" -> GIFT_RECEIVED
            "giftSent" -> GIFT_SENT
            else -> MAJOR_EVENT
        }
    }
}

@Serializable
data class GrowthLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = 0L,        // epoch millis
    val type: GrowthEventType = GrowthEventType.MAJOR_EVENT,
    val summary: String = "",        // 人类可读的变化描述
)

// MARK: - 成长分析元数据（追踪触发状态）

@Serializable
data class GrowthAnalysisMetadata(
    val lastAnalysisDate: Long? = null,           // epoch millis
    val roundsSinceLastAnalysis: Int = 0,
    val totalAnalysisCount: Int = 0,
    val lastDecayAppliedDate: Long? = null,       // 上次关系淡化执行日期（防同一天重复淡化）
    val currentPhase: String? = null,             // honeymoon/adjustment/stability/fatigue/breakthrough
    val phaseEnteredDate: Long? = null,           // 进入当前阶段的日期
)

// MARK: - JSON 编解码（对齐 iOS `GrowthJSON`，集中 Json 配置）

/**
 * 统一的成长状态 JSON 编解码工具。`coerceInputValues = true` 使「键缺失 / 值为 null」都回落到默认值，
 * 等价 iOS `decodeIfPresent ?? 默认`；`encodeDefaults = true` 使输出含全部键（等价 iOS 无条件 encode）。
 * 解码失败 → 返回默认（等价 iOS catch 后返回 `Type()`），永不抛出。
 */
object GrowthJson {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }

    fun decodePersonalitySpectrum(s: String): PersonalitySpectrum =
        if (s.isEmpty()) PersonalitySpectrum() else runCatching { json.decodeFromString<PersonalitySpectrum>(s) }.getOrDefault(PersonalitySpectrum())

    fun decodeRelationshipQuality(s: String): RelationshipQuality =
        if (s.isEmpty()) RelationshipQuality() else runCatching { json.decodeFromString<RelationshipQuality>(s) }.getOrDefault(RelationshipQuality())

    fun decodeMoodHistory(s: String): List<MoodHistoryEntry> =
        if (s.isEmpty()) emptyList() else runCatching { json.decodeFromString<List<MoodHistoryEntry>>(s) }.getOrDefault(emptyList())

    fun decodeDynamicInterests(s: String): List<DynamicInterest> =
        if (s.isEmpty()) emptyList() else runCatching { json.decodeFromString<List<DynamicInterest>>(s) }.getOrDefault(emptyList())

    fun decodeGrowthLog(s: String): List<GrowthLogEntry> =
        if (s.isEmpty()) emptyList() else runCatching { json.decodeFromString<List<GrowthLogEntry>>(s) }.getOrDefault(emptyList())

    fun decodeGrowthMetadata(s: String): GrowthAnalysisMetadata =
        if (s.isEmpty()) GrowthAnalysisMetadata() else runCatching { json.decodeFromString<GrowthAnalysisMetadata>(s) }.getOrDefault(GrowthAnalysisMetadata())

    // 写路径兜底 ""：编码一个良构 @Serializable 实际上永不抛（防御性不可达分支）。一旦真返回 "" 并被持久化，
    // 会静默清空对应成长/关系 JSON 列（下次解码回落默认值）——故保留 runCatching 仅为不崩，绝非常规路径。
    fun encode(value: PersonalitySpectrum): String = runCatching { json.encodeToString(value) }.getOrDefault("")
    fun encode(value: RelationshipQuality): String = runCatching { json.encodeToString(value) }.getOrDefault("")
    fun encode(value: GrowthAnalysisMetadata): String = runCatching { json.encodeToString(value) }.getOrDefault("")
    fun encodeMoodHistory(value: List<MoodHistoryEntry>): String = runCatching { json.encodeToString(value) }.getOrDefault("")
    fun encodeDynamicInterests(value: List<DynamicInterest>): String = runCatching { json.encodeToString(value) }.getOrDefault("")
    fun encodeGrowthLog(value: List<GrowthLogEntry>): String = runCatching { json.encodeToString(value) }.getOrDefault("")
}

// MARK: - CharacterEntity 解码访问器（iOS `AICharacter` 的 `@Transient` 计算属性等价）

/**
 * 解码后的成长状态视图。每次访问解码一次 JSON（4.1 内每轮构建仅访问数次，开销可忽略；
 * iOS 用热路径缓存避免每条消息重复解码 → 性能优化延后 P12.3）。
 */
val CharacterEntity.personalitySpectrum: PersonalitySpectrum
    get() = GrowthJson.decodePersonalitySpectrum(personalitySpectrumJSON)
val CharacterEntity.relationshipQuality: RelationshipQuality
    get() = GrowthJson.decodeRelationshipQuality(relationshipQualityJSON)
val CharacterEntity.dynamicInterests: List<DynamicInterest>
    get() = GrowthJson.decodeDynamicInterests(dynamicInterestsJSON)
val CharacterEntity.growthLog: List<GrowthLogEntry>
    get() = GrowthJson.decodeGrowthLog(growthLogJSON)
val CharacterEntity.growthMetadata: GrowthAnalysisMetadata
    get() = GrowthJson.decodeGrowthMetadata(growthMetadataJSON)
val CharacterEntity.moodHistory: List<MoodHistoryEntry>
    get() = GrowthJson.decodeMoodHistory(moodHistoryJSON)
