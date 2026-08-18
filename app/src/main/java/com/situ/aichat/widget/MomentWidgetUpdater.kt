package com.situ.aichat.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 让最新动态（朋友圈）小组件重新渲染（13.9b）。小组件同进程直读 Room，只需在新帖到达 / 相对时间推进后 nudge 一次。
 * 由 [MomentWidgetSync]（新帖）与 [com.situ.aichat.work.WidgetRefreshWorker]（30 分定期兜底）调用。
 * 无已添加的小组件时 [updateAll] 安全无操作。
 */
@Singleton
class MomentWidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun refresh() {
        try {
            MomentFeedGlanceWidget().updateAll(context)
        } catch (_: Exception) {
            // 无小组件实例 / 系统拒绝时静默：小组件是可选锦上添花，绝不影响主流程。
        }
    }
}
