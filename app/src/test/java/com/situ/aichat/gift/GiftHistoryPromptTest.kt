package com.situ.aichat.gift

import com.situ.aichat.data.local.entity.GiftRecordEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 礼物历史提示词单测（断言**反推 iOS `GiftHistoryPromptServiceTests`**）：relativeGiftTime 边界（今天/昨天/天/周/月/年/
 * 未来"刚刚"）、render 双向格式（summary 珍贵/手作子句、recent 前 3、最珍贵去重、手作识别 isDIY/目录、双段省略）。
 * 时间用 noon + systemDefault 构造，与 render 内 relativeGiftTime 同区 → 日历日差确定。
 */
class GiftHistoryPromptTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val user = "小明" // 图纸一·A1：render 现需用户名参数（人称=「你」=角色 + 用户名）
    private val nowZdt = ZonedDateTime.of(2026, 6, 15, 12, 0, 0, 0, zone)
    private val now = nowZdt.toInstant().toEpochMilli()
    private fun daysAgo(n: Int): Long = nowZdt.minusDays(n.toLong()).toInstant().toEpochMilli()
    private fun daysAhead(n: Int): Long = nowZdt.plusDays(n.toLong()).toInstant().toEpochMilli()

    private fun rec(
        giftItemId: String,
        price: Int,
        daysAgo: Int,
        isDIY: Boolean = false,
        diyTitle: String = "",
    ) = GiftRecordEntity(
        timestamp = daysAgo(daysAgo), giftItemId = giftItemId, pricePaid = price,
        isDIY = isDIY, diyTitle = diyTitle,
    )

    // MARK: - relativeGiftTime 纯数学

    @Test fun relative_today_yesterday() {
        assertEquals("今天", GiftHistoryPromptService.relativeGiftTime(daysAgo(0), now, zone))
        assertEquals("昨天", GiftHistoryPromptService.relativeGiftTime(daysAgo(1), now, zone))
    }

    @Test fun relative_2_to_6_days() {
        for (d in 2..6) {
            assertEquals("$d 天前", GiftHistoryPromptService.relativeGiftTime(daysAgo(d), now, zone))
        }
    }

    @Test fun relative_weeks() {
        assertEquals("约 1 周前", GiftHistoryPromptService.relativeGiftTime(daysAgo(7), now, zone))
        assertEquals("约 2 周前", GiftHistoryPromptService.relativeGiftTime(daysAgo(14), now, zone))
        assertEquals("约 4 周前", GiftHistoryPromptService.relativeGiftTime(daysAgo(29), now, zone))
    }

    @Test fun relative_months() {
        assertEquals("约 1 个月前", GiftHistoryPromptService.relativeGiftTime(daysAgo(30), now, zone))
        assertEquals("约 3 个月前", GiftHistoryPromptService.relativeGiftTime(daysAgo(90), now, zone))
    }

    @Test fun relative_years() {
        assertEquals("约 1 年前", GiftHistoryPromptService.relativeGiftTime(daysAgo(400), now, zone))
        assertEquals("约 2 年前", GiftHistoryPromptService.relativeGiftTime(daysAgo(800), now, zone))
    }

    @Test fun relative_future_returns_just_now() {
        assertEquals("刚刚", GiftHistoryPromptService.relativeGiftTime(daysAhead(3), now, zone))
    }

    // MARK: - render（双向格式）

    @Test fun render_empty_returns_blank() {
        assertEquals("", GiftHistoryPromptService.render(emptyList(), emptyList(), now, user))
    }

    @Test fun render_single_small_gift() {
        val r = GiftHistoryPromptService.render(listOf(rec("gift_boba_tea", 15, 1)), emptyList(), now, user)
        assertTrue(r.contains("<gift_history>"))
        assertTrue(r.contains("</gift_history>"))
        assertTrue(r.contains("共 1 份礼物"))
        assertTrue(r.contains("珍珠奶茶"))
        assertTrue(r.contains("昨天"))
        assertFalse(r.contains("件珍贵"))
        assertFalse(r.contains("件手作"))
        assertFalse(r.contains("最珍贵"))
    }

    @Test fun render_mixed_full_format() {
        // desc 排序：boba(1)、love_letter(2)、coffee(3)、hotpot(5)、99roses(60)
        val list = listOf(
            rec("gift_boba_tea", 15, 1),
            rec("gift_love_letter", 30, 2),
            rec("gift_coffee", 20, 3),
            rec("gift_hotpot", 180, 5),
            rec("gift_99_roses", 888, 60),
        )
        val r = GiftHistoryPromptService.render(list, emptyList(), now, user)
        assertTrue(r.contains("共 5 份礼物"))
        assertTrue(r.contains("1 件珍贵"))
        assertTrue(r.contains("1 件手作"))
        // recent 3 = boba/love_letter/coffee
        assertTrue(r.contains("珍珠奶茶"))
        assertTrue(r.contains("手写情书"))
        assertTrue(r.contains("手冲咖啡"))
        assertTrue(r.contains("手写情书(手作·"))
        // 最珍贵 = 99 朵玫瑰（不在 recent3）
        assertTrue(r.contains("最珍贵"))
        assertTrue(r.contains("99 朵玫瑰"))
    }

    @Test fun render_precious_in_recent_no_duplicate_line() {
        val list = listOf(
            rec("gift_ring", 1880, 0),
            rec("gift_boba_tea", 15, 1),
            rec("gift_coffee", 20, 2),
        )
        val r = GiftHistoryPromptService.render(list, emptyList(), now, user)
        assertTrue(r.contains("钻戒"))
        // 钻戒已在 recent → 不再列最珍贵
        assertEquals(0, r.split("最珍贵").size - 1)
    }

    @Test fun render_handmade_catalog_and_diy() {
        // 目录手作
        val cat = GiftHistoryPromptService.render(
            listOf(rec("gift_note", 5, 1), rec("gift_postcard", 10, 2)), emptyList(), now, user,
        )
        assertTrue(cat.contains("2 件手作"))
        assertTrue(cat.contains("手写便签(手作·"))
        assertTrue(cat.contains("手写明信片(手作·"))
        // 运行时 DIY（目录查不到，用 diyTitle）
        val diy = GiftHistoryPromptService.render(
            listOf(rec("diy_custom_thing", 100, 1, isDIY = true, diyTitle = "我给你画的小熊")), emptyList(), now, user,
        )
        assertTrue(diy.contains("1 件手作"))
        assertTrue(diy.contains("我给你画的小熊(手作·"))
    }

    @Test fun render_two_directions() {
        val r = GiftHistoryPromptService.render(
            listOf(rec("gift_boba_tea", 15, 1)),
            listOf(rec("gift_rose_bouquet", 150, 3)),
            now,
            user,
        )
        // 图纸一·A1·§9：标签=「你」=角色 + 用户名；不再含旧「用户送我」「我送用户」。金额/份数断言不变。
        assertTrue(r.contains("【小明送你的】共 1 份礼物"))
        assertFalse("旧标签消失", r.contains("【用户送我】"))
        assertTrue(r.contains("珍珠奶茶"))
        assertTrue(r.contains("【你送小明的】共 1 份礼物"))
        assertFalse("旧标签消失", r.contains("【我送用户】"))
        assertTrue(r.contains("玫瑰花束"))
    }

    @Test fun render_only_character_to_user_section() {
        val r = GiftHistoryPromptService.render(emptyList(), listOf(rec("gift_coffee", 20, 0)), now, user)
        assertTrue(r.contains("【你送小明的】"))
        assertTrue(r.contains("手冲咖啡"))
        assertFalse(r.contains("【小明送你的】"))
    }

    @Test fun render_recent3_desc_excludes_4th() {
        val list = listOf(
            rec("gift_cake_slice", 35, 0),
            rec("gift_coffee", 20, 1),
            rec("gift_boba_tea", 15, 2),
            rec("gift_oden", 8, 10),
        )
        val r = GiftHistoryPromptService.render(list, emptyList(), now, user)
        assertTrue(r.contains("共 4 份礼物"))
        assertTrue(r.contains("小蛋糕"))
        assertTrue(r.contains("手冲咖啡"))
        assertTrue(r.contains("珍珠奶茶"))
        assertFalse(r.contains("关东煮"))
    }
}
