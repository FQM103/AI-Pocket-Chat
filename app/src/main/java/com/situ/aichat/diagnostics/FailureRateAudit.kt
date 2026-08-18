package com.situ.aichat.diagnostics

/**
 * LLM 调用失败率审计（P15·P0-7）。1:1 移植 iOS `LogService.computeFailureRateAlerts`
 * （LogService.swift:126-162）：扫最近 24h 的调用记录，按来源(source)分组算失败率，超阈值则告警。
 *
 * 数据源（批 D 起已接真，本段曾记「暂无数据源」现更新）：`log_entries` 表——启动期
 * [ContextLogService.auditRecentFailureRates] 经 [com.situ.aichat.data.local.dao.LogDao.recordsSince]
 * 喂全表 24h 投影打 Logcat 告警；D-3 打磨（2026-07-16）再把**同一纯函数同阈值**亮到日志列表页告警条
 * （ContextLogViewModel 从列表轻投影现算，点击跳「失败」筛选）。两处消费共用本处阈值，改动即同步生效。
 */
data class FailureRateAlert(
    val source: String,
    val failures: Int,
    val total: Int,
    val percent: Int,
)

/** 一条调用记录（[com.situ.aichat.diagnostics.FailureRateAudit] 的输入；对齐 iOS `LogEntry` 的相关字段子集）。 */
data class CallLogRecord(
    val source: String,
    val isSuccess: Boolean,
    val timestampMillis: Long,
)

object FailureRateAudit {

    /** 24 小时窗口（毫秒）。iOS 用 `now.addingTimeInterval(-86400)`（秒）。 */
    const val WINDOW_MILLIS = 86_400_000L

    /**
     * 计算失败率告警（纯函数，单测覆盖）。1:1 对齐 iOS `LogService.computeFailureRateAlerts`：
     * 只看 `timestampMillis >= nowMillis - 24h`（含边界）的记录，按 [CallLogRecord.source] 分组统计成功/失败数；
     * 来源按升序遍历；对每个来源：`total >= minTotal` 且 `failures >= minFailures` 且 `失败率 >= failureThreshold` 才告警。
     * 三个阈值都是 `>=`（含边界）。`percent` 用整数除 `failures * 100 / total`（= iOS `Int(rate*100)` 截断，避开浮点漂移）。
     */
    fun computeFailureRateAlerts(
        entries: List<CallLogRecord>,
        nowMillis: Long,
        failureThreshold: Double = 0.5,
        minFailures: Int = 3,
        minTotal: Int = 3,
    ): List<FailureRateAlert> {
        val dayAgo = nowMillis - WINDOW_MILLIS
        // 分组：source -> (success, failure)
        val stats = LinkedHashMap<String, IntArray>() // [0]=success, [1]=failure
        for (entry in entries) {
            if (entry.timestampMillis < dayAgo) continue
            val counts = stats.getOrPut(entry.source) { IntArray(2) }
            if (entry.isSuccess) counts[0]++ else counts[1]++
        }
        val alerts = mutableListOf<FailureRateAlert>()
        for (source in stats.keys.sorted()) {
            val counts = stats.getValue(source)
            val failures = counts[1]
            val total = counts[0] + counts[1]
            if (total < minTotal || failures < minFailures) continue
            val rate = failures.toDouble() / total
            if (rate < failureThreshold) continue
            alerts.add(FailureRateAlert(source, failures, total, failures * 100 / total))
        }
        return alerts
    }
}
