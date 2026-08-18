package com.situ.aichat.notification

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 13.8·B1 通知直接回复纯函数测试：从会话最近可见消息里取「这一轮 AI 回复的各段」（末尾连续 assistant 段）。
 * 断言反推 [RecoveryReplyGenerator] 的行为：一轮回复可能切多段（各为一条 assistant 消息），用户刚发的 user 消息
 * 是 assistant 段之前的边界。
 */
class NotificationReplyThreadTest {

    private fun u(text: String) = "user" to text
    private fun a(text: String) = "assistant" to text

    @Test
    fun `单段回复 取末尾一条 assistant`() {
        val msgs = listOf(a("你好呀"), u("在干嘛"), a("在写代码呢"))
        assertEquals(listOf("在写代码呢"), NotificationReplyThread.trailingAssistantSegments(msgs))
    }

    @Test
    fun `多段回复 取末尾连续 assistant 全部段 且保持旧到新顺序`() {
        val msgs = listOf(u("在干嘛"), a("在写代码"), a("有点累"), a("你呢"))
        assertEquals(listOf("在写代码", "有点累", "你呢"), NotificationReplyThread.trailingAssistantSegments(msgs))
    }

    @Test
    fun `末条是 user（回复尚未生成）返回空`() {
        val msgs = listOf(a("早安"), u("早"))
        assertEquals(emptyList<String>(), NotificationReplyThread.trailingAssistantSegments(msgs))
    }

    @Test
    fun `只到上一条 user 为止 不把更早一轮的 assistant 也算进来`() {
        val msgs = listOf(a("第一轮回复"), u("第二句"), a("第二轮回复"))
        assertEquals(listOf("第二轮回复"), NotificationReplyThread.trailingAssistantSegments(msgs))
    }

    @Test
    fun `空列表返回空`() {
        assertEquals(emptyList<String>(), NotificationReplyThread.trailingAssistantSegments(emptyList()))
    }

    @Test
    fun `全是 assistant（无 user 边界）全取`() {
        val msgs = listOf(a("一"), a("二"))
        assertEquals(listOf("一", "二"), NotificationReplyThread.trailingAssistantSegments(msgs))
    }
}
