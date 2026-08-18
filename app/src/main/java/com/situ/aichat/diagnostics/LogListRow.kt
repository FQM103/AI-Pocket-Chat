package com.situ.aichat.diagnostics

/**
 * 上下文日志**列表轻投影**（D-3 打磨·2026-07-16）：[com.situ.aichat.data.local.dao.LogDao.recent] 的行类型，
 * 只含列表卡 + 缓存汇总 + 失败率告警需要的元数据列。
 *
 * 为什么不用整实体：列表 Flow 一次抱最新 500 条，而 `fullContext`/`responseContent` 在「全量记录」
 * （2026-07-16 取消落库截断）后单条可达数十万字——SELECT * 会让每次列表刷新搬运几十 MB 大文本并压迫
 * CursorWindow；正文只有详情/全文页需要，按 id 单取（[com.situ.aichat.data.local.dao.LogDao.getById]）。
 *
 * 字段名与 `log_entries` 列名一一对应（Room 按名映射投影）。
 */
data class LogListRow(
    val id: Long,
    val timestampMillis: Long,
    val characterName: String = "",
    val modelName: String = "",
    val isSuccess: Boolean = true,
    val source: String = LogSource.CHAT,
    val messageCount: Int = 0,
    val durationMillis: Long? = null,
    val errorMessage: String? = null,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val cacheHitTokens: Int = 0,
    val cacheMissTokens: Int = 0,
    val isTokenEstimated: Boolean = true,
)
