package com.situ.aichat.gift

import com.situ.aichat.data.local.entity.GiftRecordEntity
import com.situ.aichat.gift.GiftMomentQueueService.Companion.COOLDOWN_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 礼物 → 朋友圈/日记联动纯决策单测（P9.2e，断言反推 iOS `GiftMomentQueueService`）：
 * 冷却 24h 边界 / 候选过滤(晚于 T + 珍贵>200 严格 + 手作) / 代表礼物(价高>手作>最新) / 展示名 /
 * 朋友圈 hint(绝不引 DIY 正文) / 日记 hint(允许引 DIY 正文·英文·省价)。DB 查询路径留真机/集成验。
 *
 * 关键阈值（反推 iOS，非反推 Kotlin 输出）：cooldown=24h、window=48h、precious>200、DIY 价钳[2,20]永不珍贵。
 */
class GiftMomentQueueServiceTest {

    private fun rec(
        uuid: String = "g",
        pricePaid: Int = 0,
        itemId: String = "gift_oden",
        isDIY: Boolean = false,
        diyTitle: String = "",
        diyContent: String = "",
        timestamp: Long = 0L,
        receiver: String = "c1",
    ) = GiftRecordEntity(
        uuid = uuid,
        timestamp = timestamp,
        senderType = "user",
        receiverType = "character",
        receiverCharacterUUID = receiver,
        giftItemId = itemId,
        pricePaid = pricePaid,
        isDIY = isDIY,
        diyTitle = diyTitle,
        diyContent = diyContent,
    )

    // ---- isHandmadeLike：DIY 或 catalog.isHandmade ----

    @Test fun handmadeLike_diy() = assertTrue(GiftMomentQueueService.isHandmadeLike(rec(isDIY = true, itemId = "diy_user_x")))

    @Test fun handmadeLike_catalogHandmade() =
        assertTrue(GiftMomentQueueService.isHandmadeLike(rec(itemId = "gift_note"))) // gift_note isHandmade=true

    @Test fun handmadeLike_normalFalse() =
        assertFalse(GiftMomentQueueService.isHandmadeLike(rec(itemId = "gift_oden"))) // 非手作

    @Test fun handmadeLike_unknownIdFalse() =
        assertFalse(GiftMomentQueueService.isHandmadeLike(rec(itemId = "no_such_gift")))

    // ---- isEligibleForMoment：珍贵(>200 严格) 或 手作 ----

    @Test fun eligible_preciousStrictlyGreater() {
        assertFalse(GiftMomentQueueService.isEligibleForMoment(rec(pricePaid = 200, itemId = "gift_ktv"))) // 200 不算
        assertTrue(GiftMomentQueueService.isEligibleForMoment(rec(pricePaid = 201, itemId = "gift_ktv"))) // 201 算
    }

    @Test fun eligible_handmadeLowPrice() =
        assertTrue(GiftMomentQueueService.isEligibleForMoment(rec(pricePaid = 5, itemId = "gift_note")))

    @Test fun eligible_diyLowPrice() =
        assertTrue(GiftMomentQueueService.isEligibleForMoment(rec(pricePaid = 10, isDIY = true, itemId = "diy_user_x")))

    @Test fun eligible_cheapNonHandmadeFalse() =
        assertFalse(GiftMomentQueueService.isEligibleForMoment(rec(pricePaid = 50, itemId = "gift_oden")))

    // ---- canPostGiftMoment：null→可发；≥24h 才可发（边界 = 严格 ≥）----

    @Test fun cooldown_neverPostedAllowed() = assertTrue(GiftMomentQueueService.canPostGiftMoment(null, now = 1_000_000L))

    @Test fun cooldown_within24hBlocked() {
        val now = 100_000_000L
        assertFalse(GiftMomentQueueService.canPostGiftMoment(now - (COOLDOWN_MS - 1), now)) // 差 1ms 不到 24h
    }

    @Test fun cooldown_exactly24hAllowed() {
        val now = 100_000_000L
        assertTrue(GiftMomentQueueService.canPostGiftMoment(now - COOLDOWN_MS, now)) // 整 24h 即可（>=）
    }

    @Test fun cooldown_past24hAllowed() {
        val now = 100_000_000L
        assertTrue(GiftMomentQueueService.canPostGiftMoment(now - (COOLDOWN_MS + 1), now))
    }

    // ---- filterFreshEligible：晚于 T（严格 >）且 eligible，保序 ----

    @Test fun filter_freshAndEligibleOnly() {
        val since = 1000L
        val a = rec(uuid = "a", pricePaid = 380, itemId = "gift_steak", timestamp = 1000L) // 不 > since → 排除
        val b = rec(uuid = "b", pricePaid = 380, itemId = "gift_steak", timestamp = 1001L) // 保留
        val c = rec(uuid = "c", pricePaid = 50, itemId = "gift_oden", timestamp = 2000L) // 不 eligible → 排除
        val d = rec(uuid = "d", pricePaid = 5, itemId = "gift_note", timestamp = 1500L) // 手作 → 保留
        val out = GiftMomentQueueService.filterFreshEligible(listOf(a, b, c, d), since)
        assertEquals(listOf("b", "d"), out.map { it.uuid }) // 保输入序
    }

    @Test fun filter_sinceMinValueKeepsAll() {
        val a = rec(uuid = "a", pricePaid = 380, itemId = "gift_steak", timestamp = 0L)
        val out = GiftMomentQueueService.filterFreshEligible(listOf(a), Long.MIN_VALUE)
        assertEquals(listOf("a"), out.map { it.uuid }) // 从没晒过 → 全留
    }

    // ---- representativeGift：价高 > 手作 > 最新 ----

    @Test fun representative_priceWins() {
        val cheap = rec(uuid = "cheap", pricePaid = 100, itemId = "gift_steak", timestamp = 9L)
        val pricey = rec(uuid = "pricey", pricePaid = 300, itemId = "gift_steak", timestamp = 1L)
        assertEquals("pricey", GiftMomentQueueService.representativeGift(listOf(cheap, pricey))?.uuid)
    }

    @Test fun representative_handmadeWinsTiePrice() {
        val normal = rec(uuid = "normal", pricePaid = 200, itemId = "gift_ktv", timestamp = 5L)
        val handmade = rec(uuid = "handmade", pricePaid = 200, isDIY = true, itemId = "diy_x", timestamp = 1L)
        assertEquals("handmade", GiftMomentQueueService.representativeGift(listOf(normal, handmade))?.uuid)
    }

    @Test fun representative_newestWinsTiePriceHandmade() {
        val older = rec(uuid = "older", pricePaid = 200, itemId = "gift_ktv", timestamp = 10L)
        val newer = rec(uuid = "newer", pricePaid = 200, itemId = "gift_ktv", timestamp = 20L)
        assertEquals("newer", GiftMomentQueueService.representativeGift(listOf(older, newer))?.uuid)
    }

    @Test fun representative_emptyNull() = assertNull(GiftMomentQueueService.representativeGift(emptyList()))

    // ---- displayName ----

    @Test fun displayName_diyTitle() =
        assertEquals("给你的信", GiftMomentQueueService.displayName(rec(isDIY = true, diyTitle = "给你的信")))

    @Test fun displayName_diyEmptyTitleFallback() =
        assertEquals("a handmade gift", GiftMomentQueueService.displayName(rec(isDIY = true, diyTitle = "  ")))

    @Test fun displayName_catalogName() =
        assertEquals("关东煮", GiftMomentQueueService.displayName(rec(itemId = "gift_oden")))

    @Test fun displayName_unknownFallback() =
        assertEquals("a thoughtful gift", GiftMomentQueueService.displayName(rec(itemId = "no_such_gift")))

    // ---- 朋友圈 hint（英文，绝不引 DIY 正文）----

    @Test fun momentHint_emptyNull() = assertNull(GiftMomentQueueService.buildPromptHint(emptyList()))

    @Test fun momentHint_singlePreciousVerbatim() {
        val hint = GiftMomentQueueService.buildPromptHint(listOf(rec(pricePaid = 380, itemId = "gift_steak")))
        val expected = listOf(
            "Today your friend gave you a gift:",
            "- 高级牛排 (a precious gift)",
            "",
            "You genuinely want to share a social post about it. Write about how it made you feel — the specific detail that touched you, not a formulaic thank-you. Mention the gift(s) by name if it feels natural. For handmade items, you may mention the title but NEVER quote or paraphrase the private message/content the user wrote inside — that's between you two. Keep the post authentic and personal, not performative.",
        ).joinToString("\n")
        assertEquals(expected, hint)
    }

    @Test fun momentHint_diyTagAndNeverQuotesContent() {
        val hint = GiftMomentQueueService.buildPromptHint(
            listOf(rec(isDIY = true, diyTitle = "给你的信", diyContent = "私密的话千万别外传", pricePaid = 10)),
        )!!
        assertTrue(hint.startsWith("Today your friend gave you a gift:\n- 给你的信 (a handmade card from the user)\n"))
        assertFalse(hint.contains("私密的话千万别外传")) // 朋友圈版绝不引用 DIY 正文
    }

    @Test fun momentHint_catalogHandmadeTag() {
        val hint = GiftMomentQueueService.buildPromptHint(listOf(rec(pricePaid = 5, itemId = "gift_note")))!!
        assertTrue(hint.contains("- 手写便签 (a handmade card)"))
    }

    @Test fun momentHint_multipleHeader() {
        val hint = GiftMomentQueueService.buildPromptHint(
            listOf(rec(uuid = "a", pricePaid = 380, itemId = "gift_steak"), rec(uuid = "b", pricePaid = 5, itemId = "gift_note")),
        )!!
        assertTrue(hint.startsWith("Recently your friend gave you these gifts:\n"))
    }

    // ---- 日记 hint（英文，允许引 DIY 正文，省价）----

    @Test fun diaryHint_emptyNull() = assertNull(GiftMomentQueueService.buildDiaryPromptHint(emptyList(), emptyMap()))

    @Test fun diaryHint_diyQuotesContentVerbatim() {
        val hint = GiftMomentQueueService.buildDiaryPromptHint(
            listOf(rec(isDIY = true, diyTitle = "信", diyContent = "我想你了", receiver = "c1")),
            mapOf("c1" to "小樱"),
        )
        val expected = listOf(
            "Recently you gave a meaningful gift to your AI companion:",
            "- 信 (handmade, given to 小樱; you wrote inside: “我想你了”)",
            "",
            "These gift moments touched you. Weave the feelings NATURALLY into the diary's flow — do NOT write a dedicated paragraph just about gifts, and do NOT list them mechanically. Instead: mention choosing it, how their reaction lingered in your mind, a small detail you noticed, a memory it sparked, or a fragment of the handmade words you wrote that keeps coming back. You MAY quote the handmade content above directly if it fits — this is your private diary, only you read it. Omit prices entirely. Keep first-person, intimate, real — gifts are one thread among today's events, not the headline.",
        ).joinToString("\n")
        assertEquals(expected, hint)
    }

    @Test fun diaryHint_diyEmptyContentNoQuote() {
        val hint = GiftMomentQueueService.buildDiaryPromptHint(
            listOf(rec(isDIY = true, diyTitle = "信", diyContent = "   ", receiver = "c1")),
            mapOf("c1" to "小樱"),
        )!!
        assertTrue(hint.contains("- 信 (handmade, given to 小樱)"))
        assertFalse(hint.contains("you wrote inside"))
    }

    @Test fun diaryHint_preciousAndNameFallback() {
        val hint = GiftMomentQueueService.buildDiaryPromptHint(
            listOf(rec(pricePaid = 380, itemId = "gift_steak", receiver = "unknown")),
            emptyMap(), // 查不到名 → your friend
        )!!
        assertTrue(hint.contains("- 高级牛排 (a precious gift, given to your friend)"))
    }

    @Test fun diaryHint_catalogHandmadeTag() {
        val hint = GiftMomentQueueService.buildDiaryPromptHint(
            listOf(rec(pricePaid = 5, itemId = "gift_note", receiver = "c1")),
            mapOf("c1" to "小樱"),
        )!!
        assertTrue(hint.contains("- 手写便签 (handmade, given to 小樱)"))
    }

    @Test fun diaryHint_multipleCompanionsHeader() {
        val hint = GiftMomentQueueService.buildDiaryPromptHint(
            listOf(
                rec(uuid = "a", pricePaid = 380, itemId = "gift_steak", receiver = "c1"),
                rec(uuid = "b", pricePaid = 5, itemId = "gift_note", receiver = "c2"),
            ),
            mapOf("c1" to "小樱", "c2" to "阿木"),
        )!!
        assertTrue(hint.startsWith("Recently you gave these meaningful gifts to your AI companions:\n"))
    }
}
