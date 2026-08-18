package com.situ.aichat.diagnostics

/**
 * 上下文日志容量轮转算法（批 D·纯函数·移植 iOS `LogService.trimOldLogs` 的算术部分）。铁规：容量上限自动轮转。
 *
 * 编排（在记录器/D-2 里）：`overflow = overflow(count, retention)`；`overflow>0` 时取**升序第 (overflow-1) 条**的
 * timestamp 作分界、删 `timestamp <= 分界`（批量删代替逐条，1:1 iOS）。本类只管 overflow 数学，便于独立单测。
 */
object LogRetention {

    /**
     * 待删条数 = `max(0, total - retention)`。[retention] 应为已 sanitize 的有效值
     * （见 [com.situ.aichat.data.model.AppSettings.sanitizedLogRetentionCount]，>0 否则回退默认 100）。
     */
    fun overflow(total: Int, retention: Int): Int = (total - retention).coerceAtLeast(0)

    /** 分界条在升序结果里的偏移 = `overflow - 1`（调用方保证 overflow>0 才取）。 */
    fun cutoffOffset(overflow: Int): Int = overflow - 1
}
