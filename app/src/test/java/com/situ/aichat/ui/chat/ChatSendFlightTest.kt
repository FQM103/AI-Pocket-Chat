package com.situ.aichat.ui.chat

import androidx.compose.ui.geometry.Rect
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.MessageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ④ 发送飞入·握手与降级链（契约 FABLE5_CHAT_TELEGRAM_MOTION_PROPOSAL §4·M3a）——断言从契约规格独立反推：
 * 闸链矩阵（D6 连发/D7 握手/D8 引用/减弱动画/不在底部）、押后清空握手（就位/超时先到先得·commit 恰一次·
 * 失败不清空由「不进握手」保证）、目标匹配口径、**关闸锁定**（M3a 总闸恒关=行为零变化地基）。
 */
class ChatSendFlightTest {

    // ---- 闸链矩阵 ----

    @Test
    fun `gates - all open`() {
        assertTrue(sendFlightGatesOpen(enabled = true, reduceMotion = false, listAtBottom = true, quoteReplyActive = false, flightBusy = false))
    }

    @Test
    fun `gates - each single gate closes the chain`() {
        assertFalse(sendFlightGatesOpen(false, false, true, false, false)) // 总闸
        assertFalse(sendFlightGatesOpen(true, true, true, false, false)) // reduceMotion
        assertFalse(sendFlightGatesOpen(true, false, false, false, false)) // 翻在历史深处
        assertFalse(sendFlightGatesOpen(true, false, true, true, false)) // D8 引用面板开着
        assertFalse(sendFlightGatesOpen(true, false, true, false, true)) // D6 连发第二条
    }

    @Test
    fun `gates - master switch is ON since M3b (flip it back off to fully revert to status quo)`() {
        // M3b 开闸锁定：总闸是最后一道整体降级阀——关回 false 即全径退回现状（M3a 已验证零变化地板）。
        assertTrue(SEND_FLIGHT_ENABLED)
    }

    // ---- 握手状态机 ----

    private fun state(now: Long = 1_000L) = ChatSendFlightState(nowMs = { now })

    private fun userMessage(content: String, timestamp: Long, role: String = "user", kind: MessageKind = MessageKind.PLAIN_TEXT) =
        MessageEntity(
            messageUUID = "m-$timestamp",
            conversationUuid = "c1",
            roleRaw = role,
            content = content,
            timestamp = timestamp,
            messageKindRaw = kind.raw,
        )

    @Test
    fun `tryBegin - gates closed commits immediately (status quo path)`() {
        val s = state()
        var committed = 0
        val began = s.tryBegin("你好", gatesOpen = false) { committed++ }
        assertFalse(began)
        assertEquals(1, committed)
        assertNull(s.pending)
    }

    @Test
    fun `tryBegin - gates open defers commit until resolution`() {
        val s = state()
        var committed = 0
        val began = s.tryBegin("你好", gatesOpen = true) { committed++ }
        assertTrue(began)
        assertEquals(0, committed) // 押后清空：就位/超时前不清
        assertNotNull(s.pending)
        assertTrue(s.busy)
    }

    @Test
    fun `resolveByTarget - commits once and launches`() {
        val s = state(now = 1_000L)
        var committed = 0
        var launched: MessageEntity? = null
        s.onLaunch = { msg, _, _ -> launched = msg }
        s.tryBegin("你好", gatesOpen = true) { committed++ }
        val msg = userMessage("你好", timestamp = 1_200L)
        s.resolveByTarget(msg, Rect(0f, 0f, 10f, 10f))
        assertEquals(1, committed)
        assertEquals(msg, launched)
        assertNull(s.pending)
        // 决议幂等：迟到的超时不再 commit。
        s.resolveByTimeout()
        assertEquals(1, committed)
    }

    @Test
    fun `resolveByTimeout - commits once, late target is no-op`() {
        val s = state()
        var committed = 0
        var launches = 0
        s.onLaunch = { _, _, _ -> launches++ }
        s.tryBegin("你好", gatesOpen = true) { committed++ }
        s.resolveByTimeout()
        assertEquals(1, committed)
        assertEquals(0, launches) // 超时兜底=无动画
        s.resolveByTarget(userMessage("你好", 1_200L), Rect.Zero)
        assertEquals(1, committed)
        assertEquals(0, launches)
    }

    @Test
    fun `second send while pending degrades immediately (D6)`() {
        val s = state()
        var first = 0
        var second = 0
        s.tryBegin("第一条", gatesOpen = true) { first++ }
        val began = s.tryBegin("第二条", gatesOpen = true) { second++ }
        assertFalse(began)
        assertEquals(1, second) // 第二条立即清空（降级）
        assertEquals(0, first) // 第一条握手不受扰
        assertEquals("第一条", s.pending?.text)
    }

    // ---- 目标匹配口径（其后落库的同文用户纯文本） ----

    @Test
    fun `matches - positive`() {
        val s = state(now = 1_000L)
        s.tryBegin("你好", gatesOpen = true) {}
        assertTrue(s.pending!!.matches(userMessage("你好", timestamp = 1_001L)))
    }

    @Test
    fun `matches - rejects assistant, non-plain, earlier timestamp, different text`() {
        val s = state(now = 1_000L)
        s.tryBegin("你好", gatesOpen = true) {}
        val p = s.pending!!
        assertFalse(p.matches(userMessage("你好", 1_001L, role = "assistant")))
        assertFalse(p.matches(userMessage("你好", 1_001L, kind = MessageKind.SCHEDULE_CARD)))
        assertFalse(p.matches(userMessage("你好", 999L))) // 发送前的旧同文消息
        assertFalse(p.matches(userMessage("你好呀", 1_001L)))
    }

    // ---- M3b：飞行帧数学（横轴管左右/宽·纵轴管上下/高——弧线感来源） ----

    private val start = Rect(100f, 2000f, 900f, 2100f)
    private val target = Rect(400f, 1500f, 950f, 1580f)

    @Test
    fun `frame - endpoints are exact`() {
        assertEquals(start, flightFrame(start, target, 0f, 0f))
        assertEquals(target, flightFrame(start, target, 1f, 1f))
    }

    @Test
    fun `frame - horizontal edges follow x fraction only`() {
        val f = flightFrame(start, target, xFraction = 1f, yFraction = 0f)
        assertEquals(target.left, f.left)
        assertEquals(target.right, f.right)
        assertEquals(start.top, f.top) // 纵向纹丝不动
        assertEquals(start.bottom, f.bottom)
    }

    @Test
    fun `frame - vertical edges follow y fraction only`() {
        val f = flightFrame(start, target, xFraction = 0f, yFraction = 1f)
        assertEquals(start.left, f.left)
        assertEquals(target.top, f.top)
        assertEquals(target.bottom, f.bottom)
    }

    @Test
    fun `alpha ramp - completes at 40 percent (Telegram min(t div 0_4, 1))`() {
        assertEquals(0f, flightAlphaRamp(0f))
        assertEquals(0.5f, flightAlphaRamp(0.2f))
        assertEquals(1f, flightAlphaRamp(0.4f))
        assertEquals(1f, flightAlphaRamp(0.9f))
    }

    // ---- M3b：移动靶与飞行生命周期 ----

    @Test
    fun `onBubblePositioned - pending match resolves and launches with bounds`() {
        val s = state(now = 1_000L)
        var committed = 0
        var launchedBounds: Rect? = null
        s.onLaunch = { _, bounds, _ -> launchedBounds = bounds }
        s.tryBegin("你好", gatesOpen = true) { committed++ }
        s.onBubblePositioned(userMessage("你好", 1_100L), Rect(10f, 20f, 30f, 40f))
        assertEquals(1, committed)
        assertEquals(Rect(10f, 20f, 30f, 40f), launchedBounds)
    }

    @Test
    fun `onBubblePositioned - during flight updates moving target`() {
        val s = state(now = 1_000L)
        s.inputBounds = Rect(0f, 100f, 200f, 144f)
        val msg = userMessage("你好", 1_100L)
        s.beginFlight(msg, Rect(50f, 50f, 150f, 90f))
        s.onBubblePositioned(msg, Rect(50f, 40f, 150f, 80f)) // 列表位移弹簧推着目标走
        assertEquals(Rect(50f, 40f, 150f, 80f), s.flight!!.targetBounds)
        s.endFlight()
        assertNull(s.flight)
    }

    @Test
    fun `beginFlight - degrades when input bounds unknown`() {
        val s = state()
        s.beginFlight(userMessage("你好", 1_100L), Rect(0f, 0f, 10f, 10f)) // inputBounds 未上报
        assertNull(s.flight)
    }

    @Test
    fun `busy - covers pending and flight phases (D6 gate input)`() {
        val s = state()
        assertFalse(s.busy)
        s.tryBegin("你好", gatesOpen = true) {}
        assertTrue(s.busy)
        s.onBubblePositioned(userMessage("你好", 1_100L), Rect.Zero) // 决议（未起飞·onLaunch 未挂）
        assertFalse(s.busy)
        s.inputBounds = Rect(0f, 0f, 100f, 44f)
        s.beginFlight(userMessage("你好", 1_200L), Rect(0f, 0f, 10f, 10f))
        assertTrue(s.busy) // 飞行中=连发第二条降级
    }
}
