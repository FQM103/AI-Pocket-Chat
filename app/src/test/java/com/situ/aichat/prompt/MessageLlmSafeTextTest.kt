package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.GiftCardData
import com.situ.aichat.data.model.GiftCardJson
import com.situ.aichat.data.model.GiftSender
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.model.RedPacketData
import com.situ.aichat.data.model.RedPacketEventSenderRole
import com.situ.aichat.data.model.RedPacketJson
import com.situ.aichat.data.model.SystemEventJson
import com.situ.aichat.data.model.SystemEventType
import com.situ.aichat.data.model.makeRedPacketSystemEventData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守 [messageLlmSafeText] 的单一事实源契约（money-path / 隐私）：把消息渲染成喂 LLM 的安全文本时，
 * 结构化价值卡（礼物 / 红包 / 通话）**绝不**把原始 JSON、amount/cost 数字外露；无脱敏表示的卡整条丢弃（返回 null）。
 * 这是日记/日程/记忆/通知/故事各旁路共用的收口；新增 [MessageKind] 时穷举 when 会编译期强制在此裁定其 LLM 策略。
 */
class MessageLlmSafeTextTest {

    private fun msg(content: String, kind: MessageKind) = MessageEntity(
        messageUUID = "m", conversationUuid = "c", roleRaw = "user", content = content,
        timestamp = 1L, messageKindRaw = kind.raw,
    )

    @Test fun `plain text and schedule and system hint pass through unchanged`() {
        assertEquals("今天天气很好", messageLlmSafeText(msg("今天天气很好", MessageKind.PLAIN_TEXT)))
        assertEquals("[#E1] 明天3点开会", messageLlmSafeText(msg("[#E1] 明天3点开会", MessageKind.SCHEDULE_CARD)))
        assertEquals("（用户取消了见面）", messageLlmSafeText(msg("（用户取消了见面）", MessageKind.SYSTEM_HINT)))
    }

    @Test fun `gift card never exposes raw cost or JSON`() {
        val giftJson = GiftCardJson.encode(
            GiftCardData(
                type = "gift_card", giftItemId = "g1", giftRecordId = "rec-gift-1",
                cost = 888, giftName = "钻石项链", isHandmade = false, senderType = GiftSender.USER,
            ),
        )
        val out = messageLlmSafeText(msg(giftJson, MessageKind.GIFT_CARD))!!
        assertFalse("绝不露金币数字", out.contains("888"))
        assertFalse("绝不露原始 JSON", out.contains("{"))
        assertFalse("绝不露字段名", out.contains("giftRecordId"))
        assertFalse("绝不露 cost 键名", out.contains("cost"))
        assertTrue("保留脱敏礼物名", out.contains("钻石项链"))
        assertTrue("心意分档替代金额", out.contains("分量="))
    }

    @Test fun `red packet never exposes amount or JSON`() {
        val rpJson = RedPacketJson.encode(
            RedPacketData(type = "red_packet", recordUUID = "rec-rp-1", amount = 520, blessingText = "新年快乐"),
        )
        val out = messageLlmSafeText(msg(rpJson, MessageKind.RED_PACKET))!!
        assertFalse("永不露红包金额", out.contains("520"))
        assertFalse("绝不露原始 JSON", out.contains("{"))
        assertFalse("绝不露字段名", out.contains("recordUUID"))
        assertFalse("绝不露 amount 键名", out.contains("amount"))
        assertTrue("祝福语保留", out.contains("新年快乐"))
    }

    @Test fun `cards without safe representation return null`() {
        assertNull(messageLlmSafeText(msg("""{"type":"call_record","summary":"foo"}""", MessageKind.CALL_RECORD_CARD)))
        assertNull(messageLlmSafeText(msg("{}", MessageKind.SYSTEM_EVENT_CARD)))
        assertNull(messageLlmSafeText(msg("{}", MessageKind.OFFLINE_INVITE_CARD)))
        assertNull(messageLlmSafeText(msg("{}", MessageKind.OFFLINE_END_CARD)))
        assertNull(messageLlmSafeText(msg("{}", MessageKind.OFFLINE_MARKER_START)))
        assertNull(messageLlmSafeText(msg("{}", MessageKind.OFFLINE_MARKER_END)))
    }

    @Test fun `malformed structured card is dropped not leaked raw`() {
        // GIFT_CARD/RED_PACKET 但内容非合法 JSON：解析失败 → null → 调用方跳过，绝不把原文当普通文本喂出去。
        assertNull(messageLlmSafeText(msg("""{"type":"not_a_gift","cost":999}""".let { "乱码$it" }, MessageKind.GIFT_CARD)))
        assertNull(messageLlmSafeText(msg("not json", MessageKind.RED_PACKET)))
    }

    @Test fun `resolved red packet event exposes amount - status driven post-open`() {
        // 状态驱动：红包「信封」卡永不带金额，但**已领取的系统事件** resolved 后带精确金额（与正常聊天一致）→ 日记/摘要可见。
        val eventJson = SystemEventJson.encode(
            makeRedPacketSystemEventData(
                eventType = SystemEventType.RED_PACKET_ACCEPTED, amount = 520,
                blessingText = "新年快乐", rejectionReason = null,
                senderRole = RedPacketEventSenderRole.USER, characterName = "小夏", timestampMillis = 1_700_000_000_000L,
            ),
        )
        val out = messageLlmSafeText(msg(eventJson, MessageKind.SYSTEM_EVENT_CARD))!!
        assertTrue("已领取的红包→带精确金额(已拆可暴露)", out.contains("金额=520"))
        assertTrue("祝福语保留", out.contains("新年快乐"))
        assertFalse("不露原始 JSON", out.contains("{"))
    }
}
