package com.situ.aichat.gift

import com.situ.aichat.data.model.GiftEmotionalTag
import com.situ.aichat.data.model.GiftRelationshipImpact
import com.situ.aichat.data.model.RelationshipQuality
import kotlin.math.roundToInt

/**
 * 礼物 × 8 维关系映射（1:1 iOS `GiftRelationshipImpactService`）。把礼物的 emotionalTags 翻译为 8 维 delta，让关系成长
 * 立体（浪漫推亲近/依恋、实用建信任/默契、幽默拉趣味/熟悉）。
 *
 * **核心公式**：
 * ```
 * 每维 delta = Σ(该 item 所有 tag 的映射权重，按维度累加)
 * raw = affinityGain × 维度权重 × (手作 ? 1.2 : 1.0)
 * impact[i] = round(raw)
 * 珍贵(price > 200)：respect + 1，attachment + 1（固定，不走权重）
 * ```
 * 维度索引对齐 `RelationshipQuality.DIMENSION_KEYS`。
 */
object GiftRelationshipImpactService {

    // 维度索引常量（对齐 RelationshipQuality.DIMENSION_KEYS 顺序）
    private const val FAMILIARITY = 0
    private const val TRUST = 1
    private const val CLOSENESS = 2
    private const val RAPPORT = 3
    private const val RESPECT = 4
    private const val FUN = 5
    private const val TENSION = 6
    private const val ATTACHMENT = 7

    /** tag → [维度索引: 权重]（1:1 iOS `tagMappings`，权重 0.1-0.3）。 */
    val tagMappings: Map<GiftEmotionalTag, Map<Int, Double>> = mapOf(
        GiftEmotionalTag.ROMANTIC to mapOf(CLOSENESS to 0.3, ATTACHMENT to 0.3),
        GiftEmotionalTag.PRACTICAL to mapOf(TRUST to 0.3, RAPPORT to 0.2),
        GiftEmotionalTag.HUMOROUS to mapOf(FUN to 0.3, FAMILIARITY to 0.2),
        GiftEmotionalTag.NOSTALGIC to mapOf(FAMILIARITY to 0.2, ATTACHMENT to 0.2),
        GiftEmotionalTag.ADVENTUROUS to mapOf(TENSION to 0.2, FUN to 0.2),
        GiftEmotionalTag.CUTE to mapOf(FUN to 0.2, CLOSENESS to 0.1),
        GiftEmotionalTag.WARM to mapOf(CLOSENESS to 0.2, TRUST to 0.1),
        GiftEmotionalTag.THOUGHTFUL to mapOf(ATTACHMENT to 0.2, RESPECT to 0.2),
        GiftEmotionalTag.LUXURIOUS to mapOf(RESPECT to 0.2, TENSION to 0.1),
        GiftEmotionalTag.REFINED to mapOf(RESPECT to 0.2, FAMILIARITY to 0.1),
    )

    /**
     * 根据礼物和本次 affinityGain（已含 baseline×timing×decay×clamp）计算 8 维 impact。
     */
    fun compute(item: GiftItem, affinityGain: Int): GiftRelationshipImpact {
        // 1. 聚合所有 tag 对每个维度的总权重
        val weightPerDim = DoubleArray(8)
        for (tag in item.emotionalTags) {
            val mapping = tagMappings[tag] ?: continue
            for ((dimIdx, weight) in mapping) {
                if (dimIdx in 0 until 8) weightPerDim[dimIdx] += weight
            }
        }

        // 2. 每维 delta：affinityGain × 权重，手作 ×1.2，round
        val handmadeMultiplier = if (item.isHandmade) 1.2 else 1.0
        val v = IntArray(8) { i ->
            (affinityGain * weightPerDim[i] * handmadeMultiplier).roundToInt()
        }

        // 3. 珍贵礼物固定加成：尊重 +1 + 依恋 +1（不按权重，是"贵"本身的情感信号）
        if (item.price > 200) {
            v[RESPECT] += 1
            v[ATTACHMENT] += 1
        }

        return GiftRelationshipImpact(
            familiarity = v[FAMILIARITY], trust = v[TRUST], closeness = v[CLOSENESS],
            rapport = v[RAPPORT], respect = v[RESPECT], funValue = v[FUN],
            tension = v[TENSION], attachment = v[ATTACHMENT],
        )
    }

    /**
     * 把 impact 累加到 [quality]（每维 clamp[0,100]），返回新副本（1:1 iOS `apply`，安卓不可变 → 返回新对象）。
     * 空 impact 直接返回原值，不产生无效写。
     */
    fun apply(impact: GiftRelationshipImpact, quality: RelationshipQuality): RelationshipQuality {
        if (impact.isEmpty) return quality
        var q = quality
        val current = quality.values
        for (i in 0 until 8) {
            // RelationshipQuality.setValue 内部 clamp[0,100]
            q = q.setValue(i, current[i] + impact.value(i))
        }
        return q
    }
}
