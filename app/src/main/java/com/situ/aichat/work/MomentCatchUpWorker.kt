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
 * 欠帖补发任务（M06 7.2.5）。角色发帖时在睡 → 记欠帖；醒后聊天触发
 * [MomentGenerationService.triggerCatchUpPostIfNeeded] 排本 worker（唯一名含 characterUuid、KEEP，
 * initialDelay 40~80s 模拟「回完消息顺手发」）。characterUuid 经 inputData 传入，委托
 * [MomentGenerationService.catchUpPost]（内部守 频率/今日上限/API/角色存活，欠帖标记已在排程时清除）。
 *
 * **优于 iOS**：iOS 用 in-process `Task` 延迟，app 被杀即丢；WorkManager 跨进程死亡仍存活（与日记评论同思路）。
 */
@HiltWorker
class MomentCatchUpWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val service: MomentGenerationService,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        val characterUuid = inputData.getString(KEY_CHARACTER_UUID)
        if (characterUuid.isNullOrEmpty()) {
            Log.w(TAG, "缺 characterUuid，跳过")
            Result.success()
        } else {
            service.catchUpPost(characterUuid)
            Result.success()
        }
    } catch (e: Exception) {
        Log.w(TAG, "欠帖补发 worker 异常，将重试", e)
        Result.retry()
    }

    companion object {
        const val TAG = "MomentCatchUp"
        const val KEY_CHARACTER_UUID = "characterUuid"
        private const val UNIQUE_PREFIX = "moment_catchup_"

        fun uniqueName(characterUuid: String): String = "$UNIQUE_PREFIX$characterUuid"
    }
}
