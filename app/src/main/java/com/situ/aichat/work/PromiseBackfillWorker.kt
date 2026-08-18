package com.situ.aichat.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.situ.aichat.promise.PromiseBackfillStore
import com.situ.aichat.promise.PromiseLedgerService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * 承诺账本历史回填一次性 worker（记忆改造一期·图纸 §3.11·照 [OpenLoopDueWorker] 样式）：把存量见面档案
 * `promisesJson` 里的约定注册进账本。已完成（[PromiseBackfillStore.done]）→ 直接 success；否则回填 → 置标记 → success；
 * 异常 → [androidx.work.ListenableWorker.Result.retry]（回填幂等·重跑靠注册端去重挡住）。
 *
 * 排程在 [com.situ.aichat.ui.AppViewModel.scheduleBackgroundWork]（一次性·KEEP·无网络约束）。设备本地标记、不进备份。
 */
@HiltWorker
class PromiseBackfillWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val ledger: PromiseLedgerService,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (PromiseBackfillStore.done(applicationContext)) return Result.success()
        return try {
            val count = ledger.backfillFromMeetingRows(System.currentTimeMillis())
            PromiseBackfillStore.setDone(applicationContext)
            Log.i(TAG, "承诺账本历史回填完成 count=$count")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "承诺账本历史回填失败(将重试): ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "PromiseBackfillWorker"

        /** 一次性唯一任务名（KEEP·已排过不重排）。 */
        const val UNIQUE_ONCE = "promise_backfill_once"
    }
}
