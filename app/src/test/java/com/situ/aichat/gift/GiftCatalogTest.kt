package com.situ.aichat.gift

import com.situ.aichat.data.model.GiftCardData
import com.situ.aichat.data.model.GiftCardJson
import com.situ.aichat.data.model.GiftCategory
import com.situ.aichat.data.model.GiftEmotionalTag
import com.situ.aichat.data.model.GiftSender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 礼物目录完整性 + DIY 工厂 + GiftCardData JSON 序列化单测（断言反推 iOS 46 件分布、makeUserDIY clamp/截断、
 * Codable 省略 nil 可选字段行为）。
 */
class GiftCatalogTest {

    // MARK: - 目录完整性（46 件分布 1:1 iOS）

    @Test fun catalog_has_46_unique_items() {
        assertEquals(46, GiftCatalog.allItems.size)
        assertEquals(46, GiftCatalog.allItems.map { it.id }.distinct().size)
    }

    @Test fun catalog_category_distribution() {
        fun n(c: GiftCategory) = GiftCatalog.allItems.count { it.category == c }
        assertEquals(10, n(GiftCategory.FOOD))
        assertEquals(7, n(GiftCategory.FLOWER))
        assertEquals(7, n(GiftCategory.ACCESSORY))
        assertEquals(8, n(GiftCategory.DAILY))
        assertEquals(5, n(GiftCategory.LUXURY))
        assertEquals(5, n(GiftCategory.EXPERIENCE))
        assertEquals(4, n(GiftCategory.HANDMADE))
    }

    @Test fun handmade_category_items_are_all_handmade() {
        GiftCatalog.items(GiftCategory.HANDMADE).forEach { assertTrue(it.isHandmade) }
        // 非手作品类无 isHandmade
        assertFalse(GiftCatalog.find("gift_oden")!!.isHandmade)
    }

    @Test fun find_and_sort() {
        assertEquals("珍珠奶茶", GiftCatalog.find("gift_boba_tea")!!.name)
        assertNull(GiftCatalog.find("nonexistent"))
        assertNull(GiftCatalog.find("diy_user_abc123"))  // DIY 不在目录
        // 最便宜=手写便签 5；最贵=钻戒 1880
        assertEquals("gift_note", GiftCatalog.sortedByPrice(ascending = true).first().id)
        assertEquals("gift_ring", GiftCatalog.sortedByPrice(ascending = false).first().id)
    }

    // MARK: - makeUserDIY（clamp[2,20] + 截断 + 前缀 + 固定属性）

    @Test fun diy_cost_clamped() {
        assertEquals(2, GiftCatalog.makeUserDIY("t", "c", cost = 1).price)   // < 2 → 2
        assertEquals(2, GiftCatalog.makeUserDIY("t", "c", cost = 2).price)
        assertEquals(10, GiftCatalog.makeUserDIY("t", "c", cost = 10).price)
        assertEquals(20, GiftCatalog.makeUserDIY("t", "c", cost = 20).price)
        assertEquals(20, GiftCatalog.makeUserDIY("t", "c", cost = 25).price)  // > 20 → 20
    }

    @Test fun diy_name_fallback() {
        assertEquals("手作礼物", GiftCatalog.makeUserDIY(title = "", content = "c", cost = 5).name)
        assertEquals("手作礼物", GiftCatalog.makeUserDIY(title = "   ", content = "c", cost = 5).name)  // trim 后空
        assertEquals("生日卡", GiftCatalog.makeUserDIY(title = "  生日卡 ", content = "c", cost = 5).name)
    }

    @Test fun diy_subtitle_truncation() {
        assertEquals("亲手做的一份", GiftCatalog.makeUserDIY("t", content = "", cost = 5).subtitle)
        // 恰 15 字 → 不截断
        val exactly15 = "一二三四五六七八九十一二三四五"
        assertEquals(exactly15, GiftCatalog.makeUserDIY("t", content = exactly15, cost = 5).subtitle)
        // 16 字 → take(15) + "…"
        val sixteen = exactly15 + "六"
        assertEquals(exactly15 + "…", GiftCatalog.makeUserDIY("t", content = sixteen, cost = 5).subtitle)
    }

    @Test fun diy_fixed_attributes() {
        val diy = GiftCatalog.makeUserDIY("生日卡", "happy", cost = 8)
        assertTrue(diy.id.startsWith(GiftCatalog.userDIYIdPrefix))
        assertEquals(GiftCategory.HANDMADE, diy.category)
        assertTrue(diy.isHandmade)
        assertFalse(diy.isSignature)
        assertEquals(listOf(GiftEmotionalTag.THOUGHTFUL, GiftEmotionalTag.NOSTALGIC), diy.emotionalTags)
    }

    // MARK: - GiftCardData JSON 序列化（省略 nil 可选 = iOS Codable）

    @Test fun giftcard_encode_includes_required_and_sender() {
        val card = GiftCardData(
            type = "gift_card", giftItemId = "gift_rose_single", giftRecordId = "r1",
            cost = 20, giftName = "单枝玫瑰", isHandmade = false, senderType = GiftSender.USER,
        )
        val js = GiftCardJson.encode(card)
        assertTrue(js.contains("\"type\":\"gift_card\""))
        assertTrue(js.contains("\"senderType\":\"user\""))
    }

    @Test fun giftcard_encode_omits_null_optionals() {
        val card = GiftCardData(
            type = "gift_card", giftItemId = "gift_rose_single", giftRecordId = "r1",
            cost = 20, giftName = "单枝玫瑰", isHandmade = false,
        )
        val js = GiftCardJson.encode(card)
        assertFalse(js.contains("senderType"))   // null → 省略
        assertFalse(js.contains("diyTitle"))
        assertFalse(js.contains("diyContent"))
    }

    @Test fun giftcard_roundtrip() {
        val card = GiftCardData(
            type = "gift_card", giftItemId = "diy_user_x", giftRecordId = "r2",
            cost = 8, giftName = "生日卡", isHandmade = true,
            diyTitle = "生日卡", diyContent = "happy", senderType = GiftSender.CHARACTER,
        )
        assertEquals(card, GiftCardJson.parse(GiftCardJson.encode(card)))
    }

    @Test fun giftcard_parse_rejects_non_gift() {
        assertNull(GiftCardJson.parse("hello"))                         // 非 JSON
        assertNull(GiftCardJson.parse("{\"type\":\"plain\"}"))          // 类型不符
        assertNull(GiftCardJson.parse("{\"foo\":1}"))                   // 缺 type
    }
}
