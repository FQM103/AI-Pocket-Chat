package com.situ.aichat.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.situ.aichat.offline.OfflineSummaryRetryCoordinator
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 线下见面摘要的**全局后台扫描兜底**（10.2d-3，对抗 HyperOS 杀后台）：每次跑
 * [OfflineSummaryRetryCoordinator.scanAndRetry]（扫所有 pending 退避重试，③冷启动 / ④回前台层）+
 * [OfflineSummaryRetryCoordinator.healOneFallbackIfDue]（24h 把已兜底简版升级回完整版，⑤自愈层）。
 *
 * **5 层兜底全保留**（坑 §4#3）：① finalize 即时 + ② 重进对话退避（均在 ChatViewModel）+ ③④⑤ 本 worker。
 * MIUI 可能永不调度后台 → 不能只靠本 worker，前台 ①② 是主力。
 *
 * 入队三路（[com.situ.aichat.ui.AppViewModel]）：冷启动一次性 + 回前台一次性 + 周期兜底。摘要要联网（LLM）→
 * requireNetwork=true（无网时跳过，避免白白消耗退避次数；重连后前台 ①② 兜上）。
 */
@HiltWorker
class OfflineSummaryScanWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val coordinator: OfflineSummaryRetryCoordinator,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            coordinator.scanAndRetry()
            coordinator.healOneFallbackIfDue()
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "见面摘要扫描失败: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "OfflineSummaryWorker"

        /** 周期扫描唯一任务名。 */
        const val UNIQUE_PERIODIC = "offline_summary_scan"

        /** 冷启动 / 回前台一次性扫描唯一任务名。 */
        const val UNIQUE_ENSURE = "offline_summary_scan_ensure"
    }
}
