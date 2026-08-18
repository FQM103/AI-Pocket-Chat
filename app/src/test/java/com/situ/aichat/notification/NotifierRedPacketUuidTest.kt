package com.situ.aichat.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 13.8·B2 红包一键领纯函数测试：从预警 requestKey（`red_packet_expiring_<uuid>`）剥前缀取 recordUuid。
 * **涉钱**——错的 uuid = 错的领取目标，故反推 [RedPacketExpirationScanService.warningRequestKey] 的格式严格断言。
 */
class NotifierRedPacketUuidTest {

    @Test
    fun `正常预警 key 取出 uuid`() {
        assertEquals(
            "abc-123-def",
            Notifier.redPacketUuidFromRequestKey("red_packet_expiring_abc-123-def"),
        )
    }

    @Test
    fun `uuid 含下划线也完整保留（只剥一次前缀）`() {
        assertEquals(
            "uuid_with_underscores",
            Notifier.redPacketUuidFromRequestKey("red_packet_expiring_uuid_with_underscores"),
        )
    }

    @Test
    fun `null 返回 null`() {
        assertNull(Notifier.redPacketUuidFromRequestKey(null))
    }

    @Test
    fun `非红包预警 key 返回 null（不挂领取动作）`() {
        assertNull(Notifier.redPacketUuidFromRequestKey("aichat_notif_char1_morning"))
        assertNull(Notifier.redPacketUuidFromRequestKey("story_unlock_xyz"))
    }

    @Test
    fun `仅前缀无 uuid 返回 null`() {
        assertNull(Notifier.redPacketUuidFromRequestKey("red_packet_expiring_"))
    }

    @Test
    fun `前缀与红包预警 category 常量一致（防前缀漂移）`() {
        // 守卫：本解析复用 CATEGORY_RED_PACKET_EXPIRING + "_"，须与 warningRequestKey 实际前缀一致。
        val key = "${Notifier.CATEGORY_RED_PACKET_EXPIRING}_rec-9"
        assertEquals("rec-9", Notifier.redPacketUuidFromRequestKey(key))
    }
}
