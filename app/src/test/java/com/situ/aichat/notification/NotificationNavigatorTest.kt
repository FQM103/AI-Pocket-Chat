package com.situ.aichat.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * NotificationNavigator 赴约信号行为测（Phase 10·10b-2）：[NotificationNavigator.requestMeetupArrival] 同时设
 * 赴约信号（VM 读）+ 复用会话导航信号（AIChatApp 导航）；两信号各自独立 consume（导航 consume 不清赴约，反之亦然）。
 */
class NotificationNavigatorTest {

    @Test fun requestMeetupArrival_setsBothArrivalAndConversationSignals() {
        val nav = NotificationNavigator()
        nav.requestMeetupArrival("conv-1", "appt-1")
        assertEquals(MeetupArrivalTarget("conv-1", "appt-1"), nav.pendingMeetupArrival.value)
        assertEquals("conv-1", nav.pendingConversation.value) // 复用会话导航通道
    }

    @Test fun consumeMeetupArrival_clearsArrivalOnly_notConversation() {
        val nav = NotificationNavigator()
        nav.requestMeetupArrival("conv-1", "appt-1")
        nav.consumeMeetupArrival()
        assertNull(nav.pendingMeetupArrival.value)
        // 导航信号由 AIChatApp 自己的 consume() 清，赴约 consume 不连带清它（两信号分担、各自独立）。
        assertEquals("conv-1", nav.pendingConversation.value)
    }

    @Test fun consume_clearsConversationOnly_notArrival() {
        val nav = NotificationNavigator()
        nav.requestMeetupArrival("conv-1", "appt-1")
        nav.consume() // AIChatApp 导航后调
        assertNull(nav.pendingConversation.value)
        assertEquals(MeetupArrivalTarget("conv-1", "appt-1"), nav.pendingMeetupArrival.value)
    }
}
