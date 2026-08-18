package com.situ.aichat.gift

import com.situ.aichat.data.model.AffinitySenseTier
import com.situ.aichat.data.model.GiftCardData
import com.situ.aichat.data.model.GiftCategory
import com.situ.aichat.data.model.GiftEmotionalTag
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 礼物核心公式单测（断言**反推 iOS 真实数值/公式**：baseline price×0.08/手作×1.5/奢侈×0.8、applyMultiplier round/clamp、
 * 边际衰减档位、tier 文案边界、AffinitySenseTier）。覆盖移植易错点：round（非截断）、clamp[1,20]、严格边界、
 * 奢侈折扣只在 baseline 不在角色送礼。
 */
class GiftFormulaTest {

    // 合成礼物（避免目录所有奢侈品都 ≥350 必 clamp 看不出 ×0.8）
    private fun synthetic(price: Int, category: GiftCategory, handmade: Boolean = false) = GiftItem(
        id = "test", name = "t", subtitle = "s", price = price, category = category,
        emotionalTags = listOf(GiftEmotionalTag.WARM), fallbackSymbol = "x",
        isSignature = false, isHandmade = handmade,
    )

    // MARK: - GiftAffinity.baseline（用户送角色：price×0.08，手作×1.5，奢侈×0.8）

    @Test fun baseline_catalog_values() {
        // 8×0.08=0.64→round 1→clamp 1
        assertEquals(1, GiftAffinity.baseline(GiftCatalog.find("gift_oden")!!))
        // 20×0.08=1.6→round 2
        assertEquals(2, GiftAffinity.baseline(GiftCatalog.find("gift_coffee")!!))
        // 65×0.08=5.2→round 5
        assertEquals(5, GiftAffinity.baseline(GiftCatalog.find("gift_macaron")!!))
        // 180×0.08=14.4→round 14
        assertEquals(14, GiftAffinity.baseline(GiftCatalog.find("gift_hotpot")!!))
        // 150×0.08=12→12
        assertEquals(12, GiftAffinity.baseline(GiftCatalog.find("gift_rose_bouquet")!!))
        // 380×0.08=30.4→round 30→clamp 20
        assertEquals(20, GiftAffinity.baseline(GiftCatalog.find("gift_steak")!!))
    }

    @Test fun baseline_handmade_x15() {
        // gift_love_letter 30 手作：30×0.08=2.4 ×1.5=3.6→round 4（非手作会是 2.4→2）
        assertEquals(4, GiftAffinity.baseline(GiftCatalog.find("gift_love_letter")!!))
        // gift_origami 15 手作：1.2×1.5=1.8→round 2
        assertEquals(2, GiftAffinity.baseline(GiftCatalog.find("gift_origami")!!))
        // gift_note 5 手作：0.4×1.5=0.6→round 1→clamp 1
        assertEquals(1, GiftAffinity.baseline(GiftCatalog.find("gift_note")!!))
    }

    @Test fun baseline_luxury_x08() {
        // 合成奢侈 100：100×0.08=8 ×0.8=6.4→round 6（同价非奢侈是 8）
        assertEquals(6, GiftAffinity.baseline(synthetic(100, GiftCategory.LUXURY)))
        assertEquals(8, GiftAffinity.baseline(synthetic(100, GiftCategory.FOOD)))
        // 真实奢侈品全 ≥350 必 clamp 20
        assertEquals(20, GiftAffinity.baseline(GiftCatalog.find("gift_lipstick")!!))
    }

    @Test fun baseline_handmade_and_luxury_combined() {
        // 合成手作+奢侈 100：8 ×1.5=12 ×0.8=9.6→round 10
        assertEquals(10, GiftAffinity.baseline(synthetic(100, GiftCategory.LUXURY, handmade = true)))
    }

    // MARK: - GiftAffinity.affinityForCharacterGift（角色送用户：无奢侈折扣）

    @Test fun characterGift_no_luxury_discount() {
        // 100×0.08=8（无折扣）
        assertEquals(8, GiftAffinity.affinityForCharacterGift(price = 100, isHandmade = false))
        // 手作 20：1.6×1.5=2.4→round 2
        assertEquals(2, GiftAffinity.affinityForCharacterGift(price = 20, isHandmade = true))
        // 手作 5：0.4×1.5=0.6→round 1→clamp 1
        assertEquals(1, GiftAffinity.affinityForCharacterGift(price = 5, isHandmade = true))
        // 380→30.4→clamp 20
        assertEquals(20, GiftAffinity.affinityForCharacterGift(price = 380, isHandmade = false))
    }

    // MARK: - 边际衰减 multiplierForCount

    @Test fun decay_multiplierForCount_all_tiers() {
        assertEquals(1.00, GiftMarginalDecayService.multiplierForCount(0), 0.0)
        assertEquals(0.80, GiftMarginalDecayService.multiplierForCount(1), 0.0)
        assertEquals(0.65, GiftMarginalDecayService.multiplierForCount(2), 0.0)
        assertEquals(0.50, GiftMarginalDecayService.multiplierForCount(3), 0.0)
        assertEquals(0.30, GiftMarginalDecayService.multiplierForCount(4), 0.0)
        assertEquals(0.30, GiftMarginalDecayService.multiplierForCount(5), 0.0)
        assertEquals(0.30, GiftMarginalDecayService.multiplierForCount(100), 0.0)
        // 防御性：负数（理论不会出现）→ 满乘子
        assertEquals(1.00, GiftMarginalDecayService.multiplierForCount(-1), 0.0)
    }

    // MARK: - applyMultiplier（round 非截断 + clamp[1,20]）

    @Test fun applyMultiplier_basic_and_rounding() {
        assertEquals(8, GiftMarginalDecayService.applyMultiplier(10, 0.80))
        assertEquals(5, GiftMarginalDecayService.applyMultiplier(10, 0.50))
        assertEquals(20, GiftMarginalDecayService.applyMultiplier(20, 1.00))
        // 10×0.65=6.5（IEEE-754 精确落在 6.5）→ round half-up → 7
        assertEquals(7, GiftMarginalDecayService.applyMultiplier(10, 0.65))
        assertEquals(3, GiftMarginalDecayService.applyMultiplier(10, 0.30))
    }

    @Test fun applyMultiplier_clamp_low_and_high() {
        // 1×0.30=0.3→round 0→clamp 1（最低情感反馈）
        assertEquals(1, GiftMarginalDecayService.applyMultiplier(1, 0.30))
        // 2×0.30=0.6→round 1
        assertEquals(1, GiftMarginalDecayService.applyMultiplier(2, 0.30))
        // 25×1.00=25→clamp 20
        assertEquals(20, GiftMarginalDecayService.applyMultiplier(25, 1.00))
    }

    // MARK: - GiftCardData.tier 文案分档（<51 / 51..200 / >200）

    @Test fun tier_text_boundaries() {
        assertEquals("小心意", GiftCardData.tier(5))
        assertEquals("小心意", GiftCardData.tier(50))
        assertEquals("用心的选择", GiftCardData.tier(51))
        assertEquals("用心的选择", GiftCardData.tier(200))
        assertEquals("珍贵的心意", GiftCardData.tier(201))
        assertEquals("珍贵的心意", GiftCardData.tier(888))
    }

    // MARK: - AffinitySenseTier（≤5 low / 6..12 mid / ≥13 high）

    @Test fun affinity_sense_tier_boundaries() {
        assertEquals(AffinitySenseTier.LOW, AffinitySenseTier.tier(0))
        assertEquals(AffinitySenseTier.LOW, AffinitySenseTier.tier(5))
        assertEquals(AffinitySenseTier.MID, AffinitySenseTier.tier(6))
        assertEquals(AffinitySenseTier.MID, AffinitySenseTier.tier(12))
        assertEquals(AffinitySenseTier.HIGH, AffinitySenseTier.tier(13))
        assertEquals(AffinitySenseTier.HIGH, AffinitySenseTier.tier(20))
    }
}
