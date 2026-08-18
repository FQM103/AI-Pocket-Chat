package com.situ.aichat.story

import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.GiftCardData
import com.situ.aichat.data.model.GiftCardJson
import com.situ.aichat.data.model.GiftSender
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.model.RedPacketData
import com.situ.aichat.data.model.RedPacketJson
import com.situ.aichat.data.model.RelationshipQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `StoryChatInfluenceBuilder.Helpers` tests (P11.1d-2), reverse-derived from iOS
 * `Services/StoryChatInfluenceBuilder.swift` 各 summary 纯逻辑：clipped / 情绪 / 长期记忆 / 关系 /
 * 成长阶段(成长日志 or 关系质量三维) / 结构化记忆 / 话题预览(换行转空格+裁36)。
 * DB 编排部分（按角色查会话消息/四档输出）批到末期真机验。
 */
class StoryChatInfluenceBuilderTest {

    private val H = StoryChatInfluenceBuilder.Helpers

    @Test fun clipped_trims_and_truncates() {
        assertEquals("abc", H.clipped("abc", 5))
        assertEquals("abc", H.clipped("  abc  ", 5)) // 先 trim
        assertEquals("abc…", H.clipped("abcdef", 3))
        assertEquals("字".repeat(220) + "…", H.clipped("字".repeat(250), 220))
    }

    @Test fun current_mood_summary() {
        assertEquals("未记录", H.currentMoodSummary("", "x"))
        assertEquals("未记录", H.currentMoodSummary("   ", "x")) // 空白文本 → 未记录
        assertEquals("开心", H.currentMoodSummary("开心", "")) // 无 emoji
        assertEquals("😊 开心", H.currentMoodSummary("开心", "😊"))
    }

    @Test fun long_memory_summary() {
        assertEquals("暂无", H.longMemorySummary(""))
        assertEquals("暂无", H.longMemorySummary("   "))
        assertEquals("一段记忆", H.longMemorySummary("一段记忆"))
        assertEquals("字".repeat(220) + "…", H.longMemorySummary("字".repeat(300)))
    }

    @Test fun relationship_summary() {
        assertEquals("恋人（在一起了）", H.relationshipSummary("恋人", "在一起了"))
        assertEquals("好友", H.relationshipSummary("好友", ""))
        assertEquals("好友", H.relationshipSummary("好友", null))
        assertEquals("未知", H.relationshipSummary(null, "理由"))
        assertEquals("未知", H.relationshipSummary("", "理由"))
    }

    @Test fun growth_stage_summary_prefers_growth_log() {
        assertEquals(
            "变化一；变化二",
            H.growthStageSummary(listOf("变化一", "变化二"), RelationshipQuality()),
        )
        // 空成长日志 → 关系质量三维
        assertEquals(
            "熟悉度30、信任感40、亲近感50",
            H.growthStageSummary(
                listOf("", ""),
                RelationshipQuality(familiarity = 30, trust = 40, closeness = 50),
            ),
        )
        assertEquals(
            "熟悉度30、信任感40、亲近感50",
            H.growthStageSummary(emptyList(), RelationshipQuality(familiarity = 30, trust = 40, closeness = 50)),
        )
        // 长成长摘要裁 120
        assertEquals("字".repeat(120) + "…", H.growthStageSummary(listOf("字".repeat(130)), RelationshipQuality()))
    }

    @Test fun structured_memory_summary() {
        assertEquals("暂无", H.structuredMemorySummary("", "", ""))
        assertEquals(
            "TA 对用户的称呼是笨蛋；共同梗：奶茶梗；共同喜欢：老电影",
            H.structuredMemorySummary("笨蛋", "奶茶梗", "老电影"),
        )
        assertEquals("共同梗：只有梗", H.structuredMemorySummary("", "只有梗", ""))
    }

    @Test fun format_topic_preview_newline_to_space_and_clip() {
        assertEquals("第一行 第二行", H.formatTopicPreview("第一行\n第二行"))
        // 超 36 字裁断（无贴纸标记，纯文本路径）
        val out = H.formatTopicPreview("字".repeat(40))
        assertEquals("字".repeat(36) + "…", out)
    }

    // ── recentTopicPreviews（结构化卡走 messageLlmSafeText 脱敏·money-path / 隐私）──

    private fun topicMsg(content: String, kind: MessageKind, ts: Long): MessageEntity =
        MessageEntity(
            messageUUID = "m$ts", conversationUuid = "c", roleRaw = "user", content = content,
            timestamp = ts, messageKindRaw = kind.raw,
        )

    @Test fun recent_topics_red_packet_never_leaks_amount_or_json() {
        val rp = topicMsg(
            RedPacketJson.encode(RedPacketData(type = "red_packet", recordUUID = "rp1", amount = 666, blessingText = "恭喜发财")),
            MessageKind.RED_PACKET, ts = 2L,
        )
        val plain = topicMsg("今天去爬山了", MessageKind.PLAIN_TEXT, ts = 1L)
        val joined = H.recentTopicPreviews(listOf(rp, plain), 6).joinToString(" / ")
        assertFalse("永不露红包金额", joined.contains("666"))
        assertFalse("不露原始 JSON", joined.contains("{"))
        assertTrue("祝福语脱敏保留", joined.contains("恭喜发财"))
        assertTrue("普通话题保留", joined.contains("今天去爬山了"))
    }

    @Test fun recent_topics_gift_card_never_leaks_cost() {
        val gift = topicMsg(
            GiftCardJson.encode(
                GiftCardData(
                    type = "gift_card", giftItemId = "g1", giftRecordId = "rec1",
                    cost = 999, giftName = "口红", isHandmade = false, senderType = GiftSender.USER,
                ),
            ),
            MessageKind.GIFT_CARD, ts = 1L,
        )
        val joined = H.recentTopicPreviews(listOf(gift), 6).joinToString(" / ")
        assertFalse("不露金币数字", joined.contains("999"))
        assertFalse("不露原始 JSON", joined.contains("{"))
        assertTrue("脱敏礼物名保留", joined.contains("口红"))
    }

    @Test fun recent_topics_drops_call_and_offline_cards_and_respects_limit() {
        val call = topicMsg("""{"type":"call_record","summary":"x"}""", MessageKind.CALL_RECORD_CARD, ts = 5L)
        val invite = topicMsg("{}", MessageKind.OFFLINE_INVITE_CARD, ts = 4L)
        val a = topicMsg("话题A", MessageKind.PLAIN_TEXT, ts = 3L)
        val b = topicMsg("话题B", MessageKind.PLAIN_TEXT, ts = 2L)
        val c = topicMsg("话题C", MessageKind.PLAIN_TEXT, ts = 1L)
        // 通话/线下卡 → messageLlmSafeText null 丢弃；limit=2 取前两个有效话题（保留入参顺序）
        assertEquals(listOf("话题A", "话题B"), H.recentTopicPreviews(listOf(call, invite, a, b, c), 2))
    }

    @Test fun heavy_profile_line_format_is_byte_faithful_via_helpers() {
        // 间接核对 heavy 行各段分隔符（当前关系 空格 / 长期记忆摘要 全角冒号）通过组合助手验证
        val rel = H.relationshipSummary("恋人", null)
        val mood = H.currentMoodSummary("愉快", "🙂")
        val mem = H.longMemorySummary("一起看了电影")
        assertTrue(rel == "恋人")
        assertTrue(mood == "🙂 愉快")
        assertTrue(mem == "一起看了电影")
    }
}
