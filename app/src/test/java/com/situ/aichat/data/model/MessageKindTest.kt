package com.situ.aichat.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定 [MessageKind] 的 raw 字面量与分类谓词。raw 串被 [com.situ.aichat.data.local.dao.MessageDao] 的 @Query SQL
 * 硬编码（'system_hint' / 'offline_marker_end'），SQL 无法编译期校验——故在此钉死：改 raw 会让本测试先红，
 * 提醒同步 DAO SQL，避免「改了枚举 → 可见性过滤静默失配」。
 */
class MessageKindTest {

    @Test
    fun `DAO SQL 依赖的 raw 字面量不可漂移`() {
        assertEquals("system_hint", MessageKind.SYSTEM_HINT.raw)
        assertEquals("offline_marker_end", MessageKind.OFFLINE_MARKER_END.raw)
    }

    /**
     * 向量记忆回填「不嵌入结构化卡」denylist（[com.situ.aichat.data.local.dao.MessageDao.hasMissingEmbedding] /
     * messagesMissingEmbedding 的 `messageKindRaw NOT IN (…)`）逐字硬编码这 10 个 raw。SQL 无法编译期校验，故钉死：
     * ① 每个 raw 不漂移；② 结构化卡集合**恰好**是这 10 个——新增结构化卡却忘了同步 DAO denylist 时本测试先红，
     * 避免「新卡原始 JSON（可能含金额 / 约定信息）静默漏进可检索向量库」。
     */
    @Test
    fun `向量回填 denylist 的结构化卡 raw 集合钉死`() {
        val backfillDenylist = setOf(
            "offline_invite_card", "offline_end_card", "call_record_card", "offline_marker_start",
            "offline_marker_end", "system_event_card", "gift_card", "red_packet", "future_meeting_proposal",
            "future_meeting_change",
        )
        assertEquals(
            backfillDenylist,
            MessageKind.entries.filter { it.isStructuredCard }.map { it.raw }.toSet(),
        )
    }

    @Test
    fun `fromRaw round-trip 与未知值回退 PLAIN_TEXT`() {
        MessageKind.entries.forEach { assertEquals(it, MessageKind.fromRaw(it.raw)) }
        assertEquals(MessageKind.PLAIN_TEXT, MessageKind.fromRaw("future_unknown_kind"))
    }

    @Test
    fun `结构化卡分类（JSON·标记 = 结构化；文本类 = 非结构化）`() {
        listOf(
            MessageKind.GIFT_CARD, MessageKind.RED_PACKET, MessageKind.CALL_RECORD_CARD,
            MessageKind.SYSTEM_EVENT_CARD, MessageKind.OFFLINE_INVITE_CARD, MessageKind.OFFLINE_END_CARD,
            MessageKind.OFFLINE_MARKER_START, MessageKind.OFFLINE_MARKER_END, MessageKind.FUTURE_MEETING_PROPOSAL_CARD,
            MessageKind.FUTURE_MEETING_CHANGE_CARD,
        ).forEach { assertTrue("$it 应为结构化卡", it.isStructuredCard) }
        listOf(MessageKind.PLAIN_TEXT, MessageKind.SCHEDULE_CARD, MessageKind.SYSTEM_HINT)
            .forEach { assertFalse("$it 不应为结构化卡", it.isStructuredCard) }
    }

    @Test
    fun `线下事件卡分类`() {
        listOf(
            MessageKind.OFFLINE_INVITE_CARD, MessageKind.OFFLINE_END_CARD,
            MessageKind.OFFLINE_MARKER_START, MessageKind.OFFLINE_MARKER_END,
        ).forEach { assertTrue("$it 应为线下事件卡", it.isOfflineEventCard) }
        assertFalse(MessageKind.PLAIN_TEXT.isOfflineEventCard)
        assertFalse(MessageKind.GIFT_CARD.isOfflineEventCard)
        assertFalse(MessageKind.SYSTEM_HINT.isOfflineEventCard)
    }
}
