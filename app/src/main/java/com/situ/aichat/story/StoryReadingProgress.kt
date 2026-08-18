package com.situ.aichat.story

import kotlin.math.ceil
import kotlin.math.floor

/**
 * 阅读进度纯计算（ST7d·契约 §6.4 底部进度胶囊「62% · 还剩 X 分钟」）。
 *
 * 模型 = **视口底边**（2026-08-03 修复「滚到底仍 20%」：章末建议卡/快评/选择区/推进区是一串矮项，
 * 旧「首个可见项」模型滚到底时首项还停在前段，进度永远不满格）。阅读位置 = 视口底边扫过哪：
 * [percent] 按（最后可见项下标 + 该项已见比例）/ 总项数；[remainingMinutes] 按「本章剩余字数 ÷ 阅读速度」
 * 端上估算（无网络、无隐私成本）。纯函数，单测反推；layoutInfo 取参见 ui.story.StoryReaderProgressBridge。
 */
object StoryReadingProgress {
    /** 中文阅读速度估算（字 / 分钟·端上确定性估值）。 */
    const val CHARS_PER_MINUTE = 400

    /**
     * 已读百分比 0..100：(lastVisibleIndex + lastVisibleFraction) / totalItems，**向下取整**——
     * 未到底绝不虚报 100（到底时几何上 fraction 精确 = 1，无浮点债）。空布局 / 无项 → 0。
     */
    fun percent(lastVisibleIndex: Int, lastVisibleFraction: Float, totalItems: Int): Int {
        if (totalItems <= 0) return 0
        val idx = lastVisibleIndex.coerceIn(0, totalItems - 1)
        val frac = if (lastVisibleFraction.isNaN()) 1f else lastVisibleFraction.coerceIn(0f, 1f)
        return floor((idx + frac) / totalItems * 100f).toInt().coerceIn(0, 100)
    }

    /**
     * 已读完的正文块数（供剩余字数估算）：视口底边**完全越过**的最后一项 [lastFullyPassedIndex]
     * 换算到正文块下标空间——正文块在 LazyColumn 里从 [bodyStartIndex] 起（封面恒 0；「上回说到」在则 2、否则 1）。
     */
    fun consumedBodyBlocks(lastFullyPassedIndex: Int, bodyStartIndex: Int, totalBodyBlocks: Int): Int =
        (lastFullyPassedIndex - bodyStartIndex + 1).coerceIn(0, totalBodyBlocks)

    /** 剩余阅读分钟：还有字未读则向上取整且至少 1；0 字（读到末尾）→ 0。 */
    fun remainingMinutes(remainingChars: Int): Int {
        if (remainingChars <= 0) return 0
        return ceil(remainingChars.toDouble() / CHARS_PER_MINUTE).toInt().coerceAtLeast(1)
    }
}
