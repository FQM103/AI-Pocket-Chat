package com.situ.aichat.ui.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P2 行为测试：[ChatScrollCoordinator]「冲突合并 + 单飞不打断」的协调语义（治 G4 连发滚动竞速）。
 * 列表反转（契约 FABLE5_CHAT_REVERSE_LIST_PROPOSAL §2 ③）后落点内化为恒 index 0——断言一并锁定
 * 「任何请求都只朝 index 0 滚」（[ChatScrollCoordinator.BOTTOM_INDEX] 单源）。
 *
 * 项目无 kotlinx-coroutines-test，沿用 runBlocking 习惯；用 [CompletableDeferred] 当挂起闸精确编排
 * 「消费者停在第一段动画里 → 期间涌入两条请求 → 放行后只按最新意图再滚一次」，断言中段请求被合并丢弃、
 * 在飞动画绝不被中途掐断。假 [BottomScroller] 记录调用序列，不依赖真实 LazyListState / 模拟器。
 */
class ChatScrollCoordinatorTest {

    @Test
    fun coalesces_rapidRequests_keepsOnlyLatest_withoutInterrupting() = runBlocking {
        val calls = mutableListOf<String>()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var animateCalls = 0
        val scroller = object : BottomScroller {
            override suspend fun animateTo(index: Int) {
                calls.add("a$index")
                if (++animateCalls == 1) { // 第一段：停在动画里,模拟「在飞」
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                }
            }
            override suspend fun snapTo(index: Int) { calls.add("s$index") }
        }
        val job = Job()
        val coord = ChatScrollCoordinator(scroller, CoroutineScope(coroutineContext + job))

        coord.stickToBottom(animate = true)
        withTimeout(2_000) { firstStarted.await() } // 消费者已停在第一段 animateTo 内
        coord.stickToBottom(animate = true) // 在飞期间涌入 → 冲突合并…
        coord.stickToBottom(animate = false) // …只剩最新意图（瞬时）
        releaseFirst.complete(Unit) // 放行第一段,消费者接着取最新的瞬时请求（中段动画请求被合并丢弃）
        withTimeout(2_000) { while (!calls.contains("s0")) yield() }

        // 第一段跑完整段不被打断,中段动画请求被合并丢弃,只按最新意图（瞬时）朝 index 0 再滚一次。
        assertEquals(listOf("a0", "s0"), calls)
        job.cancel()
    }

    @Test
    fun instantRequest_usesSnapNotAnimate_targetsIndexZero() = runBlocking {
        val calls = mutableListOf<String>()
        val scroller = recordingScroller(calls)
        val job = Job()
        ChatScrollCoordinator(scroller, CoroutineScope(coroutineContext + job))
            .stickToBottom(animate = false)
        withTimeout(2_000) { while (calls.isEmpty()) yield() }

        assertEquals(listOf("s0"), calls)
        job.cancel()
    }

    @Test
    fun animatedRequest_usesAnimateNotSnap_targetsIndexZero() = runBlocking {
        val calls = mutableListOf<String>()
        val scroller = recordingScroller(calls)
        val job = Job()
        ChatScrollCoordinator(scroller, CoroutineScope(coroutineContext + job))
            .stickToBottom(animate = true)
        withTimeout(2_000) { while (calls.isEmpty()) yield() }

        assertEquals(listOf("a0"), calls)
        job.cancel()
    }

    private fun recordingScroller(calls: MutableList<String>) = object : BottomScroller {
        override suspend fun animateTo(index: Int) { calls.add("a$index") }
        override suspend fun snapTo(index: Int) { calls.add("s$index") }
    }
}
