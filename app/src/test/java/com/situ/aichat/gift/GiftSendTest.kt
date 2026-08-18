package com.situ.aichat.gift

import com.situ.aichat.data.model.GiftCardData
import com.situ.aichat.data.model.GiftSender
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 送礼纯函数单测（断言反推 iOS）：growthLog summary（手作优先于金额分档 / DIY "用户 DIY" 区别）、giftCard
 * llmRepresentation（方向/手作徽章/分档文案/DIY 标题附言截 80）。原子编排 sendInChat/DIY 走 Room，留真机+独立复核。
 */
class GiftSendTest {

    private fun card(
        giftName: String,
        cost: Int,
        isHandmade: Boolean = false,
        sender: GiftSender? = GiftSender.USER,
        diyTitle: String? = null,
        diyContent: String? = null,
    ) = GiftCardData(
        type = "gift_card", giftItemId = "x", giftRecordId = "r", cost = cost,
        giftName = giftName, isHandmade = isHandmade, senderType = sender,
        diyTitle = diyTitle, diyContent = diyContent,
    )

    // MARK: - growthLogSummary（手作优先）

    @Test fun growthLogSummary_non_handmade() {
        // gift_boba_tea 15 → tier 小心意
        assertEquals(
            "收到用户的礼物：珍珠奶茶（小心意）",
            GiftSendService.growthLogSummary(GiftCatalog.find("gift_boba_tea")!!),
        )
        // gift_steak 380 → 珍贵的心意
        assertEquals(
            "收到用户的礼物：高级牛排（珍贵的心意）",
            GiftSendService.growthLogSummary(GiftCatalog.find("gift_steak")!!),
        )
    }

    @Test fun growthLogSummary_handmade_tag_before_tier() {
        // gift_love_letter 30 手作 → tier 小心意，手作标签在前
        assertEquals(
            "收到用户的礼物：手写情书（手作 · 小心意）",
            GiftSendService.growthLogSummary(GiftCatalog.find("gift_love_letter")!!),
        )
    }

    // MARK: - diyGrowthLogSummary（用户 DIY 区别预置手作）

    @Test fun diyGrowthLogSummary_title_and_fallback() {
        val diy = GiftCatalog.makeUserDIY("生日卡", "happy", cost = 8)  // 8 → 小心意
        assertEquals("收到用户的礼物：生日卡（用户 DIY · 小心意）", GiftSendService.diyGrowthLogSummary(diy, "生日卡"))
        // 空标题 → "手作礼物"
        assertEquals("收到用户的礼物：手作礼物（用户 DIY · 小心意）", GiftSendService.diyGrowthLogSummary(diy, ""))
    }

    // MARK: - llmRepresentation（方向 + 手作徽章 + 分档）

    @Test fun llm_user_gift() {
        assertEquals(
            "[系统记录：用户送出礼物 | 名称=珍珠奶茶 | 分量=小心意]",
            card("珍珠奶茶", 15).llmRepresentation("小满"),
        )
    }

    @Test fun llm_handmade_badge() {
        assertEquals(
            "[系统记录：用户送出礼物 | 名称=手写情书（手作） | 分量=小心意]",
            card("手写情书", 30, isHandmade = true).llmRepresentation("小满"),
        )
    }

    @Test fun llm_character_sender_uses_name() {
        assertEquals(
            "[系统记录：小满送出礼物 | 名称=玫瑰花束 | 分量=用心的选择]",
            card("玫瑰花束", 150, sender = GiftSender.CHARACTER).llmRepresentation("小满"),
        )
    }

    @Test fun llm_null_sender_defaults_user() {
        // 老消息 senderType=null → "用户送出礼物"
        assertEquals(
            "[系统记录：用户送出礼物 | 名称=钻戒 | 分量=珍贵的心意]",
            card("钻戒", 1880, sender = null).llmRepresentation("小满"),
        )
    }

    @Test fun llm_diy_appends_title_and_note() {
        assertEquals(
            "[系统记录：用户送出礼物 | 名称=生日卡（手作） | 分量=小心意] | 标题=生日卡 | 附言=「祝你生日快乐」",
            card("生日卡", 8, isHandmade = true, diyTitle = "生日卡", diyContent = "祝你生日快乐").llmRepresentation("小满"),
        )
    }

    @Test fun llm_diy_content_capped_80() {
        val content = "字".repeat(81)  // 81 字
        val rep = card("卡", 5, diyContent = content).llmRepresentation("小满")
        // 截 80 + "…"
        assertEquals("附言=「${"字".repeat(80)}…」", rep.substringAfter("分量=小心意] | "))
    }
}
