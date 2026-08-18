package com.situ.aichat.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 红包系统事件纯函数单测（P9.3a-2/3）。断言**反推 iOS 真实文案/归属**：
 * - buildRedPacketEventTitle：6 组 UI 视角文案 + 空名→「对方」。
 * - buildRedPacketLLMRepresentation：6 组角色第一人称 actionClause + 金额/祝福(80+…)/理由(仅 user+rejected,30 无…)。
 * - systemEventTargetIsAssistant：accepted/rejected 归接收方、expired 归发起方、非红包/缺 senderRole 兜底。
 * - makeRedPacketSystemEventData：emoji=🧧 + 字段装配。
 */
class SystemEventRedPacketTest {

    private fun event(
        type: SystemEventType,
        senderRole: String?,
        amount: Int? = null,
        blessing: String? = null,
        reason: String? = null,
        emoji: String = "🧧",
        title: String = "",
    ) = SystemEventData(
        type = "system_event",
        eventType = type.raw,
        title = title,
        emoji = emoji,
        timestamp = "2026-06-02T00:00:00Z",
        amount = amount,
        rejectionReason = reason,
        blessingText = blessing,
        senderRole = senderRole,
    )

    // ── buildRedPacketEventTitle（UI 用户视角） ──

    @Test fun title_six_cases() {
        val u = RedPacketEventSenderRole.USER
        val c = RedPacketEventSenderRole.CHARACTER
        assertEquals("小七收下了你的红包", buildRedPacketEventTitle(SystemEventType.RED_PACKET_ACCEPTED, u, "小七"))
        assertEquals("你收下了小七的红包", buildRedPacketEventTitle(SystemEventType.RED_PACKET_ACCEPTED, c, "小七"))
        assertEquals("小七拒收了你的红包", buildRedPacketEventTitle(SystemEventType.RED_PACKET_REJECTED, u, "小七"))
        assertEquals("你拒收了小七的红包", buildRedPacketEventTitle(SystemEventType.RED_PACKET_REJECTED, c, "小七"))
        assertEquals("发给小七的红包 24 小时未拆,已退回", buildRedPacketEventTitle(SystemEventType.RED_PACKET_EXPIRED, u, "小七"))
        assertEquals("小七发的红包 24 小时未拆,已退回", buildRedPacketEventTitle(SystemEventType.RED_PACKET_EXPIRED, c, "小七"))
    }

    @Test fun title_empty_name_falls_back() {
        assertEquals("对方收下了你的红包", buildRedPacketEventTitle(SystemEventType.RED_PACKET_ACCEPTED, RedPacketEventSenderRole.USER, ""))
    }

    // ── buildRedPacketLLMRepresentation（角色第一人称） ──

    @Test fun llm_action_clauses_six_cases() {
        fun rep(t: SystemEventType, sr: String) = buildRedPacketLLMRepresentation(event(t, sr), t)
        assertEquals("[系统记录：你收下了用户的红包]", rep(SystemEventType.RED_PACKET_ACCEPTED, "user"))
        assertEquals("[系统记录：用户收下了你的红包]", rep(SystemEventType.RED_PACKET_ACCEPTED, "character"))
        assertEquals("[系统记录：你拒收了用户的红包]", rep(SystemEventType.RED_PACKET_REJECTED, "user"))
        assertEquals("[系统记录：用户拒收了你的红包]", rep(SystemEventType.RED_PACKET_REJECTED, "character"))
        assertEquals("[系统记录：用户发给你的红包 24 小时未拆,自动退回]", rep(SystemEventType.RED_PACKET_EXPIRED, "user"))
        assertEquals("[系统记录：你发给用户的红包 24 小时未被拆开,自动退回]", rep(SystemEventType.RED_PACKET_EXPIRED, "character"))
    }

    /** 图纸一 R1 承接·红包指名（人称=角色「你」+ 用户名·可选参传名字则用名字，不传回退「用户」）。 */
    @Test fun llm_action_clauses_use_userName_when_given() {
        fun rep(t: SystemEventType, sr: String) = buildRedPacketLLMRepresentation(event(t, sr), t, "小七")
        assertEquals("[系统记录：你收下了小七的红包]", rep(SystemEventType.RED_PACKET_ACCEPTED, "user"))
        assertEquals("[系统记录：小七收下了你的红包]", rep(SystemEventType.RED_PACKET_ACCEPTED, "character"))
        assertEquals("[系统记录：你拒收了小七的红包]", rep(SystemEventType.RED_PACKET_REJECTED, "user"))
        assertEquals("[系统记录：小七拒收了你的红包]", rep(SystemEventType.RED_PACKET_REJECTED, "character"))
        assertEquals("[系统记录：小七发给你的红包 24 小时未拆,自动退回]", rep(SystemEventType.RED_PACKET_EXPIRED, "user"))
        assertEquals("[系统记录：你发给小七的红包 24 小时未被拆开,自动退回]", rep(SystemEventType.RED_PACKET_EXPIRED, "character"))
        // 不传 userName → 回退「用户」（与旧行为一致，其余消费者字节不变）。
        assertEquals("[系统记录：你收下了用户的红包]", buildRedPacketLLMRepresentation(event(SystemEventType.RED_PACKET_ACCEPTED, "user"), SystemEventType.RED_PACKET_ACCEPTED))
    }

    @Test fun llm_amount_blessing_reason_assembly() {
        // accepted + sender=user + 金额 + 祝福（理由不应出现，因为非 rejected）
        val accepted = event(SystemEventType.RED_PACKET_ACCEPTED, "user", amount = 520, blessing = "  生日快乐  ", reason = "不该出现")
        assertEquals(
            "[系统记录：你收下了用户的红包 | 金额=520 金币 | 祝福=「生日快乐」]",
            buildRedPacketLLMRepresentation(accepted, SystemEventType.RED_PACKET_ACCEPTED),
        )
    }

    @Test fun llm_reason_only_for_user_rejected_capped_30_no_ellipsis() {
        val long = "理".repeat(40)
        // rejected + sender=user → 带「你的理由」，截 30 字无 …
        val rejUser = event(SystemEventType.RED_PACKET_REJECTED, "user", amount = 88, reason = long)
        val rep = buildRedPacketLLMRepresentation(rejUser, SystemEventType.RED_PACKET_REJECTED)
        assertTrue(rep.contains("你的理由=「" + "理".repeat(30) + "」"))
        assertFalse("拒收理由截断不加 …", rep.contains("…"))
        // rejected + sender=character → 不带理由（用户拒收角色红包的罕见路径）
        val rejChar = event(SystemEventType.RED_PACKET_REJECTED, "character", amount = 88, reason = "随便")
        assertFalse(buildRedPacketLLMRepresentation(rejChar, SystemEventType.RED_PACKET_REJECTED).contains("你的理由"))
    }

    @Test fun llm_blessing_capped_80_with_ellipsis() {
        val long = "福".repeat(100)
        val e = event(SystemEventType.RED_PACKET_ACCEPTED, "user", amount = 1, blessing = long)
        val rep = buildRedPacketLLMRepresentation(e, SystemEventType.RED_PACKET_ACCEPTED)
        assertTrue(rep.contains("祝福=「" + "福".repeat(80) + "…」"))
    }

    @Test fun llm_zero_amount_omitted() {
        val e = event(SystemEventType.RED_PACKET_ACCEPTED, "user", amount = 0)
        assertEquals("[系统记录：你收下了用户的红包]", buildRedPacketLLMRepresentation(e, SystemEventType.RED_PACKET_ACCEPTED))
    }

    // ── systemEventTargetIsAssistant（归属） ──

    @Test fun target_attribution_accepted_rejected_by_receiver() {
        // accepted/rejected：动作由接收方做；user 发→角色收/拒→assistant；character 发→用户做→user
        assertTrue(systemEventTargetIsAssistant(event(SystemEventType.RED_PACKET_ACCEPTED, "user")))
        assertFalse(systemEventTargetIsAssistant(event(SystemEventType.RED_PACKET_ACCEPTED, "character")))
        assertTrue(systemEventTargetIsAssistant(event(SystemEventType.RED_PACKET_REJECTED, "user")))
        assertFalse(systemEventTargetIsAssistant(event(SystemEventType.RED_PACKET_REJECTED, "character")))
    }

    @Test fun target_attribution_expired_by_sender() {
        // expired：归发起方；user 发→user；character 发→assistant
        assertFalse(systemEventTargetIsAssistant(event(SystemEventType.RED_PACKET_EXPIRED, "user")))
        assertTrue(systemEventTargetIsAssistant(event(SystemEventType.RED_PACKET_EXPIRED, "character")))
    }

    @Test fun target_attribution_missing_sender_role_defaults_user() {
        // senderRole 缺失 → 按「用户发」兜底
        assertTrue(systemEventTargetIsAssistant(event(SystemEventType.RED_PACKET_ACCEPTED, null)))
        assertFalse(systemEventTargetIsAssistant(event(SystemEventType.RED_PACKET_EXPIRED, null)))
    }

    @Test fun target_attribution_non_redpacket_is_user() {
        val old = SystemEventData(type = "system_event", eventType = "relationship_change", title = "升级", emoji = "💞", timestamp = "x")
        assertFalse(systemEventTargetIsAssistant(old))
        // 未知 eventType（fromRaw=null）也兜底 user
        val unknown = old.copy(eventType = "garbage")
        assertFalse(systemEventTargetIsAssistant(unknown))
    }

    // ── makeRedPacketSystemEventData ──

    @Test fun make_event_data_sets_emoji_and_fields() {
        val data = makeRedPacketSystemEventData(
            eventType = SystemEventType.RED_PACKET_REJECTED,
            amount = 168,
            blessingText = "心意",
            rejectionReason = "暂时不方便收",
            senderRole = RedPacketEventSenderRole.USER,
            characterName = "小七",
            timestampMillis = 0L,
        )
        assertEquals("system_event", data.type)
        assertEquals("red_packet_rejected", data.eventType)
        assertEquals("🧧", data.emoji)
        assertEquals("小七拒收了你的红包", data.title)
        assertEquals(168, data.amount)
        assertEquals("暂时不方便收", data.rejectionReason)
        assertEquals("心意", data.blessingText)
        assertEquals("user", data.senderRole)
    }

    @Test fun system_event_json_omits_null_redpacket_fields_for_old_events() {
        val old = SystemEventData(type = "system_event", eventType = "memory_update", title = "更新", emoji = "📝", timestamp = "x")
        val encoded = SystemEventJson.encode(old)
        assertFalse(encoded.contains("amount"))
        assertFalse(encoded.contains("senderRole"))
        assertEquals(old, SystemEventJson.parse(encoded))
    }

    @Test fun system_event_type_is_red_packet_event() {
        assertTrue(SystemEventType.RED_PACKET_ACCEPTED.isRedPacketEvent)
        assertTrue(SystemEventType.RED_PACKET_REJECTED.isRedPacketEvent)
        assertTrue(SystemEventType.RED_PACKET_EXPIRED.isRedPacketEvent)
        assertFalse(SystemEventType.RELATIONSHIP_CHANGE.isRedPacketEvent)
        assertFalse(SystemEventType.MEMORY_UPDATE.isRedPacketEvent)
        assertFalse(SystemEventType.GROWTH_ANALYSIS.isRedPacketEvent)
    }
}
