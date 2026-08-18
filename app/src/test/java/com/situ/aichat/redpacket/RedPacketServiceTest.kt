package com.situ.aichat.redpacket

import com.situ.aichat.data.model.RedPacketStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RedPacketService 纯函数单测（P9.3a-2）。断言**反推 iOS** 截断阈值（不对称：blessing 80 加 …、reason 30 不加 …）
 * 与错误文案范围。原子事务/状态机/退钱编排走 Room + 真机 + 独立复核（钱），不在此单测。
 */
class RedPacketServiceTest {

    // ── cappedBlessing：≤80 原样、>80 截 80 + "…"、空串归一 "" ──

    @Test fun capped_blessing_short_unchanged() {
        assertEquals("新年快乐", RedPacketService.cappedBlessing("新年快乐"))
        assertEquals("", RedPacketService.cappedBlessing("   "))
        assertEquals("祝福", RedPacketService.cappedBlessing("  祝福  "))
    }

    @Test fun capped_blessing_exactly_80_unchanged() {
        val s = "福".repeat(80)
        assertEquals(s, RedPacketService.cappedBlessing(s))
    }

    @Test fun capped_blessing_over_80_truncates_with_ellipsis() {
        val s = "福".repeat(81)
        assertEquals("福".repeat(80) + "…", RedPacketService.cappedBlessing(s))
    }

    // ── cappedRejectionReason：≤30 原样、>30 纯截 30（无 …）、空串归一 "" ──

    @Test fun capped_reason_short_unchanged() {
        assertEquals("暂时不方便", RedPacketService.cappedRejectionReason("暂时不方便"))
        assertEquals("", RedPacketService.cappedRejectionReason(""))
        assertEquals("理由", RedPacketService.cappedRejectionReason("  理由  "))
    }

    @Test fun capped_reason_exactly_30_unchanged() {
        val s = "理".repeat(30)
        assertEquals(s, RedPacketService.cappedRejectionReason(s))
    }

    @Test fun capped_reason_over_30_truncates_no_ellipsis() {
        val s = "理".repeat(31)
        val out = RedPacketService.cappedRejectionReason(s)
        assertEquals("理".repeat(30), out)
        assertTrue("拒收理由截断不加 …", !out.contains("…"))
    }

    // ── 错误文案（金额超范围带 [1, 20000] 范围串） ──

    @Test fun amount_out_of_range_error_message() {
        val e = RedPacketError.AmountOutOfRange(99999)
        assertTrue(e.message!!.contains("99999"))
        assertTrue(e.message!!.contains("[1, 20000]"))
    }

    @Test fun already_resolved_error_message() {
        val e = RedPacketError.AlreadyResolved(RedPacketStatus.ACCEPTED)
        assertTrue(e.message!!.contains("accepted"))
    }
}
