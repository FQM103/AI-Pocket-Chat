package com.situ.aichat.gift

import com.situ.aichat.data.model.GiftCategory
import com.situ.aichat.data.model.GiftEmotionalTag
import com.situ.aichat.data.model.GiftRelationshipImpact
import com.situ.aichat.data.model.RelationshipQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 8 维关系影响单测（断言**反推 iOS `GiftRelationshipImpactServiceTests`** 的精确预期值）。覆盖：单 tag 映射、多 tag
 * 同维叠加、手作 ×1.2 round、珍贵 price>200 固定 +1（严格边界 200 不触发/201 触发）、apply 累加+clamp[0,100]、JSON。
 */
class GiftRelationshipImpactTest {

    private fun synthetic(
        price: Int,
        tags: List<GiftEmotionalTag>,
        handmade: Boolean = false,
    ) = GiftItem(
        id = "fake", name = "", subtitle = "", price = price, category = GiftCategory.FOOD,
        emotionalTags = tags, fallbackSymbol = "", isSignature = false, isHandmade = handmade,
    )

    // MARK: - 单 tag 映射

    @Test fun romantic_pushes_closeness_attachment() {
        // gift_rose_single [romantic, nostalgic], gain 10
        // romantic→closeness .3,attachment .3；nostalgic→familiarity .2,attachment .2
        val impact = GiftRelationshipImpactService.compute(GiftCatalog.find("gift_rose_single")!!, 10)
        assertEquals(3, impact.closeness)       // 10×0.3
        assertEquals(5, impact.attachment)      // 10×(0.3+0.2)
        assertEquals(2, impact.familiarity)     // 10×0.2
        assertEquals(0, impact.trust)
        assertEquals(0, impact.rapport)
    }

    @Test fun practical_pushes_trust_rapport() {
        // gift_thermos [practical], gain 10 → trust .3, rapport .2
        val impact = GiftRelationshipImpactService.compute(GiftCatalog.find("gift_thermos")!!, 10)
        assertEquals(3, impact.trust)
        assertEquals(2, impact.rapport)
    }

    @Test fun humorous_pushes_fun_familiarity() {
        // gift_oden [humorous, warm], gain 10
        // humorous→fun .3,familiarity .2；warm→closeness .2,trust .1
        val impact = GiftRelationshipImpactService.compute(GiftCatalog.find("gift_oden")!!, 10)
        assertEquals(3, impact.funValue)
        assertEquals(2, impact.familiarity)
        assertEquals(2, impact.closeness)
        assertEquals(1, impact.trust)
    }

    // MARK: - 多 tag 叠加

    @Test fun multi_tag_same_dim_weights_add() {
        // [romantic, warm] 都推 closeness：0.3+0.2=0.5 → 10×0.5=5
        val impact = GiftRelationshipImpactService.compute(
            synthetic(50, listOf(GiftEmotionalTag.ROMANTIC, GiftEmotionalTag.WARM)), 10,
        )
        assertEquals(5, impact.closeness)
        assertEquals(3, impact.attachment)      // romantic 0.3 → 3
        assertEquals(1, impact.trust)           // warm 0.1 → 1
    }

    // MARK: - 手作 ×1.2

    @Test fun handmade_weights_x12() {
        // gift_love_letter [romantic, nostalgic] 手作 price30 gain10
        val impact = GiftRelationshipImpactService.compute(GiftCatalog.find("gift_love_letter")!!, 10)
        assertEquals(4, impact.closeness)       // 10×0.3×1.2=3.6→4
        assertEquals(6, impact.attachment)      // 10×0.5×1.2=6
        assertEquals(2, impact.familiarity)     // 10×0.2×1.2=2.4→2
    }

    // MARK: - 珍贵 bonus（固定 +1，严格 >200）

    @Test fun precious_respect_attachment_plus1() {
        // gift_perfume price680 [romantic, refined] gain10
        val impact = GiftRelationshipImpactService.compute(GiftCatalog.find("gift_perfume")!!, 10)
        assertEquals(3, impact.respect)         // refined 10×0.2=2 +1
        assertEquals(4, impact.attachment)      // romantic 10×0.3=3 +1
    }

    @Test fun non_precious_no_bonus() {
        // gift_thermos price80
        val impact = GiftRelationshipImpactService.compute(GiftCatalog.find("gift_thermos")!!, 10)
        assertEquals(0, impact.respect)
        assertEquals(0, impact.attachment)
    }

    @Test fun price_200_boundary_no_bonus() {
        val impact = GiftRelationshipImpactService.compute(synthetic(200, emptyList()), 10)
        assertEquals(0, impact.respect)
        assertEquals(0, impact.attachment)
    }

    @Test fun price_201_triggers_bonus() {
        val impact = GiftRelationshipImpactService.compute(synthetic(201, emptyList()), 10)
        assertEquals(1, impact.respect)
        assertEquals(1, impact.attachment)
    }

    // MARK: - 综合（99 朵玫瑰）

    @Test fun nine_nine_roses_full() {
        // gift_99_roses price888 [romantic, luxurious] gain20
        val impact = GiftRelationshipImpactService.compute(GiftCatalog.find("gift_99_roses")!!, 20)
        assertEquals(6, impact.closeness)       // 20×0.3
        assertEquals(7, impact.attachment)      // 20×0.3=6 +1珍贵
        assertEquals(5, impact.respect)         // luxurious 20×0.2=4 +1珍贵
        assertEquals(2, impact.tension)         // luxurious 20×0.1
    }

    @Test fun no_tags_zero_impact() {
        assertTrue(GiftRelationshipImpactService.compute(synthetic(50, emptyList()), 10).isEmpty)
    }

    // MARK: - apply 到 RelationshipQuality

    @Test fun apply_adds_to_quality() {
        // 默认 RelationshipQuality(familiarity10/trust20/closeness10/rapport10/respect35/fun20/tension5/attachment5)
        val impact = GiftRelationshipImpact(closeness = 3, attachment = 7, respect = 5)
        val q = GiftRelationshipImpactService.apply(impact, RelationshipQuality())
        assertEquals(13, q.closeness)           // 10+3
        assertEquals(12, q.attachment)          // 5+7
        assertEquals(40, q.respect)             // 35+5
        assertEquals(10, q.familiarity)         // 未变
        assertEquals(20, q.trust)
    }

    @Test fun apply_clamps_to_100() {
        val q0 = RelationshipQuality().setValue(2, 98)   // closeness=98
        val q = GiftRelationshipImpactService.apply(GiftRelationshipImpact(closeness = 5), q0)
        assertEquals(100, q.closeness)
    }

    @Test fun apply_empty_no_change() {
        val q0 = RelationshipQuality()
        assertEquals(q0, GiftRelationshipImpactService.apply(GiftRelationshipImpact(), q0))
    }

    // MARK: - JSON round-trip

    @Test fun impact_json_roundtrip() {
        val impact = GiftRelationshipImpact(closeness = 3, attachment = 7, respect = 5, tension = 2)
        val json = impact.toJson()
        assertFalse(json.isEmpty())
        assertEquals(impact, GiftRelationshipImpact.decode(json))
    }

    @Test fun decode_empty_returns_null() {
        assertNull(GiftRelationshipImpact.decode(""))
    }

    @Test fun decode_corrupt_returns_null() {
        assertNull(GiftRelationshipImpact.decode("{这里损坏}"))
    }

    @Test fun isEmpty_check() {
        assertTrue(GiftRelationshipImpact().isEmpty)
        assertFalse(GiftRelationshipImpact(trust = 1).isEmpty)
    }
}
