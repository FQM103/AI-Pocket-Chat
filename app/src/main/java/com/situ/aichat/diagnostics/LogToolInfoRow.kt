package com.situ.aichat.diagnostics

/**
 * 一键去隐私·工具遥测轻投影（复核 R1·2026-07-17）：[com.situ.aichat.data.local.dao.LogDao.toolInfoRows]
 * 的行类型——只取 id + toolInfoJson，供 [ContextLogService.purgeSensitiveText] 按写侧同一口径
 * （[LogToolInfo.sanitized]）剥参数预览后列级回写。字段名与 `log_entries` 列名一致（Room 按名映射）。
 */
data class LogToolInfoRow(
    val id: Long,
    val toolInfoJson: String,
)
