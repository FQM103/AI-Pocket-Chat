package com.situ.aichat.widget

import com.situ.aichat.data.local.entity.ConversationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 角色「此刻」状态小组件「选主对话」纯逻辑单测（13.9a）。
 * 断言从聊天列表顶行口径反推：**有过消息**中，置顶优先 → 最近活动（`isPinned DESC, lastMessageDate DESC`）。
 */
class CharacterStatusWidgetDataTest {

    private fun conv(
        uuid: String,
        lastMessageDate: Long?,
        creationDate: Long = 0L,
        isPinned: Boolean = false,
    ) = ConversationEntity(
        uuid = uuid,
        title = uuid,
        characterUuid = "char-$uuid",
        creationDate = creationDate,
        isPinned = isPinned,
        lastMessageDate = lastMessageDate,
    )

    @Test
    fun `empty list yields null`() {
        assertNull(CharacterStatusWidgetData.pickConversation(emptyList()))
    }

    @Test
    fun `conversations without any message are excluded`() {
        // 全部从未发过消息（lastMessageDate == null）→ 无主对话（1:1 聊天列表「有消息」过滤）。
        val convs = listOf(conv("a", lastMessageDate = null), conv("b", lastMessageDate = null))
        assertNull(CharacterStatusWidgetData.pickConversation(convs))
    }

    @Test
    fun `picks most recent by lastMessageDate among non-pinned`() {
        val convs = listOf(
            conv("old", lastMessageDate = 100L),
            conv("new", lastMessageDate = 300L),
            conv("mid", lastMessageDate = 200L),
        )
        assertEquals("new", CharacterStatusWidgetData.pickConversation(convs)?.uuid)
    }

    @Test
    fun `pinned wins over a more recent non-pinned`() {
        val convs = listOf(
            conv("recentUnpinned", lastMessageDate = 999L, isPinned = false),
            conv("pinned", lastMessageDate = 100L, isPinned = true),
        )
        assertEquals("pinned", CharacterStatusWidgetData.pickConversation(convs)?.uuid)
    }

    @Test
    fun `among multiple pinned the most recent pinned wins`() {
        val convs = listOf(
            conv("pinnedOld", lastMessageDate = 100L, isPinned = true),
            conv("pinnedNew", lastMessageDate = 500L, isPinned = true),
            conv("unpinnedNewest", lastMessageDate = 900L, isPinned = false),
        )
        assertEquals("pinnedNew", CharacterStatusWidgetData.pickConversation(convs)?.uuid)
    }

    @Test
    fun `result is independent of input order`() {
        val a = conv("a", lastMessageDate = 100L)
        val b = conv("b", lastMessageDate = 300L)
        val c = conv("c", lastMessageDate = 200L, isPinned = true)
        // c 置顶 → 应胜出，无论顺序。
        assertEquals("c", CharacterStatusWidgetData.pickConversation(listOf(a, b, c))?.uuid)
        assertEquals("c", CharacterStatusWidgetData.pickConversation(listOf(c, b, a))?.uuid)
        assertEquals("c", CharacterStatusWidgetData.pickConversation(listOf(b, a, c))?.uuid)
    }

    @Test
    fun `a conversation with a message is preferred over one without`() {
        val convs = listOf(
            conv("noMsg", lastMessageDate = null, creationDate = 9999L),
            conv("hasMsg", lastMessageDate = 1L),
        )
        assertEquals("hasMsg", CharacterStatusWidgetData.pickConversation(convs)?.uuid)
    }
}
