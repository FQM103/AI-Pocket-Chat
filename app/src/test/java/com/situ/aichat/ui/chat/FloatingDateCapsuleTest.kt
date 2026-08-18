package com.situ.aichat.ui.chat

import java.time.LocalDate
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ② 滚动浮动日期胶囊（契约 FABLE5_CHAT_TELEGRAM_MOTION_PROPOSAL §2·M1）——断言从契约规格独立反推：
 * 天粒度标签口径（拍板 D1）、抑制谓词（最早项可见/空表）、显隐状态机（拖动开启·fling 贯穿·停滚驻留·
 * 打断续显·抑制立即熄灭+会话内复显）。
 *
 * 时序测试用 [runBlocking] 单线程事件循环 + 注入短驻留（[SHORT_DELAY]=250ms）：同一事件循环上定时器按
 * 截止时刻有序触发（250ms 的隐藏必先于 400ms 的检查点恢复），无需引入 kotlinx-coroutines-test 新依赖
 * （全套件既有 3000+ 测试均未用·维持零新增）。检查点边距 ≥3 倍防慢机漂移。
 */
class FloatingDateCapsuleTest {

    // ---- 天粒度标签（D1：今天/昨天/同年 M月d日/跨年 yyyy年M月d日） ----

    private val today: LocalDate = LocalDate.of(2026, 7, 2)

    @Test
    fun `label - same day is TODAY`() {
        assertEquals(FloatingDateLabel.TODAY, floatingDateLabel(LocalDate.of(2026, 7, 2), today))
    }

    @Test
    fun `label - previous day is YESTERDAY`() {
        assertEquals(FloatingDateLabel.YESTERDAY, floatingDateLabel(LocalDate.of(2026, 7, 1), today))
    }

    @Test
    fun `label - same year older date is SAME_YEAR`() {
        assertEquals(FloatingDateLabel.SAME_YEAR, floatingDateLabel(LocalDate.of(2026, 1, 15), today))
    }

    @Test
    fun `label - cross year date is OTHER_YEAR`() {
        assertEquals(FloatingDateLabel.OTHER_YEAR, floatingDateLabel(LocalDate.of(2025, 12, 31), today))
    }

    @Test
    fun `label - yesterday across year boundary is YESTERDAY not OTHER_YEAR`() {
        // 1月1日看 12月31日：日历差 1 天 → 「昨天」优先于跨年分支。
        assertEquals(
            FloatingDateLabel.YESTERDAY,
            floatingDateLabel(LocalDate.of(2025, 12, 31), LocalDate.of(2026, 1, 1)),
        )
    }

    // ---- 抑制谓词（Telegram「最早日期行可见永不浮动」+「顶部非消息行抑制」合并口径） ----
    // 反转列表口径（契约 REVERSE_LIST §2.2）：最早项 = 最大 index；入参为「视觉顶=最大可见 index」。

    @Test
    fun `suppressed - empty list`() {
        assertTrue(floatingDateSuppressed(lastVisibleItemIndex = 0, totalItemsCount = 0))
    }

    @Test
    fun `suppressed - earliest loaded item visible (short chat or scrolled to top)`() {
        assertTrue(floatingDateSuppressed(lastVisibleItemIndex = 29, totalItemsCount = 30))
    }

    @Test
    fun `not suppressed - mid history`() {
        assertFalse(floatingDateSuppressed(lastVisibleItemIndex = 22, totalItemsCount = 30))
    }

    // ---- 显隐状态机（T2·runBlocking 事件循环时序） ----

    @Test
    fun `machine - drag shows immediately`() = machineTest { m ->
        m.onDragStart()
        m.onScrollingChanged(true)
        assertTrue(m.visible)
    }

    @Test
    fun `machine - hide waits delay after scroll settles`() = machineTest { m ->
        m.onDragStart()
        m.onScrollingChanged(true)
        m.onDragEnd()
        m.onScrollingChanged(false) // 无 fling 收尾
        assertTrue(m.visible) // 驻留期开始仍显示
        delay(BEFORE_DEADLINE)
        assertTrue(m.visible) // 驻留期内不熄
        delay(PAST_DEADLINE)
        assertFalse(m.visible)
    }

    @Test
    fun `machine - fling keeps visible until idle then delayed hide`() = machineTest { m ->
        m.onDragStart()
        m.onScrollingChanged(true)
        m.onDragEnd() // 松手·fling 继续（scrolling 仍 true）
        delay(PAST_DEADLINE)
        assertTrue(m.visible) // fling 全程常显（Telegram：DRAGGING 标志跨 SETTLING 存活到 IDLE）
        m.onScrollingChanged(false)
        delay(PAST_DEADLINE)
        assertFalse(m.visible)
    }

    @Test
    fun `machine - re-drag during hide delay cancels hide`() = machineTest { m ->
        m.onDragStart()
        m.onScrollingChanged(true)
        m.onDragEnd()
        m.onScrollingChanged(false)
        delay(BEFORE_DEADLINE)
        m.onDragStart() // 驻留期内再滚 → 取消淡出（Telegram 13450-13453 打断续接）
        m.onScrollingChanged(true)
        delay(PAST_DEADLINE * 2)
        assertTrue(m.visible)
    }

    @Test
    fun `machine - hold still then release - scroll stops before finger lifts`() = machineTest { m ->
        // 按住不动再松手：滚动可能先于抬指归零——两入口都要能收束会话。
        m.onDragStart()
        m.onScrollingChanged(true)
        m.onScrollingChanged(false) // 手还按着、滚动先停
        assertTrue(m.visible) // 会话未收束（仍在拖）
        m.onDragEnd() // 抬指 → 收束 → 驻留
        delay(PAST_DEADLINE)
        assertFalse(m.visible)
    }

    @Test
    fun `machine - suppression hides immediately without delay`() = machineTest { m ->
        m.onDragStart()
        m.onScrollingChanged(true)
        assertTrue(m.visible)
        m.onSuppressedChanged(true) // 滚到最早项/横幅在场 → 立即熄灭（无驻留）
        assertFalse(m.visible)
    }

    @Test
    fun `machine - unsuppress mid-session shows again`() = machineTest { m ->
        m.onDragStart()
        m.onScrollingChanged(true)
        m.onSuppressedChanged(true)
        assertFalse(m.visible)
        m.onSuppressedChanged(false) // 从开头往回滚（仍在同一次滚动会话）→ 复显
        assertTrue(m.visible)
    }

    @Test
    fun `machine - drag while suppressed stays hidden`() = machineTest { m ->
        m.onSuppressedChanged(true)
        m.onDragStart()
        m.onScrollingChanged(true)
        assertFalse(m.visible)
    }

    @Test
    fun `machine - suppression during hide delay cancels pending job and stays hidden`() = machineTest { m ->
        m.onDragStart()
        m.onScrollingChanged(true)
        m.onDragEnd()
        m.onScrollingChanged(false)
        delay(BEFORE_DEADLINE)
        m.onSuppressedChanged(true)
        assertFalse(m.visible)
        // 解除抑制（会话已结束）→ 不应复显。
        m.onSuppressedChanged(false)
        delay(PAST_DEADLINE)
        assertFalse(m.visible)
    }

    /** 统一 rig：注入短驻留 + 收尾取消孤儿定时器（防跨用例泄漏）。 */
    private fun machineTest(body: suspend (FloatingDateVisibility) -> Unit) = runBlocking {
        val machine = FloatingDateVisibility(this, hideDelayMs = SHORT_DELAY)
        try {
            body(machine)
        } finally {
            coroutineContext.cancelChildren()
        }
    }

    private companion object {
        /** 测试注入的驻留时长（生产值 500ms·语义同）。 */
        const val SHORT_DELAY = 250L

        /** 驻留期内检查点（距截止 ≥3 倍边距防慢机漂移）。 */
        const val BEFORE_DEADLINE = 80L

        /** 越过截止的检查点（同一事件循环上 250ms 定时器必先于 400ms 恢复点触发=确定有序）。 */
        const val PAST_DEADLINE = 400L
    }
}
