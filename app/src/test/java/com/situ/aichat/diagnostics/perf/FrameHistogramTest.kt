package com.situ.aichat.diagnostics.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1-3（图纸 2026-07-30 性能采集与量尺 §7）：[FrameHistogram] 分桶 / 掉帧判定 / 分位数。
 *
 * 断言从图纸 §3.4 的规格**独立反推**：
 * - 恰 8 桶，边界 0.5 / 0.8 / 1.0 / 1.5 / 2.0 / 3.0 / 5.0（帧预算倍数·**左闭右开**）；
 * - jank = 耗时 > `1000/refreshHz`，severe = > 2×，**按真实刷新率算而不是写死 16.67**
 *   （120Hz 与 60Hz 两组入参各验一遍——写死 16.67 正是 M27 那条 bug 的成因）；
 * - p50/p95/p99 从桶推算；
 * - 21600 帧喂进去内存恒定（固定 8 桶 + 标量）。
 */
class FrameHistogramTest {

    // MARK: - 帧预算按真实刷新率

    @Test
    fun `帧预算按真实刷新率算而不是写死`() {
        assertEquals(1000.0 / 60, FrameHistogram(60).budgetMs, 1e-9)
        assertEquals(1000.0 / 120, FrameHistogram(120).budgetMs, 1e-9)
        assertEquals(1000.0 / 90, FrameHistogram(90).budgetMs, 1e-9)
    }

    @Test
    fun `120Hz 上 10ms 是掉帧_60Hz 上同一帧不是`() {
        val hz120 = FrameHistogram(120).apply { add(10.0) } // 预算 8.33ms → 掉帧
        val hz60 = FrameHistogram(60).apply { add(10.0) } // 预算 16.67ms → 不掉帧

        assertEquals(1, hz120.jankCount)
        assertEquals(0, hz120.severeJankCount)
        assertEquals("写死 16.67 的实现会在这里判 0", 0, hz60.jankCount)
    }

    @Test
    fun `severeJank 是超两倍预算_两种刷新率各验一遍`() {
        val hz120 = FrameHistogram(120).apply { add(16.0); add(17.0) } // 2×预算=16.67：16 不算、17 算
        val hz60 = FrameHistogram(60).apply { add(33.0); add(34.0) } // 2×预算=33.33：33 不算、34 算

        assertEquals(1, hz120.severeJankCount)
        assertEquals(1, hz60.severeJankCount)
        assertEquals("超两倍必然也算掉帧", 2, hz120.jankCount)
    }

    @Test
    fun `恰等于预算不算掉帧（严格大于）`() {
        val h = FrameHistogram(60).apply { add(1000.0 / 60) }
        assertEquals(0, h.jankCount)
    }

    // MARK: - 分桶

    @Test
    fun `恰 8 桶`() {
        assertEquals(8, FrameHistogram.BUCKET_COUNT)
        assertEquals(8, FrameHistogram(60).bucketCounts().size)
        assertEquals(8, FrameHistogram.BUCKET_UPPER_RATIOS.size)
    }

    @Test
    fun `桶边界左闭右开_边界值归右边那个桶`() {
        // 边界比值：0.5 / 0.8 / 1.0 / 1.5 / 2.0 / 3.0 / 5.0
        assertEquals(0, FrameHistogram.bucketIndexOf(0.0))
        assertEquals(0, FrameHistogram.bucketIndexOf(0.499))
        assertEquals(1, FrameHistogram.bucketIndexOf(0.5))
        assertEquals(2, FrameHistogram.bucketIndexOf(0.8))
        assertEquals(3, FrameHistogram.bucketIndexOf(1.0))
        assertEquals(4, FrameHistogram.bucketIndexOf(1.5))
        assertEquals(5, FrameHistogram.bucketIndexOf(2.0))
        assertEquals(6, FrameHistogram.bucketIndexOf(3.0))
        assertEquals(7, FrameHistogram.bucketIndexOf(5.0))
        assertEquals(7, FrameHistogram.bucketIndexOf(999.0))
    }

    @Test
    fun `每帧只进一个桶_总计数等于喂进去的帧数`() {
        val h = FrameHistogram(60)
        val budget = 1000.0 / 60
        listOf(0.2, 0.6, 0.9, 1.2, 1.7, 2.5, 4.0, 8.0).forEach { h.add(it * budget) }

        assertEquals(listOf(1, 1, 1, 1, 1, 1, 1, 1), h.bucketCounts())
        assertEquals(8, h.frameCount)
        assertEquals(8, h.bucketCounts().sum())
    }

    // MARK: - 分位数

    @Test
    fun `分位数从桶推算_取所在桶上边界并与实测最大值取小`() {
        val h = FrameHistogram(60)
        val budget = 1000.0 / 60
        repeat(95) { h.add(0.3 * budget) } // 桶 0：[0, 0.5)
        repeat(5) { h.add(1.2 * budget) } // 桶 3：[1.0, 1.5)

        // 第 50 帧落桶 0 → 上边界 0.5×预算。
        assertEquals(0.5 * budget, h.percentileMs(0.50), 1e-9)
        // 第 95 帧仍落桶 0（前 95 帧都在桶 0）。
        assertEquals(0.5 * budget, h.percentileMs(0.95), 1e-9)
        // 第 99 帧落桶 3 → 上边界 1.5×预算，但与实测 maxMs(1.2×预算) 取小。
        assertEquals(1.2 * budget, h.percentileMs(0.99), 1e-9)
    }

    @Test
    fun `落在末桶的分位数返回实测最大值（末桶无上界）`() {
        val h = FrameHistogram(60)
        val budget = 1000.0 / 60
        repeat(9) { h.add(0.3 * budget) }
        h.add(7.0 * budget) // 末桶 [5, ∞)

        assertEquals(7.0 * budget, h.percentileMs(1.0), 1e-9)
        assertEquals(7.0 * budget, h.maxMs, 1e-9)
    }

    @Test
    fun `零帧时分位数与最大值都是 0`() {
        val h = FrameHistogram(120)
        assertEquals(0.0, h.percentileMs(0.5), 1e-9)
        assertEquals(0.0, h.maxMs, 1e-9)
        assertEquals(0, h.frameCount)
    }

    // MARK: - 内存恒定（E10）

    @Test
    fun `120Hz 停留 3 分钟约 21600 帧后内存结构恒定`() {
        val h = FrameHistogram(120)
        val budget = h.budgetMs
        repeat(21_600) { i -> h.add((0.4 + (i % 7) * 0.3) * budget) }

        assertEquals(21_600, h.frameCount)
        assertEquals("桶数永远是 8，不随帧数增长", 8, h.bucketCounts().size)
        assertEquals("每帧恰进一个桶", 21_600, h.bucketCounts().sum())
    }

    // MARK: - merge

    @Test
    fun `merge 是逐桶相加_等价于把两段帧喂进同一个直方图`() {
        val budget = 1000.0 / 120
        val frames = listOf(0.3, 0.9, 1.6, 2.4, 6.0).map { it * budget }

        val whole = FrameHistogram(120).apply { frames.forEach { add(it) } }
        val a = FrameHistogram(120).apply { frames.take(2).forEach { add(it) } }
        val b = FrameHistogram(120).apply { frames.drop(2).forEach { add(it) } }
        a.merge(b)

        assertEquals(whole.bucketCounts(), a.bucketCounts())
        assertEquals(whole.frameCount, a.frameCount)
        assertEquals(whole.jankCount, a.jankCount)
        assertEquals(whole.severeJankCount, a.severeJankCount)
        assertEquals(whole.maxMs, a.maxMs, 1e-9)
    }

    @Test
    fun `merge 空直方图是恒等（重复合并空段安全）`() {
        val h = FrameHistogram(90).apply { add(5.0); add(30.0) }
        val before = h.bucketCounts()

        repeat(3) { h.merge(FrameHistogram(90)) }

        assertEquals(before, h.bucketCounts())
        assertEquals(2, h.frameCount)
        assertTrue(h.maxMs == 30.0)
    }
}
