package com.situ.aichat.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 聊天流里红包卡片消息的结构化数据（1:1 iOS `Models/RedPacketData`，[MessageKind.RED_PACKET] 的关联数据）。
 *
 * 发红包瞬间以 RED_PACKET 消息形式插入聊天流，content 存本结构 JSON，通过 [recordUUID] 关联
 * [com.situ.aichat.data.local.entity.RedPacketRecordEntity] 读真实状态/金额。
 *
 * ## 快照 vs 真相源
 * 本结构是**发送瞬间快照**（消息自包含，万一 Record 丢失仍可读）。运行态（状态机/金额变化）以 Record 为准。
 * - [amount] 放快照（UI 拆开后显示用），但 [llmRepresentation] **永远不露给 LLM**。
 * - status/isOpened **不放快照**（会变，双写脆弱），统一以 Record 为准，UI 实时查。
 *
 * **序列化**（[RedPacketJson]，encodeDefaults=false）：[type]/[recordUUID]/[amount]/[blessingText] 无默认值必写；
 * [festivalId] 默认 null 时省略 = iOS Swift Codable 对 nil Optional 跳过 encode（日常红包不写该字段，老消息 decode 兼容）。
 */
@Serializable
data class RedPacketData(
    /** 类型判别（固定 "red_packet"，解析时校验）。 */
    val type: String,
    /** 对应 `RedPacketRecord.uuid`，用于查询状态和真实金额。 */
    val recordUUID: String,
    /** 红包金额（快照）。UI 拆开后显示用，**llmRepresentation 永不露此字段**。 */
    val amount: Int,
    /** 祝福文字（快照，空串=无祝福）。 */
    val blessingText: String,
    /** 节日 id（[com.situ.aichat.gift.FestivalCalendar] id）；null = 日常红包（用户非节日日发的）。 */
    val festivalId: String? = null,
) {
    /**
     * 红包卡 → LLM 系统记录（1:1 iOS `MessageContent.llmRepresentation` 的 redPacket 分支）。
     *
     * **永远不露 amount 数字**给 LLM：红包魅力在「拆开前不知道金额」的神秘感，拆开后的感知走 PromptBuilder 独立段
     * （红包系统事件 [com.situ.aichat.data.model.buildRedPacketLLMRepresentation]），不通过消息历史原文。
     *
     * 格式：`[系统记录：发出红包 | 节日=X | 祝福=「Y」]`
     * - 节日段：命中时显示节日名（[festivalName] 由调用方经 FestivalCalendar 解析，避免 data/model → gift 反向依赖），日常红包省略。
     * - 祝福段：非空才加，防御性 80 字截断（与 giftCard diyContent 对齐）。
     */
    fun llmRepresentation(festivalName: String?): String {
        val parts = mutableListOf("系统记录：发出红包")
        festivalName?.takeIf { it.isNotEmpty() }?.let { parts.add("节日=$it") }
        val trimmed = blessingText.trim()
        if (trimmed.isNotEmpty()) {
            val capped = if (trimmed.length > 80) trimmed.take(80) + "…" else trimmed
            parts.add("祝福=「$capped」")
        }
        return "[" + parts.joinToString(" | ") + "]"
    }
}

/** 红包卡 JSON 编解码（encodeDefaults=false 以省略 null 的 [RedPacketData.festivalId]，对齐 iOS Codable）。 */
object RedPacketJson {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun encode(data: RedPacketData): String = json.encodeToString(RedPacketData.serializer(), data)

    /**
     * 尝试把消息内容解析为红包卡片（1:1 iOS `parseRedPacket`，不依赖字段顺序）。
     * - 校验 content 以 "{" 开头（排除 plainText）
     * - 校验 type == "red_packet"（防其他 JSON 卡片误入）
     * - 解析失败返回 null（调用方回退 plainText）
     */
    fun parse(content: String): RedPacketData? {
        if (!content.startsWith("{")) return null
        val data = runCatching { json.decodeFromString<RedPacketData>(content) }.getOrNull() ?: return null
        return if (data.type == "red_packet") data else null
    }
}
