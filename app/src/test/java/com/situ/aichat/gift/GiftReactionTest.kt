package com.situ.aichat.gift

import com.situ.aichat.data.model.GiftCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 礼物店反应流纯函数单测（d-2，断言**反推 iOS** `GiftSendService.parseReaction` / `fallbackReaction` / `defaultEmoji`）。
 *
 * 覆盖钱/算法移植易错点：
 * - parseReaction 把 LLM 分 **clamp 到 [baseline, 20]**（先 [0,20] 再 max(baseline) → LLM 不能压到 baseline 以下）；
 * - 最终 gain = `applyMultiplier(clampedGain 或 baseline, timing × decay)`（三处一致），round 非截断 + clamp[1,20]；
 * - think 标签 / ```json 代码块提取；空 text → null；空 emoji → 品类默认。
 */
class GiftReactionTest {

    private val macaron = GiftCatalog.find("gift_macaron")!!   // 65 food，baseline=round(5.2)=5
    private val loveLetter = GiftCatalog.find("gift_love_letter")!! // 30 handmade，baseline=round(2.4×1.5=3.6)=4

    private fun reactionJson(text: String = "好喜欢，谢谢你！", emoji: String = "🥰", gain: Int) =
        """{"reactionText":"$text","moodEmoji":"$emoji","affinityGain":$gain}"""

    // ── parseReaction：affinityGain clamp 到 [baseline, 20] ──

    @Test fun parse_gain_within_range_unchanged() {
        // macaron baseline=5；LLM 18 ∈ [5,20] → 18
        assertEquals(18, GiftReactionService.parseReaction(reactionJson(gain = 18), macaron)!!.affinityGain)
    }

    @Test fun parse_gain_below_baseline_floored() {
        // macaron baseline=5；LLM 2 < 5 → floor 到 baseline 5
        assertEquals(5, GiftReactionService.parseReaction(reactionJson(gain = 2), macaron)!!.affinityGain)
    }

    @Test fun parse_gain_above_20_clamped() {
        // LLM 25 → coerceIn(0,20)=20 → max(5,20)=20
        assertEquals(20, GiftReactionService.parseReaction(reactionJson(gain = 25), macaron)!!.affinityGain)
    }

    @Test fun parse_gain_negative_floored_to_baseline() {
        // LLM -3 → coerceIn(0,20)=0 → max(5,0)=5
        assertEquals(5, GiftReactionService.parseReaction(reactionJson(gain = -3), macaron)!!.affinityGain)
    }

    @Test fun parse_handmade_cheap_floor_is_baseline() {
        // 手作便宜款 baseline 仍保底：love_letter baseline=4；LLM 1 → floor 4（心意重于价格）
        assertEquals(4, GiftReactionService.parseReaction(reactionJson(gain = 1), loveLetter)!!.affinityGain)
    }

    // ── parseReaction：文本/emoji/异常形态 ──

    @Test fun parse_empty_text_returns_null() {
        assertNull(GiftReactionService.parseReaction(reactionJson(text = "", gain = 10), macaron))
    }

    @Test fun parse_blank_emoji_uses_category_default() {
        // 空 emoji → 品类默认（food → 😋）
        val r = GiftReactionService.parseReaction(reactionJson(emoji = "", gain = 10), macaron)!!
        assertEquals("😋", r.moodEmoji)
    }

    @Test fun parse_strips_think_tags() {
        val withThink = "<think>纠结一下</think>" + reactionJson(gain = 12)
        assertEquals(12, GiftReactionService.parseReaction(withThink, macaron)!!.affinityGain)
    }

    @Test fun parse_extracts_from_code_block() {
        val block = "```json\n" + reactionJson(gain = 9) + "\n```"
        val r = GiftReactionService.parseReaction(block, macaron)!!
        assertEquals(9, r.affinityGain)
        assertEquals("好喜欢，谢谢你！", r.reactionText)
    }

    @Test fun parse_garbage_returns_null() {
        assertNull(GiftReactionService.parseReaction("这不是 JSON，只是大模型的废话", macaron))
    }

    // ── 最终 gain 公式：applyMultiplier(clampedGain 或 baseline, timing × decay) ──

    @Test fun final_gain_llm_times_timing_and_decay() {
        // LLM 18（∈[5,20]）× timing 1.0 × decay 0.80 = applyMultiplier(18,0.80)=round(14.4)=14
        val clamped = GiftReactionService.parseReaction(reactionJson(gain = 18), macaron)!!.affinityGain
        assertEquals(14, GiftMarginalDecayService.applyMultiplier(clamped, 1.0 * 0.80))
    }

    @Test fun final_gain_birthday_timing_lifts_floored_baseline() {
        // LLM 2 floor 到 baseline 5；生日 timing 3.0 × decay 1.0 = applyMultiplier(5,3.0)=round(15)=15
        val clamped = GiftReactionService.parseReaction(reactionJson(gain = 2), macaron)!!.affinityGain
        assertEquals(15, GiftMarginalDecayService.applyMultiplier(clamped, 3.0 * 1.0))
    }

    @Test fun final_gain_fallback_uses_baseline() {
        // 无 LLM 兜底：fallbackReaction.affinityGain = baseline 5；× timing 1.0 × decay 0.65 = round(3.25)=3
        val baseGain = GiftReactionService.fallbackReaction(macaron).affinityGain
        assertEquals(5, baseGain)
        assertEquals(3, GiftMarginalDecayService.applyMultiplier(baseGain, 1.0 * 0.65))
    }

    // ── defaultEmoji 7 品类 1:1 ──

    @Test fun default_emoji_all_categories() {
        assertEquals("😋", GiftReactionService.defaultEmoji(GiftCategory.FOOD))
        assertEquals("🥰", GiftReactionService.defaultEmoji(GiftCategory.FLOWER))
        assertEquals("✨", GiftReactionService.defaultEmoji(GiftCategory.ACCESSORY))
        assertEquals("☺️", GiftReactionService.defaultEmoji(GiftCategory.DAILY))
        assertEquals("😳", GiftReactionService.defaultEmoji(GiftCategory.LUXURY))
        assertEquals("🤩", GiftReactionService.defaultEmoji(GiftCategory.EXPERIENCE))
        assertEquals("🥹", GiftReactionService.defaultEmoji(GiftCategory.HANDMADE))
    }
}
