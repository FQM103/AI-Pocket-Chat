package com.situ.aichat.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.situ.aichat.prompt.diary.DiaryCommentService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 延迟执行日记角色评论生成（M07 7.1.3）。由 [DiaryCommentService.scheduleComments] 排程（唯一名含 entryUuid，
 * 删除日记时取消）。entryUuid 经 inputData 传入；委托 [DiaryCommentService.generateCommentsForEntry]
 * （内部 refetch entry，已删 → 优雅早退）。
 */
@HiltWorker
class DiaryCommentWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val commentService: DiaryCommentService,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        val entryUuid = inputData.getString(KEY_ENTRY_UUID)
        val rootCommentId = inputData.getString(KEY_ROOT_COMMENT_ID)
        when {
            entryUuid.isNullOrEmpty() -> Log.w(TAG, "缺 entryUuid，跳过")
            // R3 评论回复一轮：带根评论 id = 回应任务（DiaryCommentService.scheduleReply 排程）。
            !rootCommentId.isNullOrEmpty() -> commentService.generateReplyForComment(entryUuid, rootCommentId)
            else -> commentService.generateCommentsForEntry(entryUuid)
        }
        Result.success()
    } catch (e: Exception) {
        Log.w(TAG, "日记评论 worker 异常，将重试", e)
        Result.retry()
    }

    companion object {
        const val TAG = "DiaryCommentWorker"
        const val KEY_ENTRY_UUID = "entry_uuid"
        const val KEY_ROOT_COMMENT_ID = "root_comment_id"
    }
}
