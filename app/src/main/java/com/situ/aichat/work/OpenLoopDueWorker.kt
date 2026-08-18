package com.situ.aichat.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.situ.aichat.openloop.OpenLoopDueMessenger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 「惦记的事」到期一次性延迟 worker（活人感一期 P2·图纸 §3.2）：某条有 dueAt 的 loop 落库时排一次
 * （延迟 = dueAt−now·[BackgroundScheduler.scheduleOneShot]·existingPolicy=KEEP），到点驱动
 * [OpenLoopDueMessenger.deliver] 走五道守卫 + LLM 生成 + 落库 + 通知。
 *
 * doWork 恒 [androidx.work.ListenableWorker.Result.success]——守卫不满足 / 生成失败一律静默不重试
 * （拍板：LLM 失败静默不发）。requireNetwork=true 由 scheduler 加约束；WorkManager 持久化，进程死亡重启自恢复。
 */
@HiltWorker
class OpenLoopDueWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val messenger: OpenLoopDueMessenger,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val loopUuid = inputData.getString(KEY_LOOP_UUID) ?: return Result.success()
        runCatching { messenger.deliver(loopUuid) }
            .onFailure { Log.w(TAG, "惦记回连消息生成失败(静默不重试): ${it.message}") }
        return Result.success() // 恒 success：守卫/生成失败均静默不重试
    }

    companion object {
        private const val TAG = "OpenLoopDueWorker"

        const val KEY_LOOP_UUID = "loopUuid"

        /** 排程唯一任务名（每 loop 一个·existingPolicy=KEEP 不重复入队）。 */
        fun uniqueName(loopUuid: String): String = "open_loop_due_$loopUuid"

        /** worker 输入（loop uuid）。 */
        fun inputData(loopUuid: String): Data = Data.Builder().putString(KEY_LOOP_UUID, loopUuid).build()
    }
}
