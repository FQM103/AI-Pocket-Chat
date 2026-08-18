package com.situ.aichat.gift

import com.situ.aichat.data.local.entity.GiftRecordEntity
import com.situ.aichat.data.model.GiftImpressionTag
import com.situ.aichat.data.model.MoodHistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 送礼印象标签单测（断言**反推 iOS `GiftImpressionTagServiceTests`**）：16 标签触发边界（频次/品类/价值严格>/工艺/独立）+
 * selectTags 同组取最高 + top3 + 空。时间用 noon + systemDefault（与服务内 sendDateSpan/remembering 同区）。
 */
class GiftImpressionTagTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val nowZdt = ZonedDateTime.of(2026, 6, 15, 12, 0, 0, 0, zone)
    private val now = nowZdt.toInstant().toEpochMilli()
    private fun daysAgo(n: Int): Long = nowZdt.minusDays(n.toLong()).toInstant().toEpochMilli()
    private fun birthdayMillis(month: Int, day: Int): Long =
        ZonedDateTime.of(1990, month, day, 12, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun rec(giftItemId: String, daysAgo: Int, price: Int? = null, isDIY: Boolean = false) =
        GiftRecordEntity(
            timestamp = daysAgo(daysAgo), giftItemId = giftItemId,
            pricePaid = price ?: (GiftCatalog.find(giftItemId)?.price ?: 10), isDIY = isDIY,
        )

    private fun fires(tag: GiftImpressionTag, records: List<GiftRecordEntity>, mood: List<MoodHistoryEntry> = emptyList(), birthday: Long? = null) =
        GiftImpressionTagService.triggers(tag, records, mood, birthday, now)

    // MARK: - 频次组

    @Test fun constantPresence_7d_2_fires() {
        assertTrue(fires(GiftImpressionTag.CONSTANT_PRESENCE, listOf(rec("gift_boba_tea", 1), rec("gift_coffee", 3))))
        assertFalse(fires(GiftImpressionTag.CONSTANT_PRESENCE, listOf(rec("gift_boba_tea", 1))))
    }

    @Test fun thoughtfulFrequency_30d_3_fires() {
        val r = (0 until 3).map { rec("gift_boba_tea", it * 10) }
        assertTrue(fires(GiftImpressionTag.THOUGHTFUL_FREQUENCY, r))
    }

    @Test fun persistent_span_30_fires() {
        assertTrue(fires(GiftImpressionTag.PERSISTENT, listOf(rec("gift_boba_tea", 0), rec("gift_coffee", 35))))
    }

    @Test fun indulgent_7d_3_fires() {
        assertTrue(fires(GiftImpressionTag.INDULGENT, (0 until 3).map { rec("gift_coffee", it) }))
    }

    // MARK: - 品类组

    @Test fun romantic_flower_40pct_fires() {
        // flower 2/5 = 40% 恰好
        val r = listOf(
            rec("gift_rose_single", 1), rec("gift_daisy", 2),
            rec("gift_boba_tea", 3), rec("gift_coffee", 4), rec("gift_cake_slice", 5),
        )
        assertTrue(fires(GiftImpressionTag.ROMANTIC, r))
    }

    @Test fun versatile_5_categories_fires_4_does_not() {
        val five = listOf(
            rec("gift_boba_tea", 1), rec("gift_rose_single", 2), rec("gift_hairclip", 3),
            rec("gift_candle", 4), rec("gift_ktv", 5),
        )
        assertTrue(fires(GiftImpressionTag.VERSATILE, five))
        assertFalse(fires(GiftImpressionTag.VERSATILE, five.take(4)))
    }

    // MARK: - 价值组（严格 >）

    @Test fun generous_strict_over_500() {
        assertFalse(fires(GiftImpressionTag.GENEROUS, listOf(rec("gift_weekend_trip", 10))))  // 500 不 > 500
        assertTrue(fires(GiftImpressionTag.GENEROUS, listOf(rec("gift_perfume", 10))))         // 680
    }

    @Test fun devoted_over_1000() {
        assertTrue(fires(GiftImpressionTag.DEVOTED, listOf(rec("gift_skincare_set", 10))))     // 1200
    }

    @Test fun littleButOften_5_small_fires() {
        assertTrue(fires(GiftImpressionTag.LITTLE_BUT_OFTEN, (0 until 5).map { rec("gift_boba_tea", it * 2) }))
    }

    // MARK: - 工艺组

    @Test fun artful_one_handmade_fires() {
        assertTrue(fires(GiftImpressionTag.ARTFUL, listOf(rec("gift_love_letter", 1))))
    }

    @Test fun meticulous_30pct_fires() {
        // 1 手作 + 2 食物 = 33%
        val r = listOf(rec("gift_love_letter", 1), rec("gift_boba_tea", 2), rec("gift_coffee", 3))
        assertTrue(fires(GiftImpressionTag.METICULOUS, r))
    }

    // MARK: - 独立标签

    @Test fun attuned_during_mood_low_fires() {
        val mood = listOf(
            MoodHistoryEntry(timestamp = now - 1800_000L, colorName = "red"),
            MoodHistoryEntry(timestamp = now - 3600_000L, colorName = "red"),
            MoodHistoryEntry(timestamp = now - 7200_000L, colorName = "red"),
        )
        assertTrue(fires(GiftImpressionTag.ATTUNED, listOf(rec("gift_cake_slice", 0)), mood = mood))
    }

    @Test fun remembering_birthday_gift_fires() {
        // 生日 6-15，送礼也在 6-15（now）
        assertTrue(fires(GiftImpressionTag.REMEMBERING, listOf(rec("gift_cake_slice", 0)), birthday = birthdayMillis(6, 15)))
    }

    @Test fun obsessed_60pct_5items_fires_under5_does_not() {
        // 5 flower + 2 food = 5/7 > 60% 且 5 件
        val fire = (0 until 5).map { rec("gift_rose_single", it) } + listOf(rec("gift_boba_tea", 10), rec("gift_coffee", 15))
        assertTrue(fires(GiftImpressionTag.OBSESSED, fire))
        // 4 flower + 1 food：80% 但单品类不足 5 件
        val no = (0 until 4).map { rec("gift_rose_single", it) } + listOf(rec("gift_boba_tea", 10))
        assertFalse(fires(GiftImpressionTag.OBSESSED, no))
    }

    // MARK: - selectTags 综合

    @Test fun selectTags_frequency_group_keeps_highest() {
        // 16 件 boba（food）days 0..15 → 频次组只保留 indulgent(80)
        val r = (0 until 16).map { rec("gift_boba_tea", it) }
        val tags = GiftImpressionTagService.selectTags(r, emptyList(), null, limit = 10, now = now)
        assertEquals(listOf(GiftImpressionTag.INDULGENT), tags.filter { it.group == "frequency" })
    }

    @Test fun selectTags_empty_returns_empty() {
        assertTrue(GiftImpressionTagService.selectTags(emptyList(), emptyList(), null, 3, now).isEmpty())
    }

    @Test fun selectTags_top3_by_priority() {
        // 多品类 + 手作 + >1000 + 跨度 + 生日 → top3 = devoted(95)/remembering(90)/indulgent(80)
        val r = listOf(
            rec("gift_boba_tea", 0), rec("gift_rose_single", 0), rec("gift_hairclip", 0),
            rec("gift_candle", 0), rec("gift_ktv", 0),        // 5 品类
            rec("gift_love_letter", 0),                       // 手作
            rec("gift_skincare_set", 0),                      // >1000
            rec("gift_boba_tea", 35),                         // 跨度
        )
        val tags = GiftImpressionTagService.selectTags(r, emptyList(), birthdayMillis(6, 15), limit = 3, now = now)
        assertEquals(3, tags.size)
        assertEquals(
            listOf(GiftImpressionTag.DEVOTED, GiftImpressionTag.REMEMBERING, GiftImpressionTag.INDULGENT),
            tags,
        )
    }
}
