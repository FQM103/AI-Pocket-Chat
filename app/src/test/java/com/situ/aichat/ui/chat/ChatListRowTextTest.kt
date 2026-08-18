package com.situ.aichat.ui.chat

import com.situ.aichat.data.local.entity.ConversationEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [chatListPreviewText] 单测——断言反推 iOS `ChatListRowView.previewText`：
 * 用户消息加「你: 」前缀、AI 不加；空预览按有无 lastMessageDate 回退「暂无消息」/「消息内容暂不可用」。
 */
class ChatListRowTextTest {

    private val you = "你: "
    private val none = "暂无消息"
    private val unavailable = "消息内容暂不可用"

    private fun conv(
        preview: String = "",
        role: String = "",
        lastMessageDate: Long? = 1_000L,
    ) = ConversationEntity(
        uuid = "c1",
        title = "标题",
        characterUuid = "ch1",
        creationDate = 0L,
        lastMessagePreview = preview,
        lastMessageRole = role,
        lastMessageDate = lastMessageDate,
    )

    private fun text(c: ConversationEntity) = chatListPreviewText(c, you, none, unavailable)

    @Test fun userMessage_getsYouPrefix() {
        assertEquals("你: 在吗", text(conv(preview = "在吗", role = "user")))
    }

    @Test fun assistantMessage_noPrefix() {
        assertEquals("在的", text(conv(preview = "在的", role = "assistant")))
    }

    @Test fun unknownRole_noPrefix() {
        assertEquals("系统提示", text(conv(preview = "系统提示", role = "system")))
    }

    @Test fun emptyPreview_noDate_showsNoMessage() {
        assertEquals(none, text(conv(preview = "", lastMessageDate = null)))
    }

    @Test fun emptyPreview_withDate_showsUnavailable() {
        assertEquals(unavailable, text(conv(preview = "", lastMessageDate = 5_000L)))
    }
}
