package com.situ.aichat.gift

import com.situ.aichat.data.model.GiftCardData
import com.situ.aichat.data.model.GiftContext
import com.situ.aichat.data.model.GiftSender
import com.situ.aichat.data.model.ProactiveGiftTriggerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主动送礼执行器纯函数单测（断言反推 iOS `ProactiveGiftExecutorTests`）：calculateAffinityGain 公式（×0.08·手作×1.5·
 * clamp[1,20]·无 luxury 折扣）、giftContextFor 映射、growthLogSummary（手作标 + 28 字截断）。原子 11 步编排走 Room，
 * 留真机 + 独立复核（钱路径）。另含 9.2b-4 复核 L2：GiftCardData.llmRepresentation CHARACTER 分支空名兜底「角色」。
 */
class ProactiveGiftExecutorTest {

    // ── calculateAffinityGain（无 luxury 折扣） ────────────────────────

    @Test fun affinity_normal_gift() {
        // 玫瑰 20：20 × 0.08 = 1.6 → round 2
        assertEquals(2, ProactiveGiftExecutor.calculateAffinityGain(20, false))
    }

    @Test fun affinity_handmade_1_5x() {
        // 便签 5：5 × 0.08 × 1.5 = 0.6 → clamp 最低 1
        assertEquals(1, ProactiveGiftExecutor.calculateAffinityGain(5, true))
        // 情书 30：30 × 0.08 × 1.5 = 3.6 → 4
        assertEquals(4, ProactiveGiftExecutor.calculateAffinityGain(30, true))
    }

    @Test fun affinity_cap_20() {
        // 钻戒 1880：1880 × 0.08 = 150.4 → clamp 20
        assertEquals(20, ProactiveGiftExecutor.calculateAffinityGain(1880, false))
    }

    @Test fun affinity_floor_1() {
        assertEquals(1, ProactiveGiftExecutor.calculateAffinityGain(0, false))
    }

    // ── giftContextFor 映射 ───────────────────────────────────────────

    @Test fun gift_context_mapping() {
        assertEquals(GiftContext.BIRTHDAY, ProactiveGiftExecutor.giftContextFor(ProactiveGiftTriggerType.BIRTHDAY))
        assertEquals(GiftContext.ANNIVERSARY, ProactiveGiftExecutor.giftContextFor(ProactiveGiftTriggerType.ANNIVERSARY))
        assertEquals(GiftContext.FESTIVAL, ProactiveGiftExecutor.giftContextFor(ProactiveGiftTriggerType.FESTIVAL))
        assertEquals(GiftContext.COMFORT, ProactiveGiftExecutor.giftContextFor(ProactiveGiftTriggerType.SENSE_LOW_MOOD))
        assertEquals(GiftContext.RANDOM, ProactiveGiftExecutor.giftContextFor(ProactiveGiftTriggerType.MISSING_YOU))
    }

    // ── growthLogSummary ──────────────────────────────────────────────

    @Test fun growth_log_summary_precious_non_handmade() {
        // gift_99_roses 888（珍贵，非手作）
        val s = ProactiveGiftExecutor.growthLogSummary(GiftCatalog.find("gift_99_roses")!!, "为你而送")
        assertEquals("主动送给用户 99 朵玫瑰:为你而送", s)
        assertTrue(s.contains("99"))
    }

    @Test fun growth_log_summary_handmade_marks_handmade() {
        // gift_note 5（手作）
        val s = ProactiveGiftExecutor.growthLogSummary(GiftCatalog.find("gift_note")!!, "给你写的")
        assertTrue(s.contains("手作"))
        assertEquals("主动送给用户 手写便签(手作):给你写的", s)
    }

    @Test fun growth_log_summary_truncates_over_30_chars() {
        // 31 字消息 → 前 28 + "…"
        val longMsg = "啊".repeat(31)
        val s = ProactiveGiftExecutor.growthLogSummary(GiftCatalog.find("gift_99_roses")!!, longMsg)
        assertTrue(s.endsWith("…"))
        assertEquals("主动送给用户 99 朵玫瑰:" + "啊".repeat(28) + "…", s)
    }

    @Test fun growth_log_summary_no_truncate_at_30() {
        val msg30 = "啊".repeat(30)
        val s = ProactiveGiftExecutor.growthLogSummary(GiftCatalog.find("gift_99_roses")!!, msg30)
        assertEquals("主动送给用户 99 朵玫瑰:$msg30", s)
    }

    // ── 9.2b-4 复核 L2：llmRepresentation CHARACTER 分支空名兜底「角色」 ──

    private fun card(sender: GiftSender?, name: String) = GiftCardData(
        type = "gift_card", giftItemId = "gift_x", giftRecordId = "r", cost = 20,
        giftName = "玫瑰", isHandmade = false, senderType = sender,
    ).llmRepresentation(name)

    @Test fun llm_representation_character_empty_name_falls_back() {
        // senderType=CHARACTER + 空名 → 「角色送出礼物」（不是「送出礼物」缺主语）
        assertTrue(card(GiftSender.CHARACTER, "").contains("角色送出礼物"))
    }

    @Test fun llm_representation_character_uses_name() {
        assertTrue(card(GiftSender.CHARACTER, "小雨").contains("小雨送出礼物"))
    }

    @Test fun llm_representation_user_unaffected_by_empty_name() {
        assertTrue(card(GiftSender.USER, "").contains("用户送出礼物"))
    }

    /** 图纸一 R1 承接·指名：USER 分支传用户名 → 用真名（不传回退「用户」·上一例已证）。 */
    @Test fun llm_representation_user_uses_userName() {
        assertTrue(
            GiftCardData(
                type = "gift_card", giftItemId = "gift_x", giftRecordId = "r", cost = 20,
                giftName = "玫瑰", isHandmade = false, senderType = GiftSender.USER,
            ).llmRepresentation("", "小明").contains("小明送出礼物"),
        )
    }
}
