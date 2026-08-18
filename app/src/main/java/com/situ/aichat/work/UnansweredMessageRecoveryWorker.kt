package com.situ.aichat.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.situ.aichat.recovery.UnansweredMessageRecoveryService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 未答恢复的**后台扫描兜底**（10.2g，对抗 HyperOS 杀后台）：跑 [UnansweredMessageRecoveryService.recoverIfNeeded]
 * （扫所有「最后一条是用户消息」的对话、串行补发未回复）。
 *
 * 入队两路（[com.situ.aichat.ui.AppViewModel]）：冷启动一次性 + 回前台一次性（对齐 iOS 冷启动 + scenePhase active
 * 触发，无周期——避免周期性后台烧 LLM；前台两路已覆盖）。恢复要联网（LLM）→ requireNetwork=true。
 */
@HiltWorker
class UnansweredMessageRecoveryWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val service: UnansweredMessageRecoveryService,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            service.recoverIfNeeded()
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "未答恢复扫描失败: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "UnansweredRecoveryWorker"

        /** 冷启动 / 回前台一次性扫描唯一任务名。 */
        const val UNIQUE_ENSURE = "unanswered_recovery_scan_ensure"
    }
}
