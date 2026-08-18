package com.situ.aichat.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * 聊天列表「贴到底部」的单一协调员（P2·治 G4「连发滚动竞速」）。
 *
 * 旧法：新消息（C1）与「回底」FAB 各自一条 `LaunchedEffect` 直接 `animateScrollToItem`——AI 连发时每来一条就
 * 重启动画、把上一段在飞的动画**中途掐断**，列表一卡一卡。
 *
 * 新法：所有「滚到底」意图投进一个 **CONFLATED 队列**（冲突合并·永远只保留最新一次的动画意图），由**单一**
 * 消费协程顺序执行，**绝不中途打断**正在飞的那一次——「一个司机、永远瞄最新落点、滑顺不顿」。即便一次动画
 * 期间涌进多条消息，动画跑完后也只朝底部再滑一次。
 *
 * 列表反转（契约 FABLE5_CHAT_REVERSE_LIST_PROPOSAL §2 ③）后「底部」恒为 **index 0**——落点内化进本类
 * （[BOTTOM_INDEX]），调用方只表达「是否动画」（[stickToBottom] 的 `animate`）：首帧定位、系统「减弱动画」
 * 传 `false` 走瞬时 `scrollToItem`。滚动动作经 [BottomScroller] 抽象，便于行为测试（`ChatScrollCoordinatorTest`
 * 用假 scroller 验「合并 + 单飞」；生产实现包真 [LazyListState]）。
 *
 * 注：旧顶锚时代的「键盘升起逐帧贴底」独立效应已随反转删除——视口缩放时反转布局天然钉底（契约 §2 ④），
 * 键盘联动不再需要任何滚动补偿。
 */
internal interface BottomScroller {
    suspend fun animateTo(index: Int)
    suspend fun snapTo(index: Int)
}

internal class ChatScrollCoordinator(
    private val scroller: BottomScroller,
    scope: CoroutineScope,
) {
    /** CONFLATED：多条请求只保留最新一条 = 冲突合并。`trySend` 永不失败。元素=是否动画。 */
    private val requests = Channel<Boolean>(Channel.CONFLATED)

    init {
        scope.launch {
            for (animate in requests) {
                if (animate) scroller.animateTo(BOTTOM_INDEX) else scroller.snapTo(BOTTOM_INDEX)
            }
        }
    }

    /** 投递一次「滚到底」请求。[animate]=false → 立即落位（瞬时·首帧/减弱动画走此）。 */
    fun stickToBottom(animate: Boolean) {
        requests.trySend(animate)
    }

    companion object {
        /** 反转列表下「底部 = 最新消息」恒为 index 0（契约 §2 ③·落点单源）。 */
        const val BOTTOM_INDEX = 0
    }
}

/** 绑定真实列表状态：动画/瞬时分别走 [LazyListState] 的 `animateScrollToItem` / `scrollToItem`。 */
@Composable
internal fun rememberChatScrollCoordinator(listState: LazyListState): ChatScrollCoordinator {
    val scope = rememberCoroutineScope()
    return remember(listState) {
        ChatScrollCoordinator(
            object : BottomScroller {
                override suspend fun animateTo(index: Int) = listState.animateScrollToItem(index)
                override suspend fun snapTo(index: Int) = listState.scrollToItem(index)
            },
            scope,
        )
    }
}
