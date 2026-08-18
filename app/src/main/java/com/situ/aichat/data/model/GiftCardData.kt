package com.situ.aichat.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * 送礼方向（1:1 iOS `GiftSender`，2026-04-22 新增以修复 llmRepresentation 方向反转 bug）。
 * - [USER]：用户送角色（GiftSendService）
 * - [CHARACTER]：角色主动送用户（ProactiveGiftExecutor）
 */
@Serializable
enum class GiftSender {
    @SerialName("user") USER,
    @SerialName("character") CHARACTER,
}

/**
 * 聊天内送礼时插入的消息卡片结构化数据（1:1 iOS `GiftCardData`，`MessageKind.GIFT_CARD` 的关联数据）。
 *
 * 送出瞬间以 GIFT_CARD 消息出现在聊天流，content 字段存本结构的 JSON。`llmRepresentation`（9.2b）会把 JSON 翻译成
 * `[系统记录：用户送出礼物 | 名称=X | 分量=Y]` 给 LLM，永不暴露原始 JSON。
 *
 * 字段为**防御性快照**（名称/价格在送礼那刻锁定）：GiftItem 目录未来可能调价/改名/下架，历史消息须能独立解释自己。
 *
 * **序列化**（[GiftCardJson]，encodeDefaults=false）：[type]/[giftItemId]/[giftRecordId]/[cost]/[giftName]/[isHandmade]
 * 无默认值必写；[diyTitle]/[diyContent]/[senderType] 默认 null 时省略 = iOS Swift Codable 对 nil Optional 跳过 encode，
 * 老消息 round-trip 字节不变、decode 兼容（缺字段 → null）。
 */
@Serializable
data class GiftCardData(
    /** 类型判别（固定 "gift_card"，解析时校验） */
    val type: String,
    /** 对应 `GiftItem.id` */
    val giftItemId: String,
    /** 对应 `GiftRecord.uuid`，关联活跃送礼记录与反馈 */
    val giftRecordId: String,
    /** 送礼时实际扣款的金币数（快照，防调价影响历史消息） */
    val cost: Int,
    /** 送礼时的礼物名称（快照，防改名/下架） */
    val giftName: String,
    /** 是否手作礼物（情感权重最高，UI 和 llmRepresentation 加手作标签） */
    val isHandmade: Boolean,
    /** 用户 DIY 卡片标题（仅 DIY 创建时非空） */
    val diyTitle: String? = null,
    /** 用户 DIY 卡片内容摘要（约 80 字，llmRepresentation 作"附言"追加） */
    val diyContent: String? = null,
    /** 送礼方向（null=老消息，按"用户送出"兜底） */
    val senderType: GiftSender? = null,
) {
    /** 当前卡片的心意分档文案 */
    val tierText: String get() = tier(cost)

    /**
     * 礼物卡 → LLM 系统记录（1:1 iOS `MessageContent.llmRepresentation` 的 giftCard 分支，**永不暴露原始 JSON/金币数字**）。
     * 格式：`[系统记录：<方向> | 名称=X（手作）| 分量=Y]`，DIY 追加 ` | 标题=Z | 附言=「W」`（content 防御性截 80 字）。
     * - [senderType]=USER/null → "<[userName]>送出礼物"（[userName] 是用户名·默认「用户」）；CHARACTER → "<[characterName]>送出礼物"（角色名·空兜底「角色」）。
     * 人称=第三人称双名字（图纸一 R1 承接·同红包/礼物历史口径）。图片永不进 LLM。
     */
    fun llmRepresentation(characterName: String, userName: String = "用户"): String {
        val handmadeBadge = if (isHandmade) "（手作）" else ""
        // 空名兜底「角色」（1:1 iOS MessageContent.swift:231 `characterName?.isEmpty == false ? ... : "角色"`）——
        // 9.2b-4 复核 L2：CHARACTER 分支接主动送礼（senderType=CHARACTER）时才用到 characterName，空名守卫避免「送出礼物」缺主语。
        val displayName = characterName.ifEmpty { "角色" }
        val senderLabel = when (senderType) {
            GiftSender.CHARACTER -> "${displayName}送出礼物"
            GiftSender.USER, null -> "${userName}送出礼物"
        }
        var representation = "[系统记录：$senderLabel | 名称=$giftName$handmadeBadge | 分量=$tierText]"
        diyTitle?.trim()?.takeIf { it.isNotEmpty() }?.let { representation += " | 标题=$it" }
        diyContent?.trim()?.takeIf { it.isNotEmpty() }?.let {
            val capped = if (it.length > 80) it.take(80) + "…" else it
            representation += " | 附言=「$capped」"
        }
        return representation
    }

    companion object {
        /**
         * 金币数 → "心意分档"文案（1:1 iOS `tier(for:)`）。把原始金币换成分档文案，避免 LLM 字面复述数字。
         * - `cost ≤ 50` → "小心意"
         * - `51 ≤ cost ≤ 200` → "用心的选择"
         * - `cost > 200` → "珍贵的心意"
         */
        fun tier(cost: Int): String = when {
            cost < 51 -> "小心意"
            cost <= 200 -> "用心的选择"
            else -> "珍贵的心意"
        }
    }
}

/** 礼物卡 JSON 编解码（encodeDefaults=false 以省略 null 可选字段，对齐 iOS Codable）。 */
object GiftCardJson {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun encode(card: GiftCardData): String = json.encodeToString(GiftCardData.serializer(), card)

    /**
     * 尝试把消息内容解析为礼物卡片（1:1 iOS `parseGiftCard`，不依赖字段顺序）。
     * - 校验 content 以 "{" 开头（排除 plainText）
     * - 校验 type == "gift_card"（防其他 JSON 卡片误入）
     * - 解析失败返回 null（调用方回退 plainText）
     */
    fun parse(content: String): GiftCardData? {
        if (!content.startsWith("{")) return null
        val card = runCatching { json.decodeFromString<GiftCardData>(content) }.getOrNull() ?: return null
        return if (card.type == "gift_card") card else null
    }
}
