package com.situ.aichat.ui.chat

import androidx.compose.ui.focus.FocusRequester
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 聊天「+」面板高度决策的纯函数单测 + 状态机「锁定高度同步生效」行为测（契约 FABLE5_CHAT_PLUS_PANEL_PROPOSAL.md §3/§5）。
 * 锁定不变量：① 自适应——开面板优先用当前真实键盘高度；② 免塌陷——区域恒取 max，切换中途不掉底；
 * ③ 锁定高度同步——openPanel 后区域当帧即 = 锁定高度，绝不留慢一帧空窗（根除快速点击 +/× 上下跳）。
 */
class InputPanelStateTest {

    // —— ① 自适应高度 + 边界硬化：chatPanelTargetHeightPx（用 minPx=300, maxPx=1600）——

    @Test
    fun target_prefersCurrentRealKeyboardHeight() {
        // 键盘正起着开面板：直接锁当前真实高度（不写死、不用兜底），这是丝滑无跳变的前提。
        assertEquals(1104, chatPanelTargetHeightPx(1104, lastImeHeightPx = 900, fallbackPx = 800, minPx = 300, maxPx = 1600))
    }

    @Test
    fun target_fallsBackToLastMeasuredWhenNoKeyboard() {
        // 无键盘时点开面板：退回最近一次量到的真实键盘高度，仍贴合用户的输入法。
        assertEquals(900, chatPanelTargetHeightPx(0, lastImeHeightPx = 900, fallbackPx = 800, minPx = 300, maxPx = 1600))
    }

    @Test
    fun target_fallsBackToDefaultWhenNeverMeasured() {
        // 从未弹过键盘（首次）：才用兜底默认值。
        assertEquals(800, chatPanelTargetHeightPx(0, lastImeHeightPx = 0, fallbackPx = 800, minPx = 300, maxPx = 1600))
    }

    @Test
    fun target_ignoresFloatingKeyboardTinyInset() {
        // 悬浮/分屏键盘报极小 inset（< minPx）：不当真实键盘用，退回最近量到值，防面板塌成一条。
        assertEquals(900, chatPanelTargetHeightPx(50, lastImeHeightPx = 900, fallbackPx = 800, minPx = 300, maxPx = 1600))
    }

    @Test
    fun target_clampsAbnormallyTallToMax() {
        // 异常畸高的 inset：钳到 maxPx，防面板畸高。
        assertEquals(1600, chatPanelTargetHeightPx(2400, lastImeHeightPx = 0, fallbackPx = 800, minPx = 300, maxPx = 1600))
    }

    @Test
    fun target_clampsTooSmallFallbackToMin() {
        // 连兜底都偏小：钳到 minPx，绝不塌成一条。
        assertEquals(300, chatPanelTargetHeightPx(0, lastImeHeightPx = 0, fallbackPx = 120, minPx = 300, maxPx = 1600))
    }

    // —— ② 免塌陷不变量：chatPanelRegionPx ——

    @Test
    fun region_followsKeyboardWhenPanelClosed() {
        assertEquals(1104, chatPanelRegionPx(imeBottomPx = 1104, heldPx = 0, panelOpen = false))
        assertEquals(0, chatPanelRegionPx(imeBottomPx = 0, heldPx = 1104, panelOpen = false))
    }

    @Test
    fun region_holdsPanelHeightWhileKeyboardSlidesAwayOnOpen() {
        // 开面板后系统键盘下落：ime 由满高→0，但区域恒为锁定高度，托盘零位移（核心免抖不变量）。
        assertEquals(1104, chatPanelRegionPx(imeBottomPx = 1104, heldPx = 1104, panelOpen = true))
        assertEquals(1104, chatPanelRegionPx(imeBottomPx = 500, heldPx = 1104, panelOpen = true))
        assertEquals(1104, chatPanelRegionPx(imeBottomPx = 0, heldPx = 1104, panelOpen = true))
    }

    @Test
    fun region_followsTallerKeyboardWhenSwappingBack() {
        // 切回键盘且键盘比面板更高：取较大值跟键盘上升，不被旧面板高度卡住。
        assertEquals(1200, chatPanelRegionPx(imeBottomPx = 1200, heldPx = 1104, panelOpen = true))
    }

    // —— ③ 锁定高度同步生效：openPanel 后区域当帧即就位，杜绝慢一帧空窗（根除快速点击 +/× 上下跳）——

    @Test
    fun openPanel_floorTakesEffectSynchronously_regionNeverCollapsesMidKeyboardSlide() {
        val state = ChatInputPanelState(
            scope = CoroutineScope(Dispatchers.Unconfined),
            keyboard = null,
            focusManager = mockk(relaxed = true),
            fieldFocus = FocusRequester(),
            minHeightPx = 160,
            maxHeightPx = 1600,
        )
        // 键盘正起着(1000)时点开面板：锁定高度必须"同一刻"就位，而非靠下一帧异步补上。
        state.openPanel(currentImePx = 1000, fallbackPx = 800)
        // 紧接着那一帧——键盘正滑走、inset 已掉到 50：区域必须仍 = 锁定高度 1000，绝不塌到 50（旧异步实现会塌→上下跳）。
        assertEquals(1000, state.regionPx(imeBottomPx = 50))
        // 键盘比锁定值更高时区域跟键盘（免塌陷 max 不被锁定值卡住）。
        assertEquals(1200, state.regionPx(imeBottomPx = 1200))
    }
}
