package com.situ.aichat.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.situ.aichat.offline.OfflineAfterglowService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 见面后「余温消息」一次性延迟 worker（梦剧场 B 部·涟漪①·图纸 §3.10）：见面结束成功分支排一次（延迟
 * 135–225 分钟·[com.situ.aichat.work.BackgroundScheduler.scheduleOneShot]），到点驱动
 * [OfflineAfterglowService.maybeGenerate] 走四道守卫 + LLM 生成 + 落库 + 通知。
 *
 * **非加急**（仿 [com.situ.aichat.world.notify.WorldNotifyWorker] 但去 getForegroundInfo/setExpedited）：余温是
 * 低优先延迟消息，无需短时前台服务；requireNetwork=true 由 scheduler 加约束。doWork 恒
 * [androidx.work.ListenableWorker.Result.success]——守卫不满足/生成失败一律静默不重试（§3.10 拍板：不发模板兜底）。
 */
@HiltWorker
class OfflineAfterglowWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val service: OfflineAfterglowService,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val conversationUuid = inputData.getString(KEY_CONVERSATION_UUID) ?: return Result.success()
        val characterUuid = inputData.getString(KEY_CHARACTER_UUID) ?: return Result.success()
        val sessionId = inputData.getString(KEY_SESSION_ID) ?: return Result.success()
        runCatching { service.maybeGenerate(conversationUuid, characterUuid, sessionId) }
            .onFailure { Log.w(TAG, "见面余温消息生成失败(静默不重试): ${it.message}") }
        return Result.success() // 恒 success：守卫/生成失败均静默不重试
    }

    companion object {
        private const val TAG = "OfflineAfterglowWorker"

        const val KEY_CONVERSATION_UUID = "conversationUuid"
        const val KEY_CHARACTER_UUID = "characterUuid"
        const val KEY_SESSION_ID = "sessionId"

        /** 排程唯一任务名前缀（同 session 不重复入队·existingPolicy=KEEP）。 */
        fun uniqueName(sessionId: String): String = "offline_afterglow_$sessionId"
    }
}
