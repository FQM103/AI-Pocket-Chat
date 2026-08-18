package com.situ.aichat.redpacket

import com.situ.aichat.data.local.entity.RedPacketRecordEntity
import com.situ.aichat.data.model.WalletOwnerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 红包过期扫描纯函数单测（P9.3b-2）。断言**反推 iOS**：过期判定 now>expiresAt、22h 预警窗口（过期前 2h）+ 三条件
 * （receiver==user && !notified && in-window）、预警文案（不含金额）。过期退钱/通知投递走真机 + 钱路径复核。
 */
class RedPacketExpirationScanServiceTest {

    private val h24 = 24L * 60 * 60 * 1000
    private val h2 = 2L * 60 * 60 * 1000

    /** createdAt=0、expiresAt=24h 的角色→用户红包（receiver=user）。 */
    private fun characterToUser(
        notified: Boolean = false,
        expiresAt: Long = h24,
    ) = RedPacketRecordEntity(
        uuid = "r1",
        senderType = WalletOwnerType.CHARACTER.raw,
        senderCharacterUUID = "c1",
        receiverType = WalletOwnerType.USER.raw,
        receiverCharacterUUID = "",
        amount = 520,
        status = "pending",
        createdAt = 0,
        expiresAt = expiresAt,
        notifiedExpiringSoon = notified,
    )

    /** 用户→角色红包（receiver=character，不应预警）。 */
    private fun userToCharacter(expiresAt: Long = h24) = RedPacketRecordEntity(
        uuid = "r2",
        senderType = WalletOwnerType.USER.raw,
        receiverType = WalletOwnerType.CHARACTER.raw,
        receiverCharacterUUID = "c1",
        amount = 88,
        status = "pending",
        createdAt = 0,
        expiresAt = expiresAt,
    )

    // ── 过期判定：now > expiresAt ──

    @Test fun is_expired_strict_greater_than() {
        val r = characterToUser()
        assertFalse(RedPacketExpirationScanService.isExpired(r, h24)) // 恰好 = expiresAt 未过期
        assertFalse(RedPacketExpirationScanService.isExpired(r, h24 - 1))
        assertTrue(RedPacketExpirationScanService.isExpired(r, h24 + 1))
    }

    // ── 预警窗口常量 = 过期前 2h ──

    @Test fun warning_lead_is_2h() {
        assertEquals(h2, RedPacketExpirationScanService.WARNING_LEAD_MS)
    }

    // ── shouldWarn 三条件：receiver==user && !notified && now>=expiresAt-2h ──

    @Test fun should_warn_in_window_for_character_to_user() {
        val r = characterToUser()
        val warningAt = h24 - h2 // 22h
        assertFalse("窗口前不预警", RedPacketExpirationScanService.shouldWarn(r, warningAt - 1))
        assertTrue("恰好进窗口预警", RedPacketExpirationScanService.shouldWarn(r, warningAt))
        assertTrue("窗口内预警", RedPacketExpirationScanService.shouldWarn(r, warningAt + 1))
    }

    @Test fun should_not_warn_when_already_notified() {
        val r = characterToUser(notified = true)
        assertFalse(RedPacketExpirationScanService.shouldWarn(r, h24 - h2))
    }

    @Test fun should_not_warn_user_to_character() {
        // 用户发给角色的红包不推预警（由决策服务即时处理，1:1 iOS）
        val r = userToCharacter()
        assertFalse(RedPacketExpirationScanService.shouldWarn(r, h24 - h2))
        assertFalse(RedPacketExpirationScanService.shouldWarn(r, h24 - 1))
    }

    // ── 预警文案（不含金额） ──

    @Test fun expiring_content_no_amount() {
        val (title, body) = RedPacketExpirationScanService.buildExpiringContent("小七")
        assertEquals("🧧 小七 给你发的红包快过期啦", title)
        assertEquals("还有 2 小时不拆就要退回咯", body)
        assertFalse("预警标题不含金额", title.contains("520"))
        assertFalse("预警正文不含金额", body.contains("520"))
    }

    // ── requestKey 前缀稳定（精确闹钟 schedule/cancel 配对 + 通知 id 派生） ──

    @Test fun warning_request_key_prefix() {
        assertEquals("red_packet_expiring_r1", RedPacketExpirationScanService.warningRequestKey("r1"))
    }
}
