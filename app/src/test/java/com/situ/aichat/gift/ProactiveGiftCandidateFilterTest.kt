package com.situ.aichat.gift

import com.situ.aichat.data.model.EconomicStatusTier
import com.situ.aichat.data.model.GiftEmotionalTag
import com.situ.aichat.data.model.ProactiveGiftContext
import com.situ.aichat.data.model.ProactiveGiftTrigger
import com.situ.aichat.data.model.ProactiveGiftTriggerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主动送礼候选过滤纯函数单测（断言反推 iOS `ProactiveGiftCandidateFilterTests`）。覆盖预算比例表 5×3 + budgetRange round +
 * preferredTags + 预算/避重过滤 + 标签匹配排序 + 紧张档放宽（手作兜底）+ 上限 8。纯函数（GiftCatalog 静态），无需 Robolectric。
 */
class ProactiveGiftCandidateFilterTest {

    private fun trig(type: ProactiveGiftTriggerType) =
        ProactiveGiftTrigger(type, type.displayName, "test", 0L)

    private fun ctx(
        type: ProactiveGiftTriggerType,
        tier: EconomicStatusTier? = EconomicStatusTier.NORMAL,
        salary: Int = 10000,
        balance: Int = 5000,
    ) = ProactiveGiftContext(
        characterUUID = "test-char",
        characterName = "Test",
        occupation = "",
        candidateTriggers = listOf(trig(type)),
        daysSinceLastProactiveGift = null,
        economicTier = tier,
        monthlySalary = salary,
        coinBalance = balance,
        relationshipLabel = null,
        recentMoodSummary = "无记录",
    )

    // ── 预算比例 ──────────────────────────────────────────────────────

    @Test fun birthday_tight_ratio() {
        val r = budgetRatioRange(ProactiveGiftTriggerType.BIRTHDAY, EconomicStatusTier.TIGHT)
        assertEquals(0.03, r.first, 0.0)
        assertEquals(0.08, r.second, 0.0)
    }

    @Test fun birthday_comfortable_ratio() {
        val r = budgetRatioRange(ProactiveGiftTriggerType.BIRTHDAY, EconomicStatusTier.COMFORTABLE)
        assertEquals(0.15, r.first, 0.0)
        assertEquals(0.30, r.second, 0.0)
    }

    @Test fun missing_you_normal_ratio() {
        val r = budgetRatioRange(ProactiveGiftTriggerType.MISSING_YOU, EconomicStatusTier.NORMAL)
        assertEquals(0.01, r.first, 0.0)
        assertEquals(0.03, r.second, 0.0)
    }

    @Test fun null_tier_falls_back_to_normal() {
        assertEquals(
            budgetRatioRange(ProactiveGiftTriggerType.BIRTHDAY, EconomicStatusTier.NORMAL),
            budgetRatioRange(ProactiveGiftTriggerType.BIRTHDAY, null),
        )
    }

    // ── 预算金额 round ────────────────────────────────────────────────

    @Test fun salary_10000_birthday_normal_budget_1000_2000() {
        val r = budgetRange(trig(ProactiveGiftTriggerType.BIRTHDAY), EconomicStatusTier.NORMAL, 10000)
        assertEquals(1000, r.first)
        assertEquals(2000, r.second)
    }

    @Test fun salary_500_birthday_tight_budget_15_40() {
        // 0.03 × 500 = 15 / 0.08 × 500 = 40
        val r = budgetRange(trig(ProactiveGiftTriggerType.BIRTHDAY), EconomicStatusTier.TIGHT, 500)
        assertEquals(15, r.first)
        assertEquals(40, r.second)
    }

    @Test fun salary_0_budget_at_least_1() {
        val r = budgetRange(trig(ProactiveGiftTriggerType.MISSING_YOU), EconomicStatusTier.TIGHT, 0)
        assertTrue(r.first >= 1)
        assertTrue(r.second >= 1)
    }

    // ── 情感标签偏好 ──────────────────────────────────────────────────

    @Test fun birthday_prefers_romantic_refined_luxurious() {
        val tags = preferredTags(ProactiveGiftTriggerType.BIRTHDAY)
        assertTrue(tags.containsAll(listOf(GiftEmotionalTag.ROMANTIC, GiftEmotionalTag.REFINED, GiftEmotionalTag.LUXURIOUS)))
    }

    @Test fun sense_low_mood_prefers_warm_cute_thoughtful() {
        val tags = preferredTags(ProactiveGiftTriggerType.SENSE_LOW_MOOD)
        assertTrue(tags.containsAll(listOf(GiftEmotionalTag.WARM, GiftEmotionalTag.CUTE, GiftEmotionalTag.THOUGHTFUL)))
    }

    // ── filterCandidates 基础 ─────────────────────────────────────────

    @Test fun filter_hits_in_budget_range() {
        // festival normal 10000 → 0.03-0.08 → 300-800
        val candidates = filterCandidatesPure(ctx(ProactiveGiftTriggerType.FESTIVAL, salary = 10000), trig(ProactiveGiftTriggerType.FESTIVAL), emptySet())
        assertTrue(candidates.isNotEmpty())
        assertTrue(candidates.count { it.price in 300..800 } >= 1)
    }

    @Test fun at_most_8_candidates() {
        val candidates = filterCandidatesPure(
            ctx(ProactiveGiftTriggerType.FESTIVAL, EconomicStatusTier.COMFORTABLE, salary = 20000),
            trig(ProactiveGiftTriggerType.FESTIVAL),
            emptySet(),
        )
        assertTrue(candidates.size <= 8)
    }

    // ── 避重 ──────────────────────────────────────────────────────────

    @Test fun recently_sent_item_excluded() {
        // missingYou tight 500 → 0.003-0.03 → 2-15，关东煮(8)在区间内
        val withoutAvoid = filterCandidatesPure(
            ctx(ProactiveGiftTriggerType.MISSING_YOU, EconomicStatusTier.TIGHT, salary = 500),
            trig(ProactiveGiftTriggerType.MISSING_YOU),
            emptySet(),
        )
        assertTrue("关东煮应在预算内成为候选", withoutAvoid.any { it.id == "gift_oden" })

        val withAvoid = filterCandidatesPure(
            ctx(ProactiveGiftTriggerType.MISSING_YOU, EconomicStatusTier.TIGHT, salary = 500),
            trig(ProactiveGiftTriggerType.MISSING_YOU),
            setOf("gift_oden"),
        )
        assertFalse("送过的关东煮应被避重排除", withAvoid.any { it.id == "gift_oden" })
    }

    // ── 紧张档放宽 ────────────────────────────────────────────────────

    @Test fun tight_low_budget_includes_handmade_fallback() {
        // 月薪 100 紧张档 missingYou 预算 0.3%-3% ≈ 0-3，基础候选几乎为 0 → 放宽含手作
        val candidates = filterCandidatesPure(
            ctx(ProactiveGiftTriggerType.MISSING_YOU, EconomicStatusTier.TIGHT, salary = 100),
            trig(ProactiveGiftTriggerType.MISSING_YOU),
            emptySet(),
        )
        assertTrue(candidates.any { it.isHandmade })
    }

    @Test fun super_low_budget_still_returns_candidates() {
        val candidates = filterCandidatesPure(
            ctx(ProactiveGiftTriggerType.MISSING_YOU, EconomicStatusTier.TIGHT, salary = 50),
            trig(ProactiveGiftTriggerType.MISSING_YOU),
            emptySet(),
        )
        assertTrue(candidates.isNotEmpty())
    }

    @Test fun salary_0_still_returns_candidates() {
        // 月薪 0 → tier null → 预算算出 1 → 基础几乎空 → 放宽手作兜底
        val candidates = filterCandidatesPure(
            ctx(ProactiveGiftTriggerType.MISSING_YOU, tier = null, salary = 0),
            trig(ProactiveGiftTriggerType.MISSING_YOU),
            emptySet(),
        )
        assertTrue(candidates.isNotEmpty())
    }

    // ── 标签匹配度排序 ────────────────────────────────────────────────

    @Test fun preferred_tag_items_rank_first() {
        // 生日宽裕档 10000 × 15-30% = 1500-3000
        val candidates = filterCandidatesPure(
            ctx(ProactiveGiftTriggerType.BIRTHDAY, EconomicStatusTier.COMFORTABLE, salary = 10000),
            trig(ProactiveGiftTriggerType.BIRTHDAY),
            emptySet(),
        )
        assertTrue(candidates.isNotEmpty())
        val birthdayTags = preferredTags(ProactiveGiftTriggerType.BIRTHDAY)
        assertTrue(candidates[0].emotionalTags.any { it in birthdayTags })
    }
}
