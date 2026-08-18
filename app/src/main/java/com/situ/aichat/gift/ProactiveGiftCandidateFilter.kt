package com.situ.aichat.gift

import com.situ.aichat.data.local.dao.GiftDao
import com.situ.aichat.data.model.EconomicStatusTier
import com.situ.aichat.data.model.GiftEmotionalTag
import com.situ.aichat.data.model.ProactiveGiftContext
import com.situ.aichat.data.model.ProactiveGiftTrigger
import com.situ.aichat.data.model.ProactiveGiftTriggerType
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * 主动送礼候选筛选器（1:1 iOS `Services/ProactiveGiftCandidateFilter.swift`，4 层架构的「候选池」子层）。
 *
 * 从 [GiftCatalog] 46 件里按规则筛 5-8 件交给 LLM：按触发类型 × 经济档位算预算区间 → 按情感标签偏好排序 →
 * 排除最近 30 天送过的（避重）→ 紧张档位候选不足 3 件则放宽（价格 ×1.5 + 手作礼物兜底）。
 *
 * **为什么不直接让 LLM 从 46 件选**：稳定性（LLM 看全 46 件易选不贴档位的）+ 省 token + 规则可审计 + 避重必须 app 层做。
 *
 * iOS 是 `@MainActor enum`；安卓改 `@Singleton` class（需 [GiftDao] 查近 30 天避重）。预算/标签/排序/放宽是注入
 * `recentlySentIds` 的**纯函数**（[filterCandidatesPure] 等，internal 便于单测），class 方法只做 DAO 读取 + 委派。
 */
@Singleton
class ProactiveGiftCandidateFilter @Inject constructor(
    private val giftDao: GiftDao,
) {

    /**
     * 筛出 5-8 件候选礼物（1:1 iOS `filterCandidates`）。先查近 30 天该角色送出的 itemId（避重集合）→ 走纯函数筛选。
     */
    suspend fun filterCandidates(
        context: ProactiveGiftContext,
        trigger: ProactiveGiftTrigger,
        now: Long = System.currentTimeMillis(),
    ): List<GiftItem> {
        val cutoff = now - AVOID_REPEAT_DAYS * DAY_MILLIS
        val recentlySentIds = giftDao.recentCharacterSentGiftItemIds(context.characterUUID, cutoff).toSet()
        return filterCandidatesPure(context, trigger, recentlySentIds)
    }
}

// ── 纯函数（internal，确定性单测，断言反推 iOS 预算表/偏好/放宽） ──────────────

/** 候选数量上限（给 LLM 看的礼物数，太多让 LLM 犯错）。 */
internal const val MAX_CANDIDATES = 8

/** 候选数量不足时的下限触发点。 */
internal const val MIN_CANDIDATES_BEFORE_FALLBACK = 3

/** 避重窗口（天）。 */
internal const val AVOID_REPEAT_DAYS = 30L

/** 放宽预算的倍数。 */
internal const val FALLBACK_WIDEN_MULTIPLIER = 1.5

/**
 * 预算比例区间（触发类型 × 经济档位 → 月薪占比 min/max；1:1 iOS `budgetRatioRange`）。
 * nil 档位（月薪 0 或未参与经济）→ 回退 normal 比例（实际预算因月薪 0 算出 1）。紧张档压缩防穷角色破产，宽裕档适度提升。
 */
internal fun budgetRatioRange(
    triggerType: ProactiveGiftTriggerType,
    economicTier: EconomicStatusTier?,
): Pair<Double, Double> {
    val tier = economicTier ?: EconomicStatusTier.NORMAL
    return when (triggerType) {
        ProactiveGiftTriggerType.BIRTHDAY -> when (tier) {
            EconomicStatusTier.TIGHT -> 0.03 to 0.08
            EconomicStatusTier.NORMAL -> 0.10 to 0.20
            EconomicStatusTier.COMFORTABLE -> 0.15 to 0.30
        }
        ProactiveGiftTriggerType.ANNIVERSARY -> when (tier) {
            EconomicStatusTier.TIGHT -> 0.02 to 0.05
            EconomicStatusTier.NORMAL -> 0.05 to 0.15
            EconomicStatusTier.COMFORTABLE -> 0.10 to 0.25
        }
        ProactiveGiftTriggerType.FESTIVAL -> when (tier) {
            EconomicStatusTier.TIGHT -> 0.01 to 0.05
            EconomicStatusTier.NORMAL -> 0.03 to 0.08
            EconomicStatusTier.COMFORTABLE -> 0.05 to 0.15
        }
        ProactiveGiftTriggerType.SENSE_LOW_MOOD -> when (tier) {
            EconomicStatusTier.TIGHT -> 0.01 to 0.03
            EconomicStatusTier.NORMAL -> 0.02 to 0.05
            EconomicStatusTier.COMFORTABLE -> 0.03 to 0.08
        }
        ProactiveGiftTriggerType.MISSING_YOU -> when (tier) {
            EconomicStatusTier.TIGHT -> 0.003 to 0.03
            EconomicStatusTier.NORMAL -> 0.01 to 0.03
            EconomicStatusTier.COMFORTABLE -> 0.02 to 0.05
        }
    }
}

/** 预算金额区间（1:1 iOS `budgetRange`）：`max(1, round(月薪 × ratio))`，月薪负值钳 0。 */
internal fun budgetRange(
    trigger: ProactiveGiftTrigger,
    economicTier: EconomicStatusTier?,
    monthlySalary: Int,
): Pair<Int, Int> {
    val (ratioMin, ratioMax) = budgetRatioRange(trigger.type, economicTier)
    val salary = maxOf(0, monthlySalary).toDouble()
    val min = maxOf(1, (salary * ratioMin).roundToInt())
    val max = maxOf(1, (salary * ratioMax).roundToInt())
    return min to max
}

/** 情感标签偏好（1:1 iOS `preferredTags`）。 */
internal fun preferredTags(triggerType: ProactiveGiftTriggerType): List<GiftEmotionalTag> = when (triggerType) {
    ProactiveGiftTriggerType.BIRTHDAY -> listOf(GiftEmotionalTag.ROMANTIC, GiftEmotionalTag.REFINED, GiftEmotionalTag.LUXURIOUS)
    ProactiveGiftTriggerType.ANNIVERSARY -> listOf(GiftEmotionalTag.ROMANTIC, GiftEmotionalTag.NOSTALGIC)
    ProactiveGiftTriggerType.FESTIVAL -> listOf(GiftEmotionalTag.WARM, GiftEmotionalTag.ROMANTIC)
    ProactiveGiftTriggerType.SENSE_LOW_MOOD -> listOf(GiftEmotionalTag.WARM, GiftEmotionalTag.CUTE, GiftEmotionalTag.THOUGHTFUL)
    ProactiveGiftTriggerType.MISSING_YOU -> listOf(GiftEmotionalTag.THOUGHTFUL, GiftEmotionalTag.CUTE)
}

/**
 * 候选筛选纯逻辑（1:1 iOS `filterCandidates` 主体）：预算 + 避重过滤 → 标签匹配度排序（平价 tie-break）→
 * ≥3 件返回前 8；不足 3 件走放宽（A 价格 ×1.5 / B 手作兜底）→ 最多 8 件。
 */
internal fun filterCandidatesPure(
    context: ProactiveGiftContext,
    trigger: ProactiveGiftTrigger,
    recentlySentIds: Set<String>,
): List<GiftItem> {
    val (minBudget, maxBudget) = budgetRange(trigger, context.economicTier, context.monthlySalary)
    val preferred = preferredTags(trigger.type)

    val baseFiltered = GiftCatalog.allItems.filter { item ->
        item.id !in recentlySentIds && item.price >= minBudget && item.price <= maxBudget
    }
    val sortedBase = sortByPreference(baseFiltered, preferred)

    if (sortedBase.size >= MIN_CANDIDATES_BEFORE_FALLBACK) {
        return sortedBase.take(MAX_CANDIDATES)
    }

    val widened = fallbackWiden(sortedBase, maxBudget, recentlySentIds, preferred)
    return widened.take(MAX_CANDIDATES)
}

/** 按「情感标签匹配度」DESC + 「平价」tie-break 排序（1:1 iOS `sortByPreference`）。 */
private fun sortByPreference(items: List<GiftItem>, preferred: List<GiftEmotionalTag>): List<GiftItem> =
    items.sortedWith(compareByDescending<GiftItem> { tagMatchScore(it, preferred) }.thenBy { it.price })

private fun tagMatchScore(item: GiftItem, preferred: List<GiftEmotionalTag>): Int =
    item.emotionalTags.count { it in preferred }

/**
 * 放宽策略（1:1 iOS `fallbackWiden`）：A 价格上限 ×1.5 补；B 手作礼物兜底（不受预算限制，成本低情感高）。
 */
private fun fallbackWiden(
    baseCandidates: List<GiftItem>,
    maxBudget: Int,
    recentlySentIds: Set<String>,
    preferred: List<GiftEmotionalTag>,
): List<GiftItem> {
    val result = baseCandidates.toMutableList()
    val usedIds = baseCandidates.map { it.id }.toSet()

    // A：放宽价格上限
    val widenedMax = (maxBudget * FALLBACK_WIDEN_MULTIPLIER).toInt()
    val priceWidened = GiftCatalog.allItems.filter { item ->
        item.price > maxBudget && item.price <= widenedMax && item.id !in recentlySentIds && item.id !in usedIds
    }
    result.addAll(sortByPreference(priceWidened, preferred))

    // B：手作兜底（不受预算限制，穷角色靠手作传心意）
    val alreadyIn = result.map { it.id }.toSet()
    val handmadeBackup = GiftCatalog.allItems.filter { item ->
        item.isHandmade && item.id !in recentlySentIds && item.id !in alreadyIn
    }
    result.addAll(sortByPreference(handmadeBackup, preferred))

    return result
}
