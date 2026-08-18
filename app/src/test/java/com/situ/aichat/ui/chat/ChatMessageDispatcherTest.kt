package com.situ.aichat.ui.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ChatMessageDispatcher 行为 + 自适应纯函数测试（输入排契约 C1·§3.2-1）。
 *
 * 手法：不引 coroutines-test 依赖——[ChatMessageDispatcher.delayMs] 注入门闩（CompletableDeferred），
 * scope 用 Unconfined 让 launch 体同步跑到挂起点，取消/放行全部确定性可控；持久层用内存假实现。
 * 覆盖：窗到期触发一次 / 连发重置计时只触发一次 / reset 撤窗 / windowPending 生命周期 / 等待值读取钳位 /
 * 时间戳记录裁剪 100 / 自适应 P75+0.5（样本不足、>10s 剔除、钳上下限）/ percentile75 线性插值。
 */
class ChatMessageDispatcherTest {

    private class FakePersistence : ChatMessageDispatcher.Persistence {
        var waitSeconds: Float? = null
        var timestamps: List<Long> = emptyList()
        override suspend fun loadWaitSeconds(): Float? = waitSeconds
        override suspend fun saveWaitSeconds(value: Float) { waitSeconds = value }
        override suspend fun loadSendTimestamps(): List<Long> = timestamps
        override suspend fun saveSendTimestamps(values: List<Long>) { timestamps = values }
    }

    /** 可控等待：每次 delayMs 记录请求毫秒数并挂在各自门闩上，测试放行/取消。 */
    private class GatedDelay {
        val requested = mutableListOf<Long>()
        val gates = mutableListOf<CompletableDeferred<Unit>>()
        val delayMs: suspend (Long) -> Unit = { ms ->
            requested += ms
            val gate = CompletableDeferred<Unit>()
            gates += gate
            gate.await()
        }
    }

    private fun dispatcher(
        persistence: FakePersistence = FakePersistence(),
        gated: GatedDelay = GatedDelay(),
        now: () -> Long = { 0L },
    ): Triple<ChatMessageDispatcher, FakePersistence, GatedDelay> {
        val d = ChatMessageDispatcher(
            scope = CoroutineScope(Dispatchers.Unconfined),
            persistence = persistence,
            nowMs = now,
            delayMs = gated.delayMs,
        )
        return Triple(d, persistence, gated)
    }

    // ────────────────────────── 窗行为 ──────────────────────────

    @Test
    fun 窗到期_触发一次onReady() {
        val (d, _, gated) = dispatcher()
        var fired = 0
        d.onReadyToSend = { fired++ }
        d.enqueue()
        assertEquals(0, fired) // 窗未到期不触发
        assertTrue(d.windowPending)
        gated.gates.single().complete(Unit)
        assertEquals(1, fired)
        assertFalse(d.windowPending)
    }

    @Test
    fun 连发_重置计时_只触发一次() {
        val (d, _, gated) = dispatcher()
        var fired = 0
        d.onReadyToSend = { fired++ }
        d.enqueue()
        d.enqueue() // 第二条：撤第一窗、开第二窗（合并的本义）
        assertEquals(2, gated.gates.size)
        gated.gates[0].complete(Unit) // 第一窗协程已被取消——放行也不触发
        assertEquals(0, fired)
        gated.gates[1].complete(Unit)
        assertEquals(1, fired) // 只有第二窗触发
    }

    @Test
    fun reset_撤销未到期窗_不触发() {
        val (d, _, gated) = dispatcher()
        var fired = 0
        d.onReadyToSend = { fired++ }
        d.enqueue()
        d.reset()
        assertFalse(d.windowPending)
        gated.gates.single().complete(Unit) // 窗协程已撤——放行也不触发
        assertEquals(0, fired)
    }

    // ────────────────── 健康线 2-5b：windowArmed（VM 死亡后的 flush 判据）──────────────────

    @Test
    fun windowArmed_窗开火与reset后复位() {
        val (d, _, gated) = dispatcher()
        d.onReadyToSend = {}
        assertFalse(d.windowArmed)
        d.enqueue()
        assertTrue("窗挂起期武装", d.windowArmed)
        gated.gates.single().complete(Unit)
        assertFalse("开火后复位", d.windowArmed)

        d.enqueue()
        d.reset()
        assertFalse("reset 后复位", d.windowArmed)
    }

    @Test
    fun windowArmed_scope死亡后仍真_而windowPending已假() {
        // 模拟 VM 清理：viewModelScope 先于 onCleared 死亡——Job 判据（windowPending）恒假，
        // 但「有已受理消息在等回合」的事实必须保留（windowArmed），供控制器退出时立即起窗回合。
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val gated = GatedDelay()
        val d = ChatMessageDispatcher(scope, FakePersistence(), nowMs = { 0L }, delayMs = gated.delayMs)
        d.onReadyToSend = {}
        d.enqueue()
        assertTrue(d.windowArmed)
        scope.cancel()
        assertFalse("Job 已死", d.windowPending)
        assertTrue("武装事实保留", d.windowArmed)
    }

    @Test
    fun 死scope上enqueue_windowPending假_armed真() {
        // 秒退竞态：发送流程（应用级作用域）在 VM 清理后才走到 enqueue——计时器无处跑，
        // 控制器据 windowPending=false 直接起回合（enqueueTurnWindow 兜底）。
        val scope = CoroutineScope(Dispatchers.Unconfined).also { it.cancel() }
        val d = ChatMessageDispatcher(scope, FakePersistence(), nowMs = { 0L }, delayMs = { })
        d.onReadyToSend = {}
        d.enqueue()
        assertFalse(d.windowPending)
        assertTrue(d.windowArmed)
    }

    @Test
    fun 等待值_未设过用默认_超范围读取时钳位() {
        val (d1, _, g1) = dispatcher()
        d1.onReadyToSend = {}
        d1.enqueue()
        assertEquals((ChatMessageDispatcher.DEFAULT_WAIT_SECONDS * 1000).toLong(), g1.requested.single())

        val p2 = FakePersistence().apply { waitSeconds = 99f } // 越上限的持久值（防异常写入）
        val (d2, _, g2) = dispatcher(persistence = p2)
        d2.onReadyToSend = {}
        d2.enqueue()
        assertEquals((ChatMessageDispatcher.MAX_WAIT_SECONDS * 1000).toLong(), g2.requested.single())
    }

    @Test
    fun 时间戳_按入队序记录_裁剪到100条并持久化() {
        var t = 0L
        val (d, p, _) = dispatcher(now = { t })
        d.onReadyToSend = {}
        repeat(105) { i ->
            t = i * 20_000L // 间隔 20s（>10s 阈，不产生自适应样本，隔离本用例只验记录）
            d.enqueue()
        }
        assertEquals(ChatMessageDispatcher.MAX_STORED_TIMESTAMPS, p.timestamps.size)
        assertEquals(5 * 20_000L, p.timestamps.first()) // 最老的 5 条被裁掉
        assertEquals(104 * 20_000L, p.timestamps.last())
    }

    @Test
    fun 自适应_连发样本足够_重算写回持久层() {
        var t = 0L
        val (d, p, _) = dispatcher(now = { t })
        d.onReadyToSend = {}
        repeat(6) { i -> // 间隔恒 2s → 5 个连发样本 → P75=2.0 → +0.5=2.5
            t = i * 2_000L
            d.enqueue()
        }
        assertEquals(2.5f, p.waitSeconds!!, 1e-4f)
    }

    // ────────────────────────── 自适应纯函数 ──────────────────────────

    @Test
    fun 自适应_样本不足5_不更新() {
        // 5 个时间戳=4 个间隔 <5 → null
        assertNull(computeAdaptiveWaitSeconds(listOf(0L, 1000L, 2000L, 3000L, 4000L)))
    }

    @Test
    fun 自适应_超10秒间隔剔除_剩余不足不更新() {
        // 6 间隔中 3 个 >10s 被剔 → 只剩 3 个连发样本 <5 → null
        val ts = listOf(0L, 1000L, 2000L, 15_000L, 30_000L, 45_000L, 46_000L)
        assertNull(computeAdaptiveWaitSeconds(ts))
    }

    @Test
    fun 自适应_P75加补偿_钳下限() {
        // 5 个 0.1s 间隔：P75=0.1 → +0.5=0.6 在范围内
        val fast = (0..5).map { it * 100L }
        assertEquals(0.6f, computeAdaptiveWaitSeconds(fast)!!, 1e-4f)
        // 5 个 9s 间隔：P75=9 → +0.5=9.5 → 钳到上限 5
        val slow = (0..5).map { it * 9_000L }
        assertEquals(ChatMessageDispatcher.MAX_WAIT_SECONDS, computeAdaptiveWaitSeconds(slow)!!, 1e-4f)
    }

    @Test
    fun percentile75_线性插值与边界() {
        assertEquals(1.0, percentile75(listOf(1.0)), 1e-9)
        assertEquals(2.5, percentile75(listOf(1.0, 2.0, 3.0)), 1e-9) // rank=1.5 → 2+(3-2)*0.5
        assertEquals(3.25, percentile75(listOf(1.0, 2.0, 3.0, 4.0)), 1e-9) // rank=2.25 → 3+(4-3)*0.25
    }
}
