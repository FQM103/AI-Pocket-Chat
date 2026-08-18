package com.situ.aichat.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 红包数据层纯函数单测（P9.3a-1）。断言**反推 iOS 真实阈值/字面**：
 * - `RedPacketAmountCatalog`：范围 [1,20000] 边界、tier 三档分界（50/51/200/201）、吉利数三组拼接顺序、isAuspicious。
 * - `RedPacketStatus`：isReturned（rejected||expired）、isTerminal（!=pending）、fromRaw 未知回退 pending。
 * - `RedPacketData`：llmRepresentation **永不露 amount**、节日/祝福段拼接、80 字截断；JSON encodeDefaults=false（festivalId null 省略）。
 */
class RedPacketDataLayerTest {

    // ── RedPacketAmountCatalog 范围 ──

    @Test fun amount_range_boundaries() {
        assertEquals(1, RedPacketAmountCatalog.MIN_AMOUNT)
        assertEquals(20000, RedPacketAmountCatalog.MAX_AMOUNT)
        assertFalse(RedPacketAmountCatalog.isValidAmount(0))
        assertTrue(RedPacketAmountCatalog.isValidAmount(1))
        assertTrue(RedPacketAmountCatalog.isValidAmount(20000))
        assertFalse(RedPacketAmountCatalog.isValidAmount(20001))
        assertFalse(RedPacketAmountCatalog.isValidAmount(-5))
    }

    // ── tier 三档分界（与 GiftCardData.tier 对齐：<51 / 51..200 / >200） ──

    @Test fun tier_thresholds_match_ios() {
        assertEquals("小心意", RedPacketAmountCatalog.tier(1))
        assertEquals("小心意", RedPacketAmountCatalog.tier(50))
        assertEquals("用心的选择", RedPacketAmountCatalog.tier(51))
        assertEquals("用心的选择", RedPacketAmountCatalog.tier(200))
        assertEquals("珍贵的心意", RedPacketAmountCatalog.tier(201))
        assertEquals("珍贵的心意", RedPacketAmountCatalog.tier(20000))
    }

    // ── 吉利数三组（顺序 = 三组升序拼接，1:1 iOS auspiciousAmounts） ──

    @Test fun auspicious_amounts_groups_and_order() {
        assertEquals(listOf(8, 18, 28), RedPacketAmountCatalog.SMALL_AMOUNTS)
        assertEquals(listOf(66, 88, 168, 188), RedPacketAmountCatalog.MEDIUM_AMOUNTS)
        assertEquals(listOf(520, 666, 888, 1314), RedPacketAmountCatalog.PRECIOUS_AMOUNTS)
        assertEquals(
            listOf(8, 18, 28, 66, 88, 168, 188, 520, 666, 888, 1314),
            RedPacketAmountCatalog.auspiciousAmounts,
        )
        assertEquals(11, RedPacketAmountCatalog.auspiciousAmounts.size)
    }

    @Test fun is_auspicious_membership() {
        assertTrue(RedPacketAmountCatalog.isAuspicious(88))
        assertTrue(RedPacketAmountCatalog.isAuspicious(1314))
        assertFalse(RedPacketAmountCatalog.isAuspicious(100))
        assertFalse(RedPacketAmountCatalog.isAuspicious(0))
    }

    // ── RedPacketStatus 状态语义 ──

    @Test fun status_is_returned() {
        assertFalse(RedPacketStatus.PENDING.isReturned)
        assertFalse(RedPacketStatus.ACCEPTED.isReturned)
        assertTrue(RedPacketStatus.REJECTED.isReturned)
        assertTrue(RedPacketStatus.EXPIRED.isReturned)
    }

    @Test fun status_is_terminal() {
        assertFalse(RedPacketStatus.PENDING.isTerminal)
        assertTrue(RedPacketStatus.ACCEPTED.isTerminal)
        assertTrue(RedPacketStatus.REJECTED.isTerminal)
        assertTrue(RedPacketStatus.EXPIRED.isTerminal)
    }

    @Test fun status_from_raw_unknown_falls_back_pending() {
        assertEquals(RedPacketStatus.ACCEPTED, RedPacketStatus.fromRaw("accepted"))
        assertEquals(RedPacketStatus.EXPIRED, RedPacketStatus.fromRaw("expired"))
        assertEquals(RedPacketStatus.PENDING, RedPacketStatus.fromRaw("garbage"))
    }

    /**
     * 锁定 raw 字面（1:1 iOS rawValue），保护 RedPacketDao.pendingRecords 的 `WHERE status = 'pending'` SQL 字面
     * （money 复核 LOW#1）：重命名 raw 会断此测试，而非静默使扫描查不到 pending 红包。
     */
    @Test fun status_raw_values_locked() {
        assertEquals("pending", RedPacketStatus.PENDING.raw)
        assertEquals("accepted", RedPacketStatus.ACCEPTED.raw)
        assertEquals("rejected", RedPacketStatus.REJECTED.raw)
        assertEquals("expired", RedPacketStatus.EXPIRED.raw)
    }

    // ── RedPacketData.llmRepresentation：永不露 amount ──

    @Test fun llm_representation_never_exposes_amount() {
        val data = RedPacketData(type = "red_packet", recordUUID = "r1", amount = 8888, blessingText = "")
        val rep = data.llmRepresentation(festivalName = null)
        assertEquals("[系统记录：发出红包]", rep)
        assertFalse(rep.contains("8888"))
    }

    @Test fun llm_representation_with_festival_and_blessing() {
        val data = RedPacketData(type = "red_packet", recordUUID = "r1", amount = 520, blessingText = "  新年快乐  ", festivalId = "chinese_new_year")
        val rep = data.llmRepresentation(festivalName = "春节")
        assertEquals("[系统记录：发出红包 | 节日=春节 | 祝福=「新年快乐」]", rep)
    }

    @Test fun llm_representation_blessing_capped_80() {
        val long = "祝".repeat(90)
        val data = RedPacketData(type = "red_packet", recordUUID = "r1", amount = 1, blessingText = long)
        val rep = data.llmRepresentation(festivalName = null)
        // 80 字 + "…"
        assertTrue(rep.contains("祝福=「" + "祝".repeat(80) + "…」"))
    }

    @Test fun llm_representation_blank_festival_name_omitted() {
        val data = RedPacketData(type = "red_packet", recordUUID = "r1", amount = 1, blessingText = "")
        // 空字符串节日名视同无节日（日常红包）
        assertEquals("[系统记录：发出红包]", data.llmRepresentation(festivalName = ""))
    }

    // ── RedPacketJson 序列化：encodeDefaults=false 省略 null festivalId ──

    @Test fun json_omits_null_festival_id_round_trip() {
        val daily = RedPacketData(type = "red_packet", recordUUID = "r1", amount = 100, blessingText = "恭喜")
        val encoded = RedPacketJson.encode(daily)
        assertFalse("日常红包不应写 festivalId 字段", encoded.contains("festivalId"))
        assertEquals(daily, RedPacketJson.parse(encoded))
    }

    @Test fun json_keeps_festival_id_when_present() {
        val festive = RedPacketData(type = "red_packet", recordUUID = "r2", amount = 520, blessingText = "", festivalId = "valentines_day")
        val encoded = RedPacketJson.encode(festive)
        assertTrue(encoded.contains("valentines_day"))
        assertEquals(festive, RedPacketJson.parse(encoded))
    }

    @Test fun json_parse_rejects_non_red_packet() {
        assertNull(RedPacketJson.parse("plain text"))
        assertNull(RedPacketJson.parse("{\"type\":\"gift_card\",\"recordUUID\":\"x\",\"amount\":1,\"blessingText\":\"\"}"))
    }
}
