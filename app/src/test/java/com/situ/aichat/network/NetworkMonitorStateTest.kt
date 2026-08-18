package com.situ.aichat.network

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [nextNetworkState] 单测（P0-2）。**断言反推 iOS `NetworkMonitorService`**：首条回调抑制 statusChanged、
 * 仅真实变化才发 statusChanged、相同状态早退。Pair = (新 isConnected, statusChanged 增量[null=不改])。
 */
class NetworkMonitorStateTest {

    @Test
    fun firstCallback_connected_suppressesStatusChanged() {
        assertEquals(true to null, nextNetworkState(prevConnected = true, hasReceivedInitial = false, nowConnected = true))
    }

    @Test
    fun firstCallback_offline_suppressesStatusChanged() {
        assertEquals(false to null, nextNetworkState(prevConnected = true, hasReceivedInitial = false, nowConnected = false))
    }

    @Test
    fun dropAfterEstablished_emitsFalse() {
        assertEquals(false to false, nextNetworkState(prevConnected = true, hasReceivedInitial = true, nowConnected = false))
    }

    @Test
    fun recoverAfterEstablished_emitsTrue() {
        assertEquals(true to true, nextNetworkState(prevConnected = false, hasReceivedInitial = true, nowConnected = true))
    }

    @Test
    fun noChange_earlyReturnsNullDelta() {
        assertEquals(true to null, nextNetworkState(prevConnected = true, hasReceivedInitial = true, nowConnected = true))
        assertEquals(false to null, nextNetworkState(prevConnected = false, hasReceivedInitial = true, nowConnected = false))
    }
}
