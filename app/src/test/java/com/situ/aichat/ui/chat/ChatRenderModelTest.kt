package com.situ.aichat.ui.chat

import androidx.compose.ui.unit.dp
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.MessageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 渲染模型 + iOS 间距节奏纯函数单测（契约 FABLE5_CHAT_BUBBLE_REFACTOR_PROPOSAL B1/B3 规格独立反推；
 * 2026-07-08 V8 修订=FABLE5_CHAT_REVERSE_LIST_PROPOSAL §9：时间分隔行退役——列表只产出消息项，
 * >60s 时间断层仅打断连发分组（断层处上间距=换发送者 12）。）：
 * 上间距（连用户 3 / 连 AI 12 / 换人·断层 12）、打字占位槽合成与落库 dedup。
 */
class ChatRenderModelTest {

    private val t0 = 1_700_000_000_000L

    private fun msg(
        uuid: String,
        role: String,
        ts: Long,
        kind: String = MessageKind.PLAIN_TEXT.raw,
        content: String = "x",
        revealed: Boolean = true,
    ) = MessageEntity(
        messageUUID = uuid,
        conversationUuid = "c",
        roleRaw = role,
        content = content,
        timestamp = ts,
        messageKindRaw = kind,
        isContentRevealed = revealed,
    )

    // ── chatTopPadding：iOS 值 ──

    @Test fun topPadding_iosRhythm() {
        assertEquals(3.dp, chatTopPadding("user", groupedWithPrev = true))
        assertEquals(12.dp, chatTopPadding("assistant", groupedWithPrev = true))
        assertEquals(12.dp, chatTopPadding("user", groupedWithPrev = false))
        assertEquals(12.dp, chatTopPadding("assistant", groupedWithPrev = false))
    }

    // ── buildChatRenderItems：基础 ──

    @Test fun empty_noTyping_isEmpty() {
        assertTrue(buildChatRenderItems(emptyList(), null, t0).isEmpty())
    }

    @Test fun firstMessage_noDividerItem_senderChangePadding() {
        // V8：分隔行退役——首条前不再产出分隔项，列表首项即消息本体。
        val items = buildChatRenderItems(listOf(msg("a", "user", t0)), null, t0)
        assertEquals(1, items.size)
        val m = items[0] as ChatRenderItem.Message
        assertEquals("a", m.entity.messageUUID)
        assertEquals(12.dp, m.topPadding)
    }

    @Test fun consecutiveUsers_tight_consecutiveAssistants_breathe() {
        val items = buildChatRenderItems(
            listOf(
                msg("u1", "user", t0),
                msg("u2", "user", t0 + 1_000), // 连用户 → 3
                msg("a1", "assistant", t0 + 2_000), // 换人 → 12
                msg("a2", "assistant", t0 + 3_000), // 连 AI → 12
            ),
            null,
            t0 + 3_000,
        )
        val msgs = items.filterIsInstance<ChatRenderItem.Message>()
        assertEquals(3.dp, msgs[1].topPadding)
        assertEquals(12.dp, msgs[2].topPadding)
        assertEquals(12.dp, msgs[3].topPadding)
    }

    @Test fun gapOver60s_noDividerItem_breaksGrouping() {
        // V8：>60s 断层不再产出分隔项，但仍打断连发分组——同角色隔断层 → 上间距 12（非贴紧 3）。
        val items = buildChatRenderItems(
            listOf(msg("a", "user", t0), msg("b", "user", t0 + 61_000)),
            null,
            t0 + 61_000,
        )
        assertEquals(2, items.size) // 只有两条消息，无分隔项
        val second = items[1] as ChatRenderItem.Message
        assertEquals(12.dp, second.topPadding)
    }

    @Test fun gapUnder60s_sameRole_staysGrouped() {
        // 断层判定边界：≤60s 不打断——连用户仍贴紧 3。
        val items = buildChatRenderItems(
            listOf(msg("a", "user", t0), msg("b", "user", t0 + 60_000)),
            null,
            t0 + 60_000,
        )
        assertEquals(3.dp, (items[1] as ChatRenderItem.Message).topPadding)
    }

    @Test fun cardBreaksGrouping_islandSpacing() {
        // 礼物卡是独立岛：其后的 AI 文本不与卡分组 → 换发送者间距 12（而非连 AI）。
        val items = buildChatRenderItems(
            listOf(
                msg("g", "assistant", t0, kind = MessageKind.GIFT_CARD.raw),
                msg("a", "assistant", t0 + 1_000),
            ),
            null,
            t0 + 1_000,
        )
        val msgs = items.filterIsInstance<ChatRenderItem.Message>()
        assertEquals(12.dp, msgs[1].topPadding)
    }

    // ── 打字占位槽 ──

    @Test fun typingSlot_appendsUnrevealedPlaceholder_continuationSpacing() {
        val items = buildChatRenderItems(
            listOf(msg("u", "user", t0), msg("a1", "assistant", t0 + 1_000)),
            TypingSlot("a2"),
            t0 + 1_500,
        )
        val last = items.last() as ChatRenderItem.Message
        assertEquals("a2", last.entity.messageUUID)
        assertFalse(last.entity.isContentRevealed) // 占位=未显形=显三点
        assertEquals("", last.entity.content)
        assertEquals(12.dp, last.topPadding) // 连 AI 留呼吸
    }

    @Test fun typingSlot_dedupOnceLanded() {
        // 段已落库（uuid 进 messages）→ 不再合成占位（真实消息以同 key 接管）。
        val items = buildChatRenderItems(listOf(msg("a1", "assistant", t0)), TypingSlot("a1"), t0 + 100)
        val msgs = items.filterIsInstance<ChatRenderItem.Message>()
        assertEquals(1, msgs.size)
        assertTrue(msgs[0].entity.isContentRevealed) // 真实消息
    }

    @Test fun typingSlot_afterUser_isSenderChange() {
        val items = buildChatRenderItems(listOf(msg("u", "user", t0)), TypingSlot("a1"), t0 + 500)
        val last = items.last() as ChatRenderItem.Message
        assertEquals(12.dp, last.topPadding) // AI 首句换发送者
    }

    @Test fun typingSlot_emptyConversation_singleItem_senderChange() {
        val items = buildChatRenderItems(emptyList(), TypingSlot("a1"), t0)
        assertEquals(1, items.size)
        val m = items[0] as ChatRenderItem.Message
        assertEquals("a1", m.entity.messageUUID)
        assertEquals(12.dp, m.topPadding)
    }

    @Test fun typingSlot_after60sIdle_noDividerItem_breaksGrouping() {
        // V8：AI 久后主动开口（距末条 >60s）——无分隔项，占位与末条断层不分组 → 换发送者 12。
        val items = buildChatRenderItems(
            listOf(msg("a1", "assistant", t0)),
            TypingSlot("a2"),
            t0 + 61_000,
        )
        assertEquals(2, items.size)
        val last = items.last() as ChatRenderItem.Message
        assertEquals("a2", last.entity.messageUUID)
        assertEquals(12.dp, last.topPadding)
    }
}
