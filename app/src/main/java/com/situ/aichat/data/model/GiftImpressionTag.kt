package com.situ.aichat.data.model

/**
 * 送礼印象标签（1:1 iOS `GiftImpressionTag`，16 个）。从用户送礼**行为模式**（不是总量）提取的"TA 眼里的你"标签。
 *
 * **绝不显示 heart/affinity 数字**（反刷分）。同组互斥（[group] 非空的四组各保留最高 [priority]），独立标签（group=""）
 * 全留；UI 最多显示 3 个（按 priority DESC）。label 中 "宠 TA"/"懂 TA"/"记得 TA" 的空格 1:1 iOS。
 */
enum class GiftImpressionTag(val label: String, val priority: Int, val group: String) {
    // 频次（互斥组 frequency）
    CONSTANT_PRESENCE("常来常往", 50, "frequency"),       // 近 7 天 ≥2 次
    THOUGHTFUL_FREQUENCY("体贴", 55, "frequency"),         // 近 30 天 ≥3 次
    PERSISTENT("坚持", 60, "frequency"),                   // 首末送礼跨度 ≥30 天
    INDULGENT("宠 TA", 80, "frequency"),                   // 近 7 天 ≥3 次

    // 品类偏好（互斥组 category，近 30 天）
    ROMANTIC("浪漫", 65, "category"),                      // flower 占比 ≥40%
    PRACTICAL("实在", 65, "category"),                     // food 占比 ≥40%
    REFINED("讲究", 65, "category"),                       // accessory 占比 ≥40%
    VERSATILE("百搭", 70, "category"),                     // ≥5 种不同 category

    // 价值高（互斥组 valueHigh）
    GENEROUS("豪气", 70, "valueHigh"),                     // 送过 ≥1 件 >500
    DEVOTED("情深义重", 95, "valueHigh"),                  // 送过 ≥1 件 >1000

    // 工艺（互斥组 craft）
    ARTFUL("亲手", 55, "craft"),                           // ≥1 件手作/DIY
    METICULOUS("细致", 75, "craft"),                       // 手作/DIY 占比 ≥30%

    // 独立（无互斥）
    LITTLE_BUT_OFTEN("小心意达人", 60, ""),                // 近 30 天 ≥5 件 ≤50
    ATTUNED("懂 TA", 85, ""),                              // 情绪低落期送过礼
    REMEMBERING("记得 TA", 90, ""),                        // 生日送过礼
    OBSESSED("痴情", 100, ""),                             // 单品类占比 ≥60% 且 ≥5 件
}
