package com.situ.aichat.notification

/**
 * 13.8·B1「通知直接回复」的纯函数辅助：从会话最近可见消息里取「这一轮 AI 回复的所有段」，供加急 worker 把回复
 * 回推到通知栏的 MessagingStyle 气泡。无 Android 依赖 → 单测可断言（断言反推：一轮回复可能分多段）。
 */
object NotificationReplyThread {

    /** 通知 MessagingStyle 气泡里最多展示的近期消息条数（通知栏可见行数有限，多了被系统折叠）。 */
    const val MAX_THREAD_MESSAGES = 6

    /** 通知 MessagingStyle 气泡里一条消息（[Notifier.postChatReply] 用）：[isUser] 决定挂「我」还是角色 Person。 */
    data class ReplyThreadMessage(val text: String, val isUser: Boolean, val timestamp: Long)

    /** 角色身份（assistant）在 [com.situ.aichat.data.local.entity.MessageEntity.roleRaw] 里的取值。 */
    private const val ROLE_ASSISTANT = "assistant"

    /**
     * 末尾连续 assistant 段 = 最近一轮 AI 回复（[RecoveryReplyGenerator] 把长回复切多段、各为一条 assistant 消息）。
     * @param roleToContent 会话最近可见消息（roleRaw to content），**时间正序**（旧→新）。
     * @return 这一轮 AI 回复的各段（旧→新）；末条非 assistant（如刚落的 user 回复尚未生成）→ 空。
     */
    fun trailingAssistantSegments(roleToContent: List<Pair<String, String>>): List<String> {
        val segments = ArrayDeque<String>()
        for ((role, content) in roleToContent.asReversed()) {
            if (role == ROLE_ASSISTANT) segments.addFirst(content) else break
        }
        return segments.toList()
    }
}
