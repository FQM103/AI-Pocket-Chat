package com.situ.aichat.redpacket

import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.MessageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 红包接受决策纯函数单测（P9.3b-1）。断言**反推 iOS**：parseAndValidate 必填/截断/技术 id 拒绝、fallback 默认收下、
 * formatDialogueLines 过滤+前缀+60 字截断、resolveFestivalName、buildPrompt 只露分档不露金额。LLM 编排走真机 + 复核。
 */
class RedPacketAcceptanceDecisionServiceTest {

    private fun parse(json: String) = RedPacketAcceptanceDecisionService.parseAndValidate(json)

    // ── parseAndValidate：accept 路径 ──

    @Test fun parse_accept_ok() {
        val out = parse("""{"shouldAccept":true,"reason":"关系好","chatReply":"谢谢啦"}""")
        out as RedPacketAcceptanceDecisionService.ParseOutcome.Success
        assertTrue(out.decision.shouldAccept)
        assertNull(out.decision.rejectionReason)
        assertEquals("谢谢啦", out.decision.chatReply)
        assertFalse(out.decision.isFromFallback)
    }

    // ── parseAndValidate：reject 路径需 rejectionReason ──

    @Test fun parse_reject_requires_rejection_reason() {
        val missing = parse("""{"shouldAccept":false,"reason":"不熟","chatReply":"先不收哈"}""")
        assertTrue(missing is RedPacketAcceptanceDecisionService.ParseOutcome.Failure)
        val ok = parse("""{"shouldAccept":false,"rejectionReason":"太贵重","reason":"不熟","chatReply":"收不起呀"}""")
        ok as RedPacketAcceptanceDecisionService.ParseOutcome.Success
        assertFalse(ok.decision.shouldAccept)
        assertEquals("太贵重", ok.decision.rejectionReason)
    }

    // ── 必填字段缺失 ──

    @Test fun parse_missing_required_fields() {
        assertTrue(parse("""{"reason":"x","chatReply":"y"}""") is RedPacketAcceptanceDecisionService.ParseOutcome.Failure) // 缺 shouldAccept
        assertTrue(parse("""{"shouldAccept":true,"chatReply":"y"}""") is RedPacketAcceptanceDecisionService.ParseOutcome.Failure) // 缺 reason
        assertTrue(parse("""{"shouldAccept":true,"reason":"x"}""") is RedPacketAcceptanceDecisionService.ParseOutcome.Failure) // 缺 chatReply
        assertTrue(parse("not json") is RedPacketAcceptanceDecisionService.ParseOutcome.Failure)
    }

    // ── chatReply 截断 40 + 技术 id 拒绝 ──

    @Test fun parse_chat_reply_capped_40() {
        val long = "啊".repeat(50)
        val out = parse("""{"shouldAccept":true,"reason":"r","chatReply":"$long"}""")
        out as RedPacketAcceptanceDecisionService.ParseOutcome.Success
        assertEquals(40, out.decision.chatReply!!.length)
    }

    @Test fun parse_rejects_tech_id_in_chat_reply() {
        assertTrue(parse("""{"shouldAccept":true,"reason":"r","chatReply":"看 red_packet 哦"}""") is RedPacketAcceptanceDecisionService.ParseOutcome.Failure)
        assertTrue(parse("""{"shouldAccept":true,"reason":"r","chatReply":"gift_x"}""") is RedPacketAcceptanceDecisionService.ParseOutcome.Failure)
    }

    // ── rejectionReason 截断 30 ──

    @Test fun parse_rejection_reason_capped_30() {
        val long = "由".repeat(40)
        val out = parse("""{"shouldAccept":false,"rejectionReason":"$long","reason":"r","chatReply":"先不啦"}""")
        out as RedPacketAcceptanceDecisionService.ParseOutcome.Success
        assertEquals(30, out.decision.rejectionReason!!.length)
    }

    // ── shouldAccept 非 Bool（字符串）应失败（严格类型） ──

    @Test fun parse_should_accept_must_be_bool() {
        assertTrue(parse("""{"shouldAccept":"true","reason":"r","chatReply":"y"}""") is RedPacketAcceptanceDecisionService.ParseOutcome.Failure)
    }

    // ── 兜底默认收下，不带 chatReply ──

    @Test fun fallback_defaults_accept_no_chat_reply() {
        val d = RedPacketAcceptanceDecisionService.fallbackDecision()
        assertTrue(d.shouldAccept)
        assertNull(d.rejectionReason)
        assertNull(d.chatReply)
        assertTrue(d.isFromFallback)
    }

    // ── resolveFestivalName ──

    @Test fun resolve_festival_name() {
        assertNull(RedPacketAcceptanceDecisionService.resolveFestivalName(null))
        assertNull(RedPacketAcceptanceDecisionService.resolveFestivalName("  "))
        // 未命中 id → 回退原 id（LLM 也能从 id 推语义）
        assertEquals("nope_festival", RedPacketAcceptanceDecisionService.resolveFestivalName("nope_festival"))
    }

    // ── formatDialogueLines：过滤系统卡片、前缀、60 字截断、取最近 6 ──

    private fun msg(role: String, content: String, kind: MessageKind = MessageKind.PLAIN_TEXT, ts: Long) =
        MessageEntity(messageUUID = "m$ts", conversationUuid = "c", roleRaw = role, content = content, timestamp = ts, messageKindRaw = kind.raw)

    @Test fun format_dialogue_filters_and_prefixes() {
        val msgs = listOf(
            msg("user", "你好", ts = 1),
            msg("assistant", "嗨", ts = 2),
            msg("user", "{json}", kind = MessageKind.RED_PACKET, ts = 3), // 红包卡不进对话氛围
            msg("user", "  ", ts = 4), // 空白过滤
            msg("assistant", "在忙", ts = 5),
        )
        val lines = RedPacketAcceptanceDecisionService.formatDialogueLines(msgs, "小七")
        assertEquals(listOf("用户:你好", "小七:嗨", "小七:在忙"), lines)
    }

    @Test fun format_dialogue_takes_last_6() {
        val msgs = (1..10).map { msg("user", "m$it", ts = it.toLong()) }
        val lines = RedPacketAcceptanceDecisionService.formatDialogueLines(msgs, "小七")
        assertEquals(6, lines.size)
        assertEquals("用户:m5", lines.first())
        assertEquals("用户:m10", lines.last())
    }

    @Test fun format_dialogue_truncates_60() {
        val long = "字".repeat(80)
        val lines = RedPacketAcceptanceDecisionService.formatDialogueLines(listOf(msg("user", long, ts = 1)), "小七")
        assertEquals("用户:" + "字".repeat(60) + "…", lines.first())
    }

    // ── buildPrompt：只露分档不露金额 ──

    @Test fun build_prompt_reveals_tier_not_amount() {
        val ctx = RedPacketAcceptanceDecisionService.Context(
            characterName = "小七", personalityDescription = "温柔", speakingStyle = "软糯",
            amountTier = "珍贵的心意", blessingText = "生日快乐", festivalName = "生日",
            recentDialogueLines = listOf("用户:在吗"), relationshipLabel = "恋人",
        )
        val (sys, usr) = RedPacketAcceptanceDecisionService.buildPrompt(ctx)
        assertTrue(sys.contains("你是「小七」"))
        assertTrue(usr.contains("金额档位:珍贵的心意"))
        assertTrue(usr.contains("祝福语:「生日快乐」"))
        assertTrue(usr.contains("当前关系:恋人"))
        // T4：Context 不含精确 amount 字段 → prompt 结构上无法泄漏；system 还显式禁止 LLM 提具体金额
        assertTrue(sys.contains("禁止:提及具体金额数字"))
    }

    @Test fun build_prompt_appends_previous_error() {
        val ctx = RedPacketAcceptanceDecisionService.Context("小七", "", "", "小心意", "", null, emptyList(), null)
        val (sys, _) = RedPacketAcceptanceDecisionService.buildPrompt(ctx, previousError = "字段缺失: chatReply")
        assertTrue(sys.contains("上次尝试出错"))
        assertTrue(sys.contains("字段缺失: chatReply"))
    }
}
