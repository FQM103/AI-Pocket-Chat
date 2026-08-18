package com.situ.aichat.ui.chat

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.SoftwareKeyboardController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 「输入面板宿主」——聊天输入栏「+」功能面板与系统键盘**轮流坐同一块底部区域**、输入托盘锚定不位移的状态机。
 * 设计契约见 FABLE5_CHAT_PLUS_PANEL_PROPOSAL.md。
 *
 * **稳定性核心（长期稳定第一目标）**：底部区域应有高度恒取 `max(实时 IME 高度, 面板锁定高度)`——无论
 * 开面板 / 切回键盘 / 收起，切换中途都**不出现高度塌陷**，故托盘永不掉底，从根上免抖；而**不是**靠精确掐
 * 系统 IME 动画帧那种脆办法。
 *
 * **锁定高度同步生效（免抖关键·2026-06-30 修）**：[floorPx] 是**同步**快照状态——开面板 / 释放时与
 * [panelOpen] 在**同一刻**写入，绝不留「panelOpen 已 true 但锁定高度还停在 0」的慢一帧空窗。旧实现用
 * `Animatable` + `scope.launch { snapTo }`，高度慢一帧才补上；快速点击 +/× 时这一帧恰逢键盘半滑、
 * `max(低 ime, 0)` 把区域塌一下又弹回 = 用户实测的上下跳。只有「收起到无」([dismiss]) 这一条需要动画，
 * 单独走协程平滑落 0。
 *
 * **自适应高度（硬指标）**：开面板时直接锁定为**当前真实键盘高度**（[openPanel] 入参 `currentImePx`，
 * 即 `WindowInsets.ime` 实时值），故不管何种输入法、用户怎么调高都严丝合缝、绝不写死；仅在「无键盘弹起时
 * 点开面板」才退回最近一次量到的真实高度 / 兜底默认值。
 */
class ChatInputPanelState(
    private val scope: CoroutineScope,
    private val keyboard: SoftwareKeyboardController?,
    private val focusManager: FocusManager,
    private val fieldFocus: FocusRequester,
    private val minHeightPx: Int,
    private val maxHeightPx: Int,
) {
    /**
     * 面板锁定高度（px）——**同步**快照状态：开面板 / 释放即时写入（与 [panelOpen] 同一快照），区域当帧即读到正确
     * 高度，杜绝慢一帧空窗致塌陷。仅 [dismiss] 收起时由协程把它平滑落 0。
     */
    private var floorPx by mutableIntStateOf(0)

    /** 面板内容是否在场（true=绘制功能 tile）。 */
    var panelOpen by mutableStateOf(false)
        private set

    /** 正把键盘唤回中——用于区分「开面板时 IME 下落」与「切回键盘时 IME 上升」，防止把刚开的面板误判收掉。 */
    private var wantKeyboard by mutableStateOf(false)

    /** 最近一次量到的真实键盘高度（仅作「无键盘弹起时开面板」的兜底）。in-memory·跨配置变更存活够用。 */
    private var lastImeHeightPx = 0

    /** 进行中的「收起」平滑动画（开面板 / 释放时取消它，避免动画与同步写值打架）。 */
    private var dismissJob: Job? = null

    /** 当前底部区域应有高度（px）：键盘与面板谁高取谁——免塌陷、免抖。 */
    fun regionPx(imeBottomPx: Int): Int =
        chatPanelRegionPx(imeBottomPx, floorPx, panelOpen)

    /** 点「+」开面板：**同步**锁定为当前真实键盘高度（无键盘则兜底）+ 收键盘。锁定高度与 panelOpen 同帧生效（不留空窗）。 */
    fun openPanel(currentImePx: Int, fallbackPx: Int) {
        dismissJob?.cancel()
        dismissJob = null
        wantKeyboard = false
        floorPx = chatPanelTargetHeightPx(currentImePx, lastImeHeightPx, fallbackPx, minHeightPx, maxHeightPx)
        panelOpen = true
        focusManager.clearFocus()   // 最稳的收键盘法（不与系统 IME 抢，避免回弹/竞态）
    }

    /** 点「+」切回 / 点输入框：唤起键盘；保持面板态直到键盘升满覆盖面板再释放（释放在 [onImeHeightChanged]）。 */
    fun requestKeyboard() {
        if (!panelOpen) return
        fieldFocus.requestFocus()
        keyboard?.show()
        armKeyboardReturn()
    }

    /** 输入框获得焦点（用户点了输入框）——键盘随聚焦自然升起，这里武装「切回键盘」流程。 */
    fun onFieldFocused() {
        if (panelOpen) armKeyboardReturn()
    }

    /** 武装「切回键盘」：标记意图 + 挂超时兜底（防这次键盘最终高度略低于锁定值致面板永不释放）。 */
    private fun armKeyboardReturn() {
        if (wantKeyboard) return
        wantKeyboard = true
        scope.launch {
            delay(KEYBOARD_RETURN_TIMEOUT_MS)
            // 到点键盘动画必已结束（imePanelPx≈floorPx），强制无缝释放·落差可忽略。
            if (panelOpen && wantKeyboard) releaseHold()
        }
    }

    /** 释放面板锁定（交棒给键盘）：**同步**清锁定高度 + 关面板态。 */
    private fun releaseHold() {
        dismissJob?.cancel()
        dismissJob = null
        panelOpen = false
        floorPx = 0
        wantKeyboard = false
    }

    /** 收起到「无」（返回键 / 选定某动作）：平滑落 0（reduceMotion 直切）。这是唯一需要动画的锁定高度变化路径。 */
    fun dismiss(reduceMotion: Boolean) {
        if (!panelOpen) return
        wantKeyboard = false
        if (reduceMotion) {
            floorPx = 0
            panelOpen = false
            return
        }
        val from = floorPx.toFloat()
        dismissJob?.cancel()
        dismissJob = scope.launch {
            animate(from, 0f, animationSpec = tween(DISMISS_MS)) { value, _ -> floorPx = value.roundToInt() }
            panelOpen = false
            dismissJob = null
        }
    }

    /**
     * 每帧 IME 高度变化驱动（从 `LaunchedEffect(imePanelPx)` 调）：
     * ① 学习真实键盘高度作兜底；② 切回键盘时，待键盘升满覆盖面板（imePanelPx ≥ 锁定高度）再释放，零落差无缝交棒。
     */
    fun onImeHeightChanged(imeBottomPx: Int) {
        // 仅学"够实"的高度（≥ minHeightPx）——滤掉悬浮/分屏键盘报的 0/极小 inset，免污染兜底值。
        if (imeBottomPx >= minHeightPx && !panelOpen) lastImeHeightPx = imeBottomPx
        // 切回键盘：**等键盘升满覆盖面板（imePanelPx ≥ 锁定高度）再释放**——此刻 max(ime, floor)=ime，清掉 floor
        // 零落差、不抖。提前释放（旧 0.9 阈值）会让区域瞬掉到当前 ime、再随键盘升回 = 抖一下（已修）。
        if (panelOpen && wantKeyboard && imeBottomPx > 0 && imeBottomPx >= floorPx) {
            releaseHold()
            lastImeHeightPx = imeBottomPx
        }
    }

    companion object {
        private const val DISMISS_MS = 240
        private const val KEYBOARD_RETURN_TIMEOUT_MS = 400L
    }
}

@Composable
fun rememberChatInputPanelState(
    keyboard: SoftwareKeyboardController?,
    focusManager: FocusManager,
    fieldFocus: FocusRequester,
    minHeightPx: Int,
    maxHeightPx: Int,
): ChatInputPanelState {
    val scope = rememberCoroutineScope()
    return remember(keyboard, focusManager, fieldFocus, minHeightPx, maxHeightPx) {
        ChatInputPanelState(scope, keyboard, focusManager, fieldFocus, minHeightPx, maxHeightPx)
    }
}

// ——纯函数：高度决策（抽出便于单测·契约 §3/§5）——

/**
 * 开面板时面板该锁定多高（px）：① 当前键盘高度够"实"（≥ [minPx]）则最准；② 否则退回最近量到的真实高度；
 * ③ 再不行兜底默认。最后钳到 `[minPx, maxPx]`。「自适应高度」读真实键盘值绝不写死，同时边界硬化——
 * `minPx` 阈值滤掉悬浮/分屏键盘报的 0/极小 inset（防面板塌成一条），`maxPx` 防异常值致畸高。
 */
internal fun chatPanelTargetHeightPx(
    currentImePx: Int,
    lastImeHeightPx: Int,
    fallbackPx: Int,
    minPx: Int,
    maxPx: Int,
): Int {
    val raw = when {
        currentImePx >= minPx -> currentImePx
        lastImeHeightPx >= minPx -> lastImeHeightPx
        else -> fallbackPx
    }
    return raw.coerceIn(minPx, maxPx)
}

/**
 * 底部区域应有高度（px）= max(实时键盘高度, 面板开时的锁定高度)。**免塌陷不变量**：开/关/换向时区域永不低于
 * 当前所需，故输入托盘绝不掉底——这是「丝滑」的根（而非掐 IME 动画帧）。
 */
internal fun chatPanelRegionPx(imeBottomPx: Int, heldPx: Int, panelOpen: Boolean): Int =
    max(imeBottomPx, if (panelOpen) heldPx else 0)
