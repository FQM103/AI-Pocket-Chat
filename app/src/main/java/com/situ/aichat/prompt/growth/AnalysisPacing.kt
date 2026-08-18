package com.situ.aichat.prompt.growth

/**
 * 活人感一期 P3 · 成长曲线「前快后慢」阶梯（纯函数·全 `internal` 便于单测）。
 *
 * 三处「30 轮才首次分析」的固定门槛改为前快后慢：新鲜期让用户尽早看到关系/成长/记忆的变化，之后回落到
 * 用户设置的常规节奏。用户设置值恒为**硬上限**（`minOf`）——把 interval 调到 5，首次门槛也不会超过 5。
 * 其余守卫（1h 最小间隔、双轨 30min/50 轮、沉浸/见面跳过）在各触发器**逐字节不动**，本对象只替换「首次门槛」。
 */
internal object AnalysisPacing {

    /** 成长分析生效门槛：首次 10 轮、第二次 25 轮、之后用户设置值（默认 30）。userInterval 恒为上限内取 min。 */
    fun growthInterval(totalAnalysisCount: Int, userInterval: Int): Int = when {
        totalAnalysisCount <= 0 -> minOf(10, userInterval)
        totalAnalysisCount == 1 -> minOf(25, userInterval)
        else -> userInterval
    }

    /** 结构化记忆生效门槛：从未抽取过（lastExtractionDate==null）→ min(10, interval)；否则 interval。 */
    fun structuredInterval(lastExtractionDate: Long?, userInterval: Int): Int =
        if (lastExtractionDate == null) minOf(10, userInterval) else userInterval

    /** 关系评估链式触发门槛：从未评估过（lastRelationshipAnalysisDate==null）→ 10；否则 30（现状值）。 */
    fun relationshipChainThreshold(lastRelationshipAnalysisDate: Long?): Int =
        if (lastRelationshipAnalysisDate == null) 10 else 30
}
