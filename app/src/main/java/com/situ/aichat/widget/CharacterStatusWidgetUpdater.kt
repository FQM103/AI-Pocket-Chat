package com.situ.aichat.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 让角色「此刻」状态小组件重新渲染（13.9a）。小组件同进程直读 Room，只需在主对话/角色/日程时间推进后 nudge 一次重渲染。
 * 由 [CharacterStatusWidgetSync]（数据变）与 [com.situ.aichat.work.WidgetRefreshWorker]（30 分定期兜底）调用。
 * 无已添加的小组件时 [updateAll] 安全无操作。
 */
@Singleton
class CharacterStatusWidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun refresh() {
        try {
            CharacterStatusGlanceWidget().updateAll(context)
        } catch (_: Exception) {
            // 无小组件实例 / 系统拒绝时静默：小组件是可选锦上添花，绝不影响主流程。
        }
    }
}
