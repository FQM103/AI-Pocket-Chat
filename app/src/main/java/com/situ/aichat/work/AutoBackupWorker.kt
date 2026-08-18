package com.situ.aichat.work

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.situ.aichat.R
import com.situ.aichat.data.backup.BackupService
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.notification.BackupNotifier
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 定时本地自动备份 worker（13.6c；安卓超越 iOS——iOS 备份纯手动）。约每日跑一次：到周（距上次含媒体备份 ≥7 天，
 * 或从未含媒体）做**含媒体**备份，否则做**文本**备份。写到用户选定的持久 SAF 目录 → 轮转（文本留 [KEEP_TEXT] /
 * 媒体留 [KEEP_MEDIA]）→ 完成发系统通知（成功+失败都发，用户拍板）。
 *
 * 流式导出（[BackupService.exportTo] 13.6c-1）→ 后台低内存预算也不 OOM。HyperOS 激进杀后台时此 job 可能不准时
 * 甚至不触发 → 需用户给电池白名单 + 自启动（设置页可靠性卡引导，复用 [BackgroundReliability]）。
 *
 * 未启用 / 未选目录 → 直接 success（无事可做）。目录持久授权丢失（撤销/换机/清数据）→ 自动关闭 + 通知，避免每周期
 * 重试刷失败通知。导出失败 → 通知 + retry（WorkManager 指数退避重试）。
 */
@HiltWorker
class AutoBackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val backupService: BackupService,
    private val settingsRepo: SettingsRepository,
    private val backgroundScheduler: BackgroundScheduler,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val cfg = settingsRepo.getAutoBackupConfig()
        if (!cfg.enabled || cfg.treeUri.isBlank()) return Result.success()
        val treeUri = runCatching { Uri.parse(cfg.treeUri) }.getOrNull() ?: return Result.success()

        if (!AutoBackupFolder.hasPersistedPermission(applicationContext, treeUri)) {
            disableAndNotifyFolderLost()
            return Result.success()
        }

        val now = System.currentTimeMillis()
        val includeMedia = cfg.lastMediaBackupAt == 0L || now - cfg.lastMediaBackupAt >= WEEK_MS
        // E2#2：开跑先扫掉上次写到一半留下的 .part 残留临时文件（进程死/断电）。
        runCatching { AutoBackupFolder.cleanTempFiles(applicationContext, treeUri) }
        return try {
            AutoBackupFolder.writeBackup(applicationContext, treeUri, AutoBackupFolder.fileName(now, includeMedia)) { out ->
                backupService.exportTo(out, includeMedia)
            }
            settingsRepo.setAutoBackupLastRun(now, if (includeMedia) now else null)
            AutoBackupFolder.prune(applicationContext, treeUri, keepText = KEEP_TEXT, keepMedia = KEEP_MEDIA)
            BackupNotifier.postSuccess(applicationContext, includeMedia)
            Result.success()
        } catch (e: BackupFolderUnavailableException) {
            // 目录结构性失效（被删/移/无写权限，授权却仍持久）→ 与授权丢失同款：自动关闭 + 提示重选，不无限退避重试。
            Log.w(TAG, "备份目录不可用，自动关闭", e)
            disableAndNotifyFolderLost()
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "自动备份失败", e)
            BackupNotifier.postFailure(applicationContext, e.message ?: "")
            Result.retry()
        }
    }

    /** 目录不可用 → 翻 enabled=false **并取消周期任务**（与 ViewModel 的 disable 路径对齐，杜绝每日空唤醒）+ 通知。 */
    private suspend fun disableAndNotifyFolderLost() {
        settingsRepo.setAutoBackupEnabled(false)
        backgroundScheduler.cancel(UNIQUE_PERIODIC)
        BackupNotifier.postFailure(
            applicationContext,
            applicationContext.getString(R.string.auto_backup_notif_folder_lost),
        )
    }

    companion object {
        private const val TAG = "AutoBackup"

        /** 周期自动备份唯一任务名（enable→schedulePeriodic / disable→cancel）。 */
        const val UNIQUE_PERIODIC = "auto_backup_periodic"

        private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000

        /** 轮转保留份数（用户拍板：文本留 7 / 媒体留 4）。 */
        const val KEEP_TEXT = 7
        const val KEEP_MEDIA = 4
    }
}
