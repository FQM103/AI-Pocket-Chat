package com.situ.aichat.ui.offline

import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.GiftCardData
import com.situ.aichat.data.model.GiftCardJson
import com.situ.aichat.data.model.GiftSender
import com.situ.aichat.data.model.MessageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守见面记忆摘要「对话正文」装配 [buildMeetingConversationText]（money-path / 隐私）：见面期主动送礼卡现随会话打
 * 线下标记会落进 offlineSession，喂见面记忆摘要 LLM（且生成结果用户可见可编辑）时必须脱敏——绝不露礼物 cost /
 * 红包 amount / 原始 JSON。结构化卡走单一事实源 messageLlmSafeText；入场/离场/结束卡丢弃；见面叙事原文保留。
 */
class OfflineMeetingConversationTextTest {

    private fun msg(content: String, kind: MessageKind, role: String, ts: Long): MessageEntity =
        MessageEntity(
            messageUUID = "m$ts", conversationUuid = "c", roleRaw = role, content = content,
            timestamp = ts, messageKindRaw = kind.raw,
        )

    @Test fun `见面期礼物卡脱敏·绝不露金币或原始 JSON`() {
        val gift = msg(
            GiftCardJson.encode(
                GiftCardData(
                    type = "gift_card", giftItemId = "g1", giftRecordId = "rec1",
                    cost = 888, giftName = "钻石项链", isHandmade = false, senderType = GiftSender.CHARACTER,
                ),
            ),
            MessageKind.GIFT_CARD, role = "assistant", ts = 2L,
        )
        val plain = msg("[动作] 她牵起你的手", MessageKind.PLAIN_TEXT, role = "assistant", ts = 1L)
        val out = buildMeetingConversationText(listOf(gift, plain), "小夏", "阿泽")
        assertFalse("不露金币数字", out.contains("888"))
        assertFalse("不露原始 JSON", out.contains("{"))
        assertFalse("不露字段名", out.contains("giftRecordId"))
        assertTrue("脱敏礼物名保留", out.contains("钻石项链"))
        assertTrue("见面叙事正文保留(带角色前缀)", out.contains("小夏：[动作] 她牵起你的手"))
    }

    @Test fun `入场离场标记与结束卡整条丢弃`() {
        val start = msg("{ignored}", MessageKind.OFFLINE_MARKER_START, "assistant", 1L)
        val end = msg("{ignored}", MessageKind.OFFLINE_MARKER_END, "assistant", 4L)
        val endCard = msg("{}", MessageKind.OFFLINE_END_CARD, "assistant", 3L)
        val plain = msg("今天好开心", MessageKind.PLAIN_TEXT, "user", 2L)
        // 标记/结束卡 → messageLlmSafeText null → 丢弃；只留可读叙事（用户侧标签名字化）。
        assertEquals("阿泽：今天好开心", buildMeetingConversationText(listOf(start, plain, endCard, end), "小夏", "阿泽"))
    }

    @Test fun `空入参或全部丢弃返回空串`() {
        assertEquals("", buildMeetingConversationText(emptyList(), "小夏", "阿泽"))
        val onlyMarker = msg("{}", MessageKind.OFFLINE_MARKER_START, "assistant", 1L)
        assertEquals("", buildMeetingConversationText(listOf(onlyMarker), "小夏", "阿泽"))
    }

    // D-2 记录名字化回归锁（手动「重新生成」路·🟡-1 返工）：用户侧标签走名字化，绝不出现「用户：」。
    @Test fun `名字化·用户侧标签用昵称·角色用角色名·不出现用户标签`() {
        val userMsg = msg("今天好开心", MessageKind.PLAIN_TEXT, "user", 1L)
        val charMsg = msg("我也是", MessageKind.PLAIN_TEXT, "assistant", 2L)
        val out = buildMeetingConversationText(listOf(userMsg, charMsg), characterName = "夏晴子", userLabel = "阿泽")
        assertTrue("用户侧用昵称标签", out.contains("阿泽："))
        assertTrue("角色侧用角色名标签", out.contains("夏晴子："))
        assertFalse("绝不出现通用「用户：」标签（回归锁·钉死退步不复发）", out.contains("用户："))
    }

    @Test fun `无昵称档·用户侧标签回退对方·不出现用户标签`() {
        val userMsg = msg("今天好开心", MessageKind.PLAIN_TEXT, "user", 1L)
        val out = buildMeetingConversationText(listOf(userMsg), characterName = "夏晴子", userLabel = "对方")
        assertTrue("无昵称回退「对方：」标签", out.contains("对方："))
        assertFalse("绝不出现通用「用户：」标签（回归锁）", out.contains("用户："))
    }
}
