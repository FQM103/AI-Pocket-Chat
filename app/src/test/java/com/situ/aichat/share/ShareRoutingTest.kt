package com.situ.aichat.share

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 分享给角色（Direct Share · C3，13.10a）路由判定的纯函数单测。断言反推既有落地语义：
 * 命中具体角色（有快捷方式 id）→ 直接投会话；否则跳联系人点选；空白文本忽略。
 */
class ShareRoutingTest {

    @Test
    fun `text plus shortcut id routes Direct with trimmed values`() {
        val route = ShareRouting.decide("  看看这个  ", "  conv-123  ")
        assertEquals(ShareRoute.Direct("conv-123", "看看这个"), route)
    }

    @Test
    fun `text without shortcut id routes Picker`() {
        assertEquals(ShareRoute.Picker("hello"), ShareRouting.decide("hello", null))
    }

    @Test
    fun `text with blank shortcut id routes Picker (generic app entry, no character chosen)`() {
        assertEquals(ShareRoute.Picker("hi"), ShareRouting.decide("hi", "   "))
    }

    @Test
    fun `blank text is ignored even with a shortcut id`() {
        assertEquals(ShareRoute.Ignore, ShareRouting.decide("   ", "conv-123"))
    }

    @Test
    fun `null text is ignored`() {
        assertEquals(ShareRoute.Ignore, ShareRouting.decide(null, null))
    }

    @Test
    fun `empty text is ignored`() {
        assertEquals(ShareRoute.Ignore, ShareRouting.decide("", null))
    }
}
