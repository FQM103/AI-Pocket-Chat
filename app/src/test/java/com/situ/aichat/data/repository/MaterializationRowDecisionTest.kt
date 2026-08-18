package com.situ.aichat.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 13.8·B1 复核 MED：通知物化的列表行写入**单调化**纯函数测试。复现 bug 场景：通知栏直接回复让 user@T1/AI@T2 先落库
 * （列表行 lastMessageDate=T2），之后回前台才把原主动消息按其较早 deliveredAt=T0 物化——不应让行「时间倒退」或加虚假未读。
 */
class MaterializationRowDecisionTest {

    @Test
    fun `首条消息（无现有 lastMessageDate）视为最新 覆写并加未读`() {
        val d = materializationRowDecision(currentLastMessageDate = null, timestamp = 100L, markReadNow = false)
        assertEquals(MaterializationRowDecision(overwriteColumns = true, bumpUnread = true, markRead = false), d)
    }

    @Test
    fun `正常前台物化（主动消息即最新活动）覆写并加未读（=原行为）`() {
        val d = materializationRowDecision(currentLastMessageDate = 100L, timestamp = 200L, markReadNow = false)
        assertEquals(MaterializationRowDecision(overwriteColumns = true, bumpUnread = true, markRead = false), d)
    }

    @Test
    fun `B1 回退物化（原主动消息 T0 晚于已落的回复 T2 才物化）不覆写 不加未读`() {
        // 现有行=AI 回复 T2=200；本次物化原主动消息 deliveredAt T0=100 < 200 → 回退
        val d = materializationRowDecision(currentLastMessageDate = 200L, timestamp = 100L, markReadNow = false)
        assertEquals(MaterializationRowDecision(overwriteColumns = false, bumpUnread = false, markRead = false), d)
    }

    @Test
    fun `时间戳相等视为最新（不回退）覆写并加未读`() {
        val d = materializationRowDecision(currentLastMessageDate = 150L, timestamp = 150L, markReadNow = false)
        assertEquals(MaterializationRowDecision(overwriteColumns = true, bumpUnread = true, markRead = false), d)
    }

    @Test
    fun `正在看会话 最新物化 覆写列 + 标记已读 不加未读`() {
        val d = materializationRowDecision(currentLastMessageDate = 100L, timestamp = 200L, markReadNow = true)
        assertEquals(MaterializationRowDecision(overwriteColumns = true, bumpUnread = false, markRead = true), d)
    }

    @Test
    fun `正在看会话 回退物化 不覆写列 但仍标记已读`() {
        val d = materializationRowDecision(currentLastMessageDate = 200L, timestamp = 100L, markReadNow = true)
        assertEquals(MaterializationRowDecision(overwriteColumns = false, bumpUnread = false, markRead = true), d)
    }
}
