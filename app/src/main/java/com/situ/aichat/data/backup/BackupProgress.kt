package com.situ.aichat.data.backup

/**
 * 备份导出/导入的确定性进度（15.2-P1·P1-7，安卓超越——iOS 全程不确定转圈无数值，
 * CharacterBackupView.swift:125-133 / CharacterBackupImportPreviewView.swift:147-150）。
 *
 * [done]/[total] 是**当前阶段内**计数（导出=段数/媒体数/拷贝 KB；导入=媒体数/角色数）；
 * 跨阶段的整体 0..1 由 [overallFraction] 映射。进度回调约定**只读、绝不抛**（导入的
 * [Stage.WRITE_DB] 在 Room 事务内回调，抛出会回滚整个导入）——UI 侧只做 StateFlow 赋值。
 */
data class BackupProgress(val stage: Stage, val done: Int, val total: Int) {
    enum class Stage {
        /** 导出：逐段收集结构化数据（逐角色 + 9 个全局段）。 */
        COLLECT,

        /** 导出：逐个媒体文件写入 zip（含跳过项，与 mediaPaths 总数对齐）。 */
        WRITE_MEDIA,

        /** 导出：cache 临时文件整体拷贝到 SAF 目标（KB 计）。 */
        COPY,

        /** 导入：zip 媒体字节重存到各 Store。 */
        RESTORE_MEDIA,

        /** 导入：事务内逐角色写库。 */
        WRITE_DB,
    }
}

/**
 * 阶段→整体 0..1 的权重映射（纯函数，可测）：导出 = COLLECT 0..0.3 → WRITE_MEDIA 0.3..0.9 → COPY 0.9..1；
 * 导入 = RESTORE_MEDIA 0..0.5 → WRITE_DB 0.5..1。total<=0（如纯文本备份无媒体）视为该阶段已完成；
 * done 越界钳到 [0,1]。权重为自定设计（iOS 无数值真值可反推，13.6 备份超越拍板背书）。
 */
internal fun overallFraction(p: BackupProgress): Float {
    val (start, end) = when (p.stage) {
        BackupProgress.Stage.COLLECT -> 0f to 0.3f
        BackupProgress.Stage.WRITE_MEDIA -> 0.3f to 0.9f
        BackupProgress.Stage.COPY -> 0.9f to 1f
        BackupProgress.Stage.RESTORE_MEDIA -> 0f to 0.5f
        BackupProgress.Stage.WRITE_DB -> 0.5f to 1f
    }
    val inner = if (p.total <= 0) 1f else (p.done.toFloat() / p.total).coerceIn(0f, 1f)
    return start + (end - start) * inner
}
