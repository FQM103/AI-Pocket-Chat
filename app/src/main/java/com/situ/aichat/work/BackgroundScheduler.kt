package com.situ.aichat.work

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 后台任务调度封装(P5.0)。把 WorkManager 的「周期 / 一次性任务」收口成几个简单入口，
 * 后续(5.1 每日日程生成等)统一挂在这里，避免 WorkManager 细节散落各处。
 *
 * 纯本地、零 GMS——WorkManager 底层走 JobScheduler/AlarmManager，不依赖 Google Play 服务。
 * 国行 ROM(HyperOS 等)休眠杀后台靠「免电池优化 + 自启动白名单」兜底(见 [com.situ.aichat.ui.settings] 引导页)。
 * 任务失败默认指数退避重试(30s 起)。
 */
@Singleton
class BackgroundScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    /**
     * 排一个唯一的周期任务。
     *
     * @param uniqueName 唯一任务名；同名任务按 [existingPolicy] 处理(默认 KEEP=已存在则不重排)。
     * @param repeatInterval 周期(WorkManager 下限 15 分钟)。
     * @param requireNetwork true=仅在有网络时运行(日程生成等需联网的任务)。
     * @param flexInterval 弹性窗口；指定后任务在每个周期的末段 [flexInterval] 内择机运行。
     */
    fun <W : ListenableWorker> schedulePeriodic(
        uniqueName: String,
        workerClass: Class<W>,
        repeatInterval: Duration,
        requireNetwork: Boolean = true,
        flexInterval: Duration? = null,
        existingPolicy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP,
        inputData: Data? = null,
    ) {
        val builder = if (flexInterval != null) {
            PeriodicWorkRequest.Builder(workerClass, repeatInterval, flexInterval)
        } else {
            PeriodicWorkRequest.Builder(workerClass, repeatInterval)
        }
        if (inputData != null) builder.setInputData(inputData)
        val request = builder
            .setConstraints(constraints(requireNetwork))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, DEFAULT_BACKOFF)
            .build()
        workManager.enqueueUniquePeriodicWork(uniqueName, existingPolicy, request)
        Log.d(TAG, "周期任务已排入: $uniqueName 每 ${repeatInterval.toMinutes()} 分钟, 需网络=$requireNetwork")
    }

    /**
     * 排一个唯一的一次性任务。
     *
     * @param initialDelay 首次延迟；null=尽快运行。
     * @param existingPolicy 同名任务处理策略(默认 REPLACE=用新任务替换)。
     * @param inputData 传给 worker 的输入数据(如日记评论 worker 的 entryUuid)；null=无输入。
     */
    fun <W : ListenableWorker> scheduleOneShot(
        uniqueName: String,
        workerClass: Class<W>,
        initialDelay: Duration? = null,
        requireNetwork: Boolean = true,
        existingPolicy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
        inputData: Data? = null,
    ) {
        val builder = OneTimeWorkRequest.Builder(workerClass)
            .setConstraints(constraints(requireNetwork))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, DEFAULT_BACKOFF)
        if (initialDelay != null) builder.setInitialDelay(initialDelay)
        if (inputData != null) builder.setInputData(inputData)
        workManager.enqueueUniqueWork(uniqueName, existingPolicy, builder.build())
        Log.d(TAG, "一次性任务已排入: $uniqueName 延迟=${initialDelay?.seconds ?: 0}s, 需网络=$requireNetwork")
    }

    /** 取消指定唯一任务(周期或一次性均可)。 */
    fun cancel(uniqueName: String) {
        workManager.cancelUniqueWork(uniqueName)
        Log.d(TAG, "任务已取消: $uniqueName")
    }

    private fun constraints(requireNetwork: Boolean): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(if (requireNetwork) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED)
            .build()

    private companion object {
        const val TAG = "BackgroundScheduler"
        val DEFAULT_BACKOFF: Duration = Duration.ofSeconds(30)
    }
}
