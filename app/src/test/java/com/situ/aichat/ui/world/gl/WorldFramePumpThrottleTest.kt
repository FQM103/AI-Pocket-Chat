package com.situ.aichat.ui.world.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WorldFramePump] 时间基准节流 T1（性能卷 B 图纸 §7·E1–E5）。
 *
 * 断言全部从**规格**独立反推、不抄实现：期望落拍序列由「目标 30Hz=33ms 阈值 / 60Hz=16ms 阈值」在各刷新率
 * 步长上手算得出（每条用例注释里附算式），再喂进真泵体 [WorldFramePump.onFrame] 对照。
 * 帧时间戳用真实量级的开机纳秒（[BASE]），因为「首拍 now−0 恒 ≥ 间隔 → 立即渲染」正是 E5 要锁的语义。
 */
class WorldFramePumpThrottleTest {

    /** 一次仿真：按刷新率步长喂 N 拍，记下**实画**落在第几拍。 */
    private class Harness(
        var frozen: Boolean = false,
        var gesturing: Boolean = false,
        var highFps: Boolean = false,
    ) {
        val rendered = mutableListOf<Int>()
        var ticks = 0
        private var index = 0
        private val pump = WorldFramePump(
            frozen = { frozen },
            gesturing = { gesturing },
            highFps = { highFps },
            render = { rendered += index },
            onTick = { ticks++ },
        )

        /** 喂第 [i] 拍（帧时间戳 = [BASE] + i×[stepNanos]）。 */
        fun frame(i: Int, stepNanos: Long) {
            index = i
            pump.onFrame(BASE + i * stepNanos)
        }

        fun frames(count: Int, stepNanos: Long) {
            for (i in 0 until count) frame(i, stepNanos)
        }
    }

    // ── ① 锁定常量 + shouldRender 边界（§9 ①/②）──

    @Test
    fun 节流阈值为图纸锁定值() {
        assertEquals(33_000_000L, WorldFramePump.AMBIENT_MIN_INTERVAL_NANOS)   // 30Hz 档（33.33ms 半拍容差）
        assertEquals(16_000_000L, WorldFramePump.INTERACTIVE_MIN_INTERVAL_NANOS) // 60Hz 档（16.67ms 半拍容差）
    }

    @Test
    fun shouldRender_恰等于间隔画_差一纳秒不画() {
        val interval = WorldFramePump.AMBIENT_MIN_INTERVAL_NANOS
        assertTrue("恰满间隔应渲染", WorldFramePump.shouldRender(BASE + interval, BASE, interval))
        assertFalse("差 1ns 不渲染", WorldFramePump.shouldRender(BASE + interval - 1, BASE, interval))
    }

    @Test
    fun shouldRender_初值零时首拍立即渲染() { // E5：泵首拍 / stop→start 重启首拍（lastRenderNanos 不重置）
        assertTrue(WorldFramePump.shouldRender(BASE, 0L, WorldFramePump.AMBIENT_MIN_INTERVAL_NANOS))
        assertTrue(WorldFramePump.shouldRender(BASE, 0L, WorldFramePump.INTERACTIVE_MIN_INTERVAL_NANOS))
    }

    // ── ② 帧序列表：四刷新率 × 环境/交互两档（E1–E3）──

    @Test
    fun 环境态各刷新率恒定三十赫兹() {
        // 33ms 阈值：120Hz 每 4 拍(4×8.333=33.33ms)、90Hz 每 3 拍(33.33)、60Hz 每 2 拍(33.33)、144Hz 每 5 拍(34.72≈28.8Hz)
        assertEquals("120Hz 环境态", listOf(0, 4, 8, 12, 16, 20), ambientSequence(HZ120))
        assertEquals("90Hz 环境态", listOf(0, 3, 6, 9, 12, 15, 18, 21), ambientSequence(HZ90))
        assertEquals("60Hz 环境态", listOf(0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22), ambientSequence(HZ60))
        assertEquals("144Hz 环境态", listOf(0, 5, 10, 15, 20), ambientSequence(HZ144))
    }

    @Test
    fun 交互态各刷新率恒定六十赫兹以下() {
        // 16ms 阈值：120Hz 每 2 拍(16.67ms=60Hz)、60Hz 每拍(16.67=60Hz)、90Hz 每 2 拍(22.2=45Hz)、144Hz 每 3 拍(20.8=48Hz)
        assertEquals("120Hz 交互态", (0 until 24 step 2).toList(), interactiveSequence(HZ120))
        assertEquals("60Hz 交互态", (0 until 24).toList(), interactiveSequence(HZ60))
        assertEquals("90Hz 交互态", (0 until 24 step 2).toList(), interactiveSequence(HZ90))
        assertEquals("144Hz 交互态", (0 until 24 step 3).toList(), interactiveSequence(HZ144))
    }

    @Test
    fun 每拍恒跑onTick_与是否实画无关() { // B3：转场信号轮询不容延迟
        val h = Harness(highFps = false)
        h.frames(24, HZ120)
        assertEquals(24, h.ticks)
        assertEquals(6, h.rendered.size) // 同上：120Hz 环境态 24 拍只画 6 次
    }

    // ── ③ frozen 手势序列（E4 / B4）──

    @Test
    fun 冻结态手势走交互档_松手补帧绕节流且不更新计时() {
        val h = Harness(frozen = true, gesturing = true)
        for (i in 0..4) h.frame(i, HZ120)          // 手势期：120Hz 上每 2 拍 = 60Hz → 0,2,4
        h.gesturing = false
        h.frame(5, HZ120)                          // 松手：距上次实画仅 8.33ms（节流本会拒）→ 仍必须补终态帧
        h.frame(6, HZ120)                          // 补帧只补一次
        h.frozen = false                           // 转回环境态：计时基准应仍是第 4 拍（补帧不更新 lastRenderNanos）
        h.frame(7, HZ120)                          // 距第 4 拍 25.0ms < 33ms → 不画
        h.frame(8, HZ120)                          // 距第 4 拍 33.33ms ≥ 33ms → 画
        assertEquals(listOf(0, 2, 4, 5, 8), h.rendered)
    }

    @Test
    fun 冻结态松手后只补一帧且静置不再画() {
        val h = Harness(frozen = true, gesturing = true)
        for (i in 0..3) h.frame(i, HZ120)          // 0,2 实画；第 3 拍被节流拒，但 settleFrames 恒置 1
        h.gesturing = false
        for (i in 4..10) h.frame(i, HZ120)         // 第 4 拍补终态帧，之后冻结静置零渲染
        assertEquals(listOf(0, 2, 4), h.rendered)
        assertEquals(11, h.ticks)                  // onTick 仍每拍恒跑
    }

    // ── 仿真辅助 ──

    private fun ambientSequence(stepNanos: Long): List<Int> =
        Harness(highFps = false).apply { frames(24, stepNanos) }.rendered

    private fun interactiveSequence(stepNanos: Long): List<Int> =
        Harness(highFps = true).apply { frames(24, stepNanos) }.rendered

    private companion object {
        /** 真实量级的开机纳秒（frameTimeNanos 是 uptime·绝非从 0 起）。 */
        const val BASE = 1_000_000_000_000L
        const val HZ120 = 8_333_333L
        const val HZ90 = 11_111_111L
        const val HZ60 = 16_666_667L
        const val HZ144 = 6_944_444L
    }
}
