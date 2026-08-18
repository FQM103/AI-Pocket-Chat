package com.situ.aichat.economy

/**
 * 日程派生经济影响（1:1 iOS `Models/ScheduleEconomicImpact.swift`）。`ScheduleEconomicCategory` 表达「钱花在什么场景」
 * （吃饭/饮品/娱乐…），用于 note 描述 + UI；最终写 CurrencyTransaction 时所有支出落 `unexpectedExpense`。
 * 比例区间 = 占月薪百分比（0.005=0.5%）；minAmount 兜底避免低月薪算出 0。
 */
enum class ScheduleEconomicCategory(
    val raw: String,
    val baseRateMin: Double,
    val baseRateMax: Double,
    val minAmount: Int,
    val emoji: String,
    val shortName: String,
) {
    DINING("dining", 0.003, 0.03, 5, "🍲", "餐饮"),
    DRINKS("drinks", 0.001, 0.005, 3, "☕", "饮品"),
    ENTERTAINMENT("entertainment", 0.003, 0.02, 10, "🎬", "娱乐"),
    MEDICAL("medical", 0.05, 0.2, 30, "🏥", "医疗"),
    SHOPPING("shopping", 0.005, 0.02, 10, "🛒", "购物"),
    TRANSPORT("transport", 0.02, 0.1, 20, "✈️", "出行"),
    FITNESS("fitness", 0.005, 0.01, 5, "💪", "健身"),
}

/** 从一个日程事件识别出的经济影响（amount 正数；note 进账本；sourceEventId 做事件级幂等 key）。 */
data class ScheduleEconomicImpact(
    val category: ScheduleEconomicCategory,
    val amount: Int,
    val note: String,
    val sourceEventId: String,
)
