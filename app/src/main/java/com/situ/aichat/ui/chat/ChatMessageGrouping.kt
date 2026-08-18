package com.situ.aichat.ui.chat

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.MessageKind

/**
 * 聊天列表「间距节奏」常量（契约 FABLE5_CHAT_BUBBLE_REFACTOR_PROPOSAL B3 · 照 iOS `ChatView.BottomLayout`）：
 * 连续用户消息贴紧、连续 AI 消息留呼吸、换发送者留呼吸。
 * **2026-07-08 拍板（契约 FABLE5_CHAT_REVERSE_LIST_PROPOSAL V8）**：列表内时间分隔行整体移除——每条气泡
 * 已内嵌时间戳、滚动浮动日期胶囊承担翻史日期定位，居中时间行不再渲染；>60s 时间断层仅保留「打断连发分组」
 * 语义（断层处按换发送者留呼吸）。原分隔行三常量（上 20/下 10/首条 4）随之退役。
 */
object ChatSpacing {
    /** 连续【用户】消息（同段连发 · 贴紧 · = iOS groupedUserSpacing 3）。 */
    val groupedUser = 3.dp

    /** 连续【AI】消息（留呼吸 · = iOS groupedAssistantSpacing 12，与换发送者同值）。 */
    val groupedAssistant = 12.dp

    /** 换发送者（= iOS senderChangeSpacing 12）。 */
    val senderChange = 12.dp
}

/**
 * 一条消息的上间距：同段连发按角色（用户 3 · AI 12）/ 换发送者或时间断层 = 12。
 * （分隔行退役后无「分隔后 10」档——断层即不成组，走换发送者值。）纯函数 · 规格独立反推单测。
 */
internal fun chatTopPadding(roleRaw: String?, groupedWithPrev: Boolean): Dp = when {
    groupedWithPrev -> if (roleRaw == "user") ChatSpacing.groupedUser else ChatSpacing.groupedAssistant
    else -> ChatSpacing.senderChange
}

/**
 * 时间断层判定：相邻消息间隔 > 60 秒（或首条）。原为「显示时间分隔行」口径（1:1 iOS `> 60`），
 * 分隔行退役后仅用于**打断连发分组**（跨断层的同角色消息不合并成段）。
 */
internal fun isChatTimeBreak(prevTimestamp: Long?, current: Long): Boolean {
    if (prevTimestamp == null) return true
    return current - prevTimestamp > 60 * 1000L
}

/**
 * Fable-5 连续卡分组判定（契约 §3.2/§3.4）：相邻两条消息是否合并为同一连发段——同发送者、无时间断层、
 * 且两者都是文本族气泡（[MessageKind.PLAIN_TEXT]·含语音/贴纸/脏消息变体）。卡片类（礼物/红包/邀约/
 * 日程/系统事件）是独立岛：自身恒为段首+段尾，也打断相邻文本段（保住卡片的头像归属语义·P1-1）。
 */
internal fun bubbleGroupsWith(
    earlierRole: String?,
    earlierKindRaw: String?,
    laterRole: String?,
    laterKindRaw: String?,
    separatedByTimeBreak: Boolean,
): Boolean {
    if (earlierRole == null || laterRole == null || separatedByTimeBreak) return false
    if (earlierRole != laterRole) return false
    return MessageKind.fromRaw(earlierKindRaw.orEmpty()) == MessageKind.PLAIN_TEXT &&
        MessageKind.fromRaw(laterKindRaw.orEmpty()) == MessageKind.PLAIN_TEXT
}

/**
 * 「正在输入」占位槽（契约 B1）：ViewModel 在打字点亮起前**提前分配**下一段 AI 消息的 UUID 并暴露此槽；
 * 渲染层据此合成一个 key=该 uuid 的占位气泡，段落库后同 key 被真实消息接管 → 同一列表项原地变身（不删不插）。
 */
internal data class TypingSlot(val uuid: String)

/** 聊天列表渲染项（契约 §1 B1；2026-07-08 V8 起只余消息一种——时间分隔行已退役）。 */
internal sealed interface ChatRenderItem {
    val key: String

    /**
     * 一条消息气泡。[entity] 为真实落库消息（`isContentRevealed=true`）或合成的打字占位
     * （`isContentRevealed=false` · content 空 · 见 [buildChatRenderItems]）；气泡在 `!isContentRevealed`
     * 时显打字三点、到内容时原地交叉淡入成正文（仿 iOS AssistantTransitionContent）。[topPadding] 为预算好的上间距。
     */
    data class Message(val entity: MessageEntity, val topPadding: Dp) : ChatRenderItem {
        override val key: String get() = entity.messageUUID
    }
}

/**
 * 由 Room 消息流 + 打字占位槽合成渲染列表（契约 B1 承重点 · 纯函数 · 全单测）：
 * - 逐条算时间断层（>60s / 首条·只打断分组不出行）与上间距（[chatTopPadding]）；
 * - 若 [typingSlot] 活跃且其 uuid 尚未落库 → 末尾追加一个 key=该 uuid 的占位消息（`isContentRevealed=false`）；
 *   段一旦落库（uuid 进入 [messages]），dedup 丢弃占位、真实消息以**同 key** 接管 → 列表项原地变身。
 *
 * @param nowMs 当前时间（占位项时间戳 + 与末条比对时间断层）；由调用方传入，便于测试且避免每帧读时钟。
 */
internal fun buildChatRenderItems(
    messages: List<MessageEntity>,
    typingSlot: TypingSlot?,
    nowMs: Long,
): List<ChatRenderItem> {
    val items = ArrayList<ChatRenderItem>(messages.size + 1)
    var prev: MessageEntity? = null
    messages.forEach { msg ->
        val timeBreak = isChatTimeBreak(prev?.timestamp, msg.timestamp)
        val grouped = bubbleGroupsWith(prev?.roleRaw, prev?.messageKindRaw, msg.roleRaw, msg.messageKindRaw, timeBreak)
        items += ChatRenderItem.Message(msg, chatTopPadding(msg.roleRaw, grouped))
        prev = msg
    }
    if (typingSlot != null && messages.none { it.messageUUID == typingSlot.uuid }) {
        val last = messages.lastOrNull()
        val timeBreak = last == null || isChatTimeBreak(last.timestamp, nowMs)
        val grouped = last != null &&
            bubbleGroupsWith(last.roleRaw, last.messageKindRaw, "assistant", MessageKind.PLAIN_TEXT.raw, timeBreak)
        // 渲染专用占位：永不落库，conversationUuid 留空；气泡据 isContentRevealed=false 显三点（不读其他字段）。
        val placeholder = MessageEntity(
            messageUUID = typingSlot.uuid,
            conversationUuid = "",
            roleRaw = "assistant",
            content = "",
            timestamp = nowMs,
            isContentRevealed = false,
        )
        items += ChatRenderItem.Message(placeholder, chatTopPadding("assistant", grouped))
    }
    return items
}
