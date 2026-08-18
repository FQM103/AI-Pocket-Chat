package com.situ.aichat.ui.chat

import com.situ.aichat.data.model.MessageKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fable-5 连续卡分组纯函数单测（契约 §3.2 规格独立反推，不照搬实现）：分组 ⇔ 同发送者 + 无时间断层 +
 * 两侧都是文本族（PLAIN_TEXT）；卡片类是独立岛、打断相邻文本段。时间断层 ⇔ 无前驱或间隔 >60s（边界 60s 不断）。
 * （V8·2026-07-08：分隔行退役——断层只打断分组不再出行，判定函数改名 isChatTimeBreak、口径不变。
 * 间距节奏 chatTopPadding + 打字占位 buildChatRenderItems 已移至 ChatRenderModelTest。）
 */
class ChatBubbleGroupingTest {

    private val plain = MessageKind.PLAIN_TEXT.raw

    private fun groups(
        earlierRole: String? = "assistant",
        earlierKind: String? = plain,
        laterRole: String? = "assistant",
        laterKind: String? = plain,
        timeBreak: Boolean = false,
    ) = bubbleGroupsWith(earlierRole, earlierKind, laterRole, laterKind, timeBreak)

    @Test fun sameSender_plainText_noDivider_groups() {
        assertTrue(groups())
        assertTrue(groups(earlierRole = "user", laterRole = "user"))
    }

    @Test fun timeBreak_breaksGroup() {
        assertFalse(groups(timeBreak = true))
    }

    @Test fun senderChange_breaksGroup() {
        assertFalse(groups(earlierRole = "user", laterRole = "assistant"))
    }

    @Test fun nullNeighbor_neverGroups() {
        assertFalse(groups(earlierRole = null))
        assertFalse(groups(laterRole = null))
    }

    @Test fun cardKinds_areIslands() {
        // 卡片在前：打断后续文本段（保住卡片头像归属语义）。
        assertFalse(groups(earlierKind = MessageKind.GIFT_CARD.raw))
        assertFalse(groups(earlierKind = MessageKind.RED_PACKET.raw))
        assertFalse(groups(earlierKind = MessageKind.SCHEDULE_CARD.raw))
        // 卡片在后：前面的文本段到此收尾。
        assertFalse(groups(laterKind = MessageKind.OFFLINE_INVITE_CARD.raw))
        assertFalse(groups(laterKind = MessageKind.OFFLINE_END_CARD.raw))
    }

    @Test fun unknownKindRaw_fallsBackToPlainText_groups() {
        // MessageKind.fromRaw 未知值保守回退 PLAIN_TEXT → 仍可分组（与渲染端回退口径一致）。
        assertTrue(groups(earlierKind = "some_future_kind", laterKind = null))
    }

    @Test fun timeBreak_boundary() {
        val base = 1_700_000_000_000L
        assertTrue(isChatTimeBreak(null, base)) // 首条
        assertFalse(isChatTimeBreak(base, base + 60_000L)) // 恰 60s 不断（>60 才断）
        assertTrue(isChatTimeBreak(base, base + 60_001L))
    }
}
