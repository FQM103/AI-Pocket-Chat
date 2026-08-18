package com.situ.aichat.gift

import com.situ.aichat.data.model.GiftCategory
import kotlin.math.roundToInt

/**
 * 心意值基线公式（1:1 iOS `GiftSendService.baselineAffinity` + `ProactiveGiftExecutor.calculateAffinityGain`）。
 *
 * 用 [roundToInt] 而非 toInt() 截断（避免 0.7→0 丢心意值），最后 clamp 到 [1,20] 保证最小情感反馈。正数下
 * Kotlin `roundToInt`（round half up）与 Swift `.rounded()`（round half away-from-zero）一致。
 */
object GiftAffinity {

    /**
     * 用户送角色的心意基线（兜底 + LLM 打分下限）：price×0.08，手作 ×1.5，奢侈 ×0.8，取整夹 [1,20]。
     */
    fun baseline(item: GiftItem): Int {
        var base = item.price * 0.08
        if (item.isHandmade) base *= 1.5
        if (item.category == GiftCategory.LUXURY) base *= 0.8
        return base.roundToInt().coerceIn(1, 20)
    }

    /**
     * 角色送用户的心意（1:1 iOS `calculateAffinityGain`）：**无 luxury 折扣**，只 ×0.08 + 手作 ×1.5 + clamp[1,20]。
     */
    fun affinityForCharacterGift(price: Int, isHandmade: Boolean): Int {
        var base = price * 0.08
        if (isHandmade) base *= 1.5
        return base.roundToInt().coerceIn(1, 20)
    }
}
