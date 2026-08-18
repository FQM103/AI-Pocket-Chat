package com.situ.aichat.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D3 重进分档纯决策 T1（断言从规格独立反推：无消息不掺和；未答任意时长恢复；已答 <3min 静默、
 * 3–10min 轻推、>10min 交恢复弹窗；>3h 算超长离开；归来分钟数向下取整且至少 3）。
 */
class OfflineReturnPolicyTest {

    @Test
    fun 无线下消息_不掺和() {
        assertEquals(OfflineReturnPolicy.Action.NONE, OfflineReturnPolicy.decide(null, 0L))
        assertEquals(OfflineReturnPolicy.Action.NONE, OfflineReturnPolicy.decide(null, 60 * 60_000L))
    }

    @Test
    fun 末条未获回答_任意时长都恢复() {
        assertEquals(OfflineReturnPolicy.Action.RECOVER_UNANSWERED, OfflineReturnPolicy.decide("user", 10_000L))
        assertEquals(OfflineReturnPolicy.Action.RECOVER_UNANSWERED, OfflineReturnPolicy.decide("user", 24 * 60 * 60_000L))
    }

    @Test
    fun 已答_分档边界() {
        // <3min 静默（含边界前一毫秒）。
        assertEquals(OfflineReturnPolicy.Action.NONE, OfflineReturnPolicy.decide("assistant", 0L))
        assertEquals(OfflineReturnPolicy.Action.NONE, OfflineReturnPolicy.decide("assistant", 3 * 60_000L - 1))
        // [3min, 10min] 轻推。
        assertEquals(OfflineReturnPolicy.Action.NUDGE, OfflineReturnPolicy.decide("assistant", 3 * 60_000L))
        assertEquals(OfflineReturnPolicy.Action.NUDGE, OfflineReturnPolicy.decide("assistant", 10 * 60_000L))
        // >10min 交恢复弹窗（本策略静默）。
        assertEquals(OfflineReturnPolicy.Action.NONE, OfflineReturnPolicy.decide("assistant", 10 * 60_000L + 1))
    }

    @Test
    fun 超长离开判定() {
        assertFalse(OfflineReturnPolicy.isLongAbsence(3 * 60 * 60_000L))
        assertTrue(OfflineReturnPolicy.isLongAbsence(3 * 60 * 60_000L + 1))
    }

    @Test
    fun 归来分钟数_向下取整且至少3() {
        assertEquals(3L, OfflineReturnPolicy.awayMinutes(0L))
        assertEquals(3L, OfflineReturnPolicy.awayMinutes(3 * 60_000L + 30_000L)) // 3.5min → 3
        assertEquals(9L, OfflineReturnPolicy.awayMinutes(9 * 60_000L + 59_999L))
        assertEquals(45L, OfflineReturnPolicy.awayMinutes(45 * 60_000L))
    }
}
