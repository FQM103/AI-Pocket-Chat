package com.situ.aichat.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.situ.aichat.widget.CharacterStatusWidgetUpdater
import com.situ.aichat.widget.MomentWidgetUpdater
import com.situ.aichat.widget.PetWidgetUpdater
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 桌面小组件定期刷新兜底（13.9，对抗 HyperOS 杀后台）。每 30 分钟 nudge 一次小组件重渲染——让「此刻」状态
 * 即使在 App 被杀、无事件驱动刷新的时段也能按当前时间翻新（小组件渲染时**现算**，本 worker 只负责「何时再渲染一次」）。
 *
 * 对齐 iOS PetWidget 的 30 分钟 timeline 节奏；纯本地（不调 LLM/网络）→ 无网也跑。
 * 即时刷新（数据变）见各 `*WidgetSync`；本 worker 是周期 catch-up。
 */
@HiltWorker
class WidgetRefreshWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val characterStatusUpdater: CharacterStatusWidgetUpdater,
    private val momentUpdater: MomentWidgetUpdater,
    private val petUpdater: PetWidgetUpdater,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            characterStatusUpdater.refresh()
            momentUpdater.refresh()
            // C1#3：宠物小组件同享 30 分周期兜底 nudge——配合 PetGlanceWidget 渲染时现算衰减/散步到点，
            // 让 App 被杀后桌面宠物心情/「散步中」也按当前时间翻新（仅触发重渲染，现算在 widget 内）。
            petUpdater.refresh()
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "小组件定期刷新失败: ${e.message}")
            Result.success() // 小组件刷新失败不重试（下个周期自然再来），不占退避配额
        }
    }

    companion object {
        private const val TAG = "WidgetRefreshWorker"

        /** 周期刷新唯一任务名。 */
        const val UNIQUE_PERIODIC = "widget_periodic_refresh"
    }
}
