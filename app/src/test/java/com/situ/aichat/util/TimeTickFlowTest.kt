package com.situ.aichat.util

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [timeTickFlow] 单测：证明它**反复发射**（= 倒数小条到点就地变身赴约按钮的驱动机制——把墙钟变成流输入，
 * 不再卡在「DB 不写就不重算」）。用 runBlocking + 小周期（项目无 kotlinx-coroutines-test，沿用 runBlocking 习惯）。
 * 边界判定本身（countdown↔arrival↔missed 各刻恰一真）已由 MeetingArrivalPolicyTest 覆盖，这里只锁「会持续跳动」。
 */
class TimeTickFlowTest {

    @Test fun emitsRepeatedly_notJustOnce() = runBlocking {
        // 立即首发 + 之后每 periodMillis 再发 → take(3) 取到 3 个（约 2*period 实墙钟，余量足不 flaky）。
        val ticks = timeTickFlow(periodMillis = 20L).take(3).toList()
        assertEquals(3, ticks.size)
    }

    @Test fun emitsCurrentWallClock_monotonicNonDecreasing() = runBlocking {
        // 发的是当前毫秒，同进程墙钟单调不减（用于 isCountdownState/isArrivalState 的 now 输入）。
        val ticks = timeTickFlow(periodMillis = 20L).take(3).toList()
        assertTrue("ticks 应单调不减: $ticks", ticks[1] >= ticks[0] && ticks[2] >= ticks[1])
    }
}
