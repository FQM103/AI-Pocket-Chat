package com.situ.aichat.ui.chat

import com.situ.aichat.data.local.entity.ConversationEntity

/**
 * 列表行「最后消息预览」文本（纯函数，1:1 iOS `ChatListRowView.previewText`，便于单测）。
 * - 有预览：用户消息（role=="user"）加 [youPrefix]「你: 」前缀，AI 消息无前缀。
 * - 空预览：无 lastMessageDate → [noMessage]「暂无消息」；否则 → [unavailable]「消息内容暂不可用」。
 * 本地化串由调用方注入，保持本函数纯。
 */
internal fun chatListPreviewText(
    conv: ConversationEntity,
    youPrefix: String,
    noMessage: String,
    unavailable: String,
): String {
    if (conv.lastMessagePreview.isNotEmpty()) {
        val prefix = if (conv.lastMessageRole == "user") youPrefix else ""
        return prefix + conv.lastMessagePreview
    }
    return if (conv.lastMessageDate == null) noMessage else unavailable
}
