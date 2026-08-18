package com.situ.aichat.data.model

/**
 * 聊天经济压力档位（1:1 iOS `ChatEconomicPressureLevel`）。比日程版 `EconomicStatusTier`（3 档，随 9.1b 接）细，
 * 专给聊天 LLM 语言风格引导——让角色自然流露「肉痛感」，**不暴露数字**。阈值 余额/月薪：`<0.30` 捉襟见肘 /
 * `<0.60` 精打细算 / `<1.00` 收支平衡 / `≥1.00` 宽裕从容。`promptGuidance` 逐字对齐 iOS（半角逗号/全角单引号）。
 */
enum class ChatEconomicPressureLevel(val raw: String, val promptLabel: String, val promptGuidance: String) {
    STRUGGLING(
        "struggling",
        "捉襟见肘",
        "【语言风格】经济压力明显。聊到消费话题时自然流露节俭或苦笑,可能在合适时机说'这个月要吃土了''最近挺紧''省着点花'这类话,但不刻意卖惨也不向用户要钱。对方送贵重礼物时会本能反应'太破费了''哪舍得',而不是冷静道谢。工作、学习、就医等必要活动不受影响。不主动报具体余额/工资数字。",
    ),
    FRUGAL(
        "frugal",
        "精打细算",
        "【语言风格】精打细算的生活节奏,会在消费话题中不经意提到性价比、省钱思路,但语气轻松,不卖惨。不主动报具体余额/工资数字。",
    ),
    COMFORTABLE(
        "comfortable",
        "收支平衡",
        "【语言风格】经济状态正常。不主动谈论钱或余额,消费话题轻描淡写。收到贵重礼物时表达自然的感谢和心动。",
    ),
    ABUNDANT(
        "abundant",
        "宽裕从容",
        "【语言风格】经济宽裕但不炫耀。面对消费话题态度随意,不主动提自己的富裕;收到贵重礼物时表达感动和珍视,不会说'太破费了'这类穷人式反应。",
    );

    companion object {
        private val byRaw = entries.associateBy { it.raw }
        fun fromRaw(raw: String): ChatEconomicPressureLevel? = byRaw[raw]
    }
}

/**
 * 聊天经济状态快照（压力档位 + 近 7 天大额扣款摘要），由 [com.situ.aichat.economy.CharacterEconomicStateService]
 * 预计算后装进 `PromptBuilder.BuildContext`，CHARACTER_ECONOMIC_STATE 模块据此渲染 `<economic_state>` 块。
 * 放 data/model 中立层，避免 prompt ↔ economy 双向引用（同 MomentChatContext）。
 */
data class CharacterEconomicChatState(
    val level: ChatEconomicPressureLevel,
    val recentEventSummaries: List<String> = emptyList(),
)
