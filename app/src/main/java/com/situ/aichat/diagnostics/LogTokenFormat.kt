package com.situ.aichat.diagnostics

import java.util.Locale

/**
 * 上下文日志的 token 数 / 占比展示格式化（批 D·纯函数·移植 iOS `LogDetailView.formatTokenCount` +
 * `LogContextSegmentsView.tokenDisplayText`/`percentageText`）。
 *
 * 用 [Locale.ROOT] 固定小数点为「.」（对齐 iOS `String(format:)` 的 C-locale，避免某些语言环境出逗号小数点
 * 导致单测漂移）。展示层（D-3 UI）直接调这里，保证 token 文案口径单一可测。
 */
object LogTokenFormat {

    /** ≥1000 显 `x.xk`，否则原数（1:1 iOS）。 */
    fun compact(count: Int): String =
        if (count >= 1000) String.format(Locale.ROOT, "%.1fk", count / 1000.0) else count.toString()

    /** 估算值加 `≈` 前缀（1:1 iOS `isTokenEstimated` 展示）。 */
    fun withEstimatePrefix(count: Int, isEstimated: Boolean): String =
        if (isEstimated) "≈${compact(count)}" else compact(count)

    /** 某段占总 token 的百分比文案：total≤0→`0%`；<1%→`<1%`；否则四舍五入整数 `N%`（1:1 iOS）。 */
    fun percent(part: Int, total: Int): String {
        if (total <= 0) return "0%"
        val pct = part.toDouble() / total.toDouble() * 100.0
        return if (pct < 1.0) "<1%" else String.format(Locale.ROOT, "%.0f%%", pct)
    }
}
