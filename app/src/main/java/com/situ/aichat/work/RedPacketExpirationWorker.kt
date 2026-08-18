package com.situ.aichat.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.situ.aichat.redpacket.RedPacketExpirationScanService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 红包过期扫描周期兜底（P9.3b，对抗 HyperOS 杀后台）。每隔数小时跑一次 [RedPacketExpirationScanService.scan]：
 * 过期退回（24h）+ 22h 预警催拆。过期不紧迫（钱锁托管账户安全），预警的精确触发靠精确闹钟，本 worker 是 catch-up 兜底。
 *
 * 无网也跑（过期/预警纯本地，不需 API）。回前台触发见 [com.situ.aichat.ui.AppViewModel.onAppForeground]。
 */
@HiltWorker
class RedPacketExpirationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val scanService: RedPacketExpirationScanService,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            scanService.scan()
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "红包过期扫描失败: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "RedPacketExpWorker"

        /** 周期扫描唯一任务名。 */
        const val UNIQUE_PERIODIC = "red_packet_expiration_scan"
    }
}
