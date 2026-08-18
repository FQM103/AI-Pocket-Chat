package com.situ.aichat.data.model

/**
 * 定时自动备份的**设备本地**配置（13.6c）。
 *
 * **刻意不进 [AppSettings]、不进备份文件**：[treeUri] 是本机 SAF 持久授权（换机/重装即失效）、[enabled] 与
 * lastRun 是本机运行状态——随备份还原到别的设备没有任何意义（同 API 密钥被排除的道理）。故单独建模、单独持久化。
 */
data class AutoBackupConfig(
    val enabled: Boolean = false,
    /** 用户选定的备份文件夹 SAF tree URI（字符串；空 = 未选目录）。 */
    val treeUri: String = "",
    /** 上次成功备份时间（epoch millis；0 = 从未）。 */
    val lastBackupAt: Long = 0L,
    /** 上次「含媒体」备份时间（epoch millis；决定本次是否到周做含媒体；0 = 从未）。 */
    val lastMediaBackupAt: Long = 0L,
)
