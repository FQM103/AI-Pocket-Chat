package com.situ.aichat.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.situ.aichat.prompt.diary.DiaryGenerationCoordinator
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 后台日记生成任务（M07 7.1.2）。委托 [DiaryGenerationCoordinator.runDiaryGeneration]（先补昨天、再查今天）。
 * 由 [BackgroundScheduler] 排程：回前台一次性（KEEP，[UNIQUE_ENSURE]）为主 + 每日周期（[UNIQUE_DAILY]）兜底，
 * 对齐 iOS「回前台触发」并对抗国产 ROM 杀后台（决策③）。时间门槛由协调器内部判定（未到设定时刻即 no-op）。
 */
@HiltWorker
class DiaryGenerationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val coordinator: DiaryGenerationCoordinator,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        coordinator.runDiaryGeneration()
        Result.success()
    } catch (e: Exception) {
        Log.w(TAG, "日记生成 worker 异常，将重试", e)
        Result.retry()
    }

    companion object {
        const val TAG = "DiaryWorker"
        const val UNIQUE_DAILY = "diary_daily_generation"
        const val UNIQUE_ENSURE = "diary_ensure_today"
    }
}
