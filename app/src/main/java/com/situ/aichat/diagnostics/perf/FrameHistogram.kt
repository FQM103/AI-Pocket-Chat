package com.situ.aichat.diagnostics.perf

import kotlin.math.ceil

/**
 * 帧耗时直方图（纯数据·固定内存·图纸 §3.4 + J2）。
 *
 * 为什么是直方图而不是逐帧落盘：120Hz × 3 分钟 ≈ 21600 帧，逐帧写盘的话**采集本身就成了最大的性能问题**。
 * 这里只维护 8 个计数器 + 几个标量，喂多少帧内存都恒定。
 *
 * 分桶单位是**帧预算的倍数**，不是绝对毫秒 —— 单帧预算 = `1000.0 / refreshHz`，**必须按真实刷新率算**：
 * 写死 16.67 正是本轮扫描里 M27 那条 bug 的成因（120Hz 屏上把 8.3ms 的正常帧当成「没掉帧」）。
 *
 * 判定（锁定）：`TOTAL_DURATION > 预算` = jank；`> 2× 预算` = severeJank。
 */
class FrameHistogram(val refreshHz: Int) {

    /** 单帧预算（毫秒）。 */
    val budgetMs: Double = 1000.0 / refreshHz.coerceAtLeast(1)

    private val buckets = IntArray(BUCKET_COUNT)

    var frameCount: Int = 0
        private set
    var jankCount: Int = 0
        private set
    var severeJankCount: Int = 0
        private set
    var maxMs: Double = 0.0
        private set

    /** 记一帧。**零分配**（只动 IntArray 与标量）。 */
    fun add(frameMs: Double) {
        frameCount++
        if (frameMs > maxMs) maxMs = frameMs
        val ratio = frameMs / budgetMs
        if (ratio > 1.0) jankCount++
        if (ratio > 2.0) severeJankCount++
        buckets[bucketIndexOf(ratio)]++
    }

    /** 合并另一段统计（同刷新率才有意义）。空直方图合进来 = 恒等，故重复合空是安全的。 */
    fun merge(other: FrameHistogram) {
        for (i in buckets.indices) buckets[i] += other.buckets[i]
        frameCount += other.frameCount
        jankCount += other.jankCount
        severeJankCount += other.severeJankCount
        if (other.maxMs > maxMs) maxMs = other.maxMs
    }

    /** 各桶计数快照（导出用；顺序 = [BUCKET_UPPER_RATIOS]）。 */
    fun bucketCounts(): List<Int> = buckets.toList()

    /**
     * 分位数（毫秒）。桶是粗粒度的，故给的是**保守上界估计**：
     * 取「累计计数首次覆盖第 ceil(p × frameCount) 帧」那个桶的**上边界**，并与 [maxMs] 取小；
     * 落在最后一桶（无上界）时直接返回 [maxMs]。零帧 → 0。
     *
     * 保守的意思是：它只会高估不会低估耗时 —— 用来判「有没有问题」时不会漏报。
     */
    fun percentileMs(p: Double): Double {
        if (frameCount == 0) return 0.0
        val target = ceil(p * frameCount).toInt().coerceIn(1, frameCount)
        var cumulative = 0
        for (i in buckets.indices) {
            cumulative += buckets[i]
            if (cumulative >= target) {
                val upperRatio = BUCKET_UPPER_RATIOS[i] ?: return maxMs
                return minOf(upperRatio * budgetMs, maxMs)
            }
        }
        return maxMs
    }

    companion object {
        /** 图纸 §9② 锁定：恰 8 桶，边界 0.5 / 0.8 / 1.0 / 1.5 / 2.0 / 3.0 / 5.0（帧预算倍数·左闭右开）。 */
        const val BUCKET_COUNT = 8

        /** 各桶的**上**边界（帧预算倍数）；末桶 `[5, ∞)` 无上界 → null。 */
        val BUCKET_UPPER_RATIOS: List<Double?> = listOf(0.5, 0.8, 1.0, 1.5, 2.0, 3.0, 5.0, null)

        /** 帧耗时 / 帧预算 → 桶号。左闭右开：恰等于边界值归**右**边那个桶。 */
        fun bucketIndexOf(ratio: Double): Int {
            for (i in 0 until BUCKET_COUNT - 1) {
                val upper = BUCKET_UPPER_RATIOS[i] ?: break
                if (ratio < upper) return i
            }
            return BUCKET_COUNT - 1
        }
    }
}
