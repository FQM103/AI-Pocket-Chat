package com.situ.aichat.data.model

/**
 * 角色经济状况档位（1:1 iOS `Models/EconomicStatusTier.swift`）。3 档「紧张/正常/宽裕」，比聊天侧
 * [ChatEconomicPressureLevel]（4 档）粗——给**日程生成**（9.1b-5 经济档位注入）和**主动送礼候选过滤**
 * （9.2c 预算比例表）用。
 *
 * **设计要点**（对齐 iOS）：给 LLM 的是**标签**不是数字；[promptGuidance] 是**软引导**不是硬规则；
 * 必要活动（工作/学习/睡眠）不受经济影响（产品底线，写进每档引导语）。`promptGuidance` 逐字对齐 iOS
 * （半角逗号/破折号），目前 9.1b-5 日程注入未接，先随主动送礼一并落地（filter 只用档位枚举，不读 guidance）。
 *
 * 阈值由 [com.situ.aichat.economy.CharacterEconomicStateService.tier] 判定：月薪 ≤ 0 → null；欠租一票否决 →
 * tight；否则 `max(0,余额)/月薪` 比例 `<0.5` tight / `≥1.5` comfortable / 其余 normal。
 */
enum class EconomicStatusTier(val raw: String, val promptLabel: String, val promptGuidance: String) {
    /** 经济紧张：余额 < 0.5 月薪 或 近期有欠租 */
    TIGHT(
        "tight",
        "紧张",
        "TA 经济偏紧。日程建议多安排日常和免费活动(上班/学习/在家/散步/公园),\n" +
            "减少高消费场所(高档餐厅/商场/KTV/娱乐城等)。\n" +
            "但必要活动(工作/学习/睡眠/就医)不受影响——即使紧张,\n" +
            "TA 仍要正常上班上课,可以在家做饭或公司食堂解决吃饭。",
    ),

    /** 经济正常：0.5 ≤ 余额 / 月薪 < 1.5 */
    NORMAL(
        "normal",
        "正常",
        "TA 经济正常。日程可以多样化,适度安排外出就餐、与朋友活动、兴趣爱好等,\n" +
            "不必过度铺张也不必刻意克制。",
    ),

    /** 经济宽裕：余额 ≥ 1.5 月薪 */
    COMFORTABLE(
        "comfortable",
        "宽裕",
        "TA 经济宽裕。日程可以安排符合 TA 月薪水平的消费活动\n" +
            "(较好的餐厅/咖啡店/娱乐场所/购物中心等),适度体现 TA 的生活质量。",
    );

    companion object {
        private val byRaw = entries.associateBy { it.raw }
        fun fromRaw(raw: String): EconomicStatusTier? = byRaw[raw]
    }
}
