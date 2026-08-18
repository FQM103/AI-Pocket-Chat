package com.situ.aichat.widget

import com.situ.aichat.data.local.entity.ConversationEntity

/**
 * 角色「此刻」状态小组件的纯逻辑（13.9a · C1，安卓超越 iOS——iOS 仅有宠物桌面小组件）。
 *
 * 小组件展示**主对话**角色当下在做什么：头像 + 名字 + 当天进行中日程的「活动 心情emoji」（现算，1:1 复用
 * [com.situ.aichat.ui.chat.ChatListScheduleStatus]）。这里只放「选哪个会话」的纯函数，便于单测反推聊天列表口径；
 * 取数 / 现算 / 渲染在 [CharacterStatusGlanceWidget]。
 */
object CharacterStatusWidgetData {
    /**
     * 选「主对话」会话：未归档、**有过消息**中，**置顶优先 → 最近活动**（1:1 聊天列表顶行口径
     * `isPinned DESC, lastMessageDate DESC`；与 [com.situ.aichat.shortcut.ConversationShortcutPublisher] 同源）。
     * 无任何有消息会话 → null（小组件显示「还没有聊天」占位）。
     *
     * 显式排序而非依赖输入顺序，使纯函数与 DAO 排序解耦、可独立单测。
     */
    fun pickConversation(active: List<ConversationEntity>): ConversationEntity? =
        active
            .filter { it.lastMessageDate != null }
            .maxWithOrNull(compareBy({ it.isPinned }, { it.lastMessageDate ?: it.creationDate }))
}

/**
 * 小组件渲染快照（轻量）。[statusLine] = 现算的「活动 心情emoji」，null 表示日程系统关或当下无进行中事件
 * （此时视图回退邀约文案）。头像位图另由 [com.situ.aichat.util.AvatarStore] 解码，不入此快照。
 */
data class CharacterStatusWidgetState(
    /** 主对话 uuid（点击 → 复用会话深链通道导航 `chat/{uuid}`，纯导航不物化）。 */
    val conversationUuid: String,
    val characterName: String,
    val avatarPath: String?,
    val statusLine: String?,
)
