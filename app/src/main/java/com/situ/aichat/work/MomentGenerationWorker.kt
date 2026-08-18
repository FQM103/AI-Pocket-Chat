package com.situ.aichat.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.situ.aichat.moments.MomentGenerationService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 后台朋友圈自动发帖任务（P7.2.3）。委托 [MomentGenerationService.checkAndGeneratePosts]。
 * 由 [BackgroundScheduler] 排程：回前台一次性（KEEP，[UNIQUE_ENSURE]）为主 + 每 15min 周期（[UNIQUE_DAILY]）
 * 兜底，对齐 iOS「回前台触发」并对抗国产 ROM 杀后台（决策③）。所有频率/冷却/睡眠守卫由 Service 内部判定。
 *
 * 13.7e：仅**周期** [UNIQUE_DAILY] 这一路（用户不在 app 时跑）经 inputData [KEY_NOTIFY_NEW_POST]=true 让新帖推
 * 「X 发了新动态」系统通知；回前台 [UNIQUE_ENSURE] 补发那路不传（默认 false，用户马上会在 feed 看到，不打扰）。
 */
@HiltWorker
class MomentGenerationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val service: MomentGenerationService,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        service.checkAndGeneratePosts(notifyOnNewPost = inputData.getBoolean(KEY_NOTIFY_NEW_POST, false))
        Result.success()
    } catch (e: Exception) {
        Log.w(TAG, "朋友圈生成 worker 异常，将重试", e)
        Result.retry()
    }

    companion object {
        const val TAG = "MomentWorker"
        const val UNIQUE_DAILY = "moment_periodic_generation"
        const val UNIQUE_ENSURE = "moment_ensure"

        /** 13.7e：true = 本轮新帖推「X 发了新动态」系统通知（仅周期后台路传 true；回前台补发路默认 false）。 */
        const val KEY_NOTIFY_NEW_POST = "notify_new_post"
    }
}
